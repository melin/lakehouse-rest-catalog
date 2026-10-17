package io.github.melin.lakehouse.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * 通用 DTO：错误响应、资源标识、配置发现。
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    /**
     * 错误响应。{@code resourceType} / {@code resourceName} 可空，
     * 取值见 {@code io.github.melin.lakehouse.rest.support.ResourceType}。
     */
    public record ErrorResponse(String message, String resourceType, String resourceName, Integer code) {
    }

    /** 由 database 名称与对象名称组成的全局标识。 */
    public record Identifier(String database, String object) {
    }

    /**
     * {@code GET /v1/config} 响应（**Paimon 形状**）。
     *
     * <p>客户端合并顺序为 defaults → client properties → overrides，后者优先。
     *
     * <p><b>Iceberg 格式的响应不是这个类型</b>，见 {@link #IcebergConfigResponse}：
     * 规格要求 Iceberg 侧额外下发 {@code endpoints} 能力清单，而 Paimon 规格里
     * 根本没有这个字段。两者共用一个类型就得给字段加「按格式可空」的注解，
     * 而那会让「Paimon 的响应体」变成一件取决于注解解释方式的事。
     */
    public record ConfigResponse(Map<String, String> defaults, Map<String, String> overrides) {
    }

    /**
     * {@code GET /v1/config} 响应（**Iceberg 形状**）：在 Paimon 的两段之上多一份
     * {@code endpoints}。
     *
     * <p><b>{@code endpoints} 必须发，不能省。</b>Iceberg 规格写明：服务端若不返回
     * 这个字段，客户端会**假定一整套默认端点存在**，而那份默认清单里包含
     * {@code POST /v1/{prefix}/transactions/commit}（多表事务）。不发声明，等于承诺了
     * 一个本服务端不做的东西——等客户端真的发起多表提交才 404，故障现场离原因很远。
     * 因此这里列出的必须是「真正实现了的」那一份清单，它与控制器上的映射一一对应，
     * 由 {@code scripts/verify-iceberg-endpoints.py} 机械校验。
     */
    public record IcebergConfigResponse(Map<String, String> defaults, Map<String, String> overrides,
                                        List<String> endpoints) {
    }

    /**
     * Iceberg 错误响应：{@code {"error": {"message", "type", "code"}}}。
     *
     * <p>与 Paimon 侧扁平的 {@link ErrorResponse} 是**两套形状**，按请求所属 catalog 的
     * 格式二选一（见 {@code GlobalExceptionHandler}）。Spark 的 Iceberg 客户端把响应体
     * 反序列化成 {@code ErrorResponse}，拿不到 {@code error} 字段就只能报一句
     * 「Unable to process: …」，看不出真正的原因。
     *
     * <p>规格的 {@code ErrorModel} 还有一个可选的 {@code stack}（服务端堆栈）。
     * 这里**不发**：它是排障用的，而本服务端的排障入口是服务端日志与审计表，
     * 把堆栈回给客户端既无用又泄露内部结构。
     */
    public record IcebergErrorResponse(IcebergError error) {

        /** {@code ErrorModel}。{@code code} 即 HTTP 状态码。 */
        public record IcebergError(String message, String type, Integer code) {
        }
    }
}
