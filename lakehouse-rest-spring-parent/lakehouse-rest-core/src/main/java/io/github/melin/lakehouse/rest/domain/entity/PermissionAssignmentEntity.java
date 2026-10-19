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
 * Paimon 权限模型的落库形态：「某 principal 在某资源上拥有某 access」。
 *
 * <p><b>这是唯一的授权表。</b>形状与官方 REST Management API 的三元组
 * {@code (resource, access, principal)} 一致，access 取值就是官方那 12 项。
 * 原先并存的 {@code lakehouse_catalog_role} 与 {@code lakehouse_resource_grant}
 * （Polaris 的 RBAC 链）已整体移除，判定层因此只剩这一个真相源。
 *
 * <p>principal 是官方语义下的不透明字符串（{@code user:alice} /
 * {@code role:sales_writer}），本服务端只存原文、不外键到主体表——官方规格明说
 * principal 的解析是服务端职责，且「实现可以把 wire 定位符解析成内部持久化 id，
 * 内部 id 不对外暴露」。主体名与 principal 字符串的关系是「命名约定」而非
 * 「外键引用」：令牌认证出的主体名按约定匹配（见
 * {@code AuthorizationService#principalCandidates}），管理面删掉一个主体时
 * 这里的赋权随之失效（匹配不上），不需要级联清理。
 *
 * <p>resource 用 {@code resourceType + databaseName + objectName} 三个字段等价表达
 * （catalog 级两者为空串，COLUMN 挂在其表上）。唯一约束落在
 * {@code locatorHash}（database 与 object 的拼接摘要）而不是两列原文上：
 * 标识符长度没有上界，MySQL 的索引键上限是 3072 字节。
 */
@Entity
@Table(name = "lakehouse_permission_assignment",
        uniqueConstraints = @UniqueConstraint(name = "uk_permission_assignment",
                columnNames = {"catalog_id", "resource_type", "locator_hash", "access", "principal"}),
        indexes = {
                @Index(name = "ix_permission_assignment_catalog", columnList = "catalog_id"),
                @Index(name = "ix_permission_assignment_principal", columnList = "principal"),
        },
        comment = "唯一授权表：principal 在某个资源上的某项 access")
@Getter
@Setter
public class PermissionAssignmentEntity extends AuditedEntity {

    /** database 与 object 拼接时的分隔符，取 ASCII 单元分隔符，正常标识符不会包含。 */
    public static final String LOCATOR_SEPARATOR = "\u001f";

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id，赋权的作用域")
    private String catalogId;

    /** 官方 {@code PermissionResource.type}：CATALOG / CATALOG_ALL / DATABASE / ... 。 */
    @Column(name = "resource_type", length = 32, nullable = false,
            comment = "官方资源类型：CATALOG / CATALOG_ALL / DATABASE / ...")
    private String resourceType;

    /** database 定位符；catalog 级为空串。 */
    @Column(name = "database_name", length = 255, nullable = false,
            comment = "database 定位符；catalog 级为空串")
    private String databaseName = "";

    /** 对象定位符（表 / 视图 / 函数名）；catalog 与 database 级为空串。 */
    @Column(name = "object_name", length = 255, nullable = false,
            comment = "对象名（表/视图/函数）；catalog 与 database 级为空串")
    private String objectName = "";

    /** 官方 access 词表：SELECT / UPDATE / ALTER / ... 。 */
    @Column(name = "access", length = 32, nullable = false,
            comment = "官方 access 词表取值：SELECT / UPDATE / ALTER / ...")
    private String access;

    /** principal 原文，官方上限 128 字符，入库前已校验。 */
    @Column(name = "principal", length = 128, nullable = false,
            comment = "收权方 principal 原文（user:alice / role:sales_writer）")
    private String principal;

    /** 过期时间（epoch 毫秒）；null 表示永不过期。 */
    @Column(name = "expire_time", comment = "过期时刻（epoch 毫秒）；为空表示永不过期")
    private Long expireTime;

    /**
     * COLUMN 赋权的列范围 JSON（{@code columnNames} / {@code excludedColumnNames}）；
     * 非 COLUMN 赋权为 null。列范围不属于赋权身份，重复授予整体替换。
     */
    @Column(name = "columns_json", length = 4096, comment = "COLUMN 赋权的列范围 JSON；非 COLUMN 赋权为空")
    private String columnsJson;

    /** {@code databaseName + objectName} 的定长摘要，承担唯一约束；见 {@link Digests}。 */
    @Column(name = "locator_hash", length = Digests.HEX_LENGTH, nullable = false,
            comment = "database_name 与 object_name 拼接后的 SHA-256 摘要，唯一约束落在它上面")
    private String locatorHash = "";

    /** 依据当前定位符重算摘要。 */
    public void refreshLocatorHash() {
        this.locatorHash = Digests.sha256Hex(databaseName, LOCATOR_SEPARATOR, objectName);
    }

    /**
     * 摘要放进生命周期回调：不依赖调用方是否记得先刷新，落库前摘要一定与定位符一致。
     */
    @PrePersist
    @PreUpdate
    void refreshLocatorHashOnWrite() {
        refreshLocatorHash();
    }
}
