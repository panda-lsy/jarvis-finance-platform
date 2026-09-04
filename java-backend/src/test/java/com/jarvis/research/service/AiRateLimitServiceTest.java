package com.jarvis.research.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiRateLimitServiceTest {

    @Test
    void limitsEleventhRequestWithinSameMinute() {
        AiRateLimitService service = new AiRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.consume(42L);
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.consume(42L));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void quotasAreIndependentBetweenUsers() {
        AiRateLimitService service = new AiRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.consume(1L);
        }
        service.consume(2L);
    }
}
