package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「加载更多」的游标：**无状态、带签名、可自解释**。
 *
 * <p><b>为什么不用 offset 翻页。</b>预览是流式读：Parquet 没有便宜的"跳到第 200 行"，
 * 只能读出来丢掉。offset 方案下第 N 页的代价随页数线性增长，用户看到的只是"越翻越慢"。
 * 游标记的是"上一个 split 的第几行"，续读时重新 {@code plan()}（只读清单，几 ms）
 * 定位过去，最坏代价是一页行数（≤ 200）。
 *
 * <p><b>为什么服务端不存会话。</b>重启、多实例、滚动发布都不该让用户手里的游标失效——
 * 存了就得考虑过期清理、实例间共享、发布顺序。签名把"内容没被改过"这件事交给
 * 密码学，服务端于是完全无状态。
 *
 * <p><b>{@code p}（解析后的分区值）与 {@code l}（label）为什么要进游标。</b>{@code auto}
 * 只在第一页解析一次：第二页若重新解析，期间有新分区提交就会指到别的分区，而
 * {@code r} 是**旧分区**里已消费的行数，在新分区上 {@code skip(r)} 会跳错位置——
 * 行错位且不报错。把解析结果与展示文本一起固化，第二页读的就一定是第一页那一段。
 *
 * <p><b>{@code f} 是条件摘要，不是条件本身。</b>过滤条件与投影列由前端每页重发
 * （否则服务端没法重新判定行），摘要用来发现"条件被换了、游标却是旧的"。
 * 摘的是**解析后**的分区值而不是请求里的 {@code mode}：{@code mode=auto} 在不同时刻
 * 指向不同分区，拿它做摘要等于没摘。
 *
 * <p>格式：{@code base64url(payload) + "." + base64url(HMAC-SHA256 前 16 字节)}，
 * payload 是 {@code k=v&k=v} 形式的表单串（值逐个 URL 编码）。选它而不是 JSON 是为了
 * 排障时解一层 base64 就能读懂，且不引额外的序列化依赖。
 */
@Component
public class PreviewCursorCodec {

    private static final Logger log = LoggerFactory.getLogger(PreviewCursorCodec.class);

    private static final String ALGORITHM = "HmacSHA256";

    /** 截断到 16 字节：游标是短命的一次性凭据，128 位足以防伪造，也让它短到能放进 URL。 */
    private static final int SIGNATURE_BYTES = 16;

    private final byte[] signingKey;

    public PreviewCursorCodec(RestServerProperties properties) {
        this.signingKey = resolveSigningKey(properties);
    }

    /**
     * 游标的内容。
     *
     * @param snapshotId  本次预览锁定的快照；空表为 {@code null}
     * @param splitOffset 下一页从第几个 split 开始
     * @param splitRows   在该 split 内已经消费掉的行数（**含**被过滤掉、被 skip 掉的行）
     * @param fingerprint 条件摘要，见 {@link #fingerprint}
     * @param resolved    分区解析结果（{@code auto} 在第一页固化）
     * @param mode        请求里的分区模式，回显用
     * @param auto        分区是不是服务端替用户挑的
     * @param depth       自动锁了几级
     * @param label       分区条件的展示文本，回显用
     */
    public record Cursor(
            Long snapshotId,
            int splitOffset,
            long splitRows,
            String fingerprint,
            List<PreviewDtos.PartitionValue> resolved,
            String mode,
            boolean auto,
            int depth,
            String label) {
    }

    // ------------------------------------------------------------------ 编解码

    /** 编码成一个不透明串。 */
    public String encode(Cursor cursor) {
        StringBuilder payload = new StringBuilder(160);
        append(payload, "s", cursor.snapshotId() == null ? "" : String.valueOf(cursor.snapshotId()));
        append(payload, "i", String.valueOf(cursor.splitOffset()));
        append(payload, "r", String.valueOf(cursor.splitRows()));
        append(payload, "f", cursor.fingerprint());
        append(payload, "m", cursor.mode());
        append(payload, "a", cursor.auto() ? "1" : "0");
        append(payload, "d", String.valueOf(cursor.depth()));
        if (cursor.label() != null) {
            append(payload, "l", cursor.label());
        }
        for (PreviewDtos.PartitionValue value : cursor.resolved()) {
            // 三段各自编码后再用 ':' 连接：编码后的文本不含裸 ':'，分隔因此无歧义。
            // 整段**不再**编码（否则 %3A 会被再编码一次，解出来是转义串而不是值）
            appendRaw(payload, "p", escape(value.field()) + ":" + escape(value.transform())
                    + ":" + escape(value.value()));
        }
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        return base64(body) + "." + base64(sign(body));
    }

    /**
     * 解码并验签；空串返回 {@code null}（调用方据此判断"这是第一页"）。
     *
     * @throws ApiException 400：格式不对 / 签名不符 / 内容缺字段。此时正确的处理是
     *                      让用户重新加载第一页，而不是猜一个位置继续读
     */
    public Cursor decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int dot = text.lastIndexOf('.');
        if (dot <= 0 || dot == text.length() - 1) {
            throw invalid("not a preview cursor");
        }
        byte[] body;
        byte[] signature;
        try {
            body = Base64.getUrlDecoder().decode(text.substring(0, dot));
            signature = Base64.getUrlDecoder().decode(text.substring(dot + 1));
        } catch (IllegalArgumentException exception) {
            throw invalid("not base64url");
        }
        if (!MessageDigest.isEqual(sign(body), signature)) {
            // 换了分区、改了条件、换了实例都可能走到这里；对用户是同一件事：重新取第一页
            throw invalid("the signature does not match (the cursor was issued by another key"
                    + " material, or its content was changed)");
        }
        Map<String, List<String>> params = parse(new String(body, StandardCharsets.UTF_8));
        List<PreviewDtos.PartitionValue> resolved = new ArrayList<>();
        for (String triple : values(params, "p")) {
            // 先按 ':' 切**再**逐段解码：反过来（先解码再切）会让值里的 ':' 参与分割，
            // 把一个分区值切成两段
            String[] parts = triple.split(":", 3);
            if (parts.length == 3) {
                resolved.add(new PreviewDtos.PartitionValue(
                        unescape(parts[0]), unescape(parts[1]), unescape(parts[2])));
            }
        }
        return new Cursor(
                optionalLong(params, "s"),
                (int) requiredLong(params, "i"),
                requiredLong(params, "r"),
                required(params, "f"),
                List.copyOf(resolved),
                params.containsKey("m") ? required(params, "m") : "auto",
                "1".equals(required(params, "a")),
                (int) requiredLong(params, "d"),
                params.containsKey("l") ? required(params, "l") : null);
    }

    // ------------------------------------------------------------------ 条件摘要

    /**
     * 过滤条件 + 投影列 + **解析后的**分区值的摘要（8 字节十六进制）。
     *
     * <p>值前面带上类型前缀（{@code s:} / {@code n:} / {@code b:}）：{@code 1} 与
     * {@code "1"} 在 JSON 里是两种值，摘出来必须也是两种，否则两个语义不同的请求
     * 会被认成同一个。
     */
    public static String fingerprint(List<ColumnFilter> filters, List<String> columns,
                                     List<PreviewDtos.PartitionValue> resolved) {
        StringBuilder canonical = new StringBuilder();
        for (String column : columns) {
            canonical.append("c=").append(column).append('\u0001');
        }
        if (filters != null) {
            for (ColumnFilter filter : filters) {
                if (filter == null) {
                    continue;
                }
                canonical.append("f=").append(filter.column()).append(':').append(filter.op())
                        .append(':').append(typed(filter.value())).append('\u0001');
            }
        }
        if (resolved != null) {
            for (PreviewDtos.PartitionValue value : resolved) {
                canonical.append("p=").append(value.field()).append(':').append(value.transform())
                        .append(':').append(value.value()).append('\u0001');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行环境坏了
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String typed(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return "n:" + value;
        }
        if (value instanceof Boolean) {
            return "b:" + value;
        }
        return "s:" + value;
    }

    // ------------------------------------------------------------------ 内部

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, ALGORITHM));
            byte[] full = mac.doFinal(body);
            byte[] truncated = new byte[SIGNATURE_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIGNATURE_BYTES);
            return truncated;
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
            throw new IllegalStateException("cannot sign the preview cursor", exception);
        }
    }

    private static void append(StringBuilder payload, String key, String value) {
        appendRaw(payload, key, escape(value));
    }

    /** 已经把值编码好的那一类（多段拼接），再加一层编码会把 {@code %} 变成 {@code %25}。 */
    private static void appendRaw(StringBuilder payload, String key, String rawValue) {
        if (payload.length() > 0) {
            payload.append('&');
        }
        payload.append(key).append('=').append(rawValue);
    }

    private static String escape(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 解析成"键 → 原始（未解码）值"。解码留给使用点：{@code p} 这种"编码后拼接"的
     * 值必须先按分隔符切开再逐段解码，整体解码会把分隔符一起解出来。
     */
    private static Map<String, List<String>> parse(String payload) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (String pair : payload.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw invalid("malformed field '" + pair + "'");
            }
            params.computeIfAbsent(pair.substring(0, equals), key -> new ArrayList<>())
                    .add(pair.substring(equals + 1));
        }
        return params;
    }

    private static List<String> values(Map<String, List<String>> params, String key) {
        return params.getOrDefault(key, List.of());
    }

    private static String required(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            throw invalid("missing '" + key + "'");
        }
        return unescape(values.get(0));
    }

    private static long requiredLong(Map<String, List<String>> params, String key) {
        try {
            return Long.parseLong(required(params, key));
        } catch (NumberFormatException exception) {
            throw invalid("'" + key + "' is not a number");
        }
    }

    private static Long optionalLong(Map<String, List<String>> params, String key) {
        String value = required(params, key);
        return value.isEmpty() ? null : Long.valueOf(value);
    }

    private static ApiException invalid(String reason) {
        return ApiException.badRequest("Invalid preview cursor: " + reason
                + "; drop the cursor and load the first page again");
    }

    /**
     * 游标签名用的密钥。
     *
     * <p>复用访问令牌的密钥材料（{@code lakehouse.rest.auth.access-token.signing-key}），
     * 不新造一套：多一个密钥就多一处轮换、多一处配置说明，而这两者的安全等级在这里
     * 没有区别——都是"防篡改的完整性校验"，不是"加密"。
     *
     * <p>没配时退回本进程随机密钥并告警：单实例本地调试仍然可用，代价是重启后
     * 用户手里的游标失效（表现为 400「cursor 无效，请重新加载第一页」，不是错误的数据）。
     */
    private static byte[] resolveSigningKey(RestServerProperties properties) {
        String configured = properties.getAuth().getAccessToken().getSigningKey();
        if (configured != null && !configured.isBlank()) {
            try {
                return Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "lakehouse.rest.auth.access-token.signing-key must be base64 (generate one with:"
                                + " openssl rand -base64 48)", exception);
            }
        }
        byte[] generated = new byte[48];
        new SecureRandom().nextBytes(generated);
        log.warn("lakehouse.rest.auth.access-token.signing-key is not set: preview cursors are signed"
                + " with a random per-process key. Cursors stop working after a restart and are not"
                + " accepted across instances (the user just reloads the first page).");
        return generated;
    }
}
