package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.user.SimOrder;
import com.jarvis.research.user.SimOrderRepository;
import com.jarvis.research.user.SimPosition;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimOrderServiceTest {

    private SimOrderRepository orderRepository;
    private SimTradeRepository tradeRepository;
    private SimPositionRepository positionRepository;
    private SimTradeService simTradeService;
    private MarketDataService marketDataService;
    private SimOrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(SimOrderRepository.class);
        tradeRepository = mock(SimTradeRepository.class);
        positionRepository = mock(SimPositionRepository.class);
        simTradeService = mock(SimTradeService.class);
        marketDataService = mock(MarketDataService.class);
        service = new SimOrderService(
                orderRepository,
                tradeRepository,
                positionRepository,
                simTradeService,
                marketDataService);

        when(marketDataService.getLatestPrices()).thenReturn(Map.of(
                "gold_etf", Map.of("price", 10.00, "stale", false)));
        when(positionRepository.findByUserIdAndSymbol(7L, "sh518850")).thenReturn(Optional.of(
                SimPosition.builder()
                        .userId(7L)
                        .symbol("sh518850")
                        .quantity(new BigDecimal("8"))
                        .build()));
    }

    @Test
    void sellStopMustStayBelowCurrentMarket() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.placeStopMarket(
                        7L,
                        "SELL",
                        "sh518850",
                        new BigDecimal("8"),
                        BigDecimal.ONE,
                        new BigDecimal("10.10"),
                        "DAY",
                        "stop-direction-test"));

        assertEquals(true, error.getMessage().contains("卖出止损价必须低于当前价"));
        verify(orderRepository, never()).save(any(SimOrder.class));
    }

    @Test
    void validSellStopCanBePersisted() {
        when(orderRepository.findByUserIdAndClientOrderId(7L, "valid-stop")).thenReturn(Optional.empty());
        when(tradeRepository.findByUserIdAndClientOrderId(7L, "valid-stop")).thenReturn(Optional.empty());

        Map<String, Object> result = service.placeStopMarket(
                7L,
                "SELL",
                "sh518850",
                new BigDecimal("8"),
                BigDecimal.ONE,
                new BigDecimal("9.90"),
                "DAY",
                "valid-stop");

        assertEquals("SELL", result.get("side"));
        assertEquals("STOP_MARKET", result.get("orderType"));
        verify(orderRepository).save(any(SimOrder.class));
    }

    @Test
    void editingOpenStopCannotCrossCurrentMarket() {
        SimOrder order = SimOrder.builder()
                .id(33L)
                .userId(7L)
                .symbol("sh518850")
                .side("SELL")
                .orderType("STOP_MARKET")
                .quantity(new BigDecimal("8"))
                .leverage(BigDecimal.ONE)
                .stopPrice(new BigDecimal("9.90"))
                .timeInForce("DAY")
                .status("OPEN")
                .build();
        when(orderRepository.findByIdAndUserId(33L, 7L)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStopPrice(7L, 33L, new BigDecimal("10.01")));

        verify(orderRepository, never()).updateOpenStopPrice(anyLong(), anyLong(), any(BigDecimal.class));
    }
}
