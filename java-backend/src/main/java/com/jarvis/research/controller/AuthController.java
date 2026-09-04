package com.jarvis.research.controller;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.AuthDtos.*;
import com.jarvis.research.security.AuthService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AuthRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/** 认证 API：JWT 仅写入 HttpOnly Cookie，不暴露给前端 JavaScript。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JarvisProperties props;
    private final AuthRateLimitService authRateLimitService;
    private final AuditService auditService;

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName()
        ));
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        authRateLimitService.checkRegister(clientIp(request));
        String clientIp = clientIp(request);
        AuthResponse auth = authService.register(req, clientIp);
        writeAuthCookie(response, auth.getToken(), auth.getExpiresIn());
        auth.setToken(null);
        return ApiResponse.ok(auth, "注册成功");
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        authRateLimitService.checkLogin(clientIp(request), req.getEmail());
        String clientIp = clientIp(request);
        AuthResponse auth = authService.login(req, clientIp);
        writeAuthCookie(response, auth.getToken(), auth.getExpiresIn());
        auth.setToken(null);
        return ApiResponse.ok(auth, "登录成功");
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me() {
        return ApiResponse.ok(authService.getUserInfo(CurrentUser.id()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        if (CurrentUser.isAuthenticated()) {
            auditService.record(CurrentUser.id(), "USER_LOGOUT", "auth", clientIp(request), "退出登录");
        }
        ResponseCookie.ResponseCookieBuilder cookie = baseCookie("").maxAge(Duration.ZERO);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        return ApiResponse.ok(null, "已退出登录");
    }

    private void writeAuthCookie(HttpServletResponse response, String token, long expirationMs) {
        ResponseCookie cookie = baseCookie(token)
                .maxAge(Duration.ofMillis(expirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String clientIp(HttpServletRequest request) {
        // 生产 Java 只监听 127.0.0.1，外部请求必须经过受信任的 Nginx/Cloudflare。
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        JarvisProperties.Auth auth = props.getAuth();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(auth.getCookieName(), value)
                .httpOnly(true)
                .secure(auth.isCookieSecure())
                .sameSite(auth.getSameSite())
                .path("/");
        if (auth.getCookieDomain() != null && !auth.getCookieDomain().isBlank()) {
            builder.domain(auth.getCookieDomain());
        }
        return builder;
    }
}
