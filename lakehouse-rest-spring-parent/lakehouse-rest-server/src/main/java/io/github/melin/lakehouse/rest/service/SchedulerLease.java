package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.SchedulerLeaseEntity;
import io.github.melin.lakehouse.rest.domain.repo.SchedulerLeaseRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 调度通道租约：多副本部署下「这一拍该不该由我来跑」的唯一判据。
 *
 * <p><b>做成租约而不是「只让一个副本开调度」。</b>后者（{@code scheduler-enabled=false}
 * 的次副本）在滚动发布期间必然空窗：旧 Pod 停了、新 Pod 还没被点上。
 * 租约是「谁先到谁跑，到期自动换人」，滚动期间新 Pod 立刻能接手。
 *
 * <p><b>它只降噪，不保正确。</b>即使租约完全失效（时钟漂移、表被清空、进程假死），
 * 正确性仍由各处的条件更新兜住：出批的 {@code next_run_at} CAS、提交的
 * {@code claim_attempt}、终态的 {@code status} CAS。因此调用方的写法是
 * 「抢不到就早退」，而不是把业务逻辑放进 {@code if (leased)} 里当唯一判断。
 *
 * <p><b>三个通道而不是一个。</b>扫描是分钟级、提交是十秒级、血缘清理是日级，
 * 合成一个会让「扫描卡在某个慢 catalog 上」把提交也堵住。
 */
@Component
@RequiredArgsConstructor
public class SchedulerLease {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLease.class);

    /** 出批通道：{@code OptimizationDispatcher#scan}。 */
    public static final String CHANNEL_SCAN = "optimization.scan";
    /** 提交与回收通道：{@code OptimizationDispatcher#submitPending} / {@code reapStaleSubmissions}。 */
    public static final String CHANNEL_DISPATCH = "optimization.dispatch";
    /** 血缘清理通道：{@code LineageRetentionJob#purgeExpiredRuns}。 */
    public static final String CHANNEL_LINEAGE = "lineage.cleanup";

    /**
     * 通道清单。新增 {@code @Scheduled} 必须在这里登记——没登记就没人给它让路，
     * 多副本下每一台都会跑同一件事（{@code docs/optimization-multi-instance-design.md} §5.6）。
     */
    private static final List<String> CHANNELS = List.of(CHANNEL_SCAN, CHANNEL_DISPATCH, CHANNEL_LINEAGE);

    /** TTL 下限：节拍调得再快，租约也不该短于 30 秒——否则网络抖动就能造成双持有。 */
    private static final Duration MIN_TTL = Duration.ofSeconds(30);

    /**
     * 血缘清理的 TTL。
     *
     * <p>cron 是日级，执行本身是分钟级，因此这里不需要「3 × 节拍」那条规则；
     * 取 1 小时既覆盖了「一次清理比预期慢」，也不至于让强杀之后的接管等到第二天。
     */
    private static final Duration LINEAGE_TTL = Duration.ofHours(1);

    private final SchedulerLeaseRepository repository;
    private final RestServerProperties properties;
    private final Clock clock;

    /** 本实例标识：{@code hostname/pid}。同一个 Pod 内重启也会不同（pid 变了）。 */
    private final String instanceId = resolveInstanceId();

    /** 我当前持有的通道，停机时只让出这些——不碰别人的租约。 */
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /** 通道行是否已经补齐。没补齐时每个节拍都会再试一次（建表脚本可能还没来得及执行）。 */
    private volatile boolean rowsEnsured;

    /**
     * 启动时补齐三行，并打印通道清单。
     *
     * <p>为什么要「补齐」而不是写进 DDL 的种子数据：{@code sql/schema-mysql.sql}
     * 是从实体生成的产物，写进去会在下次生成时丢失。两个实例同时启动时其中一个会撞主键
     * （{@link DataIntegrityViolationException}），吞掉即可——行存在就是目的。
     *
     * <p><b>失败不阻塞启动。</b>表还不存在时（例如升级时「先滚镜像、后执行 DDL」）
     * 这里只记 ERROR，第一次节拍会再试一次：调度不该因为一张辅助表缺失而停摆，
     * 但也不该让整个服务端起不来。
     */
    @PostConstruct
    void ensureRows() {
        ensureRowsQuietly("startup");
    }

    private synchronized void ensureRowsQuietly(String when) {
        if (rowsEnsured) {
            return;
        }
        try {
            for (String channel : CHANNELS) {
                if (repository.existsById(channel)) {
                    continue;
                }
                SchedulerLeaseEntity row = new SchedulerLeaseEntity();
                row.setName(channel);
                row.setFencingToken(0L);
                row.setUpdatedAt(clock.millis());
                try {
                    repository.saveAndFlush(row);
                } catch (DataIntegrityViolationException race) {
                    // 另一个实例同时也建了这一行：结果相同，不必处理
                    log.debug("scheduler lease channel {} was created by another instance", channel);
                }
            }
            rowsEnsured = true;
            log.info("scheduler lease channels {} on instance {}", CHANNELS, instanceId);
        } catch (RuntimeException exception) {
            log.error("cannot prepare scheduler lease rows ({}), will retry on the next tick."
                    + " Make sure sql/schema-mysql.sql has been applied: {}", when, exception.getMessage());
        }
    }

    /**
     * 抢或续这一拍的资格。
     *
     * <p>顺序是「先抢后续」：抢覆盖「无主 / 别人过期 / 我自己过期」，续只覆盖
     * 「我持有且未过期」。只续不抢的话，我上一次崩溃留下的过期租约永远续不上。
     *
     * @return {@code true} 表示本拍由我跑；{@code false} 表示别的实例持有，调用方应整拍早退
     */
    public boolean tryAcquire(String channel) {
        if (!rowsEnsured) {
            ensureRowsQuietly("first tick");
        }
        long now = clock.millis();
        long expires = now + ttl(channel).toMillis();
        if (repository.acquire(channel, instanceId, now, expires) == 1) {
            held.add(channel);
            log.debug("acquired scheduler lease {} (until {})", channel, expires);
            return true;
        }
        if (repository.renew(channel, instanceId, now, expires) == 1) {
            return true;
        }
        return false;
    }

    /**
     * 提交租约的 TTL（批次级 {@code claim_expires_at}）。
     *
     * <p>与通道租约同一个推导规则「3 × 节拍且 ≥ 30 秒」：节拍 10 秒 → 30 秒。
     * 这个值决定「提交中的批次多久之后可以被别人接管」，不能配得比一次
     * {@code submit()} 的实际耗时还短（k8s API 慢的时候会误判），也不能配得接近
     * Job 的清理 TTL（那会让「重投必然命中同名 Job」这条安全前提失效）。
     */
    public Duration submissionLeaseTtl() {
        Duration interval = properties.getOptimization().getDispatchInterval().multipliedBy(3);
        return interval.compareTo(MIN_TTL) < 0 ? MIN_TTL : interval;
    }

    /** 本实例标识，落进批次表的 {@code claim_owner}，排查时能认出是哪台机器卡住的。 */
    public String instanceId() {
        return instanceId;
    }

    /** 所有登记过的通道名（只读，供启动日志与静态校验脚本对照）。 */
    public static List<String> channels() {
        return CHANNELS;
    }

    /** 停机让出一个通道。抢不到或已易主时什么也不做（{@code release} 的条件自带这条）。 */
    public void release(String channel) {
        int rows = repository.release(channel, instanceId, clock.millis());
        held.remove(channel);
        if (rows == 1) {
            log.info("released scheduler lease {}", channel);
        }
    }

    /**
     * 停机时让出全部租约，让滚动发布不空窗。
     *
     * <p><b>强杀时这里不会执行</b>（{@code kill -9} / OOMKilled / 节点宕机），
     * 接管就要等一个 TTL：dispatch 60 秒、scan 3 分钟、lineage 1 小时。
     * 那是时延，不是重复执行——正确性由 CAS 兜住。
     */
    @PreDestroy
    void releaseAll() {
        for (String channel : Set.copyOf(held)) {
            try {
                release(channel);
            } catch (RuntimeException exception) {
                // 停机路径里抛异常会被 Spring 记成「bean 销毁失败」，但它不该影响退出
                log.warn("cannot release scheduler lease {} on shutdown: {}", channel, exception.getMessage());
            }
        }
    }

    /**
     * 通道 TTL：{@code 3 ×} 节拍（下限 30 秒）。
     *
     * <p>按节拍派生而不是单独一个配置键：两个数必须同步，而「改了节拍忘了改 TTL」
     * 的表现是「每拍都换人」——不报错，只是白跑。派生之后没有漂移的余地。
     */
    private Duration ttl(String channel) {
        if (CHANNEL_LINEAGE.equals(channel)) {
            return LINEAGE_TTL;
        }
        Duration interval = CHANNEL_SCAN.equals(channel)
                ? properties.getOptimization().getScanInterval()
                : properties.getOptimization().getDispatchInterval();
        Duration threeTicks = interval.multipliedBy(3);
        return threeTicks.compareTo(MIN_TTL) < 0 ? MIN_TTL : threeTicks;
    }

    /**
     * 实例标识 = {@code HOSTNAME/pid}。
     *
     * <p>优先读 {@code HOSTNAME} 环境变量：k8s 会注入 Pod 名，而在容器里
     * {@code InetAddress.getLocalHost()} 有时要等 DNS 超时、有时拿到的是一串随机名。
     * 拼上 pid 是为了区分「同一个 Pod 内的重启」——重启后的实例不该继承前任的租约。
     */
    private static String resolveInstanceId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                // 取不到主机名不影响功能：pid 在同一台机器上仍然唯一
                host = "unknown-host";
            }
        }
        return host + "/" + ProcessHandle.current().pid();
    }
}
