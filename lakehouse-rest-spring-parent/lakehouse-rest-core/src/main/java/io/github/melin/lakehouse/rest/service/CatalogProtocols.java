package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 已注册的协议面（{@code paimon} / {@code iceberg}），按格式索引。
 *
 * <p><b>为什么它必须是一个独立的 bean，而不能挂在 {@code CatalogService} 上。</b>
 * 这是本类存在的全部理由，值得写清楚：协议面的实现要干活，就得拿到
 * {@code TableService} / {@code IcebergTableService} 这些服务；而那些服务的第一件事
 * 是按 prefix 解析 catalog，也就是依赖 {@code CatalogService}。因此
 * 「{@code CatalogService} 持有 {@code List<CatalogProtocol>}」会形成一条
 * 构造器依赖环：
 *
 * <pre>
 *   CatalogService → PaimonProtocol → TableService → CatalogService
 * </pre>
 *
 * <p>Spring Boot 2.6 起默认**拒绝**这种构造器环（{@code allow-circular-references}
 * 已关闭），启动直接失败。三种解法里这是唯一不靠约定的那个：
 *
 * <ul>
 *   <li>{@code @Lazy} / {@code ObjectProvider} 把环推迟到调用时——它能让容器启动，
 *       但环本身还在，下一次有人往 {@code CatalogService} 里加一个依赖时，
 *       问题会以同样的方式回来；</li>
 *   <li>让协议面不依赖服务（把 rename 的逻辑复制一份到协议面里）——那是拿重复换顺序；</li>
 *   <li><b>把注册表放到环的外面</b>（本类）：协议面属于**装配层**，
 *       它比业务服务更靠上；而 {@code CatalogService} 是业务服务脚下的地基。
 *       让地基持有上层的东西本来就是反的，拆开之后依赖方向是单向的：
 *       <pre>
 *   控制器 → CatalogProtocols → 协议面 → 业务服务 → CatalogService
 *       </pre>
 *       {@code CatalogProtocols} 是叶子：它只被控制器依赖，自己只依赖协议面接口。</li>
 * </ul>
 *
 * <p>两个协议面由 Spring 组件扫描收集（落在同一个 fat jar、同一个 JVM 里），
 * core 因此不需要 import 任何一个面——「两个协议面互不依赖」这条模块不变量
 * （各自 POM 里的 enforcer 守着）得以保持。
 */
@Component
@RequiredArgsConstructor
public class CatalogProtocols {

    private final List<CatalogProtocol> protocols;

    /**
     * 取某个格式的协议面。
     *
     * <p>缺实现时抛 500 而不是回落：一个格式没有对应的面，说明这个部署的产物里少了
     * 一个协议模块——按另一种格式应答，等于用完全不同的元数据代数去解释客户端发来的报文，
     * 错误现场会离原因非常远。
     */
    public CatalogProtocol of(CatalogFormat format) {
        for (CatalogProtocol protocol : protocols) {
            if (protocol.format() == format) {
                return protocol;
            }
        }
        throw new ApiException(500, null, null,
                "no protocol implementation is registered for catalog format " + format.wireName());
    }

    /** 已注册的协议面，按格式索引。给需要遍历两者的地方用（端点覆盖校验、控制台）。 */
    public Map<CatalogFormat, CatalogProtocol> byFormat() {
        Map<CatalogFormat, CatalogProtocol> byFormat = new EnumMap<>(CatalogFormat.class);
        for (CatalogProtocol protocol : protocols) {
            byFormat.put(protocol.format(), protocol);
        }
        return byFormat;
    }

    /**
     * 组装 {@code GET /v1/config} 的响应体：把两段内容备齐，形状交给协议面表达。
     *
     * <p>返回的 {@code defaults} 参与客户端的配置合并（defaults → 客户端属性 → overrides）。
     * 其中必须包含 {@code prefix}，客户端据此拼出后续资源路径；{@code warehouse} 与
     * catalog 自身的 {@code defaults}（{@code table-default.*} 之类）跟在后面。
     *
     * <p>响应的形状按格式不同，因此这里只能交出一个「某种 JSON」：
     * Paimon 回 {@code {defaults, overrides}}，Iceberg 还要多一份 {@code endpoints}
     * 能力清单（见 {@code CommonDtos.IcebergConfigResponse}）。
     *
     * @param format  catalog 的格式。由调用方给出而不是本类自己从 catalog 上读，
     *                是为了让「认不出来的格式列」那条 warn 日志仍然只有一处
     *                （{@code CatalogService#formatOf}）
     */
    public Object configResponse(CatalogFormat format, CatalogEntity catalog) {
        Map<String, String> defaults = new LinkedHashMap<>();
        if (catalog.getWarehouse() != null && !catalog.getWarehouse().isBlank()) {
            defaults.put("warehouse", catalog.getWarehouse());
        }
        defaults.put("prefix", catalog.getPrefix());
        if (catalog.getDefaults() != null) {
            defaults.putAll(catalog.getDefaults());
        }
        Map<String, String> overrides = catalog.getOverrides() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(catalog.getOverrides());
        return of(format).configResponse(catalog, defaults, overrides);
    }
}
