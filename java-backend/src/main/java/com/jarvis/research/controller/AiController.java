package com.jarvis.research.controller;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import com.jarvis.research.service.FeaturePermissionService;
import com.jarvis.research.service.SimTradeService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对外 AI API。
 * 所有请求先经过 Java 的 JWT 鉴权，再由 Java 使用内部令牌转发给 Python。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiProxyService aiProxyService;
    private final AiRateLimitService aiRateLimitService;
    private final FeaturePermissionService featurePermissionService;
    private final MarketDataService marketDataService;
    private final SimTradeService simTradeService;

    @Autowired
    public AiController(AiProxyService aiProxyService, AiRateLimitService aiRateLimitService,
                        FeaturePermissionService featurePermissionService,
                        MarketDataService marketDataService,
                        SimTradeService simTradeService) {
        this.aiProxyService = aiProxyService;
        this.aiRateLimitService = aiRateLimitService;
        this.featurePermissionService = featurePermissionService;
        this.marketDataService = marketDataService;
        this.simTradeService = simTradeService;
    }

    /** 兼容不加载 Spring 容器的旧单元测试。 */
    public AiController(AiProxyService aiProxyService, AiRateLimitService aiRateLimitService,
                        FeaturePermissionService featurePermissionService) {
        this(aiProxyService, aiRateLimitService, featurePermissionService, null, null);
    }

    /** 兼容不加载 Spring 容器的旧单元测试。 */
    public AiController(AiProxyService aiProxyService, AiRateLimitService aiRateLimitService) {
        this(aiProxyService, aiRateLimitService, null, null, null);
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return aiProxyService.get("/api/ai/capabilities");
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_CHAT");
        return postAndRecord("/api/ai/chat", enrichChatBody(body));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        consumeAiQuota("AI_CHAT_STREAM");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(90_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = aiProxyService.stream("/api/ai/chat/stream", enrichChatBody(body)).subscribe(
                event -> {
                    try {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event();
                        if (event.event() != null && !event.event().isBlank()) {
                            builder.name(event.event());
                        }
                        builder.data(event.data() == null ? "" : event.data());
                        emitter.send(builder);
                    } catch (IOException | IllegalStateException e) {
                        Disposable current = subscription.get();
                        if (current != null) current.dispose();
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        subscription.set(disposable);
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            emitter.complete();
        });
        emitter.onError(error -> disposable.dispose());
        return emitter;
    }

    @PostMapping("/financial/report")
    public Map<String, Object> financialReport(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_REPORT");
        return postAndRecord("/api/ai/financial/report", body);
    }

    @PostMapping("/analyze/sentiment")
    public Map<String, Object> sentiment(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_SENTIMENT");
        return postAndRecord("/api/ai/analyze/sentiment", body);
    }

    @PostMapping("/analyze/chain")
    public Map<String, Object> chain(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_CHAIN");
        return postAndRecord("/api/ai/analyze/chain", body);
    }

    @PostMapping("/analyze/risk")
    public Map<String, Object> risk(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_RISK");
        return postAndRecord("/api/ai/analyze/risk", enrichRiskBody(body));
    }

    @PostMapping("/quote")
    public Map<String, Object> quote(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_QUOTE");
        return postAndRecord("/api/ai/quote", body);
    }

    /**
     * 个性化策略生成（FR-11）：问卷参数纯转发，不涉及服务端取数。
     * 风险等级与建议配置比例由 Python 确定性计算层生成，Java 不做任何改写。
     */
    @PostMapping("/analyze/strategy")
    public Map<String, Object> strategy(@RequestBody Map<String, Object> body) {
        consumeAiQuota("AI_STRATEGY");
        return postAndRecord("/api/ai/analyze/strategy", body);
    }

    private Map<String, Object> enrichChatBody(Map<String, Object> body) {
        if (marketDataService == null) return body;
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (body != null) enriched.putAll(body);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("generated_at", Instant.now().toString());
        context.put("prices", marketDataService.getLatestPrices());
        Map<String, Object> klines = new LinkedHashMap<>();
        try {
            klines.put("gold_etf", marketDataService.getDailyKline("gold_etf", 60));
        } catch (Exception ignored) {
            // 行情历史暂不可用时仍允许纯文本 AI 对话。
        }
        try {
            klines.put("london_gold", marketDataService.getDailyKline("london_gold", 60));
        } catch (Exception ignored) {
            // 同上；Python 会明确标记缺失的确定性研究上下文。
        }
        context.put("klines", klines);
        if (simTradeService != null) {
            try {
                context.put("portfolio", simTradeService.getAccountOverview(CurrentUser.id()));
            } catch (Exception ignored) {
                // 模拟账户暂不可估值时仍允许市场研究；Python 会省略 portfolio 指标。
            }
        }
        // 强制覆盖客户端同名字段，防止浏览器伪造“系统确定性计算上下文”。
        enriched.put("research_context", context);
        return enriched;
    }

    private Map<String, Object> enrichRiskBody(Map<String, Object> body) {
        Map<String, Object> riskPayload = new LinkedHashMap<>();
        if (marketDataService == null || body == null) return body;

        String market = body.get("market") == null ? "gold_etf" : String.valueOf(body.get("market"));
        int days = 60;
        if (body.get("days") instanceof Number) {
            days = Math.max(10, Math.min(((Number) body.get("days")).intValue(), 500));
        }
        riskPayload.put("symbol", market);
        riskPayload.put("confidence", body.get("confidence") instanceof Number
                ? body.get("confidence") : 0.95);
        if (body.get("portfolio_value") instanceof Number) {
            riskPayload.put("portfolio_value", body.get("portfolio_value"));
        }
        // 服务端从自营行情库取日 K 收盘价，强制覆盖客户端可能伪造的 closes/history 字段。
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> kline = (Map<String, Object>) marketDataService.getDailyKline(market, days);
            Object rawRows = kline == null ? null : kline.get("data");
            List<Double> closes = new ArrayList<>();
            if (rawRows instanceof List<?> rows) {
                for (Object rowObj : rows) {
                    if (!(rowObj instanceof Map<?, ?> row)) continue;
                    Object close = row.get("close");
                    if (close instanceof Number number) {
                        closes.add(number.doubleValue());
                    } else if (close != null) {
                        try {
                            closes.add(Double.parseDouble(String.valueOf(close)));
                        } catch (NumberFormatException ignored) {
                            // 忽略单行坏数据，Python 侧会对样本量做最终校验。
                        }
                    }
                }
            }
            riskPayload.put("closes", closes);
        } catch (Exception ignored) {
            // 行情暂不可用时仍透传，Python 返回样本不足；不允许客户端伪造 closes。
            riskPayload.put("closes", java.util.List.of());
        }
        return riskPayload;
    }

    private void consumeAiQuota(String featureKey) {
        if (featurePermissionService != null) {
            featurePermissionService.require(CurrentUser.id(), featureKey);
        }
        aiRateLimitService.consume(CurrentUser.id());
    }

    private Map<String, Object> postAndRecord(String path, Object body) {
        Map<String, Object> response = aiProxyService.post(path, body);
        aiRateLimitService.recordTokens(CurrentUser.id(), response);
        return response;
    }
}
