package com.jarvis.research.service;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.market.PriceSnapshotRepository;
import com.jarvis.research.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘交易服务。
 * 资金、价格、数量、保证金等持久化计算统一使用 BigDecimal，避免浮点累计误差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimTradeService {

    private static final int MONEY_SCALE = 4;
    private static final int VALUE_SCALE = 8;
    private static final int RATIO_SCALE = 12;
    private static final BigDecimal INITIAL_CASH = new BigDecimal("100000.0000");
    private static final BigDecimal MIN_POSITION_QTY = new BigDecimal("0.00000001");

    private final SimAccountRepository accountRepository;
    private final SimPositionRepository positionRepository;
    private final SimTradeRepository tradeRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final AuditService auditService;

    /** 获取或创建用户模拟账户。 */
    @Transactional
    public SimAccount getOrCreateAccount(Long userId) {
        return accountRepository.findByUserId(userId).orElseGet(() -> {
            SimAccount account = SimAccount.builder()
                    .userId(userId)
                    .initialCash(INITIAL_CASH)
                    .cash(INITIAL_CASH)
                    .status("ACTIVE")
                    .createdAt(LocalDateTime.now())
                    .build();
            return accountRepository.save(account);
        });
    }

    /** 下单：BUY / SELL，杠杆 1~5x。 */
    @Transactional
    public Map<String, Object> placeOrder(Long userId, String type, String symbol,
                                          BigDecimal quantity, BigDecimal leverage,
                                          String clientOrderId) {
        SimAccount account = accountRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> getOrCreateAccount(userId));

        String typeUp = type == null ? "" : type.trim().toUpperCase();
        String orderKey = normalizeOrderKey(clientOrderId);
        BigDecimal qty = quantity == null ? BigDecimal.ZERO : quantity;
        BigDecimal lev = leverage == null ? BigDecimal.ONE : leverage;

        if (orderKey != null) {
            SimTrade existing = tradeRepository.findByUserIdAndClientOrderId(userId, orderKey).orElse(null);
            if (existing != null) {
                boolean sameOrder = existing.getType().equals(typeUp)
                        && existing.getSymbol().equals(symbol)
                        && existing.getQuantity().compareTo(qty) == 0
                        && nz(existing.getLeverage()).compareTo(lev) == 0;
                if (!sameOrder) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "clientOrderId 已被另一笔订单使用");
                }
                return tradeResult(existing, true);
            }
        }

        if (!"ACTIVE".equals(account.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "模拟账户状态异常: " + account.getStatus());
        }
        if (!"BUY".equals(typeUp) && !"SELL".equals(typeUp)) {
            throw new IllegalArgumentException("type 必须为 BUY 或 SELL");
        }
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("数量必须大于0");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("标的不合法");
        }
        if (lev.compareTo(BigDecimal.ONE) < 0 || lev.compareTo(new BigDecimal("5")) > 0) {
            throw new IllegalArgumentException("杠杆倍数需在 1~5 之间");
        }
        if ("SELL".equals(typeUp) && lev.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("卖出订单 leverage 必须为1");
        }

        QuoteValue executionQuote = quoteValue(symbol, true);
        BigDecimal price = executionQuote.price();
        BigDecimal amount = money(price.multiply(qty));
        BigDecimal margin = "BUY".equals(typeUp)
                ? money(amount.divide(lev, MONEY_SCALE, RoundingMode.HALF_UP))
                : amount;
        BigDecimal loan = "BUY".equals(typeUp) ? money(amount.subtract(margin)) : BigDecimal.ZERO;

        if ("BUY".equals(typeUp)) {
            executeBuy(account, userId, symbol, qty, margin, loan);
        } else {
            executeSell(account, userId, symbol, qty, amount);
        }

        SimTrade trade = SimTrade.builder()
                .userId(userId)
                .symbol(symbol)
                .type(typeUp)
                .clientOrderId(orderKey)
                .price(value(price))
                .quantity(value(qty))
                .amount(amount)
                .leverage(ratio(lev))
                .createdAt(LocalDateTime.now())
                .build();
        tradeRepository.save(trade);
        auditService.record(userId, "SIM_ORDER", symbol, null,
                typeUp + " qty=" + qty + " price=" + price + " clientOrderId=" + orderKey);

        Map<String, Object> out = tradeResult(trade, false);
        if ("BUY".equals(typeUp)) {
            out.put("margin", margin);
            out.put("loan", loan);
        }
        return out;
    }

    private void executeBuy(SimAccount account, Long userId, String symbol,
                            BigDecimal quantity, BigDecimal margin, BigDecimal loan) {
        BigDecimal cash = nz(account.getCash());
        if (cash.compareTo(margin) < 0) {
            throw new IllegalArgumentException("可用资金不足: 需保证金 " + margin + ", 可用 " + cash);
        }
        account.setCash(money(cash.subtract(margin)));
        account.setLoanBalance(money(nz(account.getLoanBalance()).add(loan)));
        account.setFrozenMargin(money(nz(account.getFrozenMargin()).add(margin)));
        accountRepository.save(account);

        SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol).orElse(
                SimPosition.builder()
                        .userId(userId)
                        .symbol(symbol)
                        .build());
        BigDecimal newQty = nz(pos.getQuantity()).add(quantity);
        BigDecimal newLoan = nz(pos.getLoanAmount()).add(loan);
        BigDecimal newMargin = nz(pos.getMarginUsed()).add(margin);
        BigDecimal invested = newLoan.add(newMargin);

        pos.setQuantity(value(newQty));
        pos.setAvgCost(newQty.signum() > 0
                ? value(invested.divide(newQty, VALUE_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ZERO);
        pos.setLoanAmount(money(newLoan));
        pos.setMarginUsed(money(newMargin));
        pos.setLeverage(newMargin.signum() > 0
                ? ratio(invested.divide(newMargin, VALUE_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE);
        pos.setUpdatedAt(LocalDateTime.now());
        positionRepository.save(pos);
    }

    private void executeSell(SimAccount account, Long userId, String symbol,
                             BigDecimal quantity, BigDecimal amount) {
        SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("无该标的持仓"));
        BigDecimal oldQty = nz(pos.getQuantity());
        if (oldQty.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("持仓不足: 持有 " + oldQty + ", 卖出 " + quantity);
        }

        BigDecimal sellRatio = quantity.divide(oldQty, RATIO_SCALE, RoundingMode.HALF_UP);
        BigDecimal releaseLoan = money(nz(pos.getLoanAmount()).multiply(sellRatio));
        BigDecimal releaseMargin = money(nz(pos.getMarginUsed()).multiply(sellRatio));
        BigDecimal cashIn = money(amount.subtract(releaseLoan));

        account.setCash(money(nz(account.getCash()).add(cashIn)));
        account.setLoanBalance(money(maxZero(nz(account.getLoanBalance()).subtract(releaseLoan))));
        account.setFrozenMargin(money(maxZero(nz(account.getFrozenMargin()).subtract(releaseMargin))));
        accountRepository.save(account);

        BigDecimal remainQty = maxZero(oldQty.subtract(quantity));
        BigDecimal remainLoan = money(maxZero(nz(pos.getLoanAmount()).subtract(releaseLoan)));
        BigDecimal remainMargin = money(maxZero(nz(pos.getMarginUsed()).subtract(releaseMargin)));
        pos.setQuantity(value(remainQty));
        pos.setLoanAmount(remainLoan);
        pos.setMarginUsed(remainMargin);

        BigDecimal remainInvested = remainLoan.add(remainMargin);
        pos.setAvgCost(remainQty.signum() > 0 && remainInvested.signum() > 0
                ? value(remainInvested.divide(remainQty, VALUE_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ZERO);
        pos.setLeverage(remainQty.signum() > 0 && remainMargin.signum() > 0
                ? ratio(remainInvested.divide(remainMargin, VALUE_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE);
        pos.setUpdatedAt(LocalDateTime.now());

        if (remainQty.compareTo(MIN_POSITION_QTY) <= 0) {
            positionRepository.delete(pos);
        } else {
            positionRepository.save(pos);
        }
    }

    /** 账户总览。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getAccountOverview(Long userId) {
        SimAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟账户不存在"));
        List<SimPosition> positions = positionRepository.findAllByUserId(userId);

        BigDecimal marketValue = BigDecimal.ZERO;
        Map<String, Object> posList = new LinkedHashMap<>();
        for (SimPosition pos : positions) {
            QuoteValue quote = quoteValue(pos.getSymbol(), false);
            BigDecimal currentPrice = quote.price();
            BigDecimal mv = money(currentPrice.multiply(nz(pos.getQuantity())));
            marketValue = marketValue.add(mv);
            BigDecimal posLoan = nz(pos.getLoanAmount());
            BigDecimal posMargin = nz(pos.getMarginUsed());
            BigDecimal invested = posLoan.add(posMargin);
            BigDecimal pnl = money(mv.subtract(invested));

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("symbol", pos.getSymbol());
            detail.put("quantity", pos.getQuantity());
            detail.put("avgCost", pos.getAvgCost());
            detail.put("currentPrice", currentPrice);
            detail.put("quoteTime", quote.ts());
            detail.put("stale", quote.stale());
            detail.put("marketValue", mv);
            detail.put("leverage", pos.getLeverage() == null ? BigDecimal.ONE : pos.getLeverage());
            detail.put("loan", posLoan);
            detail.put("profit", pnl);
            detail.put("profitPct", percent(pnl, invested));
            detail.put("returnOnEquity", percent(pnl, posMargin));
            posList.put(pos.getSymbol(), detail);
        }

        marketValue = money(marketValue);
        BigDecimal cash = nz(account.getCash());
        BigDecimal loanBalance = nz(account.getLoanBalance());
        BigDecimal frozenMargin = nz(account.getFrozenMargin());
        BigDecimal totalAssets = money(cash.add(marketValue));
        BigDecimal netEquity = money(totalAssets.subtract(loanBalance));
        double totalReturn = percent(netEquity.subtract(nz(account.getInitialCash())), nz(account.getInitialCash()));
        double maint = marketValue.signum() > 0 ? percent(netEquity, marketValue) : 100.0;
        String riskStatus = marketValue.signum() == 0 ? "NONE"
                : (maint < SimRiskService.LIQUIDATION_MAINT_PCT
                    ? "DANGER"
                    : (maint < SimRiskService.WARN_MAINT_PCT ? "WARN" : "SAFE"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialCash", account.getInitialCash());
        out.put("cash", cash);
        out.put("marketValue", marketValue);
        out.put("totalAssets", totalAssets);
        out.put("loanBalance", loanBalance);
        out.put("frozenMargin", frozenMargin);
        out.put("netEquity", netEquity);
        out.put("maintMarginPct", maint);
        out.put("riskStatus", riskStatus);
        out.put("totalReturnPct", totalReturn);
        out.put("status", account.getStatus());
        out.put("positions", posList);
        return out;
    }

    /** 交易记录。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getTrades(Long userId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1~200 之间");
        }
        List<SimTrade> trades = tradeRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
        long total = tradeRepository.countByUserId(userId);
        return Map.of("total", total, "trades", trades);
    }

    private Map<String, Object> tradeResult(SimTrade trade, boolean replay) {
        BigDecimal lev = trade.getLeverage() == null ? BigDecimal.ONE : trade.getLeverage();
        BigDecimal amount = nz(trade.getAmount());
        BigDecimal margin = "BUY".equals(trade.getType())
                ? money(amount.divide(lev, MONEY_SCALE, RoundingMode.HALF_UP))
                : amount;
        BigDecimal loan = "BUY".equals(trade.getType()) ? money(amount.subtract(margin)) : BigDecimal.ZERO;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", trade.getType());
        out.put("symbol", trade.getSymbol());
        out.put("price", trade.getPrice());
        out.put("quantity", trade.getQuantity());
        out.put("amount", amount);
        out.put("leverage", lev);
        out.put("margin", margin);
        out.put("loan", loan);
        out.put("clientOrderId", trade.getClientOrderId());
        out.put("idempotentReplay", replay);
        out.put("message", trade.getType() + " 成交 @ " + trade.getPrice()
                + (lev.compareTo(BigDecimal.ONE) > 0 ? " (" + lev.stripTrailingZeros().toPlainString() + "x 杠杆)" : "")
                + (replay ? " [幂等重放]" : ""));
        return out;
    }

    private QuoteValue quoteValue(String symbol, boolean requireFresh) {
        String market = switch (symbol) {
            case "sh518850" -> "gold_etf";
            case "hf_XAU" -> "london_gold";
            case "jd_zheshang" -> "jd_zheshang";
            case "jd_minsheng" -> "jd_minsheng";
            default -> null;
        };
        if (market == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法获取 " + symbol + " 的有效行情");
        }

        var snapshot = priceSnapshotRepository.findTopByMarketOrderByTsDesc(market)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "无法获取 " + symbol + " 的有效行情"));
        BigDecimal price = asDecimal(snapshot.getPrice());
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法获取 " + symbol + " 的有效行情");
        }

        long maxAgeSeconds = market.startsWith("jd_") ? 180L : 120L;
        LocalDateTime now = LocalDateTime.now();
        boolean stale = snapshot.getTs() == null
                || snapshot.getTs().isBefore(now.minusSeconds(maxAgeSeconds));
        if (requireFresh && stale) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "行情已过期，暂停成交: " + symbol);
        }
        return new QuoteValue(value(price), snapshot.getTs(), stale);
    }

    private record QuoteValue(BigDecimal price, LocalDateTime ts, boolean stale) {}

    private String normalizeOrderKey(String clientOrderId) {
        if (clientOrderId == null) return null;
        String key = clientOrderId.trim();
        if (key.isEmpty()) return null;
        if (key.length() > 64) throw new IllegalArgumentException("clientOrderId 长度不能超过64字符");
        return key;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal value(BigDecimal value) {
        return value.setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal maxZero(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return 0.0;
        return numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
