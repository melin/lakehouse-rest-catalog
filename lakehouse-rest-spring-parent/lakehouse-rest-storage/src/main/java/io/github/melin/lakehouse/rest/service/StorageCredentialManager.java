package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.CredentialManagerType;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.List;

/**
 * 数据访问凭据的下发策略。
 *
 * <p>对应 Polaris 的 {@code PolarisCredentialManager}：按 catalog 的存储配置，
 * 决定把什么交给引擎去访问该仓库。实现按
 * {@code lakehouse.rest.credential-manager.type} 选用，见 {@link StorageRuntimePolicy}。
 *
 * <p>入参是 {@code StorageConfigInfo} 而不是表实体：凭据取决于「这个 catalog
 * 用哪种存储、什么地址、哪个具名存储」，与具体是哪张表无关。
 * 表路径单独传入，是为了支持按路径收窄的凭据（S3 的 session policy、
 * Azure 的 SAS 作用域都收窄到表目录）。
 *
 * <p><b>三个入口对应两类消费者，不能合并。</b>
 * <ul>
 *   <li>{@link #vend(StorageConfigInfo, String, String)}：引擎的票，按表收窄，只读。
 *       这是高频路径（每读一张表一次）。</li>
 *   <li>{@link #vend(StorageConfigInfo, String, String, boolean)}：同上，但显式要写权限。</li>
 *   <li>{@link #vendForCatalog}：**服务端自己**的票，按 catalog 的允许位置收窄。
 *       它是「服务端也是凭据消费者」那件事的落点（设计稿 §5.7）：给服务端的动作签
 *       表级票，表现是列目录 / 删表目录 403，而报错指向存储、与 catalog 无关。</li>
 * </ul>
 */
public interface StorageCredentialManager {

    /** 本实现对应的配置取值。 */
    CredentialManagerType type();

    /**
     * 为该存储配置签发一次凭据（表级，只读）。
     *
     * <p>默认实现转调四参版本。保留这个签名是因为 Paimon {@code /token}、
     * Iceberg {@code /credentials}、Lance {@code storage_options} 三处都用它，
     * 而它们要的确实只是「能读这张表」。
     *
     * @param storage   catalog 的存储配置
     * @param tableId   表 id，作为自包含令牌的作用域标识
     * @param tablePath 表的基路径，可为 {@code null}
     * @throws io.github.melin.lakehouse.rest.support.ApiException 配置缺失或本部署不提供该能力
     */
    default VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath) {
        return vend(storage, tableId, tablePath, false);
    }

    /**
     * 同上，但显式声明这次要不要写权限。
     *
     * <p>写权限只在服务端自己写元数据时用得上（例如 Paimon 的
     * {@code schema-<n>}）。引擎侧一律只读：协议上读写分不开，先收紧。
     *
     * @param allowWrite 是否把写对象（PutObject / DeleteObject）放进票里
     */
    VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath,
                                 boolean allowWrite);

    /**
     * 服务端自己访问仓库用的票：作用域是 catalog 的整份允许位置。
     *
     * <p><b>为什么不复用用户的票。</b>一是作用域不对（用户的票只覆盖一张表）；
     * 二是归属不对——服务端的动作不该记在某个用户头上，云侧审计里必须能分开。
     *
     * @param storage          catalog 的存储配置
     * @param catalogId        catalog id，进作用域标识与 {@code RoleSessionName}
     * @param allowedLocations catalog 的允许位置；为空时实现应当拒绝签发
     * @param allowWrite       服务端这次是否要写（删目录、写元数据）
     */
    VendedStorageCredential vendForCatalog(StorageConfigInfo storage, String catalogId,
                                           List<String> allowedLocations, boolean allowWrite);
}
