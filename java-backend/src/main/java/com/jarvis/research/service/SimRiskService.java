package com.jarvis.research.service;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.market.PriceSnapshotRepository;
import com.jarvis.research.user.SimAccount;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.SimPosition;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTrade;
import com.jarvis.research.user.SimTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模拟盘风险监控与强平。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimRiskService {

    public static final double WARN_MAINT_PCT = 25.0;
    public static final double LIQUIDATION_MAINT_PCT = 15.0;

    private static final int MONEY_SCALE = 4;
    private static final int VALUE_SCALE = 8;

    private final SimAccountRepository accountRepository;
    private final SimPositionRepository positionRepository;
    private final SimTradeRepository tradeRepository;
    private final PriceSnapshotRepository snapshotRepository;
    private final PlatformTransactionManager transactionManager;
    private final AuditService auditService;

    @Scheduled(initialDelay = 5000, fixedDelayString = "${jarvis.risk.poll-interval-ms:30000}")
    public void checkAndLiquidate() {
        List<Long> activeUserIds = accountRepository.findByStatus("ACTIVE").stream()
                .map(SimAccount::getUserId)
                .toList();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Long userId : activeUserIds) {
            try {
                tx.executeWithoutResult(status -> checkOneAccount(userId));
            } catch (Exception e) {
                log.error("风险扫描失败: userId={}", userId, e);
            }
        }
    }

    private void checkOneAccount(Long userId) {
            SimAccount account = accountRepository.findByUserIdForUpdate(userId).orElse(null);
            if (account == null || !"ACTIVE".equals(account.getStatus())) return;

            List<SimPosition> positions = positionRepository.findAllByUserId(account.getUserId());
            if (positions.isEmpty()) return;

            BigDecimal marketValue = BigDecimal.ZERO;
            boolean completeValuation = true;
            Map<String, BigDecimal> executionPrices = new LinkedHashMap<>();
            for (SimPosition position : positions) {
                QuotePoint quote = latestQuote(position.getSymbol());
                if (quote == null || quote.stale() || quote.price().signum() <= 0) {
                    completeValuation = false;
                    log.warn("跳过强平检查: userId={}, symbol={} 行情缺失或已过期",
                            account.getUserId(), position.getSymbol());
                    break;
                }
                executionPrices.put(position.getSymbol(), quote.price());
                marketValue = marketValue.add(quote.price().multiply(nz(position.getQuantity())));
            }
            marketValue = money(marketValue);
            if (!completeValuation || marketValue.signum() <= 0) return;

            BigDecimal netEquity = money(
                    nz(account.getCash()).add(marketValue).subtract(nz(account.getLoanBalance())));
            double maintPct = percent(netEquity, marketValue);
            if (maintPct >= LIQUIDATION_MAINT_PCT) return;

            forceLiquidate(account, positions, executionPrices, maintPct);
    }

    private void forceLiquidate(SimAccount account, List<SimPosition> positions,
                                Map<String, BigDecimal> executionPrices, double maintPct) {
        BigDecimal cash = nz(account.getCash());
        for (SimPosition position : positions) {
            BigDecimal price = executionPrices.get(position.getSymbol());
            if (price == null || price.signum() <= 0) {
                log.error("强平中止: 用户 {} 标的 {} 无有效行情", account.getUserId(), position.getSymbol());
                return;
            }
            BigDecimal qty = nz(position.getQuantity());
            BigDecimal amount = money(price.multiply(qty));
            BigDecimal positionLoan = nz(position.getLoanAmount());
            cash = money(cash.add(amount).subtract(positionLoan));

            SimTrade forced = SimTrade.builder()
                    .userId(account.getUserId())
                    .symbol(position.getSymbol())
                    .type("FORCE_SELL")
                    .price(value(price))
                    .quantity(value(qty))
                    .amount(amount)
                    .leverage(position.getLeverage() == null ? BigDecimal.ONE : position.getLeverage())
                    .createdAt(LocalDateTime.now())
                    .build();
            tradeRepository.save(forced);
        }

        positionRepository.deleteAll(positions);
        account.setCash(money(cash));
        account.setLoanBalance(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        account.setFrozenMargin(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        account.setStatus("FROZEN");
        accountRepository.save(account);
        auditService.record(account.getUserId(), "FORCE_LIQUIDATION", "sim_account", null,
                "maintPct=" + round2(maintPct) + " cash=" + account.getCash());
        log.warn("模拟盘强平完成: userId={}, maintPct={}, cash={}",
                account.getUserId(), round2(maintPct), account.getCash());
    }

    private QuotePoint latestQuote(String symbol) {
        String market = switch (symbol) {
            case "sh518850" -> "gold_etf";
            case "hf_XAU" -> "london_gold";
            case "jd_zheshang" -> "jd_zheshang";
            case "jd_minsheng" -> "jd_minsheng";
            default -> null;
        };
        if (market == null) return null;
        return snapshotRepository.findTopByMarketOrderByTsDesc(market)
                .map(snapshot -> {
                    BigDecimal price = new BigDecimal(String.valueOf(snapshot.getPrice()));
                    long maxAgeSeconds = market.startsWith("jd_") ? 180L : 120L;
                    boolean stale = snapshot.getTs() == null
                            || snapshot.getTs().isBefore(LocalDateTime.now().minusSeconds(maxAgeSeconds));
                    return new QuotePoint(value(price), stale);
                })
                .filter(quote -> quote.price().signum() > 0)
                .orElse(null);
    }

    private record QuotePoint(BigDecimal price, boolean stale) {}

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }

    private double percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return 0.0;
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
