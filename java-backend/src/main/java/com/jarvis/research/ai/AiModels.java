package com.jarvis.research.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 对话统一请求模型 (屏蔽不同协议差异)
 */
public class AiModels {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;    // system / user / assistant
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String model;
        private List<ChatMessage> messages;
        private Double temperature;
        private Integer maxTokens;
        private Boolean stream;      // 暂默认 false
        private List<String> stop;

        public ChatRequest(String model, List<ChatMessage> messages) {
            this.model = model;
            this.messages = messages;
            this.temperature = 0.7;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResult {
        private String content;
        private String model;
        private Integer promptTokens;
        private Integer completionTokens;
        private String finishReason;
    }
}
