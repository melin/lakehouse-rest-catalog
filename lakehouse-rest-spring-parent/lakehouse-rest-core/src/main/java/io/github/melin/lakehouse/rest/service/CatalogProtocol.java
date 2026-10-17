package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 一个协议面（{@code paimon} / {@code iceberg}）在**装配层**需要暴露的能力。
 *
 * <p>它存在的理由只有一个：两套规格里有几条端点是**同路径**的，而它们的
 * 响应体形状或请求体形状按格式不同。控制器只有一份（路径不能注册两次），
 * 于是需要一个「按格式做事」的钩子，把请求交给对的那个面。
 *
 * <p><b>为什么只有三个方法。</b>设计文档里描述过更宽的形态
 * （{@code namespaceOf} / {@code tableOf} 等），落地时收窄成现在这样：其余能力
 * （namespace / table / view 的读写）本来就落在**各自的控制器树**上——
 * 两个规格的资源名词不同（Paimon 用 {@code databases}、Iceberg 用 {@code namespaces}），
 * 一个 {@code @RequestMapping} 树里放两套控制器不会互相抢路由，装配层从不需要
 * 认识它们。接口只有「装配层真正要用的那部分」，才不会长成一个什么都往里塞的门面。
 *
 * <p>三条同路径的端点分别对应下面三个方法：
 *
 * <table border="1">
 *   <caption>同路径端点与它们在两边的差异</caption>
 *   <tr><th>路径</th><th>Paimon</th><th>Iceberg</th><th>由谁处理</th></tr>
 *   <tr><td>{@code GET /v1/config}</td><td>{@code {defaults, overrides}}</td>
 *       <td>多一份 {@code endpoints}</td><td>{@link #configResponse}</td></tr>
 *   <tr><td>{@code POST /v1/{prefix}/tables/rename}</td>
 *       <td>{@code {source: {database, object}, …}}、回 200</td>
 *       <td>{@code {source: {namespace: [...], name}, …}}、回 204</td>
 *       <td rowspan="2">{@link #rename}</td></tr>
 *   <tr><td>{@code POST /v1/{prefix}/views/rename}</td><td>同上</td><td>同上</td></tr>
 * </table>
 *
 * <p><b>实现者由 Spring 组件扫描收集</b>（两个面落在同一个 fat jar、同一个 JVM 里），
 * core 因此不需要 import 任何一个面——两个协议面互不依赖这条不变量得以保持。
 */
public interface CatalogProtocol {

    /** 本实现服务的表格式。同一种格式只允许有一个实现。 */
    CatalogFormat format();

    /**
     * 组装 {@code GET /v1/config} 的响应体。
     *
     * <p>返回 {@code Object} 而不是某个具体类型，是因为**报文的形状本身按格式不同**：
     * Paimon 回 {@code {defaults, overrides}}，Iceberg 回
     * {@code {defaults, overrides, endpoints}}。让调用方（控制器）拿到一个"某种 JSON"
     * 就够了——它只负责序列化出去。
     *
     * @param catalog  已解析出的 catalog；实现可以读它的 {@code properties}
     *                 （例如 Iceberg 侧的 {@code io-impl}、云存储端点）
     * @param defaults 已含 {@code warehouse} / {@code prefix} 与 catalog 自身 defaults
     * @param overrides catalog 的 overrides
     */
    Object configResponse(CatalogEntity catalog, Map<String, String> defaults, Map<String, String> overrides);

    /**
     * 处理两条同路径的重命名端点。
     *
     * <p><b>请求体由实现自己绑定</b>（{@code Json.read(body, ...)}），而不是由
     * 调用方绑好再传进来：两边的请求体是**同名但不同字段**的形状
     * （Paimon 的标识符是 {@code {database, object}}，Iceberg 的是
     * {@code {namespace: [...], name}}），而 core 不认识任何一边的 DTO——
     * 那正是它不依赖协议面的意思。用 JSON 树穿过这层边界，让每个面拿自己的契约去绑。
     *
     * <p>成功时正常返回，失败时抛 {@code ApiException}。**状态码由调用方按格式给**：
     * Paimon 的规格写成 200、Iceberg 的写成 204，同一个动作在两份规格里就是两个码。
     *
     * @param catalog     已解析出的 catalog（调用方已经查过一次，面内不必再按 prefix 查）
     * @param resourceType {@code TABLE} 或 {@code VIEW}
     * @param body        请求体原文，可为 {@code null}
     */
    void rename(CatalogEntity catalog, ResourceType resourceType, JsonNode body);
}
