package com.jarvis.research.controller;

import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamingChatConsumesQuotaAndAddsStreamingHeaders() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        when(proxy.stream(eq("/api/ai/chat/stream"), any()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"type\":\"delta\",\"content\":\"ok\"}")
                                .event("delta").build(),
                        ServerSentEvent.builder("{\"type\":\"done\"}")
                                .event("done").build()
                ));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        AiController controller = new AiController(proxy, rateLimit);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseEmitter emitter = controller.chatStream(
                Map.of("messages", java.util.List.of(Map.of("role", "user", "content", "hi"))),
                response
        );

        assertNotNull(emitter);
        assertEquals("no-cache, no-transform", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        verify(rateLimit).consume(42L);
        verify(proxy).stream(eq("/api/ai/chat/stream"), any());
    }
}
