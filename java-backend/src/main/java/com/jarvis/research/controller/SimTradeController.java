package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.SimTradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模拟盘 API (需登录)
 * POST /api/sim/order    下单 BUY/SELL
 * GET  /api/sim/account  账户总览
 * GET  /api/sim/trades   交易记录
 */
@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimTradeController {

    private final SimTradeService simTradeService;

    /** 下单 */
    @PostMapping("/order")
    public ApiResponse<Map<String, Object>> order(@RequestBody Map<String, Object> req) {
        Long userId = CurrentUser.id();
        String type = (String) req.getOrDefault("type", "BUY");
        String symbol = (String) req.getOrDefault("symbol", "sh518850");
        double quantity = req.get("quantity") instanceof Number
                ? ((Number) req.get("quantity")).doubleValue() : 0;
        return ApiResponse.ok(simTradeService.placeOrder(userId, type, symbol, quantity),
                "下单成功");
    }

    /** 账户总览 */
    @GetMapping("/account")
    public ApiResponse<Map<String, Object>> account() {
        return ApiResponse.ok(simTradeService.getAccountOverview(CurrentUser.id()));
    }

    /** 交易记录 */
    @GetMapping("/trades")
    public ApiResponse<Map<String, Object>> trades(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(simTradeService.getTrades(CurrentUser.id(), limit));
    }
}
