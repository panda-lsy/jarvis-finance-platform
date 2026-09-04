package com.jarvis.research.service;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.market.PriceSnapshot;
import com.jarvis.research.market.PriceSnapshotRepository;
import com.jarvis.research.user.SimAccount;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.SimPosition;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimTradeServiceTest {

    @Test
    void returnOnEquityUsesEachPositionsOwnMarginAndLoan() {
        SimAccountRepository accountRepo = mock(SimAccountRepository.class);
        SimPositionRepository positionRepo = mock(SimPositionRepository.class);
        SimTradeRepository tradeRepo = mock(SimTradeRepository.class);
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        AuditService auditService = mock(AuditService.class);

        SimTradeService service = new SimTradeService(
                accountRepo, positionRepo, tradeRepo, snapshotRepo, auditService);

        SimAccount account = SimAccount.builder()
                .userId(7L)
                .initialCash(new BigDecimal("100000.0000"))
                .cash(new BigDecimal("98000.0000"))
                .loanBalance(new BigDecimal("1500.0000"))
                .frozenMargin(new BigDecimal("1500.0000"))
                .status("ACTIVE")
                .build();
        when(accountRepo.findByUserId(7L)).thenReturn(Optional.of(account));

        SimPosition first = SimPosition.builder()
                .userId(7L).symbol("sh518850")
                .quantity(new BigDecimal("10"))
                .avgCost(new BigDecimal("100"))
                .leverage(new BigDecimal("2"))
                .loanAmount(new BigDecimal("500"))
                .marginUsed(new BigDecimal("500"))
                .build();
        SimPosition second = SimPosition.builder()
                .userId(7L).symbol("hf_XAU")
                .quantity(new BigDecimal("20"))
                .avgCost(new BigDecimal("100"))
                .leverage(new BigDecimal("2"))
                .loanAmount(new BigDecimal("1000"))
                .marginUsed(new BigDecimal("1000"))
                .build();
        when(positionRepo.findAllByUserId(7L)).thenReturn(List.of(first, second));
        when(snapshotRepo.findTopByMarketOrderByTsDesc("gold_etf"))
                .thenReturn(Optional.of(snapshot("gold_etf", 110.0)));
        when(snapshotRepo.findTopByMarketOrderByTsDesc("london_gold"))
                .thenReturn(Optional.of(snapshot("london_gold", 90.0)));

        Map<String, Object> overview = service.getAccountOverview(7L);
        @SuppressWarnings("unchecked")
        Map<String, Object> positions = (Map<String, Object>) overview.get("positions");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstOut = (Map<String, Object>) positions.get("sh518850");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondOut = (Map<String, Object>) positions.get("hf_XAU");

        assertEquals(20.0, ((Number) firstOut.get("returnOnEquity")).doubleValue(), 0.001);
        assertEquals(-20.0, ((Number) secondOut.get("returnOnEquity")).doubleValue(), 0.001);
    }

    @Test
    void staleQuoteCannotBeUsedForExecution() {
        SimAccountRepository accountRepo = mock(SimAccountRepository.class);
        SimPositionRepository positionRepo = mock(SimPositionRepository.class);
        SimTradeRepository tradeRepo = mock(SimTradeRepository.class);
        PriceSnapshotRepository snapshotRepo = mock(PriceSnapshotRepository.class);
        AuditService auditService = mock(AuditService.class);
        SimTradeService service = new SimTradeService(accountRepo, positionRepo, tradeRepo, snapshotRepo, auditService);

        SimAccount account = SimAccount.builder()
                .userId(9L)
                .cash(new BigDecimal("100000.0000"))
                .initialCash(new BigDecimal("100000.0000"))
                .status("ACTIVE")
                .build();
        when(accountRepo.findByUserIdForUpdate(9L)).thenReturn(Optional.of(account));
        PriceSnapshot stale = new PriceSnapshot("gold_etf", 10.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0,
                java.time.LocalDateTime.now().minusMinutes(10));
        when(snapshotRepo.findTopByMarketOrderByTsDesc("gold_etf")).thenReturn(Optional.of(stale));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.placeOrder(9L, "BUY", "sh518850",
                        new BigDecimal("100"), BigDecimal.ONE, "stale-order"));
        assertEquals(503, ex.getStatusCode().value());
    }

    private PriceSnapshot snapshot(String market, double price) {
        return new PriceSnapshot(market, price, 0.0, 0.0, price, price, price, price,
                java.time.LocalDateTime.now());
    }
}
