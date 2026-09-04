package com.jarvis.research.common;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 统一外部 HTTP 客户端工厂。
 * 所有行情源和内部 Python 调用必须显式设置连接/响应超时，避免上游卡死拖住线程。
 */
public final class ExternalWebClients {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private ExternalWebClients() {}

    public static WebClient create(Duration responseTimeout) {
        return builder(responseTimeout).build();
    }

    public static WebClient create(String baseUrl, Duration responseTimeout) {
        return builder(responseTimeout).baseUrl(baseUrl).build();
    }

    private static WebClient.Builder builder(Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(responseTimeout);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
