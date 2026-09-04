package com.jarvis.research.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证入口轻量限流。
 * 单实例场景使用内存窗口；未来多实例部署时替换为 Redis 即可，Controller 无需变化。
 */
@Service
public class AuthRateLimitService {

    private static final int LOGIN_ACCOUNT_LIMIT = 10;
    private static final int LOGIN_IP_LIMIT = 30;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int REGISTER_LIMIT = 5;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);

    private final Map<String, Window> loginAccountWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginIpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> registerWindows = new ConcurrentHashMap<>();

    public void checkLogin(String clientIp, String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        consume(loginAccountWindows, normalizedEmail,
                LOGIN_ACCOUNT_LIMIT, LOGIN_WINDOW, "该账号登录尝试过于频繁，请稍后再试");
        consume(loginIpWindows, safe(clientIp),
                LOGIN_IP_LIMIT, LOGIN_WINDOW, "登录请求过于频繁，请稍后再试");
    }

    public void checkRegister(String clientIp) {
        consume(registerWindows, safe(clientIp),
                REGISTER_LIMIT, REGISTER_WINDOW, "注册请求过于频繁，请稍后再试");
    }

    private void consume(Map<String, Window> windows, String key, int limit,
                         Duration duration, String message) {
        Instant now = Instant.now();
        windows.compute(key, (k, current) -> {
            Window window = current;
            if (window == null || !now.isBefore(window.startedAt.plus(duration))) {
                window = new Window(now, 0);
            }
            if (window.count >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
            }
            return new Window(window.startedAt, window.count + 1);
        });
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record Window(Instant startedAt, int count) {}
}
