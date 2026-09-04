package com.jarvis.research.security;

import com.jarvis.research.config.JarvisProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-jwt-secret-key-at-least-32-bytes-long";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesFromHttpOnlyCookieValue() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        JarvisProperties props = new JarvisProperties();
        props.getAuth().setCookieName("jarvis_token");
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, props);

        String token = jwtUtil.generateToken(42L, "user@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("jarvis_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(42L, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void ignoresInvalidCookie() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        JarvisProperties props = new JarvisProperties();
        JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("jarvis_token", "invalid"));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
