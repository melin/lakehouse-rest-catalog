package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 换一张用户委托密钥要传下去的东西：签哪个账户、用哪个身份、要多久。
 *
 * <p>做成一个 record 而不是五个参数，唯一的原因是它要能被假实现记录下来做断言——
 * 「服务端到底有没有按 catalog 的租户去签」这类问题，只能靠看请求本身回答。
 *
 * <p><b>{@code start} 要比 SAS 的生效时刻更早。</b>签名时 {@code skt} 写的是密钥的
 * 生命周期起点，而 {@code st} 是 SAS 自己的起点；密钥的窗口必须**包住** SAS，
 * 否则一批时钟稍快的机器会在 SAS 生效前就用它，回
 * {@code AuthenticationFailed}（报错里只说签名不对）。调用方因此把 {@code start}
 * 往前放几分钟，这里不做假设。
 *
 * <p>{@code tenantId} 来自 catalog（规格里有 {@code tenantId} 字段），缺省才用配置里的；
 * 前者决定「这份数据属于哪个租户」，后者是「服务端平时在哪个租户里」——两者在多租户
 * 部署里不是一回事。
 */
public record AzureDelegationRequest(String account,
                                     String blobHost,
                                     String tenantId,
                                     String clientId,
                                     String clientSecret,
                                     String authority,
                                     String scope,
                                     Instant start,
                                     Instant expiry) {
}
