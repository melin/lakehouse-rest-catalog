package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SensitiveTypeEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 敏感类型与探测器配置的仓储。
 *
 * <p>查询一律以 {@code type_code} 为入口，而不是自增主键：{@code type_code} 是
 * 治理员与文档里都用的名字（{@code CN_ID_CARD}），而 {@code id} 只在一次会话内稳定。
 * 类型数量是「几十」，因此不提供分页——列表页在全量载入后内存切页，与
 * {@code DataPolicyService#list} 同一口径。
 */
public interface SensitiveTypeRepository extends JpaRepository<SensitiveTypeEntity, Long> {

    /** 按业务编码找类型；编码上有唯一键（{@code uk_sensitive_type_code}），至多一条。 */
    Optional<SensitiveTypeEntity> findByTypeCode(String typeCode);

    Optional<SensitiveTypeEntity> findByTypeCodeAndEnabledTrue(String typeCode);

    /** 列表页与探测器的全量入口（类型几十个，不分页）。 */
    List<SensitiveTypeEntity> findAllByOrderByTypeCodeAsc();

    /**
     * 参与识别的类型（{@code enabled_flag = true}）。
     *
     * <p>扫描每张表都要取这一份，所以它必须是**一条查全量**的语句，而不是
     * 「先查全部再在内存里 filter enabled」——后者在扫描循环里会变成每表一次全量载入。
     */
    List<SensitiveTypeEntity> findAllByEnabledTrueOrderByTypeCodeAsc();

    /** seed 前判存在用；比 {@code findByTypeCode} 少一次实体构造。 */
    boolean existsByTypeCode(String typeCode);

    /** 内置类型不能被删（设计稿 §12 的 409 断言），seed 与守卫都用它。 */
    List<SensitiveTypeEntity> findAllByBuiltinTrueOrderByTypeCodeAsc();
}
