package io.github.melin.paimonrest.service.submit;

import io.github.melin.paimonrest.config.RestServerProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 不连接执行环境的提交器：只记日志。
 *
 * <p>存在的理由有两个。一是本地开发：想验证「策略 → 组批 → 落库 → 跟踪」这条链路
 * 而不想先搭一个 k8s，把它设成 {@code paimon.rest.optimization.submitter=log} 即可，
 * 批次照常创建、状态照常推进。二是把「服务端在没有集群时会不会把批次卡死」这件事
 * 变成可观察的：它永远返回 {@link JobState#UNKNOWN}，于是批次会一直等到
 * {@code deadline} 才被判失败——这个行为正是真实环境里「提交成功但任务丢了」的形态。
 *
 * <p>它**不会**让批次成功：没有执行者上报结果，明细永远停在 PENDING。要结束这种批次，
 * 用取消（状态置 CANCELED）。这是刻意的——一个会自动「假装成功」的桩提交器，
 * 迟早会有人在生产里误配它。
 */
@Component
@ConditionalOnProperty(prefix = "paimon.rest.optimization", name = "submitter", havingValue = "log")
@RequiredArgsConstructor
public class LoggingJobSubmitter implements OptimizationJobSubmitter {

    private static final Logger log = LoggerFactory.getLogger(LoggingJobSubmitter.class);

    private final RestServerProperties properties;

    @Override
    public String name() {
        return "log";
    }

    @Override
    public Submitted submit(SubmissionRequest request) {
        log.warn("optimization job {} for catalog {} is recorded but NOT executed:"
                        + " submitter is 'log' (paimon.rest.optimization.submitter)."
                        + " Point it at k8s to actually run the spark job",
                request.jobId(), request.catalog());
        return new Submitted(properties.getOptimization().getNamespace(), "log-" + request.jobId());
    }

    @Override
    public JobState state(String namespace, String resource) {
        return JobState.UNKNOWN;
    }

    @Override
    public void cancel(String namespace, String resource) {
        log.info("optimization job resource {} has nothing to cancel: submitter is 'log'", resource);
    }
}
