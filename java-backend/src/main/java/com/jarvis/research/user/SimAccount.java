package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Column(name = "initial_cash", nullable = false)
    private Double initialCash = 100000.0;

    /** 可用资金 */
    @Column(name = "cash", nullable = false)
    private Double cash = 100000.0;

    /** 借款余额 (杠杆借入总额) */
    @Column(name = "loan_balance")
    private Double loanBalance = 0.0;

    /** 冻结保证金 (杠杆持仓占用) */
    @Column(name = "frozen_margin")
    private Double frozenMargin = 0.0;

    /** 账户状态: ACTIVE / FROZEN / CLOSED */
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
