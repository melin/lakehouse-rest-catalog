package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.GroupRoleMappingEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 「IdP 组 → 主体角色」映射仓储。 */
public interface GroupRoleMappingRepository extends JpaRepository<GroupRoleMappingEntity, String> {

    List<GroupRoleMappingEntity> findAllByOrderByProviderNameAscIdpGroupAscRoleNameAsc();

    List<GroupRoleMappingEntity> findByProviderNameOrderByIdpGroupAscRoleNameAsc(String providerName);

    List<GroupRoleMappingEntity> findByProviderNameAndIdpGroupOrderByRoleNameAsc(
            String providerName, String idpGroup);

    /**
     * 一次取出「这个人所属的全部组」的映射。
     *
     * <p>登录路径上的关键查询：每个组一次往返会让登录变成 N+1 次数据库访问，
     * 而组数在企业 IdP 里动辄几十个。查回来的是全部映射（含停用的），
     * 由调用方按 {@code enabled} 过滤——停用的行也要参与「这个组是否已映射」的判定，
     * 否则控制台会把「停用」显示成「未映射」，管理员会去重复建一条。
     */
    List<GroupRoleMappingEntity> findByProviderNameAndIdpGroupIn(String providerName,
            Collection<String> idpGroups);

    Optional<GroupRoleMappingEntity> findByProviderNameAndIdpGroupAndRoleName(
            String providerName, String idpGroup, String roleName);

    boolean existsByProviderNameAndIdpGroupAndRoleName(
            String providerName, String idpGroup, String roleName);
}
