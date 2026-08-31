package com.jarvis.research.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 多协议配置 (ai.*)
 * 支持 openai-chat / openai-responses / anthropic-messages
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    /** 默认协议 */
    private String protocol = "openai-chat";

    private Provider openaiChat = new Provider("https://api.deepseek.com/v1", "deepseek-chat");
    private Provider openaiResponses = new Provider("https://api.deepseek.com/v1", "deepseek-chat");
    private Provider anthropicMessages = new Provider("https://api.anthropic.com", "claude-3-5-sonnet-20241022");

    /** 当前协议对应的默认模型 */
    public String getProtocolModel() {
        return switch (protocol) {
            case "openai-responses" -> openaiResponses.getModel();
            case "anthropic-messages" -> anthropicMessages.getModel();
            default -> openaiChat.getModel();
        };
    }

    @Data
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int timeoutSeconds = 60;
        private double temperature = 0.7;

        public Provider() {}
        public Provider(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }
    }
}
