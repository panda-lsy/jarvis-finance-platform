package com.jarvis.research.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个性化策略问卷请求。
 *
 * Java 主后端在扣减 AI 配额前完成边界校验，避免非法请求先消耗配额再由 Python 拒绝。
 */
@Data
@NoArgsConstructor
public class StrategyRequest {

    @NotNull(message = "horizon_years 不能为空")
    @DecimalMin(value = "0.5", message = "horizon_years 不能小于0.5")
    @DecimalMax(value = "30", message = "horizon_years 不能大于30")
    @JsonProperty("horizon_years")
    private BigDecimal horizonYears;

    @NotNull(message = "max_drawdown_pct 不能为空")
    @DecimalMin(value = "1", message = "max_drawdown_pct 不能小于1")
    @DecimalMax(value = "60", message = "max_drawdown_pct 不能大于60")
    @JsonProperty("max_drawdown_pct")
    private BigDecimal maxDrawdownPct;

    @NotNull(message = "target_return_pct 不能为空")
    @DecimalMin(value = "0", message = "target_return_pct 不能小于0")
    @DecimalMax(value = "50", message = "target_return_pct 不能大于50")
    @JsonProperty("target_return_pct")
    private BigDecimal targetReturnPct;

    @DecimalMin(value = "0", inclusive = false, message = "capital 必须大于0")
    @DecimalMax(value = "1000000000", message = "capital 不能大于1000000000")
    private BigDecimal capital;

    @Pattern(regexp = "none|basic|rich", message = "experience 只能为 none、basic 或 rich")
    private String experience = "basic";

    /** 转成 Python AI 服务使用的稳定字段集合，不透传客户端未知字段。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("horizon_years", horizonYears);
        payload.put("max_drawdown_pct", maxDrawdownPct);
        payload.put("target_return_pct", targetReturnPct);
        if (capital != null) {
            payload.put("capital", capital);
        }
        payload.put("experience", experience == null ? "basic" : experience);
        return payload;
    }
}
