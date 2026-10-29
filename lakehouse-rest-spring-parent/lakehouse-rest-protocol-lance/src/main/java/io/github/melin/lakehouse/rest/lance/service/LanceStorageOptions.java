package io.github.melin.lakehouse.rest.lance.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把共享凭据层产出的 {@code s3.*} 键译成 Lance 客户端真正认的 {@code aws_*} 键。
 *
 * <p><b>为什么必须在协议面翻译，而不是让凭据层直接发两套名字。</b>
 * Lance 的 {@code storage_options} 是一条独立协议面，键名由它的 Rust 侧
 * （{@code object_store}）决定；Paimon 与 Iceberg 的 {@code s3.*} 由 Java 侧的
 * FileIO 决定。让共享层同时发 {@code s3.*} 与 {@code aws_*} 的后果是：
 * 把 {@code aws_access_key_id} 灌进 Paimon 的 options，而它不认这个键——
 * 轻则日志里一堆未知属性警告，重则某些实现「未知键即报错」。
 * 翻译放在协议面，共享层只负责「签出什么」，各面负责「怎么叫」。
 *
 * <p><b>为什么以前是错的。</b>Lance 侧一直原样透传 {@code s3.*}，而客户端只认
 * {@code aws_access_key_id} 一族，于是**下发了但客户端没认**——表现为仍然要手写
 * 密钥进配置，且不报任何错（设计稿 G12，键名由 Gravitino #10895 的
 * {@code CredentialPropertyUtils} 反证）。
 *
 * <p><b>不认识的键原样保留。</b>{@code FILE} 存储的 token 里是
 * {@code accessKeyId} / {@code expiration} 这类自包含字段，与对象存储无关；
 * 只挑 {@code s3.} 前缀翻译、其余透传，才不会把本地文件系统的行为一起改掉。
 */
public final class LanceStorageOptions {

    private LanceStorageOptions() {
    }

    /** object_store 认的键名。 */
    public static final String KEY_ACCESS = "aws_access_key_id";

    public static final String KEY_SECRET = "aws_secret_access_key";

    public static final String KEY_SESSION = "aws_session_token";

    public static final String KEY_REGION = "aws_region";

    public static final String KEY_ENDPOINT = "aws_endpoint";

    /** 允许明文 HTTP 端点；私有化部署的对象存储常见。 */
    public static final String KEY_ALLOW_HTTP = "allow_http";

    /**
     * 翻译一份凭据映射。
     *
     * <p>顺序稳定（{@link LinkedHashMap}）：{@code storage_options} 会被序列化进响应体，
     * 键序漂移会让「内容没变」的改动看起来像改动，也会让快照测试无故失败。
     */
    public static Map<String, String> fromS3(Map<String, String> token) {
        Map<String, String> options = new LinkedHashMap<>();
        if (token == null) {
            return options;
        }
        for (Map.Entry<String, String> entry : token.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            switch (key) {
                // 规格键名与 Paimon 引擎键名都会出现，两套都要认：凭据层为了兼容
                // Paimon 会同时下发 s3.access-key-id 与 s3.access-key，落到这里
                // 是同义的两把钥匙，取哪个都对。
                case "s3.access-key-id", "s3.access-key" -> options.putIfAbsent(KEY_ACCESS, value);
                case "s3.secret-access-key", "s3.secret-key" -> options.putIfAbsent(KEY_SECRET, value);
                case "s3.session-token" -> options.put(KEY_SESSION, value);
                case "s3.region" -> options.put(KEY_REGION, value);
                case "s3.endpoint" -> {
                    options.put(KEY_ENDPOINT, value);
                    if (value.startsWith("http://")) {
                        // 明文端点不显式放行时，object_store 会以 TLS 失败收场，
                        // 而错误信息指向证书而不是「你少了一个开关」。
                        options.put(KEY_ALLOW_HTTP, "true");
                    }
                }
                // 其余键（含 file:// 那套自包含字段、以及未来的新键）原样透传：
                // 这里是一张白名单，漏掉一个键的后果是静默丢配置，比多带一个键严重。
                default -> options.putIfAbsent(key, value);
            }
        }
        return options;
    }
}
