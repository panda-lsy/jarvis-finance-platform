package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Column(nullable = false)
    private Double quantity = 0.0;

    /** 平均成本 */
    @Column(name = "avg_cost", nullable = false)
    private Double avgCost = 0.0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
