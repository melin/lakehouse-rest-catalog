package io.github.melin.lakehouse.rest.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 阿里云 RPC 风格接口（STS 就是这种）的签名：按 {@code SignatureVersion=1.0} 算 HMAC-SHA1。
 *
 * <p>这一段单独成类，理由是它**没有远程调用的成分**：入参是一张参数表与一把密钥，
 * 出参是一个字符串。把它放进 HTTP 客户端里，就只能靠「真调一次云」来验证；
 * 拎出来之后，「同一组参数一定得到同一个签名」「换个密钥签名一定变」这类断言
 * 可以在单测里跑。
 *
 * <p><b>三个容易写错、写错又都不报错的地方：</b>
 *
 * <ol>
 *   <li><b>编码必须用阿里那套 percentEncode</b>，不是 {@code URLEncoder}。标准
 *       {@code URLEncoder} 把空格变成 {@code +}、把 {@code ~} 变成 {@code %7E}，
 *       而阿里要求 {@code %20} 与字面的 {@code ~}。差一个字符，签名就对不上，
 *       而服务端只说 {@code SignatureDoesNotMatch}。</li>
 *   <li><b>待签串里的 {@code "/"} 要编码成 {@code %2F}</b>。它出现在 stringToSign
 *       的第二段，看起来像是个路径分隔符，其实是「被编码过的斜杠」。</li>
 *   <li><b>排序按原始键名的字典序</b>，不是按编码后的值。用 {@link TreeMap} 是为了
 *       把这条规则写进类型里——手写 {@code sort()} 的版本在后续有人加参数时
 *       很容易被改成「按插入顺序」。</li>
 * </ol>
 *
 * <p>签名是用 {@code AccessKeySecret + "&"} 当密钥的，那个结尾的 {@code &} 不是笔误：
 * 阿里云的签名算法沿用 OAuth 的「密钥 + &」约定。漏掉它得到的签名同样算得出来，
 * 只是永远不匹配。
 */
public final class AliyunRpcSignature {

    private AliyunRpcSignature() {
    }

    /**
     * 阿里云的 percentEncode（RFC 3986 的 unreserved 之外一律编码）。
     *
     * <p>{@code *} 在这里被编成 {@code %2A}、{@code ~} 保持原样——与
     * {@code AzureSasToken#encode} 的实现恰好一致，但不要去把两者合并：那两个函数
     * 服务的是两家云的文档，任何一家改了字符集（历史上都改过）时，
     * 合并会变成一次同时影响两条链的改动。
     */
    public static String percentEncode(String value) {
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

    /**
     * 规范化查询串：按参数名排序后逐个 {@code key=percentEncode(value)}，用 {@code &} 连接。
     *
     * <p>公共参数（{@code Action} / {@code Version} / {@code SignatureNonce} …）与业务参数
     * 一视同仁地进这张表——它们不排序时得到的串同样能算出签名，只是永远不匹配。
     */
    public static String canonicalQuery(Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(parameters).entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(percentEncode(entry.getKey())).append('=')
                    .append(percentEncode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return query.toString();
    }

    /** 待签串：{@code GET&%2F&<规范化查询串>}。GET 是本文档里唯一的调用方式。 */
    public static String stringToSign(String canonicalQuery) {
        return "GET&" + percentEncode("/") + "&" + percentEncode(canonicalQuery);
    }

    /**
     * 算出签名（base64）。
     *
     * @param accessKeySecret 阿里云的 AccessKeySecret
     * @param canonicalQuery  由 {@link #canonicalQuery} 产出的串
     */
    public static String sign(String accessKeySecret, String canonicalQuery) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1"));
            byte[] digest = mac.doFinal(stringToSign(canonicalQuery).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.GeneralSecurityException failure) {
            // HmacSHA1 是 JVM 标准算法，走到这里只可能是 getBytes 出了问题
            throw new IllegalStateException("cannot sign the Aliyun RPC request: "
                    + failure.getMessage(), failure);
        }
    }
}
