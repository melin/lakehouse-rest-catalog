package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paths;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Lance 面共用的装配：catalog 定位、格式校验、location 生成、凭据下发、声明态探测。
 *
 * <p><b>这一面唯一的数据面控制点是 {@code storage_options}</b>（{@link #storageOptions}）：
 * 客户端拿它去开数据集，之后我们再也看不到它。因此这里的策略与 Iceberg 面一致，
 * 不因为「Lance 的表是客户端建的」就放宽——恰恰相反，正因为数据不是我们写的，
 * 凭据是我们唯一能管的东西。
 *
 * <p><b>catalog 定位用第一级 {@code id}</b>（{@code cat$schema$table} 里的 {@code cat}），
 * 它不是 {@code /v1/{prefix}} 那种 prefix：Lance 客户端只配 {@code uri}，
 * 分级全靠 {@code id}。格式校验走 {@code resolveForFormat(..., LANCE)}——
 * 命中非 Lance 的 catalog 时它抛 404，由 {@code GlobalExceptionHandler} 转成
 * Lance 形状的 {@code NamespaceNotFound}，而不是 400 或 403。这个选择看着像丢信息，
 * 实则是唯一不会让客户端误判的答案：规范里没有「格式不匹配」这个错误码。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LanceCatalogSupport {

    /** 数据集目录的后缀。Lance 生态的惯例，也便于与同目录下的其它产物区分。 */
    private static final String DATASET_SUFFIX = ".lance";

    /** 探测数据集时不带版本号的两种入口：V1 的首个 manifest 与 V2 的版本提示文件。 */
    private static final List<String> DATASET_MARKERS = List.of(
            "_versions/1.manifest", "_versions/latest_version_hint.json");

    private final CatalogService catalogService;
    /**
     * 凭据经 {@code StorageRuntimePolicy} 取，而不是直接注入一个
     * {@code StorageCredentialManager}：容器里有 default / noop 两个实现，
     * 「用哪个」是部署配置决定的（{@code credential-manager.type}），
     * 直接按类型注入会在启动期以 NoUniqueBeanDefinitionException 失败。
     */
    private final StorageRuntimePolicy runtimePolicy;
    private final StorageFileAccess files;
    private final RestServerProperties properties;

    /**
     * 按第一级 {@code id} 取 catalog，并要求它是 Lance 格式。
     *
     * <p>未登记的 catalog 会被 {@code resolveForFormat} **自动登记**吗？不会——
     * 那一侧的自动登记只发生在 {@code /v1/**} 的既有路径上；这里传入的期望格式
     * 已经由基址确定了（能打到 {@code /lance/v1/**} 的请求就是要 Lance），
     * 因此按 Lance 建是唯一自洽的选择，这里显式传 {@link CatalogFormat#LANCE}。
     */
    public CatalogEntity requireCatalog(String catalogName) {
        return catalogService.resolveForFormat(catalogName, CatalogFormat.LANCE);
    }

    // ------------------------------------------------------------------ location

    /**
     * 表位置的默认取值：{@code <warehouse>/<schema>.db/<table>.lance}。
     *
     * <p>与 Iceberg 面共用同一个模板函数（{@link Paths#tableLocation}），
     * 只多一个后缀。共用不是偷懒：位置一旦按格式分叉，「运维按仓库目录找表」
     * 这件事就要先知道格式，而仓库目录本身不告诉你格式。
     *
     * @throws LanceErrors.LanceException catalog 没有配置 warehouse 时（400）
     */
    public String defaultLocation(CatalogEntity catalog, String schema, String table) {
        String warehouse = catalog.getWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw LanceErrors.invalidInput("catalog \"" + catalog.getPrefix()
                    + "\" has no warehouse configured; location must be given explicitly");
        }
        String template = properties.getPathTemplate();
        String base = Paths.tableLocation(template, warehouse, schema, table);
        return base.endsWith(DATASET_SUFFIX) ? base : base + DATASET_SUFFIX;
    }

    /**
     * 校验客户端给的 {@code location} 落在 catalog 的白名单内。
     *
     * <p>白名单为空表示不限制（与 catalog 创建时的语义一致）。
     */
    public void requireAllowedLocation(CatalogEntity catalog, String location) {
        List<String> allowed = catalog.getAllowedLocations();
        if (allowed == null || allowed.isEmpty() || location == null || location.isBlank()) {
            return;
        }
        for (String prefix : allowed) {
            if (location.startsWith(prefix)) {
                return;
            }
        }
        throw LanceErrors.invalidInput("location \"" + location
                + "\" is outside the allowed locations of catalog \"" + catalog.getPrefix() + "\"");
    }

    // ------------------------------------------------------------------ 凭据

    /**
     * 生成 {@code storage_options}。
     *
     * @param vendCredentials 客户端是否要求下发临时凭据
     *
     * <p><b>{@code expires_at_millis} 与 {@code ttlSeconds} 必须同源</b>：前者是 Lance 的键名，
     * 后者是本服务端的字段，两者由同一个 {@code ttlSeconds} 算出来（见下面那一行）。
     * 分开算会出现「签名 15 分钟有效、声明 1 小时有效」的错配，而它的表现是
     * 客户端在凭据过期后继续用、拿到一个不含任何 Lance 信息的 403。
     *
     * <p><b>不要求凭据时也返回 {@code storage_options}</b>：里面放定位信息（不含密钥）。
     * 客户端不传这个参数不代表它不需要知道 endpoint。但**绝不**在这种情况下塞长命密钥：
     * 只有 {@code hasSecrets()} 为假（文件系统、无密钥的环境凭据）时才原样下发。
     */
    public Map<String, String> storageOptions(CatalogEntity catalog, String tableId,
                                              String location, boolean vendCredentials) {
        VendedStorageCredential credential;
        try {
            credential = runtimePolicy.credentialManager().vend(catalog.getStorageConfig(), tableId, location);
        } catch (RuntimeException e) {
            if (vendCredentials) {
                // 客户端明确要凭据：失败必须可见。静默返回空的后果是客户端拿着
                // 没有密钥的 storage_options 去开数据集，报一个指向存储的错。
                throw ApiException.protocolError(503, "service_unavailable",
                        "could not vend credentials for table " + tableId + ": " + e.getMessage());
            }
            log.debug("credential vending failed for {}; answering without storage credentials", tableId, e);
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (vendCredentials) {
            options.putAll(credential.token());
            options.put("expires_at_millis",
                    Long.toString(System.currentTimeMillis() + credential.ttlSeconds() * 1000L));
        } else if (!credential.hasSecrets()) {
            options.putAll(credential.token());
        }
        return options;
    }

    // ------------------------------------------------------------------ 声明态

    /**
     * 探测存储里是否已有数据集。
     *
     * <p><b>这是弱校验，不解析 manifest。</b>{@code _versions/1.manifest}（V1 命名）与
     * {@code latest_version_hint.json}（V2 用来加速版本发现的提示文件）只要有一个在，
     * 就认为「有数据集」。它验不出「有 manifest 但数据文件不全」——那种表我们
     * 打不开，而打开它是客户端的事。
     *
     * @return {@code true} 有、{@code false} 没有、{@code null} 探测不了（存储不可达）
     */
    public Boolean hasDataset(CatalogEntity catalog, String location) {
        if (location == null || location.isBlank()) {
            return Boolean.FALSE;
        }
        for (String marker : DATASET_MARKERS) {
            try {
                if (files.exists(catalog.getId(), Paths.trimTrailingSlash(location) + "/" + marker)) {
                    return Boolean.TRUE;
                }
            } catch (RuntimeException e) {
                log.debug("could not probe {} under {}", marker, location, e);
                return null;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * {@code register} 的门槛：位置里必须已经有一个数据集。
     *
     * <p>与 {@code declare} 的差别就在这里——declare 登记的是**意图**（可以还没有数据），
     * register 登记的是**既成事实**。
     */
    public void requireDataset(CatalogEntity catalog, String location) {
        Boolean present = hasDataset(catalog, location);
        if (Boolean.FALSE.equals(present)) {
            throw LanceErrors.invalidInput("location \"" + location
                    + "\" does not contain a Lance dataset (no manifest under _versions/);"
                    + " use declare to register an intent to create one");
        }
    }
}
