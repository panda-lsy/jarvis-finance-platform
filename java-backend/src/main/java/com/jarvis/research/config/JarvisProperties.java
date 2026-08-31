package com.jarvis.research.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台配置: 黄金行情 + Python 微服务 + AI 多协议
 */
@Component
@ConfigurationProperties(prefix = "jarvis")
@Data
public class JarvisProperties {

    private Gold gold = new Gold();
    private PythonService pythonService = new PythonService();

    @Data
    public static class Gold {
        private String realtimeUrl = "https://qt.gtimg.cn/q={symbol}";
        private String klineUrl = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        private String defaultSymbol = "sh518850";
    }

    @Data
    public static class PythonService {
        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:8100";
    }
}
