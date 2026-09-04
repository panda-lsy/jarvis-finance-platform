package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.SimTradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/** 模拟盘 API（需登录）。 */
@RestController
@RequestMapping("/api/sim")
@RequiredArgsConstructor
public class SimTradeController {

    private final SimTradeService simTradeService;

    @PostMapping("/order")
    public ApiResponse<Map<String, Object>> order(@Valid @RequestBody OrderRequest req) {
        return ApiResponse.ok(simTradeService.placeOrder(
                        CurrentUser.id(),
                        req.getType(),
                        req.getSymbol(),
                        req.getQuantity(),
                        req.getLeverage(),
                        req.getClientOrderId()),
                "下单成功");
    }

    @GetMapping("/account")
    public ApiResponse<Map<String, Object>> account() {
        return ApiResponse.ok(simTradeService.getAccountOverview(CurrentUser.id()));
    }

    @GetMapping("/trades")
    public ApiResponse<Map<String, Object>> trades(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(simTradeService.getTrades(CurrentUser.id(), limit));
    }

    @Data
    public static class OrderRequest {
        @NotBlank(message = "type 不能为空")
        private String type = "BUY";

        @NotBlank(message = "symbol 不能为空")
        @Size(max = 20, message = "symbol 长度不能超过20字符")
        private String symbol = "sh518850";

        @DecimalMin(value = "0.00000001", message = "数量必须大于0")
        private BigDecimal quantity;

        @DecimalMin(value = "1", message = "杠杆不能小于1")
        @DecimalMax(value = "5", message = "杠杆不能大于5")
        private BigDecimal leverage = BigDecimal.ONE;

        @Size(max = 64, message = "clientOrderId 长度不能超过64字符")
        private String clientOrderId;
    }
}
