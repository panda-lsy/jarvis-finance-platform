package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SimAccountRepository extends JpaRepository<SimAccount, Long> {
    Optional<SimAccount> findByUserId(Long userId);
}
