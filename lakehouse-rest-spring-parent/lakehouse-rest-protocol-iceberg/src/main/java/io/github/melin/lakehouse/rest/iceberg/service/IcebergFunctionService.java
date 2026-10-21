package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.FunctionEntity;
import io.github.melin.lakehouse.rest.domain.repo.FunctionRepository;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Iceberg 形态的函数端点：对 {@code lakehouse_function} 的**只读投影**。
 *
 * <p><b>为什么是投影而不是独立存储。</b>函数的 Paimon 形状（入参 / 返回值 / 定义体 /
 * 注释 / 确定性）与 Iceberg 的 {@code FunctionMetadata} 表达的是同一件事，只是名字不同；
 * 视图之所以要按格式隔离（{@code view_schema_doc} 按格式存各自整份元数据），是因为
 * 两边的「定义」差异大到没法互相翻译。函数不存在这个问题：任何一边建的函数，
 * 另一边都读得到，只是看到的形状是自己的——因此这里不落任何新列、不加任何投影行。
 *
 * <p><b>这条路径不做 catalog 格式门禁</b>（与 Iceberg 面其它端点不同）：
 * 函数的写路径只在 Paimon 面，而那个面严格限定 PAIMON catalog——于是
 * {@code lakehouse_function} 里只会有 PAIMON catalog 的行。若这里再按 ICEBERG 过滤，
 * 两个 GET 就成了永远为空的死端点；不过滤，它们给 PAIMON catalog 的函数提供了一条
 * Iceberg 形状的只读通道（跨引擎客户端、控制台都能用）。ICEBERG catalog 上它是
 * 如实的空列表——没有写路径，这是事实而不是缺陷。
 *
 * <p><b>投影不是无损转换，三处取舍得写在明面上：</b>
 *
 * <ul>
 *   <li><b>只有 SQL 定义能投影出来。</b>Iceberg 规格的 {@code FunctionRepresentation}
 *       是 {@code oneOf} 且只有 {@code FunctionSQLRepresentation} 一种（sql + dialect），
 *       Paimon 的 {@code file} / {@code lambda} 定义在 Iceberg 的词表里没有对应物——
 *       硬编一个形状等于发明协议。它们被跳过，剩下的照常服务。</li>
 *   <li><b>{@code function-uuid} 由实体 id 派生（UUID v3）。</b>同一行每次 load 得到
 *       同一个 uuid（客户端会拿它做 {@code assert-function-uuid} 这类校验的锚点），
 *       但它不是建函数时生成的那个 uuid——本服务端的函数没有元数据文件，
 *       没有地方在创建时生成并保存它。</li>
 *   <li><b>{@code metadata-location} 恒为缺席。</b>规格说「if available」；
 *       函数没有元数据文件，缺席即如实。</li>
 * </ul>
 *
 * <p>写路径（建 / 改 / 删）只在 Paimon 面（{@code /v1/{prefix}/databases/{db}/functions…}），
 * 这里只读——与官方规格一致：Iceberg REST 的 functions 本来就只定义了两个 GET。
 */
@Service
@RequiredArgsConstructor
public class IcebergFunctionService {

    private final FunctionRepository functionRepository;
    private final IcebergNamespaceService namespaceService;
    private final CatalogService catalogService;
    private final RestServerProperties properties;

    /** {@code GET /v1/{prefix}/namespaces/{namespace}/functions}。 */
    @Transactional(readOnly = true)
    public IcebergDtos.ListFunctionsResponse list(String prefix, List<String> levels,
                                                  Integer pageSize, String pageToken, Integer page ) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        List<IcebergDtos.TableIdentifier> identifiers = new ArrayList<>();
        for (FunctionEntity function : functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            identifiers.add(new IcebergDtos.TableIdentifier(levels, function.getName()));
        }
        // 页码模式（控制台表格）：切页并带回总数；协议里的 pageToken 翻页保持原样。
        Paging.Paged<IcebergDtos.TableIdentifier> paged = Paging.paged(identifiers, page, pageSize);
        if (paged != null) {
            return new IcebergDtos.ListFunctionsResponse(paged.items(), null, (int) paged.meta().total());
        }
        Paging.Slice<IcebergDtos.TableIdentifier> slice = Paging.slice(identifiers,
                Paging.offset(pageToken),
                Paging.pageSize(pageSize, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new IcebergDtos.ListFunctionsResponse(slice.items(), slice.nextPageToken(), null);
    }

    /** {@code GET /v1/{prefix}/namespaces/{namespace}/functions/{function}}。 */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadFunctionResult load(String prefix, List<String> levels, String functionName) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        FunctionEntity function = functionRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), functionName)
                .orElseThrow(() -> functionNotExist(levels, functionName));
        return new IcebergDtos.LoadFunctionResult(null, metadataOf(function));
    }

    // ------------------------------------------------------------------ 投影

    /**
     * 把一行 {@code lakehouse_function} 投影成整份 {@code FunctionMetadata}。
     *
     * <p>每**一个定义条目**（Paimon 按「定义名」存多条）投影成一个
     * {@code FunctionDefinition}（重载），各带一个版本；{@code definition-log}
     * 补一条以最近更新时间为准的记录，让必填字段齐整。
     */
    private ObjectNode metadataOf(FunctionEntity function) {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("function-uuid", UUID.nameUUIDFromBytes(
                function.getId().getBytes(StandardCharsets.UTF_8)).toString());
        metadata.put("format-version", 1);
        if (function.getComment() != null && !function.getComment().isBlank()) {
            metadata.put("doc", function.getComment());
        }
        Map<String, String> options = function.getOptions();
        if (options != null && !options.isEmpty()) {
            ObjectNode props = metadata.putObject("properties");
            options.forEach(props::put);
        }

        JsonNode inputParams = parseDoc(function.getInputParamsDoc());
        JsonNode returnParams = parseDoc(function.getReturnParamsDoc());
        String definitionId = definitionIdOf(inputParams);
        long timestamp = function.getUpdatedAt() != null ? function.getUpdatedAt() : function.getCreatedAt();

        ArrayNode definitions = metadata.putArray("definitions");
        ArrayNode logVersions = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<String, JsonNode> entry : parseDoc(function.getDefinitionsDoc()).properties()) {
            ObjectNode definition = projectDefinition(entry.getKey(), entry.getValue(),
                    inputParams, firstTypeOf(returnParams), definitionId, function, timestamp);
            if (definition != null) {
                definitions.add(definition);
                ObjectNode ref = logVersions.addObject();
                ref.put("definition-id", definitionId);
                ref.put("version-id", 1);
            }
        }
        ObjectNode logEntry = metadata.putArray("definition-log").addObject();
        logEntry.put("timestamp-ms", timestamp);
        logEntry.set("definition-versions", logVersions);
        return metadata;
    }

    /**
     * 投影一个定义条目；不是 SQL 定义（{@code file} / {@code lambda}）时回 {@code null} 跳过。
     *
     * <p>Paimon 的定义条目形状是 {@code {"type":"sql","definition":"SELECT …"}}（按定义名存放），
     * 因此这里只认「SQL 型」：显式 {@code type=sql}，或者没写 type 但带着 SQL 文本。
     */
    private ObjectNode projectDefinition(String name, JsonNode entry, JsonNode inputParams,
                                         JsonNode returnType, String definitionId,
                                         FunctionEntity function, long timestamp) {
        String sql = sqlOf(entry);
        if (sql == null) {
            return null;
        }
        ObjectNode definition = JsonNodeFactory.instance.objectNode();
        definition.put("definition-id", definitionId);
        definition.put("specific-name", name);
        definition.set("parameters", projectParameters(inputParams));
        if (returnType == null) {
            definition.put("return-type", "void");
        } else if (returnType.isObject()) {
            definition.set("return-type", returnType);
        } else {
            definition.put("return-type", returnType.asString());
        }
        ObjectNode version = definition.putArray("versions").addObject();
        version.put("version-id", 1);
        ObjectNode representation = version.putArray("representations").addObject();
        representation.put("type", "sql");
        representation.put("dialect", "spark");
        representation.put("sql", sql);
        if (Boolean.TRUE.equals(function.getDeterministic())) {
            version.put("deterministic", true);
        }
        version.put("timestamp-ms", timestamp);
        definition.put("current-version-id", 1);
        definition.put("function-type", "udf");
        return definition;
    }

    private static ArrayNode projectParameters(JsonNode inputParams) {
        ArrayNode parameters = JsonNodeFactory.instance.arrayNode();
        if (inputParams == null || !inputParams.isArray()) {
            return parameters;
        }
        for (JsonNode param : inputParams) {
            ObjectNode node = parameters.addObject();
            node.put("name", param.path("name").asString("arg"));
            JsonNode type = param.get("type");
            if (type == null || type.isNull()) {
                node.put("type", "unknown");
            } else if (type.isObject()) {
                node.set("type", type);
            } else {
                node.put("type", type.asString());
            }
            String doc = param.path("description").asString(null);
            if (doc != null && !doc.isBlank()) {
                node.put("doc", doc);
            }
        }
        return parameters;
    }

    /** {@code definition-id}：规格说「由参数类型派生的规范串，逗号分隔、无空格」。 */
    private static String definitionIdOf(JsonNode inputParams) {
        if (inputParams == null || !inputParams.isArray() || inputParams.isEmpty()) {
            return "";
        }
        List<String> types = new ArrayList<>();
        inputParams.forEach(param -> {
            JsonNode type = param.get("type");
            types.add(type == null || type.isNull() ? "unknown"
                    : (type.isObject() ? Json.write(type) : type.asString()));
        });
        return String.join(",", types);
    }

    /** 返回值取「返回值列表的第一个参数的类型」；列表为空时回 {@code null}。 */
    private static JsonNode firstTypeOf(JsonNode params) {
        if (params == null || !params.isArray() || params.isEmpty()) {
            return null;
        }
        return params.get(0).get("type");
    }

    /** 从一个定义条目里取 SQL 文本；不是 SQL 定义时回 {@code null}。 */
    private static String sqlOf(JsonNode entry) {
        if (entry == null || entry.isNull() || !entry.isObject()) {
            return null;
        }
        String type = entry.path("type").asString(null);
        if (type != null && !type.isBlank() && !"sql".equalsIgnoreCase(type)) {
            return null;
        }
        String sql = entry.path("definition").asString(null);
        if (sql == null || sql.isBlank()) {
            sql = entry.path("sql").asString(null);
        }
        return sql == null || sql.isBlank() ? null : sql;
    }

    private static JsonNode parseDoc(String document) {
        if (document == null || document.isBlank()) {
            return JsonNodeFactory.instance.missingNode();
        }
        return Json.readTree(document);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 解析 prefix，**不做格式门禁**——理由见类注释（函数数据只存在于 PAIMON catalog，
     * 过滤会让这两个 GET 永远为空）。
     */
    private CatalogEntity resolve(String prefix) {
        return catalogService.resolve(prefix);
    }

    private static ApiException functionNotExist(List<String> levels, String functionName) {
        return new ApiException(404, ResourceType.FUNCTION, functionName,
                "The given function does not exist: "
                        + NamespacePaths.toStorageName(levels) + "." + functionName,
                "NoSuchFunctionException");
    }
}
