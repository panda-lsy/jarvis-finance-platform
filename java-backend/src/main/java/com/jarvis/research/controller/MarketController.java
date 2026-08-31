package com.jarvis.research.controller;

import com.jarvis.research.market.MarketDataService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 市场数据接口 (Java 主管数据存储)
 * - /api/market/prices  实时存取黄金ETF + 伦敦金价格
 * - /api/market/kline   日K(每日抓取一次) + 分钟K(基于实时快照)
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketService;

    public MarketController(MarketDataService marketService) {
        this.marketService = marketService;
    }

    /** 实时存取黄金ETF + 伦敦金价格 */
    @GetMapping("/prices")
    public Map<String, Object> prices() {
        return marketService.fetchAndStorePrices();
    }

    /**
     * K线
     * @param market   gold_etf / london_gold
     * @param limit    返回根数
     * @param interval day(默认) / 1 / 5 / 15 / 30 / 60 (分钟)
     */
    @GetMapping("/kline")
    public Map<String, Object> kline(
            @RequestParam(defaultValue = "gold_etf") String market,
            @RequestParam(defaultValue = "120") int limit,
            @RequestParam(defaultValue = "day") String interval) {
        if (!"day".equals(interval)) {
            try {
                int minutes = Integer.parseInt(interval);
                return marketService.getMinuteKline(market, minutes, limit);
            } catch (NumberFormatException e) {
                // fall through to daily
            }
        }
        return marketService.getDailyKline(market, limit);
    }
}
