package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.scan.SensitivityEnums;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * catalog 级敏感数据设置的读写（设计见 {@code docs/sensitive-data-design.md}
 * §5.6.1 / §5.6.4 / §9）。
 *
 * <p><b>为什么落在 {@code lakehouse_catalog.properties_json} 而不是新加几列。</b>
 * 与 {@code optimization.&lt;action&gt;.*} 同一做法（见 {@code OptimizationConfigResolver}）：
 * 它是 catalog 的一个属性，不是独立可枚举的实体，也没有「不属于任何 catalog 的设置」。
 * 加列的话，每加一个设置项就是一次 DDL 与迁移；而这里三项都是可选覆盖，
 * 「没设」是正常状态，映射表天然表达得了。
 *
 * <p><b>键名带 {@code sensitivity.} 前缀。</b>properties_json 是同一张表里所有
 * catalog 级设置的共用空间（优化策略用 {@code optimization.*}），前缀让「读一个设置」
 * 不需要知道全部键——摘键就是一个 stripPrefix。
 *
 * <p><b>缺省值放在这里，而不是散在三个新增点。</b>「未设置时回落到什么」在三处被问到：
 * 服务层算生效值、控制台显示「继承全局」的提示、测试断言三层覆盖。
 * 让它们各写一遍 {@code == null ? 2 : value}，漂移的表现是「界面说 2、实际跑 3」。
 */
public final class SensitivityCatalogSettings {

    /** catalog 属性里的键前缀。与 {@code optimization.} 同一层，互不干扰。 */
    public static final String PREFIX = "sensitivity.";

    /** 某个 catalog 默认走哪条执行路线（A/B 两路，见设计稿 §5.6.1）。 */
    public static final String KEY_DEFAULT_EXECUTION = PREFIX + "default-execution";

    /**
     * A 路线（进程内）的扫描并发上限（设计稿 §5.6.4）。
     *
     * <p>名字里带 {@code Scans} 是刻意的：它与执行集群那把
     * {@code maxConcurrentJobs} 是**两件事**——那个管「这台集群上同时跑几个作业」，
     * 这个管「服务端进程内同时跑几条扫描」。历史上两者曾同名，读到这里请按上面这句
     * 区分（设计稿 §5.6.4 的警示框）。
     */
    public static final String KEY_MAX_CONCURRENT_SCANS = PREFIX + "max-concurrent-scans";

    /** 血缘传播最大跳数（设计稿 §7.1，P2 落地）。 */
    public static final String KEY_PROPAGATION_MAX_DEPTH = PREFIX + "propagation-max-depth";

    private SensitivityCatalogSettings() {
    }

    /**
     * 一个 catalog 的覆盖值：{@code null} 表示继承全局。
     *
     * <p>与 {@code SensitivityDtos.SensitivitySettings} 分开（一个是存储层的读模型、
     * 一个是 API 契约）：DTO 里还有生效值与乐观锁版本号那些不属于存储的东西。
     */
    public record Overrides(String defaultExecution, Integer maxConcurrentScans,
                            Integer propagationMaxDepth) {

        /** 全空 = 这个 catalog 什么都没覆盖（catalog 建完就是这个状态）。 */
        public static Overrides none() {
            return new Overrides(null, null, null);
        }

        public boolean isEmpty() {
            return defaultExecution == null && maxConcurrentScans == null
                    && propagationMaxDepth == null;
        }
    }

    /**
     * 从 catalog 属性里摘出三项覆盖值。
     *
     * <p><b>认不出的数值一律当「没设」</b>（回落全局），而不是抛异常：properties_json
     * 是运维可以直接改的自由格式（与优化策略的 catalog 默认值同一条理由），
     * 一个手抖写进去的 {@code abc} 不该让整个 catalog 的扫描不可用。
     * 表现为「配置写了但没生效」，与「没配」同结果——这一点在
     * {@code SensitivitySettingsService} 的读接口上会以「生效值 = 全局值」暴露出来。
     */
    public static Overrides read(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Overrides.none();
        }
        String execution = blankToNull(properties.get(KEY_DEFAULT_EXECUTION));
        Integer scans = parseInt(properties.get(KEY_MAX_CONCURRENT_SCANS));
        Integer depth = parseInt(properties.get(KEY_PROPAGATION_MAX_DEPTH));
        return new Overrides(execution, scans, depth);
    }

    /**
     * 把三项覆盖写回属性表，返回新的 Map（不改原 Map）。
     *
     * <p>{@code null} = 清除该项的覆盖（回落全局）。**清除与「写成默认值」是两件事**：
     * 写 {@code 2} 表示「这个 catalog 永远是 2」（全局改成 4 时它不动），
     * 清除表示「跟着全局走」（全局改成 4 时它变 4）。前者是一次治理决定、后者是把
     * 决定收回——两者都要能表达，因此用「键在不在」而不是「值等不等于默认」来区分。
     */
    public static Map<String, String> write(Map<String, String> properties, Overrides overrides) {
        Map<String, String> out = properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        put(out, KEY_DEFAULT_EXECUTION, overrides.defaultExecution());
        put(out, KEY_MAX_CONCURRENT_SCANS,
                overrides.maxConcurrentScans() == null ? null : String.valueOf(overrides.maxConcurrentScans()));
        put(out, KEY_PROPAGATION_MAX_DEPTH,
                overrides.propagationMaxDepth() == null ? null
                        : String.valueOf(overrides.propagationMaxDepth()));
        return out;
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value == null || value.isBlank()) {
            target.remove(key);
        } else {
            target.put(key, value);
        }
    }

    /** 解析成枚举；认不出返回空（调用方回落全局并留 warn）。 */
    public static Optional<SensitivityEnums.ScanExecution> parseExecution(String value) {
        return SensitivityEnums.ScanExecution.parse(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
