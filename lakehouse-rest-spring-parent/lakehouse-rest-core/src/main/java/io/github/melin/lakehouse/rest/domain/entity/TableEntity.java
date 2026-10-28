package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 表实体。
 *
 * <p>{@code id} 即规格 {@code GetTableResponse.id}，也是
 * {@code GET /v1/{prefix}/tables/id/{tableId}} 使用的标识。
 * 当前 schema 冗余存放在 {@code schema_doc}，历史版本在
 * {@link TableSchemaVersionEntity}，以支持 {@code rollback-schema}。
 */
@Entity
@Table(name = "lakehouse_table",
        uniqueConstraints = @UniqueConstraint(name = "uk_table", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = {
                @Index(name = "ix_table_catalog", columnList = "catalog_id"),
                @Index(name = "ix_table_database", columnList = "database_id")
        },
        comment = "表实体（Paimon 表与 Iceberg 表共用）")
@Getter
@Setter
public class TableEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键，即规格的 GetTableResponse.id")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false, comment = "所属库 id")
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false, comment = "表名，库内唯一")
    private String name;

    /** 表的物理根路径。 */
    @Column(name = "path_value", length = 1024, comment = "表的物理根路径")
    private String path;

    /** 外部注册（register）而来的表标记为 true。 */
    @Column(name = "external_flag", nullable = false, comment = "是否为 register 注册进来的外部表")
    private boolean external;

    /** 当前 schema 版本号，从 0 递增。 */
    @Column(name = "schema_id", nullable = false, comment = "当前 schema 版本号，从 0 递增")
    private long schemaId;

    /** 当前 schema 的 JSON 文档。 */
    @Column(name = "schema_doc", length = 1048576, comment = "当前 schema 的 JSON 文档")
    private String schemaDoc;

    /** 表类型，取自 schema options 的 {@code type}，默认 PAIMON。 */
    @Column(name = "table_type", length = 64, comment = "表类型，取自 schema options 的 type，默认 PAIMON")
    private String tableType = "PAIMON";

    /** 最新快照 id / uuid，未提交过快照时为 null。 */
    @Column(name = "latest_snapshot_id", comment = "最新快照 id；未提交过快照时为空")
    private Long latestSnapshotId;

    @Column(name = "latest_snapshot_uuid", length = 64, comment = "最新快照 uuid")
    private String latestSnapshotUuid;

    // ------------------------------------------------------- Iceberg 侧的四列
    //
    // 这四列只对 catalog_format=ICEBERG 的表有值；Paimon 表全部为 null。
    // 为什么不新开一张表：Iceberg 的 TableMetadata 与 Paimon 的表模型在「位置、当前 schema、
    // 最新快照」这三处本来就是同构的（见 docs/iceberg-rest-catalog-design.md §4.1），
    // 另开一张表会让「列出 catalog 下的表」「列出一张表的所有快照」这类查询变成 union。
    // 加列的代价只是「Paimon 侧的行多四个 null」。

    /**
     * <b>Iceberg 整份 {@code TableMetadata} JSON，Iceberg 表的唯一真相源。</b>
     *
     * <p>Iceberg 的元数据是一份**整体快照**（每次 commit 生成一份新的 JSON），
     * 而 Paimon 侧是逐资源建行。这里照抄本工程已有的那一套
     * （{@code lakehouse_catalog.storage_config_json} 是真相源、{@code storage_type} 与
     * {@code allowed_locations_json} 是它的投影，见 {@code docs/data-model.md}）：
     * <b>文档是真相源，{@code lakehouse_snapshot} / {@code lakehouse_branch} / {@code lakehouse_tag}
     * 里的行是它的派生投影。</b>
     *
     * <p>列类型必须是 mediumtext（{@code length = 1048576}）。大表的 metadata 含
     * snapshot-log 与 metadata-log，用 {@code varchar(8192)} 这种「看起来够大」的值
     * 会在某张大表上以 {@code Data too long} 炸掉——而且是在提交成功、数据已落盘之后。
     */
    @Column(name = "metadata_doc", length = 1048576,
            comment = "Iceberg TableMetadata 原文 JSON，Iceberg 表的唯一真相源")
    private String metadataDoc;

    /**
     * 当前元数据文件路径：{@code <location>/metadata/00003-<uuid>.metadata.json}。
     *
     * <p>两个身份：给引擎的「去读哪个文件」的指针，以及**乐观并发的 CAS 条件**。
     * 提交时的落库语句形如
     * {@code update lakehouse_table set metadata_location = :new, ... where id = :id and metadata_location = :expected}；
     * 影响 0 行说明期间被别人提交过，回 409（与优化批次
     * {@code update ... where id = ? and next_run_at = ?} 抢占是同一个手法）。
     */
    @Column(name = "metadata_location", length = 1024,
            comment = "当前元数据文件路径，同时是提交时乐观并发的 CAS 条件")
    private String metadataLocation;

    /** Iceberg 的 {@code table-uuid}：表的身份，跨重命名与改位置保持不变。 */
    @Column(name = "table_uuid", length = 64, comment = "Iceberg table-uuid，跨重命名与改位置保持不变")
    private String tableUuid;

    /** Iceberg 的 {@code format-version}（1 / 2 / 3）；Paimon 表为 {@code null}。 */
    @Column(name = "format_version", comment = "Iceberg format-version（1/2/3）；Paimon 表为空")
    private Integer formatVersion;

    /**
     * Lance dataset 的属性（{@code DescribeTable.properties} / {@code DeclareTable} 入参）。
     *
     * <p><b>为什么必须新增一列而不是复用既有的：</b>
     * {@code metadata_doc} 是 **Iceberg {@code TableMetadata} 的真相源**——往里塞 Lance
     * 属性会让两个格式的语义互相污染（谁改这一列都等于改另一个格式的元数据）；
     * {@code options} 在 Paimon 侧是 **schema 的一部分、要随 schema 文件写进仓库**，
     * 而 Lance 面的属性不进任何文件。两个既有的「属性位」都有明确归属，
     * 复用任何一个都是把 Lance 的语义借放在别人的抽屉里。
     *
     * <p>这一列同样服务 {@code generic-table-design.md} 的通用表（它的
     * {@code properties} 也没有落处），因此列名叫 {@code properties_json} 而不是
     * {@code lance_properties}。
     */
    @Convert(converter = JsonConverters.StringMap.class)
    @Column(name = "properties_json", length = 65535, comment = "Lance 表 / 通用表的属性（JSON 对象）")
    private Map<String, String> propertiesJson = new LinkedHashMap<>();

    /**
     * Lance：最近一次读到的版本号（P1）。
     *
     * <p><b>纯展示缓存，不参与任何判定</b>——这是它唯一能安全存在的用法。
     * 一旦有逻辑读它（比如「下一个版本 = 它 + 1」），缓存就变成了第二个真相源，
     * 而真相在 {@code _versions/} 里、且不由我们写。缓存写失败**不能**让请求失败：
     * 调用方要的是 schema 与位置，版本号是附带的。
     */
    @Column(name = "table_version", comment = "Lance：最近一次读到的版本号（纯展示缓存，不参与判定）")
    private Long tableVersion;

    /**
     * Lance：{@code managed_versioning}——{@code true} 时这张表的版本由**服务端记账**
     * （{@code CreateTableVersion} 那一组，P2）。
     *
     * <p>开关放在列上而不是每次去 {@code properties_json} 里查那个键：
     * 它是一个**硬承诺**（客户端看到 {@code true} 就改用版本端点写），
     * 因此必须有一个「唯一、可索引、能被断言」的落点，
     * 而不是散落在透传属性里的一行字符串。
     */
    @Column(name = "managed_versioning", nullable = false,
            comment = "Lance：版本是否由服务端记账（managed_versioning）")
    private boolean managedVersioning;
}
