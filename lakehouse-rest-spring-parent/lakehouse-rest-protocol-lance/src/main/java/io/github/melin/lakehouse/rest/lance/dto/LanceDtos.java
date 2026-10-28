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
 * <p><b>不出现 {@code managed_versioning} 字段</b>：它一旦为 {@code true}，客户端的写入
 * 路径就完全依赖服务端分配版本号，而我们不做提交协调（规范里它属于
 * {@code CreateTableVersion} 那一组）。不返回该字段即 {@code false}，
 * 这是规范允许的缺省表达，比显式 {@code false} 更不容易被误读成「可开关」。
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
     */
    public record DescribeTableResponse(@JsonProperty("id") String id,
                                        @JsonProperty("location") String location,
                                        @JsonProperty("properties") Map<String, String> properties,
                                        @JsonProperty("storage_options") Map<String, String> storageOptions,
                                        @JsonProperty("table_uri") String tableUri,
                                        @JsonProperty("is_only_declared") Boolean isOnlyDeclared) {
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
}
