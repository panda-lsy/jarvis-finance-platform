package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtendedMarketDataServiceTest {

    private final ExtendedMarketDataService service = new ExtendedMarketDataService(new ObjectMapper());

    @Test
    void exposesAllowListedAUsAndCryptoInstruments() {
        var instruments = service.listInstruments();
        assertFalse(instruments.isEmpty());
        assertEquals(10, instruments.size());
        assertEquals("a_share", instruments.get(0).get("market"));
    }

    @Test
    void rejectsUnknownSymbolBeforeCallingExternalSource() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.quote("us_stock", "NOT_ALLOWED"));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void rejectsMalformedUserEnteredSymbols() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.resolveInstrument("a_share", "贵州茅台")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.resolveInstrument("us_stock", "AAPL/1")).getStatusCode().value());
    }

    @Test
    void reportsCryptoAsAlwaysOpen() {
        var status = service.session("crypto");
        assertEquals("open", status.get("status"));
        assertEquals(true, status.get("is_open"));
    }

    @Test
    void rejectsInvalidKlineLimit() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.kline("crypto", "BTCUSDT", "1d", 501));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void computesTechnicalIndicatorsAndSummaryFromKlineRows() {
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double close = 100 + i * 0.8 + (i % 4) * 0.2;
            rows.add(new LinkedHashMap<>(java.util.Map.of(
                    "date", "2026-01-" + String.format("%02d", i + 1),
                    "open", close - 0.5,
                    "close", close,
                    "high", close + 1,
                    "low", close - 1,
                    "volume", 1000.0)));
        }

        var summary = service.enrichTechnicalIndicators(rows);

        assertEquals("ok", summary.get("status"));
        assertNotNull(rows.get(39).get("sma20"));
        assertNotNull(rows.get(39).get("rsi14"));
        assertNotNull(rows.get(39).get("macd"));
        assertNotNull(rows.get(39).get("bollinger_upper"));
        assertNotNull(rows.get(39).get("atr14"));
        assertNotNull(rows.get(39).get("stoch_k14"));
        assertNotNull(rows.get(39).get("adx14"));
        assertNotNull(summary.get("indicators"));
        assertNotNull(summary.get("support_20"));
        assertNotNull(summary.get("resistance_20"));
    }

    @Test
    void offlineFallbackServesCachedResultWithStaleFlag() {
        var service = new ExtendedMarketDataService(new ObjectMapper());
        var key = new ExtendedMarketDataService.CacheKey("quote", "a_share", "sh600519", "", 0);
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("price", 1316.01);

        assertEquals(1316.01, service.fetchWithOfflineFallback(key, () -> fresh).get("price"));

        // 上游断网后回退最近成功缓存，并带 stale 标记
        Map<String, Object> fallback = service.fetchWithOfflineFallback(key,
                () -> { throw new RuntimeException("connection refused"); });
        assertEquals(Boolean.TRUE, fallback.get("stale"));
        assertNotNull(fallback.get("cached_at"));
        assertEquals(1316.01, fallback.get("price"));
    }

    @Test
    void offlineFallbackWithoutCacheStillThrows() {
        var service = new ExtendedMarketDataService(new ObjectMapper());
        var key = new ExtendedMarketDataService.CacheKey("kline", "us_stock", "AAPL", "1d", 120);
        assertThrows(RuntimeException.class, () -> service.fetchWithOfflineFallback(key,
                () -> { throw new RuntimeException("connection refused"); }));
    }
}
