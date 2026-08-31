package com.jarvis.research.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KlineDailyRepository extends JpaRepository<KlineDaily, Long> {

    Optional<KlineDaily> findByMarketAndDate(String market, String date);

    List<KlineDaily> findByMarketOrderByDateAsc(String market);

    List<KlineDaily> findTopByMarketOrderByDateDesc(String market);

    long countByMarket(String market);
}
