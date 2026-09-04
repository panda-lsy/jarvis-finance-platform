package com.jarvis.research.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
