package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.scan.SensitivityEnums.SensitivityLevel;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.TagSource;
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
 * 列级标签：**结论的当前状态**（{@code lakehouse_column_tag}，设计见
 * {@code docs/sensitive-data-design.md} §4.4 / §4.7）。
 *
 * <p><b>与服务端侧的 {@code SensitivityFindingEntity} 是两张表的同一件事的两面</b>
 * （finding 表在 server 模块，此处不写成链接，避免 core → server 方向的依赖）：
 * finding 是「扫描这轮看到了什么」（每天重跑、会重复产生），tag 是
 * 「现在这列是什么」（只该有一份）。合并成一张表立刻出现两个问题：同列堆出
 * 30 条 pending，以及重扫把人工拒绝的结论洗回待审。分开之后，重扫只新增 finding，
 * 已有人工结论的 tag 不受影响。
 *
 * <p><b>不复用快照标签 {@code lakehouse_tag}。</b>那张表的名字里也有 tag，
 * 但它记的是表快照（Iceberg tag / branch 语义），与列级治理结论毫无关系——
 * 两者混在一张表里，`tag_key` 这个列会同时承载「快照名」与「PII.CN_ID_CARD」，
 * 任何一个查询都要先分辨这一行的语义。因此新表另起一个名字，
 * 并在表注释里写明「与快照标签 lakehouse_tag 无关」。
 *
 * <p><b>{@code column_name} 空串 = 表级标签</b>（照抄血缘边「常量列用空串占位」的
 * 既有做法，不引入可空列）。表级标签的值 = 该表所有列标签的 {@code max(level)}，
 * 供目录检索与「这张表能不能对外」的粗判。
 *
 * <p><b>唯一键是 {@code (column_ref_hash, tag_key)} 两列，不是三段 + 列名。</b>
 * 列级全名 {@code catalog.database.table.column} 在 utf8mb4 下
 * {@code (64+255+512+255)×4 = 4344} 字节，远超 MySQL 单索引 3072 上限；摘要定长 64，
 * 索引长度与值长度解耦——与 {@link DataPolicyEntity} 的 {@code full_name_hash} 同一做法。
 * 三段 + 列名原名列只用于展示与按 catalog 过滤，写入方（服务层）负责保持两者一致。
 *
 * <p>唯一键含 {@code tag_key} 而不是只有 {@code column_ref_hash}：一列可以有多个标签键
 * （{@code PII.CN_ID_CARD} 与 {@code owner.team} 并存），但**同一个键只能有一个值**——
 * 从数据模型上排除「同列同键两个值」，与 {@link ColumnMaskEntity} 的
 * {@code (policy_id, column_name)} 同一思路。
 */
@Entity
@Table(name = "lakehouse_column_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_column_tag",
                columnNames = {"column_ref_hash", "tag_key"}),
        indexes = @Index(name = "ix_column_tag_catalog", columnList = "catalog_name"),
        comment = "列级标签（治理标签；与快照标签 lakehouse_tag 无关）")
@Getter
@Setter
public class ColumnTagEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    @Column(name = "catalog_name", length = 64, nullable = false,
            comment = "绑定的 catalog 名；列表页按它过滤")
    private String catalogName;

    @Column(name = "database_name", length = 255, nullable = false, comment = "库名")
    private String databaseName;

    @Column(name = "table_name", length = 512, nullable = false, comment = "表名")
    private String tableName;

    /** 列名；**空串 = 表级标签**（照抄血缘边「常量列用空串占位」的做法，不引入可空列）。 */
    @Column(name = "column_name", length = 255, nullable = false,
            comment = "列名；空串 = 表级标签")
    private String columnName;

    /**
     * {@code catalog.db.table.column} 的 SHA-256，唯一键的一半。
     *
     * <p>为什么不直接在三段 + 列名上建唯一键：见类注释——联合索引超长。
     * 改名钩子（P2）同时更新两处；漂移只会来自绕过服务层的写法。
     */
    @Column(name = "column_ref_hash", length = 64, nullable = false,
            comment = "catalog.db.table.column 的 SHA-256（唯一键，索引长度与值长度解耦）")
    private String columnRefHash;

    /** 标签键。敏感标签形如 {@code PII.CN_ID_CARD}；手工标签自由（{@code owner.team}）。 */
    @Column(name = "tag_key", length = 128, nullable = false,
            comment = "标签键；敏感标签形如 PII.CN_ID_CARD，手工标签自由")
    private String tagKey;

    /**
     * 标签值。敏感标签存分级。
     *
     * <p><b>自动识别只产出 {@code L1}–{@code L4}</b>；{@code CORE} / {@code IMPORTANT}
     * 只能人工打（设计稿 §5.5）。可空：手工标签（如 {@code owner.team}）不一定有值。
     */
    @Column(name = "tag_value", length = 255,
            comment = "标签值；敏感标签存分级 L1-L4（CORE / IMPORTANT 仅人工）")
    private String tagValue;

    /** 来源：{@code AUTO} / {@code MANUAL} / {@code INHERITED}（血缘继承，P2）。 */
    @Column(name = "source", length = 16, nullable = false,
            comment = "来源：AUTO（自动应用）/ MANUAL（人工）/ INHERITED（血缘继承）")
    private String source = TagSource.MANUAL.name();

    /** 关联的敏感类型；手工标签可空（{@code owner.team} 没有类型）。 */
    @Column(name = "type_code", length = 64, comment = "关联的敏感类型；手工标签可空")
    private String typeCode;

    /** 置信度；只 {@code AUTO} / {@code INHERITED} 有值（人工打标不打分）。 */
    @Column(name = "confidence", comment = "置信度 0-1；AUTO / INHERITED 才有")
    private Double confidence;

    /** 继承来源列全名（{@code source=INHERITED} 时有值）。 */
    @Column(name = "inherited_from", length = 1024, comment = "继承来源列全名（INHERITED 才有）")
    private String inheritedFrom;

    /** 溯源：这条 tag 是哪次扫描的哪条 finding 落下来的。 */
    @Column(name = "finding_id", comment = "溯源到哪条 finding")
    private Long findingId;

    /**
     * 乐观并发版本号，同时作为 etag。
     *
     * <p>刻意不用 JPA {@code @Version}：既有实体全是「普通列 + 服务层比对」的口径，
     * PUT 请求体里的 {@code entityVersion} 要与它比对后手工递增——挂 {@code @Version}
     * 反而把「版本不匹配」变成 Hibernate 的 {@code OptimisticLockException}，
     * 错误形状对不上管理 API 的 409（与 {@link DataPolicyEntity} 同一理由）。
     */
    @Column(name = "entity_version", nullable = false,
            comment = "乐观并发版本号，同时作为 etag")
    private int entityVersion;

    /** 停用 = 保留记录但不参与策略展开（撤回保护动作，但不丢历史）。 */
    @Column(name = "enabled_flag", nullable = false,
            comment = "是否参与策略展开；false = 保留记录但不生效")
    private boolean enabled = true;

    /**
     * 该标签的分级，读 {@link #tagValue} 得来。
     *
     * <p>放在实体上而不是让每个调用方各写一遍：表级级别、分级分布、
     * 「{@code level=L4} 过滤」三处都要它，而「{@code tagValue} 不是合法分级」这种行
     * （手工标签）在每一处都要处理——收口在这里，调用方永远拿 {@code null}。
     */
    public SensitivityLevel level() {
        return SensitivityLevel.parse(tagValue).orElse(null);
    }
}
