package io.github.melin.lakehouse.rest.lance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Lance Namespace / REST Catalog 的报文契约。
 *
 * <p><b>字段名一律 snake_case 且逐字照规范写，因此每个字段都显式带
 * {@link JsonProperty}。</b>与 IcebergDtos 是同一个理由：报文的消费者是 Lance 自己的
 * 客户端（Python SDK / Java OpenAPI 生成代码 / Spark connector），它按规范拼字段名；
 * 「与规范一致」在这里比「与工程内其它 DTO 的驼峰一致」重要。
 *
 * <p><b>{@code id} 在本面一律是**字符串**（{@code cat$schema$table}），不是数组。</b>
 * 规范两层模型（Client spec 与 REST spec）里，抽象那一层把 id 写成层级列表，
 * 而 REST 那一层把它挪进路径、拼成一个串。我们实现的是后者，因此契约里
 * 不存在「数组形态的 id」——请求体里若出现，由 {@code LanceIds} 判一致性时拒绝。
 *
 * <p><b>{@code managed_versioning} 只在为 {@code true} 时出现</b>（P2 起它是表级开关）。
 * 规范里它的缺省是 {@code false}，因此「不出现该键」与「{@code false}」同义；
 * 只有真的开了托管才写 {@code true}，这样客户端不会看到一串恒为 {@code false} 的字段
 * 而去猜「这个服务端是不是支持托管」。
 *
 * <p><b>一处刻意的不一致：{@code TagContents.manifestSize} 是驼峰</b>（其余全是
 * snake_case）。这是规范原文，不是笔误——见 {@link TagContents}。
 */
public final class LanceDtos {

    private LanceDtos() {
    }

    // ------------------------------------------------------------------ 通用

    /**
     * 一张表（或一个命名空间）的登记信息。
     *
     * @param storageOptions 开数据集所需的存储定位与临时凭据；仅在下发凭据时含密钥
     */
    public record TableRef(@JsonProperty("id") String id,
                           @JsonProperty("location") String location,
                           @JsonProperty("properties") Map<String, String> properties,
                           @JsonProperty("storage_options") Map<String, String> storageOptions) {
    }

    // ------------------------------------------------------------------ namespace

    /**
     * {@code POST /lance/v1/namespace/{id}/create}。
     *
     * <p>{@code id} 可空：空表示「以路径里的 id 为准」——规范允许 body 省略它。
     */
    public record CreateNamespaceRequest(@JsonProperty("id") String id,
                                         @JsonProperty("properties") Map<String, String> properties) {
    }

    public record CreateNamespaceResponse(@JsonProperty("id") String id,
                                          @JsonProperty("properties") Map<String, String> properties) {
    }

    /** 列举结果里的一个命名空间（id + 属性）。 */
    public record NamespaceRef(@JsonProperty("id") String id,
                               @JsonProperty("properties") Map<String, String> properties) {
    }

    public record ListNamespacesResponse(@JsonProperty("namespaces") List<NamespaceRef> namespaces,
                                         @JsonProperty("page_token") String pageToken) {
    }

    /** {@code POST .../describe} 与 {@code .../exists} 的成功响应（后者是空体，不复用它）。 */
    public record DescribeNamespaceResponse(@JsonProperty("id") String id,
                                            @JsonProperty("properties") Map<String, String> properties) {
    }

    /**
     * {@code POST .../drop} 的响应：规范里是个空对象。
     *
     * <p>显式返回一个空 record 而不是 {@code ResponseEntity<Void>}，是为了让响应体是
     * {@code {}} 而不是空串——OpenAPI 生成的客户端会把空串当成反序列化失败。
     */
    public record EmptyResponse() {
    }

    // ------------------------------------------------------------------ table

    public record ListTablesResponse(@JsonProperty("tables") List<String> tables,
                                     @JsonProperty("page_token") String pageToken) {
    }

    /**
     * {@code POST .../declare}：登记建表意图。
     *
     * <p><b>{@code location} 可空。</b>不给时由服务端按仓库模板分配；
     * 给了要过 catalog 的 {@code allowedLocations} 白名单。
     */
    public record DeclareTableRequest(@JsonProperty("id") String id,
                                      @JsonProperty("location") String location,
                                      @JsonProperty("properties") Map<String, String> properties) {
    }

    /**
     * {@code POST .../register}：登记一个**已经存在**的数据集。
     *
     * <p>与 declare 的差别只在校验强度：register 会探测 {@code _versions/} 下有没有
     * manifest，declare 不碰存储。
     */
    public record RegisterTableRequest(@JsonProperty("id") String id,
                                       @JsonProperty("location") String location,
                                       @JsonProperty("properties") Map<String, String> properties) {
    }

    /**
     * {@code POST .../describe} 的响应。
     *
     * @param isOnlyDeclared 三态：{@code true} 登记了但存储里还没有数据集、
     *                       {@code false} 有、{@code null} 没检查（默认不探测）。
     *                       **不能拍平成两态**——「不知道」与「没有」对客户端是不同的决策。
     * @param tableUri      {@code with_table_uri=true} 时才填：规范化成合法 URI 的 location
     * @param table         {@code load_detailed_metadata=true} 时才填：表名
     * @param namespace     {@code load_detailed_metadata=true} 时才填：{@code [catalog, schema]}
     * @param version       {@code load_detailed_metadata=true} 时才填：manifest 里的版本号；
     *                      读不出 manifest 时为 {@code null}（降级，不是错误）
     * @param schema        {@code load_detailed_metadata=true} 时才填：JSON Arrow
     * @param stats         {@code load_detailed_metadata=true} 时才填：fragment 数与删除行数
     * @param managedVersioning {@code true} 表示这张表的版本由服务端记账；
     *                       {@code null}（不出现该键）即规范里的缺省 {@code false}
     */
    public record DescribeTableResponse(@JsonProperty("id") String id,
                                        @JsonProperty("location") String location,
                                        @JsonProperty("properties") Map<String, String> properties,
                                        @JsonProperty("storage_options") Map<String, String> storageOptions,
                                        @JsonProperty("table_uri") String tableUri,
                                        @JsonProperty("is_only_declared") Boolean isOnlyDeclared,
                                        @JsonProperty("table") String table,
                                        @JsonProperty("namespace") List<String> namespace,
                                        @JsonProperty("version") Long version,
                                        @JsonProperty("schema") Map<String, Object> schema,
                                        @JsonProperty("stats") TableStats stats,
                                        @JsonProperty("managed_versioning") Boolean managedVersioning) {
    }

    /** {@code DescribeTableResponse.stats}：规范只要求这两个数，都不可省。 */
    public record TableStats(@JsonProperty("num_fragments") long numFragments,
                              @JsonProperty("num_deleted_rows") long numDeletedRows) {
    }

    /** {@code POST .../deregister} 的响应：带回 location 与属性，便于调用方自己清理目录。 */
    public record DeregisterTableResponse(@JsonProperty("id") String id,
                                          @JsonProperty("location") String location,
                                          @JsonProperty("properties") Map<String, String> properties) {
    }

    /**
     * {@code POST .../rename}：{@code new_name} 是**新表名**（同一 schema 内）
     * 或 {@code new_schema$new_table} 形态的目标 id。
     *
     * <p>跨 catalog 一律 400：本服务端的 rename 是 catalog 内操作，
     * 跨 catalog 涉及两行不同的 catalog_id，不是一次改名的语义。
     */
    public record RenameTableRequest(@JsonProperty("id") String id,
                                     @JsonProperty("new_name") String newName) {
    }

    // ------------------------------------------------------------------ 版本（P1 读 / P2 写）

    /**
     * 一个版本。
     *
     * <p><b>{@code manifest_path} 是必填</b>（规范里它是 required），
     * 其余都可为 {@code null}：{@code manifest_size} 与 {@code e_tag} 客户端未必给，
     * {@code timestamp_millis} 在「只知道版本号、没列举过文件」时我们给不出。
     */
    public record TableVersion(@JsonProperty("version") long version,
                                @JsonProperty("manifest_path") String manifestPath,
                                @JsonProperty("manifest_size") Long manifestSize,
                                @JsonProperty("e_tag") String eTag,
                                @JsonProperty("timestamp_millis") Long timestampMillis,
                                @JsonProperty("metadata") Map<String, String> metadata) {
    }

    public record ListTableVersionsResponse(@JsonProperty("versions") List<TableVersion> versions,
                                             @JsonProperty("page_token") String pageToken) {
    }

    /** {@code POST .../version/describe}：只带版本号（id 在路径里）。 */
    public record DescribeTableVersionRequest(@JsonProperty("id") String id,
                                               @JsonProperty("version") Long version) {
    }

    public record DescribeTableVersionResponse(@JsonProperty("version") TableVersion version) {
    }

    /**
     * {@code POST .../version/create}。
     *
     * <p><b>版本号由客户端给，不是服务端分配。</b>规范把它列为 required：
     * 服务端的职责是「记下这个号」并在重复时拒绝。若换成服务端分配，
     * 客户端按自己算的下一个版本重试时就永远对不上。
     */
    public record CreateTableVersionRequest(@JsonProperty("id") String id,
                                             @JsonProperty("version") Long version,
                                             @JsonProperty("manifest_path") String manifestPath,
                                             @JsonProperty("manifest_size") Long manifestSize,
                                             @JsonProperty("e_tag") String eTag,
                                             @JsonProperty("metadata") Map<String, String> metadata) {
    }

    public record CreateTableVersionResponse(@JsonProperty("version") TableVersion version) {
    }

    /**
     * 一段版本区间：[{@code start_version}, {@code end_version})。
     *
     * <p>{@code end_version = -1} 是规范约定的「到最新为止（含）」；
     * 其它负值一律判非法——静默忽略一个负数会把「删错了范围」变成数据事故。
     */
    public record VersionRange(@JsonProperty("start_version") Long startVersion,
                                @JsonProperty("end_version") Long endVersion) {
    }

    public record BatchDeleteTableVersionsRequest(@JsonProperty("id") String id,
                                                   @JsonProperty("ranges") List<VersionRange> ranges) {
    }

    /** 删的是**版本账**，不是数据文件；原生的版本管理没有账可删，{@code deleted_count} 为 0。 */
    public record BatchDeleteTableVersionsResponse(@JsonProperty("deleted_count") long deletedCount) {
    }

    // ------------------------------------------------------------------ 标签（P2·L2-2，只读）

    /**
     * 一个标签的内容。
     *
     * <p><b>{@code manifestSize} 是驼峰。</b>规范在 {@code TagContents} 上唯一一次
     * 没跟 snake_case：{@code TableVersion} 用 {@code manifest_size}，这里用
     * {@code manifestSize}（盘上的 JSON 也是驼峰）。写错的后果不是编译失败，
     * 而是客户端读到一个 {@code null}，看上去像「这个标签没有 size」。
     *
     * <p>{@code branch} 为 {@code null} 表示主分支——盘上「缺该键」与「值为 null」
     * 是同一个意思，这里都不填。
     */
    public record TagContents(@JsonProperty("branch") String branch,
                              @JsonProperty("version") long version,
                              @JsonProperty("manifestSize") Long manifestSize) {
    }

    /** {@code POST .../tags/list}：{@code tags} 是**map**（tag 名 → 内容），不是数组。 */
    public record ListTableTagsResponse(@JsonProperty("tags") Map<String, TagContents> tags,
                                        @JsonProperty("page_token") String pageToken) {
    }

    /** {@code POST .../tags/version}：只需要标签名（id 在路径里）。 */
    public record GetTableTagVersionRequest(@JsonProperty("id") String id,
                                            @JsonProperty("tag") String tag) {
    }

    /**
     * {@code POST .../tags/version} 的响应。
     *
     * <p>{@code branch} 为 {@code null} = 主分支；规范原话是「Absent when the tag points
     * to the main branch」，因此不额外编一个 {@code "main"} 出来（分支名 {@code main}
     * 是保留字，它不代表真有一个叫 main 的分支元数据文件）。
     */
    public record GetTableTagVersionResponse(@JsonProperty("version") long version,
                                             @JsonProperty("branch") String branch) {
    }
}
