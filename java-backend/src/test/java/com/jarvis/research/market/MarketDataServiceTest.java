package com.jarvis.research.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.config.JarvisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MarketDataServiceTest {

    @Test
    void dailyKlineQueryIsReadOnlyAndReturnsAscendingBars() {
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        KlineDailyRepository klineRepo = mock(KlineDailyRepository.class);
        MarketDataService service = new MarketDataService(
                new JarvisProperties(), snapshotRepo, klineRepo, new ObjectMapper());

        KlineDaily newest = new KlineDaily(
                "gold_etf", "2026-09-04", 10.1, 10.4, 10.5, 10.0, 1200.0);
        KlineDaily older = new KlineDaily(
                "gold_etf", "2026-09-03", 9.8, 10.0, 10.2, 9.7, 1000.0);
        when(klineRepo.findByMarketOrderByDateDesc(eq("gold_etf"), any(Pageable.class)))
                .thenReturn(List.of(newest, older));

        Map<String, Object> result = service.getDailyKline("gold_etf", 2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertEquals(2, data.size());
        assertEquals("2026-09-03", data.get(0).get("date"));
        assertEquals("2026-09-04", data.get(1).get("date"));

        verify(klineRepo).findByMarketOrderByDateDesc(eq("gold_etf"), any(Pageable.class));
        verify(klineRepo, never()).save(any());
        verify(klineRepo, never()).saveAll(any());
        verify(klineRepo, never()).findByMarketAndDate(anyString(), anyString());
        verifyNoInteractions(snapshotRepo);
    }
}
