package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogRepository extends JpaRepository<CatalogEntity, String> {

    Optional<CatalogEntity> findByPrefix(String prefix);

    List<CatalogEntity> findAllByOrderByPrefixAsc();

    /**
     * 拿 catalog 行的写锁（{@code select ... for update}）。
     *
     * <p><b>用途只有一个：把「数一遍活跃批次」与「造一个新批次」之间的窗口关掉。</b>
     * 出批的配额检查是「先 count 再 create」，多副本下两台机器可以同时读到 {@code 1/2}
     * 然后各造一个批次，结果是 3 个——超额而不是重复执行，但既然要多副本部署，
     * 一次做对更省心。
     *
     * <p><b>锁的粒度是一个 catalog 的一次出批事务</b>（毫秒级），不是整轮扫描：
     * 调用方必须保证事务里没有外部调用，也不能把「遍历所有 catalog」包在同一个事务里，
     * 否则两台机器会在整轮扫描上互相排队。
     *
     * <p><b>锁顺序纪律：{@code catalog → policy/明细}。</b>所有同时碰这两类行的路径
     * （出批、立即优化、重试）都按这个顺序加锁，否则两条路径交叉持锁会死锁。
     * {@code TableOptimizationRepository#claim} 是 policy 行锁，排在后面对。
     *
     * <p>行不存在时返回空 {@link Optional}：catalog 刚被删掉是正常并发，
     * 调用方按「没什么可做的」处理即可，不必当成错误。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CatalogEntity c where c.id = :id")
    Optional<CatalogEntity> lockById(@Param("id") String id);
}
