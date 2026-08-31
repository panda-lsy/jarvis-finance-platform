package com.jarvis.research.ai;

import com.jarvis.research.ai.AiModels.ChatMessage;
import com.jarvis.research.ai.AiModels.ChatRequest;
import com.jarvis.research.ai.AiModels.ChatResult;

import java.util.List;

/**
 * AI 大模型 Provider 统一接口
 * 屏蔽 OpenAI Chat / OpenAI Responses / Anthropic Messages 协议差异
 */
public interface AiProvider {

    /** 协议标识 */
    String protocol();

    /** 发起对话 */
    ChatResult chat(ChatRequest request);

    /** 便捷: 单轮系统+用户消息 */
    default ChatResult chatSimple(String system, String user) {
        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role("system").content(system).build(),
                ChatMessage.builder().role("user").content(user).build()
        );
        return chat(new ChatRequest(null, messages));
    }
}
