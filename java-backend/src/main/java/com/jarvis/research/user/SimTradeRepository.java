package com.jarvis.research.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimTradeRepository extends JpaRepository<SimTrade, Long> {
    List<SimTrade> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Optional<SimTrade> findByUserIdAndClientOrderId(Long userId, String clientOrderId);
    long countByUserId(Long userId);
}
