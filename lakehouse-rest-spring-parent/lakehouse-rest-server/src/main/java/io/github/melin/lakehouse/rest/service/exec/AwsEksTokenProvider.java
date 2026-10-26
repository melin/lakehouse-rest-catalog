package io.github.melin.lakehouse.rest.service.exec;

import io.fabric8.kubernetes.client.OAuthTokenProvider;
import io.github.melin.lakehouse.rest.support.ProbeReason;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * EKS 的令牌：**用 SigV4 预签名的 STS {@code GetCallerIdentity} URL**，不是 AK/SK 直传。
 *
 * <p>EKS 的 apiserver 认的从来不是 AWS 的 AK/SK，而是 {@code aws eks get-token} 产出的那个串
 * （{@code k8s-aws-v1.} + base64url）。它的本质是一个签好名的 STS URL：EKS 的 authenticator
 * 拿它回查 STS，确认真的是你就放行。因此「用令牌认证」在这里等于本地签一个 URL——
 * 服务端不需要装 aws cli，也不需要 {@code exec:} 那种「执行本地命令」的能力
 * （那正是被硬拒的东西，见 {@code KubeconfigParser}）。
 *
 * <p><b>有效期 15 分钟，不缓存这个串。</b>fabric8 每次请求都会调 {@link #getToken()}，
 * 天然是「现签」；我们不再加一层缓存，否则一层过期令牌会让每次请求都 401 而不是重签。
 *
 * <p><b>两处必须与参考实现不同：</b>
 * <ol>
 *   <li>支持 {@code sessionToken}（{@link AwsSessionCredentials}）：STS 临时凭据是推荐形态，
 *       只支持长期密钥等于鼓励大家把长期密钥写进配置。</li>
 *   <li>失败抛**带结论**的异常：{@code InvalidClientTokenId} 要翻译成「密钥无效或已停用」，
 *       而不是把 AWS 的原文丢给用户。</li>
 * </ol>
 *
 * <p>中国区 STS 的域名是 {@code sts.<region>.amazonaws.com.cn}：区域以 {@code cn-} 开头时切换。
 * 用错域名与用错密钥的表现一样（都是 401/超时），因此这条判断放在这里而不是让用户填域名。
 */
public class AwsEksTokenProvider implements OAuthTokenProvider {

    /** EKS authenticator 靠这个头把令牌与集群对上；**集群名大小写敏感**。 */
    private static final String CLUSTER_ID_HEADER = "x-k8s-aws-id";

    private static final String TOKEN_PREFIX = "k8s-aws-v1.";

    private static final String STS_VERSION = "2011-06-15";

    /** 预签名 URL 的有效期。EKS 只接受 15 分钟以内，超过直接拒绝。 */
    private static final Duration TTL = Duration.ofMinutes(15);

    private static final Pattern ARN = Pattern.compile("<Arn>([^<]+)</Arn>");

    private static final Pattern ACCOUNT = Pattern.compile("<Account>([^<]+)</Account>");

    private static final Pattern ERROR_CODE = Pattern.compile("<Code>([^<]+)</Code>");

    private static final Pattern ERROR_MESSAGE = Pattern.compile("<Message>([^<]+)</Message>");

    private final String region;

    private final String clusterName;

    private final AwsCredentials credentials;

    public AwsEksTokenProvider(String region, String clusterName,
                               String accessKeyId, String secretAccessKey, String sessionToken) {
        this.region = region;
        this.clusterName = clusterName;
        this.credentials = sessionToken == null || sessionToken.isBlank()
                ? AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                : AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken.trim());
    }

    @Override
    public String getToken() {
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signedUrl().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 探活第 1 步：拿**同一个预签名 URL** 真去调一次 STS，换回调用者身份。
     *
     * <p>为什么用预签名 URL 而不是再写一套签名：这就是 EKS 自己要做的动作——
     * 它能通过，说明「签名能被 STS 认」，而这一步失败正是 401 的前置原因。
     * 顺带把 ARN 回显给用户，他能立刻认出「配的是哪个 IAM 身份」。
     */
    public CallerIdentity getCallerIdentity() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(signedUrl()))
                .timeout(Duration.ofSeconds(10))
                .header(CLUSTER_ID_HEADER, clusterName)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ExecutionProbeException.of(ProbeReason.NETWORK, "调用 STS 被中断", "重试一次");
        } catch (Exception failure) {
            throw new ExecutionProbeException(ProbeReason.NETWORK,
                    "连不上 sts." + region + "（" + failure.getClass().getSimpleName() + "）",
                    "服务端要能出网到 sts.<region>.amazonaws.com" + (region.startsWith("cn-") ? ".cn" : "")
                            + "；无公网出口的机房签得出令牌但换不到结果", failure);
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() == 200) {
            return new CallerIdentity(first(ARN, body), first(ACCOUNT, body));
        }
        String code = first(ERROR_CODE, body);
        String message = first(ERROR_MESSAGE, body);
        throw new ExecutionProbeException(ProbeReason.CREDENTIAL_INVALID,
                "sts:GetCallerIdentity 返回 " + (code == null ? "HTTP " + response.statusCode() : code)
                        + (message == null ? "" : "：" + message),
                conclude(code), null);
    }

    /** 把 STS 的错误码翻成「该去改什么」。这些结论是探活第 1 步的产出。 */
    private static String conclude(String code) {
        if (code == null) {
            return "确认访问密钥与区域是否正确";
        }
        return switch (code) {
            case "InvalidClientTokenId" -> "访问密钥无效或已停用：换一把属于优化任务专用用户的密钥";
            case "SignatureDoesNotMatch" -> "secretAccessKey 与 accessKeyId 不配套：重新粘贴整对密钥";
            case "ExpiredToken" -> "会话令牌（sessionToken）已过期：重新签发一份临时凭据";
            case "AccessDenied" -> "这个 IAM 身份没有 sts:GetCallerIdentity：补上这条权限（它不需要任何资源级授权）";
            case "InvalidClientTokenIdException" -> "访问密钥无效或已停用";
            default -> "按 STS 返回的错误码检查凭据与区域";
        };
    }

    /**
     * 签一个预签名 URL。
     *
     * <p>{@code x-k8s-aws-id} 必须参与签名（因此它在 {@code appendHeader} 里，而不是在
     * 真正发请求时才加）：EKS 会校验这个头与签名里的一致，只加头不签名会得到 401。
     */
    private String signedUrl() {
        String host = "sts." + region + (region.startsWith("cn-") ? ".amazonaws.com.cn" : ".amazonaws.com");
        SdkHttpFullRequest requestToSign = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("https://" + host))
                .appendHeader(CLUSTER_ID_HEADER, clusterName)
                .appendRawQueryParameter("Action", "GetCallerIdentity")
                .appendRawQueryParameter("Version", STS_VERSION)
                .encodedPath("/")
                .build();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Aws4PresignerParams params = Aws4PresignerParams.builder()
                .awsCredentials(credentials)
                .signingRegion(Region.of(region))
                .signingName("sts")
                .expirationTime(Instant.now().plus(TTL))
                .build();
        return Aws4Signer.create().presign(requestToSign, params).getUri().toString();
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 探活第 1 步回显的身份。{@code arn} 是 IAM 侧的标识，与 k8s 里的用户名不是一回事。 */
    public record CallerIdentity(String arn, String accountId) {
    }
}
