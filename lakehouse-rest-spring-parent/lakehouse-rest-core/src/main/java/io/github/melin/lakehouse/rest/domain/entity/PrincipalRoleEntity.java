package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * principal role（主体角色）。
 *
 * <p>对应管理规格的 {@code PrincipalRole}，语义是**纯分组**：它自己不携带任何权限，
 * 只把授予 {@code role:<name>} 的那条赋权转发给成员（见
 * {@code AuthorizationService#principalCandidates}）。成员关系落在
 * {@link PrincipalRoleAssignmentEntity}。
 *
 * <p>原先「principal role → catalog role → 权限」的中转层已随 catalog role 整体移除，
 * 因此这里不再有指向 catalog role 的成员关系。
 *
 * <p>{@code federated} 标记该角色是否来自外部身份提供方（管理规格字段）。
 * 本服务不接入外部 IdP，因此该字段**只作为标记保存与回显**：授权判定不读它，
 * 成员关系也不受它影响；{@code PUT} 不接受它，建好之后无法改回。
 * 正因如此控制台不再暴露这个开关（勾了没有任何行为差异，只是给列表多一列死信息），
 * 但字段保留——它属于管理规格的 {@code PrincipalRole} 形状，且
 * {@code SHOW PRINCIPAL ROLES} 会把它作为一列返回。
 */
@Entity
@Table(name = "lakehouse_principal_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_principal_role_name", columnNames = "name"),
        comment = "主体角色：纯分组，自身不携带权限")
@Getter
@Setter
public class PrincipalRoleEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "name", length = 255, nullable = false, comment = "角色名，全局唯一")
    private String name;

    @Column(name = "federated", nullable = false,
            comment = "是否来自外部身份提供方（本服务只作标记保存与回显）")
    private boolean federated;

    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535, comment = "角色属性（JSON 对象）")
    private Map<String, String> properties = new LinkedHashMap<>();

    @Column(name = "entity_version", nullable = false, comment = "乐观并发版本号，每次变更自增")
    private int entityVersion;
}
