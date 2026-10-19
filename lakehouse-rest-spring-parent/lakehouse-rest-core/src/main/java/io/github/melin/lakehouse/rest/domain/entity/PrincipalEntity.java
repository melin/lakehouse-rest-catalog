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
}
