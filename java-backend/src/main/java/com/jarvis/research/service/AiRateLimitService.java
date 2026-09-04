package com.jarvis.research.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 教学/个人规模的用户级 AI 限流。
 * 单实例下按用户限制 10 次/分钟、100 次/自然日。
 * 迁移到多实例后应替换为 Redis/Bucket4j 等分布式实现。
 */
@Service
public class AiRateLimitService {

    private static final int PER_MINUTE = 10;
    private static final int PER_DAY = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Map<Long, Usage> usageByUser = new HashMap<>();

    public synchronized void consume(Long userId) {
        long minute = System.currentTimeMillis() / 60_000L;
        LocalDate day = LocalDate.now(ZONE);
        Usage usage = usageByUser.computeIfAbsent(userId, ignored -> new Usage(minute, day));

        if (usage.minuteBucket != minute) {
            usage.minuteBucket = minute;
            usage.minuteCount = 0;
        }
        if (!usage.day.equals(day)) {
            usage.day = day;
            usage.dayCount = 0;
        }

        if (usage.minuteCount >= PER_MINUTE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI 请求过于频繁，请稍后再试（每分钟最多10次）");
        }
        if (usage.dayCount >= PER_DAY) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "今日 AI 调用额度已用完（每天最多100次）");
        }

        usage.minuteCount++;
        usage.dayCount++;
    }

    private static final class Usage {
        long minuteBucket;
        int minuteCount;
        LocalDate day;
        int dayCount;

        Usage(long minuteBucket, LocalDate day) {
            this.minuteBucket = minuteBucket;
            this.day = day;
        }
    }
}
