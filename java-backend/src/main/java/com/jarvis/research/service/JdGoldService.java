package com.jarvis.research.service;

import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.market.PriceSnapshot;
import com.jarvis.research.market.PriceSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 京东积存金采集服务。
 * Java 负责外部行情采集与数据库持久化；Python 不再保存积存金业务数据。
 */
@Slf4j
@Service
public class JdGoldService {

    private record Source(String key, String label, String url, String productSku) {}

    private static final Source ZHESHANG = new Source(
            "zheshang", "浙商积存金",
            "https://api.jdjygold.com/gw2/generic/jrm/h5/m/stdLatestPrice",
            "1961543816");
    private static final Source MINSHENG = new Source(
            "minsheng", "民生积存金",
            "https://api.jdjygold.com/gw/generic/hj/h5/m/latestPrice",
            "P005");
    private static final Source[] SOURCES = {ZHESHANG, MINSHENG};

    private final PriceSnapshotRepository snapshotRepository;
    private final WebClient webClient;

    public JdGoldService(PriceSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
        this.webClient = ExternalWebClients.create(Duration.ofSeconds(10)).mutate()
                .defaultHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Origin", "https://www.jdjygold.com")
                .defaultHeader("Referer", "https://www.jdjygold.com/")
                .build();
    }

    /** 每分钟独立采集，不依赖页面访问量。 */
    @Scheduled(initialDelay = 1000, fixedDelayString = "${jarvis.jd.poll-interval-ms:60000}")
    public void pollAndStore() {
        for (Source source : SOURCES) {
            try {
                Map<String, Object> quote = fetch(source);
                if (quote == null) continue;
                snapshotRepository.save(new PriceSnapshot(
                        "jd_" + source.key(),
                        number(quote.get("price")),
                        number(quote.get("change")),
                        number(quote.get("change_pct")),
                        number(quote.get("yesterday_price")),
                        null, null, null,
                        LocalDateTime.now()));
            } catch (Exception e) {
                log.warn("京东积存金采集失败 {}: {}", source.key(), e.getMessage());
            }
        }
    }

    /** 从 Java 数据库读取最近有效快照。 */
    @Transactional(readOnly = true)
    public Map<String, Object> latestPrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Source source : SOURCES) {
            snapshotRepository.findTopByMarketOrderByTsDesc("jd_" + source.key())
                    .ifPresent(snapshot -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("source", source.key());
                        item.put("label", source.label());
                        item.put("price", snapshot.getPrice());
                        item.put("yesterday_price", snapshot.getPrevClose());
                        item.put("change", snapshot.getChange());
                        item.put("change_pct", snapshot.getChangePct());
                        item.put("time", snapshot.getTs().toString());
                        out.put(source.key(), item);
                    });
        }
        return out;
    }

    /** 模拟盘按 symbol 读取最近积存金价格。 */
    @Transactional(readOnly = true)
    public Map<String, Object> latestQuote(String symbol) {
        String key = switch (symbol) {
            case "jd_zheshang" -> "zheshang";
            case "jd_minsheng" -> "minsheng";
            default -> null;
        };
        if (key == null) return Map.of("error", "unsupported jd symbol");
        Map<String, Object> all = latestPrices();
        Object quote = all.get(key);
        if (quote instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            copy.put("symbol", symbol);
            return copy;
        }
        return Map.of("error", "jd quote unavailable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(Source source) {
        Map<String, Object> raw = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.jdjygold.com")
                        .path(java.net.URI.create(source.url()).getPath())
                        .queryParam("productSku", source.productSku())
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(10));
        if (raw == null || !Boolean.TRUE.equals(raw.get("success"))) return null;
        Object resultData = raw.get("resultData");
        if (!(resultData instanceof Map<?, ?> resultMap)) return null;
        Object datas = resultMap.get("datas");
        if (!(datas instanceof Map<?, ?> data)) return null;

        Double price = number(data.get("price"));
        if (price == null || price <= 0) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("price", price);
        out.put("yesterday_price", number(data.get("yesterdayPrice")) == null
                ? price : number(data.get("yesterdayPrice")));
        out.put("change", number(data.get("upAndDownAmt")) == null ? 0.0 : number(data.get("upAndDownAmt")));
        out.put("change_pct", percent(data.get("upAndDownRate")));
        return out;
    }

    private Double percent(Object value) {
        if (value == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(value).replace("%", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
