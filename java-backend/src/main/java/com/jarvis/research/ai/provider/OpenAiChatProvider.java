package com.jarvis.research.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.ai.AiModels.*;
import com.jarvis.research.ai.AiProvider;
import com.jarvis.research.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议实现
 * 端点: POST {baseUrl}/chat/completions
 * DeepSeek 等多数模型服务兼容此协议
 */
@Slf4j
@Service
public class OpenAiChatProvider implements AiProvider {

    private final AiProperties props;
    private final AiProperties.Provider cfg;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiChatProvider(AiProperties props) {
        this.props = props;
        this.cfg = props.getOpenaiChat();
        this.client = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    @Override
    public String protocol() {
        return "openai-chat";
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        String model = request.getModel() != null ? request.getModel() : cfg.getModel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> msgs = new ArrayList<>();
        for (ChatMessage m : request.getMessages()) {
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", m.getRole());
            mm.put("content", m.getContent());
            msgs.add(mm);
        }
        body.put("messages", msgs);
        body.put("temperature", request.getTemperature() != null ? request.getTemperature() : cfg.getTemperature());
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        // 兼容: 有些服务用 max_completion_tokens
        if (request.getMaxTokens() != null) body.put("max_completion_tokens", request.getMaxTokens());
        if (request.getStream() != null) body.put("stream", request.getStream());

        String url = trimSlash(cfg.getBaseUrl()) + "/chat/completions";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + cfg.getApiKey());
        headers.put("Content-Type", "application/json");

        try {
            JsonNode root = client.post().uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .bodyValue(mapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();
            if (root == null) return ChatResult.builder().content("").build();
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            String finish = root.path("choices").path(0).path("finish_reason").asText("");
            var usage = root.path("usage");
            return ChatResult.builder()
                    .content(content)
                    .model(model)
                    .promptTokens(usage.path("prompt_tokens").asInt(0))
                    .completionTokens(usage.path("completion_tokens").asInt(0))
                    .finishReason(finish)
                    .build();
        } catch (Exception e) {
            log.error("OpenAI Chat 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    protected static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
