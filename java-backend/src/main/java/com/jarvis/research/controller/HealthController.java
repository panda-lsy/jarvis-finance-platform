package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.DatabaseHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 健康检查：live 只看 Java 进程，ready 额外要求数据库可查询。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final AiProxyService aiProxyService;
    private final DatabaseHealthService databaseHealthService;

    /** 兼容旧探针：等同 liveness。 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(livePayload());
    }

    @GetMapping("/health/live")
    public ApiResponse<Map<String, Object>> live() {
        return ApiResponse.ok(livePayload());
    }

    /** Java readiness：数据库必须能在短超时内执行 SELECT 1。 */
    @GetMapping("/health/ready")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ready() {
        DatabaseHealthService.CheckResult db = databaseHealthService.check();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", db.up() ? "ready" : "not_ready");
        data.put("service", "jarvis-gold-research-backend (java)");
        data.put("database", db.publicView());
        data.put("time", LocalDateTime.now().toString());
        if (db.up()) {
            return ResponseEntity.ok(ApiResponse.ok(data));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "service not ready", data));
    }

    /** 登录用户可查看数据库产品与延迟；不暴露连接串、用户或异常详情。 */
    @GetMapping("/health/db")
    public ResponseEntity<ApiResponse<Map<String, Object>>> databaseHealth() {
        DatabaseHealthService.CheckResult db = databaseHealthService.check();
        if (db.up()) {
            return ResponseEntity.ok(ApiResponse.ok(db.detailedView()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "database unavailable", db.detailedView()));
    }

    /** 登录用户通过 Java 检查 Python AI 服务，不向浏览器暴露 Python 地址。 */
    @GetMapping("/health/ai")
    public ApiResponse<Map<String, Object>> aiHealth() {
        return ApiResponse.ok(aiProxyService.get("/api/ready"));
    }

    private Map<String, Object> livePayload() {
        return Map.of(
                "status", "ok",
                "service", "jarvis-gold-research-backend (java)",
                "time", LocalDateTime.now().toString()
        );
    }
}
