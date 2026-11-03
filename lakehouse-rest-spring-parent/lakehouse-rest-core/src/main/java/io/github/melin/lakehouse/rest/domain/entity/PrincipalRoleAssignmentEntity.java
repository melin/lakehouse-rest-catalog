package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 「principal role 授予主体」关系。
 *
 * <p>对应 {@code PUT /principals/{principalName}/principal-roles}。规格允许
 * principal 与 principal role 之间多对多，因此关系独立成表而不是放在任一侧。
 */
@Entity
@Table(name = "lakehouse_principal_role_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_principal_role_grant",
                columnNames = {"principal_id", "principal_role_id"}),
        indexes = @Index(name = "ix_principal_role_grant_role", columnList = "principal_role_id"),
        comment = "主体与主体角色的成员关系（多对多）")
@Getter
@Setter
public class PrincipalRoleAssignmentEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "principal_id", length = 64, nullable = false, comment = "主体 id")
    private String principalId;

    @Column(name = "principal_role_id", length = 64, nullable = false, comment = "主体角色 id")
    private String principalRoleId;

    /**
     * 这条成员关系是谁给的：手工授予还是登录时按 IdP 组同步出来。
     *
     * <p><b>组同步永远只删 {@code IDP} 的行</b>（{@code docs/sso-catalog-identity-design.md}
     * §5.2 不变量 1）。少了这一列，IdP 里少一个组就会连管理员手工授的角色一起删掉，
     * 而事后只能看到「权限没了」，看不到「谁删的」。
     *
     * <p>{@code columnDefinition} 里的 {@code default 'MANUAL'} 让升级变成纯增量：
     * 存量行全部视为手工授权，同步逻辑对它们完全不可见。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "granted_by", length = 16, nullable = false,
            columnDefinition = "varchar(16) default 'MANUAL'",
            comment = "授予来源：MANUAL（管理面手工）/ IDP（登录时组同步）")
    private GrantSource grantedBy = GrantSource.MANUAL;
}
