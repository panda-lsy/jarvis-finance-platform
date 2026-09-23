package com.jarvis.research.admin;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.jarvis.research.admin.AdminDtos.*;

/** 管理员后台 API。SecurityConfig 负责将 /api/admin/** 限制为 ADMIN 角色。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(adminService.listUsers(query, limit));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Map<String, Object>> user(@PathVariable Long userId) {
        return ApiResponse.ok(adminService.userDetails(userId));
    }

    @GetMapping("/users/{userId}/audit")
    public ApiResponse<?> userAudit(@PathVariable Long userId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(adminService.userAudit(userId, limit));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long userId,
                                                   @Valid @RequestBody StatusRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateStatus(
                CurrentUser.id(), userId, body.getEnabled(), body.getReason(), clientIp(request)));
    }

    @PatchMapping("/users/{userId}/role")
    public ApiResponse<Map<String, Object>> role(@PathVariable Long userId,
                                                 @Valid @RequestBody RoleRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateRole(
                CurrentUser.id(), userId, body.getRole(), body.getReason(), clientIp(request)));
    }

    @PostMapping("/users/{userId}/sessions/revoke")
    public ApiResponse<Map<String, Object>> revokeSessions(@PathVariable Long userId,
                                                           @Valid @RequestBody SessionRevokeRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(adminService.revokeSessions(
                CurrentUser.id(), userId, body.getReason(), clientIp(request)));
    }

    @PutMapping("/users/{userId}/quota")
    public ApiResponse<Map<String, Object>> quota(@PathVariable Long userId,
                                                 @Valid @RequestBody QuotaRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateQuota(
                CurrentUser.id(), userId, body, clientIp(request)));
    }

    @PutMapping("/users/{userId}/permissions")
    public ApiResponse<Map<String, Object>> permissions(@PathVariable Long userId,
                                                        @Valid @RequestBody PermissionsRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(adminService.updatePermissions(
                CurrentUser.id(), userId, body, clientIp(request)));
    }

    @GetMapping("/groups")
    public ApiResponse<Map<String, Object>> groups() {
        return ApiResponse.ok(adminService.listGroups());
    }

    @GetMapping("/groups/{groupId}")
    public ApiResponse<Map<String, Object>> group(@PathVariable Long groupId) {
        return ApiResponse.ok(adminService.groupDetails(groupId));
    }

    @PostMapping("/groups")
    public ApiResponse<Map<String, Object>> createGroup(@Valid @RequestBody GroupRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(adminService.createGroup(CurrentUser.id(), body, clientIp(request)));
    }

    @PatchMapping("/groups/{groupId}")
    public ApiResponse<Map<String, Object>> updateGroup(@PathVariable Long groupId,
                                                        @Valid @RequestBody GroupRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateGroup(CurrentUser.id(), groupId, body, clientIp(request)));
    }

    @DeleteMapping("/groups/{groupId}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long groupId,
                                         @Valid @RequestBody ReasonRequest body,
                                         HttpServletRequest request) {
        adminService.deleteGroup(CurrentUser.id(), groupId, body.getReason(), clientIp(request));
        return ApiResponse.ok(null);
    }

    @PutMapping("/groups/{groupId}/members")
    public ApiResponse<Map<String, Object>> groupMembers(@PathVariable Long groupId,
                                                          @Valid @RequestBody GroupMembersRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateGroupMembers(
                CurrentUser.id(), groupId, body, clientIp(request)));
    }

    @PutMapping("/groups/{groupId}/quota")
    public ApiResponse<Map<String, Object>> groupQuota(@PathVariable Long groupId,
                                                       @Valid @RequestBody GroupQuotaRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateGroupQuota(
                CurrentUser.id(), groupId, body, clientIp(request)));
    }

    @PutMapping("/groups/{groupId}/permissions")
    public ApiResponse<Map<String, Object>> groupPermissions(@PathVariable Long groupId,
                                                             @Valid @RequestBody GroupPermissionsRequest body,
                                                             HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateGroupPermissions(
                CurrentUser.id(), groupId, body, clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String trustedProxyIp = request.getHeader("X-Real-IP");
        if (trustedProxyIp != null && !trustedProxyIp.isBlank()) return trustedProxyIp.trim();
        return request.getRemoteAddr();
    }
}
