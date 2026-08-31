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

    /** 解析腾讯实时行情 (GBK编码)
     *  兼容多标的: A股/ETF (~分隔) · 伦敦金/国际金 (,分隔) · 京东积存金 (走Python微服务) */
    public Map<String, Object> getRealtimeQuote(String symbol) {
        var s = symbol == null || symbol.isBlank() ? props.getGold().getDefaultSymbol() : symbol;
        // 京东积存金: Python 微服务 (每分钟自京东抓取缓存)
        if (s.startsWith("jd_")) {
            return fetchJdRealtime(s);
        }
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
            // A股/ETF 用 ~ 分隔, 伦敦金/国际金用 , 分隔
            return raw.contains("~") ? parseTencentRealtime(raw) : parseLondonRealtime(raw, s);
        } catch (Exception e) {
            log.warn("腾讯实时行情抓取失败: {}", e.getMessage());
            // 回退到 Python 微服务
            return fetchFromPython("/api/prices");
        }
    }

    /** 京东积存金实时价: 走 Python 微服务 (每分钟抓取京东缓存) */
    private Map<String, Object> fetchJdRealtime(String symbol) {
        try {
            if (!props.getPythonService().isEnabled()) {
                return Map.of("error", "python-service disabled");
            }
            Map<String, Object> resp = webClient.get()
                    .uri(props.getPythonService().getBaseUrl() + "/api/jd/prices")
                    .retrieve().bodyToMono(Map.class).block();
            if (resp == null || !(resp.get("data") instanceof Map)) {
                return Map.of("error", "jd price empty");
            }
            Map<?, ?> data = (Map<?, ?>) resp.get("data");
            if (!(data.get("prices") instanceof Map)) {
                return Map.of("error", "jd prices missing");
            }
            Map<?, ?> prices = (Map<?, ?>) data.get("prices");
            String key = "jd_zheshang".equals(symbol) ? "zheshang" : "minsheng";
            if (!(prices.get(key) instanceof Map)) {
                return Map.of("error", "jd " + key + " missing");
            }
            Map<?, ?> p = (Map<?, ?>) prices.get(key);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", symbol);
            Object label = p.get("label");
            out.put("name", label != null ? String.valueOf(label) : key);
            out.put("price", toDouble(p.get("price")));
            out.put("change", toDouble(p.get("change")));
            out.put("change_pct", toDouble(p.get("change_pct")));
            out.put("prev_close", toDouble(p.get("yesterday_price")));
            return out;
        } catch (Exception e) {
            return Map.of("error", "jd fetch fail: " + e.getMessage());
        }
    }

    private Double toDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try { return Double.parseDouble((String) o); } catch (Exception ignored) {}
        }
        return null;
    }

    /** 伦敦金/国际金: 腾讯逗号分隔 (v_hf_XAU="现价,涨跌,今开,昨收,最高,最低,时间,...,名称") */
    private Map<String, Object> parseLondonRealtime(String raw, String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || !raw.contains("=")) {
            out.put("error", "bad response");
            return out;
        }
        Matcher m = Pattern.compile("\"(.+?)\"").matcher(raw);
        if (!m.find()) {
            out.put("error", "no data");
            return out;
        }
        String[] v = m.group(1).split(",");
        try {
            out.put("symbol", symbol);
            out.put("name", v.length > 13 ? v[13] : "伦敦金/国际金");
            out.put("price", parseSafe(v, 0));
            out.put("change", parseSafe(v, 1));
            out.put("open", parseSafe(v, 2));
            out.put("prev_close", parseSafe(v, 3));
            out.put("high", parseSafe(v, 4));
            out.put("low", parseSafe(v, 5));
            Object price = out.get("price");
            Object prev = out.get("prev_close");
            out.put("change_pct", (price instanceof Number && prev instanceof Number
                    && ((Number) prev).doubleValue() != 0)
                    ? (((Number) price).doubleValue() - ((Number) prev).doubleValue())
                        / ((Number) prev).doubleValue() * 100 : 0.0);
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
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
