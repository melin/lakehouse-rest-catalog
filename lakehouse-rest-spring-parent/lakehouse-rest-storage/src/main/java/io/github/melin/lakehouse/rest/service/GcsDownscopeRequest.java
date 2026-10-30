package io.github.melin.lakehouse.rest.service;

/**
 * 一次 GCS {@code DownscopedCredentials} 交换的输入。
 *
 * <p>只有三样东西：源令牌、boundary、端点。比起 S3 那边少了「角色」——
 * GCS 的模型是「用小权限换更小权限」，没有第三个身份可以 assume，
 * 这让它比另外两家简单：**没有角色就不需要配置角色**，能力由源凭据自带的
 * {@code roles/iam.serviceAccountTokenCreator} 决定。
 *
 * @param subjectToken 源访问令牌（就是这个部署现在会下发的那个）
 * @param options      {@code accessBoundary} 的 JSON 字符串，见 {@link GcsBoundary}
 * @param endpoint     STS 端点，默认 {@code https://sts.googleapis.com}
 */
public record GcsDownscopeRequest(String subjectToken, String options, String endpoint) {
}
