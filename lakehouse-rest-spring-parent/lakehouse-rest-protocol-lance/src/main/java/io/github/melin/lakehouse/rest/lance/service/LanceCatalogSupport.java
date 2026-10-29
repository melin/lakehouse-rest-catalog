package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.lance.support.LanceManifest;
import io.github.melin.lakehouse.rest.lance.support.LanceTagFiles;
import io.github.melin.lakehouse.rest.lance.support.LanceVersionNaming;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.PermissionDtos;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.FileEntry;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paths;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** 版本目录；manifest 的命名方案见 {@link LanceVersionNaming}。 */
    private static final String VERSIONS = "_versions";

    /** V2 客户端为加速版本发现而写的提示文件；它只是信号，不是判据（见 {@link #hasDataset}）。 */
    private static final String VERSION_HINT = "latest_version_hint.json";

    private final CatalogService catalogService;
    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    /**
     * 凭据经 {@code StorageRuntimePolicy} 取，而不是直接注入一个
     * {@code StorageCredentialManager}：容器里有 default / noop 两个实现，
     * 「用哪个」是部署配置决定的（{@code credential-manager.type}），
     * 直接按类型注入会在启动期以 NoUniqueBeanDefinitionException 失败。
     */
    private final StorageRuntimePolicy runtimePolicy;

    /**
     * 凭据的两个权限点在这里判。Lance 面没有「凭据端点」——取票是
     * {@code describe / rename} 的一个 query 参数（{@code vend_credentials=true}），
     * 路径和参数都不进 {@code LanceAccessRules}，所以两个点只能在下发凭据的那一处判。
     */
    private final AuthorizationService authorization;
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

    // ------------------------------------------------------------------ 定位（表/库共用）

    /**
     * 按 {@code id} 的第二级取 schema。
     *
     * <p>放在这里而不是留在各个服务里：{@code LanceTableService} 与
     * {@code LanceVersionService} 都要「按 id 取到表」，两处各写一份的后果是
     * 某天只改了一处的错误码——而错误码不一致正是客户端最难排查的那类问题。
     */
    public DatabaseEntity requireDatabase(CatalogEntity catalog, LanceIds.Id parsee, String delimiter) {
        return databaseRepository.findByCatalogIdAndName(catalog.getId(), parsee.schema())
                .orElseThrow(() -> LanceErrors.namespaceNotFound(parsee.catalog() + sep(delimiter) + parsee.schema()));
    }

    /** 按 {@code id} 的第三级取表。 */
    public TableEntity requireTable(CatalogEntity catalog, DatabaseEntity database,
                                    LanceIds.Id parsee, String delimiter) {
        return tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), parsee.table())
                .orElseThrow(() -> LanceErrors.tableNotFound(parsee.join(sep(delimiter))));
    }

    /** {@code delimiter} 的归一：空值按 {@link LanceIds#DEFAULT_DELIMITER}。 */
    public static String sep(String delimiter) {
        return delimiter == null || delimiter.isEmpty() ? LanceIds.DEFAULT_DELIMITER : delimiter;
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
     * <p><b>取票要两个权限点，与另外两个协议面同一套语义</b>（{@link #requireVending}）。
     * 差别只在判的位置：Paimon 与 Iceberg 各有一个凭据端点，端点级那道由
     * {@code CatalogAccessRules} 判；这里取票是 {@code vend_credentials=true} 这个
     * **query 参数**，路径不进 {@code LanceAccessRules}，因此两道都在本方法里判。
     *
     * <p><b>不要求凭据时也返回 {@code storage_options}</b>：里面放定位信息（不含密钥）。
     * 客户端不传这个参数不代表它不需要知道 endpoint。但**绝不**在这种情况下塞长命密钥：
     * 只有 {@code hasSecrets()} 为假（文件系统、无密钥的环境凭据）时才原样下发。
     */
    public Map<String, String> storageOptions(CatalogEntity catalog, String tableId,
                                              String location, List<String> namespace, String table,
                                              boolean vendCredentials) {
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
        if (vendCredentials) {
            requireVending(catalog, namespace, table, credential);
        }
        Map<String, String> options = new LinkedHashMap<>();
        if (vendCredentials) {
            // 换键名：凭据层发的是 s3.*（Paimon / Iceberg 的 Java FileIO 认），
            // Lance 的 Rust 侧只认 aws_*，原样透传等于没下发（G12）。
            options.putAll(LanceStorageOptions.fromS3(credential.token()));
            // 到期时刻优先用凭据自己的寿命：STS 的票可能比配置的 ttl 短
            // （角色 MaxSessionDuration 更小时），声明得更久会让客户端在过期后继续用。
            options.put("expires_at_millis", Long.toString(credential.shortLived()
                    ? credential.expireTimeInMs()
                    : System.currentTimeMillis() + credential.ttlSeconds() * 1000L));
        } else if (!credential.hasSecrets()) {
            options.putAll(LanceStorageOptions.fromS3(credential.token()));
        }
        return options;
    }

    /**
     * 取票的两个权限点：先 {@code USE_CREDENTIAL}（能取票），票里若含**没有时限的那对
     * 长期密钥**再要 {@code USE_SECRET}（能拿走存着的钥匙）。
     *
     * <p>为什么 Lance 面和另外两面不同、要在这里判：那边「能取票」是路径本身
     * （{@code /token}、{@code /credentials}），映射表能表达；这边是一个 query 参数，
     * {@code LanceAccessRules.resolve} 只拿到路径与 {@code delimiter}，看不到它。
     * 与其把 query 参数塞进映射表（那会让「同一路径两种判权」变成判权层的隐患），
     * 不如在唯一的下发点判——它与 Iceberg 委派那条路径是同一种形态。
     *
     * <p><b>这里选择抛 403 而不是「不带」。</b>Iceberg 的 {@code loadTable} 委派之所以
     * 只静默跳过，是因为那个请求同时承载元数据读取；而 {@code vend_credentials=true}
     * 是客户端**明确要凭据**，与 {@code /token} 同性质，静默返回空的直接后果是客户端拿着
     * 没有密钥的 {@code storage_options} 去开数据集，报一个指向存储的错。
     */
    private void requireVending(CatalogEntity catalog, List<String> namespace, String table,
                                VendedStorageCredential credential) {
        authorization.require(catalog.getPrefix(), PermissionDtos.ResourceType.TABLE, namespace, table,
                Access.USE_CREDENTIAL, "receive vended storage credentials for table " + table);
        if (credential.carriesLongTermKeyPair()) {
            authorization.require(catalog.getPrefix(), PermissionDtos.ResourceType.TABLE, namespace, table,
                    Access.USE_SECRET, "receive the long-term access key stored in catalog "
                            + catalog.getPrefix() + " (grant USE_SECRET on this table, or switch the"
                            + " catalog to a short-lived credential provider)");
        }
    }

    // ------------------------------------------------------------------ 声明态

    /**
     * 探测存储里是否已有数据集。
     *
     * <p><b>这是弱校验，不解析 manifest。</b>{@code _versions/} 下只要有**一个认得出名字的
     * manifest**（V1 的 {@code 1.manifest}、V2 的 {@code 18446744073709551610.manifest}
     * 都算，认得出来由 {@link LanceVersionNaming} 判定）就认为「有数据集」；
     * 另把 {@code latest_version_hint.json} 单独算一个信号。
     *
     * <p><b>为什么是列举而不是拼死几个文件名。</b>早先只探
     * {@code _versions/1.manifest} 与提示文件——那等于同时假设「客户端按 V1 命名」
     * 与「客户端会写提示文件」。Lance 4.x 的 Spark 客户端两条都不成立：默认写 V2
     * 命名、也不写提示文件。结果是**表里已经有数据，这里依然回「只有登记」**，
     * 客户端据此以为要重建表。文件名不是契约，列举结果才是。
     *
     * <p>验不出「有 manifest 但数据文件不全」——那种表我们打不开，而打开它是客户端的事。
     *
     * @return {@code true} 有、{@code false} 没有、{@code null} 探测不了（存储不可达）
     */
    public Boolean hasDataset(CatalogEntity catalog, String location) {
        if (location == null || location.isBlank()) {
            return Boolean.FALSE;
        }
        String dataset = Paths.trimTrailingSlash(location);
        try {
            for (FileEntry entry : files.list(catalog.getId(), dataset + "/" + VERSIONS)) {
                if (!entry.directory() && LanceVersionNaming.versionOf(entry.name()) != null) {
                    return Boolean.TRUE;
                }
            }
            return files.exists(catalog.getId(), dataset + "/" + VERSION_HINT)
                    ? Boolean.TRUE : Boolean.FALSE;
        } catch (RuntimeException e) {
            log.debug("could not probe {} under {}", VERSIONS, location, e);
            return null;
        }
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

    // ------------------------------------------------------------------ 版本（P1）

    /**
     * {@code _versions/} 下的一个 manifest 文件。
     *
     * @param timestampMillis 文件的修改时刻（对象存储 LIST 顺带返回，本地文件系统才有值）；
     *                        不可得时为 {@code null}——它不是「epoch 0 这个真实时刻」
     * @param size            字节数；只知道版本号、没列举过文件时为 {@code null}
     */
    public record VersionFile(long version, String path, Long size, Long timestampMillis) {
    }

    /**
     * 列出数据集的版本（一次 {@code LIST _versions/}）。
     *
     * <p><b>只用列举，不读 {@code latest_version_hint.json}。</b>提示文件是给
     * 「列举代价高」的客户端准备的加速手段；我们为了拿 {@code manifest_size} 与
     * 时间戳本来就要列举一次，再看提示文件等于引入第二个真相源——它可能与
     * 目录里的文件不一致（写失败、并发提交），而那时**没有判据**说谁对。
     *
     * <p><b>混用 V1 / V2 命名即报错。</b>规范说得很直白：两套方案混在一起会出错。
     * 这时继续猜「最新版本」会得到两种答案，而客户端拿它去读数据，
     * 读错版本的后果比报一个错严重得多。
     *
     * @return 按版本号升序；没有 {@code _versions/} 目录或目录为空时返回空列表
     */
    public List<VersionFile> versions(CatalogEntity catalog, String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        String directory = Paths.trimTrailingSlash(location) + "/" + VERSIONS;
        List<VersionFile> found = new ArrayList<>();
        boolean v1 = false;
        boolean v2 = false;
        for (FileEntry entry : files.list(catalog.getId(), directory)) {
            if (entry.directory()) {
                continue;
            }
            Long version = LanceVersionNaming.versionOf(entry.name());
            if (version == null) {
                continue;
            }
            if (LanceVersionNaming.isV2(entry.name())) {
                v2 = true;
            } else {
                v1 = true;
            }
            found.add(new VersionFile(version, directory + "/" + entry.name(),
                    entry.length(), entry.modificationTime() == 0 ? null : entry.modificationTime()));
        }
        if (v1 && v2) {
            throw LanceErrors.invalidTableState("dataset \"" + location
                    + "\" mixes the V1 and V2 manifest naming schemes under _versions/,"
                    + " which the lance table format forbids; rewrite the dataset with one scheme");
        }
        found.sort(Comparator.comparingLong(VersionFile::version));
        return found;
    }

    /** 最新版本；数据集还没有任何 manifest 时为 {@code null}。 */
    public VersionFile latestVersion(CatalogEntity catalog, String location) {
        List<VersionFile> found = versions(catalog, location);
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    /**
     * 读一份 manifest。
     *
     * <p>解析失败抛 {@link LanceManifest.Unreadable}，由调用方降级——
     * 「读不出 schema」不等于「这张表坏了」：它可能是我们不支持的新字段布局，
     * 而表本身完全可用。这个降级是 P1 验收里明确要的一条。
     */
    public LanceManifest.Manifest manifest(CatalogEntity catalog, VersionFile file) {
        byte[] bytes = files.readBytes(catalog.getId(), file.path());
        return LanceManifest.parse(bytes);
    }

    /**
     * 探测某个版本的 manifest 是否存在（{@code DescribeTableVersion} 用）。
     *
     * @return 存在时返回该文件；不存在时返回 {@code null}（两种命名都试：
     *         调用方拿到的版本号不携带「这份数据集用哪套命名」这个信息）
     */
    public VersionFile probeVersion(CatalogEntity catalog, String location, long version) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String directory = Paths.trimTrailingSlash(location) + "/" + VERSIONS;
        String v1Name = LanceVersionNaming.manifestName(version, false);
        if (files.exists(catalog.getId(), directory + "/" + v1Name)) {
            return new VersionFile(version, directory + "/" + v1Name, null, null);
        }
        String v2Name = LanceVersionNaming.manifestName(version, true);
        if (files.exists(catalog.getId(), directory + "/" + v2Name)) {
            return new VersionFile(version, directory + "/" + v2Name, null, null);
        }
        return null;
    }

    // ------------------------------------------------------------------ 标签（P2·L2-2）

    /**
     * 列出数据集里的全部标签（一次 {@code LIST _refs/tags/} + 逐个读）。
     *
     * <p><b>这是纯读取，不做任何登记。</b>标签是客户端（Lance 自己）写的，我们既不落库
     * 也不校验它指向的版本是否还存在——{@code tags/list} 的语义是「目录里有什么」，
     * 不是「我们担保了什么」。若哪天给标签加了写端点，那个区别就要重新说一遍。
     *
     * <p><b>单个文件读不懂就跳过并 {@code warn}，不让整个列举失败。</b>一个坏标签
     * 不该把其余标签一起藏起来；但直接问那个标签（{@code tags/version}）时会失败，
     * 因为那一次没有「跳过」这个选项——见 {@link #tag}。
     *
     * @return 按名字排序的结果；没有目录、或目录里没有 {@code .json} 时为空列表
     */
    public List<LanceTagFiles.Tag> tags(CatalogEntity catalog, String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        String directory = LanceTagFiles.directoryOf(location);
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (FileEntry entry : files.list(catalog.getId(), directory)) {
            if (entry.directory()) {
                continue;
            }
            String name = LanceTagFiles.nameOf(entry.name());
            if (name == null) {
                continue;
            }
            names.add(name);
            paths.add(directory + "/" + entry.name());
        }
        if (paths.isEmpty()) {
            return List.of();
        }
        // 一次 FileIO 读整个目录：逐个读会每个标签重建一次 FileIO 并重新解析凭据
        // （StorageFileAccess 刻意不缓存，见它自己的注释），几十个标签就是几十次
        Map<String, String> texts = files.readTexts(catalog.getId(), paths);
        List<LanceTagFiles.Tag> found = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            String text = texts.get(paths.get(i));
            if (text == null) {
                // 列举之后、读之前被删掉了：当成没有，不是错误
                continue;
            }
            try {
                found.add(LanceTagFiles.parse(names.get(i), text));
            } catch (LanceTagFiles.Unreadable e) {
                log.warn("skipping unreadable tag file {}: {}", paths.get(i), e.getMessage());
            }
        }
        return found;
    }

    /**
     * 读一个标签。
     *
     * @return 文件不存在时 {@code null}（调用方回 {@code TableTagNotFound}）；
     *         文件在但读不懂时抛 {@link LanceTagFiles.Unreadable}——
     *         那次没有「跳过」的余地，把它说成「没有这个标签」就是撒谎
     */
    public LanceTagFiles.Tag tag(CatalogEntity catalog, String location, String tagName) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = LanceTagFiles.pathOf(location, tagName);
        String text = files.readTexts(catalog.getId(), List.of(path)).get(path);
        return text == null ? null : LanceTagFiles.parse(tagName, text);
    }

    /**
     * {@code manifest_path} 的弱存在性探测（{@code CreateTableVersion} 用）。
     *
     * <p>与 {@link #hasDataset} 同一套三态：{@code null} 是「探测不了」，
     * 调用方按「不能证明它在」处理（→ 503），而不是当成「不存在」去拒绝——
     * 存储抖动时不该让客户端以为自己写错了版本号。
     */
    public Boolean manifestExists(CatalogEntity catalog, String manifestPath) {
        if (manifestPath == null || manifestPath.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            return files.exists(catalog.getId(), manifestPath);
        } catch (RuntimeException e) {
            log.debug("could not probe manifest {}", manifestPath, e);
            return null;
        }
    }
}
