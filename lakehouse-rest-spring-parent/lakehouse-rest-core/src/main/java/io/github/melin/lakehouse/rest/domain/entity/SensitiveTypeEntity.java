package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitiveCategory;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 敏感类型与它的探测器配置（{@code lakehouse_sensitive_type}，设计见
 * {@code docs/sensitive-data-design.md} §4.1）。
 *
 * <p><b>一行 = 一种可被识别的敏感类型</b>（{@code CN_ID_CARD} / {@code CN_MOBILE} /
 * {@code PERSON_NAME} …）。内置的类型随启动 seed（服务端侧的
 * {@code SensitiveTypeInitializer}），
 * <b>不可删、只可停用</b>——删掉一个内置类型会让历史 finding 指向一个不存在的
 * {@code type_code}，控制台渲染标签时就只剩一个孤零零的字符串。
 *
 * <p><b>seed 是 upsert 且不覆盖用户改动。</b>升级时新增一个内置类型要能进到已有库里，
 * 但「治理员把 {@code CN_ID_CARD} 的阈值从 0.85 调到 0.99」这个决定不能被下次启动
 * 冲掉——因此已存在就跳过（判据是 {@code type_code}），只在字段为空时补。
 *
 * <p><b>{@code auto_apply} 与 {@code min_confidence} 是两条独立开关</b>
 * （设计稿 §5.3）：前者是「这个类型允不允许自动落标」，后者是「置信度要到多少」，
 * <b>两者同时满足</b>才自动落标。之所以要前者：{@code PERSON_NAME} / {@code ADDRESS}
 * 这类误报率天然高（{@code name} 可能是商品名），一旦自动应用就是**误脱敏**——
 * 数据被 mask 掉但没人知道为什么，业务侧只看到值突然全成了星号。
 *
 * <p><b>{@code level} 不出 {@code CORE} / {@code IMPORTANT}</b>：那两档按国家与行业
 * 目录人工认定（GB/T 43697-2024），不是探测器能推的结论。
 */
@Entity
@Table(name = "lakehouse_sensitive_type",
        uniqueConstraints = @UniqueConstraint(name = "uk_sensitive_type_code",
                columnNames = "type_code"),
        comment = "敏感类型与探测器配置（内置类型不可删、只可停用）")
@Getter
@Setter
public class SensitiveTypeEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    /** 稳定标识，如 {@code CN_ID_CARD}。标签键形如 {@code PII.CN_ID_CARD}，用它拼。 */
    @Column(name = "type_code", length = 64, nullable = false,
            comment = "类型编码（稳定标识，如 CN_ID_CARD）；标签键形如 PII.CN_ID_CARD")
    private String typeCode;

    /** 展示名，如「中国大陆居民身份证号」。 */
    @Column(name = "type_name", length = 128, nullable = false, comment = "展示名（如 中国大陆居民身份证号）")
    private String typeName;

    /** 类别：{@code PII} / {@code PCI} / {@code PHI} / {@code SECRET} / {@code FINANCIAL}。 */
    @Column(name = "category", length = 32, nullable = false,
            comment = "类别：PII / PCI / PHI / SECRET / FINANCIAL")
    private String category;

    /**
     * 默认分级（{@code L1}–{@code L4}）。
     *
     * <p>落库用 {@link SensitivityLevel#name()}。**不出现 {@code CORE} / {@code IMPORTANT}**
     * ——那两档只能人工打标（设计稿 §5.5）。
     */
    @Column(name = "level", length = 8, nullable = false,
            comment = "默认分级 L1-L4；CORE / IMPORTANT 只人工打，不进类型表")
    private String level;

    /**
     * 五层探测器的配置（JSON）。
     *
     * <p>形状：{@code {"name":{"patterns":[...],"weight":0.35},"type":{...},
     * "value":{"regex":"...","validator":"CN_ID_CARD","weight":0.40},"dict":{...},
     * "stat":{...}}}。**缺一层 = 这层没开**，不是「这层还不存在」——
     * 因此 P0 只实现 D1/D2 时，配置里出现 {@code value} 段是合法的（只是不生效），
     * 与「没有这一层」是两种状态。
     *
     * <p>存 JSON 文本而不是拆列：五层探测器的字段形态差异很大（正则、校验器名、
     * 字典项、阈值），拆成十几列里大半是空的，而且每加一层都要改表。
     */
    @Column(name = "detectors_json", length = 65535,
            comment = "五层探测器配置（JSON；缺一层 = 这层没开）")
    private String detectorsJson;

    /**
     * 是否允许自动应用（默认 {@code false}）。
     *
     * <p>只有「格式强、误判代价低」的类型该开（设计稿 §5.3 的白名单：
     * {@code CN_ID_CARD} / {@code CN_MOBILE} / {@code BANK_CARD} / {@code CN_USCC} /
     * {@code EMAIL} / {@code IP} / {@code CREDENTIAL}）。
     * **默认关**，因为开了它就意味着「扫描可以直接改脱敏行为」，而误脱敏的代价
     * 比多一条待审高得多——宁可多问一次。
     */
    @Column(name = "auto_apply", nullable = false,
            comment = "是否允许自动应用（默认 false；仅强格式类型应开，见设计稿 §5.3）")
    private boolean autoApply;

    /**
     * 自动应用阈值（默认 0.85）。
     *
     * <p>与 {@link #autoApply} 同时满足才落标。可按 catalog 覆盖——同一个
     * {@code CN_ID_CARD}，A 库用 0.85、B 库用 0.99 是正常诉求（新接入的库先只收建议）。
     * 覆盖动作本身就是一次治理决定，因此进审计。
     */
    @Column(name = "min_confidence", nullable = false,
            comment = "自动应用阈值（默认 0.85；与 auto_apply 同时满足才自动落标）")
    private double minConfidence = 0.85d;

    /** 内置类型：随启动 seed，**不可删、只可停用**。 */
    @Column(name = "builtin", nullable = false,
            comment = "内置类型：随启动 seed，不可删只可停用（删除返 409）")
    private boolean builtin;

    /** 停用后不再参与识别；**历史标签保留**（停用不是撤回结论）。 */
    @Column(name = "enabled_flag", nullable = false,
            comment = "是否参与识别；停用后不再命中，但历史标签保留")
    private boolean enabled = true;
}
