package io.github.melin.lakehouse.rest.domain.entity;

/**
 * 一条「主体 ← 主体角色」成员关系是谁给的。
 *
 * <p>组同步只被允许删除 {@link #IDP} 的行，手工授权永远不参与差集
 * （{@code docs/sso-catalog-identity-design.md} §5.2 不变量 1）。
 * 没有这一列时，IdP 组里少一个组就会把管理员手工授予的角色一起删掉——
 * 而现象是「某人昨天还能看，今天权限没了」，且没有任何日志说明是谁删的。
 *
 * <p><b>存量行一律是 {@link #MANUAL}</b>：加列时的默认值即此，升级后同步逻辑
 * 对既有授权完全不可见。
 */
public enum GrantSource {

    /** 管理面手工授予（{@code PUT /principals/{name}/principal-roles}）。 */
    MANUAL,

    /** 登录时按 IdP 组映射自动同步出来的。 */
    IDP
}
