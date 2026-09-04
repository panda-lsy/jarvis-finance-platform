package com.jarvis.research.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SimAccountRepository extends JpaRepository<SimAccount, Long> {
    Optional<SimAccount> findByUserId(Long userId);
    List<SimAccount> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SimAccount a where a.userId = :userId")
    Optional<SimAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
