package io.github.melin.lakehouse.rest.event;

/**
 * 事件类别：一组相关事件类型的集合，是<b>监听器订阅的粒度</b>。
 *
 * <p>对标 Polaris 的 {@code PolarisEventType.Category}（配置项
 * {@code enabled-event-categories} 的取值就是它的名字）。类别与类型的关系是
 * 一对多：一个类别下挂若干个 {@link CatalogEventType}，订阅了类别就等于订阅了
 * 该类别当前与将来的全部类型——这是它相对「逐个列类型」的价值：新增事件类型时，
 * 只关心「所有权限变更」的监听器不用改配置。
 *
 * <p>与 Polaris 的差异：Polaris 把「给 catalog role 加授权」归在
 * {@code CATALOG_ROLE} 类别下，没有独立的授权类别。本工程只有**一套**权限模型
 * （官方 {@code (resource, access, principal)} 三元组），catalog role 已整体移除，
 * 因此没有 {@code CATALOG_ROLE} 类别；授权变更统一落在 {@link #GRANT}。
 * {@link #OPTIMIZATION} 是本地扩展（Polaris 无对应物）。
 */
public enum CatalogEventCategory {

    /** catalog 自身（建目录、改配置、删目录）。 */
    CATALOG,

    /** 库 / namespace（建库、改属性、删库）。 */
    NAMESPACE,

    /** 表（建表、注册、删表、注销、改名、提交）。 */
    TABLE,

    /** 视图（建视图、改定义、删视图、改名）。 */
    VIEW,

    /** 授权（授予 / 撤销一条 {@code (resource, access, principal)}）。 */
    GRANT,

    /** 策略（当前只有表存储优化策略）。 */
    POLICY,

    /** 主体（账号的增删改、凭据轮换、角色授予）。 */
    PRINCIPAL,

    /** 主体角色（角色自身的增删改；它只是分组，不携带权限）。 */
    PRINCIPAL_ROLE,

    /** 存储优化（策略变更与批次终态）。 */
    OPTIMIZATION
}
