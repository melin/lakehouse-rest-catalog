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
 * 行过滤 / 列脱敏的表级策略（{@code lakehouse_data_policy}，设计见
 * {@code docs/row-filter-column-mask-design.md} §5.2）。
 *
 * <p><b>一行 = 一张表的一套策略</b>：行过滤至多一条（{@code row_filter_json} 可空）、
 * 列脱敏在 {@link ColumnMaskEntity} 里一列一行。同一张表命中多条策略的「挑一条执行」
 * 从数据模型上就不存在——Databricks 的第 3 条语义（多条同时命中报错）在这里退化成
 * 「写不进第二条」。
 *
 * <p><b>按三名字符串绑定，不按 table_id。</b>两种格式的表都没有跨格式统一的稳定 id
 * （Paimon 没有 table id，Iceberg 的 table-uuid 不能对齐），第一版只能按
 * {@code catalog.database.table} 绑。后果与处置（§5.2 取舍 4）：DROP 级联删策略、
 * RENAME 跟随改名、CREATE 不继承——三条钩子在
 * {@code io.github.melin.lakehouse.rest.event.DataPolicyLifecycleListener}，
 * 漏钩子的最坏结果交给引擎侧 fail-closed 兜。
 *
 * <p><b>全名唯一键用摘要列而不是三列联合。</b>三列联合索引在 utf8mb4 下是
 * {@code (64 + 255 + 512) × 4 = 3328} 字节，超过 MySQL 单索引 3072 上限；摘要定长 64
 * 索引长度与值长度解耦——与血缘边表（{@code LineageTableEdgeEntity}）同一做法。
 * 展示与过滤仍用三段原名列：列表页要按 catalog 过滤、详情页要显示全名。
 *
 * <p><b>策略不是表属性</b>（§5.2 取舍 2）：表属性对所有客户端可见，会泄露
 * 「这张表被保护了、保护成什么样」；且 {@code ALTER TABLE} 能改属性就会绕过策略自己的判权。
 */
@Entity
@Table(name = "lakehouse_data_policy",
        uniqueConstraints = @UniqueConstraint(name = "uk_data_policy_table",
                columnNames = "full_name_hash"),
        indexes = @Index(name = "ix_data_policy_catalog", columnList = "catalog_name"),
        comment = "行过滤/列脱敏的表级策略（一表一行，行过滤 + 豁免名单 + 版本）")
@Getter
@Setter
public class DataPolicyEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    @Column(name = "catalog_name", length = 64, nullable = false,
            comment = "绑定的 catalog 名；列表页按它过滤")
    private String catalogName;

    @Column(name = "database_name", length = 255, nullable = false,
            comment = "绑定的库名（Iceberg 多级 namespace 按点号拼成一段）")
    private String databaseName;

    @Column(name = "table_name", length = 512, nullable = false, comment = "绑定的表名")
    private String tableName;

    /**
     * 三段全名 {@code catalog.database.table} 的 SHA-256，唯一键。
     *
     * <p>为什么不直接在三列上建唯一键：见类注释——联合索引超长。写入方（服务层）
     * 负责保持它与三列一致；改名钩子同时更新两处，漂移只会来自绕过服务层的写法。
     */
    @Column(name = "full_name_hash", length = 64, nullable = false,
            comment = "catalog.database.table 的 SHA-256，唯一键（索引长度与值长度解耦）")
    private String fullNameHash;

    /**
     * 行过滤表达式（AST JSON，§5.3.1）。
     *
     * <p><b>存结构化表达式，不存 SQL 文本。</b>AST 在结构上不存在「子查询」「函数调用」
     * 这些注入形态；SQL 文本只是给人看的（{@link #rowFilterSql}，SHOW POLICY 与控制台
     * 渲染用它），两侧语义以 AST 为准。
     *
     * <p>可空：一张表可以只脱敏列、不过滤行。
     */
    @Column(name = "row_filter_json", length = 65535,
            comment = "行过滤表达式（白名单 AST 的 JSON；空 = 不过滤行）")
    private String rowFilterJson;

    /** 行过滤的 SQL 渲染（只用于展示；落库时由 AST 生成，改策略时随 AST 重算）。 */
    @Column(name = "row_filter_sql", length = 2048,
            comment = "行过滤的 SQL 渲染（展示用；语义以 row_filter_json 为准）")
    private String rowFilterSql;

    /**
     * 豁免主体名单：名单内的人读这张表完全不走策略（明文）。
     *
     * <p>豁免的语义在<b>下发</b>侧实现：{@code resolve} 认出调用者在名单里就返回空策略，
     * 引擎侧不需要知道豁免逻辑的存在（§1.3「表 owner 豁免」那行）。
     * 名单本身是敏感信息——查看它需要 {@code TABLE:ALTER}（§8.1），与改策略同权。
     */
    @Column(name = "exempt_json", length = 65535,
            comment = "豁免主体名单（JSON 字符串数组；名单内的人不走策略）")
    private String exemptJson;

    /**
     * 策略开关：{@code false} = 这套策略暂停执行，但定义保留。
     *
     * <p>「停用」只在控制台提供，SQL 侧没有对应语句（§13.2 的清单只有物理删除）——
     * 临时放行是一个治理决定，从命令行随手敲一条 SQL 做掉不留审计痕迹，不合适。
     */
    @Column(name = "enabled_flag", nullable = false,
            comment = "策略开关；false = 暂停执行但定义保留（只在控制台提供）")
    private boolean enabled = true;

    /**
     * 强制范围声明（§7.1）：{@code engine} = 引擎内强制（当前唯一取值），
     * {@code advisory} = 仅提示。第一版一律 {@code engine}，它表达的是
     * 「谁承诺执行」，不是「实际强度」——同一份 engine 策略在裸 Spark 集群上是 L0，
     * 在 Kyuubi 后面是 L1，控制台显示部署等级而不是这个字段。
     */
    @Column(name = "enforced_scope", length = 16, nullable = false,
            comment = "强制范围声明：engine（引擎内强制）；表达谁承诺执行，不是实际强度")
    private String enforcedScope = "engine";

    /**
     * 乐观并发版本号，一列两用（§5.2 取舍 3）：改策略时 {@code WHERE} 级比对
     * （后提交者 409，与 Iceberg 提交、catalog 实体同一机制），下发时作为 etag——
     * 客户端缓存策略，就必须能判断「这份缓存过期了没有」。
     *
     * <p>刻意不用 JPA {@code @Version}：既有实体（{@code CatalogEntity} 等）全是
     * 「普通列 + 服务层比对」的口径，PUT 请求体里的 {@code baseVersion} 要与它比对后
     * 手工递增——挂 @Version 反而把「版本不匹配」变成 Hibernate 的
     * {@code OptimisticLockException}，错误形状对不上管理 API 的 409。
     */
    @Column(name = "entity_version", nullable = false,
            comment = "乐观并发版本号；改策略时比对，下发时作为 etag")
    private int entityVersion;
}
