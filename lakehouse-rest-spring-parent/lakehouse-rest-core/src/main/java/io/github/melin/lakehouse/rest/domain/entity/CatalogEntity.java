package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringList;
import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * catalog 实例。REST 路径中的 {@code {prefix}} 就是它的对外标识，
 * 由 {@code GET /v1/config?warehouse=...} 下发给客户端。
 *
 * <p>{@code defaults} 与 {@code overrides} 参与客户端的配置合并
 * （defaults → 客户端属性 → overrides，后者优先）。
 */
@Entity
@Table(name = "lakehouse_catalog",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_prefix", columnNames = "prefix_value"),
        comment = "catalog 实例，REST 路径中的 {prefix} 即其对外标识")
@Getter
@Setter
public class CatalogEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    /** 对外暴露的 prefix，客户端后续所有路径都以它开头。 */
    @Column(name = "prefix_value", length = 255, nullable = false,
            comment = "对外 prefix，客户端后续所有 REST 路径都以它开头（全局唯一）")
    private String prefix;

    @Column(name = "warehouse", length = 1024, comment = "默认仓库根路径")
    private String warehouse;

    @Convert(converter = StringMap.class)
    @Column(name = "defaults_json", length = 65535,
            comment = "下发给客户端的默认配置（合并顺序的第一层，可被客户端覆盖）")
    private Map<String, String> defaults = new LinkedHashMap<>();

    @Convert(converter = StringMap.class)
    @Column(name = "overrides_json", length = 65535,
            comment = "强制覆盖的配置（合并顺序的最后一层，优先级最高）")
    private Map<String, String> overrides = new LinkedHashMap<>();

    /** INTERNAL：元数据由本服务托管；EXTERNAL：外部同步、只读。 */
    @Column(name = "catalog_type", length = 32,
            comment = "INTERNAL：元数据由本服务托管；EXTERNAL：外部同步、只读")
    private String catalogType = "INTERNAL";

    /**
     * 本 catalog 服务哪一种 REST Catalog 协议：{@code PAIMON} / {@code ICEBERG}。
     *
     * <p><b>与 {@code catalogType} 是两条正交的属性</b>，因此另开一列而不是往那个枚举里
     * 加取值——{@code EXTERNAL + ICEBERG}（外部 Iceberg 仓库的只读入口）是
     * {@code catalogType} 单独表达不了的状态。它也不进任何唯一约束：一个 catalog
     * 只有一种格式，格式不是键的一部分。
     *
     * <p>存字符串而不是 {@code @Enumerated(EnumType.STRING)}：与同表其余状态列
     * （{@code catalog_type} / {@code storage_type}）保持一致，且这个列还会被
     * 管理 API 直接读写（{@code catalogFormat} 字段），字符串少一层转换。
     *
     * <p><b>创建后不可变</b>，理由见 {@link io.github.melin.lakehouse.rest.support.CatalogFormat}。
     * 存量数据零迁移：默认值就是本特性引入前的事实（全部 Paimon）。
     *
     * <p><b>为什么这一列有 {@code not null} 与库级默认值，而同表其它状态列没有。</b>
     * 这一列是**分派键**：读不出来（{@code null}）时按什么解释都有道理，而选错的后果是
     * 整套请求走错协议面。库级默认值让「手工 INSERT、旧脚本、运维直接改库」这些
     * 绕开 JPA 的写入也落到 {@code PAIMON}——那正是本特性引入前它们唯一能表达的东西。
     * 升级已有库时用
     * {@code alter table lakehouse_catalog add column catalog_format varchar(32) not null default 'PAIMON';}
     * （带默认值的加列，MySQL 会把存量行一并回填）。
     */
    @Column(name = "catalog_format", length = 32, nullable = false,
            comment = "REST 协议格式 PAIMON / ICEBERG，创建后不可变")
    @ColumnDefault("'PAIMON'")
    private String catalogFormat = "PAIMON";

    /**
     * 管理 API 视角下的 catalog 属性。
     *
     * <p>与 {@code defaults} / {@code overrides} 分开存放：后两者参与客户端的
     * 配置合并，只对支持它们的引擎有意义；这里是 catalog 的通用属性，
     * 由 {@code /api/management/v1/catalogs} 读写。
     */
    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535, comment = "管理 API 视角下的 catalog 通用属性")
    private Map<String, String> properties = new LinkedHashMap<>();

    /**
     * 管理规格 {@code StorageConfigInfo} 的完整形态，按 {@code storageType} 判别。
     *
     * <p>这是存储配置的唯一真相来源。下面两列（{@code storage_type}、
     * {@code allowed_locations_json}）是它的**投影**：写入时由同一个方法一起落库，
     * 保留它们是为了能直接在 SQL 里按存储类型与位置筛选 catalog，
     * 也便于管理员用肉眼核对。读取时一律以本字段为准。
     */
    @Convert(converter = JsonConverters.StorageConfig.class)
    @Column(name = "storage_config_json", length = 65535, comment = "存储配置完整形态，存储配置的唯一真相源")
    private StorageConfigInfo storageConfig;

    /** {@code StorageConfigInfo.storageType} 的投影：存储实现类型。 */
    @Column(name = "storage_type", length = 32,
            comment = "存储实现类型（storage_config_json 的投影，读取以真相源为准）")
    private String storageType = "FILE";

    /** {@code StorageConfigInfo.allowedLocations} 的投影。 */
    @Convert(converter = StringList.class)
    @Column(name = "allowed_locations_json", length = 65535,
            comment = "允许使用的存储位置白名单（storage_config_json 的投影）")
    private List<String> allowedLocations = new ArrayList<>();

    /** 乐观并发版本号，对应管理规格的 {@code entityVersion}。 */
    @Column(name = "entity_version", nullable = false,
            comment = "乐观并发版本号，对应管理 API 的 entityVersion")
    private int entityVersion;
}
