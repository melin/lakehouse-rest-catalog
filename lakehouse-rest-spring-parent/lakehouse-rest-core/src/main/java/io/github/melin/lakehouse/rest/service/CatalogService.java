package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 注册表与配置发现。
 *
 * <p>REST 路径中的 {@code {prefix}} 即 catalog 的对外标识。客户端拿到
 * {@code GET /v1/config} 返回的 prefix 之后，后续所有资源路径都以它开头，
 * 这与 Iceberg / Paimon 客户端的约定一致。
 *
 * <p><b>prefix 也是协议面的分派键。</b>一个 catalog 只有一种格式
 * （{@code catalog_format}，创建时定下、不可变），于是
 * 「路径 → prefix → catalog → 格式 → 协议处理链」这条链上唯一需要判断的地方
 * 就是这里。协议面自己不判断——进来的请求已经被确认过格式，见
 * {@link #resolveForFormat}。
 *
 * <p><b>本类只管「catalog 是哪个、是什么格式」这件事，不持有协议面本身。</b>
 * 协议面的注册表在 {@link CatalogProtocols}，那是一个由控制器依赖的叶子 bean。
 * 为什么必须这么分，见那个类的注释（一句话：让地基持有上层的东西会形成构造器环）。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogRepository catalogRepository;
    private final RestServerProperties properties;

    /**
     * 解析 prefix 对应的 catalog。
     *
     * <p>未登记且开了自动登记时，按 {@code lakehouse.rest.default-catalog-format}
     * 建一个——这是「部署侧选默认格式」那一半对策；另一半见
     * {@link #resolveOrCreate}。
     *
     * @throws ApiException 404 未登记且未开启自动创建时
     */
    @Transactional
    public CatalogEntity resolve(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw ApiException.badRequest("prefix must not be blank");
        }
        return resolveOrCreate(prefix, CatalogFormat.of(properties.getDefaultCatalogFormat()));
    }

    /**
     * 取 catalog；未登记且开了自动登记时，按**调用方指定的**格式建一个。
     *
     * <p>与 {@link #resolve} 的差别只有一处：自动登记时用哪种格式由调用方定。
     * 它存在的场景是重命名那两条同路径端点（见 {@code CatalogProtocol#rename}）——
     * 那里「这条请求属于哪个面」是由**请求体**判出来的（catalog 还没登记，
     * 没有 prefix 可查），因此自动登记出来的 catalog 必须与判定结果一致。
     * 若走 {@link #resolve}，一个 Iceberg 客户端的 rename 会建出一个 Paimon catalog，
     * 而它接下来在这个 prefix 上的**每一次**调用都会 404。
     *
     * @throws ApiException 404 未登记且未开启自动创建时
     */
    @Transactional
    public CatalogEntity resolveOrCreate(String prefix, CatalogFormat format) {
        if (prefix == null || prefix.isBlank()) {
            throw ApiException.badRequest("prefix must not be blank");
        }
        return catalogRepository.findByPrefix(prefix).orElseGet(() -> {
            if (!properties.isAutoCreateCatalog()) {
                throw new ApiException(404, null, prefix, "Unknown catalog prefix");
            }
            return create(prefix, properties.getDefaultWarehouse(), format, "自动登记");
        });
    }

    /**
     * 解析 prefix，并确认这个 catalog 服务的正是期望的格式。
     *
     * <p><b>格式不匹配要报明确的错，而且消息里必须点出真正的原因。</b>
     * 一个 Iceberg 客户端敲了一个 Paimon catalog 的 prefix（或反过来）时，
     * 如果只按「路径里没有这个资源」回一句普通的 404，客户端会以为是自己把
     * namespace / 表名写错了——而真正的原因是**格式不对**，它再试一百次也不会对。
     * 因此消息里把两个格式都写出来。
     *
     * <p>状态码仍是 404 而不是 409：对客户端而言「这个 prefix 上没有我要的东西」
     * 是事实，Iceberg 的 {@code RESTCatalog} 也只在 404 时回落到「未知 warehouse」
     * 的处理。资源类型报 {@code DATABASE}，于是 Iceberg 形状的错误类型是
     * {@code NoSuchNamespaceException}——与设计文档 §3.3 的意图一致
     * （文档写的是 {@code NoSuchNamespaceError}，那是 Iceberg 异常类的近似写法，
     * 实际类名是 {@code NoSuchNamespaceException}）。
     *
     * <p><b>自动登记按 {@code expected} 建，而不是按部署默认格式。</b>这条路径上的请求
     * 已经把自己的格式说清楚了——是 Iceberg 的路径形状把请求送到这个面的，那比
     * {@code lakehouse.rest.default-catalog-format} 这个部署级猜测更权威。按默认格式建的后果很具体：
     * 一个 Iceberg 客户端的首次调用会**建出一个 Paimon catalog**，然后以格式不匹配 404，
     * 而它此后每一次调用都是同一个错——错还是这一次请求自己留下的。
     * 部署默认值仍然管两件事：管理 API 未指定 {@code catalogFormat} 时建哪种，
     * 以及 {@code /v1/config} 这种「客户端还没说过自己要哪个格式」的连接阶段。
     */
    @Transactional
    public CatalogEntity resolveForFormat(String prefix, CatalogFormat expected) {
        CatalogEntity catalog = resolveOrCreate(prefix, expected);
        CatalogFormat actual = formatOf(catalog);
        if (actual != expected) {
            throw new ApiException(404, ResourceType.DATABASE, prefix,
                    "prefix \"" + catalog.getPrefix() + "\" is " + withArticle(actual)
                            + " catalog; " + expected.wireName()
                            + " REST paths need " + withArticle(expected) + " catalog");
        }
        return catalog;
    }

    /**
     * {@code ICEBERG} 的冠词是 {@code an}、{@code PAIMON} 的是 {@code a}。
     *
     * <p>这行消息是给排障的人看的，写 {@code "is a ICEBERG catalog"} 不只是难看：
     * 它容易被当成字符串拼接出了 bug，让人先去怀疑服务端而不是自己敲错了 prefix。
     */
    private static String withArticle(CatalogFormat format) {
        return switch (format) {
            case ICEBERG -> "an " + format.wireName();
            case PAIMON, LANCE -> "a " + format.wireName();
        };
    }

    /** 读 catalog 的格式列；认不出来的值回落成默认格式并留一条日志（可能是手工改库写进去的）。 */
    public CatalogFormat formatOf(CatalogEntity catalog) {
        CatalogFormat format = CatalogFormat.of(catalog.getCatalogFormat());
        if (CatalogFormat.parse(catalog.getCatalogFormat()).isEmpty()
                && catalog.getCatalogFormat() != null) {
            log.warn("catalog {} has an unrecognized catalog_format \"{}\"; treating it as {}",
                    catalog.getPrefix(), catalog.getCatalogFormat(), format.wireName());
        }
        return format;
    }

    @Transactional
    public CatalogEntity create(String prefix, String warehouse, String source) {
        return create(prefix, warehouse, CatalogFormat.of(properties.getDefaultCatalogFormat()), source);
    }

    /**
     * 登记一个 catalog。
     *
     * <p>{@code format} 由调用方给出（管理 API 的 {@code catalogFormat} 字段、
     * 或自动登记时的 {@code lakehouse.rest.default-catalog-format}）。自动登记必须走这个
     * 重载而不是老的那一个：Iceberg 客户端敲一个未登记的 prefix 时，
     * 「建成 Iceberg」与「建成 Paimon」的区别就是「此后一切正常」与「此后每次调用都 404」。
     */
    @Transactional
    public CatalogEntity create(String prefix, String warehouse, CatalogFormat format, String source) {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setId(Paging.newId());
        catalog.setPrefix(prefix);
        String resolved = warehouse == null || warehouse.isBlank()
                ? properties.getDefaultWarehouse()
                : warehouse;
        // 存储配置必须与仓库一起写：只设 warehouse 而漏掉 storageConfig，
        // 读回来就只能靠投影列重建，类型专属字段会丢失
        StorageConfigs.apply(catalog, StorageConfigs.fileStorage(resolved));
        catalog.setDefaults(new LinkedHashMap<>());
        catalog.setOverrides(new LinkedHashMap<>());
        catalog.setCatalogType("INTERNAL");
        catalog.setCatalogFormat(format.wireName());
        catalog.markCreated(RequestContext.principal(), RequestContext.now());
        return catalogRepository.save(catalog);
    }

    /**
     * 解析 {@code GET /v1/config} 要看的 catalog：请求带了 {@code warehouse} 就用它，
     * 没带就回落到 {@code lakehouse.rest.default-prefix}。
     *
     * <p>「缺省用哪个 catalog」这件事需要 {@link RestServerProperties}，而它在本类手里；
     * 因此这个回落留在这里，而**响应体的组装**（{@code defaults} 拼装 + 形状选择）
     * 与协议面待在一起，见 {@link CatalogProtocols#configResponse}。
     *
     * @throws ApiException 404 该 prefix 未登记且未开启自动创建时
     */
    @Transactional
    public CatalogEntity resolveForConfig(String warehouse) {
        String requested = warehouse == null || warehouse.isBlank()
                ? properties.getDefaultPrefix()
                : warehouse;
        return resolve(requested);
    }
}
