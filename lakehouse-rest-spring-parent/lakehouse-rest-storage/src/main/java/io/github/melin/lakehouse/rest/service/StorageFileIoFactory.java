package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileIOLoader;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 服务端自己访问仓库所需的 {@link FileIO} 装配。
 *
 * <p><b>为什么单独抽一个组件。</b>服务端有两处需要「以 catalog 的身份读写仓库」：
 * 物化表元数据（{@link TableMetadataService}）与浏览仓库文件（{@link CatalogFileService}）。
 * 两处要的其实是同一件事——按 scheme 找到本部署的 FileIO 实现、配上与下发给引擎
 * 完全一致的凭据选项。复制一份的代价不是多几十行，而是**两份凭据来源会漂移**：
 * 一旦哪次只改了其中一处（例如新增一级具名存储），现象就是「引擎能写、控制台看不了
 * 那个目录」，而两边的报错都只说认证失败，从现象反推不回原因。
 *
 * <p><b>为什么不用 {@code FileIO.get(path, context)}。</b>后者对本部署会直接失败——
 * 它构造 {@code ResolvingFileIO}，而 {@code ResolvingFileIO.configure} 要拿 Hadoop
 * {@code Configuration}，服务端不带 Hadoop。逐条列举的写法同时让「这个 scheme
 * 有没有实现」变成一个可以自己给出错误信息的问题。
 *
 * <p><b>为什么每次调用都重新构造而不是缓存。</b>缓存凭据的代价是「管理 API 换过
 * catalog 凭据之后，服务端还在用旧钥匙」——那种问题会在下次操作时才炸。
 * {@code loaders} 缓存的是**实现类**而不是实例：按 scheme 加载类这件事与配置无关，
 * 而 {@link FileIO} 实例本身仍然每次新建，理由同上。
 *
 * <p>唯一缓存的是 {@link #catalogOptionsFor} 里的**短期票**（不是 FileIO 实例）：
 * 它的键里含整份存储配置，换钥匙即换键；而且不缓存就等于「浏览一次目录签十几次票」。
 * 静态转发档不缓存，仍然每次解析配置。
 */
@Component
@RequiredArgsConstructor
public class StorageFileIoFactory {

    private static final Logger log = LoggerFactory.getLogger(StorageFileIoFactory.class);

    private final StorageRuntimePolicy storagePolicy;

    private final StorageCredentialCache credentialCache;

    /** scheme → FileIO 实现，来自 {@code META-INF/services/org.apache.paimon.fs.FileIOLoader}。 */
    private final Map<String, FileIOLoader> loaders = new ConcurrentHashMap<>();

    private volatile boolean loadersDiscovered;

    /**
     * 打开一个绑定到 {@code path} 所在存储的 FileIO。
     *
     * @param path    目标路径，用于判定 scheme；无 scheme 时按本地文件处理
     * @param options 交给 FileIO 的选项，见 {@link #optionsFor}
     */
    public FileIO open(Path path, Map<String, String> options) throws IOException {
        String scheme = schemeOf(path);
        if (scheme == null) {
            // 没有 scheme 的路径按本地文件处理，与 FileIO.get 的判定保持一致
            return new LocalFileIO();
        }
        FileIOLoader loader = loaders().get(scheme);
        if (loader == null) {
            throw new IOException("no FileIO implementation for scheme '" + scheme
                    + "' is available on the classpath; lakehouse.rest.file-io.type="
                    + storagePolicy.fileIo().wireName()
                    + " declares which storages this deployment accepts, but the actual"
                    + " implementation has to be deployed as a Paimon plugin"
                    + " (file:// is built in; s3://, obs://, oss:// and cosn:// are not)");
        }
        FileIO fileIO = loader.load(path);
        fileIO.configure(CatalogContext.create(new Options(options)));
        return fileIO;
    }

    /**
     * 交给 FileIO 的选项：catalog 的存储种类与位置，加上本服务端为它算出的凭据。
     *
     * <p>密钥来源与引擎完全一致（含「catalog 自带静态凭据」这一级），因此
     * 「服务端能不能访问」这个问题与「引擎能不能访问」是同一个答案，不需要分别排查。
     *
     * <p>仓库位置（{@code warehouse}）也要给：对象存储的 FileIO 在缺少定位信息时
     * 会退回默认凭据链与默认区域，报出来的错与真正的原因隔着一层。
     *
     * @param catalog   catalog 实体
     * @param scopeId   自包含令牌的作用域标识；凭据管理器需要它来记账
     * @param scopePath 被访问的路径，可为 {@code null}
     */
    public Map<String, String> optionsFor(CatalogEntity catalog, String scopeId, String scopePath) {
        return optionsFor(catalog, scopeId, scopePath, false);
    }

    /**
     * 同上，但显式声明要不要写权限。
     *
     * <p><b>写权限必须显式要，不能靠「反正服务端有全权」的假设。</b>
     * STS 的票是收窄过的，服务端自己写元数据时若拿的是只读票，
     * 失败点是 {@code PutObject} 403——它会出现在「建表之后引擎读不到 schema」这种
     * 离凭据很远的现象里。
     *
     * @param allowWrite 这次是否要写（物化元数据）
     */
    public Map<String, String> optionsFor(CatalogEntity catalog, String scopeId, String scopePath,
                                          boolean allowWrite) {
        Map<String, String> options = new LinkedHashMap<>();
        StorageConfigInfo storage = StorageConfigs.of(catalog);
        if (storage != null) {
            VendedStorageCredential vended = storagePolicy.credentialManager()
                    .vend(storage, scopeId, scopePath, allowWrite);
            options.putAll(vended.token());
        }
        putWarehouse(options, catalog);
        return options;
    }

    /**
     * 服务端自己按「catalog 的允许位置」取票。
     *
     * <p><b>为什么要单独一个入口。</b>浏览目录、删一张表的整个目录、按快照清单核对文件，
     * 这些动作的共同点是「手里只有一个路径，而它可能在任何地方」——用表级票去覆盖它们，
     * 结果就是列目录 403，而报错指向存储、与 catalog 无关。这里把作用域换成整份
     * {@code allowedLocations}，并用独立的缓存键与用户的表级票分开（见
     * {@code StorageCredentialCache#catalogKey}）。
     *
     * <p><b>只缓存短期票。</b>静态转发档不缓存，理由与「FileIO 每次重新构造」相同：
     * 管理 API 换过 catalog 凭据之后，服务端不该还拿着旧钥匙。短期票反过来必须缓存——
     * 浏览一次目录会连开十几个文件，不缓存就是十几次 STS 调用，而云侧配额是单账号
     * 数百 TPS 量级。它的键里已经含整份存储配置，改了配置键就变，所以换钥匙也立刻生效。
     *
     * @param allowWrite 这次是否要写（删目录、写元数据）
     */
    public Map<String, String> catalogOptionsFor(CatalogEntity catalog, boolean allowWrite) {
        Map<String, String> options = new LinkedHashMap<>();
        StorageConfigInfo storage = StorageConfigs.of(catalog);
        if (storage != null) {
            options.putAll(catalogCredential(catalog, storage, allowWrite).token());
        }
        putWarehouse(options, catalog);
        return options;
    }

    private VendedStorageCredential catalogCredential(CatalogEntity catalog, StorageConfigInfo storage,
                                                      boolean allowWrite) {
        String key = StorageCredentialCache.catalogKey(catalog.getId(), storage, allowWrite);
        // singleflight 与「只缓存短期票」两件事一起做：并发时只签一次，
        // 而静态凭据（长期 AK/SK）用完即弃——库里的钥匙被换掉之后，
        // 服务端后台不该在缓存过期前继续拿着旧的那对。
        StorageCredentialCache.Cached cached = credentialCache.resolve(key,
                () -> storagePolicy.credentialManager()
                        .vendForCatalog(storage, catalog.getId(), storage.allowedLocations(), allowWrite));
        if (!cached.credential().shortLived()) {
            credentialCache.invalidate(key);
        }
        return cached.credential();
    }

    /**
     * 仓库位置也要给：对象存储的 FileIO 在缺少定位信息时会退回默认凭据链与默认区域，
     * 报出来的错与真正的原因隔着一层。
     */
    private static void putWarehouse(Map<String, String> options, CatalogEntity catalog) {
        if (catalog.getWarehouse() != null && !catalog.getWarehouse().isBlank()) {
            options.put(CatalogOptions.WAREHOUSE.key(), catalog.getWarehouse());
        }
    }

    /** 路径的 scheme；无 scheme（本地相对/绝对路径）时返回 {@code null}。 */
    public static String schemeOf(Path path) {
        String scheme = path.toUri().getScheme();
        return scheme == null || scheme.isBlank() ? null : scheme.toLowerCase(Locale.ROOT);
    }

    /**
     * 发现一次 FileIOLoader 并按 scheme 建索引。
     *
     * <p>逐个接住加载失败：插件 jar 损坏或依赖缺失时，{@code ServiceLoader} 会在
     * 「取下一个」这一步抛 {@link ServiceConfigurationError}。一个坏插件不该让
     * 整个 scheme 索引都建不起来——那会把问题放大成「连本地表也建不了」。
     */
    private Map<String, FileIOLoader> loaders() {
        if (!loadersDiscovered) {
            synchronized (this) {
                if (!loadersDiscovered) {
                    discoverLoaders();
                    loadersDiscovered = true;
                }
            }
        }
        return loaders;
    }

    private void discoverLoaders() {
        Iterator<FileIOLoader> iterator =
                ServiceLoader.load(FileIOLoader.class, FileIOLoader.class.getClassLoader()).iterator();
        while (iterator.hasNext()) {
            try {
                FileIOLoader loader = iterator.next();
                loaders.putIfAbsent(loader.getScheme().toLowerCase(Locale.ROOT), loader);
            } catch (ServiceConfigurationError | LinkageError e) {
                log.warn("skipping an unusable Paimon FileIOLoader on the classpath: {}", rootMessage(e));
            }
        }
        log.info("discovered Paimon FileIO implementations for schemes {}", loaders.keySet());
    }

    /** 异常链最里面那条的消息；顶层消息常常只是一句「null」。 */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
