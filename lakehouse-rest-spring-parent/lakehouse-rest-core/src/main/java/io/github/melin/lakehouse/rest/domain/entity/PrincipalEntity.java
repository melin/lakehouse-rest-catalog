package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理主体（principal）。
 *
 * <p>对应管理规格的 {@code Principal}。主体是访问服务的身份，本身不直接持有权限：
 * 权限授予 catalog role，catalog role 授予 principal role，principal role 再授予主体
 * （见 {@code docs/management-api-contract.md} 第 2 节）。
 *
 * <p>凭据以「clientId + 客户端密钥摘要」形式保存。规格要求创建、轮换、重置时
 * 返回 {@code PrincipalWithCredentials}，明文密钥只在响应当中出现一次，
 * 因此本表只存 {@code secretHash}，不存明文。
 */
@Entity
@Table(name = "lakehouse_principal",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_principal_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_principal_client_id", columnNames = "client_id")
        },
        comment = "管理主体（访问服务的身份）")
@Getter
@Setter
public class PrincipalEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "name", length = 255, nullable = false, comment = "主体名，全局唯一")
    private String name;

    @Column(name = "client_id", length = 255, comment = "OAuth2 client id，全局唯一")
    private String clientId;

    /** 密钥摘要（{@code SHA-256(salt || secret)} 的十六进制），不存明文。 */
    @Column(name = "secret_hash", length = 128, comment = "密钥摘要 SHA-256(salt || secret)，不存明文")
    private String secretHash;

    @Column(name = "secret_salt", length = 64, comment = "密钥盐，与 secret_hash 配套")
    private String secretSalt;

    @Column(name = "credential_rotation_required", nullable = false, comment = "是否要求下次使用时轮换凭据")
    private boolean credentialRotationRequired;

    @Convert(converter = StringMap.class)
    @Column(name = "properties_json", length = 65535, comment = "主体属性（JSON 对象）")
    private Map<String, String> properties = new LinkedHashMap<>();

    /** 乐观并发版本号，对应规格的 {@code entityVersion}。每次变更自增。 */
    @Column(name = "entity_version", nullable = false, comment = "乐观并发版本号，每次变更自增")
    private int entityVersion;

    /**
     * 主体从哪儿来：本地创建还是 SSO 首次登录自动创建。
     *
     * <p>{@code columnDefinition} 里带 {@code default 'LOCAL'} 不是装饰：升级时
     * {@code ALTER TABLE ... ADD COLUMN ... NOT NULL} 在有存量行的表上会直接失败，
     * 而存量行本来就该全部算本地主体（{@code docs/sso-catalog-identity-design.md} §3.5）。
     * 缺省值让「加列」这一步对线上是纯增量。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false,
            columnDefinition = "varchar(16) default 'LOCAL'",
            comment = "主体来源：LOCAL（管理面创建）/ OIDC（首次 SSO 登录自动创建）")
    private PrincipalSource source = PrincipalSource.LOCAL;

    /**
     * 外部身份标识：{@code issuer + "|" + sub}。
     *
     * <p>它不参与授权，只用来回答「同名的两个主体是不是同一个人」，以及 IdP 换
     * issuer 后运维能一眼看出哪些主体失联。长度 512 是因为 issuer 本身可能很长
     * （Keycloak 带 realm 路径、Cognito 带 region 与 user pool id），
     * 而 {@code sub} 在不同 IdP 下没有统一上限。
     */
    @Column(name = "external_id", length = 512,
            comment = "外部身份标识 issuer + \"|\" + sub，仅 OIDC 主体有值")
    private String externalId;

    /**
     * 显示名：{@code email} 或 {@code preferred_username}。
     *
     * <p><b>不参与授权、不作为主体名</b>。主体名用 {@code sub}（稳定），
     * 显示名只解决「审计里全是 UUID，认不出人」的问题。
     */
    @Column(name = "display_name", length = 255,
            comment = "显示名（email / preferred_username），仅展示用，不参与授权")
    private String displayName;

    /**
     * 最后一次成功 SSO 认证的时刻（epoch 毫秒）。
     *
     * <p>与 {@code AuditedEntity} 的 {@code created_at} / {@code updated_at} 同单位：
     * 一张表里混用「日期时间」与「毫秒整数」两种时间表示，比较与排序都要先做转换，
     * 迟早会有人拿 {@code last_login_at} 去和 {@code updated_at} 直接相减。
     */
    @Column(name = "last_login_at", comment = "最后一次成功 SSO 认证时刻（epoch 毫秒）")
    private Long lastLoginAt;
}
