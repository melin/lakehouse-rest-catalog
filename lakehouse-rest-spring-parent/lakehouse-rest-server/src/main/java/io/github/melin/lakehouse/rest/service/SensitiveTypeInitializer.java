package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.SensitiveTypeEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitiveTypeRepository;
import io.github.melin.lakehouse.rest.support.BuiltinSensitiveTypes;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把内置敏感类型 seed 进库里（设计见 {@code docs/sensitive-data-design.md} §4.1）。
 *
 * <p><b>为什么是「有则跳过」而不是「每次都 upsert 回内置值」。</b>验收断言要求
 * 「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变」——这句话只有在
 * **重启不会把权重改回去**的前提下才成立。写成 upsert 的话，治理员调完权重、
 * 下一次发布把服务重启一遍，改动就无声消失，而现象是「扫描结果又变回原来那样」，
 * 没有任何一条日志能解释它。
 *
 * <p>因此 seed 的语义是「**补齐缺失的内置类型**」，不是「把内置类型重置成出厂值」。
 * 于是：
 * <ul>
 *   <li>库里没有这个 {@code type_code} → 建出来，{@code builtin=true}；</li>
 *   <li>库里已有（无论是不是内置）→ 一行不动。已有而 {@code builtin=false} 的情况
 *       只可能是「先手工建了一个同名的」，那是用户的定义，seed 没有立场覆盖它——
 *       代价是这个编码没有「不可删」保护，而这正是它本来的身份（用户自建）；</li>
 *   <li>治理员显式停用的内置类型（{@code enabled=false}）→ 保持停用。
 *       「停用」是「我不想要它」，与「还没建出来」是两件事。</li>
 * </ul>
 *
 * <p><b>与 {@code DataInitializer} 分开而不是塞进去。</b>那个类管的是「服务能不能
 * 用起来」（默认 catalog、初始管理员、认证配置告警），失败会让服务不可用；
 * 这一个管的是「识别能力有没有装上」，失败只让扫描扫不出东西。混在一起之后
 * 「seed 失败」与「默认 catalog 没建出来」在日志里长得一样，而处置完全不同。
 *
 * <p>整个 {@code run} 在一个事务里：内置类型是「要么都在、要么都不在」的一批，
 * 中途失败留下半批会让「为什么只认得身份证、认不出手机号」需要逐个查库才能回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveTypeInitializer implements ApplicationRunner {

    /**
     * 内置类型的创建者主体名。
     *
     * <p>与 {@code DataInitializer.SYSTEM_PRINCIPAL} 取同一个词，但它**不是**同一个常量：
     * 那个类为了不把 {@code system} 这个哨兵散布到各处，自己定义了一份。这里若去
     * 引用它，就要把 {@code DataInitializer}（一个 {@code ApplicationRunner}）
     * 变成被依赖的公共类——为一个字符串不值得。审计列上两者填的值一致即可。
     */
    private static final String SYSTEM_PRINCIPAL = "system";

    private final RestServerProperties properties;
    private final SensitiveTypeRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.getSensitivity().isSeedBuiltinTypes()) {
            // 这一条是 WARN 而不是 INFO：关掉之后一次扫描跑不出任何结论（候选类型表为空），
            // 而失败表现是「扫完了什么都没发现」，与「这张表真的没有敏感数据」一模一样。
            // 「我明明关了 seed」应当能在启动日志里被翻到。
            log.warn("built-in sensitive types are NOT seeded"
                    + " (lakehouse.rest.sensitivity.seed-builtin-types=false);"
                    + " scans will not produce any finding until types are created by hand");
            return;
        }
        long now = clock.millis();
        int created = 0;
        for (BuiltinSensitiveTypes.Builtin builtin : BuiltinSensitiveTypes.all()) {
            if (repository.existsByTypeCode(builtin.typeCode())) {
                continue;
            }
            SensitiveTypeEntity entity = new SensitiveTypeEntity();
            entity.setTypeCode(builtin.typeCode());
            entity.setTypeName(builtin.typeName());
            entity.setCategory(builtin.category().name());
            entity.setLevel(builtin.level().name());
            entity.setDetectorsJson(builtin.detectorsJson());
            entity.setAutoApply(builtin.autoApply());
            entity.setMinConfidence(builtin.minConfidence());
            // builtin=true **只在这里**被写：类型服务的新建/更新路径都显式写 false/保持，
            // 否则「把一个内置类型标成非内置再删掉」就是一条绕过 409 保护的路
            entity.setBuiltin(true);
            entity.setEnabled(true);
            entity.markCreated(SYSTEM_PRINCIPAL, now);
            repository.save(entity);
            created++;
        }
        if (created > 0) {
            log.info("seeded {} built-in sensitive type(s); {} already present",
                    created, BuiltinSensitiveTypes.all().size() - created);
        }
    }
}
