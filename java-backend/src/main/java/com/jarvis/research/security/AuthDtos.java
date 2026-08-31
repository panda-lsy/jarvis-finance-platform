package com.jarvis.research.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 认证请求/响应 DTO
 */
public class AuthDtos {

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需6-64位")
        private String password;

        private String displayName;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private String tokenType = "Bearer";
        private long expiresIn;
        private UserInfo user;

        public AuthResponse(String token, long expiresIn, UserInfo user) {
            this.token = token;
            this.expiresIn = expiresIn;
            this.user = user;
        }
    }

    @Data
    public static class UserInfo {
        private Long id;
        private String email;
        private String displayName;
        private Double simCash;
        private Double simInitialCash;
    }
}
