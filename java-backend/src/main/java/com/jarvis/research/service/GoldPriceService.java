package com.jarvis.research.service;

import com.jarvis.research.config.JarvisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 黄金行情服务
 * 主源: 腾讯财经 (qt.gtimg.cn 实时 / ifzq.gtimg.cn K线)
 * 辅助: Python 微服务 (数据采集/预处理)
 */
@Slf4j
@Service
public class GoldPriceService {

    private final JarvisProperties props;
    private final WebClient webClient;

    public GoldPriceService(JarvisProperties props) {
        this.props = props;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /** 解析腾讯实时行情 (GBK编码) */
    public Map<String, Object> getRealtimeQuote(String symbol) {
        var s = symbol == null || symbol.isBlank() ? props.getGold().getDefaultSymbol() : symbol;
        String url = props.getGold().getRealtimeUrl().replace("{symbol}", s);
        try {
            // 腾讯返回 GBK; 先取字节再用 GBK 解码
            byte[] bytes = webClient.get().uri(url)
                    .retrieve().bodyToMono(byte[].class)
                    .block();
            if (bytes == null) {
                return Map.of("error", "empty response");
            }
            String raw = new String(bytes, java.nio.charset.Charset.forName("GBK"));
            return parseTencentRealtime(raw);
        } catch (Exception e) {
            log.warn("腾讯实时行情抓取失败: {}", e.getMessage());
            // 回退到 Python 微服务
            return fetchFromPython("/api/prices");
        }
    }

    /** 获取历史K线 */
    public Object getKline(String symbol, int limit) {
        var s = symbol == null || symbol.isBlank() ? props.getGold().getDefaultSymbol() : symbol;
        try {
            String param = s + ",day,,," + limit + ",qfq";
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("web.ifzq.gtimg.cn")
                            .path("/appstock/app/fqkline/get")
                            .queryParam("param", param).build())
                    .retrieve().bodyToMono(Map.class).block();
        } catch (Exception e) {
            log.warn("腾讯K线拉取失败回退Python: {}", e.getMessage());
            return fetchFromPython("/api/kline?market=gold_etf&limit=" + limit);
        }
    }

    private Map<String, Object> fetchFromPython(String path) {
        if (!props.getPythonService().isEnabled()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "python-service disabled");
            return err;
        }
        try {
            return webClient.get()
                    .uri(props.getPythonService().getBaseUrl() + path)
                    .retrieve().bodyToMono(Map.class).block();
        } catch (Exception e) {
            return Map.of("error", "python-service unreachable: " + e.getMessage());
        }
    }

    private Map<String, Object> parseTencentRealtime(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || !raw.contains("~")) {
            out.put("error", "bad response");
            return out;
        }
        // v_sh518850="1~黄金ETF华夏~518850~9.573~..."
        Matcher m = Pattern.compile("\"(.+?)\"").matcher(raw);
        if (!m.find()) {
            out.put("error", "no data");
            return out;
        }
        String[] v = m.group(1).split("~");
        // 1名称, 3现价, 4昨收, 5今开, 6成交量, 31涨跌, 32涨跌%
        try {
            out.put("symbol", v[2]);
            out.put("name", v[1]);
            out.put("price", parseSafe(v, 3));
            out.put("prev_close", parseSafe(v, 4));
            out.put("open", parseSafe(v, 5));
            out.put("change", parseSafe(v, 31));
            out.put("change_pct", parseSafe(v, 32));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Object parseSafe(String[] v, int idx) throws Exception {
        if (v.length > idx && !v[idx].isEmpty()) {
            return Double.parseDouble(v[idx]);
        }
        return null;
    }
}
