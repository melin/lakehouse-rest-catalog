package io.github.melin.lakehouse.rest.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一次凭据签发的作用域：这张表在对象存储里到底占哪几个前缀。
 *
 * <p>它是「整桶凭据」与「按表凭据」之间唯一的分界线。在它出现之前，
 * {@code vend(storage, tableId, tablePath)} 收了 {@code tablePath} 却只有本地文件系统
 * 那一支用到了它——对象存储的几个分支都是直接从配置里取密钥、原样转发，
 * 于是**一张表的凭据能读整个仓库**（设计稿 G2）。STS 的 session policy 是唯一
 * 能真正收窄的地方：它只能与角色自身策略取交集，永远不会放大权限。
 *
 * <p><b>三组路径的用途严格分开，混用会踩两个坑：</b>
 * <ul>
 *   <li>{@code readPrefixes} 拼进 {@code s3:GetObject} 的 {@code Resource}；
 *   <li>{@code listPrefixes} 只能进 {@code s3:ListBucket} 的 {@code Condition}
 *       （{@code s3:prefix}）——{@code ListBucket} 的 {@code Resource} 是 **bucket**，
 *       把前缀拼进 ARN 会得到一个永远不匹配、也不报错的策略；
 *   <li>{@code writePrefixes} 只在客户端要写时才非空。
 * </ul>
 *
 * <p><b>为什么 {@code GetBucketLocation} 也在清单里。</b>只给 GetObject + ListBucket 时，
 * 部分客户端 SDK 会在初始化阶段报一个与权限完全不像的错（区域探测失败被包装成
 * 「无法连接」）。它的成本为零，漏了却很难查。
 *
 * <p><b>目前只从表 location 派生一组路径。</b>表可以在属性里另写
 * {@code write.data.path} / {@code write.metadata.path}，那两个位置也在读取范围内；
 * 但下发入口只拿得到 location，跨路径归一（Gravitino #13928 的做法：等价写法归一、
 * 父级路径合成一张目录级票）需要调用方把属性一并传进来，属于后续项。
 * 接口按「多组路径」设计，加的时候不用改签名。
 */
public record CredentialScope(String bucket,
                              List<String> readPrefixes,
                              List<String> listPrefixes,
                              List<String> writePrefixes) {

    public CredentialScope {
        readPrefixes = List.copyOf(readPrefixes);
        listPrefixes = List.copyOf(listPrefixes);
        writePrefixes = List.copyOf(writePrefixes);
    }

    /**
     * 从表路径派生作用域。
     *
     * <p>{@code s3://bucket/wh/db.db/t} → bucket {@code bucket}、前缀 {@code wh/db.db/t}。
     * 只认 {@code scheme://authority/path} 这一种形状：认不出来的地址返回 {@code null}，
     * 由调用方决定是「不收窄、整桶」还是「拒绝签发」。**不猜**——一个猜出来的桶名
     * 会让策略看起来生效、实际放行了别处。
     *
     * @param location 表的存储位置，可为 {@code null}
     * @param allowWrite 客户端这次是否需要写权限
     */
    public static CredentialScope resolve(String location, boolean allowWrite) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String rest = trimmed.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        if (bucket.isBlank()) {
            return null;
        }
        String prefix = slash < 0 ? "" : trimSlashes(rest.substring(slash));
        List<String> read = List.of(prefix);
        // 桶根：前缀为空时 ListBucket 的条件要写成 "*"，写空串在 StringLike 里
        // 只能匹配到「空前缀」，与实际想要「整桶可列」不是同一件事。
        List<String> list = List.of(prefix.isEmpty() ? "*" : prefix, prefix.isEmpty() ? "*" : prefix + "/*");
        List<String> write = allowWrite ? List.of(prefix) : List.of();
        return new CredentialScope(bucket, read, distinct(list), write);
    }

    /**
     * 从一组位置派生作用域（catalog 级票用）。
     *
     * <p><b>为什么服务端自己取票要按一组位置而不是一张表。</b>表级票只覆盖
     * {@code s3://wh/db/t} 这一个前缀，而服务端要做的动作天然跨表：浏览仓库目录、
     * 删一张表的整个目录、按快照清单校验文件是否存在。给这些动作签表级票的表现是
     * 「列目录 403」，而报错指向存储、与 catalog 无关。
     *
     * <p><b>认不出来的位置直接跳过，不报错。</b>{@code allowedLocations} 是用户填的，
     * 里面可能混着 {@code file:///...}（本地测试）或写错的 scheme；一个认不出的条目
     * 不该让整张票签不出来。但**全都认不出时返回空列表**，由调用方决定拒绝签发——
     * 空作用域等于「不收窄」，静默放行比报错危险。
     *
     * <p>同一个桶下的多个前缀会合并进同一条语句（见 {@link S3SessionPolicy}），
     * 这里只负责按 {@code bucket + prefix} 去重，不去动 bucket 的数量：
     * 跨桶的 catalog 合法，S3 策略也能表达，别在解析阶段就把它判掉。
     *
     * @param locations  位置列表，可为 {@code null}
     * @param allowWrite 这次是否需要写权限
     */
    public static List<CredentialScope> resolveAll(List<String> locations, boolean allowWrite) {
        if (locations == null || locations.isEmpty()) {
            return List.of();
        }
        Map<String, CredentialScope> byPrefix = new LinkedHashMap<>();
        for (String location : locations) {
            CredentialScope scope = resolve(location, allowWrite);
            if (scope == null) {
                continue;
            }
            // 用 \u0000 当分隔符：合法 URI 里不会出现它，而 '|' 会
            byPrefix.putIfAbsent(scope.bucket() + '\u0000' + scope.readPrefixes().get(0), scope);
        }
        return List.copyOf(byPrefix.values());
    }

    /** 是否落在桶根（前缀为空）。桶根无法用前缀收窄，签发方要说清楚。 */
    public boolean bucketRoot() {
        return readPrefixes.isEmpty() || readPrefixes.get(0).isEmpty();
    }

    /** 该前缀在 S3 策略里的对象级 ARN，形如 {@code arn:aws:s3:::bucket/wh/db/t/*}。 */
    public List<String> objectArns(List<String> prefixes) {
        List<String> arns = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            arns.add(bucketArn() + (prefix.isEmpty() ? "/*" : "/" + prefix + "/*"));
        }
        return arns;
    }

    /** bucket 级 ARN，{@code ListBucket} 的 {@code Resource} 用它。 */
    public String bucketArn() {
        return "arn:aws:s3:::" + bucket;
    }

    /**
     * 从任意 URI 里取出 {@code scheme://bucket/prefix} 的 bucket 部分。
     *
     * <p>独立于 {@link #resolve}：诊断信息里只要桶名，不需要构造整个作用域。
     */
    public static String bucketOf(String location) {
        if (location == null) {
            return null;
        }
        int schemeEnd = location.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String rest = location.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        return bucket.isBlank() ? null : bucket;
    }

    /** scheme 的小写形式；解析失败返回 {@code null}。 */
    public static String schemeOf(String location) {
        if (location == null) {
            return null;
        }
        int schemeEnd = location.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        return location.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
