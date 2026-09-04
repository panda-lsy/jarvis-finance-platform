package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘持仓 - 某用户某标的的持仓
 */
@Entity
@Table(name = "sim_position",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "symbol"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 标的代码, 如 sh518850 */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** 持有数量(股/份额) */
    @Builder.Default
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** 平均成本 */
    @Builder.Default
    @Column(name = "avg_cost", nullable = false, precision = 24, scale = 8)
    private BigDecimal avgCost = BigDecimal.ZERO;

    /** 杠杆倍数 (1.0 = 全款买入; >1 为杠杆) */
    @Builder.Default
    @Column(name = "leverage", precision = 8, scale = 4)
    private BigDecimal leverage = BigDecimal.ONE;

    /** 借款金额 (杠杆部分) */
    @Builder.Default
    @Column(name = "loan_amount", precision = 19, scale = 4)
    private BigDecimal loanAmount = BigDecimal.ZERO;

    /** 累计投入保证金 */
    @Builder.Default
    @Column(name = "margin_used", precision = 19, scale = 4)
    private BigDecimal marginUsed = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
