package com.jarvis.research.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 平台配置: 黄金行情 + Python 微服务 + AI 多协议
 */
@Component
@ConfigurationProperties(prefix = "jarvis")
@Data
public class JarvisProperties {

    private Gold gold = new Gold();
    private PythonService pythonService = new PythonService();
    private Cors cors = new Cors();
    private Auth auth = new Auth();

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
        /** Java -> Python 内部调用凭证，仅通过环境变量注入。 */
        private String internalToken = "";
    }

    @Data
    public static class Auth {
        private String cookieName = "jarvis_token";
        private String cookieDomain = "";
        private boolean cookieSecure = false;
        private String sameSite = "Lax";
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of(
                "https://f.shengxia.me",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        );
    }
}
