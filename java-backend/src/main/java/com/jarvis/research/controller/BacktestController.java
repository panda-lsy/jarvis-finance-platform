package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.service.BacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Java 统一回测入口（需登录）。 */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @GetMapping
    public ApiResponse<Map<String, Object>> run(
            @RequestParam(defaultValue = "gold_etf") String market,
            @RequestParam(name = "short_ma", defaultValue = "5") int shortMa,
            @RequestParam(name = "long_ma", defaultValue = "20") int longMa,
            @RequestParam(name = "initial_cash", defaultValue = "100000") double initialCash,
            @RequestParam(defaultValue = "120") int limit) {
        return ApiResponse.ok(backtestService.run(market, shortMa, longMa, initialCash, limit));
    }
}
