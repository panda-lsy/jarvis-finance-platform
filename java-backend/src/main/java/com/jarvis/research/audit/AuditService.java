package com.jarvis.research.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository repository;

    @Transactional
    public void record(Long userId, String action, String target, String clientIp, String detail) {
        repository.save(AuditEvent.builder()
                .userId(userId)
                .action(trim(action, 48))
                .target(trim(target, 64))
                .clientIp(trim(clientIp, 64))
                .detail(trim(detail, 1000))
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentForUser(Long userId, int limit) {
        validateLimit(limit);
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentForAdmin(Long userId, int limit) {
        validateLimit(limit);
        PageRequest page = PageRequest.of(0, limit);
        Map<Long, AuditEvent> unique = new HashMap<>();
        repository.findByUserIdOrderByCreatedAtDesc(userId, page)
                .forEach(event -> unique.put(event.getId(), event));
        repository.findByTargetOrderByCreatedAtDesc("user:" + userId, page)
                .forEach(event -> unique.put(event.getId(), event));

        return unique.values().stream()
                .sorted(Comparator.comparing(AuditEvent::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AuditEvent::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    /** 当前用户近 N 天审计事件聚合报表（Java 内存聚合，兼容 H2/PostgreSQL）。 */
    @Transactional(readOnly = true)
    public AuditReport reportForUser(Long userId, int days) {
        int window = Math.min(Math.max(days, 1), 90);
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusDays(window - 1L).atStartOfDay();

        List<AuditEvent> events = repository.findByUserIdAndCreatedAtGreaterThanEqual(userId, since);

        Map<LocalDate, Long> perDay = new LinkedHashMap<>();
        for (int i = window - 1; i >= 0; i--) {
            perDay.put(today.minusDays(i), 0L);
        }
        Map<String, Long> perAction = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            LocalDate day = event.getCreatedAt().toLocalDate();
            perDay.merge(day, 1L, Long::sum);
            perAction.merge(event.getAction(), 1L, Long::sum);
        }

        List<AuditReport.DailyCount> daily = new ArrayList<>();
        long todayCount = 0;
        for (Map.Entry<LocalDate, Long> entry : perDay.entrySet()) {
            if (entry.getKey().equals(today)) todayCount = entry.getValue();
            daily.add(new AuditReport.DailyCount(entry.getKey().toString(), entry.getValue()));
        }

        List<AuditReport.ActionCount> actions = new ArrayList<>();
        perAction.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(e -> actions.add(new AuditReport.ActionCount(e.getKey(), e.getValue())));

        return new AuditReport(window, events.size(), todayCount, daily, actions);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1~200 之间");
        }
    }
}
