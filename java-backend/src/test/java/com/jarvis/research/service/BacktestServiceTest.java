package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestServiceTest {

    @Test
    void sellTradeKeepsActualQuantityAndReturnsStableMetrics() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);

        // 先上涨触发买入，再下跌触发卖出。
        double[] closes = {
                10, 10, 10, 10, 10,
                11, 12, 13, 14, 15,
                14, 13, 12, 11, 10,
                9, 8, 8, 8, 8
        };
        for (int i = 0; i < closes.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", start.plusDays(i).toString());
            row.put("close", closes[i]);
            rows.add(row);
        }
        when(marketDataService.getDailyKline("gold_etf", 20))
                .thenReturn(Map.of("data", rows));

        BacktestService service = new BacktestService(marketDataService);
        Map<String, Object> result = service.run("gold_etf", 3, 5, 100000.0, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trades = (List<Map<String, Object>>) result.get("trades");
        assertFalse(trades.isEmpty());
        Map<String, Object> sell = trades.stream()
                .filter(t -> "SELL".equals(t.get("type")))
                .findFirst()
                .orElseThrow();
        assertTrue(((Number) sell.get("qty")).doubleValue() > 0.0,
                "SELL 成交数量不能在清仓后被记录成0");
        assertTrue(Double.isFinite(((Number) result.get("annual_return_pct")).doubleValue()));
        assertEquals(20, ((Number) ((Map<?, ?>) result.get("range")).get("bars")).intValue());
    }

    @Test
    void rejectsInvalidMovingAverageParameters() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        BacktestService service = new BacktestService(marketDataService);
        assertThrows(IllegalArgumentException.class,
                () -> service.run("gold_etf", 20, 5, 100000.0, 120));
    }
}
