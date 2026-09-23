package com.jarvis.research.admin;

import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.JwtAuthFilter;
import com.jarvis.research.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import({com.jarvis.research.security.SecurityConfig.class, AdminControllerSecurityTest.TestBeans.class})
class AdminControllerSecurityTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        JarvisProperties jarvisProperties() {
            return new JarvisProperties();
        }

        @Bean
        JwtAuthFilter jwtAuthFilter(JarvisProperties properties) {
            return new JwtAuthFilter(mock(JwtUtil.class), properties);
        }
    }

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @MockBean private AdminService adminService;

    @Test
    void anonymousAndOrdinaryUsersCannotReadAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/users")
                        .with(authentication(authFor(72L, "USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRevokeSessionsOnlyWithAReasonAndCsrf() throws Exception {
        when(adminService.revokeSessions(9L, 72L, "security review", "127.0.0.1"))
                .thenReturn(Map.of("userId", 72L, "sessionsRevoked", true));

        mockMvc.perform(post("/api/admin/users/72/sessions/revoke")
                        .with(authentication(authFor(9L, "ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"security review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionsRevoked").value(true));

        verify(adminService).revokeSessions(9L, 72L, "security review", "127.0.0.1");
    }

    @Test
    void adminSessionRevocationRequiresAuditReason() throws Exception {
        mockMvc.perform(post("/api/admin/users/72/sessions/revoke")
                        .with(authentication(authFor(9L, "ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupDeletionKeepsItsRequiredAuditReasonInTheRequestBody() throws Exception {
        mockMvc.perform(delete("/api/admin/groups/12")
                        .with(authentication(authFor(9L, "ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate group\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(adminService).deleteGroup(9L, 12L, "duplicate group", "127.0.0.1");
    }

    @Test
    void groupDeletionRequiresAnAuditReason() throws Exception {
        mockMvc.perform(delete("/api/admin/groups/12")
                        .with(authentication(authFor(9L, "ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    private static UsernamePasswordAuthenticationToken authFor(Long userId, String role) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
