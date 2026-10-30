package io.github.melin.lakehouse.rest.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 用用户委托密钥签一张 SAS，并把查询串拼好。
 *
 * <p>这一段没有任何远程调用，全部是字符串与 HMAC——恰恰因此它是最容易写错、
 * 又最难在测试之外发现的一段：签错了不会抛异常，浏览器/引擎拿到的是一个
 * {@code AuthenticationFailed: Signature did not match}，错误信息与「密钥不对」
 * 「权限不够」「路径写错」全都长得一样。
 *
 * <p>所以这里的取舍是：**按官方文档的字段顺序原样摆开，一个字段都不省**。
 * 可选字段一律写成空串并保留它后面的换行——少写一行会让后面所有字段整体前移一位，
 * 而签名仍然算得出来（HMAC 不关心含义），只在服务端校验失败。
 *
 * <p><b>版本固定 {@value #VERSION}，不用 {@code 2022-11-02} 之类的新版本。</b>
 * 版本号同时出现在三处：{@code sv}、委托密钥的 {@code skv}、以及签名串的字段表。
 * 越新的版本会增加字段（{@code srh} / {@code srq} / {@code sduoid}），
 * 而多一个字段就多一处与文档对齐的工作；{@code 2020-12-06} 是第一个同时支持
 * 「目录级作用域」与「加密范围」的版本，满足本工程需要的最小集合。
 *
 * <p>字段来源：{@code Create a user delegation SAS}（Azure Storage REST API）里
 * "Version 2020-12-06 and later" 那一份模板。{@code sdd} **不在**签名串里——
 * 它只进查询串，通过「规范化资源必须包含对应层数的子目录」约束签名的作用面。
 */
public final class AzureSasToken {

    /** SAS 的服务版本，同时用作 {@code sv} 与委托密钥的 {@code skv}。 */
    public static final String VERSION = "2020-12-06";

    /** SAS 起止时间的格式（ISO 8601 UTC，秒精度）。 */
    public static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    private AzureSasToken() {
    }

    /**
     * 只读用 {@code rl}，写用 {@code racwdl}。
     *
     * <p>顺序是 Azure 的硬要求（{@code racwdxltmeop} 的相对次序），**不是**随便排的：
     * 顺序不同的 {@code sp} 会让签名对不上。所以这里按序拼，不接受调用方传字符串——
     * 一个「顺手写成 {@code wracdl}」的调用点就足以让整条链失效。
     *
     * <p>只读多给一个 {@code l}（list）：列目录是读表的一部分，不给它时引擎在
     * {@code listStatus} 那一步 403，而报错指向的是一次与「读文件」无关的调用。
     * {@code c}（create）只在写时要，它的缺失表现是「能覆盖已有文件、不能新建」，
     * 比整体 403 更难看出。
     */
    public static String permissions(boolean allowWrite) {
        return allowWrite ? "racwdl" : "rl";
    }

    /**
     * 拼出 SAS 查询串（不含前导 {@code ?}）。
     *
     * @param location    位置：决定容器、账户、规范化资源与 {@code sdd}
     * @param permissions {@link #permissions} 的结果
     * @param start       SAS 生效时刻，格式见 {@link #TIME}
     * @param expiry      SAS 失效时刻；必须落在委托密钥的生命周期内
     * @param key         换回来的委托密钥
     */
    public static String build(AzureLocation location,
                               String permissions,
                               String start,
                               String expiry,
                               UserDelegationKey key) {
        // 目录级还是容器级由位置自己决定（见 {@link AzureLocation#directory()}）：
        // flat 命名空间没有目录，容器根也没有「目录」可指，两种情况都只能 sr=c。
        boolean directory = location.directory();
        String resource = directory ? "d" : "c";
        String protocol = location.tls() ? "https" : "https,http";

        String stringToSign = String.join("\n",
                permissions,                                   // sp
                start,                                         // st
                expiry,                                        // se
                location.sasResource(),                        // 规范化资源
                key.signedOid(),                               // skoid
                key.signedTid(),                               // sktid
                key.signedStart(),                             // skt
                key.signedExpiry(),                            // ske
                key.signedService(),                           // sks
                key.signedVersion(),                           // skv
                "",                                            // saoid
                "",                                            // suoid
                "",                                            // scid
                "",                                            // sip
                protocol,                                      // spr
                VERSION,                                       // sv
                resource,                                      // sr
                "",                                            // sst
                "",                                            // ses
                "",                                            // rscc
                "",                                            // rscd
                "",                                            // rsce
                "",                                            // rscl
                "");                                           // rsct

        StringBuilder query = new StringBuilder();
        append(query, "sp", permissions);
        append(query, "st", start);
        append(query, "se", expiry);
        append(query, "skoid", key.signedOid());
        append(query, "sktid", key.signedTid());
        append(query, "skt", key.signedStart());
        append(query, "ske", key.signedExpiry());
        append(query, "sks", key.signedService());
        append(query, "skv", key.signedVersion());
        append(query, "spr", protocol);
        append(query, "sv", VERSION);
        append(query, "sr", resource);
        if (directory) {
            // 只在目录级出现。容器级带上它会让服务端在解析时直接拒（不必要的参数）
            append(query, "sdd", Integer.toString(location.depth()));
        }
        append(query, "sig", sign(stringToSign, key.value()));
        return query.toString();
    }

    /** HMAC-SHA256 后 base64；密钥是服务端返回的 base64 值，要先解码。 */
    private static String sign(String stringToSign, String base64Key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            // 只可能是 HmacSHA256 不在本 JVM 里（标准算法，实际不会发生），
            // 或者密钥不是合法 base64（说明上一步的响应解析坏了）。都不该被吞掉。
            throw new IllegalStateException("cannot sign the Azure SAS: " + failure.getMessage(), failure);
        }
    }

    /**
     * 追加一个已编码的键值对。
     *
     * <p>值一律按 RFC 3986 百分号编码：base64 的签名里有 {@code +} 与 {@code =}，
     * {@code spr} 里有逗号，时间戳里有冒号。**用 {@code URLEncoder} 是错的**——
     * 它把空格编成 {@code +}，而查询串里的 {@code +} 会被解成空格，
     * 签名因此对不上（这是 SAS 排障里最常见的一类）。
     */
    private static void append(StringBuilder query, String name, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(name).append('=').append(encode(value));
    }

    /** RFC 3986 的 unreserved 集合之外一律百分号编码（UTF-8 字节）。 */
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }
}
