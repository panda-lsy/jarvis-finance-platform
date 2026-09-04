package com.jarvis.research.service;

import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.config.JarvisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * AI 内部代理服务。
 * 浏览器只访问 Java；Java 使用内部令牌调用本机 Python AI 服务。
 */
@Service
@RequiredArgsConstructor
public class AiProxyService {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final JarvisProperties props;

    public Map<String, Object> get(String path) {
        return client().get()
                .uri(path)
                .header(INTERNAL_TOKEN_HEADER, token())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new ResponseStatusException(
                                response.statusCode(),
                                "Python AI 服务调用失败: " + body)))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public Map<String, Object> post(String path, Object body) {
        return client().post()
                .uri(path)
                .header(INTERNAL_TOKEN_HEADER, token())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(respBody -> new ResponseStatusException(
                                response.statusCode(),
                                "Python AI 服务调用失败: " + respBody)))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /** 透明代理 Python SSE；Java 不拼接模型输出，只负责鉴权、限流和内部凭证。 */
    public Flux<ServerSentEvent<String>> stream(String path, Object body) {
        return client().post()
                .uri(path)
                .header(INTERNAL_TOKEN_HEADER, token())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(respBody -> new ResponseStatusException(
                                response.statusCode(),
                                "Python AI 服务调用失败: " + respBody)))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    private WebClient client() {
        if (!props.getPythonService().isEnabled()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Python AI 服务已禁用");
        }
        return ExternalWebClients.create(
                props.getPythonService().getBaseUrl(), java.time.Duration.ofSeconds(65));
    }

    private String token() {
        String token = props.getPythonService().getInternalToken();
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "PYTHON_SERVICE_TOKEN 未配置");
        }
        return token;
    }
}
