package com.jarvis.research.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimitServiceTest {

    @Test
    void loginAccountIsLimitedIndependentlyOfIp() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.checkLogin("10.0.0." + i, "User@Example.com");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkLogin("10.0.0.99", "user@example.com"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void registrationIsLimitedPerIp() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 5; i++) service.checkRegister("1.2.3.4");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkRegister("1.2.3.4"));
        assertEquals(429, ex.getStatusCode().value());
    }
}
