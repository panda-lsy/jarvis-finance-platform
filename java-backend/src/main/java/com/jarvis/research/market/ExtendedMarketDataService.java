package com.jarvis.research.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.common.ExternalWebClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 股、美股和加密货币的公开行情适配器。
 *
 * <p>默认提供常用标的，并允许用户输入经过市场专属格式校验的自定义 symbol，
 * 避免把任意 URL 或任意外部 symbol 直接透传给行情源。
 * A 股使用腾讯公开接口，美股使用 Yahoo Finance chart 接口，加密货币使用 Binance 公共接口。
 * 后续如需生产级多源容灾，可在本服务后面增加统一行情落库和备用源。</p>
 */
@Slf4j
@Service
public class ExtendedMarketDataService {

    private static final Charset GBK = Charset.forName("GBK");
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter INTRADAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> YAHOO_INTERVALS = Set.of("1d", "5m", "15m", "30m", "1h");
    private static final Set<String> BINANCE_INTERVALS = Set.of("1d", "1h", "30m", "15m", "5m");
    private static final Pattern A_SHARE_PATTERN = Pattern.compile(
            "^(?:(SH|SZ|BJ))?(\\d{6})(?:(SH|SZ|BJ))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern US_STOCK_PATTERN = Pattern.compile(
            "^[A-Z][A-Z0-9.-]{0,9}$");
    private static final Pattern CRYPTO_PATTERN = Pattern.compile(
            "^[A-Z0-9]{2,15}(USDT)?$");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * 断网缓存兜底：记录每个请求最近一次成功结果。
     * 上游不可用时回退最近缓存并标记 stale=true，缓存有效期 24 小时。
     */
    record CacheKey(String kind, String market, String symbol, String interval, int limit) {}

    private record CachedPayload(Map<String, Object> data, LocalDateTime cachedAt) {}

    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofHours(24);

    private final java.util.concurrent.ConcurrentHashMap<CacheKey, CachedPayload> responseCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    Map<String, Object> fetchWithOfflineFallback(CacheKey key, ThrowingFetch fetch) {
        try {
            Map<String, Object> fresh = fetch.get();
            responseCache.put(key, new CachedPayload(new LinkedHashMap<>(fresh), LocalDateTime.now()));
            return fresh;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            CachedPayload cached = responseCache.get(key);
            if (cached != null && cached.cachedAt().isAfter(LocalDateTime.now().minus(CACHE_TTL))) {
                log.warn("行情源不可用，返回断网缓存 kind={}, market={}, symbol={}, cachedAt={}",
                        key.kind(), key.market(), key.symbol(), cached.cachedAt());
                Map<String, Object> out = new LinkedHashMap<>(cached.data());
                out.put("stale", true);
                out.put("cached_at", cached.cachedAt().toString());
                return out;
            }
            throw e instanceof RuntimeException re ? re
                    : new IllegalStateException("行情抓取失败: " + e.getMessage(), e);
        }
    }

    /** 允许抛出受检异常的抓取动作（上游源方法普遍声明 throws Exception）。 */
    @FunctionalInterface
    interface ThrowingFetch {
        Map<String, Object> get() throws Exception;
    }

    private final List<Instrument> instruments = List.of(
            new Instrument("a_share", "sh600519", "贵州茅台", "CNY", "Tencent"),
            new Instrument("a_share", "sz000001", "平安银行", "CNY", "Tencent"),
            new Instrument("a_share", "sz300750", "宁德时代", "CNY", "Tencent"),
            new Instrument("us_stock", "AAPL", "Apple", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "MSFT", "Microsoft", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "NVDA", "NVIDIA", "USD", "Yahoo Finance"),
            new Instrument("us_stock", "TSLA", "Tesla", "USD", "Yahoo Finance"),
            new Instrument("crypto", "BTCUSDT", "Bitcoin", "USDT", "Binance"),
            new Instrument("crypto", "ETHUSDT", "Ethereum", "USDT", "Binance"),
            new Instrument("crypto", "SOLUSDT", "Solana", "USDT", "Binance")
    );

    public ExtendedMarketDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = ExternalWebClients.create(java.time.Duration.ofSeconds(12));
    }

    public List<Map<String, Object>> listInstruments() {
        return instruments.stream().map(this::instrumentView).toList();
    }

    /**
     * 解析用户输入的市场标的。只返回通过市场专属格式校验的标准化 symbol，
     * 后续报价与 K 线接口仍会复用同一套校验，避免将任意输入拼接到外部 URL。
     */
    public Map<String, Object> resolveInstrument(String market, String query) {
        Instrument instrument = parseInstrument(market, query);
        try {
            Map<String, Object> resolved = switch (instrument.market()) {
                case "a_share" -> quoteTencent(instrument);
                case "us_stock" -> quoteYahoo(instrument);
                case "crypto" -> quoteBinance(instrument);
                default -> Map.of();
            };
            Object resolvedName = resolved.get("name");
            if (resolvedName instanceof String name && !name.isBlank()) {
                instrument = instrument.withName(name.trim());
            }
        } catch (Exception e) {
            // 解析本身仍可返回标准化代码；行情源暂时不可用时由后续报价接口给出明确错误。
            log.info("自定义标的名称解析暂不可用 market={}, symbol={}, message={}",
                    instrument.market(), instrument.symbol(), e.getMessage());
        }
        return instrumentView(instrument);
    }

    /** 返回交易时段状态；节假日历未接入，因此只按工作日和交易时段判断。 */
    public Map<String, Object> session(String market) {
        String normalizedMarket = normalizeMarket(market);
        LocalDateTime now = LocalDateTime.now(zoneFor(normalizedMarket));
        boolean weekday = now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
        boolean open = switch (normalizedMarket) {
            case "crypto" -> true;
            case "a_share" -> weekday && inAnySession(now.toLocalTime(),
                    LocalTime.of(9, 30), LocalTime.of(11, 30),
                    LocalTime.of(13, 0), LocalTime.of(15, 0));
            case "us_stock" -> weekday && inAnySession(now.toLocalTime(),
                    LocalTime.of(9, 30), LocalTime.of(16, 0));
            default -> false;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", normalizedMarket);
        out.put("is_open", open);
        out.put("status", open ? "open" : "closed");
        out.put("label", open ? "交易中" : "非交易时段");
        out.put("timezone", zoneFor(normalizedMarket).getId());
        out.put("checked_at", now.toString());
        out.put("disclaimer", "交易状态按工作日和常规时段估算，未接入交易所节假日历");
        return out;
    }

    public Map<String, Object> quote(String market, String symbol) {
        Instrument instrument = requireInstrument(market, symbol);
        try {
            return fetchWithOfflineFallback(
                    new CacheKey("quote", instrument.market(), instrument.symbol(), "", 0),
                    () -> switch (instrument.market()) {
                        case "a_share" -> quoteTencent(instrument);
                        case "us_stock" -> quoteYahoo(instrument);
                        case "crypto" -> quoteCryptoWithFallback(instrument);
                        default -> throw invalid("不支持的市场: " + instrument.market());
                    });
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扩展行情源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "行情源暂不可用，请稍后重试");
        }
    }

    public Map<String, Object> kline(String market, String symbol, String interval, int limit) {
        Instrument instrument = requireInstrument(market, symbol);
        if (limit < 1 || limit > 500) {
            throw invalid("limit 必须在 1~500 之间");
        }
        String normalized = normalizeInterval(interval);
        try {
            return fetchWithOfflineFallback(
                    new CacheKey("kline", instrument.market(), instrument.symbol(), normalized, limit),
                    () -> buildKline(instrument, normalized, limit));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扩展K线源调用失败 market={}, symbol={}, message={}", market, symbol, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "K线源暂不可用，请稍后重试");
        }
    }

    private Map<String, Object> buildKline(Instrument instrument, String normalized, int limit) throws Exception {
        List<Map<String, Object>> data = switch (instrument.market()) {
            case "a_share" -> "1d".equals(normalized)
                    ? klineTencent(instrument, limit)
                    : klineTencentIntraday(instrument, normalized, limit);
            case "us_stock" -> klineYahoo(instrument, normalized, limit);
            case "crypto" -> klineCryptoWithFallback(instrument, normalized, limit);
            default -> throw invalid("不支持的市场: " + instrument.market());
        };
        Map<String, Object> technicalAnalysis = enrichTechnicalIndicators(data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", instrument.market());
        out.put("symbol", instrument.symbol());
        out.put("interval", normalized);
        out.put("count", data.size());
        out.put("data", data);
        out.put("analysis", technicalAnalysis);
        if (!data.isEmpty()) {
            out.put("range", Map.of(
                    "start", data.get(0).get("date"),
                    "end", data.get(data.size() - 1).get("date"),
                    "count", data.size()));
        }
        return out;
    }

    private Map<String, Object> quoteTencent(Instrument instrument) {
        byte[] bytes = webClient.get()
                .uri("https://qt.gtimg.cn/q=" + instrument.symbol())
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
        if (bytes == null) throw upstream("A股行情为空");
        String raw = new String(bytes, GBK);
        Matcher matcher = Pattern.compile("\\\"(.+?)\\\"").matcher(raw);
        if (!matcher.find()) throw upstream("A股行情格式异常");
        String[] values = matcher.group(1).split("~", -1);
        Double price = parseDouble(values, 3);
        Double previous = parseDouble(values, 4);
        if (price == null) throw upstream("A股价格为空");
        Map<String, Object> out = quoteBase(instrument);
        if (values.length > 1 && values[1] != null && !values[1].isBlank()) {
            out.put("name", values[1].trim());
        }
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", parseDouble(values, 31));
        out.put("change_pct", parseDouble(values, 32));
        out.put("open", parseDouble(values, 5));
        out.put("high", parseDouble(values, 33));
        out.put("low", parseDouble(values, 34));
        out.put("quote_time", LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> quoteYahoo(Instrument instrument) throws Exception {
        return quoteYahoo(instrument, yahooStockSymbol(instrument.symbol()));
    }

    private Map<String, Object> quoteYahoo(Instrument instrument, String providerSymbol) throws Exception {
        JsonNode result = yahooResult(providerSymbol, "1d", "1d");
        JsonNode meta = result.path("meta");
        Double price = number(meta, "regularMarketPrice");
        Double previous = number(meta, "previousClose");
        if (previous == null) previous = number(meta, "chartPreviousClose");
        if (price == null) throw upstream("美股价格为空");
        Map<String, Object> out = quoteBase(instrument);
        String displayName = meta.path("longName").asText(meta.path("shortName").asText(""));
        if (!displayName.isBlank()) out.put("name", displayName);
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", price - (previous == null ? price : previous));
        out.put("change_pct", previous == null || previous == 0 ? 0.0 : (price - previous) / previous * 100.0);
        out.put("quote_time", meta.path("regularMarketTime").isNumber()
                ? Instant.ofEpochSecond(meta.path("regularMarketTime").asLong()).toString()
                : LocalDateTime.now().toString());
        return out;
    }

    private Map<String, Object> quoteCryptoWithFallback(Instrument instrument) throws Exception {
        try {
            return quoteBinance(instrument);
        } catch (Exception binanceError) {
            log.warn("Binance 行情不可用，切换 Yahoo 备用源 symbol={}, message={}",
                    instrument.symbol(), binanceError.getMessage());
            Map<String, Object> fallback = quoteYahoo(instrument, yahooCryptoSymbol(instrument.symbol()));
            fallback.put("source", "Yahoo Finance (fallback)");
            return fallback;
        }
    }

    private Map<String, Object> quoteBinance(Instrument instrument) throws Exception {
        JsonNode root = objectMapper.readTree(webClient.get()
                .uri("https://api.binance.com/api/v3/ticker/24hr?symbol=" + instrument.symbol())
                .retrieve()
                .bodyToMono(String.class)
                .block());
        Double price = textDouble(root, "lastPrice");
        Double previous = textDouble(root, "prevClosePrice");
        if (price == null) throw upstream("加密货币价格为空");
        Map<String, Object> out = quoteBase(instrument);
        out.put("price", price);
        out.put("prev_close", previous);
        out.put("change", textDouble(root, "priceChange"));
        out.put("change_pct", textDouble(root, "priceChangePercent"));
        out.put("open", textDouble(root, "openPrice"));
        out.put("high", textDouble(root, "highPrice"));
        out.put("low", textDouble(root, "lowPrice"));
        out.put("quote_time", root.path("closeTime").isNumber()
                ? Instant.ofEpochMilli(root.path("closeTime").asLong()).toString()
                : LocalDateTime.now().toString());
        return out;
    }

    private List<Map<String, Object>> klineTencent(Instrument instrument, int limit) throws Exception {
        String param = instrument.symbol() + ",day,,,500,qfq";
        String body = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("web.ifzq.gtimg.cn")
                        .path("/appstock/app/fqkline/get")
                        .queryParam("param", param).build())
                .retrieve().bodyToMono(String.class).block();
        JsonNode root = objectMapper.readTree(body);
        JsonNode raw = root.path("data").path(instrument.symbol()).path("day");
        if (!raw.isArray()) raw = root.path("data").path(instrument.symbol()).path("qfqday");
        if (!raw.isArray()) throw upstream("A股K线为空");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isArray() || item.size() < 5) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", item.get(0).asText());
            row.put("open", item.get(1).asDouble());
            row.put("close", item.get(2).asDouble());
            row.put("high", item.get(3).asDouble());
            row.put("low", item.get(4).asDouble());
            row.put("volume", item.size() > 5 ? item.get(5).asDouble(0.0) : 0.0);
            out.add(row);
        }
        return tail(out, limit);
    }

    private List<Map<String, Object>> klineTencentIntraday(Instrument instrument,
                                                            String interval,
                                                            int limit) throws Exception {
        int minutes = switch (interval) {
            case "5m", "10m", "15m", "30m" -> Integer.parseInt(interval.substring(0, interval.length() - 1));
            case "1h" -> 60;
            default -> throw invalid("A股不支持该周期: " + interval);
        };
        int sourceMinutes = minutes == 10 ? 5 : minutes;
        int sourceLimit = Math.min(1000, minutes == 10 ? limit * 3 : limit);
        String body = webClient.get()
                .uri(uriBuilder -> uriBuilder.scheme("https").host("push2his.eastmoney.com")
                        .path("/api/qt/stock/kline/get")
                        .queryParam("secid", eastmoneySecId(instrument))
                        .queryParam("klt", sourceMinutes)
                        .queryParam("fqt", 1)
                        .queryParam("beg", 0)
                        .queryParam("end", 20500000)
                        .queryParam("lmt", sourceLimit)
                        .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                        .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                        .build())
                .retrieve().bodyToMono(String.class).block();
        JsonNode raw = objectMapper.readTree(body).path("data").path("klines");
        if (!raw.isArray() || raw.isEmpty()) throw upstream("A股分钟K线为空");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : raw) {
            String[] values = item.asText().split(",", -1);
            if (values.length < 6) continue;
            Double open = parseDouble(values[1]);
            Double close = parseDouble(values[2]);
            Double high = parseDouble(values[3]);
            Double low = parseDouble(values[4]);
            if (open == null || close == null || high == null || low == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", values[0]);
            row.put("open", open);
            row.put("close", close);
            row.put("high", high);
            row.put("low", low);
            row.put("volume", parseDouble(values[5]) == null ? 0.0 : parseDouble(values[5]));
            out.add(row);
        }
        return minutes == sourceMinutes ? tail(out, limit) : aggregateCandles(out, minutes, limit);
    }

    private String eastmoneySecId(Instrument instrument) {
        String symbol = instrument.symbol().toLowerCase(Locale.ROOT);
        return (symbol.startsWith("sh") ? "1." : "0.") + symbol.substring(2);
    }

    private List<Map<String, Object>> klineYahoo(Instrument instrument, String interval, int limit) throws Exception {
        return klineYahoo(instrument, yahooStockSymbol(instrument.symbol()), interval, limit);
    }

    private List<Map<String, Object>> klineYahoo(Instrument instrument, String providerSymbol,
                                                 String interval, int limit) throws Exception {
        if ("10m".equals(interval)) {
            return aggregateCandles(klineYahoo(instrument, providerSymbol, "5m", Math.min(500, limit * 3)), 10, limit);
        }
        JsonNode result = yahooResult(providerSymbol, yahooRange(interval), interval);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            if (!numberAt(quote.path("open"), i) || !numberAt(quote.path("close"), i)
                    || !numberAt(quote.path("high"), i) || !numberAt(quote.path("low"), i)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", Instant.ofEpochSecond(timestamps.get(i).asLong()).toString());
            row.put("open", quote.path("open").get(i).asDouble());
            row.put("close", quote.path("close").get(i).asDouble());
            row.put("high", quote.path("high").get(i).asDouble());
            row.put("low", quote.path("low").get(i).asDouble());
            row.put("volume", numberAt(quote.path("volume"), i) ? quote.path("volume").get(i).asDouble() : 0.0);
            out.add(row);
        }
        return tail(out, limit);
    }

    private String yahooStockSymbol(String symbol) {
        return symbol.replace('.', '-');
    }

    private String yahooRange(String interval) {
        return "1d".equals(interval) ? "1y" : "5d";
    }

    private List<Map<String, Object>> klineCryptoWithFallback(Instrument instrument,
                                                               String interval, int limit) throws Exception {
        try {
            if ("10m".equals(interval)) {
                return aggregateCandles(klineBinance(instrument, "5m", Math.min(1000, limit * 3)), 10, limit);
            }
            return klineBinance(instrument, interval, limit);
        } catch (Exception binanceError) {
            log.warn("Binance K线不可用，切换 Yahoo 备用源 symbol={}, message={}",
                    instrument.symbol(), binanceError.getMessage());
            if ("10m".equals(interval)) {
                return aggregateCandles(klineYahoo(instrument, yahooCryptoSymbol(instrument.symbol()), "5m",
                        Math.min(500, limit * 3)), 10, limit);
            }
            return klineYahoo(instrument, yahooCryptoSymbol(instrument.symbol()), interval, limit);
        }
    }

    private List<Map<String, Object>> klineBinance(Instrument instrument, String interval, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(webClient.get()
                .uri("https://api.binance.com/api/v3/klines?symbol=" + instrument.symbol()
                        + "&interval=" + interval + "&limit=" + limit)
                .retrieve().bodyToMono(String.class).block());
        if (!root.isArray()) throw upstream("加密货币K线为空");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isArray() || item.size() < 6) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", Instant.ofEpochMilli(item.get(0).asLong()).toString());
            row.put("open", item.get(1).asDouble());
            row.put("high", item.get(2).asDouble());
            row.put("low", item.get(3).asDouble());
            row.put("close", item.get(4).asDouble());
            row.put("volume", item.get(5).asDouble());
            out.add(row);
        }
        return out;
    }

    private JsonNode yahooResult(String symbol, String range, String interval) throws Exception {
        if (!YAHOO_INTERVALS.contains(interval)) throw invalid("美股不支持该周期: " + interval);
        String body = webClient.get()
                .uri("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                        + "?range=" + range + "&interval=" + interval)
                .retrieve().bodyToMono(String.class).block();
        JsonNode result = objectMapper.readTree(body).path("chart").path("result").path(0);
        if (result.isMissingNode() || result.isNull()) throw upstream("美股行情为空");
        return result;
    }

    private List<Map<String, Object>> aggregateCandles(List<Map<String, Object>> rows, int minutes, int limit) {
        long bucketSize = minutes * 60L;
        Map<Long, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long epoch = epochSeconds(row.get("date"));
            long bucket = Math.floorDiv(epoch, bucketSize) * bucketSize;
            Map<String, Object> target = buckets.computeIfAbsent(bucket, ignored -> {
                Map<String, Object> initial = new LinkedHashMap<>();
                initial.put("date", row.get("date"));
                initial.put("open", row.get("open"));
                initial.put("close", row.get("close"));
                initial.put("high", row.get("high"));
                initial.put("low", row.get("low"));
                initial.put("volume", row.getOrDefault("volume", 0.0));
                return initial;
            });
            target.put("close", row.get("close"));
            target.put("high", Math.max(((Number) target.get("high")).doubleValue(), ((Number) row.get("high")).doubleValue()));
            target.put("low", Math.min(((Number) target.get("low")).doubleValue(), ((Number) row.get("low")).doubleValue()));
            target.put("volume", ((Number) target.get("volume")).doubleValue()
                    + ((Number) row.getOrDefault("volume", 0.0)).doubleValue());
        }
        return tail(new ArrayList<>(buckets.values()), limit);
    }

    private long epochSeconds(Object value) {
        String date = String.valueOf(value);
        try {
            return Instant.parse(date).getEpochSecond();
        } catch (Exception ignored) {
            return LocalDateTime.parse(date, INTRADAY_DATE).atZone(SHANGHAI_ZONE).toEpochSecond();
        }
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank() || "day".equalsIgnoreCase(interval)) return "1d";
        return interval.trim().toLowerCase();
    }

    private String yahooCryptoSymbol(String symbol) {
        if (symbol != null && symbol.endsWith("USDT") && symbol.length() > 4) {
            return symbol.substring(0, symbol.length() - 4) + "-USD";
        }
        throw invalid("不支持的加密货币标的: " + symbol);
    }

    private Instrument requireInstrument(String market, String symbol) {
        if (market == null || symbol == null) throw invalid("市场和标的不能为空");
        String normalizedMarket = normalizeMarket(market);
        String normalizedSymbol = symbol.trim();
        return instruments.stream()
                .filter(item -> item.market().equals(normalizedMarket)
                        && item.symbol().equalsIgnoreCase(normalizedSymbol))
                .findFirst()
                .orElseGet(() -> parseInstrument(normalizedMarket, normalizedSymbol));
    }

    private Instrument parseInstrument(String market, String query) {
        if (market == null || query == null || query.isBlank()) {
            throw invalid("市场和标的不能为空");
        }
        String normalizedMarket = normalizeMarket(market);
        String raw = query.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedMarket) {
            case "a_share" -> parseAShare(raw);
            case "us_stock" -> parseUsStock(raw);
            case "crypto" -> parseCrypto(raw);
            default -> throw invalid("不支持的市场: " + market);
        };
    }

    private String normalizeMarket(String market) {
        String normalized = market.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("a_share", "us_stock", "crypto").contains(normalized)) {
            throw invalid("不支持的市场: " + market);
        }
        return normalized;
    }

    private Instrument parseAShare(String raw) {
        String compact = raw.replace(".", "").replace("-", "");
        Matcher matcher = A_SHARE_PATTERN.matcher(compact);
        if (!matcher.matches()) throw invalid("A股代码应为 6 位数字，例如 600519 或 SH600519");
        String exchange = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String code = matcher.group(2);
        if (exchange == null) {
            exchange = code.startsWith("6") ? "SH" : code.startsWith("4") || code.startsWith("8") ? "BJ" : "SZ";
        }
        String symbol = exchange.toLowerCase(Locale.ROOT) + code;
        String name = switch (symbol) {
            case "sh600519" -> "贵州茅台";
            case "sz000001" -> "平安银行";
            case "sz300750" -> "宁德时代";
            default -> code + "（自定义标的）";
        };
        return new Instrument("a_share", symbol, name, "CNY", "Tencent");
    }

    private Instrument parseUsStock(String raw) {
        String symbol = raw.startsWith("$") ? raw.substring(1) : raw;
        if (!US_STOCK_PATTERN.matcher(symbol).matches()) {
            throw invalid("美股代码格式不正确，例如 AAPL、MSFT 或 BRK.B");
        }
        String name = switch (symbol) {
            case "AAPL" -> "Apple";
            case "MSFT" -> "Microsoft";
            case "NVDA" -> "NVIDIA";
            case "TSLA" -> "Tesla";
            default -> symbol + "（自定义标的）";
        };
        return new Instrument("us_stock", symbol, name, "USD", "Yahoo Finance");
    }

    private Instrument parseCrypto(String raw) {
        String symbol = raw.replace("-", "");
        if (!CRYPTO_PATTERN.matcher(symbol).matches()) {
            throw invalid("加密货币代码格式不正确，例如 BTC、BTCUSDT 或 ETHUSDT");
        }
        if (!symbol.endsWith("USDT")) symbol += "USDT";
        String name = switch (symbol) {
            case "BTCUSDT" -> "Bitcoin";
            case "ETHUSDT" -> "Ethereum";
            case "SOLUSDT" -> "Solana";
            default -> symbol + "（自定义标的）";
        };
        return new Instrument("crypto", symbol, name, "USDT", "Binance");
    }

    private Map<String, Object> instrumentView(Instrument item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", item.market());
        out.put("symbol", item.symbol());
        out.put("name", item.name());
        out.put("currency", item.currency());
        out.put("source", item.source());
        return out;
    }

    private Map<String, Object> quoteBase(Instrument item) {
        Map<String, Object> out = instrumentView(item);
        out.put("quote_time", LocalDateTime.now().atZone(ZoneId.of("Asia/Shanghai")).toString());
        return out;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> rows, int limit) {
        int from = Math.max(0, rows.size() - limit);
        return new ArrayList<>(rows.subList(from, rows.size()));
    }

    /**
     * 计算可复核的基础技术指标。这里不使用大模型，也不输出买卖建议；AI 只负责在这些数据之上生成研究性解读。
     */
    Map<String, Object> enrichTechnicalIndicators(List<Map<String, Object>> rows) {
        List<Double> closes = rows.stream().map(row -> numeric(row, "close")).toList();
        List<Double> highs = rows.stream().map(row -> numeric(row, "high")).toList();
        List<Double> lows = rows.stream().map(row -> numeric(row, "low")).toList();
        List<Double> volumes = rows.stream().map(row -> numeric(row, "volume")).toList();
        List<Double> ema12 = ema(closes, 12);
        List<Double> ema26 = ema(closes, 26);
        List<Double> macd = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            macd.add(ema12.get(i) == null || ema26.get(i) == null ? null : ema12.get(i) - ema26.get(i));
        }
        List<Double> signal = ema(macd, 9);
        List<Double> rsi14 = rsi(closes, 14);
        List<Double> sma5 = sma(closes, 5);
        List<Double> sma20 = sma(closes, 20);
        List<Double> bollingerStd20 = rollingStdDev(closes, 20);
        List<Double> bollingerUpper = combine(sma20, bollingerStd20, (middle, deviation) -> middle + deviation * 2);
        List<Double> bollingerLower = combine(sma20, bollingerStd20, (middle, deviation) -> middle - deviation * 2);
        List<Double> atr14 = atr(rows, 14);
        List<Double> stochasticK14 = stochasticK(closes, highs, lows, 14);
        List<Double> stochasticD3 = sma(stochasticK14, 3);
        List<Double> williamsR14 = williamsR(closes, highs, lows, 14);
        List<Double> adx14 = adx(rows, 14);
        List<Double> volumeSma20 = sma(volumes, 20);
        List<Double> obv = obv(closes, volumes);
        List<Double> roc12 = rateOfChange(closes, 12);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            putIfPresent(row, "sma5", at(sma5, i));
            putIfPresent(row, "sma20", at(sma20, i));
            putIfPresent(row, "ema12", at(ema12, i));
            putIfPresent(row, "ema26", at(ema26, i));
            putIfPresent(row, "rsi14", at(rsi14, i));
            putIfPresent(row, "bollinger_middle", at(sma20, i));
            putIfPresent(row, "bollinger_upper", at(bollingerUpper, i));
            putIfPresent(row, "bollinger_lower", at(bollingerLower, i));
            putIfPresent(row, "atr14", at(atr14, i));
            putIfPresent(row, "stoch_k14", at(stochasticK14, i));
            putIfPresent(row, "stoch_d3", at(stochasticD3, i));
            putIfPresent(row, "williams_r14", at(williamsR14, i));
            putIfPresent(row, "adx14", at(adx14, i));
            putIfPresent(row, "volume_sma20", at(volumeSma20, i));
            putIfPresent(row, "obv", at(obv, i));
            putIfPresent(row, "roc12", at(roc12, i));
            Double macdValue = at(macd, i);
            Double signalValue = at(signal, i);
            putIfPresent(row, "macd", macdValue);
            putIfPresent(row, "macd_signal", signalValue);
            putIfPresent(row, "macd_histogram",
                    macdValue == null || signalValue == null ? null : macdValue - signalValue);
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            analysis.put("status", "insufficient_data");
            analysis.put("message", "暂无足够K线数据");
            return analysis;
        }
        int latestIndex = rows.size() - 1;
        Double close = numeric(rows.get(latestIndex), "close");
        Double latestSma20 = at(sma20, latestIndex);
        Double latestEma12 = at(ema12, latestIndex);
        Double latestEma26 = at(ema26, latestIndex);
        Double latestRsi = at(rsi14, latestIndex);
        Double latestMacd = at(macd, latestIndex);
        Double latestSignal = at(signal, latestIndex);
        Double latestBollingerMiddle = at(sma20, latestIndex);
        Double latestBollingerUpper = at(bollingerUpper, latestIndex);
        Double latestBollingerLower = at(bollingerLower, latestIndex);

        String trend = "neutral";
        String trendLabel = "震荡观察";
        if (close != null && latestSma20 != null && latestEma12 != null && latestEma26 != null) {
            if (close > latestSma20 && latestEma12 > latestEma26) {
                trend = "bullish";
                trendLabel = "偏强：价格在20期均线上方，短期均线向上";
            } else if (close < latestSma20 && latestEma12 < latestEma26) {
                trend = "bearish";
                trendLabel = "偏弱：价格在20期均线下方，短期均线向下";
            }
        }
        String momentum = "normal";
        String momentumLabel = "动能中性";
        if (latestRsi != null && latestRsi >= 70) {
            momentum = "overbought";
            momentumLabel = "RSI偏高，注意短线过热";
        } else if (latestRsi != null && latestRsi <= 30) {
            momentum = "oversold";
            momentumLabel = "RSI偏低，注意短线超跌";
        }

        int windowStart = Math.max(0, rows.size() - 20);
        double support = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "low") == null ? Double.POSITIVE_INFINITY : numeric(row, "low"))
                .min().orElse(Double.NaN);
        double resistance = rows.subList(windowStart, rows.size()).stream()
                .mapToDouble(row -> numeric(row, "high") == null ? Double.NEGATIVE_INFINITY : numeric(row, "high"))
                .max().orElse(Double.NaN);

        analysis.put("status", "ok");
        analysis.put("trend", trend);
        analysis.put("trend_label", trendLabel);
        analysis.put("momentum", momentum);
        analysis.put("momentum_label", momentumLabel);
        analysis.put("support_20", finiteOrNull(support));
        analysis.put("resistance_20", finiteOrNull(resistance));
        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("close", close);
        indicators.put("sma20", latestSma20);
        indicators.put("ema12", latestEma12);
        indicators.put("ema26", latestEma26);
        indicators.put("rsi14", latestRsi);
        indicators.put("macd", latestMacd);
        indicators.put("macd_signal", latestSignal);
        indicators.put("bollinger_middle", latestBollingerMiddle);
        indicators.put("bollinger_upper", latestBollingerUpper);
        indicators.put("bollinger_lower", latestBollingerLower);
        indicators.put("bollinger_width", latestBollingerMiddle == null || latestBollingerMiddle == 0
                || latestBollingerUpper == null || latestBollingerLower == null
                ? null : (latestBollingerUpper - latestBollingerLower) / latestBollingerMiddle * 100);
        indicators.put("bollinger_position", close == null || latestBollingerUpper == null
                || latestBollingerLower == null || latestBollingerUpper.equals(latestBollingerLower)
                ? null : (close - latestBollingerLower) / (latestBollingerUpper - latestBollingerLower) * 100);
        indicators.put("atr14", at(atr14, latestIndex));
        indicators.put("stoch_k14", at(stochasticK14, latestIndex));
        indicators.put("stoch_d3", at(stochasticD3, latestIndex));
        indicators.put("williams_r14", at(williamsR14, latestIndex));
        indicators.put("adx14", at(adx14, latestIndex));
        indicators.put("volume_sma20", at(volumeSma20, latestIndex));
        indicators.put("obv", at(obv, latestIndex));
        indicators.put("roc12", at(roc12, latestIndex));
        analysis.put("indicators", indicators);
        analysis.put("disclaimer", "技术指标仅供研究参考，不构成投资建议");
        return analysis;
    }

    private List<Double> rollingStdDev(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 < period) {
                out.add(null);
                continue;
            }
            List<Double> window = values.subList(i - period + 1, i + 1);
            if (window.stream().anyMatch(value -> value == null)) {
                out.add(null);
                continue;
            }
            double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double variance = window.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(Double.NaN);
            out.add(Double.isFinite(variance) ? Math.sqrt(variance) : null);
        }
        return out;
    }

    private List<Double> combine(List<Double> left, List<Double> right, java.util.function.DoubleBinaryOperator operator) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            Double a = at(left, i);
            Double b = at(right, i);
            out.add(a == null || b == null ? null : operator.applyAsDouble(a, b));
        }
        return out;
    }

    private List<Double> atr(List<Map<String, Object>> rows, int period) {
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Double high = numeric(rows.get(i), "high");
            Double low = numeric(rows.get(i), "low");
            Double previousClose = i == 0 ? null : numeric(rows.get(i - 1), "close");
            if (high == null || low == null) {
                trueRanges.add(null);
            } else if (previousClose == null) {
                trueRanges.add(high - low);
            } else {
                trueRanges.add(Math.max(high - low,
                        Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose))));
            }
        }
        return sma(trueRanges, period);
    }

    private List<Double> stochasticK(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            if (i + 1 < period) {
                out.add(null);
                continue;
            }
            List<Double> highWindow = highs.subList(i - period + 1, i + 1);
            List<Double> lowWindow = lows.subList(i - period + 1, i + 1);
            Double close = closes.get(i);
            if (close == null || highWindow.stream().anyMatch(value -> value == null)
                    || lowWindow.stream().anyMatch(value -> value == null)) {
                out.add(null);
                continue;
            }
            double highest = highWindow.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            double lowest = lowWindow.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            out.add(highest == lowest ? 50.0 : (close - lowest) / (highest - lowest) * 100);
        }
        return out;
    }

    private List<Double> williamsR(List<Double> closes, List<Double> highs, List<Double> lows, int period) {
        List<Double> stochastic = stochasticK(closes, highs, lows, period);
        return stochastic.stream().map(value -> value == null ? null : value - 100).toList();
    }

    private List<Double> adx(List<Map<String, Object>> rows, int period) {
        List<Double> trueRanges = new ArrayList<>();
        List<Double> plusDirectional = new ArrayList<>();
        List<Double> minusDirectional = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Double high = numeric(rows.get(i), "high");
            Double low = numeric(rows.get(i), "low");
            if (i == 0) {
                trueRanges.add(high == null || low == null ? null : high - low);
                plusDirectional.add(0.0);
                minusDirectional.add(0.0);
                continue;
            }
            Double previousHigh = numeric(rows.get(i - 1), "high");
            Double previousLow = numeric(rows.get(i - 1), "low");
            Double previousClose = numeric(rows.get(i - 1), "close");
            if (high == null || low == null || previousHigh == null || previousLow == null || previousClose == null) {
                trueRanges.add(null);
                plusDirectional.add(null);
                minusDirectional.add(null);
                continue;
            }
            trueRanges.add(Math.max(high - low,
                    Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose))));
            double upMove = high - previousHigh;
            double downMove = previousLow - low;
            plusDirectional.add(upMove > downMove && upMove > 0 ? upMove : 0.0);
            minusDirectional.add(downMove > upMove && downMove > 0 ? downMove : 0.0);
        }
        List<Double> dx = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (i + 1 < period || trueRanges.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)
                    || plusDirectional.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)
                    || minusDirectional.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)) {
                dx.add(null);
                continue;
            }
            double trSum = trueRanges.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double plusSum = plusDirectional.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double minusSum = minusDirectional.subList(i - period + 1, i + 1).stream().mapToDouble(Double::doubleValue).sum();
            double plusDi = trSum == 0 ? 0 : plusSum / trSum * 100;
            double minusDi = trSum == 0 ? 0 : minusSum / trSum * 100;
            double denominator = plusDi + minusDi;
            dx.add(denominator == 0 ? 0 : Math.abs(plusDi - minusDi) / denominator * 100);
        }
        return sma(dx, period);
    }

    private List<Double> obv(List<Double> closes, List<Double> volumes) {
        List<Double> out = new ArrayList<>();
        double value = 0;
        for (int i = 0; i < closes.size(); i++) {
            Double close = closes.get(i);
            Double volume = volumes.get(i);
            if (i > 0 && close != null && closes.get(i - 1) != null && volume != null) {
                if (close > closes.get(i - 1)) value += volume;
                else if (close < closes.get(i - 1)) value -= volume;
            }
            out.add(close == null ? null : value);
        }
        return out;
    }

    private List<Double> rateOfChange(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i < period || values.get(i) == null || values.get(i - period) == null || values.get(i - period) == 0) {
                out.add(null);
            } else {
                out.add((values.get(i) - values.get(i - period)) / values.get(i - period) * 100);
            }
        }
        return out;
    }

    private List<Double> sma(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (i + 1 < period || values.subList(i - period + 1, i + 1).stream().anyMatch(value -> value == null)) {
                out.add(null);
            } else {
                out.add(values.subList(i - period + 1, i + 1).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
            }
        }
        return out;
    }

    private List<Double> ema(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        double multiplier = 2.0 / (period + 1);
        Double previous = null;
        for (Double value : values) {
            if (value == null) {
                out.add(null);
                continue;
            }
            previous = previous == null ? value : (value - previous) * multiplier + previous;
            out.add(previous);
        }
        return out;
    }

    private List<Double> rsi(List<Double> values, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) out.add(null);
        if (values.size() <= period) return out;
        double gains = 0;
        double losses = 0;
        for (int i = 1; i <= period; i++) {
            Double change = change(values, i);
            if (change == null) return out;
            if (change >= 0) gains += change;
            else losses -= change;
        }
        double averageGain = gains / period;
        double averageLoss = losses / period;
        out.set(period, rsiValue(averageGain, averageLoss));
        for (int i = period + 1; i < values.size(); i++) {
            Double change = change(values, i);
            if (change == null) continue;
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            averageGain = (averageGain * (period - 1) + gain) / period;
            averageLoss = (averageLoss * (period - 1) + loss) / period;
            out.set(i, rsiValue(averageGain, averageLoss));
        }
        return out;
    }

    private Double rsiValue(double averageGain, double averageLoss) {
        if (averageLoss == 0) return 100.0;
        return 100.0 - 100.0 / (1 + averageGain / averageLoss);
    }

    private Double change(List<Double> values, int index) {
        if (index <= 0 || index >= values.size() || values.get(index) == null || values.get(index - 1) == null) {
            return null;
        }
        return values.get(index) - values.get(index - 1);
    }

    private Double numeric(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double at(List<Double> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    private void putIfPresent(Map<String, Object> row, String key, Double value) {
        if (value != null && Double.isFinite(value)) row.put(key, value);
    }

    private Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private boolean numberAt(JsonNode node, int index) {
        return node != null && node.isArray() && index < node.size()
                && node.get(index) != null && node.get(index).isNumber();
    }

    private Double number(JsonNode node, String field) {
        return node != null && node.path(field).isNumber() ? node.path(field).asDouble() : null;
    }

    private Double textDouble(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return parseDouble(value);
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String[] values, int index) {
        return index < values.length ? parseDouble(values[index]) : null;
    }

    private ZoneId zoneFor(String market) {
        return "us_stock".equals(market) ? NEW_YORK_ZONE : SHANGHAI_ZONE;
    }

    private boolean inAnySession(LocalTime current, LocalTime... boundaries) {
        for (int i = 0; i + 1 < boundaries.length; i += 2) {
            if (!current.isBefore(boundaries[i]) && current.isBefore(boundaries[i + 1])) return true;
        }
        return false;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException upstream(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private record Instrument(String market, String symbol, String name, String currency, String source) {
        private Instrument withName(String resolvedName) {
            return new Instrument(market, symbol, resolvedName, currency, source);
        }
    }
}
