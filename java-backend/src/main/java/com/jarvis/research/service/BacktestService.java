package com.jarvis.research.service;

import com.jarvis.research.market.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双均线回测。
 * 数据由 Java 行情层/数据库提供，前端不再自行计算，也不再直连 Python 回测接口。
 */
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final MarketDataService marketDataService;

    public Map<String, Object> run(String market, int shortMa, int longMa,
                                   double initialCash, int limit) {
        if (shortMa < 1 || longMa < 2 || shortMa >= longMa) {
            throw new IllegalArgumentException("均线参数需满足 1 <= short_ma < long_ma");
        }
        if (initialCash <= 0) {
            throw new IllegalArgumentException("initial_cash 必须大于0");
        }
        if (limit < longMa || limit > 5000) {
            throw new IllegalArgumentException("limit 必须在 long_ma ~ 5000 之间");
        }

        Map<String, Object> kline = marketDataService.getDailyKline(market, limit);
        Object raw = kline.get("data");
        if (!(raw instanceof List<?> rows) || rows.size() < longMa) {
            throw new IllegalArgumentException("K线数据不足，至少需要 " + longMa + " 根");
        }

        List<Double> closes = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            Object closeObj = row.get("close");
            Object dateObj = row.get("date");
            if (!(closeObj instanceof Number) || dateObj == null) continue;
            closes.add(((Number) closeObj).doubleValue());
            dates.add(String.valueOf(dateObj));
        }
        if (closes.size() < longMa) {
            throw new IllegalArgumentException("有效K线数据不足，至少需要 " + longMa + " 根");
        }

        final double transactionCost = 0.001;
        List<Double> shortSeries = movingAverage(closes, shortMa);
        List<Double> longSeries = movingAverage(closes, longMa);

        double cash = initialCash;
        double shares = 0.0;
        boolean inPosition = false;
        List<Map<String, Object>> trades = new ArrayList<>();
        List<Map<String, Object>> equityCurve = new ArrayList<>();

        for (int i = 0; i < closes.size(); i++) {
            double close = closes.get(i);
            Double ms = shortSeries.get(i);
            Double ml = longSeries.get(i);

            if (ms != null && ml != null) {
                if (ms > ml && !inPosition) {
                    double buyPrice = close * (1 + transactionCost);
                    shares = cash / buyPrice;
                    cash = 0.0;
                    inPosition = true;
                    trades.add(trade(dates.get(i), "BUY", close, shares));
                } else if (ms < ml && inPosition) {
                    double sellQty = shares;
                    double sellPrice = close * (1 - transactionCost);
                    cash = sellQty * sellPrice;
                    shares = 0.0;
                    inPosition = false;
                    trades.add(trade(dates.get(i), "SELL", close, sellQty));
                }
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", dates.get(i));
            point.put("equity", round2(cash + shares * close));
            point.put("close", close);
            equityCurve.add(point);
        }

        double finalEquity = ((Number) equityCurve.get(equityCurve.size() - 1).get("equity")).doubleValue();
        double bhEquity = initialCash / closes.get(0) * closes.get(closes.size() - 1) / (1 + transactionCost);
        double totalReturn = (finalEquity / initialCash - 1) * 100;
        double buyHoldReturn = (bhEquity / initialCash - 1) * 100;

        double peak = ((Number) equityCurve.get(0).get("equity")).doubleValue();
        double maxDrawdown = 0.0;
        for (Map<String, Object> point : equityCurve) {
            double equity = ((Number) point.get("equity")).doubleValue();
            peak = Math.max(peak, equity);
            if (peak > 0) maxDrawdown = Math.max(maxDrawdown, (peak - equity) / peak);
        }

        long calendarDays = Math.max(1, ChronoUnit.DAYS.between(
                LocalDate.parse(dates.get(0)),
                LocalDate.parse(dates.get(dates.size() - 1))));
        double annualReturn = finalEquity > 0
                ? (Math.pow(finalEquity / initialCash, 365.0 / calendarDays) - 1) * 100
                : 0.0;

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", dates.get(0));
        range.put("end", dates.get(dates.size() - 1));
        range.put("bars", dates.size());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("short_ma", shortMa);
        params.put("long_ma", longMa);
        params.put("transaction_cost", transactionCost);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("range", range);
        out.put("params", params);
        out.put("initial_cash", initialCash);
        out.put("final_equity", round2(finalEquity));
        out.put("total_return_pct", round2(totalReturn));
        out.put("annual_return_pct", round2(annualReturn));
        out.put("buy_hold_return_pct", round2(buyHoldReturn));
        out.put("max_drawdown_pct", round2(maxDrawdown * 100));
        out.put("num_trades", trades.size());
        out.put("trades", trades.size() <= 20 ? trades : trades.subList(trades.size() - 20, trades.size()));
        out.put("equity_curve", equityCurve);
        return out;
    }

    private List<Double> movingAverage(List<Double> values, int window) {
        List<Double> out = new ArrayList<>();
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i);
            if (i >= window) sum -= values.get(i - window);
            out.add(i >= window - 1 ? sum / window : null);
        }
        return out;
    }

    private Map<String, Object> trade(String date, String type, double price, double qty) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("type", type);
        out.put("price", round3(price));
        out.put("qty", round2(qty));
        return out;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
