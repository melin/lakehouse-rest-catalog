package io.github.melin.paimonrest.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 优化批次与表级明细的状态取值。
 *
 * <p>用枚举而不是散落的字符串常量：状态机是这个功能里唯一「写错就静默走错分支」的地方
 * （比如把 {@code PARTIAL_SUCCESS} 拼成 {@code PARTIAL_SUCCESSED}，终态判定会认为批次
 * 还在跑），而且判断「是不是终态」「算不算在跑」的逻辑必须只有一份——
 * 调度器、重试、控制台展示都要用它。落库时存 {@code name()}，改名等于改数据。
 */
public final class OptimizationStatus {

    private OptimizationStatus() {
    }

    /** 批次状态。 */
    public enum Job {

        /** 已落库、等着被提交。服务端重启后这一状态的批次会被下一个节拍捡起来。 */
        PENDING,
        /** 提交中：已抢占、正在调执行侧接口。 */
        SUBMITTING,
        /** 执行侧已有资源并在跑。 */
        RUNNING,
        /** 全部明细成功（跳过也算成功：没有该做的事不等于失败）。 */
        SUCCESS,
        /** 部分表失败：合并执行的批次特有的中间态。 */
        PARTIAL_SUCCESS,
        /** 提交失败、超时，或全部表失败。 */
        FAILED,
        /** 用户取消。 */
        CANCELED;

        /** 终态之后不再有状态变化，也不该再被提交或跟踪。 */
        public boolean terminal() {
            return this == SUCCESS || this == PARTIAL_SUCCESS || this == FAILED || this == CANCELED;
        }

        /** 占着执行资源的状态：判断「还该不该造新批次」时数它们。 */
        public boolean active() {
            return this == PENDING || this == SUBMITTING || this == RUNNING;
        }

        /**
         * 已经交给执行侧的状态。
         *
         * <p>提交闸门数的是这些，而不是 {@link #active()}：把排队中的 {@code PENDING}
         * 也算进去的话，队列里的第一个批次会被自己数进去，于是永远提交不出去。
         *
         * <p>两个口径分别属于两道闸门——{@link #active()} 决定「还要不要再出批」，
         * {@link #dispatched()} 决定「出好的批放不放出去」。后者不能省：手动「立即优化」
         * 与重试都是直接建批次，不经过出批那条路，只靠前者挡不住。
         */
        public boolean dispatched() {
            return this == SUBMITTING || this == RUNNING;
        }

        /** {@link #active()} 的状态名，供「按状态计数」的查询使用。 */
        public static List<String> activeNames() {
            return namesOf(Job::active);
        }

        /** {@link #dispatched()} 的状态名。 */
        public static List<String> dispatchedNames() {
            return namesOf(Job::dispatched);
        }

        private static List<String> namesOf(Predicate<Job> filter) {
            List<String> names = new ArrayList<>();
            for (Job status : values()) {
                if (filter.test(status)) {
                    names.add(status.name());
                }
            }
            return names;
        }

        /** 解析存储值；无法识别返回 {@code null}。 */
        public static Job parse(String value) {
            return parseValue(value, Job.class);
        }
    }

    /** 表级明细状态。 */
    public enum Item {

        /** 已入批、等待执行。 */
        PENDING,
        /** 优化任务已开始处理这张表。 */
        RUNNING,
        /** 执行成功。 */
        SUCCESS,
        /** 执行失败，错误原因在明细上。 */
        FAILED,
        /** 跑过但无事可做（未达阈值、没有过期快照、没有孤儿文件）。 */
        SKIPPED;

        public boolean terminal() {
            return this == SUCCESS || this == FAILED || this == SKIPPED;
        }

        public boolean active() {
            return this == PENDING || this == RUNNING;
        }

        public static Item parse(String value) {
            return parseValue(value, Item.class);
        }
    }

    private static <E extends Enum<E>> E parseValue(String value, Class<E> type) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
