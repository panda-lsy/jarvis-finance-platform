package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimPositionRepository extends JpaRepository<SimPosition, Long> {
    Optional<SimPosition> findByUserIdAndSymbol(Long userId, String symbol);
    List<SimPosition> findAllByUserId(Long userId);
}
