package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.GrantSource;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 「principal role 授予主体」关系仓储。 */
public interface PrincipalRoleAssignmentRepository
        extends JpaRepository<PrincipalRoleAssignmentEntity, String> {

    List<PrincipalRoleAssignmentEntity> findByPrincipalId(String principalId);

    /**
     * 只取某个来源授予的成员关系。
     *
     * <p>组同步的差集<b>只能</b>由这一组行构成：手工授权的角色永远不出现在
     * 「该删的」集合里，否则 IdP 里少一个组就会连管理员手工授的角色一起删掉
     * （{@code docs/sso-catalog-identity-design.md} §5.2）。
     */
    List<PrincipalRoleAssignmentEntity> findByPrincipalIdAndGrantedBy(String principalId,
            GrantSource grantedBy);

    List<PrincipalRoleAssignmentEntity> findByPrincipalRoleId(String principalRoleId);

    boolean existsByPrincipalIdAndPrincipalRoleId(String principalId, String principalRoleId);

    void deleteByPrincipalId(String principalId);

    void deleteByPrincipalRoleId(String principalRoleId);

    void deleteByPrincipalIdAndPrincipalRoleId(String principalId, String principalRoleId);
}
