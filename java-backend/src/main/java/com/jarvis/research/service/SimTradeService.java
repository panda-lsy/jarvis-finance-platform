package com.jarvis.research.service;

import com.jarvis.research.user.*;
import com.jarvis.research.service.GoldPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘交易服务
 * 每用户独立: 资金 / 持仓 / 交易记录 互不干扰
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimTradeService {

    private final SimAccountRepository accountRepository;
    private final SimPositionRepository positionRepository;
    private final SimTradeRepository tradeRepository;
    private final GoldPriceService goldPriceService;

    /** 获取或创建用户模拟账户 */
    @Transactional
    public SimAccount getOrCreateAccount(Long userId) {
        return accountRepository.findByUserId(userId).orElseGet(() -> {
            SimAccount a = SimAccount.builder()
                    .userId(userId).initialCash(100000.0).cash(100000.0)
                    .status("ACTIVE").createdAt(LocalDateTime.now()).build();
            return accountRepository.save(a);
        });
    }

    /**
     * 下单
     * type: BUY / SELL
     * symbol: sh518850 等
     * leverage: 杠杆倍数 (默认 1.0; >1 为杠杆购入, 仅 BUY 支持)
     * 使用实时价成交
     */
    @Transactional
    public Map<String, Object> placeOrder(Long userId, String type, String symbol, Double quantity, Double leverage) {
        SimAccount account = getOrCreateAccount(userId);
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new IllegalArgumentException("模拟账户状态异常: " + account.getStatus());
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于0");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("标的不合法");
        }

        double lev = leverage == null ? 1.0 : leverage;
        if (lev < 1.0 || lev > 10.0) {
            throw new IllegalArgumentException("杠杆倍数需在 1~10 之间");
        }

        // 取实时价
        Map<String, Object> quote = goldPriceService.getRealtimeQuote(symbol);
        Object priceObj = quote.get("price");
        if (!(priceObj instanceof Number)) {
            throw new IllegalArgumentException("无法获取行情, 下单失败");
        }
        double price = ((Number) priceObj).doubleValue();
        double amount = price * quantity;   // 名义买入/卖出额
        double margin = amount / lev;       // 自有保证金部分 (杠杆时 < amount)
        double loan = amount - margin;      // 借款部分 (杠杆时 > 0)

        String typeUp = type == null ? "" : type.toUpperCase();
        if ("BUY".equals(typeUp)) {
            // 杠杆买入: 只冻结自有保证金, 其余为借款
            if (account.getCash() < margin) {
                throw new IllegalArgumentException("可用资金不足: 需保证金 " + round(margin)
                        + ", 可用 " + round(account.getCash()));
            }
            account.setCash(round(account.getCash() - margin));
            account.setLoanBalance(round((account.getLoanBalance() == null ? 0 : account.getLoanBalance()) + loan));
            account.setFrozenMargin(round((account.getFrozenMargin() == null ? 0 : account.getFrozenMargin()) + margin));
            accountRepository.save(account);

            // 更新持仓
            SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol).orElse(
                    SimPosition.builder().userId(userId).symbol(symbol)
                            .quantity(0.0).avgCost(0.0)
                            .leverage(1.0).loanAmount(0.0).marginUsed(0.0).build());
            double newQty = pos.getQuantity() + quantity;
            double newLoan = (pos.getLoanAmount() == null ? 0 : pos.getLoanAmount()) + loan;
            double newMargin = (pos.getMarginUsed() == null ? 0 : pos.getMarginUsed()) + margin;
            // 总成本 = 投入保证金(自有) + 借款
            double newCost = (newLoan + newMargin) / newQty;
            pos.setQuantity(newQty);
            pos.setAvgCost(round(newCost));
            pos.setLoanAmount(round(newLoan));
            pos.setMarginUsed(round(newMargin));
            pos.setLeverage(round(newQty > 0 ? (newLoan + newMargin) / (newMargin > 0 ? newMargin : 1e-9) : 1.0));
            pos.setUpdatedAt(LocalDateTime.now());
            positionRepository.save(pos);
        } else if ("SELL".equals(typeUp)) {
            SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol)
                    .orElseThrow(() -> new IllegalArgumentException("无该标的持仓"));
            if (pos.getQuantity() < quantity) {
                throw new IllegalArgumentException("持仓不足: 持有 " + pos.getQuantity()
                        + ", 卖出 " + quantity);
            }
            // 按比例释放借款与保证金
            double sellRatio = quantity / pos.getQuantity();
            double releaseLoan = (pos.getLoanAmount() == null ? 0 : pos.getLoanAmount()) * sellRatio;
            double releaseMargin = (pos.getMarginUsed() == null ? 0 : pos.getMarginUsed()) * sellRatio;
            // 杠杆持仓卖出: 先还借款, 剩余归现金
            double cashIn = amount - releaseLoan;
            account.setCash(round(account.getCash() + cashIn));
            account.setLoanBalance(round(Math.max(0, (account.getLoanBalance() == null ? 0 : account.getLoanBalance()) - releaseLoan)));
            account.setFrozenMargin(round(Math.max(0, (account.getFrozenMargin() == null ? 0 : account.getFrozenMargin()) - releaseMargin)));
            accountRepository.save(account);

            pos.setQuantity(pos.getQuantity() - quantity);
            pos.setLoanAmount(round(Math.max(0, (pos.getLoanAmount() == null ? 0 : pos.getLoanAmount()) - releaseLoan)));
            pos.setMarginUsed(round(Math.max(0, (pos.getMarginUsed() == null ? 0 : pos.getMarginUsed()) - releaseMargin)));
            pos.setAvgCost((pos.getQuantity() > 0 && pos.getLoanAmount() + pos.getMarginUsed() > 0)
                    ? round((pos.getLoanAmount() + pos.getMarginUsed()) / pos.getQuantity()) : 0.0);
            pos.setLeverage(pos.getQuantity() > 0 && pos.getMarginUsed() > 0
                    ? round((pos.getLoanAmount() + pos.getMarginUsed()) / pos.getMarginUsed()) : 1.0);
            pos.setUpdatedAt(LocalDateTime.now());
            positionRepository.save(pos);
            if (pos.getQuantity() <= 0.0001) {
                positionRepository.delete(pos);
            }
        } else {
            throw new IllegalArgumentException("type 必须为 BUY 或 SELL");
        }

        // 记录交易 (记录杠杆)
        SimTrade trade = SimTrade.builder()
                .userId(userId).symbol(symbol).type(typeUp)
                .price(price).quantity(quantity).amount(round(amount))
                .leverage(round(lev))
                .createdAt(LocalDateTime.now()).build();
        tradeRepository.save(trade);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", typeUp);
        out.put("symbol", symbol);
        out.put("price", price);
        out.put("quantity", quantity);
        out.put("amount", round(amount));
        out.put("leverage", round(lev));
        out.put("margin", round(margin));
        out.put("loan", round(loan));
        out.put("message", typeUp + " 成交 @ " + price + (lev > 1 ? " (" + lev + "x 杠杆)" : ""));
        return out;
    }

    /** 账户总览: 资金 + 持仓市值 + 总资产 + 持仓明细 + 杠杆/风险 */
    @Transactional(readOnly = true)
    public Map<String, Object> getAccountOverview(Long userId) {
        SimAccount account = getOrCreateAccount(userId);
        List<SimPosition> positions = positionRepository.findAllByUserId(userId);

        double marketValue = 0.0;
        double loanTotal = 0.0;
        double marginTotal = 0.0;
        Map<String, Object> posList = new LinkedHashMap<>();
        for (SimPosition p : positions) {
            double cur = quotePrice(p.getSymbol());
            double mv = cur * p.getQuantity();
            marketValue += mv;
            double pLoan = p.getLoanAmount() == null ? 0 : p.getLoanAmount();
            double pMargin = p.getMarginUsed() == null ? 0 : p.getMarginUsed();
            loanTotal += pLoan;
            marginTotal += pMargin;
            double invested = pLoan + pMargin;
            double pnl = mv - invested;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("symbol", p.getSymbol());
            detail.put("quantity", p.getQuantity());
            detail.put("avgCost", round(p.getAvgCost()));
            detail.put("currentPrice", cur);
            detail.put("marketValue", round(mv));
            detail.put("leverage", p.getLeverage() == null ? 1.0 : round(p.getLeverage()));
            detail.put("loan", round(pLoan));
            detail.put("profit", round(pnl));
            detail.put("profitPct", invested > 0 ? round((pnl / invested) * 100) : 0.0);
            detail.put("returnOnEquity", marginTotal > 0
                    ? round((mv - (loanTotal + marginTotal)) / marginTotal * 100) : 0.0);
            posList.put(p.getSymbol(), detail);
        }

        double totalAssets = account.getCash() + marketValue;
        double netEquity = totalAssets - (account.getLoanBalance() == null ? 0 : account.getLoanBalance());
        double equity = account.getCash() + ((account.getFrozenMargin() == null ? 0 : account.getFrozenMargin()) == 0
                ? marketValue - (account.getLoanBalance() == null ? 0 : account.getLoanBalance())
                : marketValue - (account.getLoanBalance() == null ? 0 : account.getLoanBalance()));
        double totalReturn = account.getInitialCash() > 0
                ? (netEquity / account.getInitialCash() - 1) * 100 : 0;
        // 维持保证金率 = 净资产 / 市值
        double maint = marketValue > 0 ? round((netEquity / marketValue) * 100) : 100.0;
        String riskStatus = marketValue == 0 ? "NONE"
                : (maint < 30 ? "DANGER" : (maint < 50 ? "WARN" : "SAFE"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialCash", account.getInitialCash());
        out.put("cash", round(account.getCash()));
        out.put("marketValue", round(marketValue));
        out.put("totalAssets", round(totalAssets));
        out.put("loanBalance", round(account.getLoanBalance() == null ? 0 : account.getLoanBalance()));
        out.put("frozenMargin", round(account.getFrozenMargin() == null ? 0 : account.getFrozenMargin()));
        out.put("netEquity", round(netEquity));
        out.put("maintMarginPct", maint);
        out.put("riskStatus", riskStatus);
        out.put("totalReturnPct", round(totalReturn));
        out.put("status", account.getStatus());
        out.put("positions", posList);
        return out;
    }

    /** 交易记录 */
    @Transactional(readOnly = true)
    public Map<String, Object> getTrades(Long userId, int limit) {
        List<SimTrade> trades = tradeRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
        long total = tradeRepository.countByUserId(userId);
        return Map.of("total", total, "trades", trades);
    }

    private double quotePrice(String symbol) {
        try {
            Map<String, Object> q = goldPriceService.getRealtimeQuote(symbol);
            Object p = q.get("price");
            return p instanceof Number ? ((Number) p).doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
