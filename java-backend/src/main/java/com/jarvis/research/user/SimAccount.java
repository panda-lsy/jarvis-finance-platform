package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘账户 - 每用户独立
 */
@Entity
@Table(name = "sim_account", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联用户 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 初始资金 */
    @Builder.Default
    @Column(name = "initial_cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialCash = new BigDecimal("100000.0000");

    /** 可用资金 */
    @Builder.Default
    @Column(name = "cash", nullable = false, precision = 19, scale = 4)
    private BigDecimal cash = new BigDecimal("100000.0000");

    /** 借款余额 (杠杆借入总额) */
    @Builder.Default
    @Column(name = "loan_balance", precision = 19, scale = 4)
    private BigDecimal loanBalance = BigDecimal.ZERO;

    /** 冻结保证金 (杠杆持仓占用) */
    @Builder.Default
    @Column(name = "frozen_margin", precision = 19, scale = 4)
    private BigDecimal frozenMargin = BigDecimal.ZERO;

    /** 账户状态: ACTIVE / FROZEN / CLOSED */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
    }
}
