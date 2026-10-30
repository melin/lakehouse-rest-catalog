package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.Tag;

/**
 * AWS STS 的真实实现。
 *
 * <p><b>「谁在签名」有两条路，由 command 决定。</b>catalog 上填了 AK/SK 时用那一对
 * （自建机房常见：一把只能 {@code sts:AssumeRole} 的钥匙，按 catalog 一把）；
 * 没填时交给 SDK 的默认链（环境变量、实例角色、IRSA 的 web identity）——
 * 那是部署自己的身份，不需要任何配置项。
 *
 * <p>两条路都只用来**换票**，不用来读写数据：服务端从不把这对钥匙当访问凭据用。
 * 目标态（设计 §5.7）是它只能 {@code AssumeRole}，那是运维侧的 IAM 配置。
 *
 * <p><b>客户端按「区域 + 端点」缓存。</b>STS 客户端内部有连接池与端点解析缓存，
 * 每次签发都新建一个会让长跑作业每秒创建若干个连接池，最终表现成句柄耗尽。
 * 缓存键把区域与自定义端点都算进去，因为同一份部署里可能同时存在公有云与私有端点
 * 的 catalog——只用区域当键会让后者的请求打到公网端点，报一个「凭据无效」的错，
 * 而真正的原因是端点选错了。
 *
 * <p>{@code DurationSeconds} 的下限是 STS 的硬约束（900 秒），越界时云侧回
 * {@code ValidationError}；这里提前夹住并说明原因，避免把一个配置问题推迟成一次
 * 远程调用的失败。
 *
 * <p><b>带 session tags 时有一个纯运维前提。</b>被 assume 的角色，其信任策略必须放行
 * {@code sts:TagSession}，否则带 tag 的调用回 {@code AccessDenied}——现象是
 * 「配置里什么都没改，读表全部 503 且报权限不足」。本类无法区分这种失败与真正的
 * 授权失败（云侧返回的 code 相同），所以只在日志里带上 session name；开关默认关闭、
 * 由配置说明写清前提，是唯一能避免「升级即故障」的做法。
 */
@Component
@RequiredArgsConstructor
public class AwsSdkAssumeRoleClient implements AssumeRoleClient {

    private static final Logger log = LoggerFactory.getLogger(AwsSdkAssumeRoleClient.class);

    /** STS 允许的最短会话时长。比它短，云侧直接拒绝。 */
    static final long MIN_DURATION_SECONDS = 900L;

    /** STS 允许的最长会话时长（角色自身的 {@code MaxSessionDuration} 可能更短）。 */
    static final long MAX_DURATION_SECONDS = 43200L;

    private final Map<String, StsClient> clients = new ConcurrentHashMap<>();

    @Override
    public StsSession assumeRole(AssumeRoleCommand command) {
        if (command.roleArn() == null || command.roleArn().isBlank()) {
            throw new ApiException(500, null, null,
                    "cannot assume a role without roleArn; set storageConfigInfo.roleArn on the catalog");
        }
        long seconds = clampDuration(command.duration());
        AssumeRoleRequest.Builder request = AssumeRoleRequest.builder()
                .roleArn(command.roleArn())
                .roleSessionName(command.roleSessionName())
                .durationSeconds((int) seconds)
                .policy(command.policy())
                .externalId(command.externalId());
        if (!command.tags().isEmpty()) {
            // 只有真要发的时候才带 tags：传空列表与不传在云侧等价，但「不传」能让
            // 关闭该特性时的请求与改造前逐字节相同，抓包比对因此还有意义。
            request.tags(command.tags().entrySet().stream()
                    .map(tag -> Tag.builder().key(tag.getKey()).value(tag.getValue()).build())
                    .toList());
        }
        try {
            Credentials credentials = clientFor(command).assumeRole(request.build()).credentials();
            if (credentials == null) {
                throw new ApiException(503, null, null,
                        "STS returned no credentials for role " + command.roleArn());
            }
            return new StsSession(credentials.accessKeyId(), credentials.secretAccessKey(),
                    credentials.sessionToken(), credentials.expiration());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // 失败必须可见：静默降级成静态转发会把「本来就该报错」变成「跑到一半才 403」，
            // 而那时错误现场离原因已经有几百行日志的距离（设计 §5.8）。
            log.warn("sts:AssumeRole failed for role {} (session {}): {}",
                    command.roleArn(), command.roleSessionName(), e.getMessage());
            throw new ApiException(503, null, null,
                    "could not assume role " + command.roleArn() + ": " + e.getMessage());
        }
    }

    static long clampDuration(Duration duration) {
        long seconds = duration == null ? 3600L : duration.toSeconds();
        return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, seconds));
    }

    private StsClient clientFor(AssumeRoleCommand command) {
        String region = command.region() == null || command.region().isBlank()
                ? "us-east-1" : command.region();
        String endpoint = command.stsEndpoint() == null ? "" : command.stsEndpoint().trim();
        // 缓存键必须带上「谁在签名」：STS 客户端把凭据存在自己身上，两个 catalog
        // 用不同的 AK/SK 签同一个区域、同一个端点时，第二张票会拿第一把钥匙去换，
        // 报 AccessDenied，而错误信息里没有任何一处提到「用错了钥匙」。
        String identity = command.hasCallerCredentials()
                ? "static:" + command.callerAccessKey() : "default";
        String key = region + "|" + endpoint + "|" + identity;
        return clients.computeIfAbsent(key, ignored -> build(command, region, endpoint));
    }

    private StsClient build(AssumeRoleCommand command, String region, String endpoint) {
        StsClientBuilder builder = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(callerIdentity(command))
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * 换票时用的身份：catalog 自带的 AK/SK，或 SDK 的默认链。
     *
     * <p>只有 {@code hasCallerCredentials()} 为真才建静态 provider——半份凭据
     * （只有 AK 没有 SK）会让 SDK 在签名时抛出「无法解析凭据」，而那条消息与
     * 「catalog 里少填了一栏」看不出关系。
     */
    private AwsCredentialsProvider callerIdentity(AssumeRoleCommand command) {
        if (command.hasCallerCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    command.callerAccessKey(), command.callerSecretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
