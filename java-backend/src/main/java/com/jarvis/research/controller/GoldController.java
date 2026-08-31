package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.service.GoldPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 黄金行情 API
 * GET /api/gold/quote?symbol=sh518850        实时报价
 * GET /api/gold/kline?symbol=sh518850&limit=120  历史K线
 */
@RestController
@RequestMapping("/api/gold")
public class GoldController {

    private final GoldPriceService goldPriceService;

    public GoldController(GoldPriceService goldPriceService) {
        this.goldPriceService = goldPriceService;
    }

    @GetMapping("/quote")
    public ApiResponse<Map<String, Object>> quote(
            @RequestParam(required = false) String symbol) {
        return ApiResponse.ok(goldPriceService.getRealtimeQuote(symbol));
    }

    @GetMapping("/kline")
    public ApiResponse<Object> kline(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "120") int limit) {
        return ApiResponse.ok(goldPriceService.getKline(symbol, limit));
    }
}
