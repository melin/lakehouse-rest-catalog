package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 人审否决的**永久抑制项**（{@code lakehouse_sensitivity_suppression}，设计见
 * {@code docs/sensitive-data-design.md} §7.2）。
 *
 * <p><b>它解决的是一个死循环，不是一条记录。</b>识别器认为某列是身份证 →
 * 人审判定不是（比如那是「身份证后四位」的掩码列）→ 第二天重扫又判成敏感 →
 * 人再拒一次。没有抑制项，这个循环每天都要人手点一遍，而人手是有限的资源
 * ——最后的结果是没人再看那个人审队列，闸门形同不存在。
 *
 * <p><b>键是 {@code (column_ref_hash, type_code)}，不是 finding 或 tag。</b>
 * 三者的区别是这一张表存在的理由：
 * <ul>
 *   <li>按 finding 记会让抑制项跟着「哪一轮扫描」走——下一轮就没有抑制了；</li>
 *   <li>按 tag 记不行：拒绝的语义恰恰是**没有**标签；</li>
 *   <li>按列哈希 + 类型记，才表达得出「这一列不是身份证」这个**结论**
 *       （而不是「上一次那条命中被否了」）。</li>
 * </ul>
 *
 * <p><b>失效只有两条路，这是刻意的：</b>
 * <ol>
 *   <li><b>列被重命名</b>——哈希变了，于是它是一条新列，抑制项不会跟着走过去
 *       （§7.2 的「视为新列」）。这条不靠代码，靠哈希本身的语义；</li>
 *   <li><b>治理员显式解禁</b>——{@link #enabledFlag} 置 {@code false} 而不是删行。
 *       留行是为了让「谁在什么时候解禁、原来是谁拒的」还能查得到：抑制项是
 *       一条治理决策，删掉它就等于删掉决策史，而 {@code REJECTED} 的 finding
 *       行虽然也留着，却答不了「后来有没有被解禁」。</li>
 * </ol>
 *
 * <p><b>为什么它不并进 {@code lakehouse_column_tag}。</b>那个表的唯一键是
 * {@code (column_ref_hash, tag_key)}，语义是「这一列现在是什么标签」；
 * 抑制项是「这一列被判定**不是**某类型」，两者互斥（有抑制项时不该有对应标签）。
 * 合并会让「没有标签」这一个状态有两种表示（无行 / 有一行 value 是空），
 * 而两种表示迟早会在某个查询里被当成不同的东西。
 */
@Entity
@Table(name = "lakehouse_sensitivity_suppression",
        uniqueConstraints = @UniqueConstraint(name = "uk_sensitivity_suppression",
                columnNames = {"column_ref_hash", "type_code"}),
        indexes = @Index(name = "ix_sensitivity_suppression_catalog", columnList = "catalog_name"),
        comment = "人审否决的永久抑制项（按列哈希 + 类型记，重扫不再自动打标）")
@Getter
@Setter
public class SensitivitySuppressionEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    @Column(name = "catalog_name", length = 64, nullable = false, comment = "catalog 名")
    private String catalogName;

    @Column(name = "database_name", length = 255, nullable = false, comment = "库名")
    private String databaseName;

    @Column(name = "table_name", length = 512, nullable = false, comment = "表名")
    private String tableName;

    @Column(name = "column_name", length = 255, nullable = false, comment = "列名")
    private String columnName;

    /** {@code catalog.db.table.column} 的 SHA-256；**唯一键的一半，也是「重命名即失效」的载体**。 */
    @Column(name = "column_ref_hash", length = 64, nullable = false,
            comment = "catalog.db.table.column 的 SHA-256（列改名后哈希变，抑制项自动失效）")
    private String columnRefHash;

    @Column(name = "type_code", length = 64, nullable = false, comment = "被否决的敏感类型")
    private String typeCode;

    /** 否决理由（人审备注或系统生成的一句话），进审计与界面悬浮提示。 */
    @Column(name = "reason", length = 512, comment = "否决理由（人审备注）")
    private String reason;

    /** 否决人主体名；与 {@code createdBy} 的区别是后者可能被审计归一化改写。 */
    @Column(name = "decided_by", length = 255, comment = "否决人主体名")
    private String decidedBy;

    @Column(name = "decided_at", comment = "否决时刻（epoch 毫秒）")
    private Long decidedAt;

    /** 来源 finding（可空，溯源用；finding 是历史，可能先于本行被清理）。 */
    @Column(name = "finding_id", comment = "来源 finding 的 id（溯源用，可空）")
    private Long findingId;

    /**
     * 是否仍生效。解禁 = 置 {@code false}（不删行，保留决策史）。
     *
     * <p>扫描侧的判定必须**同时**看这一列与哈希：只看哈希会把解禁过的项当成仍生效，
     * 表现为「解禁了但明天又复发了」——而用户会以为是解禁按钮坏了。
     */
    @Column(name = "enabled_flag", nullable = false, comment = "是否仍生效；解禁置 false，不删行")
    private boolean enabledFlag = true;
}
