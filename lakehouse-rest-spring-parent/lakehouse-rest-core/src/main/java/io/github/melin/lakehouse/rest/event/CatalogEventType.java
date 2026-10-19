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

    // ------------------------------------------------------------------ OPTIMIZATION

    /** 优化批次全部成功。 */
    OPTIMIZATION_JOB_SUCCEEDED(CatalogEventCategory.OPTIMIZATION, "optimization.job_succeeded"),

    /** 优化批次部分成功：合并执行的批次特有的终态。 */
    OPTIMIZATION_JOB_PARTIALLY_SUCCEEDED(CatalogEventCategory.OPTIMIZATION,
            "optimization.job_partially_succeeded"),

    /** 优化批次失败（含提交失败、超时、全部表失败）。 */
    OPTIMIZATION_JOB_FAILED(CatalogEventCategory.OPTIMIZATION, "optimization.job_failed"),

    /** 优化批次被取消。 */
    OPTIMIZATION_JOB_CANCELED(CatalogEventCategory.OPTIMIZATION, "optimization.job_canceled");

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
