package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.market.MarketDataService;
import com.jarvis.research.service.JdGoldService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 市场数据接口 (Java 主管数据存储)
 * - /api/market/prices  实时存取黄金ETF + 伦敦金价格
 * - /api/market/kline   日K(每日抓取一次) + 分钟K(基于实时快照)
 * 统一返回 ApiResponse {code, message, data}
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketService;
    private final JdGoldService jdGoldService;

    public MarketController(MarketDataService marketService, JdGoldService jdGoldService) {
        this.marketService = marketService;
        this.jdGoldService = jdGoldService;
    }

    /** 最近有效行情；纯查询，不触发外部抓取或数据库写入。 */
    @GetMapping("/prices")
    public ApiResponse<Object> prices() {
        return ApiResponse.ok(marketService.getLatestPrices());
    }

    /** 京东积存金最近有效价格（数据来自 Java 定时采集并落库）。 */
    @GetMapping("/jd/prices")
    public ApiResponse<Object> jdPrices() {
        return ApiResponse.ok(jdGoldService.latestPrices());
    }

    /** 京东积存金分钟K线。 */
    @GetMapping("/jd/kline")
    public ApiResponse<Object> jdKline(
            @RequestParam(defaultValue = "zheshang") String market,
            @RequestParam(defaultValue = "5") int interval,
            @RequestParam(defaultValue = "200") int limit) {
        if (!"zheshang".equals(market) && !"minsheng".equals(market)) {
            throw new IllegalArgumentException("market 必须为 zheshang 或 minsheng");
        }
        if (interval != 1 && interval != 5 && interval != 15 && interval != 30 && interval != 60) {
            throw new IllegalArgumentException("interval 必须为 1/5/15/30/60");
        }
        if (limit < 1 || limit > 2000) {
            throw new IllegalArgumentException("limit 必须在 1~2000 之间");
        }
        return ApiResponse.ok(marketService.getMinuteKline("jd_" + market, interval, limit));
    }

    /**
     * K线
     * @param market   gold_etf / london_gold
     * @param limit    返回根数
     * @param interval day(默认) / 1 / 5 / 15 / 30 / 60 (分钟)
     */
    @GetMapping("/kline")
    public ApiResponse<Object> kline(
            @RequestParam(defaultValue = "gold_etf") String market,
            @RequestParam(defaultValue = "120") int limit,
            @RequestParam(defaultValue = "day") String interval) {
        if (!"gold_etf".equals(market) && !"london_gold".equals(market)) {
            throw new IllegalArgumentException("market 必须为 gold_etf 或 london_gold");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1~1000 之间");
        }
        if ("day".equals(interval)) {
            try {
                return ApiResponse.ok(marketService.getDailyKline(market, limit));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "K线获取失败", e);
            }
        }
        int minutes;
        try {
            minutes = Integer.parseInt(interval);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("interval 必须为 day/1/5/15/30/60");
        }
        if (minutes != 1 && minutes != 5 && minutes != 15 && minutes != 30 && minutes != 60) {
            throw new IllegalArgumentException("interval 必须为 day/1/5/15/30/60");
        }
        return ApiResponse.ok(marketService.getMinuteKline(market, minutes, limit));
    }
}
