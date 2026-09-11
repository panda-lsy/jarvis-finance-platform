package com.jarvis.research.controller;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.AuthService;
import com.jarvis.research.security.GitHubOAuthService;
import com.jarvis.research.security.JwtAuthFilter;
import com.jarvis.research.security.JwtUtil;
import com.jarvis.research.security.SecurityConfig;
import com.jarvis.research.service.AuthRateLimitService;
import com.jarvis.research.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthCsrfSecurityTest.TestBeans.class})
class AuthCsrfSecurityTest {

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

    @MockBean private AuthService authService;
    @MockBean private AuthRateLimitService authRateLimitService;
    @MockBean private AuditService auditService;
    @MockBean private EmailVerificationService emailVerificationService;
    @MockBean private GitHubOAuthService gitHubOAuthService;

    @Test
    void csrfBootstrapIsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void anonymousVerificationPostWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/verification/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().is(419))
                .andExpect(jsonPath("$.code").value(419))
                .andExpect(jsonPath("$.message").value("CSRF token 已失效，请重试"));
    }

    @Test
    void anonymousVerificationPostWithCsrfIsAllowed() throws Exception {
        mockMvc.perform(post("/api/auth/verification/email")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
