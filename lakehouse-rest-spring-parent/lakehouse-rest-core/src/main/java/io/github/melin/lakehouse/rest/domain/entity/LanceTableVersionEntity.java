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
 * Lance **托管版本**的版本账（{@code managed_versioning=true} 时才会有行）。
 *
 * <p><b>为什么必须有一张表，而不复用 {@code lakehouse_table} 上的某一列。</b>
 * 托管版本的语义是「版本号由服务端分配」，而分配的正确性靠两件事：
 *
 * <ul>
 *   <li><b>唯一约束 {@code (table_id, version)}</b>——它是 {@code put_if_not_exists}
 *       的落点：两个客户端抢同一个版本号时，数据库只允许一个插入成功，
 *       另一个拿到唯一键冲突 → {@code 409 ConcurrentModification}。
 *       靠「先查再插」做不到这件事（两步之间有窗口）；</li>
 *   <li><b>一行一个版本</b>——{@code ListTableVersions} 要列历史，
 *       {@code BatchDeleteTableVersions} 要按范围删，两者都要按版本号检索。</li>
 * </ul>
 *
 * <p><b>本行不是数据集的真相源。</b>manifest 文件仍然由客户端写在
 * {@code _versions/} 下，我们只记「这个版本对应哪个文件」。因此删这里的行
 * **不动数据**（规范对 {@code BatchDeleteTableVersions} 的说明同此），
 * 也因此在表被 deregister 时要一并清掉。
 *
 * <p><b>与 P0/P1「0 张新表」的关系。</b>那句话只包含 P0 与 P1——
 * 它们不记账，版本由 Lance 原生机制管。P2 把「版本分配」接到服务端，
 * 代价就是这张表；这也正是设计文档把 P2 单列出来评审的原因。
 */
@Entity
@Table(name = "lakehouse_lance_table_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_lance_table_version",
                columnNames = {"table_id", "version"}),
        indexes = @Index(name = "ix_lance_table_version_table", columnList = "table_id"),
        comment = "Lance 托管版本（managed_versioning）的版本账；删行不动数据集")
@Getter
@Setter
public class LanceTableVersionEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    /** 关联 {@link TableEntity#getId()}。 */
    @Column(name = "table_id", length = 64, nullable = false, comment = "关联的表 id")
    private String tableId;

    @Column(name = "catalog_id", length = 64, nullable = false,
            comment = "所属 catalog id（与表冗余，删表时按 catalog 清理）")
    private String catalogId;

    /**
     * 版本号。
     *
     * <p>由**客户端**在请求里给出，而不是服务端分配：规范里
     * {@code CreateTableVersionRequest.version} 是必填，服务端的职责是
     * 「这个版本号我记下了」并在重复时拒绝（{@code put_if_not_exists}）。
     * 自作主张改成服务端分配，会让客户端的重试逻辑（它按自己算的下一个版本重试）
     * 永远拿不到它要的那个号。
     */
    @Column(name = "version", nullable = false, comment = "Lance 版本号")
    private long version;

    @Column(name = "manifest_path", length = 1024, nullable = false,
            comment = "该版本 manifest 的完整路径")
    private String manifestPath;

    /** 可选：manifest 字节数。 */

    @Column(name = "manifest_size", comment = "manifest 字节数（客户端给了才记）")
    private Long manifestSize;

    /** 可选：对象存储返回的 ETag，用于乐观并发。 */
    @Column(name = "e_tag", length = 128, comment = "manifest 的 ETag（乐观并发用）")
    private String eTag;

    /** 可选：版本级 metadata（规范里是 string→string）。 */
    @Convert(converter = JsonConverters.StringMap.class)
    @Column(name = "metadata_json", length = 65535, comment = "版本级 metadata（JSON 对象）")
    private Map<String, String> metadataJson = new LinkedHashMap<>();
}
