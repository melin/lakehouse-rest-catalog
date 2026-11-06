package io.github.melin.lakehouse.rest.support;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 敏感数据识别与标签治理的取值枚举（设计见 {@code docs/sensitive-data-design.md}）。
 *
 * <p><b>为什么集中在一个文件里。</b>这些枚举是几个 DTO、五个实体、服务层判定带与控制台
 * 下拉共同读的同一份词表：{@code METADATA_ONLY} 这个字符串既进数据库、
 * 又进事件属性、又被前端用来决定「要不要显示采样量」——分散声明迟早出现
 * 「一处写 {@code IN_PROCESS}、另一处写 {@code INPROCESS}」，而这类漂移的表现是
 * 某条分支永远不进，不报错。
 *
 * <p><b>落库一律用 {@link Enum#name()}。</b>与 {@link CatalogFormat} 同一口径：
 * 大写名本身就是契约，不做大小写归一，也不存小写值——存小写会让「读回来比较」
 * 需要一次大小写转换，而转换点漏掉一个就是静默不相等。
 *
 * <p><b>取值域的宽度按「将来会到哪一档」建，而不是按今天用到哪一档建</b>
 * （设计稿 §12 的注）：{@link ScanStatus} 今天就包含 B 路线（下推 Spark）的
 * {@code PENDING} / {@code SUBMITTING}，{@code lakehouse_sensitivity_scan} 的那组
 * B 专属列也一次建齐。理由是扩枚举取值域会改到列宽与所有既有读取点，
 * 而多几个今天不产生的取值没有代价。
 */
public final class SensitivityEnums {

    private SensitivityEnums() {
    }

    /**
     * 允许自动应用的类型白名单（设计稿 §5.3）。
     *
     * <p><b>只有「格式强、误判代价低」的类型在这里</b>：身份证、手机号、银行卡、
     * 统一社会信用代码、邮箱、IP、凭据——它们都有校验位或强结构，正则加校验器之后
     * 误报率极低。
     *
     * <p>反过来说，{@code PERSON_NAME} / {@code ADDRESS} / {@code SALARY} /
     * {@code MEDICAL_DIAGNOSIS} <b>永不自动应用</b>：{@code name} 可能是商品名、
     * {@code amount} 可能是库存，一旦自动落标就是**误脱敏**——数据被 mask 掉但没人
     * 知道为什么，业务侧只看到值突然全成了星号。
     *
     * <p><b>这个集合是「默认该开」的判据，不是「一定会开」的保证。</b>真正的开关是
     * {@code SensitiveTypeEntity.autoApply}——治理员想让姓名也自动，必须显式去改类型
     * 配置，而这个动作会被审计，责任可追（见 {@link #defaultAutoApply(String)}）。
     */
    public static final Set<String> AUTO_APPLY_ALLOWED_TYPES = Set.of(
            "CN_ID_CARD", "CN_MOBILE", "BANK_CARD", "CN_USCC", "EMAIL", "IP", "CREDENTIAL");

    /** 明确**不允许**自动应用的类型：seed 时把开关钉成 false，测试也断言它恒为 false。 */
    public static final Set<String> AUTO_APPLY_FORBIDDEN_TYPES = Set.of(
            "PERSON_NAME", "ADDRESS", "SALARY", "MEDICAL_DIAGNOSIS");

    /** 分级里只能**人工**打的两档（GB/T 43697-2024 的核心 / 重要数据）。 */
    public static final Set<SensitivityLevel> MANUAL_ONLY_LEVELS =
            Set.of(SensitivityLevel.CORE, SensitivityLevel.IMPORTANT);

    /**
     * seed 内置类型时，该类型的 {@code auto_apply} 初始值。
     *
     * <p><b>为什么不直接用 {@link #AUTO_APPLY_ALLOWED_TYPES} 里有没有来判断。</b>
     * 两个集合今天互补，但它们的语义不同：白名单回答「允不允许」，
     * 这个方法回答「随启动建的这一行该填什么」。将来若要给某个白名单类型默认关
     * （比如先观察一段 {@code CREDENTIAL} 的误报），改的是这里，不动白名单——
     * 混成一个的话，「默认关」会被读成「禁止」，下一个改的人就会去删白名单项。
     */
    public static boolean defaultAutoApply(String typeCode) {
        return AUTO_APPLY_ALLOWED_TYPES.contains(typeCode);
    }

    /** 一次扫描的范围层级。 */
    public enum ScanScope {

        /** 整个 catalog（{@code database_name} / {@code table_name} 为空）。 */
        CATALOG,

        /** 一个库（只有 {@code table_name} 为空）。 */
        DATABASE,

        /** 一张表。 */
        TABLE;

        public static Optional<ScanScope> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 扫描模式，决定跑哪几层探测器。
     *
     * <p>{@link #METADATA_ONLY} 只跑 D1（名称·注释）与 D2（类型·形状）——两层都只读
     * {@code lakehouse_table.schema_doc}，零额外 I/O，因此它<b>恒走进程内</b>
     * （下推 Spark 读元数据没有任何收益，反而多一次作业调度开销）。
     */
    public enum ScanMode {

        /** 仅元数据（D1 + D2）。 */
        METADATA_ONLY,

        /** 采样并跑 D3（值模式）/ D4（字典）/ D5（统计特征）。 */
        SAMPLED;

        public static Optional<ScanMode> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 扫描在哪里执行（设计稿 §5.6.1）。
     *
     * <p>三层覆盖的**生效值**才落库：请求 &gt; catalog 的 {@code defaultExecution} &gt;
     * 全局 {@code lakehouse.rest.sensitivity.scan.execution}。落生效值而不是请求体原值，
     * 是为了「三个月后回看这次扫描跑在哪」有答案——请求没传时，原值是 {@code null}。
     */
    public enum ScanExecution {

        /** A 路线：服务端进程内，复用表预览的读通道。默认值。 */
        IN_PROCESS,

        /** B 路线：提交到 catalog 的执行集群（P4 才通，P2 起先有列再有值）。 */
        SPARK;

        public static Optional<ScanExecution> parse(String value) {
            return lookup(values(), value);
        }
    }

    /** 扫描怎么被触发的（设计稿 §5.6.2）。 */
    public enum ScanTrigger {

        /** 控制台 / REST 手动发起。 */
        MANUAL,

        /** 生命周期钩子（{@code ALTER TABLE ADD COLUMN}，P2）。 */
        LIFECYCLE,

        /** 定时调度（外部调度器或自建调度，P4）。 */
        SCHEDULE;

        public static Optional<ScanTrigger> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 一次扫描的状态。
     *
     * <p>{@link #PENDING} 与 {@link #SUBMITTING} 是 B 路线（下推 Spark）专有的前置态，
     * 与优化批次同形（先落库、再提交、提交中可被其它实例凭 fencing 回收）。
     * A 路线直接 {@link #RUNNING} → 终态，不会经过那两态。
     */
    public enum ScanStatus {

        /** 已落库、等提交（仅 B）。 */
        PENDING,

        /** 提交执行中（仅 B）。 */
        SUBMITTING,

        /** 执行中。 */
        RUNNING,

        /** 完成。 */
        SUCCEEDED,

        /** 失败（{@code error_message} 有原因）。 */
        FAILED,

        /** 被取消。 */
        CANCELLED;

        /** 是否已到终态（终态不再推进，也不再被回收）。 */
        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }

        public static Optional<ScanStatus> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 一条 finding 的处置状态（设计稿 §5.2 的三条决策带）。
     *
     * <p><b>finding 与 tag 是两张表</b>：扫描每天重跑，finding 会重复产生；
     * 而「这列是身份证」这个结论只该有一份。合并成一张表就会出现
     * 「同列 30 条 pending」或者「重扫把人工拒绝洗回待审」。
     */
    public enum FindingState {

        /** 落在 0.60–0.85，或类型不允许自动应用 → 进人审队列。 */
        PENDING,

        /** 置信度达标且类型允许自动应用 → 已落 tag。 */
        AUTO_APPLIED,

        /** 人工确认。 */
        CONFIRMED,

        /**
         * 人工拒绝。
         *
         * <p>拒绝还要落**永久抑制项**（按 {@code column_ref_hash + type_code}），
         * 否则下一次扫描会把同一条结论重新提出来——「我说了不是，它每次还问」。
         */
        REJECTED;

        public static Optional<FindingState> parse(String value) {
            return lookup(values(), value);
        }
    }

    /** 一条列级标签是怎么来的。 */
    public enum TagSource {

        /** 自动应用（扫描命中且过闸门）。 */
        AUTO,

        /** 人工打标（含 {@code CORE} / {@code IMPORTANT} 两档——它们只能人工打）。 */
        MANUAL,

        /** 血缘继承（P2）。 */
        INHERITED;

        public static Optional<TagSource> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 标签值里的分级（设计稿 §5.5，对齐《网络数据分类分级指引》1–4 级）。
     *
     * <p><b>{@link #CORE} / {@link #IMPORTANT} 不进自动识别。</b>GB/T 43697-2024
     * 规定核心数据与重要数据按国家与行业发布的目录认定，不是扫描能推出来的——
     * 把一张含身份证的业务表自动判成重要数据，是把监管责任当成算法输出。
     * 因此这两个取值只在手工打标（{@code source=MANUAL}）时出现，
     * 探测器与自动应用白名单里都没有它们。
     *
     * <p>{@link #ordinal()} 不可用于比大小：{@code CORE} 排在 {@code L1} 之后
     * 不代表它更「低」。比较一律走 {@link #rank()}。
     */
    public enum SensitivityLevel {

        L1(1),
        L2(2),
        L3(3),
        L4(4),
        /** 重要数据（人工认定）。 */
        IMPORTANT(5),
        /** 核心数据（人工认定）。 */
        CORE(6);

        private final int rank;

        SensitivityLevel(int rank) {
            this.rank = rank;
        }

        /** 严重程度序数，用于「表级级别 = 该表所有列标签的 max」这类比较。 */
        public int rank() {
            return rank;
        }

        /** 是否为自动识别能产出的三档之一（{@code L1}–{@code L4} 里排除人工两档）。 */
        public boolean automatable() {
            return this == L1 || this == L2 || this == L3 || this == L4;
        }

        public static Optional<SensitivityLevel> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 采样方式（设计稿 §6.2）。
     *
     * <p>{@link #TABLESAMPLE} 是首选（按块随机，分布无偏）；引擎或格式不支持时降级到
     * {@link #HEAD}（前 N 行）。降级必须落到 finding 上——「这列 92% 的行像身份证」
     * 在 {@code HEAD} 下的置信度天然低于 {@code TABLESAMPLE}，因为前 N 行常常是
     * 一批同质数据（同一批导入、同一个地区），审核人看到 0.87 要知道它是不是
     * 从一个偏斜的窗口算出来的。
     */
    public enum SamplingMode {

        /** 按块随机采样（首选）。 */
        TABLESAMPLE,

        /** 前 N 行（降级）。 */
        HEAD;

        public static Optional<SamplingMode> parse(String value) {
            return lookup(values(), value);
        }
    }

    /** 敏感类型的类别（设计稿 §4.1）。 */
    public enum SensitiveCategory {

        /** 个人信息。 */
        PII,

        /** 支付卡行业数据。 */
        PCI,

        /** 医疗健康数据。 */
        PHI,

        /** 凭据 / 密钥。 */
        SECRET,

        /** 金融数据。 */
        FINANCIAL;

        public static Optional<SensitiveCategory> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 五层探测器（设计稿 §5.1）。
     *
     * <p>P0 只实现 {@link #NAME} 与 {@link #TYPE} 两层（零成本，只读 schema 文档）；
     * {@link #VALUE} / {@link #DICT} / {@link #STAT} 需要采样，P1 起才有实现。
     * 枚举一次列全，是为了让「探测器配置」的形状从第一天就稳定——
     * {@code detectors_json} 里少一层就是「这层没开」，不是「这层还不存在」。
     */
    public enum DetectorKind {

        /** D1 名称·注释（列名 / 列注释 / 表名上下文）。 */
        NAME,

        /** D2 类型·形状（SQL 类型 + 长度/精度）。 */
        TYPE,

        /** D3 值模式（采样值正则 + 校验器）。 */
        VALUE,

        /** D4 字典（小基数枚举对照敏感字典）。 */
        DICT,

        /** D5 统计特征（基数比 / 长度分布 / 空值率）。 */
        STAT;

        public static Optional<DetectorKind> parse(String value) {
            return lookup(values(), value);
        }
    }

    /**
     * 各嵌套枚举共用的查找实现。
     *
     * <p><b>名字不能叫 {@code parse}</b>：嵌套枚举里的 {@code parse(String)} 是
     * 「不带限定名的调用」，按 Java 的作用域规则会先命中枚举自己声明的方法——
     * 于是 {@code parse(values(), value)} 被解析成对 {@code parse(String)} 的双参调用，
     * 编译报「实际参数列表和形式参数列表长度不同」。这不是笔误能看出来的错，
     * 换个名字才是根治。
     *
     * <p><b>只认大写名、不做大小写归一</b>（与 {@link CatalogFormat} 同一口径）：
     * 存进去的就是 {@code name()}，读回来就能直接比。让 {@code parse} 顺手
     * {@code toUpperCase()}，等于允许「库里混着 {@code in_process} 与
     * {@code IN_PROCESS}」——而这类脏数据在按枚举比对的地方会静默不相等，
     * 不会报错，只是某条分支永远不进。
     */
    private static <E extends Enum<E>> Optional<E> lookup(E[] values, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return Arrays.stream(values)
                .filter(candidate -> candidate.name().equals(trimmed))
                .findFirst();
    }
}
