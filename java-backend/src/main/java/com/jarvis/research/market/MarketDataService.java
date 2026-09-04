package com.jarvis.research.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.config.JarvisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private final ObjectMapper objectMapper;

    public MarketDataService(JarvisProperties props,
                             PriceSnapshotRepository snapshotRepo,
                             KlineDailyRepository klineRepo,
                             ObjectMapper objectMapper) {
        this.props = props;
        this.snapshotRepo = snapshotRepo;
        this.klineRepo = klineRepo;
        this.objectMapper = objectMapper;
        this.webClient = ExternalWebClients.create(java.time.Duration.ofSeconds(10)).mutate()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ==================== 实时价格 ====================

    /** 独立定时采集，不依赖任何页面请求。 */
    @Scheduled(initialDelay = 1000, fixedDelayString = "${jarvis.market.poll-interval-ms:30000}")
    public void pollAndStorePrices() {
        fetchAndStorePrices();
    }

    /** 抓取并存储两个标的的实时价格。仅供内部定时任务使用。 */
    public Map<String, Object> fetchAndStorePrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (isChinaEtfTradingTime()) {
            out.put("gold_etf", fetchAndStoreOne("gold_etf", "sh518850", false));
        } else {
            out.put("gold_etf", Map.of("status", "market_closed"));
        }
        if (isLondonTradingDay()) {
            out.put("london_gold", fetchAndStoreOne("london_gold", "hf_XAU", true));
        } else {
            out.put("london_gold", Map.of("status", "market_closed"));
        }
        return out;
    }

    private boolean isChinaEtfTradingTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        boolean morning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30));
        boolean afternoon = !time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    private boolean isLondonTradingDay() {
        DayOfWeek day = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** API 查询只读最近快照，不触发外部抓取或数据库写入。 */
    public Map<String, Object> getLatestPrices() {
        Map<String, Object> out = new LinkedHashMap<>();
        latestPrice("gold_etf", "sh518850", "黄金ETF华夏").ifPresent(v -> out.put("gold_etf", v));
        latestPrice("london_gold", "hf_XAU", "伦敦金(现货黄金)").ifPresent(v -> out.put("london_gold", v));
        return out;
    }

    private Optional<Map<String, Object>> latestPrice(String market, String symbol, String name) {
        return snapshotRepo.findTopByMarketOrderByTsDesc(market).map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("market", market);
            item.put("symbol", symbol);
            item.put("name", name);
            item.put("price", s.getPrice());
            item.put("change", s.getChange());
            item.put("change_pct", s.getChangePct());
            item.put("prev_close", s.getPrevClose());
            item.put("open", s.getOpen());
            item.put("high", s.getHigh());
            item.put("low", s.getLow());
            item.put("quote_time", s.getTs().toString());
            return item;
        });
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

    // ==================== 日K线 (定时刷新 + API纯查询) ====================

    /**
     * 独立刷新日K，不依赖页面访问。
     * 同一日期的数据允许覆盖，避免盘中第一次抓取后当天 OHLC 被永久冻结。
     */
    @Scheduled(initialDelay = 5000, fixedDelayString = "${jarvis.market.daily-kline-refresh-ms:1800000}")
    public void refreshDailyKlines() {
        refreshDailyKlineMarket("gold_etf", fetchTencentKline("sh518850"));
        refreshDailyKlineMarket("london_gold", fetchLondonKline());
    }

    private void refreshDailyKlineMarket(String market, List<Map<String, Object>> fetched) {
        if (fetched == null || fetched.isEmpty()) return;

        Map<String, KlineDaily> existingByDate = new HashMap<>();
        for (KlineDaily existing : klineRepo.findByMarketOrderByDateAsc(market)) {
            existingByDate.put(existing.getDate(), existing);
        }

        List<KlineDaily> changed = new ArrayList<>();
        for (Map<String, Object> row : fetched) {
            String date = String.valueOf(row.getOrDefault("date", "")).trim();
            if (date.isEmpty()) continue;

            Double open = asDouble(row.get("open"));
            Double close = asDouble(row.get("close"));
            Double high = asDouble(row.get("high"));
            Double low = asDouble(row.get("low"));
            Double volume = asDouble(row.getOrDefault("volume", 0.0));
            if (open == null || close == null || high == null || low == null) continue;
            if (volume == null) volume = 0.0;

            KlineDaily entity = existingByDate.get(date);
            if (entity == null) {
                entity = new KlineDaily(market, date, open, close, high, low, volume);
                changed.add(entity);
                continue;
            }

            if (!Objects.equals(entity.getOpen(), open)
                    || !Objects.equals(entity.getClose(), close)
                    || !Objects.equals(entity.getHigh(), high)
                    || !Objects.equals(entity.getLow(), low)
                    || !Objects.equals(entity.getVolume(), volume)) {
                entity.setOpen(open);
                entity.setClose(close);
                entity.setHigh(high);
                entity.setLow(low);
                entity.setVolume(volume);
                changed.add(entity);
            }
        }

        if (!changed.isEmpty()) {
            klineRepo.saveAll(changed);
            log.info("日K刷新完成: market={}, changed={}", market, changed.size());
        }
    }

    /** API 只读数据库最近 N 根日K，不触发任何外部请求或写入。 */
    public Map<String, Object> getDailyKline(String market, int limit) {
        List<KlineDaily> latest = new ArrayList<>(
                klineRepo.findByMarketOrderByDateDesc(market, PageRequest.of(0, limit)));
        Collections.reverse(latest);

        List<Map<String, Object>> data = new ArrayList<>();
        for (KlineDaily k : latest) {
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

    private Double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
            JsonNode root = objectMapper.readTree(body);
            JsonNode node = root.path("data").path(symbol);
            if (node.isMissingNode() || node.isNull()) return List.of();
            JsonNode raw = node.get("day");
            if (raw == null || !raw.isArray()) raw = node.get("qfqday");
            if (raw == null || !raw.isArray()) return List.of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode item : raw) {
                if (!item.isArray() || item.size() < 5) continue;
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("date", item.get(0).asText());
                k.put("open", Double.parseDouble(item.get(1).asText()));
                k.put("close", Double.parseDouble(item.get(2).asText()));
                k.put("high", Double.parseDouble(item.get(3).asText()));
                k.put("low", Double.parseDouble(item.get(4).asText()));
                k.put("volume", item.size() > 5 ? Double.parseDouble(item.get(5).asText("0")) : 0.0);
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
            JsonNode raw = objectMapper.readTree(json);
            if (!raw.isArray()) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode k : raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", k.path("date").asText());
                item.put("open", Double.parseDouble(k.path("open").asText()));
                item.put("close", Double.parseDouble(k.path("close").asText()));
                item.put("high", Double.parseDouble(k.path("high").asText()));
                item.put("low", Double.parseDouble(k.path("low").asText()));
                item.put("volume", k.hasNonNull("volume")
                        ? Double.parseDouble(k.path("volume").asText("0")) : 0.0);
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
        // 只读取生成所需 K 线的大致快照量，避免运行越久每次请求扫描越多历史数据。
        int samplesPerMinute = market.startsWith("jd_") ? 1 : 2;
        long estimatedRows = (long) limit * minutes * samplesPerMinute * 2L;
        int rowsToRead = (int) Math.max(500L, Math.min(100_000L, estimatedRows));
        List<PriceSnapshot> snaps = new ArrayList<>(
                snapshotRepo.findByMarketOrderByTsDesc(market, PageRequest.of(0, rowsToRead)));
        Collections.reverse(snaps);
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
