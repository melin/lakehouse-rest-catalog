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
 * <p>{@code federated} 标记该角色是否来自外部身份提供方（规格字段）。
 * 本服务不接入外部 IdP，因此该字段只作为标记保存与回显。
 */
@Entity
@Table(name = "lakehouse_principal_role",
        uniqueConstraints = @UniqueConstraint(name = "uk_principal_role_name", columnNames = "name"))
@Getter
@Setter
public class PrincipalRoleEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "federated", nullable = false)
    private boolean federated;

    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535)
    private Map<String, String> properties = new LinkedHashMap<>();

    @Column(name = "entity_version", nullable = false)
    private int entityVersion;
}
