package com.jarvis.research.admin;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.service.AiQuotaService;
import com.jarvis.research.user.GroupAiQuotaRepository;
import com.jarvis.research.user.GroupFeaturePermissionRepository;
import com.jarvis.research.user.OAuthAccountRepository;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import com.jarvis.research.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AiQuotaService quotaService;
    @Mock private UserFeaturePermissionRepository permissionRepository;
    @Mock private AuditService auditService;
    @Mock private OAuthAccountRepository oauthAccountRepository;
    @Mock private UserGroupRepository groupRepository;
    @Mock private UserGroupMemberRepository groupMemberRepository;
    @Mock private GroupAiQuotaRepository groupQuotaRepository;
    @Mock private GroupFeaturePermissionRepository groupPermissionRepository;

    @InjectMocks private AdminService service;

    @Test
    void revokingSessionsIncrementsCredentialVersionAndRecordsReasonAndChange() {
        User target = user(72L, "USER", true, 4);
        when(userRepository.findById(72L)).thenReturn(Optional.of(target));

        Map<String, Object> result = service.revokeSessions(9L, 72L, "疑似账号被盗", "203.0.113.7");

        assertEquals(5, target.getCredentialVersion());
        assertEquals(Boolean.TRUE, result.get("sessionsRevoked"));
        verify(userRepository).save(target);
        verify(auditService).record(eq(9L), eq("ADMIN_USER_SESSIONS_REVOKE"), eq("user:72"), eq("203.0.113.7"),
                argThat(detail -> detail.contains("before=credentialVersion:4")
                        && detail.contains("after=credentialVersion:5")
                        && detail.contains("reason=疑似账号被盗")));
    }

    @Test
    void statusAuditContainsBeforeAfterAndRequiredReason() {
        User target = user(72L, "USER", true, 0);
        stubBasicUserView(target);
        when(userRepository.findById(72L)).thenReturn(Optional.of(target));

        service.updateStatus(9L, 72L, false, "管理员确认的异常注册", "198.51.100.4");

        verify(auditService).record(eq(9L), eq("ADMIN_USER_STATUS"), eq("user:72"), eq("198.51.100.4"),
                argThat(detail -> detail.contains("before=enabled:true")
                        && detail.contains("after=enabled:false")
                        && detail.contains("reason=管理员确认的异常注册")));
    }

    @Test
    void administratorCannotRevokeItsOwnOnlySession() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.revokeSessions(9L, 9L, "轮换会话", "127.0.0.1"));

        assertEquals(400, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("当前管理员"));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(auditService, never()).record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void stubBasicUserView(User user) {
        when(groupMemberRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(oauthAccountRepository.findByUserIdOrderByProviderAsc(user.getId())).thenReturn(List.of());
    }

    private User user(Long id, String role, boolean enabled, int credentialVersion) {
        return User.builder()
                .id(id)
                .email("target@example.com")
                .passwordHash("not-used")
                .role(role)
                .enabled(enabled)
                .credentialVersion(credentialVersion)
                .build();
    }
}
