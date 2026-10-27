package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SchedulerLeaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 调度通道租约的抢/续/让。
 *
 * <p><b>抢与续刻意拆成两条语句。</b>合成一条时「只在易主时 +1」需要在 {@code set} 里判断旧值，
 * 而 MySQL 的 {@code UPDATE} 赋值按从左到右生效，后一个表达式读到的是前面刚写的新值——
 * 这种依赖求值顺序的写法迟早被人改错。两条语句各自只有一个条件，读起来就是它的语义。
 */
public interface SchedulerLeaseRepository extends JpaRepository<SchedulerLeaseEntity, String> {

    /**
     * 抢：我本来不持有（无主或别人持有）且它已过期。
     *
     * <p>{@code expires_at <= :now} 这条同时覆盖三种情形：别人崩溃留下的过期租约、
     * 我自己上一次崩溃留下的过期租约、以及行是新建的（{@code expires_at} 为空）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SchedulerLeaseEntity l"
            + " set l.leaseOwner = :me, l.acquiredAt = :now, l.expiresAt = :expires,"
            + "     l.fencingToken = l.fencingToken + 1, l.updatedAt = :now"
            + " where l.name = :name"
            + "   and (l.leaseOwner is null or l.leaseOwner <> :me)"
            + "   and (l.expiresAt is null or l.expiresAt <= :now)")
    int acquire(@Param("name") String name, @Param("me") String me,
                @Param("now") long now, @Param("expires") long expires);

    /**
     * 续：我持有且还没到期。
     *
     * <p>{@code expires_at > :now} 是这道条件的关键：租约已经过期就不该「悄悄续上」——
     * 那段空窗里别人完全可能已经抢走（哪怕它还没开始跑）。过期之后走 {@link #acquire}。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SchedulerLeaseEntity l set l.expiresAt = :expires, l.updatedAt = :now"
            + " where l.name = :name and l.leaseOwner = :me and l.expiresAt > :now")
    int renew(@Param("name") String name, @Param("me") String me,
              @Param("now") long now, @Param("expires") long expires);

    /**
     * 停机让出：把到期时刻置 0，下一个节拍任何人一拍就能抢到。
     *
     * <p>只认自己的租约（{@code lease_owner = :me}）——已经易主的话这次调用应当什么也不做，
     * 否则就是「旧持有者把新持有者的租约撕了」。
     *
     * <p>这条只是加速器：进程被 {@code kill -9} 时它不会执行，接管要等一个 TTL。
     * 见 {@code docs/optimization-multi-instance-design.md} §5.1。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SchedulerLeaseEntity l set l.expiresAt = 0, l.updatedAt = :now"
            + " where l.name = :name and l.leaseOwner = :me")
    int release(@Param("name") String name, @Param("me") String me, @Param("now") long now);
}
