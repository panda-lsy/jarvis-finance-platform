package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 模拟盘交易记录
 */
@Entity
@Table(name = "sim_trade", indexes = {
        @Index(name = "idx_trade_user", columnList = "user_id"),
        @Index(name = "idx_trade_symbol", columnList = "symbol")
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

    /** BUY / SELL */
    @Column(nullable = false, length = 10)
    private String type;

    /** 成交价格 */
    @Column(nullable = false)
    private Double price;

    /** 成交数量 */
    @Column(nullable = false)
    private Double quantity;

    /** 成交金额 */
    @Column(nullable = false)
    private Double amount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
