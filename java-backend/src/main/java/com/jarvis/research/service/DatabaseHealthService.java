package com.jarvis.research.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** 数据库 liveness/readiness 检查；不暴露连接串、用户名或异常详情。 */
@Service
@RequiredArgsConstructor
public class DatabaseHealthService {

    private final DataSource dataSource;

    public CheckResult check() {
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    return CheckResult.down();
                }
            }
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            String product = connection.getMetaData().getDatabaseProductName();
            return CheckResult.up(product, latencyMs);
        } catch (Exception ignored) {
            return CheckResult.down();
        }
    }

    public record CheckResult(boolean up, String product, Long latencyMs) {
        static CheckResult up(String product, long latencyMs) {
            return new CheckResult(true, product, latencyMs);
        }

        static CheckResult down() {
            return new CheckResult(false, null, null);
        }

        public Map<String, Object> publicView() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", up ? "up" : "down");
            if (up) data.put("latency_ms", latencyMs);
            return data;
        }

        public Map<String, Object> detailedView() {
            Map<String, Object> data = new LinkedHashMap<>(publicView());
            if (up && product != null) data.put("product", product);
            return data;
        }
    }
}
