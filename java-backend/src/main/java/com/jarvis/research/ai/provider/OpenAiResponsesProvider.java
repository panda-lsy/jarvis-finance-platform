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
 * OpenAI Responses API 协议实现
 * 端点: POST {baseUrl}/responses
 */
@Slf4j
@Service
public class OpenAiResponsesProvider implements AiProvider {

    private final AiProperties props;
    private final AiProperties.Provider cfg;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiResponsesProvider(AiProperties props) {
        this.props = props;
        this.cfg = props.getOpenaiResponses();
        this.client = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    @Override
    public String protocol() {
        return "openai-responses";
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        String model = request.getModel() != null ? request.getModel() : cfg.getModel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, Object>> input = new ArrayList<>();
        for (ChatMessage m : request.getMessages()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", m.getRole());
            mm.put("content", m.getContent());
            input.add(mm);
        }
        body.put("input", input);
        if (request.getMaxTokens() != null) body.put("max_output_tokens", request.getMaxTokens());

        String url = OpenAiChatProvider.trimSlash(cfg.getBaseUrl()) + "/responses";
        try {
            JsonNode root = client.post().uri(url)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(mapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .block();
            if (root == null) return ChatResult.builder().content("").build();

            // 提取文本 (output[].content[].text)
            StringBuilder sb = new StringBuilder();
            root.path("output").forEach(o -> o.path("content").forEach(c -> {
                if (c.path("text").isTextual()) sb.append(c.path("text").asText());
            }));
            String finish = root.path("status").asText("");
            var usage = root.path("usage");
            return ChatResult.builder()
                    .content(sb.toString())
                    .model(model)
                    .promptTokens(usage.path("input_tokens").asInt(0))
                    .completionTokens(usage.path("output_tokens").asInt(0))
                    .finishReason(finish)
                    .build();
        } catch (Exception e) {
            log.error("OpenAI Responses 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI responses failed: " + e.getMessage(), e);
        }
    }
}
