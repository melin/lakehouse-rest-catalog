package io.github.melin.lakehouse.rest.event;

/**
 * 目录领域事件的类型。
 *
 * <p>只收录「控制台与运维真正会问」的变更（判据与不发清单见
 * {@code docs/event-framework.md} §1），wire 值用小写点分——它是跨系统
 * 的契约字符串（事件流、未来可能的外发报文），因此放在枚举里集中声明，
 * 而不是让各发布点手写字符串。
 */
public enum CatalogEventType {

    /** 表进入目录（建表与 register）。 */
    TABLE_CREATED("table.created"),

    /** 表离开目录（删表与 unregister）。 */
    TABLE_DROPPED("table.dropped"),

    /** 表改名或换库。 */
    TABLE_RENAMED("table.renamed"),

    /** 元数据指针推进（提交快照 / updates、schema 变更）。 */
    TABLE_COMMITTED("table.committed"),

    /** 直授授权变化（授予 / 撤销）。 */
    GRANT_CHANGED("grant.changed"),

    /** 优化策略变化（表策略与 catalog 级默认设置）。 */
    OPTIMIZATION_POLICY_CHANGED("optimization.policy_changed"),

    /** 优化批次到达终态（成功 / 部分成功 / 失败 / 取消）。 */
    OPTIMIZATION_JOB_FINISHED("optimization.job_finished");

    private final String wire;

    CatalogEventType(String wire) {
        this.wire = wire;
    }

    /** 事件流与外发报文里使用的字符串形式。 */
    public String wire() {
        return wire;
    }
}
