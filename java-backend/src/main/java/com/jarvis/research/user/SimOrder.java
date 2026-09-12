package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘挂单。当前用于 STOP_MARKET，后续可扩展 LIMIT / STOP_LIMIT。
 */
@Entity
@Table(name = "sim_order", indexes = {
        @Index(name = "idx_sim_order_user_status", columnList = "user_id,status"),
        @Index(name = "idx_sim_order_status_created", columnList = "status,created_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_sim_order_user_client", columnNames = {"user_id", "client_order_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    /** BUY / SELL */
    @Column(nullable = false, length = 10)
    private String side;

    /** STOP_MARKET */
    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Builder.Default
    @Column(precision = 8, scale = 4)
    private BigDecimal leverage = BigDecimal.ONE;

    @Column(name = "stop_price", nullable = false, precision = 24, scale = 8)
    private BigDecimal stopPrice;

    /** DAY / GTC */
    @Builder.Default
    @Column(name = "time_in_force", nullable = false, length = 10)
    private String timeInForce = "DAY";

    /** OPEN / TRIGGERING / FILLED / CANCELLED / REJECTED */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
