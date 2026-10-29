package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalog;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 控制台的「凭据来源」端点：把 {@link CatalogCredentialSource} 的判定批量套到当前可见的 catalog 上。
 *
 * <p>为什么不是逐 catalog 一个小端点：列表页一次要标几十行，逐行请求就是 N+1；
 * 而这件事本身不访问存储、不调云，批量做的成本只是多走一遍内存里的配置。
 *
 * <p><b>可见性照抄管理 API 的规则</b>（{@link io.github.melin.lakehouse.rest.web.ManagementCatalogController}）：
 * 未开授权或服务管理员看全部，其余人只看自己至少持有一条赋权的 catalog。
 * 不这么做，这个端点就成了「用分类信息探测 catalog 是否存在」的旁路——
 * 列表接口过滤掉的东西，这里却漏出来。
 */
@Service
@RequiredArgsConstructor
public class ConsoleCredentialSourceService {

    private final ManagementCatalogService catalogs;
    private final RestServerProperties properties;
    private final AuthorizationService authorization;

    /**
     * {@code GET /api/console/v1/catalog-credential-sources}。
     *
     * <p>判定输入是**对外那份** {@code storageConfigInfo}（{@code accessKeyId} 已打码）：
     * {@link CatalogCredentialSource} 只问「配没配」，不读密钥本身，因此打码值不影响结论。
     * 走对外那份而不是库里那份，顺带保证这个端点不可能把真密钥带出去。
     */
    @Transactional(readOnly = true)
    public ConsoleDtos.CatalogCredentialSources list() {
        List<ConsoleDtos.CatalogCredentialSources.Entry> entries = new ArrayList<>();
        for (Catalog catalog : catalogs.list().catalogs()) {
            if (!visible(catalog.name())) {
                continue;
            }
            CatalogCredentialSource.Source source =
                    CatalogCredentialSource.of(catalog.storageConfigInfo(), properties);
            entries.add(new ConsoleDtos.CatalogCredentialSources.Entry(
                    catalog.name(), source.mode().name(), source.detail()));
        }
        return new ConsoleDtos.CatalogCredentialSources(entries,
                List.copyOf(properties.getCredential().getProviders()));
    }

    /** 与 {@code ManagementCatalogController#visible} 同一条规则（含两个短路）。 */
    private boolean visible(String catalog) {
        return !authorization.enabled()
                || authorization.isServiceAdmin(RequestContext.principal())
                || authorization.hasAnyGrant(RequestContext.principal(), catalog);
    }
}
