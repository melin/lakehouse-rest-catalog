package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityFindingEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivitySuppressionEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityFindingRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityScanRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivitySuppressionRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.Finding;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanAccepted;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanRequest;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanSummary;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.scan.ColumnRefs;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.ScanTokens;
import io.github.melin.lakehouse.rest.support.SchemaColumns;
import io.github.melin.lakehouse.rest.scan.ScanColumn;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanFinding;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanItemReport;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanJobSpec;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanTableSpec;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanType;
import io.github.melin.lakehouse.rest.support.SensitivityCatalogSettings;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.CandidateType;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.ColumnValues;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.Match;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.FindingState;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanExecution;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanMode;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanScope;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanStatus;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanTrigger;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.TagSource;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 敏感数据扫描的编排（设计见 {@code docs/sensitive-data-design.md} §5.6 / §6）。
 *
 * <p><b>P1 起两条路都能跑：{@code METADATA_ONLY}（D1 + D2）与 {@code SAMPLED}
 * （再加 D3 值层）。</b>前者只读 {@code lakehouse_table.schema_doc}——零 I/O、
 * 不碰凭据、不读明文样本，因此它也是「刚接入一个 catalog、第一次想看看有什么敏感数据」
 * 时唯一能跑的路径。后者要采样读明文，走的是<a>数据预览那条只读通道</a>
 * （{@link SensitivitySampler} → {@code TablePreviewService.sample}），
 * 与预览共用同一个并发池（§5.4 已拍板的口径）。
 * {@code execution=SPARK} **两条路都通**：元数据档下仍是 400（下推只读元数据的扫描没有
 * 收益），采样档下建一条 {@code PENDING} 就返回，由 {@code SensitivityScanDispatcher}
 * 的节拍提交成 k8s Job，作业逐表回调写回 findings（§5.6.5）。
 * 「我明明选了 SPARK」不该变成一条查不出来的行为（§5.6.5 的 fail-closed）。
 *
 * <p><b>扫描从 P1 起会产 tag，但只在 {@code AUTO_APPLIED} 时。</b>值层让置信度可以
 * 到 {@code 0.35 + 0.10 + 0.40 = 0.85}，于是强格式类型（身份证 / 手机号 / 银行卡 /
 * USCC / 邮箱 / IP / 凭据）在 {@code min_confidence=0.85} 与 {@code auto_apply=true}
 * 同时满足时自动落标；其余仍是人审候选。{@code PERSON_NAME} / {@code ADDRESS} /
 * {@code SALARY} / {@code MEDICAL_DIAGNOSIS} 四类的 {@code auto_apply} 恒 false，
 * 因此哪怕置信度 0.99 也只进 {@code PENDING}——这正是 §5.3 的「防误脱敏」。
 *
 * <p><b>人审否决过的结论不再产出。</b>开跑前把该 catalog 仍生效的抑制项取成集合，
 * 逐列命中时先查它（§7.2）。这一条与「自动应用白名单」是一对：白名单决定
 * 「什么样的结论可以自动落」，抑制项决定「什么样的结论连提都不要提」。
 *
 * <p><b>仍然同步执行，不引入执行池。</b>{@code METADATA_ONLY} 是纯 CPU（解析 JSON），
 * 一次 catalog 扫描以毫秒到秒计；{@code SAMPLED} 变成有 I/O 的路径，但它的并发
 * 由**两层**现成的闸门守住：本类的 catalog 级名额（{@code maxConcurrentScans}），
 * 以及读通道自己的 {@code MAX_CONCURRENT_PER_PRINCIPAL}（与预览共用，§5.4）。
 * 为此再引入本工程第一个生产线程池，换来的是「进程内并发闸门要自己实现」与
 * 「重启要清理孤儿行」两套机制——而这两件事今天都已经有了，不需要第二套。
 * 因此本类<b>同步跑完再返回</b>。代价写在明处：{@code POST /sensitivity-scans}
 * 的 202 与响应体里的 {@code status} 在 A 路线通常是终态；B 路线（{@code execution=SPARK}）
 * 下这个字段是 {@code PENDING}，客户端必须按下标轮询 {@code GET /sensitivity-scans/{id}}。
 *
 * <p><b>整个 {@link #start} 不加 {@code @Transactional}。</b>一次扫描会在库里写入
 * 成百上千条 finding，把它们圈进一个事务意味着请求线程长时间占着一条数据库连接，
 * 并且任何一条 finding 的失败会把整轮扫描的回滚——而这一轮的意图正是
 * 「扫完能扫的表、失败的单独计数」（§5.6.5 的 {@code tables_failed}）。
 * 每张表的 finding 用一次 {@code saveAll} 落库，边界与「一张表一个结果」一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityScanService {

    /**
     * 「同表闸门」认的在跑态。
     *
     * <p>含 {@code PENDING} / {@code SUBMITTING}（B 路线的两个前置态）：它们虽然还没开始读表，
     * 但已经占住了「这张表正在被扫」这个位置，放进来才是完整的闸门。
     */
    private static final List<String> ACTIVE_STATUSES = List.of(
            ScanStatus.PENDING.name(), ScanStatus.SUBMITTING.name(), ScanStatus.RUNNING.name());

    /** {@code error_message} 的列宽（实体上声明 2048），写入前截断。 */
    private static final int MAX_ERROR_LENGTH = 2048;

    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final SensitivityScanRepository scanRepository;
    private final SensitivityFindingRepository findingRepository;
    private final SensitivitySuppressionRepository suppressionRepository;
    private final SensitivitySampler sampler;
    private final SensitivityTagWriter tagWriter;
    private final SensitivityPropagationService propagation;
    private final SensitiveTypeService typeService;
    private final SensitivitySettingsService settingsService;
    private final ExecutionClusterService clusters;
    /**
     * B 路线的提交器：只有 {@link #cancel} 直接用它（停掉正在跑的 Job）。
     * 提交本身在 {@code SensitivityScanDispatcher} 里——那个节拍才是「谁去提交」的答案。
     */
    private final ScanJobSubmitter scanSubmitter;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;
    private final Clock clock;

    /**
     * 进程内并发闸门：catalog 名 → 正在跑的扫描数。
     *
     * <p>与 {@code TablePreviewService} 同形（同一份 {@code ConcurrentHashMap} + {@code merge}
     * 的手法）：闸门要挡的是「同一个 catalog 上同时几条扫描在读存储」，
     * 而调大执行集群的 {@code maxConcurrentJobs} **不该**顺带把这里放开——
     * 两者守的是两件事（§5.6.4 的警示框）。本字段与执行集群服务没有任何耦合，
     * 这正是那条断言「互不影响」的落点。
     */
    private final Map<String, Integer> inFlight = new ConcurrentHashMap<>();

    /**
     * 本进程的启动时刻，用于清理上一个进程留下的 {@code RUNNING} 扫描。
     *
     * <p>取「构造时刻」而不是「清理时刻」：清理时那些行的 {@code created_at}
     * 一定早于本进程启动（执行线程已随上一个进程消亡），因此这个分界是紧的；
     * 用清理时刻当分界会把「本进程刚启动、正在跑的扫描」也一起判成孤儿。
     */
    private final long processStartedAt = System.currentTimeMillis();

    // ------------------------------------------------------------------ 发起扫描

    /**
     * {@code POST /sensitivity-scans}：校验 → 落一条 {@code RUNNING} → 同步跑完 → 写终态。
     *
     * <p>顺序上有两处不能互换：**先过同表闸门再落库**（否则 409 时库里已经多了一条
     * 注定要失败的 {@code RUNNING} 行），以及**先占并发名额再落库**（否则超额的那一次
     * 会在库里留下一条立即失败的行，而它本不该存在）。
     */
    public ScanAccepted start(ScanRequest request) {
        return start(request, null);
    }

    /**
     * 带调度项名的发起（{@code trigger_type=SCHEDULE} 专用）。
     *
     * <p>{@code scheduleName} 走参数而不是加进 {@link ScanRequest}：请求体里放这个字段
     * 等于让任何调用方都能给自己的扫描贴一个「某某调度项触发的」标签，而
     * {@code schedule_name} 是「这条扫描的出处在哪」的答案——它可以被伪造的话，
     * 控制台上「这个调度项的历史扫描」就不再可信。
     */
    public ScanAccepted start(ScanRequest request, String scheduleName) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        String catalogName = trimToNull(request.catalog());
        if (catalogName == null) {
            throw ApiException.badRequest("catalog is required");
        }
        CatalogEntity catalog = settingsService.requireCatalog(catalogName);

        ScanScope scope = parseScope(request.scope());
        String database = trimToNull(request.database());
        String table = trimToNull(request.table());
        requireScopeFields(scope, database, table);

        ScanMode mode = parseMode(request.mode());
        ScanTrigger trigger = parseTrigger(request.triggerType());
        int sampleSize = resolveSampleSize(request.sampleSize());

        ScanExecution execution = resolveExecution(request.execution(), catalog, catalogName);
        boolean pushed = execution == ScanExecution.SPARK;
        int pushLimit = 0;
        if (pushed) {
            if (mode == ScanMode.METADATA_ONLY && !properties.getSensitivity().getScan().isAllowMetadataOnSpark()) {
                // §5.6.1：元数据档下推没有任何收益，反而多一次作业调度开销。这一条是 400
                // 而不是静默改走 A——「我明明选的 SPARK」不该变成一条查不出来的行为。
                throw ApiException.badRequest("execution=SPARK is not allowed for mode=METADATA_ONLY"
                        + " (pushing a metadata-only scan to Spark adds a job scheduling overhead"
                        + " with no benefit); use execution=IN_PROCESS or mode=SAMPLED");
            }
            // fail-closed：给一个没有可派发执行集群的 catalog 建 SPARK 扫描必须 400（§5.6.5），
            // 不静默退回 A。
            clusters.assertDispatchable(catalogName);
            pushLimit = clusters.maxScanTablesPerJob(catalog.getId());
        }

        // 同表闸门只对 TABLE 级生效：CATALOG / DATABASE 级扫描的 database_name / table_name
        // 是空的，用它们去做「同一张表」的相等判定会误伤（§5.6.4 的注）。
        if (scope == ScanScope.TABLE) {
            Optional<SensitivityScanEntity> running = scanRepository
                    .findFirstByCatalogNameAndDatabaseNameAndTableNameAndStatusInOrderByCreatedAtDesc(
                            catalogName, database, table, ACTIVE_STATUSES);
            if (running.isPresent()) {
                throw ApiException.conflict("a scan is already " + running.get().getStatus()
                        + " for " + catalogName + "." + database + "." + table
                        + " (scan " + running.get().getId() + "); two scans of the same table would"
                        + " produce two pending findings for the same conclusion");
            }
        }

        Map<String, String> databaseNames = databaseNames(catalog.getId());
        List<ResolvedTable> tables = resolveTables(catalog.getId(), scope, database, table, databaseNames);
        int maxTables = properties.getSensitivity().getScan().getMaxTablesPerScan();
        if (tables.size() > maxTables) {
            // 400 并给出实际表数，让调用方显式换成库级或加过滤（§5.6.4 的 maxTablesPerScan）。
            throw ApiException.badRequest("scope=" + scope + " resolves to " + tables.size()
                    + " tables, which exceeds lakehouse.rest.sensitivity.scan.max-tables-per-scan="
                    + maxTables + "; narrow the scope to a database or a table");
        }
        if (pushed && tables.size() > pushLimit) {
            // 下推路线**一个扫描 = 一个 Job**（§5.6.5 第 1 条），所以这里不能像优化批次那样
            // 自动切成多个 Job：切开之后「同一张表同时只允许一个扫描」这条互斥会被绕过。
            // 因此超了就 400，并告诉调用方两个可调的旋钮。
            throw ApiException.badRequest("scope=" + scope + " resolves to " + tables.size()
                    + " tables, which exceeds this catalog's maxScanTablesPerJob=" + pushLimit
                    + "; a pushed scan is a single Spark job (one scan = one job, so it cannot be"
                    + " split), narrow the scope or raise maxScanTablesPerJob in the execution cluster");
        }

        int limit = settingsService.resolvedMaxConcurrentScans(
                SensitivityCatalogSettings.read(catalog.getProperties()));
        // A 路线的并发闸门守的是「这台服务进程同时读几份明文」；B 路线不读明文、不占进程内
        // 名额，它的并发由执行集群那把 maxConcurrentJobs 管（§5.6.6 末段）。
        if (!pushed) {
            requireSlot(catalogName, limit);
        }
        try {
            long now = clock.millis();
            SensitivityScanEntity scan = new SensitivityScanEntity();
            scan.setId(Paging.newId());
            scan.setCatalogName(catalogName);
            scan.setDatabaseName(database);
            scan.setTableName(table);
            scan.setScope(scope.name());
            scan.setMode(mode.name());
            // 落**生效值**：请求没传 execution 时原值是 null，而「三个月后回看这次扫描跑在哪」
            // 必须能答上来（§4.2）
            scan.setExecution(execution.name());
            scan.setTriggerType(trigger.name());
            if (scheduleName != null) {
                // 与 trigger 一致性由调用方保证（只有节拍会传它），这里不做「自动改成 SCHEDULE」：
                // 一个 MANUAL 扫描带上调度项名，只会让人在历史里看到一个错的出处
                scan.setScheduleName(scheduleName);
            }
            scan.setSampleSize(sampleSize);
            // PENDING 只属于 B 路线：A 路线的执行者就是发起请求的这个线程，从落库那一刻起它
            // 已经在跑了，写 PENDING 等于谎报一个并不存在的等待（§5.6.5 的「状态机要补两态」）
            scan.setStatus(pushed ? ScanStatus.PENDING.name() : ScanStatus.RUNNING.name());
            // 代数从 0 起：抢占语句自己带 coalesce 兜着历史行，但新行没必要留 NULL
            scan.setClaimAttempt(0);
            scan.setTriggeredBy(RequestContext.auditPrincipal());
            // started_at 记「真正开始读数据」的时刻：B 路线要等调度节拍把它提交成 Job 之后
            // 才写（markSubmitted），否则「扫描耗时」会把排队时间也算进去
            if (!pushed) {
                scan.setStartedAt(now);
            }
            scan.markCreated(RequestContext.principal(), now);
            scanRepository.save(scan);

            publishStarted(scan, tables.size());
            if (pushed) {
                // 建单即返回 202：提交、跟踪、逐表回调都在别的节拍 / 别的进程里发生
                return new ScanAccepted(scan.getId(), catalogName, ScanStatus.PENDING.name(), execution.name());
            }
            RunResult result = runScan(scan, catalogName, tables);
            ScanStatus finalStatus = finish(scan, result, now);
            // 传播是**收尾的增值动作**：它不改这一轮的计数，也不该让一次已经成功的扫描变成失败。
            // 放在 finish 之后（终态已落、事件已发）而不是之前，是因为「扫完了」这件事
            // 不依赖传播是否成功；而传播需要的只是「哪些列刚有了结论」。
            propagate(scan, tables);
            scan.setStatus(finalStatus.name());
            return new ScanAccepted(scan.getId(), catalogName, finalStatus.name(), execution.name());
        } finally {
            if (!pushed) {
                releaseSlot(catalogName);
            }
        }
    }

    // ------------------------------------------------------------------ B 路线（下推 Spark）

    /** 执行清单的单页上限。作业按页拉，服务端不能被一个 `limit=1000000` 拖住。 */
    private static final int SUBMISSION_PAGE_MAX = 200;

    /** 执行清单的默认页大小：200 张表 × 每张几十列，一页几十 KB。 */
    private static final int SUBMISSION_PAGE_DEFAULT = 50;

    /**
     * 作业回调时写进审计列的主体名。
     *
     * <p>回调端点不参与 RBAC（调用方是 k8s 里的作业，它没有主体身份），因此
     * {@code RequestContext.principal()} 会是 {@code anonymous}。而作业回调会写 finding 与
     * 自动标签的 {@code created_by}——「谁写的这条结论」的答案是「匿名」时，审计就断了。
     * 记成 {@code system:sensitivity-scanner} 才是事实：这是作业写的，不是某个人。
     * 与 {@code SensitivityScheduleDispatcher#SYSTEM_PRINCIPAL} 同一个口径。
     */
    public static final String SCANNER_PRINCIPAL = "system:sensitivity-scanner";

    /**
     * 校验回调令牌（{@code X-Sensitivity-Scan-Token}）。
     *
     * <p><b>库里只有哈希</b>（{@code callback_token_hash}，§4.2），因此这里算一次 SHA-256
     * 再比。用 {@link MessageDigest#isEqual} 而不是 {@code equals}：后者在第一个不同的字节
     * 就返回，把「比到第几位」泄漏成一个可测量的时间差——令牌是这次扫描唯一的凭据，
     * 不差这几行。
     *
     * <p>没有哈希（A 路线、或还没提交的 PENDING）时**直接拒**：那种扫描根本没有作业在跑，
     * 任何携带令牌的调用都不是它的作业发出来的。
     */
    public void requireCallbackToken(String scanId, String token) {
        SensitivityScanEntity scan = require(scanId);
        if (token == null || token.isBlank()) {
            throw ApiException.forbidden("missing sensitivity scan token");
        }
        String expected = scan.getCallbackTokenHash();
        if (expected == null || expected.isBlank()) {
            throw ApiException.forbidden("scan " + scanId + " has no callback token"
                    + " (execution=" + scan.getExecution() + ", status=" + scan.getStatus() + ")");
        }
        if (!ScanTokens.matches(expected, token)) {
            throw ApiException.forbidden("invalid sensitivity scan token");
        }
    }

    /**
     * {@code GET /sensitivity-scans/{id}/submission}：作业拉取执行清单（分页）。
     *
     * <p><b>为什么分页。</b>一次 catalog 级扫描可能是几百张表 × 每张几十列，一整份响应
     * 在几千列的 catalog 上会到几十 MB；作业侧也只需要「当前这一页」——它逐表跑，
     * 跑完一页再拉下一页，内存里永远只有一页的列清单。
     *
     * <p>{@code hasMore} 由服务端算而不是让作业拿 {@code total} 自己比：表清单在一次扫描
     * 期间可能变（有人建了张新表），作业按「还有没有下一页」推进比按「拿到 total 条为止」
     * 更不容易漏扫或空转。
     */
    public ScanJobSpec submission(String scanId, Integer offset, Integer limit) {
        SensitivityScanEntity scan = require(scanId);
        if (!ScanExecution.SPARK.name().equals(scan.getExecution())) {
            throw ApiException.badRequest("scan " + scanId + " is execution=" + scan.getExecution()
                    + "; the submission listing only exists for execution=SPARK");
        }
        CatalogEntity catalog = settingsService.requireCatalog(scan.getCatalogName());
        Map<String, String> databaseNames = databaseNames(catalog.getId());
        List<ResolvedTable> tables = resolveTables(catalog.getId(), parseScope(scan.getScope()),
                scan.getDatabaseName(), scan.getTableName(), databaseNames);

        int total = tables.size();
        int from = offset == null || offset < 0 ? 0 : Math.min(offset, total);
        int size = limit == null || limit <= 0
                ? SUBMISSION_PAGE_DEFAULT : Math.min(limit, SUBMISSION_PAGE_MAX);
        int to = Math.min(from + size, total);
        List<ScanTableSpec> page = new ArrayList<>(Math.max(0, to - from));
        for (ResolvedTable table : tables.subList(from, to)) {
            page.add(new ScanTableSpec(table.database(), table.table(), SchemaColumns.parse(table.schemaDoc())));
        }
        // catalog_format 一并下发：作业没有别的途径知道该用哪种 catalog 实现建会话，
        // 而猜错的症状不是报错，是「每张表都说不存在」——见 ScanJobSpec#format
        return new ScanJobSpec(scan.getId(), scan.getCatalogName(), catalog.getCatalogFormat(),
                scan.getMode(), scan.getSampleSize(),
                from, size, total, to < total, scanTypes(), page);
    }

    /**
     * 作业逐表回报（{@code POST /sensitivity-scans/{id}/items}）。
     *
     * <p><b>「扫了但没命中」也要回。</b>调用方（作业）对每张表都要回一条，哪怕
     * {@code findings} 为空——否则「扫完没发现」与「压根没跑」在库里长得一模一样，
     * 而覆盖率正是靠这个区分才成立（§5.6.5 第 2 条）。因此这里**不因为空清单而早退**：
     * 计数照加，只是没有 finding 要落。
     *
     * <p><b>重复回调是安全的。</b>{@link #existingKeys} 挡住同一次扫描里同列同型已经落过的
     * 组合（作业重试、多副本竞争），抑制项则挡住人审否决过的结论。计数不满足这条：
     * 一次重复回调会把 {@code tables_scanned} 多加 1——那是**如实的**，它确实又被上报了一次，
     * 而「上报次数」与「扫过的表数」在库里是同一个字段。要区分就得加一列，值不值得由
     * 「作业会不会重复上报」决定，而作业侧已由 {@code backoffLimit=0} 与逐表 try/catch
     * 保证「一张表只回一次」。
     */
    public void reportItem(String scanId, ScanItemReport report) {
        SensitivityScanEntity scan = require(scanId);
        if (!ScanExecution.SPARK.name().equals(scan.getExecution())) {
            throw ApiException.badRequest("scan " + scanId + " is execution=" + scan.getExecution()
                    + "; item reports only exist for execution=SPARK");
        }
        if (report == null) {
            throw ApiException.badRequest("item report must not be empty");
        }
        String database = trimToNull(report.database());
        String table = trimToNull(report.table());
        if (database == null || table == null) {
            throw ApiException.badRequest("database and table are required in an item report");
        }
        String catalogName = scan.getCatalogName();
        List<ScanFinding> findings = report.findings() == null ? List.of() : report.findings();
        // 成功判据用协议里的常量，不写字面量：这一处与作业侧的
        // ScanItemReport.STATUS_SCANNED 是同一个值，拼错不会报错，只会把「扫过」记成「失败」
        boolean failed = report.status() == null
                || !ScanItemReport.STATUS_SCANNED.equalsIgnoreCase(report.status().trim());

        long now = clock.millis();
        // 回调没有主体身份（端点在 RBAC 之外，见 controller 类注释）。不设它的话，
        // 这次回调写下的 finding 与自动标签的 created_by 会是 anonymous——审计断在这里
        RequestContext.setPrincipal(SCANNER_PRINCIPAL);
        try {
            int saved = 0;
            if (!findings.isEmpty()) {
                Set<String> suppressed = suppressedKeys(catalogName);
                Set<String> written = existingKeys(scanId);
                List<SensitivityFindingEntity> batch = new ArrayList<>(findings.size());
                Set<String> columns = new HashSet<>();
                for (ScanFinding item : findings) {
                    String column = trimToNull(item.column());
                    if (column == null || trimToNull(item.typeCode()) == null) {
                        continue;
                    }
                    columns.add(column);
                    String hash = ColumnRefs.hash(catalogName, database, table, column);
                    String key = item.typeCode() + "|" + hash;
                    if (suppressed.contains(key) || !written.add(key)) {
                        continue;
                    }
                    batch.add(remoteFinding(scan, database, table, column, hash, item, now));
                }
                if (!batch.isEmpty()) {
                    saved = findingRepository.saveAll(batch).size();
                    applyAutoTags(batch, catalogName, database, table);
                }
                // 列数取「上报值」与「实际见到的列数」的较大者：作业可能只报了命中列，
                // 而 columns_scanned 的语义是「扫过多少列」——它得是个可信的分母
                int columnsScanned = Math.max(report.columnsScanned(), columns.size());
                scanRepository.addItemCounts(scanId, 1, columnsScanned, saved, failed ? 1 : 0, now);
            } else {
                scanRepository.addItemCounts(scanId, 1, Math.max(0, report.columnsScanned()), 0,
                        failed ? 1 : 0, now);
            }
        } finally {
            // 必须清：Tomcat 的请求线程是复用的，留着会让下一个请求继承「扫描作业」这个身份
            RequestContext.clear();
        }
        if (failed) {
            log.info("sensitivity scan {} reported table {}.{}.{} as failed: {}", scanId,
                    catalogName, database, table, report.errorMessage());
        }
    }

    /**
     * {@code POST /sensitivity-scans/{id}:cancel}：取消扫描，B 路线同时停作业。
     *
     * <p><b>先停作业再改状态。</b>反过来的话，作业在「状态已改成 CANCELLED、Job 还没删」的
     * 窗口里继续回调，那些回调会因为状态不是 RUNNING 而全部落空（{@code addItemCounts} 的
     * 条件更新返回 0 行）——不算错，但会留下一个「在跑的作业往一个已取消的扫描上报」的
     * 错觉。先删 Job，那条路就断了。
     *
     * <p><b>不发事件。</b>事件类型表里没有 {@code sensitivity.scan.cancelled}，而新增一个
     * 类型要同步「10 类 N 型」的五处计数与校验脚本。取消本身在审计列（{@code updated_by}）
     * 与 {@code error_message} 里已经可追溯，为它扩一套事件枚举不划算——**这是有意的缺口，
     * 不是漏写**。
     */
    public ScanSummary cancel(String scanId) {
        SensitivityScanEntity scan = require(scanId);
        List<String> active = List.of(ScanStatus.PENDING.name(), ScanStatus.SUBMITTING.name(),
                ScanStatus.RUNNING.name());
        if (!active.contains(scan.getStatus())) {
            throw ApiException.conflict("scan " + scanId + " is already " + scan.getStatus()
                    + "; only PENDING / SUBMITTING / RUNNING can be cancelled");
        }
        long now = clock.millis();
        String principal = RequestContext.auditPrincipal();
        if (ScanExecution.SPARK.name().equals(scan.getExecution()) && scan.getExecutionResource() != null) {
            String catalogId = settingsService.requireCatalog(scan.getCatalogName()).getId();
            try {
                scanSubmitter.cancel(catalogId, scan.getExecutionNamespace(), scan.getExecutionResource());
            } catch (RuntimeException exception) {
                // 停不掉作业不该挡取消：扫描状态是**用户能看到的那个真相**，而作业停不掉
                // 时它跑完会以 FAILED/SUCCEEDED 的回调落空（状态已 CANCELLED），
                // 资源由 TTL 清理
                log.warn("scan {} cancelled but its job {}/{} could not be deleted: {}", scanId,
                        scan.getExecutionNamespace(), scan.getExecutionResource(), exception.getMessage());
            }
        }
        int updated = scanRepository.cancel(scanId, "cancelled by " + principal, principal, active, now);
        if (updated == 0) {
            throw ApiException.conflict("scan " + scanId + " was finished concurrently; nothing to cancel");
        }
        log.info("sensitivity scan {} cancelled by {}", scanId, principal);
        return toDto(require(scanId));
    }

    /**
     * B 路线收尾时的血缘传播。
     *
     * <p>与 A 路线同一个动作、同一个理由（传播不改计数、不该让已成功的扫描变失败），
     * 只是入口不同：A 路线在 {@code start} 里手上有 {@code tables}，B 路线收尾发生在
     * {@code SensitivityScanDispatcher} 里，那时只有库里的那一行。因此这里按范围重新
     * resolve 一次——只查 {@code lakehouse_database} / {@code lakehouse_table}，
     * 不读任何存储。
     */
    public void propagateAfterPush(SensitivityScanEntity scan) {
        try {
            CatalogEntity catalog = settingsService.requireCatalog(scan.getCatalogName());
            List<ResolvedTable> tables = resolveTables(catalog.getId(), parseScope(scan.getScope()),
                    scan.getDatabaseName(), scan.getTableName(), databaseNames(catalog.getId()));
            propagate(scan, tables);
        } catch (RuntimeException exception) {
            log.warn("pushed scan {} finished but propagation failed: {}",
                    scan.getId(), exception.getMessage(), exception);
        }
    }

    /** 候选类型 → 作业侧的线格式（{@code detectors} 用结构化对象，不传 JSON 文本）。 */
    private List<ScanType> scanTypes() {
        List<CandidateType> candidates = typeService.candidates();
        List<ScanType> types = new ArrayList<>(candidates.size());
        for (CandidateType candidate : candidates) {
            types.add(new ScanType(candidate.typeCode(), candidate.level(), candidate.minConfidence(),
                    candidate.autoApply(), candidate.detectors().wireLayers()));
        }
        return types;
    }

    /**
     * 把作业报回来的一条命中转成实体。
     *
     * <p>与 A 路线的 {@link #finding} 分开，是因为**样本来源不同**：A 路线的样本在服务端
     * 由 {@code SensitivitySampler} 打码，B 路线的样本由作业打码后随回调送上来
     * （§5.6.5 的「明文不出 Pod」）。这里只做形状校验，不碰原值——它本来就不在。
     */
    private SensitivityFindingEntity remoteFinding(SensitivityScanEntity scan, String database, String table,
                                                   String column, String referenceHash, ScanFinding item,
                                                   long now) {
        SensitivityFindingEntity entity = new SensitivityFindingEntity();
        entity.setScanId(scan.getId());
        entity.setCatalogName(scan.getCatalogName());
        entity.setDatabaseName(database);
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setColumnRefHash(referenceHash);
        entity.setTypeCode(item.typeCode());
        entity.setConfidence(item.confidence());
        entity.setMatchedDetectorsJson(Json.write(item.scores() == null ? Map.of() : item.scores()));
        entity.setState(normalizeState(item.state()));
        List<String> masked = item.maskedSamples() == null ? List.of() : item.maskedSamples();
        if (!masked.isEmpty()) {
            entity.setMaskedSamplesJson(Json.write(masked));
            entity.setSamplingMode(item.samplingMode());
        }
        entity.markCreated(RequestContext.principal(), now);
        return entity;
    }

    /**
     * 作业报回来的 {@code state} 只认 {@code PENDING} / {@code AUTO_APPLIED}，
     * 其余（空、拼错、{@code CONFIRMED} / {@code REJECTED}）一律回落 {@code PENDING}。
     *
     * <p><b>不能直接 {@code valueOf}。</b>作业侧是一个独立编译单元，它写错一个字符串的
     * 后果不该是整次回调 500——那时作业只会看到「上报失败」而表已经跑过了，
     * 而回退成 PENDING 的坏处只是「本条结论多一次人审」。
     */
    private static String normalizeState(String state) {
        String value = trimToNull(state);
        if (FindingState.AUTO_APPLIED.name().equals(value)) {
            return FindingState.AUTO_APPLIED.name();
        }
        return FindingState.PENDING.name();
    }

    /** 回调令牌的 SHA-256 已在 {@link ScanTokens}：生成方与校验方必须是同一份实现。 */

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /sensitivity-scans}：按 catalog / 状态 / 范围 / 触发方式 / 调度项过滤后分页。
     *
     * <p>先过滤再切页（与全部既有列表端点同一口径）：候选集是「几十到几百」条，
     * 在内存里过滤比把四个条件拼成一条动态 JPQL 更不容易出错，也不会出现
     * 「按状态筛出来的页码和没筛的对不上」。
     *
     * <p>{@code scheduleName} 是 P4 加的第五个过滤轴：调度项控制台要回答「这条规则跑过
     * 哪几次」，而按 {@code triggerType=SCHEDULE} 筛出来的是**所有**调度项的历史。
     * 名字是字符串而不是外键（§4.6），因此删掉调度项之后这里仍能查到旧历史——
     * 这正是「删除不影响审计」那条口径的读侧。
     */
    public ScanList list(String catalog, String status, String scope, String triggerType,
                         String scheduleName, Integer page, Integer pageSize) {
        String catalogName = trimToNull(catalog);
        List<SensitivityScanEntity> all = catalogName == null
                ? scanRepository.findAllByOrderByCreatedAtDesc()
                : scanRepository.findAllByCatalogNameOrderByCreatedAtDesc(catalogName);
        String statusFilter = trimToNull(status);
        String scopeFilter = trimToNull(scope);
        String triggerFilter = trimToNull(triggerType);
        String scheduleFilter = trimToNull(scheduleName);
        List<ScanSummary> items = new ArrayList<>();
        for (SensitivityScanEntity entity : all) {
            if (statusFilter != null && !statusFilter.equals(entity.getStatus())) {
                continue;
            }
            if (scopeFilter != null && !scopeFilter.equals(entity.getScope())) {
                continue;
            }
            if (triggerFilter != null && !triggerFilter.equals(entity.getTriggerType())) {
                continue;
            }
            if (scheduleFilter != null && !scheduleFilter.equals(entity.getScheduleName())) {
                continue;
            }
            items.add(toDto(entity));
        }
        Paging.Paged<ScanSummary> paged = Paging.paged(items, page, pageSize);
        return paged == null
                ? new ScanList(items, null)
                : new ScanList(paged.items(), paged.meta());
    }

    /** {@code GET /sensitivity-scans/{id}}：单次扫描详情；不存在抛 404。 */
    public ScanSummary get(String scanId) {
        return toDto(require(scanId));
    }

    /** 按 id 取实体；不存在抛 404（资源类型是 {@code SENSITIVITY_SCAN}）。 */
    public SensitivityScanEntity require(String scanId) {
        String id = trimToNull(scanId);
        if (id == null) {
            throw ApiException.badRequest("scanId must not be empty");
        }
        return scanRepository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.SENSITIVITY_SCAN, id));
    }

    /**
     * {@code GET /sensitivity-findings}：命中清单（读侧；写侧是人审的
     * {@code decide}，P1 起在 {@link SensitivityDecisionService}）。
     *
     * <p><b>它挂在本服务上而不是另起一个服务。</b>P0 的 finding 只有读路径，
     * 唯一的查询轴是「哪个 catalog / 哪次扫描」，而 `findingRepository` 已经在这里
     * （写 finding 就是本类的职责）。P1 的 {@code decide}（批量确认/拒绝）会需要
     * 事务性写入与抑制项，那部分落在独立服务里——**读与写分开，而不是把两件事
     * 塞进同一个类**。
     *
     * <p><b>{@code catalog} 必填。</b>finding 的量级是「每轮扫描每命中列一行」，
     * 而扫描是每天重跑的——全库查询在两个维度上都会长（时间 × 列数）。
     * 更要紧的是可见性：没有 catalog 这个参数，这个端点就成了「一次读到所有
     * catalog 的识别结论」，而识别结论（类型 + 置信度）本身是敏感信息（§10）。
     *
     * <p><b>为什么它按 catalog 全量取而不是按扫描。</b>{@code #overview} 要算
     * 「待确认」与「自动应用率」两个比例，分母是「这个 catalog 目前判过什么」，
     * 而不是「最近这一次扫描判过什么」。按扫描逐次取会变成「N 次扫描 N 个请求」。
     * 代价是同一个结论会在多轮扫描里各留一行——这是 finding 表的本性（它是历史），
     * 而「现在这列是什么」问的是 {@code /column-tags}。
     */
    public FindingList findings(String catalog, String state, String table, String typeCode,
                                Integer page, Integer pageSize) {
        String catalogName = trimToNull(catalog);
        if (catalogName == null) {
            throw ApiException.badRequest("catalog is required");
        }
        String stateFilter = trimToNull(state);
        if (stateFilter != null && FindingState.parse(stateFilter).isEmpty()) {
            // 给了但认不出 → 400。静默忽略会让「按 PENDING 筛」回一份全部，
            // 而调用方以为自己在看待审队列。
            throw ApiException.badRequest("Unknown state \"" + state
                    + "\"; supported: PENDING, AUTO_APPLIED, CONFIRMED, REJECTED");
        }
        String tableFilter = trimToNull(table);
        String typeFilter = trimToNull(typeCode);
        List<Finding> items = new ArrayList<>();
        for (SensitivityFindingEntity entity
                : findingRepository.findByCatalogNameOrderByConfidenceDesc(catalogName)) {
            if (stateFilter != null && !stateFilter.equals(entity.getState())) {
                continue;
            }
            if (typeFilter != null && !typeFilter.equals(entity.getTypeCode())) {
                continue;
            }
            if (tableFilter != null
                    && !tableFilter.equals(entity.getDatabaseName() + "." + entity.getTableName())) {
                continue;
            }
            items.add(toDto(entity));
        }
        Paging.Paged<Finding> paged = Paging.paged(items, page, pageSize);
        return paged == null
                ? new FindingList(items, null)
                : new FindingList(paged.items(), paged.meta());
    }

    // ------------------------------------------------------------------ 启动清理

    /**
     * 收尾上一个进程留下的 {@code RUNNING} 扫描。
     *
     * <p>同步执行下也可能留下它：请求线程在「落库之后、写终态之前」被杀（进程崩溃、
     * pod 被驱逐）时，库里那行永远是 {@code RUNNING}。不清掉的话有两个后果——
     * 「这个 catalog 正在扫」的提示一直亮着，以及**同表闸门永久挡住重扫**
     * （用户会发现「这张表再也扫不了了」，而唯一线索是一行状态）。
     *
     * <p>挂在 {@code ApplicationReadyEvent} 而不是构造期：清理要写库，而本类被任何
     * 依赖它的 bean 触发构造时，数据源可能还没就绪。就绪事件保证这一步跑在
     * 「服务已经能处理请求」之前。
     *
     * <p><b>这里刻意不加 {@code @Transactional}</b>：事务落在仓储那条语句上
     * （{@code SensitivityScanRepository#failOrphanedRunning} 自带）。加在方法上会造出
     * 更坏的结果——本方法有意吞掉异常（表还没建出来时不该挡住启动），而事务里被吞掉的
     * 异常已经把事务标成 rollback-only，提交时抛 {@code UnexpectedRollbackException}，
     * 于是「表缺失」从一个可容忍的启动日志变成整个上下文加载失败。
     * 语句级事务让失败只是那一条语句回滚，catch 拿到的仍是原始错误信息。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanOrphanedRuns() {
        try {
            int cleaned = scanRepository.failOrphanedRunning(
                    "scan was left RUNNING by a previous process and never finished"
                            + " (the executing thread died with that process)",
                    processStartedAt, clock.millis());
            if (cleaned > 0) {
                log.warn("marked {} orphaned RUNNING sensitivity scan(s) as FAILED", cleaned);
            }
        } catch (RuntimeException exception) {
            // 清理失败不能挡住启动：最坏的结果是那几张表暂时扫不了（同表闸门），
            // 而为此让整个服务起不来是更坏的结果。
            log.error("failed to clean orphaned sensitivity scans: {}", exception.getMessage(), exception);
        }
    }

    // ------------------------------------------------------------------ 扫描本体

    /**
     * 跑一遍 D1 + D2（纯元数据）。
     *
     * <p>逐表 {@code try/catch}：一张表的坏数据（超长、非法编码的 {@code schema_doc}）
     * 不该让整轮扫描失败——那正是 {@code tables_failed} 要表达的东西。
     * {@code SchemaColumns.parse} 已经把「认不出的形状」折成空列表（不抛），
     * 所以这里的 catch 兜的是更外层的东西（字符串解码、内存）。
     */
    private RunResult runScan(SensitivityScanEntity scan, String catalogName,
                              List<ResolvedTable> tables) {
        List<CandidateType> types = typeService.candidates();
        if (types.isEmpty()) {
            // 不是错误，但必须说出来：候选类型为空时整轮扫描恒零命中，
            // 而现象与「这张表真的没有敏感数据」一模一样。
            log.warn("sensitivity scan {} has no candidate type; every catalog will scan to zero"
                    + " findings (check lakehouse.rest.sensitivity.seed-builtin-types"
                    + " and whether all types are disabled)", scan.getId());
        }
        Set<String> written = existingKeys(scan.getId());
        // 抑制项在开跑前一次取全：逐列回查会在 2000 列 × 11 类型上变成两万次查询
        Set<String> suppressed = suppressedKeys(catalogName);
        boolean sampled = ScanMode.SAMPLED.name().equals(scan.getMode());
        int tablesScanned = 0;
        int tablesFailed = 0;
        int columnsScanned = 0;
        int findingsCreated = 0;
        int suppressedSkipped = 0;

        for (ResolvedTable candidate : tables) {
            tablesScanned++;
            List<ScanColumn> columns = SchemaColumns.parse(candidate.schemaDoc());
            if (columns.isEmpty()) {
                // §6.1 的兜底：Lance 表根本不写 schema_doc，register 进来的外部表也可能还没刷新
                // 元数据。这类表**跳过、不猜**，并单独计数——把它算成「已扫描、无敏感列」
                // 会让覆盖率说「这张表已经看过了」，而其实一眼都没看过。
                tablesFailed++;
                log.info("sensitivity scan {} skipped {}.{}.{}: no parseable schema_doc",
                        scan.getId(), catalogName, candidate.database(), candidate.table());
                continue;
            }
            SensitivitySampler.TableSample sample = SensitivitySampler.TableSample.none();
            if (sampled) {
                List<String> names = new ArrayList<>(columns.size());
                for (ScanColumn column : columns) {
                    names.add(column.name());
                }
                try {
                    sample = sampler.sample(catalogName, candidate.database(), candidate.table(),
                            names, scan.getSampleSize());
                } catch (RuntimeException exception) {
                    // 采样读失败 = 这张表这一轮**没看过**：计入 tables_failed，不是「无敏感列」。
                    // 把漏扫说成干净，是这类系统最坏的一种错（§13「假覆盖」）。
                    tablesFailed++;
                    log.warn("sensitivity scan {} failed to sample {}.{}.{}: {}", scan.getId(),
                            catalogName, candidate.database(), candidate.table(),
                            exception.getMessage(), exception);
                    continue;
                }
            }
            try {
                List<SensitivityFindingEntity> batch = new ArrayList<>();
                long now = clock.millis();
                for (ScanColumn column : columns) {
                    columnsScanned++;
                    String referenceHash = ColumnRefs.hash(catalogName, candidate.database(),
                            candidate.table(), column.name());
                    ColumnValues values = sample.values(column.name());
                    for (Match match : SensitivityDetectors.detect(catalogName, candidate.table(),
                            column, types, values)) {
                        String key = match.typeCode() + "|" + referenceHash;
                        if (suppressed.contains(key)) {
                            // 人审否决过的结论不再产出（§7.2）：否则「拒绝 → 次日复活 →
                            // 再拒一次」会变成每天的例行劳动，而人手是有限的资源。
                            // 抑制项只在列改名（哈希变了）或治理员显式解禁后失效。
                            suppressedSkipped++;
                            continue;
                        }
                        if (!written.add(key)) {
                            // 同一次扫描里同列同型已经有了（P4 的逐表回调会重放）。跳过而不是
                            // 靠唯一键抛异常：几十次约束冲突会把事务标记成 rollback-only，
                            // 「继续处理下一列」的意图就实现不了（见仓储注释）。
                            continue;
                        }
                        batch.add(finding(scan, candidate, column, referenceHash, match, sample, now));
                    }
                }
                if (!batch.isEmpty()) {
                    findingsCreated += findingRepository.saveAll(batch).size();
                    // 自动应用闸门（§5.3）：AUTO_APPLIED 是打分器**已经**判过的结论
                    // （置信度 ≥ min_confidence 且该类型允许自动），这里只负责落标。
                    applyAutoTags(batch, catalogName, candidate, sample);
                }
            } catch (RuntimeException exception) {
                tablesFailed++;
                log.warn("sensitivity scan {} failed on {}.{}: {}", scan.getId(), catalogName,
                        candidate.table(), exception.getMessage(), exception);
            }
        }
        if (suppressedSkipped > 0) {
            log.info("sensitivity scan {} skipped {} finding(s) suppressed by a human rejection",
                    scan.getId(), suppressedSkipped);
        }
        return new RunResult(tablesScanned, columnsScanned, findingsCreated, tablesFailed);
    }

    /**
     * 把本批次里 {@code AUTO_APPLIED} 的命中落成标签。
     *
     * <p><b>为什么在 {@code saveAll} 之后做。</b>标签的 {@code finding_id} 要指向真实的
     * 那一行，而 id 是自增的——先落 finding 再落标签，标签才有溯源。反过来会得到
     * 一条「来源未知」的自动标签，而自动标签恰恰是最需要能被追问「凭什么自动」的那种。
     *
     * <p><b>{@code source=AUTO}。</b>人工确认的走 {@code SensitivityDecisionService}，
     * 那里传 {@code MANUAL}；两条路径写同一张表但标不同的来源，{@code TagWriter}
     * 会保证人工结论不被这里的自动结论覆盖。
     */
    private void applyAutoTags(List<SensitivityFindingEntity> batch, String catalogName,
                               ResolvedTable candidate, SensitivitySampler.TableSample sample) {
        boolean applied = false;
        for (SensitivityFindingEntity entity : batch) {
            if (!FindingState.AUTO_APPLIED.name().equals(entity.getState())) {
                continue;
            }
            applied |= tagWriter.apply(catalogName, candidate.database(), candidate.table(),
                    entity.getColumnName(), entity.getTypeCode(), entity.getConfidence(),
                    entity.getId(), TagSource.AUTO, RequestContext.principal()).isPresent();
        }
        if (applied) {
            // 表级级别 = 该表所有列标签的 max(level)（§5.5）。它不参与策略展开，
            // 所以这一步不会改变任何列的脱敏行为。
            tagWriter.refreshTableLevel(catalogName, candidate.database(), candidate.table(),
                    RequestContext.principal());
        }
    }

    /**
     * B 路线的自动落标：作业回调里只带库名与表名，没有 {@code ResolvedTable}。
     *
     * <p>与上面那条同一条路径（同一个 {@code tagWriter}、同一个「表级级别重算」），
     * 只是不需要 schema —— 落标要的是列名与类型码，那些已经在 finding 上。
     * {@code sample} 不传：那条路径里它本来就是空位（A 路线传进来是为了语义完整，
     * 方法体从未读过它），这里补齐一个重载比让调用方去造一份假样本干净。
     */
    private void applyAutoTags(List<SensitivityFindingEntity> batch, String catalogName,
                               String database, String table) {
        applyAutoTags(batch, catalogName, new ResolvedTable(database, table, null), null);
    }

    /**
     * 这个 catalog 里仍生效的抑制项，键的形状与 finding 的去重键一致
     * （{@code typeCode|columnRefHash}）。
     */
    private Set<String> suppressedKeys(String catalogName) {
        Set<String> keys = new HashSet<>();
        for (SensitivitySuppressionEntity entity
                : suppressionRepository.findByCatalogNameAndEnabledFlagTrueOrderByDecidedAtDesc(
                        catalogName)) {
            keys.add(entity.getTypeCode() + "|" + entity.getColumnRefHash());
        }
        return keys;
    }

    /**
     * 落一条 finding。
     *
     * <p><b>{@code maskedSamplesJson} 只写打码后的样本</b>（§5.4 的唯一不变量）：
     * 采样读到的原值在 {@link SensitivitySampler} 里已经转成了
     * {@code maskedSamples}，这里拿到的只有那一份。未采样时保持 {@code null}
     * ——「没采」与「采到空」是两件事，后者是一条真实的证据。
     */
    private SensitivityFindingEntity finding(SensitivityScanEntity scan, ResolvedTable table,
                                             ScanColumn column, String referenceHash, Match match,
                                             SensitivitySampler.TableSample sample, long now) {
        SensitivityFindingEntity entity = new SensitivityFindingEntity();
        entity.setScanId(scan.getId());
        entity.setCatalogName(scan.getCatalogName());
        entity.setDatabaseName(table.database());
        entity.setTableName(table.table());
        entity.setColumnName(column.name());
        entity.setColumnRefHash(referenceHash);
        entity.setTypeCode(match.typeCode());
        entity.setConfidence(match.confidence());
        entity.setMatchedDetectorsJson(Json.write(scores(match)));
        entity.setState(match.state().name());
        List<String> masked = sample.maskedSamples(column.name());
        if (!masked.isEmpty()) {
            entity.setMaskedSamplesJson(Json.write(masked));
            entity.setSamplingMode(sample.samplingMode());
        }
        entity.markCreated(RequestContext.principal(), now);
        return entity;
    }

    /** 各层得分，键用小写层名（与 {@code detectors_json} 同一写法，人审界面直接渲染）。 */
    private static Map<String, Object> scores(Match match) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<DetectorKind, Double> entry : match.scores().entrySet()) {
            out.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return out;
    }

    /**
     * 这次扫描里已经存在的 {@code (typeCode, columnRefHash)} 组合。
     *
     * <p>新扫描恒为空集，因此这一条查询在 P0 是纯粹的浪费——保留它是因为
     * P4 的 B 路线会**重放**同一次扫描的逐表回调（作业重试、多实例竞争），
     * 那时它是「不产生重复 finding」的唯一防线。写成一次查询而不是每列一次
     * {@code findColumnRefHashes(scanId, typeCode)}：后者在 2000 列 × 11 类型上
     * 是两万次查询。
     */
    private Set<String> existingKeys(String scanId) {
        Set<String> keys = new HashSet<>();
        for (SensitivityFindingEntity entity : findingRepository.findByScanIdOrderByConfidenceDesc(scanId)) {
            keys.add(entity.getTypeCode() + "|" + entity.getColumnRefHash());
        }
        return keys;
    }

    /** 写终态并发事件；返回真正的终态。 */
    private ScanStatus finish(SensitivityScanEntity scan, RunResult result, long startedAt) {
        long now = clock.millis();
        int updated = scanRepository.finish(scan.getId(), ScanStatus.SUCCEEDED.name(), null,
                result.tablesScanned(), result.columnsScanned(), result.findingsCreated(),
                result.tablesFailed(), List.of(ScanStatus.RUNNING.name()), now);
        if (updated == 0) {
            // 状态已经不是 RUNNING：另一个收尾者（P4 的多实例）先到了。改成 FAILED 是诚实的
            // ——这一轮的计数没有被写进去，不能对外说它「成功了」。
            log.warn("sensitivity scan {} was already finished by someone else; its counts from this"
                    + " round were discarded", scan.getId());
            scan.setStatus(ScanStatus.FAILED.name());
            scan.setFinishedAt(now);
            scan.setErrorMessage("the scan was finished concurrently; this round's counts were discarded");
            publishFinished(CatalogEventType.SENSITIVITY_SCAN_FAILED, scan, result, startedAt);
            return ScanStatus.FAILED;
        }
        scan.setFinishedAt(now);
        scan.setTablesScanned(result.tablesScanned());
        scan.setColumnsScanned(result.columnsScanned());
        scan.setFindingsCreated(result.findingsCreated());
        scan.setTablesFailed(result.tablesFailed());
        publishFinished(CatalogEventType.SENSITIVITY_SCAN_COMPLETED, scan, result, startedAt);
        return ScanStatus.SUCCEEDED;
    }

    // ------------------------------------------------------------------ 传播与生命周期

    /**
     * 扫完之后沿血缘把结论往上下游搬（§7.1）。
     *
     * <p><b>为什么以扫描为触发器。</b>传播产出的 {@code PENDING} 建议必须挂在某次扫描上
     * （finding 的唯一键是 {@code (scan_id, column_ref_hash, type_code)}），而「刚扫过的这批表
     * 有了新结论」正是最自然的起点。代价是**没有独立的重算入口**——想把一整棵血缘树
     * 重推一遍，得重新扫一次源头那几张表。这是有意的：一次没有任何扫描的传播，
     * 它写下的结论没有「谁在什么时候基于什么得出」的出处。
     *
     * <p><b>异常一律吞掉。</b>传播是收尾的增值动作：让它把一次已经成功的扫描变成 500，
     * 等于「扫完了、finding 也对，但接口报错」，而调用方拿不到那个 scanId。
     */
    private void propagate(SensitivityScanEntity scan, List<ResolvedTable> tables) {
        if (tables.isEmpty()) {
            return;
        }
        List<SensitivityPropagationService.Seed> seeds = new ArrayList<>(tables.size());
        for (ResolvedTable table : tables) {
            seeds.add(new SensitivityPropagationService.Seed(table.database(), table.table()));
        }
        try {
            propagation.propagate(scan, seeds);
        } catch (RuntimeException exception) {
            log.warn("sensitivity scan {} finished but propagation failed: {}",
                    scan.getId(), exception.getMessage(), exception);
        }
    }

    /**
     * 生命周期钩子触发的单表扫描（{@code trigger_type=LIFECYCLE}，§5.6.2 / §7.3）。
     *
     * <p>恒为 {@code METADATA_ONLY} 且恒为**该表**：新增一列要判的是「这一列叫什么、什么类型」，
     * 那是 D1/D2 的事，零 I/O；而重新采样整张表只为发现一个新列，是把一次 schema 变更
     * 变成一次明文读。
     *
     * <p><b>失败只记日志，不往上抛。</b>钩子挂在建表 / 改表的事务提交之后，那时候
     * DDL 已经成功了；让一次扫描的失败（同表已有扫描在跑 → 409、并发名额满了 → 429）
     * 冒泡出去，只会让调用方以为改表失败。
     */
    public void scanForLifecycle(String catalogName, String database, String table) {
        try {
            start(new ScanRequest(catalogName, ScanScope.TABLE.name(), database, table,
                    ScanMode.METADATA_ONLY.name(), null, null, ScanTrigger.LIFECYCLE.name()));
        } catch (RuntimeException exception) {
            log.info("lifecycle scan of {}.{}.{} skipped: {}", catalogName, database, table,
                    exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ 事件

    private void publishStarted(SensitivityScanEntity scan, int tables) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("scanId", scan.getId());
        attributes.put("scope", scan.getScope());
        attributes.put("database", scan.getDatabaseName());
        attributes.put("table", scan.getTableName());
        attributes.put("mode", scan.getMode());
        // execution 与 triggerType 必须在事件里：设计稿 §9 末段要求「谁在什么时候读了明文」
        // 能**仅凭事件流**回答——事件会被外发到 Kafka / Webhook，消费方不一定有库的读权限。
        attributes.put("execution", scan.getExecution());
        attributes.put("triggerType", scan.getTriggerType());
        attributes.put("tables", tables);
        attributes.put("sampleSize", scan.getSampleSize());
        events.publish(CatalogEventType.SENSITIVITY_SCAN_STARTED, scan.getCatalogName(),
                CatalogEvent.TARGET_CATALOG, scan.getCatalogName(), attributes);
    }

    private void publishFinished(CatalogEventType type, SensitivityScanEntity scan, RunResult result,
                                 long startedAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("scanId", scan.getId());
        attributes.put("mode", scan.getMode());
        attributes.put("execution", scan.getExecution());
        attributes.put("triggerType", scan.getTriggerType());
        attributes.put("tablesScanned", result.tablesScanned());
        attributes.put("columnsScanned", result.columnsScanned());
        attributes.put("findingsCreated", result.findingsCreated());
        attributes.put("tablesFailed", result.tablesFailed());
        attributes.put("durationMillis", clock.millis() - startedAt);
        events.publish(type, scan.getCatalogName(), CatalogEvent.TARGET_CATALOG,
                scan.getCatalogName(), attributes);
    }

    // ------------------------------------------------------------------ 范围解析

    /**
     * 把范围解析成「要扫的表清单」。
     *
     * <p>{@code CATALOG} 级一次取全 catalog 的表（{@code findAllByCatalogIdOrderByDatabaseIdAscNameAsc}），
     * 因此库名要另外查一次并建成映射——表实体里只有 {@code database_id}，
     * 而 finding / tag 存的是**库名**（改名后要能直接读出来，不必再回表）。
     */
    private List<ResolvedTable> resolveTables(String catalogId, ScanScope scope, String database,
                                              String table, Map<String, String> databaseNames) {
        switch (scope) {
            case CATALOG -> {
                List<ResolvedTable> resolved = new ArrayList<>();
                for (TableEntity entity : tableRepository.findAllByCatalogIdOrderByDatabaseIdAscNameAsc(catalogId)) {
                    resolved.add(new ResolvedTable(
                            databaseNames.getOrDefault(entity.getDatabaseId(), entity.getDatabaseId()),
                            entity.getName(), entity.getSchemaDoc()));
                }
                return resolved;
            }
            case DATABASE -> {
                DatabaseEntity databaseEntity = requireDatabase(catalogId, database);
                List<ResolvedTable> resolved = new ArrayList<>();
                for (TableEntity entity : tableRepository.findAllByDatabaseIdOrderByNameAsc(databaseEntity.getId())) {
                    resolved.add(new ResolvedTable(databaseEntity.getName(), entity.getName(),
                            entity.getSchemaDoc()));
                }
                return resolved;
            }
            case TABLE -> {
                DatabaseEntity databaseEntity = requireDatabase(catalogId, database);
                TableEntity entity = tableRepository
                        .findByCatalogIdAndDatabaseIdAndName(catalogId, databaseEntity.getId(), table)
                        .orElseThrow(() -> ApiException.tableNotExist(table));
                return List.of(new ResolvedTable(databaseEntity.getName(), entity.getName(),
                        entity.getSchemaDoc()));
            }
            default -> throw ApiException.badRequest("Unknown scope: " + scope);
        }
    }

    private DatabaseEntity requireDatabase(String catalogId, String database) {
        return databaseRepository.findByCatalogIdAndName(catalogId, database)
                .orElseThrow(() -> ApiException.databaseNotExist(database));
    }

    /** 一次取全 catalog 的「库 id → 库名」，避免逐表回查。 */
    private Map<String, String> databaseNames(String catalogId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (DatabaseEntity entity : databaseRepository.findAllByCatalogIdOrderByNameAsc(catalogId)) {
            names.put(entity.getId(), entity.getName());
        }
        return names;
    }

    // ------------------------------------------------------------------ 三层覆盖

    /**
     * 执行位置的**生效值**：请求 &gt; catalog 的 {@code defaultExecution} &gt; 全局。
     *
     * <p>请求层在这里解析，后两层交给 {@link SensitivitySettingsService}（它才是
     * 「这个 catalog 缺省是什么」的拥有者）。请求写了但认不出 → 400：与 yml 里写错
     * 只 warn 刻意不同，API 请求调用方立刻能改，静默回落会让「我明明选了 SPARK」
     * 变成一条查不出来的行为。
     */
    private ScanExecution resolveExecution(String requested, CatalogEntity catalog, String catalogName) {
        String value = trimToNull(requested);
        if (value != null) {
            return ScanExecution.parse(value)
                    .orElseThrow(() -> ApiException.badRequest("Unknown execution \"" + requested
                            + "\" for catalog " + catalogName + "; supported: IN_PROCESS, SPARK"));
        }
        return ScanExecution.parse(settingsService.resolvedExecution(
                        SensitivityCatalogSettings.read(catalog.getProperties())))
                .orElse(ScanExecution.IN_PROCESS);
    }

    private int resolveSampleSize(Integer requested) {
        int max = properties.getSensitivity().getScan().getMaxSampleSize();
        if (requested == null) {
            return properties.getSensitivity().getScan().getSampleSize();
        }
        if (requested <= 0) {
            throw ApiException.badRequest("sampleSize must be positive, got " + requested);
        }
        if (requested > max) {
            // 不静默截断：结论质量正是由样本量决定的（§5.4）。
            throw ApiException.badRequest("sampleSize " + requested
                    + " exceeds lakehouse.rest.sensitivity.scan.max-sample-size=" + max);
        }
        return requested;
    }

    // ------------------------------------------------------------------ 并发闸门

    /**
     * 占一个进程内并发名额（按 catalog 的生效值）。
     *
     * <p><b>先占位再看数</b>（{@code merge} 是原子的）：先读后写在并发窗口里会
     * 让两个请求都看到「还没满」而一起放行——那样这道闸门只在串行时成立。
     *
     * <p>超额回 429 而不是排队：§5.6.4 的「排队」是给定时批量触发准备的（P4），
     * 而同步执行下排队等价于把请求线程按在那里，超过几条就是线程池耗尽——
     * 那是把「保护存储」换成了「打挂服务端」。
     */
    private void requireSlot(String catalogName, int limit) {
        int current = inFlight.merge(catalogName, 1, Integer::sum);
        if (current > limit) {
            releaseSlot(catalogName);
            throw new ApiException(429, null, null, "too many concurrent sensitivity scans on catalog "
                    + catalogName + " (" + limit + " allowed;"
                    + " lakehouse.rest.sensitivity.scan.max-concurrent / sensitivity-settings"
                    + " .maxConcurrentScans); this gate guards the storage side against concurrent"
                    + " reads and is separate from the execution cluster's maxConcurrentJobs");
        }
    }

    private void releaseSlot(String catalogName) {
        inFlight.computeIfPresent(catalogName, (key, value) -> value <= 1 ? null : value - 1);
    }

    // ------------------------------------------------------------------ 参数解析

    private static ScanScope parseScope(String value) {
        String scope = trimToNull(value);
        if (scope == null) {
            return ScanScope.CATALOG;
        }
        return ScanScope.parse(scope)
                .orElseThrow(() -> ApiException.badRequest("Unknown scope \"" + value
                        + "\"; supported: CATALOG, DATABASE, TABLE"));
    }

    private static ScanMode parseMode(String value) {
        String mode = trimToNull(value);
        if (mode == null) {
            return ScanMode.METADATA_ONLY;
        }
        return ScanMode.parse(mode)
                .orElseThrow(() -> ApiException.badRequest("Unknown mode \"" + value
                        + "\"; supported: METADATA_ONLY, SAMPLED"));
    }

    private static ScanTrigger parseTrigger(String value) {
        String trigger = trimToNull(value);
        if (trigger == null) {
            return ScanTrigger.MANUAL;
        }
        return ScanTrigger.parse(trigger)
                .orElseThrow(() -> ApiException.badRequest("Unknown triggerType \"" + value
                        + "\"; supported: MANUAL, LIFECYCLE, SCHEDULE"));
    }

    /** 范围内必需的字段：层级不靠「哪几个字段有值」反推（§9 的注）。 */
    private static void requireScopeFields(ScanScope scope, String database, String table) {
        if (scope == ScanScope.DATABASE && database == null) {
            throw ApiException.badRequest("scope=DATABASE requires database");
        }
        if (scope == ScanScope.TABLE && (database == null || table == null)) {
            throw ApiException.badRequest("scope=TABLE requires database and table");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ 映射

    private static ScanSummary toDto(SensitivityScanEntity entity) {
        return new ScanSummary(entity.getId(), entity.getCatalogName(), entity.getScope(),
                entity.getDatabaseName(), entity.getTableName(), entity.getMode(), entity.getExecution(),
                entity.getTriggerType(), entity.getScheduleName(), entity.getStatus(), entity.getSampleSize(),
                entity.getTablesScanned(), entity.getColumnsScanned(), entity.getFindingsCreated(),
                entity.getTablesFailed(), entity.getTriggeredBy(), entity.getStartedAt(),
                entity.getFinishedAt(), entity.getErrorMessage(), entity.getExecutionNamespace(),
                entity.getExecutionResource(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * 实体 → 读模型。
     *
     * <p>{@code columnRefHash} 只用于内部（列级标签的寻址），不比读模型里多一个字段——
     * 调用方要的是能展示、能比较的全名 {@code columnRef}。是哈希也好，重算也好，
     * 都不该是调用方要知道的事。
     */
    private static Finding toDto(SensitivityFindingEntity entity) {
        return new Finding(entity.getId(), entity.getScanId(), entity.getCatalogName(),
                entity.getDatabaseName(), entity.getTableName(), entity.getColumnName(),
                ColumnRefs.fullName(entity.getCatalogName(), entity.getDatabaseName(),
                        entity.getTableName(), entity.getColumnName()),
                entity.getTypeCode(), entity.getConfidence(),
                detectors(entity.getMatchedDetectorsJson()), samples(entity.getMaskedSamplesJson()),
                entity.getSamplingMode(), entity.getState(), entity.getDecidedBy(),
                entity.getDecidedAt(), entity.getCreatedAt());
    }

    /**
     * 分层得分解码：旧行可能没有这一段（列可空），认不出就当「没有」，不抛。
     *
     * <p>quietly-return-empty 是刻意的：一行读不出来不该让整页人审队列打不开。
     * 而「为什么这么判」缺失时界面显示的是「无分层明细」——这比一个 500 诚实。
     */
    private static Map<String, Object> detectors(String json) {
        try {
            Object parsed = Json.read(json);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        } catch (RuntimeException exception) {
            log.warn("cannot decode matched_detectors_json: {}", exception.getMessage());
        }
        return Map.of();
    }

    /**
     * 打码样本解码。
     *
     * <p>它是**打码后**的字符串（{@code 110***********1234}），不是原值的掩码视图——
     * 因此这里可以直接回给调用方。返回 {@code List<String>} 而不是更宽的类型，
     * 是为了让「这里拿不到原值」成为类型上的事实而不是约定（见 {@code SensitivityDtos.Finding}）。
     */
    private static List<String> samples(String json) {
        try {
            Object parsed = Json.read(json);
            if (parsed instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(item == null ? null : String.valueOf(item));
                }
                return out;
            }
        } catch (RuntimeException exception) {
            log.warn("cannot decode masked_samples_json: {}", exception.getMessage());
        }
        return List.of();
    }

    /** 一张待扫的表：库名 + 表名 + 元数据文档。 */
    private record ResolvedTable(String database, String table, String schemaDoc) {
    }

    /** 一轮扫描的计数（与 {@code lakehouse_sensitivity_scan} 的四列一一对应）。 */
    private record RunResult(int tablesScanned, int columnsScanned, int findingsCreated, int tablesFailed) {
    }
}
