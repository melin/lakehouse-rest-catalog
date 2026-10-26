package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;

/**
 * catalog 执行集群的**派生状态**：不落库，每次由「有没有记录 / 是否停用 / 配置改过没验过 /
 * 最近一次探活结果」算出来。
 *
 * <p><b>为什么状态是算出来的而不是存出来的。</b>它有三个输入，其中两个（记录是否存在、
 * {@code configHash != probeConfigHash}）在库里本来就各有一份真相；再存一个状态列，
 * 就等于允许「状态列与那两列说法不一致」这种只能靠程序纪律避免的状态。派生之后，
 * 「改完配置没验证」是 {@code UNVERIFIED} 这件事只可能来自一处判断。
 *
 * <p><b>判定顺序是有意义的</b>：停用优先于一切（停用的记录不该显示「异常」，
 * 那会让人去修一份本来就不打算跑的配置）；「没验过当前这份配置」优先于「上次验过了」，
 * 否则刚改完错配置的 catalog 会一直显示上次的绿色。
 */
public enum ExecutionClusterStatus {

    /** 没有记录。优化调度对它是跳过的，游标不推进。 */
    NOT_CONFIGURED("未配置", false),

    /** {@code enabled=false}：刻意的关闭，不是失败。 */
    DISABLED("已停用", false),

    /** 有记录，但当前配置从没验过（或验过之后又改了）。照常调度，但列表上要看得见。 */
    UNVERIFIED("待验证", true),

    /** 最近一次探活通过。 */
    HEALTHY("正常", true),

    /**
     * 最近一次探活失败：**暂停本 catalog 的派发**，游标不推进，{@code PENDING} 批次不判失败。
     *
     * <p>不判失败的理由与「未配置」同一条：批次从未被提交过，写一个红色的「失败」只会
     * 让人去找根本不存在的执行侧日志。
     */
    FAILING("异常", false);

    private final String label;

    private final boolean dispatchable;

    ExecutionClusterStatus(String label, boolean dispatchable) {
        this.label = label;
        this.dispatchable = dispatchable;
    }

    public String label() {
        return label;
    }

    /** 这个状态下调度该不该往这个 catalog 提交批次。 */
    public boolean dispatchable() {
        return dispatchable;
    }

    /**
     * 由记录算出状态。{@code null} 记录表示「没配」。
     *
     * <p>探活结果为 {@code null}（有记录但从没探过）也算 {@code UNVERIFIED}——
     * 「保存完没点验证」与「验证过之后又改了配置」对用户是同一件事：
     * 现在这份配置没有证据。
     */
    public static ExecutionClusterStatus of(CatalogExecutionClusterEntity entity) {
        if (entity == null) {
            return NOT_CONFIGURED;
        }
        if (!entity.isEnabled()) {
            return DISABLED;
        }
        String hash = entity.getConfigHash();
        String probed = entity.getProbeConfigHash();
        if (probed == null || !probed.equals(hash)) {
            return UNVERIFIED;
        }
        if ("PASS".equals(entity.getProbeStatus())) {
            return HEALTHY;
        }
        if ("FAIL".equals(entity.getProbeStatus())) {
            return FAILING;
        }
        return UNVERIFIED;
    }
}
