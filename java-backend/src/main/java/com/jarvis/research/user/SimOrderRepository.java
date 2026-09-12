package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SimOrderRepository extends JpaRepository<SimOrder, Long> {

    List<SimOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<SimOrder> findByStatusOrderByCreatedAtAsc(String status);

    Optional<SimOrder> findByIdAndUserId(Long id, Long userId);

    Optional<SimOrder> findByUserIdAndClientOrderId(Long userId, String clientOrderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SimOrder o set o.status = 'TRIGGERING', o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id and o.status = 'OPEN'")
    int claimOpen(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SimOrder o set o.stopPrice = :stopPrice, o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id and o.userId = :userId and o.status = 'OPEN'")
    int updateOpenStopPrice(@Param("id") Long id,
                            @Param("userId") Long userId,
                            @Param("stopPrice") BigDecimal stopPrice);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SimOrder o set o.status = 'CANCELLED', o.updatedAt = CURRENT_TIMESTAMP "
            + "where o.id = :id and o.userId = :userId and o.status = 'OPEN'")
    int cancelOpen(@Param("id") Long id, @Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SimOrder o set o.status = :status, o.triggeredAt = :at, o.updatedAt = :at "
            + "where o.id = :id")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("at") LocalDateTime at);
}
