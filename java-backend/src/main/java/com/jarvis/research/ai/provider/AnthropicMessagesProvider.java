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
 * Anthropic Messages API 协议实现
 * 端点: POST {baseUrl}/v1/messages
 * 认证: x-api-key + anthropic-version
 */
@Slf4j
@Service
public class AnthropicMessagesProvider implements AiProvider {

    private final AiProperties props;
    private final AiProperties.Provider cfg;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicMessagesProvider(AiProperties props) {
        this.props = props;
        this.cfg = props.getAnthropicMessages();
        this.client = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    @Override
    public String protocol() {
        return "anthropic-messages";
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        String model = request.getModel() != null ? request.getModel() : cfg.getModel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 1024);
        body.put("temperature", request.getTemperature() != null ? request.getTemperature() : cfg.getTemperature());

        // Anthropic: system 单独字段, 其余进 messages
        List<Map<String, String>> msgs = new ArrayList<>();
        String system = null;
        List<Map<String, String>> bodyMsgs = new ArrayList<>();
        for (ChatMessage m : request.getMessages()) {
            Map<String, String> mm = new LinkedHashMap<>();
            mm.put("role", "assistant".equals(m.getRole()) ? "assistant" : "user");
            mm.put("content", m.getContent());
            if ("system".equals(m.getRole())) {
                system = system == null ? m.getContent() : system + "\n" + m.getContent();
                continue;
            }
            msgs.add(mm);
        }
        if (system != null) body.put("system", system);
        body.put("messages", msgs);

        String url = OpenAiChatProvider.trimSlash(cfg.getBaseUrl()) + "/v1/messages";
        try {
            JsonNode root = client.post().uri(url)
                    .header("x-api-key", cfg.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .bodyValue(mapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();
            if (root == null) return ChatResult.builder().content("").build();

            StringBuilder sb = new StringBuilder();
            root.path("content").forEach(c -> {
                if (c.path("text").isTextual()) sb.append(c.path("text").asText());
            });
            var usage = root.path("usage");
            return ChatResult.builder()
                    .content(sb.toString())
                    .model(model)
                    .promptTokens(usage.path("input_tokens").asInt(0))
                    .completionTokens(usage.path("output_tokens").asInt(0))
                    .finishReason(root.path("stop_reason").asText(""))
                    .build();
        } catch (Exception e) {
            log.error("Anthropic Messages 调用失败: {}", e.getMessage());
            throw new RuntimeException("Anthropic chat failed: " + e.getMessage(), e);
        }
    }
}
