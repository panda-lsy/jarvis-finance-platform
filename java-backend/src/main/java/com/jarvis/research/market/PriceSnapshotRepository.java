package com.jarvis.research.market;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, Long> {

    List<PriceSnapshot> findByMarketOrderByTsAsc(String market);
    List<PriceSnapshot> findByMarketOrderByTsDesc(String market, Pageable pageable);

    Optional<PriceSnapshot> findTopByMarketOrderByTsDesc(String market);

    List<PriceSnapshot> findByMarketAndTsAfterOrderByTsAsc(String market, LocalDateTime ts);

    @Query("select p from PriceSnapshot p where p.market = :market and p.ts >= :from order by p.ts asc")
    List<PriceSnapshot> findSince(@Param("market") String market, @Param("from") LocalDateTime from);

    long countByMarket(String market);
}
