package io.github.melin.lakehouse.rest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开血缘的调度能力（过期 run 的每日清理）。
 *
 * <p>{@code @EnableScheduling} 在优化那套里已经开过一次；这里按
 * {@code lakehouse.rest.lineage.enabled} 再开一个条件配置，是为了让
 * 「只开血缘、不开优化」的部署也能清理过期 run。两处都开着没有副作用
 * （Spring 的调度基础设施是幂等的），而关掉优化调度不该连带让血缘的清理也停摆。
 *
 * <p>{@code enabled=false} 时整个配置不生效：那时没有 run 会被写入，
 * 也就不需要清理；让一个关着的功能仍在后台跑定时任务，是「看起来什么都没做」的
 * 那类配置噪声。
 *
 * <p><b>{@code matchIfMissing} 必须是 true。</b>缺省值是开，因此「配置文件里没写这个键」
 * 与「显式写了 true」是同一档语义。写成 false 会让绝大多数部署（不写这个键的）保留采集
 * 却不做清理，现象是「run 表一直在长」——比报错难查得多。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "lakehouse.rest.lineage", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LineageSchedulingConfig {
}
