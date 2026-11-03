package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 「IdP 组 → 主体角色」的翻译表。
 *
 * <p>它是本设计里唯一新增的表，解决的也只有一个问题：把 IdP 的组名翻译成
 * 本服务端的 principal role 名。翻译的结果<strong>不落在这张表里</strong>——
 * 真正生效的是 {@code lakehouse_principal_role_grant} 中 {@code granted_by=IDP} 的行，
 * 于是授权判定链一行都不用改（{@code docs/sso-catalog-identity-design.md} §3.4）。
 *
 * <p><b>为什么从第一天就带 {@code providerName}。</b>加列容易、改唯一键要重建索引，
 * 而多 IdP 是明确会来的需求。本期它恒为 {@code default}，等真的接第二个 IdP 时
 * 不需要再动表结构。
 *
 * <p><b>停用而不删（{@link #enabled}）。</b>发现映射配错时的第一反应是删掉，
 * 但删掉之后「当时是怎么配的」就没了，回滚只能凭记忆。停用保留行、把效果撤回，
 * 出问题时点一下就能恢复。
 */
@Entity
@Table(name = "lakehouse_group_role_mapping",
        uniqueConstraints = @UniqueConstraint(name = "uk_group_role_mapping",
                columnNames = {"provider_name", "idp_group", "role_name"}),
        // 登录时每次都要按「这个人的组」批量查映射，这条索引就是那条热路径
        indexes = @Index(name = "ix_group_role_mapping_group",
                columnList = "provider_name,idp_group"),
        comment = "IdP 组到主体角色的映射（组同步的翻译表）")
@Getter
@Setter
public class GroupRoleMappingEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    /**
     * 身份提供方标识。本期恒为 {@code default}（单 IdP）。
     *
     * <p>写进唯一键而不是运行时拼在组名前：组名里带分隔符时，拼接方案会
     * 让 {@code a:b} + {@code c} 与 {@code a} + {@code b:c} 撞成同一个键。
     */
    @Column(name = "provider_name", length = 128, nullable = false,
            comment = "身份提供方标识，单 IdP 部署恒为 default")
    private String providerName = "default";

    /**
     * IdP 里的组名，<b>精确匹配</b>：区分大小写、不 trim、不做前缀/通配。
     *
     * <p>不 trim 是刻意的。Keycloak 的 group membership mapper 打开
     * {@code full group path} 之后组名是 {@code /data-engineers}，
     * 关掉是 {@code data-engineers}——两者相差一个斜杠。如果这里悄悄 trim，
     * 「差一个斜杠」这种配置错误就永远查不出来，只表现为「同步了 0 个角色」。
     */
    @Column(name = "idp_group", length = 255, nullable = false,
            comment = "IdP 组名，精确匹配（区分大小写、不 trim）")
    private String idpGroup;

    /** 目标主体角色名。名称不存在时同步会跳过并告警，不会让登录失败。 */
    @Column(name = "role_name", length = 255, nullable = false, comment = "目标主体角色名")
    private String roleName;

    /** 停用而不删：效果撤回、行还在，回滚只需要再打开。 */
    @Column(name = "enabled", nullable = false, comment = "是否生效；停用不删除")
    private boolean enabled = true;

    /** 乐观并发版本号：两个管理员同时改同一行时，后提交的收到 409 而不是覆盖。 */
    @Column(name = "entity_version", nullable = false, comment = "乐观并发版本号，每次变更自增")
    private int entityVersion;
}
