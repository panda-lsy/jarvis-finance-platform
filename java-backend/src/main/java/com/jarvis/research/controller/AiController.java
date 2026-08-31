package com.jarvis.research.controller;

import com.jarvis.research.ai.AiGateway;
import com.jarvis.research.ai.AiModels.ChatMessage;
import com.jarvis.research.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 大模型 API
 * POST /api/ai/chat                      通用对话
 * POST /api/ai/financial/report          财报智能解析
 * POST /api/ai/analyze/sentiment         研报情感分析
 * POST /api/ai/analyze/chain             产业链分析
 * GET  /api/ai/status                    协议/模型状态
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiGateway aiGateway;

    public AiController(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    @PostMapping("/chat")
    public ApiResponse<?> chat(@RequestBody Map<String, Object> req) {
        List<Map<String, String>> raw = (List<Map<String, String>>) req.get("messages");
        List<ChatMessage> messages = raw.stream()
                .map(m -> ChatMessage.builder()
                        .role((String) m.getOrDefault("role", "user"))
                        .content((String) m.getOrDefault("content", ""))
                        .build())
                .toList();
        return ApiResponse.ok(aiGateway.chat(messages));
    }

    @PostMapping("/financial/report")
    public ApiResponse<?> analyzeReport(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(aiGateway.analyzeFinancialReport(
                body.getOrDefault("content", body.getOrDefault("text", ""))));
    }

    @PostMapping("/analyze/sentiment")
    public ApiResponse<?> analyzeSentiment(@RequestBody Map<String, Object> body) {
        List<String> reports = (List<String>) body.getOrDefault("reports", List.of());
        return ApiResponse.ok(aiGateway.analyzeSentiment(reports));
    }

    @PostMapping("/analyze/chain")
    public ApiResponse<?> analyzeChain(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(aiGateway.analyzeChain(
                body.getOrDefault("node", ""),
                body.getOrDefault("context", "")));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(Map.of(
                "protocol", aiGateway.activeProtocol(),
                "supported", List.of("openai-chat", "openai-responses", "anthropic-messages")
        ));
    }
}
