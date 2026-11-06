package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventListener;
import io.github.melin.lakehouse.rest.support.LineageRefs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 表生命周期与敏感数据治理的联动（设计见 {@code docs/sensitive-data-design.md} §7.3）。
 *
 * <p>四条规则，与 {@code DataPolicyLifecycleListener} 同一位置、同一套结论：
 * <ul>
 *   <li><b>{@code DROP} / {@code UNREGISTER} → 删标签与 finding。</b>不删的话同名重建的表
 *       会继承旧标签，表现是「新表某列被莫名其妙 mask 掉」；</li>
 *   <li><b>{@code RENAME} → 标签跟随。</b>改名不改语义，保护要跟着表走；</li>
 *   <li><b>{@code CREATE} → 不继承任何东西。</b>新表就是新表（这一条不需要代码——它由上面
 *       两条保证，写在这里是为了让「为什么 CREATE 什么都不做」有出处）；</li>
 *   <li><b>{@code ALTER} → 触发该表的一次增量扫描</b>（{@code trigger_type=LIFECYCLE}）。
 *       新增的列在分类前没有标签，控制台据此把它显示成「未分类」并计入覆盖率缺口——
 *       「缺口」的定义就是「schema 里有的列，标签里没有」，因此这一条不需要额外标记，
 *       它需要的是**尽快把新列扫一遍**，让 D1/D2 先给出一个名字与类型上的判断。</li>
 * </ul>
 *
 * <p><b>为什么挂在事件上，而不是在协议面里直接调。</b>{@code ALTER} 的实现分散在 Paimon /
 * Iceberg / Lance 三个协议模块，标签与扫描都在 server——让协议模块反向依赖治理服务，
 * 等于把「敏感数据」焊死在表结构变更上。{@code CatalogEventListener} 是唯一一个
 * 两边都够得着的位置（与 {@code DataPolicyLifecycleListener} 同一条理由）。
 *
 * <p><b>{@code ALTER} 的扫描放在事务提交之后。</b>发布点是同步的，监听器因此跑在
 * 改表那个事务**里面**：此时直接扫一遍会把一次 DDL 变成一个「改表 + 扫表」的长事务，
 * 并且扫描读到的 {@code schema_doc} 在提交前对别的实例不可见。注册
 * {@code afterCommit} 回调把扫描挪到提交之后——DDL 已经生效、事务已释放，
 * 扫描哪怕失败也只是少一次「新列已分类」，不会让改表本身回滚。
 */
@Slf4j
@Component
public class SensitivityLifecycleListener implements CatalogEventListener {

    /** 监听器名：部署可以用 {@code lakehouse.rest.event-listener.types} 只启用部分监听器。 */
    public static final String NAME = "sensitivity";

    private final ColumnTagService tagService;
    private final SensitivityScanService scanService;
    private final TransactionTemplate newTransaction;

    /**
     * 两个依赖都 {@code @Lazy}。
     *
     * <p>{@code SensitivityScanService} 那条环是明显的（它自己发事件）；{@code ColumnTagService}
     * 那条不那么显眼：它经由 {@code SensitivityTagWriter} 发 {@code column.tag.changed}，
     * 而发布器要先把全部监听器收齐——收齐就要构造本类，本类又要 {@code ColumnTagService}。
     * 监听器是被动消费方，第一次事件到来时才解析这些服务是安全的。
     *
     * <p><b>{@code REQUIRES_NEW} 的模板不是可有可无的。</b>监听器是同步的，因此
     * {@code afterCommit} 里线程上仍然绑着刚提交那个事务的连接与 {@code EntityManager}；
     * 直接写库会写进一个「已提交但未清理」的会话里，<b>插入既不会被 flush，也不会报错</b>
     * ——表现是「改完表结构，扫描记录一条也没有」。开一个新事务把这批写操作绑到
     * 一份干净的资源上，同时也让「扫描失败」只回滚扫描，不影响已经提交的 DDL。
     */
    public SensitivityLifecycleListener(@Lazy ColumnTagService tagService,
                                        @Lazy SensitivityScanService scanService,
                                        PlatformTransactionManager transactionManager) {
        this.tagService = tagService;
        this.scanService = scanService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEvent(CatalogEvent event) {
        if (!CatalogEvent.TARGET_TABLE.equals(event.targetType())) {
            return;
        }
        try {
            switch (event.type()) {
                case TABLE_DROPPED, TABLE_UNREGISTERED -> drop(event);
                case TABLE_RENAMED -> rename(event);
                case TABLE_COMMITTED -> alter(event);
                default -> {
                    // TABLE_CREATED / TABLE_REGISTERED 以及其余表事件与敏感数据无关：
                    // 新表不继承任何东西（§7.3）
                }
            }
        } catch (RuntimeException exception) {
            // 事件是业务的旁观者：治理数据的残留最坏结果是「多了一条解释不清的标签」，
            // 而让删表 / 改名本身因此失败，是把旁观者的错误变成参与者的错误
            log.error("failed to apply {} to the sensitivity tags of {}.{}: {}", event.type(),
                    event.catalog(), event.target(), exception.getMessage(), exception);
        }
    }

    private void drop(CatalogEvent event) {
        Ref ref = ref(event, event.target());
        if (ref == null) {
            return;
        }
        tagService.onTableDropped(ref.catalog(), ref.database(), ref.table());
    }

    /**
     * {@code RENAME} 事件：{@code target} 是<b>新</b>全名，{@code from} 属性是旧名。
     *
     * <p>缺 {@code from} 时只能跳过并记 WARN：静默跳过会让「改名后标签没跟上」
     * 排障无门，而那张表从此没有标签保护，看起来像是「扫不出来了」。
     */
    private void rename(CatalogEvent event) {
        Ref from = ref(event, event.attributes().get("from"));
        Ref to = ref(event, event.target());
        if (from == null || to == null) {
            log.warn("table.renamed event without a parsable from/target, column tags not moved:"
                    + " catalog={} target={}", event.catalog(), event.target());
            return;
        }
        tagService.onTableRenamed(to.catalog(), from.database(), from.table(),
                to.database(), to.table());
    }

    /**
     * schema 变更（{@code ALTER} 的发布点带 {@code change=alter}）→ 提交后扫一遍这张表。
     *
     * <p><b>判据只看 {@code change}，不看具体是哪一种变更。</b>发布点的报文里没有变更清单，
     * 而「加列」与「改列类型」对识别的意义是同一件事：这一列的元数据变了，之前那个
     * 「未分类」或「已分类」的判断可能不再成立。重扫一遍是幂等的，代价是
     * {@code METADATA_ONLY} 的一张表（毫秒级、零 I/O）。
     */
    private void alter(CatalogEvent event) {
        Object change = event.attributes() == null ? null : event.attributes().get("change");
        if (!"alter".equals(String.valueOf(change))) {
            // 快照提交（change 为空）与 rollback-schema 走不到这里：它们不改列的集合
            return;
        }
        Ref ref = ref(event, event.target());
        if (ref == null) {
            log.warn("table.committed(alter) event without a parsable target, no lifecycle scan:"
                    + " catalog={} target={}", event.catalog(), event.target());
            return;
        }
        runAfterCommit(() -> {
            try {
                newTransaction.executeWithoutResult(status ->
                        scanService.scanForLifecycle(ref.catalog(), ref.database(), ref.table()));
            } catch (RuntimeException exception) {
                // 扫描失败不该让改表失败：DDL 已经提交了，这里少的是「新列刚被扫过一遍」
                log.info("lifecycle scan of {}.{}.{} failed: {}", ref.catalog(), ref.database(),
                        ref.table(), exception.getMessage());
            }
        });
    }

    /**
     * 把一段定位符凑成三段名。
     *
     * <p>发布点的形状是 {@code db.table}（不带 catalog，库段自身可能多级），catalog 永远从
     * 事件的 catalog 字段补——不能从定位符里猜首段：多级 namespace 的表，定位符首段是
     * namespace 而不是 catalog。复用 {@link LineageRefs#parse} 而不是自己切字符串：
     * 「首段 catalog、末段表、中间全是 namespace」这条规则在仓库里只该有一份。
     */
    private Ref ref(CatalogEvent event, Object raw) {
        String text = raw == null ? null : String.valueOf(raw);
        String catalog = event.catalog();
        if (text == null || text.isBlank() || catalog == null || catalog.isBlank()) {
            return null;
        }
        return LineageRefs.parse(catalog + "." + text)
                .filter(parsed -> !parsed.namespace().isEmpty())
                .map(parsed -> new Ref(parsed.catalog(),
                        String.join(".", parsed.namespace()), parsed.table()))
                .orElse(null);
    }

    /**
     * 提交后执行；没有事务（例如测试直接调服务层）时就地执行。
     *
     * <p>没有事务上下文的那条分支不是兜底代码，而是**必须存在**的一条：MockMvc 的用例
     * 不包事务，如果只注册回调，那些用例里 {@code afterCommit} 永远不会被调用，
     * 于是「钩子确实触发了扫描」这条断言会以一个「什么都没发生」的假绿灯通过。
     */
    private static void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /** 三段名。 */
    private record Ref(String catalog, String database, String table) {
    }
}
