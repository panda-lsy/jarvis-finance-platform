package com.jarvis.research.controller;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.FeaturePermissionService;
import com.jarvis.research.service.SimTradeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void chatInjectsServerOwnedMarketAndPortfolioContext() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        FeaturePermissionService permissions = mock(FeaturePermissionService.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        SimTradeService simTradeService = mock(SimTradeService.class);
        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", 7.88)));
        when(marketDataService.getDailyKline(any(), eq(60))).thenReturn(Map.of("data", java.util.List.of()));
        when(simTradeService.getAccountOverview(42L)).thenReturn(Map.of(
                "cash", 90000, "positions", Map.of()));
        when(proxy.post(eq("/api/ai/chat"), any())).thenReturn(Map.of("code", 200));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        AiController controller = new AiController(
                proxy, rateLimit, permissions, marketDataService, simTradeService);
        controller.chat(Map.of(
                "messages", java.util.List.of(Map.of("role", "user", "content", "我的风险如何")),
                "research_context", Map.of("forged", true)));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(proxy).post(eq("/api/ai/chat"), bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("research_context");
        assertNotNull(context.get("generated_at"));
        assertEquals(Map.of("gold_etf", Map.of("price", 7.88)), context.get("prices"));
        assertEquals(Map.of("cash", 90000, "positions", Map.of()), context.get("portfolio"));
        assertFalse(context.containsKey("forged"));
        verify(simTradeService).getAccountOverview(42L);
        verify(rateLimit).consume(42L);
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

    @Test
    void riskUsesServerOwnedKlineClosesAndIgnoresClientForgery() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        FeaturePermissionService permissions = mock(FeaturePermissionService.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        SimTradeService simTradeService = mock(SimTradeService.class);

        when(marketDataService.getDailyKline("gold_etf", 60)).thenReturn(Map.of(
                "data", java.util.List.of(
                        Map.of("date", "2026-08-01", "close", 100.0),
                        Map.of("date", "2026-08-02", "close", 101.0),
                        Map.of("date", "2026-08-03", "close", 102.0))));
        when(proxy.post(eq("/api/ai/analyze/risk"), any())).thenReturn(Map.of("code", 200));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        AiController controller = new AiController(
                proxy, rateLimit, permissions, marketDataService, simTradeService);
        controller.risk(Map.of(
                "market", "gold_etf",
                "confidence", 0.95,
                "portfolio_value", 100000,
                // 客户端伪造的历史收盘价应被服务端数据覆盖
                "closes", java.util.List.of(1.0, 2.0, 3.0)));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(proxy).post(eq("/api/ai/analyze/risk"), bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertEquals("gold_etf", body.get("symbol"));
        assertEquals(0.95, body.get("confidence"));
        assertEquals(100000, body.get("portfolio_value"));
        assertEquals(java.util.List.of(100.0, 101.0, 102.0), body.get("closes"));
        assertFalse(body.containsKey("market"));
        assertFalse(body.containsKey("days"));
        verify(rateLimit).consume(42L);
    }

    @Test
    void strategyForwardsQuestionnaireVerbatimAndConsumesQuota() {
        AiProxyService proxy = mock(AiProxyService.class);
        AiRateLimitService rateLimit = mock(AiRateLimitService.class);
        FeaturePermissionService permissions = mock(FeaturePermissionService.class);

        when(proxy.post(eq("/api/ai/analyze/strategy"), any())).thenReturn(Map.of("code", 200));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(42L, null));

        // 纯代理端点：不依赖行情/模拟盘数据，允许使用精简构造函数
        AiController controller = new AiController(proxy, rateLimit, permissions);
        Map<String, Object> questionnaire = new java.util.LinkedHashMap<>();
        questionnaire.put("horizon_years", 5);
        questionnaire.put("max_drawdown_pct", 20);
        questionnaire.put("target_return_pct", 7.5);
        questionnaire.put("capital", 100000);
        questionnaire.put("experience", "basic");

        controller.strategy(questionnaire);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(proxy).post(eq("/api/ai/analyze/strategy"), bodyCaptor.capture());
        // 问卷参数原样透传，Java 不新增/改写任何字段（等级与配置比例由 Python 计算）
        assertEquals(questionnaire, bodyCaptor.getValue());
        verify(permissions).require(42L, "AI_STRATEGY");
        verify(rateLimit).consume(42L);
    }
}
