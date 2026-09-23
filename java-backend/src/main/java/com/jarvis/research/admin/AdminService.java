package com.jarvis.research.admin;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiQuotaService;
import com.jarvis.research.user.AiQuota;
import com.jarvis.research.user.User;
import com.jarvis.research.user.OAuthAccount;
import com.jarvis.research.user.OAuthAccountRepository;
import com.jarvis.research.user.UserFeaturePermission;
import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.UserRepository;
import com.jarvis.research.user.GroupAiQuota;
import com.jarvis.research.user.GroupAiQuotaRepository;
import com.jarvis.research.user.GroupFeaturePermission;
import com.jarvis.research.user.GroupFeaturePermissionRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMember;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jarvis.research.admin.AdminDtos.*;

/** 管理员账户、AI 配额和用户功能权限管理。 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final AiQuotaService quotaService;
    private final UserFeaturePermissionRepository permissionRepository;
    private final AuditService auditService;
    private final OAuthAccountRepository oauthAccountRepository;
    private final UserGroupRepository groupRepository;
    private final UserGroupMemberRepository groupMemberRepository;
    private final GroupAiQuotaRepository groupQuotaRepository;
    private final GroupFeaturePermissionRepository groupPermissionRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> listUsers(String query, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1~200 之间");
        }
        PageRequest pageRequest = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> page = query == null || query.isBlank()
                ? userRepository.findAll(pageRequest)
                : userRepository.findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                        query.trim(), query.trim(), pageRequest);
        List<Map<String, Object>> users = page.getContent().stream().map(this::basicUserView).toList();
        return Map.of("items", users, "count", users.size(), "total", page.getTotalElements());
    }

    @Transactional
    public Map<String, Object> userDetails(Long userId) {
        User user = requireUser(userId);
        return fullUserView(user);
    }

    @Transactional(readOnly = true)
    public List<com.jarvis.research.audit.AuditEvent> userAudit(Long userId, int limit) {
        requireUser(userId);
        return auditService.recentForAdmin(userId, limit);
    }

    @Transactional
    public Map<String, Object> updateStatus(Long actorId, Long userId, boolean enabled,
                                            String reason, String clientIp) {
        if (actorId.equals(userId) && !enabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能禁用当前管理员账号");
        }
        User user = requireUser(userId);
        boolean beforeEnabled = user.isEnabled();
        user.setEnabled(enabled);
        userRepository.save(user);
        auditService.record(actorId, "ADMIN_USER_STATUS", "user:" + userId, clientIp,
                "before=enabled:" + beforeEnabled + "; after=enabled:" + enabled
                        + "; reason=" + normalizeReason(reason));
        return basicUserView(user);
    }

    @Transactional
    public Map<String, Object> updateRole(Long actorId, Long userId, String role,
                                          String reason, String clientIp) {
        User user = requireUser(userId);
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!"USER".equals(normalized) && !"ADMIN".equals(normalized)) {
            throw new IllegalArgumentException("role 只能为 USER 或 ADMIN");
        }
        if (actorId.equals(userId) && !"ADMIN".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除当前管理员的管理员权限");
        }
        String beforeRole = user.getRole();
        user.setRole(normalized);
        userRepository.save(user);
        auditService.record(actorId, "ADMIN_USER_ROLE", "user:" + userId, clientIp,
                "before=role:" + beforeRole + "; after=role:" + normalized
                        + "; reason=" + normalizeReason(reason));
        return basicUserView(user);
    }

    /** 递增凭证版本，立即撤销目标用户已经签发的所有 JWT，不改动其密码。 */
    @Transactional
    public Map<String, Object> revokeSessions(Long actorId, Long userId, String reason, String clientIp) {
        if (actorId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能重置当前管理员的登录状态");
        }
        User user = requireUser(userId);
        int previousVersion = user.getCredentialVersion();
        int nextVersion = Math.addExact(previousVersion, 1);
        user.setCredentialVersion(nextVersion);
        userRepository.save(user);
        auditService.record(actorId, "ADMIN_USER_SESSIONS_REVOKE", "user:" + userId, clientIp,
                "before=credentialVersion:" + previousVersion
                        + "; after=credentialVersion:" + nextVersion
                        + "; sessions=revoked; reason=" + normalizeReason(reason));
        return Map.of("userId", userId, "sessionsRevoked", true);
    }

    @Transactional
    public Map<String, Object> updateQuota(Long actorId, Long userId, QuotaRequest request, String clientIp) {
        User user = requireUser(userId);
        String previousQuota = quotaService.findForAdmin(userId)
                .map(existing -> "dailyRequestLimit:" + existing.getDailyRequestLimit()
                        + ",monthlyTokenLimit:" + existing.getMonthlyTokenLimit())
                .orElse("unset");
        AiQuota quota = quotaService.getOrCreateForAdmin(userId);
        quota.setDailyRequestLimit(request.getDailyRequestLimit());
        quota.setMonthlyTokenLimit(request.getMonthlyTokenLimit());
        quota.setUpdatedAt(LocalDateTime.now());
        quotaService.save(quota);
        auditService.record(actorId, "ADMIN_AI_QUOTA", "user:" + userId, clientIp,
                "before=" + previousQuota
                        + "; after=dailyRequestLimit:" + request.getDailyRequestLimit()
                        + ",monthlyTokenLimit:" + request.getMonthlyTokenLimit()
                        + "; reason=" + normalizeReason(request.getReason()));
        return quotaView(user, quota);
    }

    @Transactional
    public Map<String, Object> updatePermissions(Long actorId, Long userId,
                                                 PermissionsRequest request, String clientIp) {
        User user = requireUser(userId);
        List<String> features = request.getFeatures() == null ? List.of() : request.getFeatures().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(100)
                .toList();
        List<String> previousFeatures = permissionRepository.findByUserIdOrderByFeatureKey(userId).stream()
                .filter(UserFeaturePermission::isEnabled)
                .map(UserFeaturePermission::getFeatureKey)
                .toList();
        permissionRepository.deleteByUserId(userId);
        List<UserFeaturePermission> entities = new ArrayList<>();
        for (String feature : features) {
            entities.add(UserFeaturePermission.builder()
                    .userId(userId)
                    .featureKey(feature)
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        permissionRepository.saveAll(entities);
        auditService.record(actorId, "ADMIN_USER_PERMISSIONS", "user:" + userId, clientIp,
                "before=features:" + summarizeFeatures(previousFeatures)
                        + "; after=features:" + summarizeFeatures(features)
                        + "; reason=" + normalizeReason(request.getReason()));
        return fullUserView(user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listGroups() {
        List<Map<String, Object>> groups = groupRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::groupView)
                .toList();
        return Map.of("items", groups, "count", groups.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> groupDetails(Long groupId) {
        return groupView(requireGroup(groupId));
    }

    @Transactional
    public Map<String, Object> createGroup(Long actorId, GroupRequest request, String clientIp) {
        String name = normalizeGroupName(request.getName());
        if (groupRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户组名称已存在");
        }
        UserGroup group = groupRepository.save(UserGroup.builder()
                .name(name)
                .description(trimToNull(request.getDescription()))
                .enabled(true)
                .build());
        auditService.record(actorId, "ADMIN_GROUP_CREATE", "group:" + group.getId(), clientIp,
                "before=absent; after=name:" + auditValue(group.getName())
                        + ",description:" + auditValue(group.getDescription())
                        + "; reason=" + normalizeReason(request.getReason()));
        return groupView(group);
    }

    @Transactional
    public Map<String, Object> updateGroup(Long actorId, Long groupId,
                                           GroupRequest request, String clientIp) {
        UserGroup group = requireGroup(groupId);
        String name = normalizeGroupName(request.getName());
        String previousName = group.getName();
        String previousDescription = group.getDescription();
        groupRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (!Objects.equals(existing.getId(), groupId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "用户组名称已存在");
            }
        });
        group.setName(name);
        group.setDescription(trimToNull(request.getDescription()));
        groupRepository.save(group);
        auditService.record(actorId, "ADMIN_GROUP_UPDATE", "group:" + groupId, clientIp,
                "before=name:" + auditValue(previousName) + ",description:" + auditValue(previousDescription)
                        + "; after=name:" + auditValue(name) + ",description:" + auditValue(group.getDescription())
                        + "; reason=" + normalizeReason(request.getReason()));
        return groupView(group);
    }

    @Transactional
    public void deleteGroup(Long actorId, Long groupId, String reason, String clientIp) {
        UserGroup group = requireGroup(groupId);
        long memberCount = groupMemberRepository.countByGroupId(groupId);
        auditService.record(actorId, "ADMIN_GROUP_DELETE", "group:" + groupId, clientIp,
                "before=name:" + auditValue(group.getName()) + ",enabled:" + group.isEnabled() + ",members:" + memberCount
                        + "; after=deleted; reason=" + normalizeReason(reason));
        groupRepository.delete(group);
    }

    @Transactional
    public Map<String, Object> updateGroupMembers(Long actorId, Long groupId,
                                                  GroupMembersRequest request, String clientIp) {
        UserGroup group = requireGroup(groupId);
        List<Long> userIds = request.getUserIds() == null ? List.of() : request.getUserIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (Long userId : userIds) requireUser(userId);
        List<Long> previousUserIds = groupMemberRepository.findByGroupIdOrderByCreatedAtAsc(groupId).stream()
                .map(UserGroupMember::getUserId)
                .toList();
        // 一个用户只允许一个策略组；重新分配时从旧组移出，避免多个共享配额叠加。
        groupMemberRepository.deleteByGroupId(groupId);
        for (Long userId : userIds) {
            groupMemberRepository.deleteByUserId(userId);
            groupMemberRepository.save(UserGroupMember.builder()
                    .groupId(groupId)
                    .userId(userId)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        auditService.record(actorId, "ADMIN_GROUP_MEMBERS", "group:" + groupId, clientIp,
                "before=users:" + summarizeIds(previousUserIds)
                        + "; after=users:" + summarizeIds(userIds)
                        + "; reason=" + normalizeReason(request.getReason()));
        return groupView(group);
    }

    @Transactional
    public Map<String, Object> updateGroupQuota(Long actorId, Long groupId,
                                                GroupQuotaRequest request, String clientIp) {
        UserGroup group = requireGroup(groupId);
        var existingQuota = groupQuotaRepository.findByGroupId(groupId);
        String previousQuota = existingQuota.map(this::quotaSummary).orElse("unset");
        GroupAiQuota quota = existingQuota.orElseGet(() ->
                groupQuotaRepository.save(GroupAiQuota.builder().groupId(groupId).build()));
        quota.setDailyRequestLimit(request.getDailyRequestLimit());
        quota.setMonthlyTokenLimit(request.getMonthlyTokenLimit());
        quota.setUpdatedAt(LocalDateTime.now());
        groupQuotaRepository.save(quota);
        auditService.record(actorId, "ADMIN_GROUP_QUOTA", "group:" + groupId, clientIp,
                "before=" + previousQuota + "; after=" + quotaSummary(quota)
                        + "; reason=" + normalizeReason(request.getReason()));
        return groupView(group);
    }

    @Transactional
    public Map<String, Object> updateGroupPermissions(Long actorId, Long groupId,
                                                      GroupPermissionsRequest request,
                                                      String clientIp) {
        UserGroup group = requireGroup(groupId);
        List<String> features = request.getFeatures() == null ? List.of() : request.getFeatures().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(100)
                .toList();
        List<String> previousFeatures = groupPermissionRepository.findByGroupIdOrderByFeatureKey(groupId).stream()
                .filter(GroupFeaturePermission::isEnabled)
                .map(GroupFeaturePermission::getFeatureKey)
                .toList();
        groupPermissionRepository.deleteByGroupId(groupId);
        LocalDateTime now = LocalDateTime.now();
        List<GroupFeaturePermission> entities = features.stream()
                .map(feature -> GroupFeaturePermission.builder()
                        .groupId(groupId)
                        .featureKey(feature)
                        .enabled(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();
        groupPermissionRepository.saveAll(entities);
        auditService.record(actorId, "ADMIN_GROUP_PERMISSIONS", "group:" + groupId, clientIp,
                "before=features:" + summarizeFeatures(previousFeatures)
                        + "; after=features:" + summarizeFeatures(features)
                        + "; reason=" + normalizeReason(request.getReason()));
        return groupView(group);
    }

    private Map<String, Object> fullUserView(User user) {
        Map<String, Object> out = new LinkedHashMap<>(basicUserView(user));
        out.put("quota", quotaService.findForAdmin(user.getId())
                .map(quota -> quotaView(user, quota).get("quota"))
                .orElse(null));
        out.put("permissions", permissionRepository.findByUserIdOrderByFeatureKey(user.getId()).stream()
                .map(UserFeaturePermission::getFeatureKey).toList());
        out.put("group", userGroupView(user.getId()));
        return out;
    }

    private Map<String, Object> quotaView(User user, AiQuota quota) {
        Map<String, Object> quotaData = new LinkedHashMap<>();
        quotaData.put("dailyRequestLimit", quota.getDailyRequestLimit());
        quotaData.put("dailyRequestUsed", quota.getDailyRequestUsed());
        quotaData.put("monthlyTokenLimit", quota.getMonthlyTokenLimit());
        quotaData.put("monthlyTokenUsed", quota.getMonthlyTokenUsed());
        quotaData.put("resetDate", quota.getResetDate());
        quotaData.put("periodMonth", quota.getPeriodMonth());
        return Map.of("userId", user.getId(), "quota", quotaData);
    }

    private Map<String, Object> basicUserView(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("email", user.getEmail());
        out.put("displayName", user.getDisplayName());
        out.put("role", user.getRole());
        out.put("enabled", user.isEnabled());
        out.put("createdAt", user.getCreatedAt());
        out.put("lastLoginAt", user.getLastLoginAt());
        out.put("group", userGroupView(user.getId()));
        List<Map<String, Object>> oauth = oauthAccountRepository.findByUserIdOrderByProviderAsc(user.getId()).stream()
                .map(this::oauthView).toList();
        out.put("oauthAccounts", oauth);
        out.put("authProviders", oauth.stream().map(item -> String.valueOf(item.get("provider"))).toList());
        return out;
    }

    private Map<String, Object> oauthView(OAuthAccount account) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", account.getProvider());
        out.put("login", account.getProviderLogin());
        out.put("email", account.getEmail());
        out.put("createdAt", account.getCreatedAt());
        out.put("updatedAt", account.getUpdatedAt());
        return out;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private UserGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户组不存在"));
    }

    private Map<String, Object> userGroupView(Long userId) {
        return groupMemberRepository.findByUserId(userId)
                .map(member -> groupRepository.findById(member.getGroupId())
                        .map(group -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("id", group.getId());
                            out.put("name", group.getName());
                            out.put("enabled", group.isEnabled());
                            out.put("quota", groupQuotaRepository.findByGroupId(group.getId())
                                    .map(this::groupQuotaView).orElse(null));
                            out.put("permissions", groupPermissionRepository
                                    .findByGroupIdOrderByFeatureKey(group.getId()).stream()
                                    .filter(GroupFeaturePermission::isEnabled)
                                    .map(GroupFeaturePermission::getFeatureKey)
                                    .toList());
                            return out;
                        })
                        .orElse(null))
                .orElse(null);
    }

    private Map<String, Object> groupView(UserGroup group) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", group.getId());
        out.put("name", group.getName());
        out.put("description", group.getDescription());
        out.put("enabled", group.isEnabled());
        out.put("createdAt", group.getCreatedAt());
        out.put("updatedAt", group.getUpdatedAt());
        out.put("memberCount", groupMemberRepository.countByGroupId(group.getId()));
        out.put("members", groupMemberRepository.findByGroupIdOrderByCreatedAtAsc(group.getId()).stream()
                .map(member -> userRepository.findById(member.getUserId()).map(this::basicUserView).orElse(null))
                .filter(Objects::nonNull)
                .toList());
        out.put("quota", groupQuotaRepository.findByGroupId(group.getId()).map(this::groupQuotaView).orElse(null));
        out.put("permissions", groupPermissionRepository.findByGroupIdOrderByFeatureKey(group.getId()).stream()
                .filter(GroupFeaturePermission::isEnabled)
                .map(GroupFeaturePermission::getFeatureKey)
                .toList());
        return out;
    }

    private Map<String, Object> groupQuotaView(GroupAiQuota quota) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailyRequestLimit", quota.getDailyRequestLimit());
        out.put("dailyRequestUsed", quota.getDailyRequestUsed());
        out.put("monthlyTokenLimit", quota.getMonthlyTokenLimit());
        out.put("monthlyTokenUsed", quota.getMonthlyTokenUsed());
        out.put("resetDate", quota.getResetDate());
        out.put("periodMonth", quota.getPeriodMonth());
        return out;
    }

    private String quotaSummary(GroupAiQuota quota) {
        return "dailyRequestLimit:" + quota.getDailyRequestLimit()
                + ",monthlyTokenLimit:" + quota.getMonthlyTokenLimit();
    }

    private String summarizeFeatures(List<String> features) {
        int shown = Math.min(features.size(), 6);
        String values = features.subList(0, shown).stream()
                .map(feature -> auditValue(feature.length() <= 40 ? feature : feature.substring(0, 40) + "…"))
                .collect(java.util.stream.Collectors.joining(","));
        return "[" + values + (features.size() > shown ? ",…" : "") + "] (count=" + features.size() + ")";
    }

    private String summarizeIds(List<Long> ids) {
        int shown = Math.min(ids.size(), 10);
        String values = ids.subList(0, shown).stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return "[" + values + (ids.size() > shown ? ",…" : "") + "] (count=" + ids.size() + ")";
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.replace('\r', ' ').replace('\n', ' ')
                .replace(';', ',').trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("操作原因不能为空");
        if (normalized.length() > 300) throw new IllegalArgumentException("操作原因不能超过300个字符");
        return normalized;
    }

    private String auditValue(String value) {
        if (value == null || value.isBlank()) return "—";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replace(';', ',').trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "…";
    }

    private String normalizeGroupName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank()) throw new IllegalArgumentException("组名不能为空");
        return name;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
