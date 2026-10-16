package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.support.Digests;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Paimon 原生权限模型的落库形态：「某 principal 在某资源上拥有某 access」。
 *
 * <p>与 {@link ResourceGrantEntity}（Polaris 模型，授权挂在 catalog role 上）是**两套并行的
 * 存储**，对应官方 REST Management API 的 permission 端点。principal 是官方语义下的
 * 不透明字符串（{@code user:alice} / {@code role:sales_writer}），本服务端只存原文、
 * 不外键到主体表——官方规格明说 principal 的解析是服务端职责，且「实现可以把
 * wire 定位器解析成内部持久化 id，内部 id 不对外暴露」，但本工程的主体名与
 * principal 字符串的关系是「命名约定」而非「外键引用」：令牌认证出的主体名
 * 直接按约定匹配（见 {@code PaimonPermissionModel}），管理面删掉一个主体时
 * 这里的赋权随之失效（匹配不上），不需要级联清理。
 *
 * <p>落库形态与官方三元组身份 {@code (resource, access, principal)} 一致：
 * resource 用 {@code resourceType + databaseName + objectName} 三个字段等价表达
 * （catalog 级两者为空串，COLUMN 挂在其表上）。唯一约束落在
 * {@code locatorHash}（database 与 object 的拼接摘要）而不是两列原文上，
 * 理由与 {@link ResourceGrantEntity} 相同：标识符长度没有上界，
 * MySQL 的索引键上限是 3072 字节。
 */
@Entity
@Table(name = "paimon_permission_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_permission_assignment",
                columnNames = {"catalog_id", "resource_type", "locator_hash", "access", "principal"}),
        indexes = {
                @Index(name = "ix_permission_assignment_catalog", columnList = "catalog_id"),
                @Index(name = "ix_permission_assignment_principal", columnList = "principal"),
        })
@Getter
@Setter
public class PermissionAssignmentEntity extends AuditedEntity {

    /** database 与 object 拼接时的分隔符，取 ASCII 单元分隔符，正常标识符不会包含。 */
    public static final String LOCATOR_SEPARATOR = "\u001f";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    /** 官方 {@code PermissionResource.type}：CATALOG / CATALOG_ALL / DATABASE / ... 。 */
    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;

    /** database 定位符；catalog 级为空串。 */
    @Column(name = "database_name", length = 255, nullable = false)
    private String databaseName = "";

    /** 对象定位符（表 / 视图 / 函数名）；catalog 与 database 级为空串。 */
    @Column(name = "object_name", length = 255, nullable = false)
    private String objectName = "";

    /** 官方 access 词表：SELECT / UPDATE / ALTER / ... 。 */
    @Column(name = "access", length = 32, nullable = false)
    private String access;

    /** principal 原文，官方上限 128 字符，入库前已校验。 */
    @Column(name = "principal", length = 128, nullable = false)
    private String principal;

    /** 过期时间（epoch 毫秒）；null 表示永不过期。 */
    @Column(name = "expire_time")
    private Long expireTime;

    /**
     * COLUMN 赋权的列范围 JSON（{@code columnNames} / {@code excludedColumnNames}）；
     * 非 COLUMN 赋权为 null。列范围不属于赋权身份，重复授予整体替换。
     */
    @Column(name = "columns_json", length = 4096)
    private String columnsJson;

    /** {@code databaseName + objectName} 的定长摘要，承担唯一约束；见 {@link Digests}。 */
    @Column(name = "locator_hash", length = Digests.HEX_LENGTH, nullable = false)
    private String locatorHash = "";

    /** 依据当前定位符重算摘要。 */
    public void refreshLocatorHash() {
        this.locatorHash = Digests.sha256Hex(databaseName, LOCATOR_SEPARATOR, objectName);
    }

    /**
     * 摘要放进生命周期回调：与 {@link ResourceGrantEntity#refreshNamespaceHash()} 同理，
     * 不依赖调用方是否记得先刷新，落库前摘要一定与定位符一致。
     */
    @PrePersist
    @PreUpdate
    void refreshLocatorHashOnWrite() {
        refreshLocatorHash();
    }
}
