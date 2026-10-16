package io.github.melin.paimonrest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开调度能力（表存储优化的出批、提交与跟踪节拍）。
 *
 * <p>{@code @EnableScheduling} 在本工程里是新东西：在此之前服务端没有任何定时任务，
 * 因此它被单独放成一个可按配置关掉的配置类，而不是散在业务组件上。
 * {@code paimon.rest.optimization.scheduler-enabled=false} 时这个类整体不生效——
 * 于是「谁在跑定时任务」这件事在配置里一眼可见，而不是靠读代码里的注解发现。
 *
 * <p>关掉它只停调度：策略读写、立即优化、批次查询都照常工作。这对
 * 「优化任务由外部调度系统触发」的部署是必要的——两个调度器同时往同一批表上出批，
 * 会互相抢同一张表的执行机会（抢占能保证不重复执行，但会让两边都出不满批）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "paimon.rest.optimization", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class OptimizationSchedulingConfig {
}
