package com.jarvis.research.controller;

import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对外 AI API。
 * 所有请求先经过 Java 的 JWT 鉴权，再由 Java 使用内部令牌转发给 Python。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiProxyService aiProxyService;
    private final AiRateLimitService aiRateLimitService;

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return aiProxyService.get("/api/ai/capabilities");
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        consumeAiQuota();
        return aiProxyService.post("/api/ai/chat", body);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        consumeAiQuota();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(90_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Disposable disposable = aiProxyService.stream("/api/ai/chat/stream", body).subscribe(
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
        consumeAiQuota();
        return aiProxyService.post("/api/ai/financial/report", body);
    }

    @PostMapping("/analyze/sentiment")
    public Map<String, Object> sentiment(@RequestBody Map<String, Object> body) {
        consumeAiQuota();
        return aiProxyService.post("/api/ai/analyze/sentiment", body);
    }

    @PostMapping("/analyze/chain")
    public Map<String, Object> chain(@RequestBody Map<String, Object> body) {
        consumeAiQuota();
        return aiProxyService.post("/api/ai/analyze/chain", body);
    }

    @PostMapping("/quote")
    public Map<String, Object> quote(@RequestBody Map<String, Object> body) {
        consumeAiQuota();
        return aiProxyService.post("/api/ai/quote", body);
    }

    private void consumeAiQuota() {
        aiRateLimitService.consume(CurrentUser.id());
    }
}
