package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
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
 * <p><b>「谁在签名」必须是服务自己的身份，不是 catalog 里那把 AK/SK。</b>
 * 这是与转发模式最本质的区别：转发是把长期钥匙交给引擎，签发是服务用自己的身份
 * 去换一张只覆盖这张表的票。所以这里的凭据链按顺序取服务身份——
 * 显式配置的 {@code lakehouse.rest.storage.aws.access-key} → 环境变量 →
 * 实例角色 / IRSA。目标态（设计 §5.7）是服务身份**只能** {@code AssumeRole}、
 * 不能直接读写桶，那是运维侧的 IAM 配置，代码侧只需要保证不拿静态钥匙当访问凭据。
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

    private final RestServerProperties properties;

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
        String key = region + "|" + endpoint;
        return clients.computeIfAbsent(key, ignored -> build(region, endpoint));
    }

    private StsClient build(String region, String endpoint) {
        StsClientBuilder builder = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(serviceIdentity())
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * 服务自身身份的凭据链。
     *
     * <p>配了 {@code storage.aws.access-key} 就用它（自建机房常见：一把只能
     * {@code sts:AssumeRole} 的钥匙），否则交给 SDK 的默认链（环境变量、
     * 实例角色、IRSA 的 web identity）。
     */
    private AwsCredentialsProvider serviceIdentity() {
        RestServerProperties.Storage.Aws aws = properties.getStorage().getAws();
        if (aws.getAccessKey() != null && !aws.getAccessKey().isBlank()
                && aws.getSecretKey() != null && !aws.getSecretKey().isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
