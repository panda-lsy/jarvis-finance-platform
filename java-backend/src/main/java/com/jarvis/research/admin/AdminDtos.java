package com.jarvis.research.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

public final class AdminDtos {

    private AdminDtos() {}

    @Data
    public static class StatusRequest {
        @NotNull(message = "enabled 不能为空")
        private Boolean enabled;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class RoleRequest {
        @NotBlank(message = "role 不能为空")
        @Pattern(regexp = "USER|ADMIN", message = "role 只能为 USER 或 ADMIN")
        private String role;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class SessionRevokeRequest {
        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class ReasonRequest {
        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class QuotaRequest {
        @NotNull(message = "dailyRequestLimit 不能为空")
        @Min(value = 0, message = "dailyRequestLimit 不能小于0")
        @Max(value = 1_000_000, message = "dailyRequestLimit 过大")
        private Integer dailyRequestLimit;

        @NotNull(message = "monthlyTokenLimit 不能为空")
        @Min(value = 0, message = "monthlyTokenLimit 不能小于0")
        @Max(value = 1_000_000_000L, message = "monthlyTokenLimit 过大")
        private Long monthlyTokenLimit;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class PermissionsRequest {
        @NotNull(message = "features 不能为空")
        private List<@NotBlank(message = "功能权限不能为空") @jakarta.validation.constraints.Size(max = 80, message = "功能权限不能超过80个字符") String> features;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class GroupRequest {
        @NotBlank(message = "name 不能为空")
        @jakarta.validation.constraints.Size(max = 80, message = "组名不能超过80个字符")
        private String name;

        @jakarta.validation.constraints.Size(max = 500, message = "描述不能超过500个字符")
        private String description;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class GroupMembersRequest {
        @NotNull(message = "userIds 不能为空")
        @jakarta.validation.constraints.Size(max = 1000, message = "组成员不能超过1000人")
        private List<@NotNull(message = "用户 ID 不能为空") Long> userIds;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class GroupQuotaRequest {
        @NotNull(message = "dailyRequestLimit 不能为空")
        @Min(value = 0, message = "dailyRequestLimit 不能小于0")
        @Max(value = 1_000_000, message = "dailyRequestLimit 过大")
        private Integer dailyRequestLimit;

        @NotNull(message = "monthlyTokenLimit 不能为空")
        @Min(value = 0, message = "monthlyTokenLimit 不能小于0")
        @Max(value = 1_000_000_000L, message = "monthlyTokenLimit 过大")
        private Long monthlyTokenLimit;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }

    @Data
    public static class GroupPermissionsRequest {
        @NotNull(message = "features 不能为空")
        private List<@NotBlank(message = "功能权限不能为空") @jakarta.validation.constraints.Size(max = 80, message = "功能权限不能超过80个字符") String> features;

        @NotBlank(message = "reason 不能为空")
        @jakarta.validation.constraints.Size(max = 300, message = "操作原因不能超过300个字符")
        private String reason;
    }
}
