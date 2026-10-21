package io.github.melin.lakehouse.rest.iceberg.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg REST Catalog 的报文契约（{@code spec/rest-catalog-open-api-iceberg.yaml}）。
 *
 * <p><b>字段名一律 kebab-case，与规格逐字一致</b>，因此每个组件都显式写
 * {@link JsonProperty}。这与本工程其余 DTO 的驼峰约定不同，但这里是刻意的：
 * 报文的消费者是 Iceberg 自己的客户端（Spark / Flink / PyIceberg / Java 库），
 * 它按规格拼字段名，「与规范一致」在这里比「与工程内一致」重要——
 * 与 {@code ConsoleDtos.TokenResponse} 为 RFC 6749 破例是同一个理由。
 *
 * <p><b>几处字段刻意用 {@link JsonNode} 而不是建类型：</b>{@code metadata}、
 * {@code schema}、{@code partition-spec}、{@code write-order}、{@code updates}、
 * {@code requirements}、{@code view-version}。它们的内容由 Iceberg 的元数据代数定义，
 * 而那个代数**随上游版本漂移**（1.9→1.10 新增了 {@code next-row-id} /
 * {@code encryption-keys} / {@code read-restrictions}）。用核心库自己的
 * {@code TableMetadataParser} / {@code SchemaParser} / {@code MetadataUpdateParser}
 * 去读它们，就自动跟着上游走；在这里再建一套 DTO 等于把上游的 schema
 * 抄一份并**承诺它不变**。
 *
 * <p>这条纪律同时也解释了为什么 {@code metadata} 回程也是 {@link JsonNode}：
 * 服务端存的是整份文档原文（{@code lakehouse_table.metadata_doc}），
 * 原样嵌回去才是「引擎读到的与服务端存的是同一份」。
 */
public final class IcebergDtos {

    private IcebergDtos() {
    }

    // ------------------------------------------------------------------ 通用

    /**
     * {@code TableIdentifier}：{@code {namespace: [...], name}}。
     *
     * <p>{@code namespace} 是**数组**——这一点与 Paimon 的 {@code Identifier}
     * （{@code {database, object}} 两个扁平字符串）不同，也是两条 {@code rename}
     * 路径唯一的区分判据（见 {@code CatalogAccessRules} 与两个面的控制器）。
     */
    public record TableIdentifier(@JsonProperty("namespace") List<String> namespace,
                                  @JsonProperty("name") String name) {
    }

    /**
     * {@code StorageCredential}：{@code {prefix, config}}。
     *
     * <p>{@code prefix} 是「这份凭据对哪个存储位置前缀有效」，客户端按最长前缀匹配选择。
     */
    public record StorageCredential(@JsonProperty("prefix") String prefix,
                                    @JsonProperty("config") Map<String, String> config) {
    }

    // ------------------------------------------------------------------ namespace

    public record ListNamespacesResponse(@JsonProperty("namespaces") List<List<String>> namespaces,
                                         @JsonProperty("next-page-token") String nextPageToken,
                                         @JsonProperty("total") Integer total ) {
    }

    public record CreateNamespaceRequest(@JsonProperty("namespace") List<String> namespace,
                                         @JsonProperty("properties") Map<String, String> properties) {
    }

    public record CreateNamespaceResponse(@JsonProperty("namespace") List<String> namespace,
                                          @JsonProperty("properties") Map<String, String> properties) {
    }

    public record GetNamespaceResponse(@JsonProperty("namespace") List<String> namespace,
                                       @JsonProperty("properties") Map<String, String> properties) {
    }

    /** {@code POST .../namespaces/{ns}/properties}：一次批量增删，原子生效。 */
    public record UpdateNamespacePropertiesRequest(@JsonProperty("removals") List<String> removals,
                                                   @JsonProperty("updates") Map<String, String> updates) {
    }

    /**
     * {@code updates} 与 {@code removals} 是规格的必填项（可为空数组），
     * {@code missing} 是「请求删除但不存在的键」——规格说服务端可以只做部分成功，
     * 本服务端按规格建议如实报告。
     */
    public record UpdateNamespacePropertiesResponse(@JsonProperty("updated") List<String> updated,
                                                    @JsonProperty("removed") List<String> removed,
                                                    @JsonProperty("missing") List<String> missing) {
    }

    // ------------------------------------------------------------------ table

    public record ListTablesResponse(@JsonProperty("identifiers") List<TableIdentifier> identifiers,
                                     @JsonProperty("next-page-token") String nextPageToken,
                                         @JsonProperty("total") Integer total ) {
    }

    /**
     * {@code POST .../namespaces/{ns}/tables}。
     *
     * <p>{@code stage-create} 本服务端**不支持**（多表事务未实现，暂存的表没有任何
     * 提交路径），收到 {@code true} 时明确报 **400** 而不是静默忽略：忽略等于让客户端以为
     * 它拿到了一张暂存的表，而它拿到的是已提交的——两者在后续的
     * {@code transactions/commit} 语义上完全不同。
     *
     * <p>报 400 而不是 501（{@code UnsupportedOperationException} 那个状态码）：
     * 501 说的是「这个**服务端**没有实现这个能力」，而 {@code stage-create} 是**报文里的
     * 一个字段**，与服务端的能力面不是一回事——服务端实现的能力是「建表」，
     * 只是不接受这一个字段的取值。客户端的处置也不同：400 表示报文有问题、
     * 改报文可重试，501 表示换服务端才行。
     */
    public record CreateTableRequest(@JsonProperty("name") String name,
                                     @JsonProperty("location") String location,
                                     @JsonProperty("schema") JsonNode schema,
                                     @JsonProperty("partition-spec") JsonNode partitionSpec,
                                     @JsonProperty("write-order") JsonNode writeOrder,
                                     @JsonProperty("stage-create") Boolean stageCreate,
                                     @JsonProperty("properties") Map<String, String> properties) {
    }

    /**
     * {@code LoadTableResult}：{@code {metadata-location, metadata, config, storage-credentials}}。
     *
     * <p>{@code config} 与 {@code storage-credentials} 都可为 {@code null}（不下发时）——
     * 规格允许缺席。凭据走哪个字段有讲究：{@code storage-credentials} 是首选，
     * 老的 {@code config} 里的 {@code s3.access-key-id} 之类是兼容路径。
     * 本服务端只填 {@code storage-credentials}，并在客户端明确要求
     * （{@code X-Iceberg-Access-Delegation}）时才填。
     */
    public record LoadTableResponse(@JsonProperty("metadata-location") String metadataLocation,
                                    @JsonProperty("metadata") JsonNode metadata,
                                    @JsonProperty("config") Map<String, String> config,
                                    @JsonProperty("storage-credentials") List<StorageCredential> storageCredentials) {
    }

    /** {@code POST .../namespaces/{ns}/register}：登记一个已存在于对象存储的 Iceberg 表。 */
    public record RegisterTableRequest(@JsonProperty("name") String name,
                                       @JsonProperty("metadata-location") String metadataLocation,
                                       @JsonProperty("overwrite") Boolean overwrite) {
    }

    /** {@code POST /v1/{prefix}/tables/rename}：{@code namespace} 是数组，与 Paimon 的请求体不同。 */
    public record RenameTableRequest(@JsonProperty("source") TableIdentifier source,
                                     @JsonProperty("destination") TableIdentifier destination) {
    }

    /**
     * {@code POST .../tables/{table}}：一轮 update 作用在当前元数据上。
     *
     * <p>{@code identifier} 在单表提交里可以缺席（路径已经指明了表）；在
     * {@link CommitTransactionRequest 多表事务} 的每个子请求里它是**必填的**——
     * 路径不再指明对象，缺失时服务端按 400 拒绝整笔事务。
     */
    public record UpdateTableRequest(@JsonProperty("identifier") TableIdentifier identifier,
                                     @JsonProperty("requirements") List<JsonNode> requirements,
                                     @JsonProperty("updates") List<JsonNode> updates) {
    }

    /**
     * {@code POST /v1/{prefix}/transactions/commit}：把多张表的一轮提交包成一个原子单元。
     *
     * <p>规格里每个元素就是 {@code CommitTableRequest}，形状与单表提交的报文体相同
     * （{@code identifier + requirements + updates}），因此直接复用
     * {@link UpdateTableRequest}——它与单表端点共用同一条解析与应用路径
     * （{@code IcebergUpdateCodec}），只是多包了一层「全部成立才落库」的闸。
     */
    public record CommitTransactionRequest(
            @JsonProperty("table-changes") List<UpdateTableRequest> tableChanges) {
    }

    public record LoadCredentialsResponse(@JsonProperty("storage-credentials") List<StorageCredential> storageCredentials) {
    }

    // ------------------------------------------------------------------ view

    public record ListViewsResponse(@JsonProperty("identifiers") List<TableIdentifier> identifiers,
                                    @JsonProperty("next-page-token") String nextPageToken,
                                         @JsonProperty("total") Integer total ) {
    }

    public record CreateViewRequest(@JsonProperty("name") String name,
                                    @JsonProperty("location") String location,
                                    @JsonProperty("schema") JsonNode schema,
                                    @JsonProperty("view-version") JsonNode viewVersion,
                                    @JsonProperty("properties") Map<String, String> properties) {
    }

    public record LoadViewResponse(@JsonProperty("metadata-location") String metadataLocation,
                                   @JsonProperty("metadata") JsonNode metadata,
                                   @JsonProperty("config") Map<String, String> config) {
    }

    /**
     * {@code POST .../namespaces/{ns}/register-view}：登记仓库里已存在的一份
     * {@code ViewMetadata}。
     *
     * <p>与表的 {@link RegisterTableRequest} 相比少一个 {@code overwrite}——规格没定义它，
     * 重名时服务端直接 409。
     */
    public record RegisterViewRequest(@JsonProperty("name") String name,
                                      @JsonProperty("metadata-location") String metadataLocation) {
    }

    public record UpdateViewRequest(@JsonProperty("identifier") TableIdentifier identifier,
                                    @JsonProperty("requirements") List<JsonNode> requirements,
                                    @JsonProperty("updates") List<JsonNode> updates) {
    }

    // ------------------------------------------------------------------ function

    public record ListFunctionsResponse(@JsonProperty("identifiers") List<TableIdentifier> identifiers,
                                        @JsonProperty("next-page-token") String nextPageToken,
                                         @JsonProperty("total") Integer total ) {
    }

    /**
     * {@code GET .../functions/{function}}：{@code LoadFunctionResult}。
     *
     * <p>{@code metadata} 是整份 {@code FunctionMetadata} JSON；本服务端的函数没有
     * 元数据文件（它是 {@code lakehouse_function} 一行的只读投影），因此
     * {@code metadata-location} 恒为 {@code null}——规格说「if available」，缺席即如实。
     */
    public record LoadFunctionResult(@JsonProperty("metadata-location") String metadataLocation,
                                     @JsonProperty("metadata") JsonNode metadata) {
    }
}
