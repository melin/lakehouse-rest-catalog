package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 目录事件的读取口。
 *
 * <p><b>查询形状只有增量拉取这一种</b>：给定游标（含「从头」= 取最新一页）与可选的
 * catalog / category 过滤，返回升序或倒序的一页。过滤条件是<b>组合</b>的
 * （目录 × 类别 × 游标），派生查询要写 8 个方法名才能覆盖，因此用
 * {@link JpaSpecificationExecutor} 动态拼谓词——加一个过滤维度不必再改接口。
 *
 * <p>仍然不提供按类型 / 时间区间的任意查询：控制台之外还没有第二个消费方，
 * 要加的时候同样是加一个谓词，而不是加一排派生方法。
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long>,
        JpaSpecificationExecutor<AuditEventEntity> {
}
