package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 换票客户端：拿服务账号换一个 access token。
 *
 * <p>与 {@link GcsDownscopeClient} 分成两个接口，是因为它们在失败时的含义不同：
 * 换不到**源令牌**说明 catalog 上那份服务账号密钥有问题（或没网），此时应当报错；
 * 换不到**收窄后的令牌**可以是「这份 boundary 规则不够/太多」，此时退回更保守的行为。
 * 合成一个接口会让调用点无法区分这两种情况。
 */
public interface GcsAccessTokenClient {

    GcsAccessToken mint(GcsServiceAccount account);
}
