package com.jarvis.research.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<AuditEvent> findByTargetOrderByCreatedAtDesc(String target, Pageable pageable);

    List<AuditEvent> findByUserIdAndActionInOrderByCreatedAtDesc(
            Long userId, Collection<String> actions, Pageable pageable);

    List<AuditEvent> findByUserIdAndCreatedAtGreaterThanEqual(Long userId, LocalDateTime since);
}
