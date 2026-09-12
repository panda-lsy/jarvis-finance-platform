package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.user.SimOrder;
import com.jarvis.research.user.SimOrderRepository;
import com.jarvis.research.user.SimPosition;
import com.jarvis.research.user.SimPositionRepository;
import com.jarvis.research.user.SimTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘挂单服务。
 *
 * 第一阶段实现 STOP_MARKET：订单持久化在服务端，浏览器关闭后仍由调度器检查行情并触发。
 * MARKET 订单继续由 SimTradeService 直接成交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimOrderService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    private final SimOrderRepository orderRepository;
    private final SimTradeRepository tradeRepository;
    private final SimPositionRepository positionRepository;
    private final SimTradeService simTradeService;
    private final MarketDataService marketDataService;

    @Autowired(required = false)
    private JdGoldService jdGoldService;

    @Transactional
    public Map<String, Object> placeStopMarket(Long userId,
                                                String side,
                                                String symbol,
                                                BigDecimal quantity,
                                                BigDecimal leverage,
                                                BigDecimal stopPrice,
                                                String timeInForce,
                                                String clientOrderId) {
        String normalizedSide = normalizeSide(side);
        String normalizedSymbol = normalizeSymbol(symbol);
        BigDecimal qty = positive(quantity, "数量必须大于0");
        BigDecimal lev = leverage == null ? BigDecimal.ONE : leverage;
        BigDecimal stop = positive(stopPrice, "止损价必须大于0");
        String tif = normalizeTimeInForce(timeInForce);
        String orderKey = normalizeOrderKey(clientOrderId);

        if (lev.compareTo(BigDecimal.ONE) < 0 || lev.compareTo(new BigDecimal("5")) > 0) {
            throw new IllegalArgumentException("杠杆倍数需在 1~5 之间");
        }
        if ("SELL".equals(normalizedSide) && lev.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("卖出订单 leverage 必须为1");
        }
        validateSupportedSymbol(normalizedSymbol);
        validateSellPosition(userId, normalizedSide, normalizedSymbol, qty);
        validateStopRelation(normalizedSide, normalizedSymbol, stop);

        if (orderKey != null) {
            SimOrder existing = orderRepository.findByUserIdAndClientOrderId(userId, orderKey).orElse(null);
            if (existing != null) {
                if (!sameOrder(existing, normalizedSide, normalizedSymbol, qty, lev, stop, tif)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "clientOrderId 已被另一笔挂单使用");
                }
                return orderResult(existing, true);
            }
            if (tradeRepository.findByUserIdAndClientOrderId(userId, orderKey).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "clientOrderId 已被另一笔成交订单使用");
            }
        }

        SimOrder order = SimOrder.builder()
                .userId(userId)
                .symbol(normalizedSymbol)
                .side(normalizedSide)
                .orderType("STOP_MARKET")
                .quantity(scaleValue(qty))
                .leverage(lev.setScale(4, RoundingMode.HALF_UP))
                .stopPrice(scaleValue(stop))
                .timeInForce(tif)
                .status("OPEN")
                .clientOrderId(orderKey)
                .build();
        orderRepository.save(order);
        return orderResult(order, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpen(Long userId) {
        List<SimOrder> orders = new ArrayList<>(
                orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "OPEN"));
        orders.addAll(orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "TRIGGERING"));
        orders.sort(Comparator.comparing(SimOrder::getCreatedAt).reversed());
        return orders.stream().map(order -> orderResult(order, false)).toList();
    }

    public Map<String, Object> updateStopPrice(Long userId, Long orderId, BigDecimal stopPrice) {
        BigDecimal stop = scaleValue(positive(stopPrice, "止损价必须大于0"));
        SimOrder existing = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!"OPEN".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单已触发或已撤销，无法修改");
        }
        validateStopRelation(existing.getSide(), existing.getSymbol(), stop);

        int changed = orderRepository.updateOpenStopPrice(orderId, userId, stop);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单不存在、已触发或已撤销，无法修改");
        }
        SimOrder refreshed = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        return orderResult(refreshed, false);
    }

    public Map<String, Object> cancel(Long userId, Long orderId) {
        int changed = orderRepository.cancelOpen(orderId, userId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单不存在、已触发或已经撤销");
        }
        SimOrder refreshed = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        return orderResult(refreshed, false);
    }

    /**
     * 每秒检查一次服务端 STOP_MARKET。先用原子更新把 OPEN 认领为 TRIGGERING，
     * 再调用现有幂等成交服务；即使进程在成交后短暂退出，TRIGGERING 也会在后续轮次恢复执行。
     */
    @Scheduled(initialDelay = 3000,
            fixedDelayString = "${jarvis.sim-trading.order-trigger-interval-ms:1000}")
    public void processStopOrders() {
        for (SimOrder order : orderRepository.findByStatusOrderByCreatedAtAsc("OPEN")) {
            if (isDayOrderExpired(order)) {
                orderRepository.cancelOpen(order.getId(), order.getUserId());
                continue;
            }
            Quote quote = latestQuote(order.getSymbol());
            if (quote == null || quote.stale || !isTriggered(order, quote.price)) continue;
            if (orderRepository.claimOpen(order.getId()) == 1) {
                executeClaimed(order);
            }
        }

        // 宕机恢复：已认领但尚未写入终态的订单通过成交幂等号安全重放。
        for (SimOrder order : orderRepository.findByStatusOrderByCreatedAtAsc("TRIGGERING")) {
            executeClaimed(order);
        }
    }

    private void executeClaimed(SimOrder order) {
        try {
            simTradeService.placeOrder(
                    order.getUserId(),
                    order.getSide(),
                    order.getSymbol(),
                    order.getQuantity(),
                    order.getLeverage(),
                    order.getClientOrderId());
            orderRepository.finish(order.getId(), "FILLED", LocalDateTime.now());
        } catch (RuntimeException error) {
            orderRepository.finish(order.getId(), "REJECTED", LocalDateTime.now());
            log.warn("STOP_MARKET 触发后成交失败: orderId={}, symbol={}, message={}",
                    order.getId(), order.getSymbol(), error.getMessage());
        }
    }

    private boolean isTriggered(SimOrder order, BigDecimal currentPrice) {
        return "SELL".equals(order.getSide())
                ? currentPrice.compareTo(order.getStopPrice()) <= 0
                : currentPrice.compareTo(order.getStopPrice()) >= 0;
    }

    private boolean isDayOrderExpired(SimOrder order) {
        if (!"DAY".equals(order.getTimeInForce()) || order.getCreatedAt() == null) return false;
        LocalDate today = LocalDate.now(MARKET_ZONE);
        return order.getCreatedAt().toLocalDate().isBefore(today);
    }

    private Quote latestQuote(String symbol) {
        try {
            Object raw;
            if ("sh518850".equals(symbol)) {
                raw = marketDataService.getLatestPrices().get("gold_etf");
            } else if ("hf_XAU".equals(symbol)) {
                raw = marketDataService.getLatestPrices().get("london_gold");
            } else if (("jd_zheshang".equals(symbol) || "jd_minsheng".equals(symbol))
                    && jdGoldService != null) {
                raw = jdGoldService.latestQuote(symbol);
            } else {
                return null;
            }
            if (!(raw instanceof Map<?, ?> quote)) return null;
            BigDecimal price = decimal(quote.get("price"));
            if (price == null || price.signum() <= 0) return null;
            boolean stale = Boolean.TRUE.equals(quote.get("stale"));
            return new Quote(scaleValue(price), stale);
        } catch (Exception error) {
            log.debug("挂单触发行情暂不可用: symbol={}, message={}", symbol, error.getMessage());
            return null;
        }
    }

    private void validateSellPosition(Long userId, String side, String symbol, BigDecimal qty) {
        if (!"SELL".equals(side)) return;
        SimPosition position = positionRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseThrow(() -> new IllegalArgumentException("无该标的持仓"));
        BigDecimal available = position.getQuantity() == null ? BigDecimal.ZERO : position.getQuantity();
        if (available.compareTo(qty) < 0) {
            throw new IllegalArgumentException("持仓不足: 持有 " + available + ", 挂单卖出 " + qty);
        }
    }

    /**
     * STOP_MARKET 必须位于当前价格的正确一侧，否则它会在创建后立刻触发，
     * 这既不符合交易界面语义，也容易把误填的止损价变成市价成交。
     * 市场休市时允许使用最后行情做方向校验；真正触发仍要求 fresh quote。
     */
    private void validateStopRelation(String side, String symbol, BigDecimal stopPrice) {
        Quote quote = latestQuote(symbol);
        if (quote == null || quote.price == null || quote.price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法获取当前行情，暂不能设置止损价: " + symbol);
        }
        if ("SELL".equals(side) && stopPrice.compareTo(quote.price) >= 0) {
            throw new IllegalArgumentException("卖出止损价必须低于当前价 " + quote.price.stripTrailingZeros().toPlainString());
        }
        if ("BUY".equals(side) && stopPrice.compareTo(quote.price) <= 0) {
            throw new IllegalArgumentException("买入止损价必须高于当前价 " + quote.price.stripTrailingZeros().toPlainString());
        }
    }

    private boolean sameOrder(SimOrder order,
                              String side,
                              String symbol,
                              BigDecimal quantity,
                              BigDecimal leverage,
                              BigDecimal stopPrice,
                              String timeInForce) {
        return side.equals(order.getSide())
                && symbol.equals(order.getSymbol())
                && "STOP_MARKET".equals(order.getOrderType())
                && order.getQuantity().compareTo(quantity) == 0
                && order.getLeverage().compareTo(leverage) == 0
                && order.getStopPrice().compareTo(stopPrice) == 0
                && timeInForce.equals(order.getTimeInForce());
    }

    private Map<String, Object> orderResult(SimOrder order, boolean replay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", order.getId());
        out.put("symbol", order.getSymbol());
        out.put("side", order.getSide());
        out.put("orderType", order.getOrderType());
        out.put("quantity", order.getQuantity());
        out.put("leverage", order.getLeverage());
        out.put("stopPrice", order.getStopPrice());
        out.put("timeInForce", order.getTimeInForce());
        out.put("status", order.getStatus());
        out.put("clientOrderId", order.getClientOrderId());
        out.put("createdAt", order.getCreatedAt());
        out.put("updatedAt", order.getUpdatedAt());
        out.put("triggeredAt", order.getTriggeredAt());
        out.put("idempotentReplay", replay);
        return out;
    }

    private String normalizeSide(String side) {
        String value = side == null ? "" : side.trim().toUpperCase();
        if (!"BUY".equals(value) && !"SELL".equals(value)) {
            throw new IllegalArgumentException("side 必须为 BUY 或 SELL");
        }
        return value;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("标的不合法");
        return symbol.trim();
    }

    private void validateSupportedSymbol(String symbol) {
        if (!"sh518850".equals(symbol)
                && !"hf_XAU".equals(symbol)
                && !"jd_zheshang".equals(symbol)
                && !"jd_minsheng".equals(symbol)) {
            throw new IllegalArgumentException("暂不支持该标的挂单: " + symbol);
        }
    }

    private String normalizeTimeInForce(String value) {
        String normalized = value == null || value.isBlank() ? "DAY" : value.trim().toUpperCase();
        if (!"DAY".equals(normalized) && !"GTC".equals(normalized)) {
            throw new IllegalArgumentException("timeInForce 必须为 DAY 或 GTC");
        }
        return normalized;
    }

    private String normalizeOrderKey(String clientOrderId) {
        if (clientOrderId == null) return null;
        String key = clientOrderId.trim();
        if (key.isEmpty()) return null;
        if (key.length() > 64) throw new IllegalArgumentException("clientOrderId 长度不能超过64字符");
        return key;
    }

    private BigDecimal positive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(message);
        return value;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private BigDecimal scaleValue(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private record Quote(BigDecimal price, boolean stale) {}
}
