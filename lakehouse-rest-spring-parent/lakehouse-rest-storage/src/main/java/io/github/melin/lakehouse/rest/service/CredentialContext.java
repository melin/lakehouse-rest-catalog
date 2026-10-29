package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;

/**
 * 一次签发请求的上下文：给谁、为哪张表、要不要写。
 *
 * <p>比 {@code vend(storage, tableId, tablePath)} 多两样东西，都有明确用途：
 * <ul>
 *   <li>{@code principal} 进 {@code RoleSessionName}。云侧审计日志里能直接看出
 *       「谁读了哪张表」，成本为零；不做的唯一理由是忘了。注意**不要把敏感元数据
 *       塞进 session name 或 session tag**——它们会进云侧审计日志，也可能被 ABAC
 *       策略引用。</li>
 *   <li>{@code allowWrite} 决定 {@link CredentialScope} 的写路径是否非空。
 *       只读请求不签写权限是收窄里最便宜的一档，也最容易被忽略。</li>
 * </ul>
 *
 * <p>目前所有调用点都按 {@code allowWrite=false} 发起：读路径与写路径在协议上
 * 分不开（{@code loadTable} 之后客户端既读也写）。这里先把位置留出来，
 * 等确认了「写请求」的判据再启用，而不是先放开写权限。
 */
public record CredentialContext(AwsStorageConfigInfo storage,
                                String tableId,
                                String tablePath,
                                String principal,
                                boolean allowWrite) {

    /** 作用域；表路径认不出来时为 {@code null}。 */
    public CredentialScope scope() {
        return CredentialScope.resolve(tablePath, allowWrite);
    }

    /**
     * {@code RoleSessionName}：{@code lh-<principal>-<tableId>}，按 AWS 的字符集规整。
     *
     * <p>规整而不是直接拼：STS 对 session name 的约束是
     * {@code [\w+=,.@-]{2,64}}，主体标识里常见的 {@code /}、{@code :}、空格都在集合外，
     * 直接拼会在签名阶段以 {@code ValidationError} 失败——一个看起来与授权毫无关系的错。
     * 超长时保留尾部（表 id 更有辨识度），并保证至少 2 个字符。
     */
    public String roleSessionName() {
        String name = "lh-" + (principal == null ? "service" : principal) + "-" + tableId;
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = c == '_' || c == '+' || c == '=' || c == ',' || c == '.'
                    || c == '@' || c == '-' || Character.isLetterOrDigit(c);
            sanitized.append(allowed ? c : '-');
        }
        String value = sanitized.toString();
        if (value.length() > 64) {
            value = value.substring(value.length() - 64);
        }
        return value.length() < 2 ? "lh" : value;
    }
}
