package io.github.melin.lakehouse.rest.iceberg.support;

import java.util.List;

/**
 * 本服务端在 Iceberg 侧**真正实现**的端点清单，也就是
 * {@code GET /v1/config} 响应里 {@code endpoints} 字段的内容。
 *
 * <p><b>为什么这份清单必须发、而且必须准。</b>Iceberg 规格写明：服务端若不返回
 * {@code endpoints}，客户端会**假定一整套默认端点存在**——那份默认清单里含
 * {@code POST /v1/{prefix}/transactions/commit}（多表事务），而本服务端一期不做多表事务。
 * 不发声明等于承诺了一个不存在的能力，故障现场离原因很远：客户端会一直正常工作到
 * 用户真的发起一次多表提交。客户端侧的行为已经实测过：拿不到声明时
 * {@code Endpoint.check(...)} 抛的是
 * {@code UnsupportedOperationException: Server does not support endpoint: ...}——
 * 有了准确清单，这个错在**连接阶段**就报出来。
 *
 * <p><b>字符串格式与 Iceberg 自己的 {@code Endpoint.toString()} 逐字一致</b>
 * （{@code "<HTTP 方法> <规格里的资源路径>"}）。这一点不能凭规格文档抄：客户端会
 * 拿这份清单构造 {@code Endpoint} 再与它自己需要的端点做等值比较，多一个空格、
 * 少一个 {@code {prefix}} 都会静默地让比较失败。清单里的每一行都是从
 * {@code org.apache.iceberg.rest.Endpoint} 的常量反射打印出来的（见
 * {@code docs/iceberg-rest-catalog-design.md} §9 的校验手段），
 * 唯一的例外是 {@code unregister}——上游没有为它定义常量，字面量取自 OpenAPI 规格。
 *
 * <p><b>为什么是硬编码的字符串而不是引用 {@code Endpoint}。</b>引用它会在类初始化时
 * 拉进 {@code org.apache.hc.core5.http.Method}（实测：缺 httpclient5 时
 * {@code Endpoint} 的静态块直接 {@code NoClassDefFoundError}）。服务端为了**声明**一份
 * 清单而去依赖客户端的 HTTP 实现，是把客户端的世界拖进服务端。
 *
 * <p>这份清单与控制器上的映射一一对应，由
 * {@code scripts/verify-iceberg-endpoints.py} 机械校验——「声明了但没实现」
 * 与「实现了但没声明」两种错都会在那条校验里露出来。
 *
 * <p><b>一期不实现的 9 个端点</b>（因此不在这里声明）：
 * {@code functions} 只读两个、{@code plan} 两个 + {@code tasks} 一个、
 * {@code sign} 一个、{@code transactions/commit} 一个、{@code register-view} 一个。
 * 原因见 {@code docs/iceberg-rest-catalog-design.md} §6。
 */
public final class IcebergEndpoints {

    /**
     * 已实现的端点，按「namespace → table → view → 装配层」的次序排列。
     *
     * <p>次序只影响可读性与校验脚本的 diff 结果，客户端的比较是基于集合的。
     */
    public static final List<String> SUPPORTED = List.of(
            // ---- 装配层（不带 prefix：客户端在拿到 catalog 配置之前就要用）
            "GET /v1/config",
            "POST /v1/oauth/tokens",

            // ---- namespace
            "GET /v1/{prefix}/namespaces",
            "POST /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "HEAD /v1/{prefix}/namespaces/{namespace}",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties",

            // ---- table
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "POST /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
            "POST /v1/{prefix}/namespaces/{namespace}/register",
            "POST /v1/{prefix}/tables/rename",

            // ---- view
            "GET /v1/{prefix}/namespaces/{namespace}/views",
            "POST /v1/{prefix}/namespaces/{namespace}/views",
            "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
            "POST /v1/{prefix}/views/rename");

    private IcebergEndpoints() {
    }
}
