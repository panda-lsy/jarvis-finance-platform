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
     * Uses 实时价成交
     */
    @Transactional
    public Map<String, Object> placeOrder(Long userId, String type, String symbol, Double quantity) {
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

        // 取实时价
        Map<String, Object> quote = goldPriceService.getRealtimeQuote(symbol);
        Object priceObj = quote.get("price");
        if (!(priceObj instanceof Number)) {
            throw new IllegalArgumentException("无法获取行情, 下单失败");
        }
        double price = ((Number) priceObj).doubleValue();
        double amount = price * quantity;

        String typeUp = type == null ? "" : type.toUpperCase();
        if ("BUY".equals(typeUp)) {
            if (account.getCash() < amount) {
                throw new IllegalArgumentException("可用资金不足: 需 " + round(amount)
                        + ", 可用 " + round(account.getCash()));
            }
            account.setCash(account.getCash() - amount);
            accountRepository.save(account);
            // 更新持仓 (加权平均成本)
            SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol).orElse(
                    SimPosition.builder().userId(userId).symbol(symbol)
                            .quantity(0.0).avgCost(0.0).build());
            double newQty = pos.getQuantity() + quantity;
            double newCost = (pos.getQuantity() * pos.getAvgCost() + amount) / newQty;
            pos.setQuantity(newQty);
            pos.setAvgCost(newCost);
            pos.setUpdatedAt(LocalDateTime.now());
            positionRepository.save(pos);
        } else if ("SELL".equals(typeUp)) {
            SimPosition pos = positionRepository.findByUserIdAndSymbol(userId, symbol)
                    .orElseThrow(() -> new IllegalArgumentException("无该标的持仓"));
            if (pos.getQuantity() < quantity) {
                throw new IllegalArgumentException("持仓不足: 持有 " + pos.getQuantity()
                        + ", 卖出 " + quantity);
            }
            account.setCash(account.getCash() + amount);
            accountRepository.save(account);
            pos.setQuantity(pos.getQuantity() - quantity);
            pos.setUpdatedAt(LocalDateTime.now());
            positionRepository.save(pos);
            if (pos.getQuantity() <= 0.0001) {
                positionRepository.delete(pos);
            }
        } else {
            throw new IllegalArgumentException("type 必须为 BUY 或 SELL");
        }

        // 记录交易
        SimTrade trade = SimTrade.builder()
                .userId(userId).symbol(symbol).type(typeUp)
                .price(price).quantity(quantity).amount(round(amount))
                .createdAt(LocalDateTime.now()).build();
        tradeRepository.save(trade);

        return Map.of(
                "type", typeUp,
                "symbol", symbol,
                "price", price,
                "quantity", quantity,
                "amount", round(amount),
                "message", typeUp + " 成交 @ " + price
        );
    }

    /** 账户总览: 资金 + 持仓市值 + 总资产 + 持仓明细 */
    @Transactional(readOnly = true)
    public Map<String, Object> getAccountOverview(Long userId) {
        SimAccount account = getOrCreateAccount(userId);
        List<SimPosition> positions = positionRepository.findAllByUserId(userId);

        double marketValue = 0.0;
        Map<String, Object> posList = new LinkedHashMap<>();
        for (SimPosition p : positions) {
            double cur = quotePrice(p.getSymbol());
            double mv = cur * p.getQuantity();
            marketValue += mv;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("symbol", p.getSymbol());
            detail.put("quantity", p.getQuantity());
            detail.put("avgCost", round(p.getAvgCost()));
            detail.put("currentPrice", cur);
            detail.put("marketValue", round(mv));
            detail.put("profit", round((cur - p.getAvgCost()) * p.getQuantity()));
            detail.put("profitPct", round((cur / p.getAvgCost() - 1) * 100));
            posList.put(p.getSymbol(), detail);
        }

        double totalAssets = account.getCash() + marketValue;
        double totalReturn = account.getInitialCash() > 0
                ? (totalAssets / account.getInitialCash() - 1) * 100 : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialCash", account.getInitialCash());
        out.put("cash", round(account.getCash()));
        out.put("marketValue", round(marketValue));
        out.put("totalAssets", round(totalAssets));
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
