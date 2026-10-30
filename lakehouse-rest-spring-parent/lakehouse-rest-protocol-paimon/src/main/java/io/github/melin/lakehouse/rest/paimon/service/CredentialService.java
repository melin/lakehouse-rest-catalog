package io.github.melin.lakehouse.rest.paimon.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.PermissionDtos;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.support.Codecs;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageCredentialManager;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表级数据访问授权，对应 Polaris 的凭证下发模型。
 *
 * <p>引擎不直接持有对象存储的长期密钥：先向目录服务申请一份范围限定到单表、
 * 有明确过期时间的凭据，服务端确认调用方有权访问该表后才签发。
 *
 * <p><b>下发什么由 catalog 的存储类型决定。</b>本类只负责定位 catalog、
 * 走缓存、把结果的过期时刻算出来；具体产出哪些键、密钥从哪一级配置取，
 * 都交给 {@link StorageRuntimePolicy} 选定的 {@link StorageCredentialManager}。
 * 这样各存储类型（S3 / Azure / GCS / OBS / OSS / FILE）的键名与取值规则集中在一个类里，
 * 不会因为类型变多而把本类写成一张大分支表。
 *
 * <p><b>过期时刻来自缓存。</b>同一份凭据在有效期内被反复请求时，返回的是同一个
 * 过期时刻，而不是「当前时间 + TTL」。差异在这里很实际：后者会让调用方以为
 * 每次拿到的都是一份全新的、完整时长的凭据，而实际上密钥没变。
 *
 * <p><b>票的写权限跟着调用者走，不由建 catalog 的人决定。</b>同一个 {@code /token}，
 * 对该表有 {@code UPDATE} 的人拿到的是可写票（policy 覆盖读写），只有 {@code SELECT}
 * 的人拿到只读票。这条判据放在这里而不是放在表单上：建 catalog 的人通常不知道
 * 谁会来写（设计稿 §2），而「能不能写这张表」与「有没有 {@code UPDATE}」是同一个问题。
 *
 * <p><b>鉴权分两步。</b>「能调这个端点」是拦截器按 {@code CatalogAccessRules} 判的
 * {@code USE_CREDENTIAL}；「票里能不能含库里存的那对长期密钥」在这里再判一次
 * {@code USE_SECRET}。第二步必须留在这里：它取决于**签发结果**而不是路径，
 * 同一个端点可能发短期票、也可能发库里的 AK/SK。
 */
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final TableLookup tableLookup;
    private final CatalogRepository catalogRepository;
    private final StorageRuntimePolicy storagePolicy;
    private final StorageCredentialCache cache;
    private final AuthorizationService authorization;

    /** {@code GET .../tables/{table}/token}：签发数据访问凭据。 */
    @Transactional(readOnly = true)
    public TableDtos.GetTableDataTokenResponse token(String prefix, String database, String table) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        CatalogEntity catalog = catalogRepository.findById(target.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, prefix));
        StorageConfigInfo storage = StorageConfigs.of(catalog);

        // 票里要不要写权限，判据是这个调用者**对这张表**有没有 UPDATE——
        // 不是建 catalog 时谁选了什么（设计稿 §2.3）。协议形状不变：字段一个没多，
        // policy 变宽变窄。写票与只读票在缓存里也是两个键，否则先到的那一次
        // 决定了后来者拿到哪一种。
        boolean allowWrite = authorization.allows(prefix, PermissionDtos.ResourceType.TABLE,
                List.of(database), table, Access.UPDATE);
        String key = StorageCredentialCache.key(catalog.getId(), storage, target.getPath(), allowWrite);
        // singleflight：一批引擎同时启动时，同一张表只会打一次 AssumeRole
        StorageCredentialCache.Cached cached = cache.resolve(key,
                () -> storagePolicy.credentialManager()
                        .vend(storage, target.getId(), target.getPath(), allowWrite));

        // 端点本身要求 USE_CREDENTIAL（能取票）；票里若有**没有时限的那对长期密钥**，
        // 还要 USE_SECRET（能拿走存着的钥匙）。判据在凭据自己身上，所以只能在这之后判：
        // 路径上区分不出来——同一个 /token，relay 发的是库里的 AK/SK，s3-token 发的是
        // 现签的短期票，用配置反推就会造出「配了 STS 但其实没签」这类错位（设计稿 §5.8）。
        if (cached.credential().carriesLongTermKeyPair()) {
            // 本文件里的 ResourceType 是 support 包那个（ApiException 的定位符），
            // 权限判定要的是 PermissionDtos 里那个同名的枚举——两者不是一回事，写全限定名
            authorization.require(prefix, PermissionDtos.ResourceType.TABLE, List.of(database), table,
                    Access.USE_SECRET, "receive the long-term access key stored in catalog " + prefix
                            + " (grant USE_SECRET on this table, or switch the catalog to a"
                            + " short-lived credential provider)");
        }

        // 同一个时刻回两个名字：规格写的是 expiresAt，Paimon 客户端读的是 expiresAtMillis。
        // 理由与后果见 GetTableDataTokenResponse 的注释。
        long expiresAt = cached.expiresAtMillis();
        return new TableDtos.GetTableDataTokenResponse(cached.credential().token(), expiresAt, expiresAt);
    }

    /**
     * {@code POST .../tables/{table}/auth}：查询鉴权。
     *
     * <p>返回该查询需要附加的行过滤表达式与列脱敏规则；请求的列若不在表 schema 中，
     * 直接以 403 拒绝。当前实现不配置策略，因此返回空的过滤与脱敏规则。
     */
    @Transactional(readOnly = true)
    public TableDtos.AuthTableQueryResponse auth(String prefix, String database, String table,
                                                 TableDtos.AuthTableQueryRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request != null && request.select() != null && !request.select().isEmpty()) {
            Set<String> known = new HashSet<>(columnNames(Codecs.readSchema(target.getSchemaDoc())));
            for (String column : request.select()) {
                if (column != null && !column.isBlank() && !known.contains(column)) {
                    throw ApiException.forbidden("Table has no permission for column: " + column);
                }
            }
        }
        return new TableDtos.AuthTableQueryResponse(new ArrayList<>(), new LinkedHashMap<>());
    }

    private static List<String> columnNames(TypeDtos.Schema schema) {
        List<String> names = new ArrayList<>();
        for (TypeDtos.DataField field : schema.getFields()) {
            if (field != null && field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
    }
}
