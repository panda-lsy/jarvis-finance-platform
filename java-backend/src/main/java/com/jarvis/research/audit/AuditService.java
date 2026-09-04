package com.jarvis.research.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;

    @Transactional
    public void record(Long userId, String action, String target, String clientIp, String detail) {
        repository.save(AuditEvent.builder()
                .userId(userId)
                .action(trim(action, 48))
                .target(trim(target, 64))
                .clientIp(trim(clientIp, 64))
                .detail(trim(detail, 1000))
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentForUser(Long userId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1~200 之间");
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
