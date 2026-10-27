package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期血缘 run 的每日清理（{@code lineage.run-retention-days}，默认 90 天）。
 *
 * <p><b>为什么单独一个任务，不塞进优化批次。</b>优化批次是「表存储优化」的语义
 * （compaction / expire snapshots 那一套），与血缘没有任何关系；共用一个调度只会让
 * 「优化被关掉时血缘也不清理」这件事变成一条要读代码才发现的耦合（§7.5）。
 *
 * <p><b>只清 run，不清边。</b>边是「现在谁依赖谁」的状态，删它需要的是「表没了」这个
 * 事实（由 {@code LineageEdgeCleanupListener} 负责），而不是时间。反过来，
 * run 过期之后「最近一次是谁写的」仍在边表的 {@code lastSeen} 上——
 * 这个不对称是刻意的（§7.1）。
 *
 * <p>清理失败只记 WARN：一个删旧数据的任务失败，比它把服务搞挂要好得多；
 * 第二天会再跑一次，表只会多留一天。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lakehouse.rest.lineage", name = "enabled", havingValue = "true")
public class LineageRetentionJob {

    private final LineageService lineageService;
    private final SchedulerLease lease;
    private final RestServerProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${lakehouse.rest.lineage.cleanup-cron:0 30 3 * * *}")
    public void purgeExpiredRuns() {
        // 多副本下必须单写者：这个 DELETE 虽然是幂等的，但两台机器同时扫同一段范围
        // 会撞上 InnoDB 的 gap lock，出现死锁与回滚日志。这条脏活交给租约最省心，
        // 代价是「持有者挂掉后当天可能整轮空窗」——日级幂等任务，次日重跑等价。
        if (!lease.tryAcquire(SchedulerLease.CHANNEL_LINEAGE)) {
            log.debug("Skip lineage cleanup: another instance holds {}", SchedulerLease.CHANNEL_LINEAGE);
            return;
        }
        long now = clock.millis();
        try {
            int deleted = lineageService.purgeExpiredRuns(now);
            if (deleted > 0) {
                log.info("Purged {} lineage runs older than {} days",
                        deleted, properties.getLineage().getRunRetentionDays());
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to purge expired lineage runs, will retry next schedule: {}",
                    exception.getMessage(), exception);
        }
    }
}
