package io.github.melin.lakehouse.rest.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 目录领域事件的类型。每个类型属于一个 {@link CatalogEventCategory}——后者是
 * 监听器订阅的粒度，前者是事件流里的契约字符串。
 *
 * <p><b>wire 值用小写点分</b>，形如 {@code table.created}：它是跨系统的契约
 * （事件流、未来 Kafka / Webhook 报文），集中声明在这里而不是让各发布点手写字符串。
 *
 * <p><b>对标 Polaris 的 {@code PolarisEventType}，但只取「AFTER + 变更」这一半。</b>
 * Polaris 的枚举是 CRUD × BEFORE/AFTER 的请求级遥测（15 个类别、一百多个常量），
 * 其中 GET / LIST / LOAD 这类读操作占了一半。照抄它会有两个后果：事件表变成访问日志
 * （量级从「变更次数」变成「请求数」），以及每条业务路径上多一次无意义的 publish。
 * 因此本枚举的收录判据是：<b>这次调用是否让某个持久状态发生了改变</b>。
 * 与 Polaris 的逐条映射见 {@code docs/event-framework.md} §1.1。
 *
 * <p>不收录的情形（每条都有具体理由，不是遗漏）：读路径（load / list / metrics 回报）、
 * 幂等空操作（撤销不存在的授权、空 updates 的提交、CAS 失败）、BEFORE 事件。
 */
public enum CatalogEventType {

    // ------------------------------------------------------------------ CATALOG

    /** 新建 catalog。对标 Polaris {@code AFTER_CREATE_CATALOG}。 */
    CATALOG_CREATED(CatalogEventCategory.CATALOG, "catalog.created"),

    /** 改 catalog 配置（属性、存储配置）。对标 {@code AFTER_UPDATE_CATALOG}。 */
    CATALOG_UPDATED(CatalogEventCategory.CATALOG, "catalog.updated"),

    /** 删 catalog。对标 {@code AFTER_DELETE_CATALOG}。 */
    CATALOG_DROPPED(CatalogEventCategory.CATALOG, "catalog.dropped"),

    // ------------------------------------------------------------------ NAMESPACE

    /** 建库 / namespace。对标 {@code AFTER_CREATE_NAMESPACE}。 */
    NAMESPACE_CREATED(CatalogEventCategory.NAMESPACE, "namespace.created"),

    /** 改库属性。对标 {@code AFTER_UPDATE_NAMESPACE_PROPERTIES}。 */
    NAMESPACE_UPDATED(CatalogEventCategory.NAMESPACE, "namespace.updated"),

    /** 删库（含级联删表）。对标 {@code AFTER_DROP_NAMESPACE}。 */
    NAMESPACE_DROPPED(CatalogEventCategory.NAMESPACE, "namespace.dropped"),

    // ------------------------------------------------------------------ TABLE

    /** 建表。对标 {@code AFTER_CREATE_TABLE}。 */
    TABLE_CREATED(CatalogEventCategory.TABLE, "table.created"),

    /**
     * 注册已存在的表（只登记元数据位置，不接管数据）。对标 {@code AFTER_REGISTER_TABLE}。
     *
     * <p>与 {@link #TABLE_CREATED} 分开的理由：注册进来的表数据不归我们管（删表时
     * 默认也不删目录），审计上「我们创建的数据」与「我们接管的别人的数据」是两类事。
     */
    TABLE_REGISTERED(CatalogEventCategory.TABLE, "table.registered"),

    /** 删表。对标 {@code AFTER_DROP_TABLE}。 */
    TABLE_DROPPED(CatalogEventCategory.TABLE, "table.dropped"),

    /** 注销（只从目录摘掉登记，不动底层数据）。对标 {@code AFTER_DROP_TABLE} 的注销分支。 */
    TABLE_UNREGISTERED(CatalogEventCategory.TABLE, "table.unregistered"),

    /** 表改名或换库。对标 {@code AFTER_RENAME_TABLE}。 */
    TABLE_RENAMED(CatalogEventCategory.TABLE, "table.renamed"),

    /** 元数据指针推进（提交快照、schema 变更）。对标 {@code AFTER_UPDATE_TABLE}。 */
    TABLE_COMMITTED(CatalogEventCategory.TABLE, "table.committed"),

    /**
     * 有人通过控制台读了表里的数据（数据预览的**第一页**）。
     *
     * <p><b>这是本枚举里唯一一个"读路径"事件</b>，与上面那条"只收变更"的判据不一致，
     * 因此要单独说明：预览是本工程第一个真的把行数据取出来给浏览器看的接口，
     * "谁在什么时候看了哪张表、看了多大范围"本身就是合规要回答的问题——
     * 它记录的不是状态变化，而是**数据访问**。两类问题分开记，比把它们混进
     * {@code table.committed} 里好查。
     *
     * <p><b>一次预览只记一条。</b>记在"加载更多"的每一页上会让事件表变成访问日志；
     * 用户翻页是同一个意图的延续。
     *
     * <p><b>取值不落库。</b>属性里只有过滤条件的列名与算子、分区字段名与命中数——
     * 值可能就是手机号或身份证号。这条约束由发布点（{@code TablePreviewService}）保证，
     * 因为只有它知道哪一部分是"条件"、哪一部分是"值"。
     */
    TABLE_PREVIEWED(CatalogEventCategory.TABLE, "table.previewed"),

    // ------------------------------------------------------------------ VIEW

    /** 建视图。对标 {@code AFTER_CREATE_VIEW}。 */
    VIEW_CREATED(CatalogEventCategory.VIEW, "view.created"),

    /** 改视图定义。对标 {@code AFTER_REPLACE_VIEW}。 */
    VIEW_UPDATED(CatalogEventCategory.VIEW, "view.updated"),

    /** 删视图。对标 {@code AFTER_DROP_VIEW}。 */
    VIEW_DROPPED(CatalogEventCategory.VIEW, "view.dropped"),

    /** 视图改名或换库。对标 {@code AFTER_RENAME_VIEW}。 */
    VIEW_RENAMED(CatalogEventCategory.VIEW, "view.renamed"),

    // ------------------------------------------------------------------ GRANT

    /** 授予权限（直授给主体或主体角色，见官方 12 项 access）。对标 {@code AFTER_ADD_GRANT_TO_CATALOG_ROLE}。 */
    GRANT_GRANTED(CatalogEventCategory.GRANT, "grant.granted"),

    /** 撤销权限。对标 {@code AFTER_REVOKE_GRANT_FROM_CATALOG_ROLE}。 */
    GRANT_REVOKED(CatalogEventCategory.GRANT, "grant.revoked"),

    // ------------------------------------------------------------------ PRINCIPAL

    /** 建主体。对标 {@code AFTER_CREATE_PRINCIPAL}。 */
    PRINCIPAL_CREATED(CatalogEventCategory.PRINCIPAL, "principal.created"),

    /** 改主体（显示名、属性）。对标 {@code AFTER_UPDATE_PRINCIPAL}。 */
    PRINCIPAL_UPDATED(CatalogEventCategory.PRINCIPAL, "principal.updated"),

    /** 删主体。对标 {@code AFTER_DELETE_PRINCIPAL}。 */
    PRINCIPAL_DROPPED(CatalogEventCategory.PRINCIPAL, "principal.dropped"),

    /** 轮换凭据（服务端生成新密钥）。对标 {@code AFTER_ROTATE_CREDENTIALS}。 */
    PRINCIPAL_CREDENTIALS_ROTATED(CatalogEventCategory.PRINCIPAL, "principal.credentials_rotated"),

    /** 重置凭据（管理员指定新密钥）。对标 {@code AFTER_RESET_CREDENTIALS}。 */
    PRINCIPAL_CREDENTIALS_RESET(CatalogEventCategory.PRINCIPAL, "principal.credentials_reset"),

    /** 把主体角色授予主体。对标 {@code AFTER_ASSIGN_PRINCIPAL_ROLE}。 */
    PRINCIPAL_ROLE_ASSIGNED(CatalogEventCategory.PRINCIPAL, "principal.role_granted"),

    /** 收回主体的角色。对标 {@code AFTER_REVOKE_PRINCIPAL_ROLE}。 */
    PRINCIPAL_ROLE_REVOKED(CatalogEventCategory.PRINCIPAL, "principal.role_revoked"),

    /**
     * 一次 SSO 登录（成功与失败都记）。
     *
     * <p><b>这是 PRINCIPAL 类别里唯一的「读」事件</b>，与「只收变更」那条判据不一致，
     * 因此单独说明：身份接入的问题全都是靠它回答的——「这个人为什么没有权限」
     * 要能看出「他登录过吗、用的哪个 issuer、供给成功了吗」。
     * 没有这条事件时，这类问题的唯一线索是服务端日志，而日志会被轮转掉。
     *
     * <p><b>属性里不放 claim 的值</b>（邮箱、组名原样进事件表等于复制一份通讯录），
     * 只放个数与分类原因。
     */
    PRINCIPAL_LOGIN(CatalogEventCategory.PRINCIPAL, "principal.login"),

    /** 首次 SSO 登录自动创建主体（JIT）。属性里有 issuer 与 externalId。 */
    PRINCIPAL_PROVISIONED(CatalogEventCategory.PRINCIPAL, "principal.provisioned"),

    /** 组 → 角色同步完成。属性里有增加/删除的角色名、同步模式与组数。 */
    PRINCIPAL_ROLES_SYNCED(CatalogEventCategory.PRINCIPAL, "principal.roles_synced"),

    /**
     * 组映射本身变了（增、改、删、启停）。
     *
     * <p>与 {@link #PRINCIPAL_ROLES_SYNCED} 分开：那一条记「某个人现在有哪些角色」，
     * 这一条记「规则变了」。两者合在一起时，事后要回答「这批人的权限为什么变了」
     * 就得从一堆同步事件里反推规则变更，而规则变更往往只有一次。
     */
    GROUP_MAPPING_CHANGED(CatalogEventCategory.PRINCIPAL, "group_mapping.changed"),

    // ------------------------------------------------------------------ PRINCIPAL_ROLE

    /** 建主体角色。对标 {@code AFTER_CREATE_PRINCIPAL_ROLE}。 */
    PRINCIPAL_ROLE_CREATED(CatalogEventCategory.PRINCIPAL_ROLE, "principal_role.created"),

    /** 改主体角色。对标 {@code AFTER_UPDATE_PRINCIPAL_ROLE}。 */
    PRINCIPAL_ROLE_UPDATED(CatalogEventCategory.PRINCIPAL_ROLE, "principal_role.updated"),

    /** 删主体角色。对标 {@code AFTER_DELETE_PRINCIPAL_ROLE}。 */
    PRINCIPAL_ROLE_DROPPED(CatalogEventCategory.PRINCIPAL_ROLE, "principal_role.dropped"),

    // ------------------------------------------------------------------ POLICY

    /**
     * 优化策略变化（表策略与 catalog 级默认设置）。
     *
     * <p>Polaris 的 policy store 是独立实体，有 create / update / drop / attach / detach
     * 五类事件；我们的策略挂在表与 catalog 设置上（upsert + enabled 开关），没有独立的
     * 生命周期，因此只有一个类型。真要接 Polaris 的 policy store 时再按它的粒度拆。
     */
    OPTIMIZATION_POLICY_UPDATED(CatalogEventCategory.POLICY, "optimization.policy_updated"),

    /**
     * 行过滤 / 列脱敏策略变化（增、改、删、启停，{@code docs/row-filter-column-mask-design.md} §8.2）。
     *
     * <p><b>与优化策略分开一个类型</b>而不是共用 {@code optimization.policy_updated}：
     * 两者的「变化」严重级完全不同——优化策略改错最多是「该合并的没合并」，数据策略改错
     * 是「该保护的保护没了」，合规审计要把后者单独拉出来看。
     *
     * <p><b>属性里只有变更前后的摘要，不记表达式的字面量值</b>（§8.2 的「不记什么」）：
     * 谓词里的 {@code region = 'CN'} 可能换成黑名单成员或手机号，字面量进事件表等于
     * 把敏感值复制了一份。要看变化全文，走策略自己的版本号与操作者定位。
     */
    POLICY_CHANGED(CatalogEventCategory.POLICY, "policy.changed"),

    // ------------------------------------------------------------------ OPTIMIZATION

    /** 优化批次全部成功。 */
    OPTIMIZATION_JOB_SUCCEEDED(CatalogEventCategory.OPTIMIZATION, "optimization.job_succeeded"),

    /** 优化批次部分成功：合并执行的批次特有的终态。 */
    OPTIMIZATION_JOB_PARTIALLY_SUCCEEDED(CatalogEventCategory.OPTIMIZATION,
            "optimization.job_partially_succeeded"),

    /** 优化批次失败（含提交失败、超时、全部表失败）。 */
    OPTIMIZATION_JOB_FAILED(CatalogEventCategory.OPTIMIZATION, "optimization.job_failed"),

    /** 优化批次被取消。 */
    OPTIMIZATION_JOB_CANCELED(CatalogEventCategory.OPTIMIZATION, "optimization.job_canceled"),

    // ------------------------------------------------------------------ SENSITIVITY

    /**
     * 一次敏感数据扫描开始。
     *
     * <p><b>属性里带 {@code execution} 与 {@code triggerType}（设计稿 §9 末段）。</b>
     * 「谁在什么时候读了明文」要能**仅凭事件流**回答，不必回查扫描表——
     * 事件表会被外发到 Kafka / Webhook，消费方不一定有库的读权限。
     *
     * <p><b>这是 SENSITIVITY 类别里的「读」事件</b>（与 {@code TABLE_PREVIEWED} 同类），
     * 与「只收变更」那条总判据不一致，因此单独说明：扫描要读明文样本来判断列里
     * 是什么，这个读取动作本身就是合规要记录的访问。设计稿 §5.4 把它列为
     * 「全程审计」的一条，属性里有范围、列数、采样行数与主体。
     */
    SENSITIVITY_SCAN_STARTED(CatalogEventCategory.SENSITIVITY, "sensitivity.scan.started"),

    /** 一次敏感数据扫描完成（含扫了 0 条命中的情况）。 */
    SENSITIVITY_SCAN_COMPLETED(CatalogEventCategory.SENSITIVITY, "sensitivity.scan.completed"),

    /** 一次敏感数据扫描失败（{@code error_message} 有原因）。 */
    SENSITIVITY_SCAN_FAILED(CatalogEventCategory.SENSITIVITY, "sensitivity.scan.failed"),

    /**
     * 人对一条 finding 做了决策（确认 / 拒绝）。
     *
     * <p>P0 不发这个事件（人审队列是 P1），但类型先建：{@code CatalogEventType}
     * 的取值是「跨系统的契约字符串」，{@code enabled-event-types} 配置里写了它
     * 就该能被解析——等实现时再补，会让已经按文档配好的部署在升级后拿到一个
     * 「未知事件类型」的启动告警。
     */
    SENSITIVITY_FINDING_DECIDED(CatalogEventCategory.SENSITIVITY, "sensitivity.finding.decided"),

    /**
     * 某列的标签变了（新增 / 改值 / 删除 / 启停）。
     *
     * <p>属性里带 before / after 与操作者（设计稿 §8.4）：下游订阅方（数据地图、
     * 审批流）要能据此增量更新，而不是每次全量拉一遍标签表。
     *
     * <p><b>属性里不放列的值</b>——标签的变化不涉及数据值，这条约束自然成立；
     * 写在这里是提醒：将来若要给「自动应用」的标签加个「命中样本」字段，
     * 那会是一条把明文带进事件表的路径（§5.4 的第一条不变量）。
     */
    COLUMN_TAG_CHANGED(CatalogEventCategory.SENSITIVITY, "column.tag.changed"),

    /**
     * catalog 级治理配置变了。
     *
     * <p><b>P0 的发布点是 {@code SensitivitySettingsService.update}（{@code PUT
     * /sensitivity-settings}）</b>——它改的是「这个 catalog 的扫描怎么跑」：
     * {@code defaultExecution} / {@code maxConcurrentScans} / {@code propagationMaxDepth}，
     * 属性里带改动前后的三个值、解析后的生效值与乐观锁版本。设计稿 §9 对此的要求是
     * 「{@code PUT} 走乐观锁，且**变更进审计**——它改的是『这个 catalog 的扫描怎么跑』，
     * 与改绑定同级」，控制台那一屏的标签也因此是「治理设置变更」。
     *
     * <p><b>P3 的真绑定（标签 → 脱敏动作）落地后复用同一个类型</b>：两者都是
     * 「catalog 级治理配置变更」，设计稿的事件清单只给了这一个类型承载它们。
     * 到那时属性里要再加一个 {@code kind} 区分「设置 / 绑定」，否则下游订阅方
     * 拿到 {@code sensitivity.binding.changed} 时无法判断该刷新哪一份缓存。
     *
     * <p><b>值没变不发</b>（与事件框架「没有变化的操作不进事件流」同一条规则）：
     * 同一个值连按两次保存不该在事件流里留两条。
     */
    SENSITIVITY_BINDING_CHANGED(CatalogEventCategory.SENSITIVITY, "sensitivity.binding.changed");

    private static final Map<String, CatalogEventType> BY_WIRE = indexByWire();

    private final CatalogEventCategory category;
    private final String wire;

    CatalogEventType(CatalogEventCategory category, String wire) {
        this.category = category;
        this.wire = wire;
    }

    /** 事件流与外发报文里使用的字符串形式。 */
    public String wire() {
        return wire;
    }

    /** 所属类别：监听器按它订阅（配置项 {@code enabled-event-categories}）。 */
    public CatalogEventCategory category() {
        return category;
    }

    /** 按 wire 值反查，配置校验用（{@code enabled-event-types} 写错时要能报出来）。 */
    public static Optional<CatalogEventType> byWire(String wire) {
        return wire == null ? Optional.empty() : Optional.ofNullable(BY_WIRE.get(wire.trim()));
    }

    private static Map<String, CatalogEventType> indexByWire() {
        Map<String, CatalogEventType> index = new LinkedHashMap<>();
        for (CatalogEventType type : values()) {
            index.put(type.wire, type);
        }
        return Map.copyOf(index);
    }
}
