package com.jarvis.research.market;

import com.jarvis.research.config.JarvisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 市场数据服务 (Java 主管数据存储)
 * - 实时存取黄金ETF + 伦敦金价格 (PriceSnapshot)
 * - 每日抓取一次K线 (KlineDaily)
 * - 根据实时价格生成分钟K线 (1/5/15/30/60分)
 */
@Slf4j
@Service
public class MarketDataService {

    private final JarvisProperties props;
    private final WebClient webClient;
    private final PriceSnapshotRepository snapshotRepo;
    private final KlineDailyRepository klineRepo;

    public MarketDataService(JarvisProperties props,
                             PriceSnapshotRepository snapshotRepo,
                             KlineDailyRepository klineRepo) {
        this.props = props;
        this.snapshotRepo = snapshotRepo;
        this.klineRepo = klineRepo;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ==================== 实时价格 ====================

    /** 抓取并存储两个标的的实时价格, 返回最新价格 */
    public Map<String, Object> fetchAndStorePrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gold_etf", fetchAndStoreOne("gold_etf", "sh518850", false));
        out.put("london_gold", fetchAndStoreOne("london_gold", "hf_XAU", true));
        return out;
    }

    private Map<String, Object> fetchAndStoreOne(String market, String symbol, boolean london) {
        try {
            Map<String, Object> quote = london ? fetchLondonRealtime() : fetchTencentRealtime(symbol);
            if (quote.containsKey("error")) {
                return quote;
            }
            Double price = (Double) quote.get("price");
            Double change = (Double) quote.get("change");
            Double changePct = (Double) quote.get("change_pct");
            Double prevClose = (Double) quote.get("prev_close");
            Double open = (Double) quote.get("open");
            Double high = (Double) quote.get("high");
            Double low = (Double) quote.get("low");
            // 存快照
            snapshotRepo.save(new PriceSnapshot(market, price, change, changePct,
                    prevClose, open, high, low, LocalDateTime.now()));
            Map<String, Object> out = new LinkedHashMap<>(quote);
            out.put("market", market);
            return out;
        } catch (Exception e) {
            log.warn("抓取价格失败 {}: {}", market, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /** 腾讯 A股/ETF 实时 (~ 分隔) */
    private Map<String, Object> fetchTencentRealtime(String symbol) {
        String url = props.getGold().getRealtimeUrl().replace("{symbol}", symbol);
        byte[] bytes = webClient.get().uri(url)
                .retrieve().bodyToMono(byte[].class).block();
        if (bytes == null) return Map.of("error", "empty");
        String raw = new String(bytes, java.nio.charset.Charset.forName("GBK"));
        Matcher m = Pattern.compile("\"(.+?)\"").matcher(raw);
        if (!m.find()) return Map.of("error", "no data");
        String[] v = m.group(1).split("~");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("name", v.length > 1 ? v[1] : symbol);
        out.put("price", parseD(v, 3));
        out.put("prev_close", parseD(v, 4));
        out.put("open", parseD(v, 5));
        out.put("change", parseD(v, 31));
        out.put("change_pct", parseD(v, 32));
        out.put("high", parseD(v, 33));
        out.put("low", parseD(v, 34));
        return out;
    }

    /** 腾讯伦敦金实时 (, 分隔) */
    private Map<String, Object> fetchLondonRealtime() {
        String url = props.getGold().getRealtimeUrl().replace("{symbol}", "hf_XAU");
        byte[] bytes = webClient.get().uri(url)
                .retrieve().bodyToMono(byte[].class).block();
        if (bytes == null) return Map.of("error", "empty");
        String raw = new String(bytes, java.nio.charset.Charset.forName("GBK"));
        Matcher m = Pattern.compile("\"(.+?)\"").matcher(raw);
        if (!m.find()) return Map.of("error", "no data");
        String[] v = m.group(1).split(",");
        // [0]现价 [1]涨跌 [2]今开 [3]昨收 [4]最高 [5]最低 [6]时间 [13]名称
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", "hf_XAU");
        out.put("name", v.length > 13 ? v[13] : "伦敦金");
        out.put("price", parseD(v, 0));
        out.put("change", parseD(v, 1));
        out.put("open", parseD(v, 2));
        out.put("prev_close", parseD(v, 3));
        out.put("high", parseD(v, 4));
        out.put("low", parseD(v, 5));
        Double price = (Double) out.get("price");
        Double prev = (Double) out.get("prev_close");
        out.put("change_pct", (price != null && prev != null && prev != 0)
                ? (price - prev) / prev * 100 : 0.0);
        return out;
    }

    private Double parseD(String[] v, int idx) {
        if (v.length > idx && v[idx] != null && !v[idx].isEmpty()) {
            try { return Double.parseDouble(v[idx]); } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================== 日K线 (每日抓取一次) ====================

    /** 获取日K线: 若今日未抓取则抓取并存储, 否则读库 */
    public Map<String, Object> getDailyKline(String market, int limit) {
        String symbol = "gold_etf".equals(market) ? "sh518850" : "hf_XAU";
        boolean london = "london_gold".equals(market);
        // 检查今日是否已抓取
        String today = LocalDate.now().toString();
        boolean hasToday = klineRepo.findByMarketAndDate(market, today).isPresent();
        if (!hasToday) {
            try {
                List<Map<String, Object>> fetched = london ? fetchLondonKline() : fetchTencentKline(symbol);
                if (!fetched.isEmpty()) {
                    for (Map<String, Object> k : fetched) {
                        String date = (String) k.get("date");
                        if (klineRepo.findByMarketAndDate(market, date).isEmpty()) {
                            klineRepo.save(new KlineDaily(market, date,
                                    (Double) k.get("open"), (Double) k.get("close"),
                                    (Double) k.get("high"), (Double) k.get("low"),
                                    (Double) k.getOrDefault("volume", 0.0)));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("日K线抓取失败 {}: {}", market, e.getMessage());
            }
        }
        // 读库
        List<KlineDaily> all = klineRepo.findByMarketOrderByDateAsc(market);
        List<Map<String, Object>> data = new ArrayList<>();
        int from = Math.max(0, all.size() - limit);
        for (int i = from; i < all.size(); i++) {
            KlineDaily k = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", k.getDate());
            m.put("open", k.getOpen());
            m.put("close", k.getClose());
            m.put("high", k.getHigh());
            m.put("low", k.getLow());
            m.put("volume", k.getVolume() == null ? 0.0 : k.getVolume());
            data.add(m);
        }
        Map<String, Object> rng = new LinkedHashMap<>();
        rng.put("min", data.isEmpty() ? null : data.get(0).get("date"));
        rng.put("max", data.isEmpty() ? null : data.get(data.size() - 1).get("date"));
        rng.put("count", data.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("range", rng);
        out.put("count", data.size());
        out.put("data", data);
        return out;
    }

    /** 腾讯日K线 */
    private List<Map<String, Object>> fetchTencentKline(String symbol) {
        String param = symbol + ",day,,,500,qfq";
        String body = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("web.ifzq.gtimg.cn")
                        .path("/appstock/app/fqkline/get")
                        .queryParam("param", param).build())
                .retrieve().bodyToMono(String.class).block();
        if (body == null) return List.of();
        try {
            Map resp = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            Map data = (Map) resp.get("data");
            if (data == null) return List.of();
            Map node = (Map) data.get(symbol);
            if (node == null) return List.of();
            List<List> raw = (List) node.get("day");
            if (raw == null) raw = (List) node.get("qfqday");
            if (raw == null) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw) {
                List item = (List) o;
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("date", item.get(0));
                k.put("open", Double.parseDouble(item.get(1).toString()));
                k.put("close", Double.parseDouble(item.get(2).toString()));
                k.put("high", Double.parseDouble(item.get(3).toString()));
                k.put("low", Double.parseDouble(item.get(4).toString()));
                k.put("volume", item.size() > 5 ? Double.parseDouble(item.get(5).toString()) : 0.0);
                out.add(k);
            }
            return out;
        } catch (Exception e) {
            log.warn("腾讯K线解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 新浪伦敦金日K线 */
    private List<Map<String, Object>> fetchLondonKline() {
        String url = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine";
        String text = webClient.get().uri(uriBuilder -> uriBuilder
                        .scheme("https").host("stock2.finance.sina.com.cn")
                        .path("/futures/api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine")
                        .queryParam("symbol", "XAU").build())
                .header("Referer", "https://finance.sina.com.cn")
                .retrieve().bodyToMono(String.class).block();
        if (text == null) return List.of();
        Matcher m = Pattern.compile("\\(\\[(.*)\\]\\)", Pattern.DOTALL).matcher(text);
        if (!m.find()) return List.of();
        String json = "[" + m.group(1) + "]";
        try {
            List<Map<String, Object>> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> k : raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", k.get("date"));
                item.put("open", Double.parseDouble(k.get("open").toString()));
                item.put("close", Double.parseDouble(k.get("close").toString()));
                item.put("high", Double.parseDouble(k.get("high").toString()));
                item.put("low", Double.parseDouble(k.get("low").toString()));
                item.put("volume", k.get("volume") == null ? 0.0 : Double.parseDouble(k.get("volume").toString()));
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.warn("新浪K线解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 分钟K线 (基于实时价格快照) ====================

    /**
     * 根据 PriceSnapshot 生成分钟K线
     * @param market 标的
     * @param minutes 分钟数 (1/5/15/30/60)
     * @param limit 返回根数
     */
    public Map<String, Object> getMinuteKline(String market, int minutes, int limit) {
        List<PriceSnapshot> snaps = snapshotRepo.findByMarketOrderByTsAsc(market);
        // 按分钟桶聚合
        Map<String, List<PriceSnapshot>> buckets = new TreeMap<>();
        for (PriceSnapshot s : snaps) {
            String bucket = bucketKey(s.getTs(), minutes);
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map.Entry<String, List<PriceSnapshot>> e : buckets.entrySet()) {
            List<PriceSnapshot> list = e.getValue();
            PriceSnapshot first = list.get(0);
            PriceSnapshot last = list.get(list.size() - 1);
            double open = first.getPrice();
            double close = last.getPrice();
            double high = list.stream().mapToDouble(PriceSnapshot::getPrice).max().orElse(close);
            double low = list.stream().mapToDouble(PriceSnapshot::getPrice).min().orElse(close);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("date", e.getKey());
            k.put("open", open);
            k.put("close", close);
            k.put("high", high);
            k.put("low", low);
            k.put("volume", 0.0);
            data.add(k);
        }
        int from = Math.max(0, data.size() - limit);
        List<Map<String, Object>> sliced = new ArrayList<>(data.subList(from, data.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("interval", minutes + "m");
        out.put("count", sliced.size());
        out.put("data", sliced);
        return out;
    }

    private String bucketKey(LocalDateTime ts, int minutes) {
        int total = ts.getHour() * 60 + ts.getMinute();
        int bucket = (total / minutes) * minutes;
        return ts.toLocalDate() + " " + String.format("%02d:%02d", bucket / 60, bucket % 60);
    }
}
