package io.github.melin.lakehouse.rest.domain.entity;

/**
 * 主体从哪儿来。
 *
 * <p>这个字段存在的唯一目的是回答一个问题：<b>同名的主体能不能被外部身份接管</b>。
 * 一个 OIDC 用户的 {@code sub} 恰好等于某个本地主体名（例如 {@code root}）时，
 * 「按名字找到就复用」等于把那个主体的全部授权直接送出去——那是提权，不是便利。
 * 因此判定链上必须先看来源，再看外部标识（{@code docs/sso-catalog-identity-design.md} §3.2）。
 *
 * <p><b>存量行一律是 {@link #LOCAL}</b>：加列时的数据库默认值就是它，
 * 因此升级后行为逐字不变——没有 JIT、没有组同步、手工授权照旧。
 */
public enum PrincipalSource {

    /** 管理面手工创建的主体（含机器凭据）。 */
    LOCAL,

    /** 首次 SSO 登录时自动创建的主体（JIT）。没有 clientId / 密钥。 */
    OIDC
}
