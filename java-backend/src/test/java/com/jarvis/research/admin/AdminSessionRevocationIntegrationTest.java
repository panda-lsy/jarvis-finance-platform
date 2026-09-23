package com.jarvis.research.admin;

import com.jarvis.research.audit.AuditEvent;
import com.jarvis.research.audit.AuditEventRepository;
import com.jarvis.research.security.JwtAuthFilter;
import com.jarvis.research.security.JwtUtil;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-session-revoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jarvis.jwt.secret=integration-test-jwt-secret-key-at-least-32-bytes",
        "jarvis.python-service.enabled=false",
        "jarvis.auth.require-email-verification=false",
        "jarvis.risk.poll-interval-ms=3600000"
})
class AdminSessionRevocationIntegrationTest {

    @Autowired private AdminService adminService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revocationPersistsAuditAndRejectsThePreviouslyValidJwt() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User administrator = userRepository.saveAndFlush(User.builder()
                .email("admin-" + suffix + "@example.test")
                .passwordHash("test-hash")
                .role("ADMIN")
                .enabled(true)
                .build());
        User target = userRepository.saveAndFlush(User.builder()
                .email("target-" + suffix + "@example.test")
                .passwordHash("test-hash")
                .role("USER")
                .enabled(true)
                .build());
        String previouslyValidToken = jwtUtil.generateToken(
                target.getId(), target.getEmail(), target.getCredentialVersion());

        passThroughFilter(previouslyValidToken);
        assertEquals(target.getId(), SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        SecurityContextHolder.clearContext();

        adminService.revokeSessions(administrator.getId(), target.getId(), "安全核查后强制重新登录", "127.0.0.1");

        assertEquals(1, userRepository.findById(target.getId()).orElseThrow().getCredentialVersion());
        List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(
                administrator.getId(), PageRequest.of(0, 10));
        AuditEvent event = events.stream()
                .filter(candidate -> "ADMIN_USER_SESSIONS_REVOKE".equals(candidate.getAction()))
                .findFirst()
                .orElseThrow();
        assertEquals("user:" + target.getId(), event.getTarget());
        assertTrue(event.getDetail().contains("before=credentialVersion:0"));
        assertTrue(event.getDetail().contains("after=credentialVersion:1"));
        assertTrue(event.getDetail().contains("reason=安全核查后强制重新登录"));
        assertTrue(adminService.userAudit(target.getId(), 50).stream()
                .anyMatch(targetEvent -> event.getId().equals(targetEvent.getId())),
                "目标用户的审计页必须展示针对该账户的管理员操作");

        passThroughFilter(previouslyValidToken);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private void passThroughFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {};
        jwtAuthFilter.doFilter(request, response, chain);
    }
}
