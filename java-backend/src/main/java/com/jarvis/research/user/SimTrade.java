package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘交易记录
 */
@Entity
@Table(name = "sim_trade", indexes = {
        @Index(name = "idx_trade_user", columnList = "user_id"),
        @Index(name = "idx_trade_symbol", columnList = "symbol")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_trade_user_client_order", columnNames = {"user_id", "client_order_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    /** BUY / SELL / FORCE_SELL */
    @Column(nullable = false, length = 10)
    private String type;

    /** 客户端幂等订单号；同一用户不可重复。 */
    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    /** 成交价格 */
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal price;

    /** 成交数量 */
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    /** 成交金额 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 杠杆倍数 (1.0 = 全款) */
    @Builder.Default
    @Column(precision = 8, scale = 4)
    private BigDecimal leverage = BigDecimal.ONE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
