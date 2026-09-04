package com.jarvis.research.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 关键业务操作审计事件。 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_audit_action_created", columnList = "action,created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(length = 64)
    private String target;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
