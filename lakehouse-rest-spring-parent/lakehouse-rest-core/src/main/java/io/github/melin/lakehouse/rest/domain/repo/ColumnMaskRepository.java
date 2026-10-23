package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.ColumnMaskEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 列脱敏定义的仓储。随 {@link io.github.melin.lakehouse.rest.domain.entity.DataPolicyEntity}
 * 一起读、整批替换（PUT 的语义是「提交一套完整策略」，不是逐列打补丁）。
 */
public interface ColumnMaskRepository extends JpaRepository<ColumnMaskEntity, Long> {

    List<ColumnMaskEntity> findByPolicyId(Long policyId);

    /**
     * 删掉一张策略下的全部 mask。
     *
     * <p><b>批量删除禁用 {@code @Modifying(clearAutomatically = true)}</b>：清空一级缓存
     * 会把调用方事务里还没刷的实体一并作废——这是本仓踩过的坑（见
     * {@code TableRepository} 的注释），调用方自己管理 flush 与缓存一致性。
     */
    @Modifying
    @Query("delete from ColumnMaskEntity m where m.policyId = :policyId")
    void deleteByPolicyId(@Param("policyId") Long policyId);
}
