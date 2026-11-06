package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TagMaskBindingEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 标签到脱敏动作绑定的仓储。
 *
 * <p><b>读路径永远是「按 catalog 一次取全量、大者优先」</b>：绑定是查询期展开的
 * （每次查询要按列标签找出该用哪条 mask），逐条按条件查会让一条 SQL 的解析
 * 变成多次数据库往返。catalog 的绑定数量是「几条到几十条」，全量载入后
 * 在内存里按 {@code priority} 排序更划算，也更容易保证「大者优先」这个
 * 语义只有一处实现。
 */
public interface TagMaskBindingRepository extends JpaRepository<TagMaskBindingEntity, Long> {

    /** 展开用：某 catalog 的全部启用绑定，**已按优先级降序**（大者优先）。 */
    List<TagMaskBindingEntity> findByCatalogNameAndEnabledTrueOrderByPriorityDesc(String catalogName);

    /** 管理列表用：含停用项（治理员要能看到自己停过什么）。 */
    List<TagMaskBindingEntity> findByCatalogNameOrderByPriorityDesc(String catalogName);

    /**
     * 唯一键定位。
     *
     * <p>{@code tagValue} 的通配（该键任意值）在**服务层写入时归一成空串**，
     * 不是 {@code null}——MySQL 唯一索引下多行 {@code NULL} 互不冲突，
     * 用 {@code null} 会让同一把键可以插两条通配绑定（见实体类注释）。
     */
    Optional<TagMaskBindingEntity> findByCatalogNameAndTagKeyAndTagValue(
            String catalogName, String tagKey, String tagValue);

    /** 写入前的冲突判定（比上面少一次行映射）。 */
    boolean existsByCatalogNameAndTagKeyAndTagValue(String catalogName, String tagKey, String tagValue);
}
