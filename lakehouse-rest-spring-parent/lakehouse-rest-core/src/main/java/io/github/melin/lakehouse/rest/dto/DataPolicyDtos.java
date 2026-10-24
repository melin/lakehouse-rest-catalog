package io.github.melin.lakehouse.rest.dto;

import io.github.melin.lakehouse.rest.support.PageMeta;
import java.util.List;
import java.util.Map;

/**
 * 行过滤 / 列脱敏策略的 DTO（设计见 {@code docs/row-filter-column-mask-design.md} §5.2 / §5.4）。
 *
 * <p>表达式在请求与响应里都是<b>裸 JSON 节点</b>（{@code Object} 承载）：
 * 服务端校验白名单后才落库，DTO 层不做形状假设——把 AST 形状写进 DTO 类型
 * 等于把白名单复制一份，两份迟早漂移。
 */
public final class DataPolicyDtos {

    private DataPolicyDtos() {
    }

    // ------------------------------------------------------------------ 写入

    /** 一列的 mask 提交项。{@code mask} 是 {@code {"fn":..., "args":[...]}} 或 {@code {"expr":...}}。 */
    public record MaskUpsert(String column, Object mask, Boolean allowNullOnRequired) {
    }

    /**
     * 整份策略的 PUT 请求体：提交的是<b>完整策略</b>（行过滤 + 全部 mask + 豁免名单 +
     * 主体白名单），不是增量——逐列打补丁会让「删一条 mask」需要第四种动作，PUT 的幂等性
     * 也保不住。
     *
     * <p>{@code baseVersion}：首次创建传 {@code null}（或省略）；更新必须带上次读到的
     * {@code version}，不符回 409（与 catalog 实体同一机制）。
     *
     * <p>{@code subjects}：主体白名单（§13.2 SQL 的 {@code FOR} 子句写入）。空 / null =
     * 全部主体生效；非空 = 只对名单内主体生效。SQL 命令经 GET → 修改 → PUT 全量回写时，
     * 没带 {@code FOR} 子句的语句必须把上一次读到的名单原样带回——PUT 是整份替换。
     */
    public record PolicyUpsertRequest(Integer baseVersion, Boolean enabled, Object rowFilter,
                                      List<MaskUpsert> masks, List<String> hiddenColumns,
                                      List<String> exemptPrincipals,
                                      List<String> subjects, String enforcedScope) {
    }

    // ------------------------------------------------------------------ 读取

    /** 一列的 mask（管理面视图）。 */
    public record MaskEntry(String column, Map<String, Object> mask, String sql,
                            boolean allowNullOnRequired) {
    }

    /** 一张表的策略详情。没配过策略时端点回 200 + {@code null} 语义见控制器。 */
    public record PolicyDetail(String table, boolean enabled, Map<String, Object> rowFilter,
                               String rowFilterSql, List<MaskEntry> masks,
                               List<String> hiddenColumns,
                               List<String> exemptPrincipals, List<String> subjects,
                               String enforcedScope, int version,
                               String createdBy, Long createdAt, String updatedBy, Long updatedAt) {
    }

    /**
     * 总览页的一行摘要。列表页带条件文本（{@code rowFilterSql}）与脱敏列名
     * （{@code maskColumns}）：总览的判权与详情一致（{@code TABLE:ALTER} /
     * 全 catalog 要服务管理员），能看到列表的人本来就能看详情，
     * 把「条件是什么」平铺成 chip 才能让治理员不点进每一张表排查——
     * 悬空策略（引用列已删）尤其要一眼可见。
     */
    public record PolicySummary(String table, boolean enabled, boolean hasRowFilter,
                                String rowFilterSql, List<String> maskColumns,
                                List<String> hiddenColumns,
                                int maskedColumns, int exemptCount, int version,
                                String updatedBy, Long updatedAt) {
    }

    /** 策略总览：分页走既有 {@code PageMeta} 口径（先过滤再切页）。 */
    public record PolicyList(List<PolicySummary> items, PageMeta meta) {
    }

    // ------------------------------------------------------------------ 引擎下发（resolve）

    /** 下发给引擎的一条列绑定：列 + mask AST（原文，不含 $principal）。 */
    public record MaskBinding(String column, Map<String, Object> mask, boolean allowNullOnRequired) {
    }

    /**
     * 一张表的解析结果。
     *
     * <p>{@code status} 三值：<b>NONE</b> = 没策略或已停用（引擎放行——「没策略」与
     * 「没数据」等价）；<b>FOUND</b> = 命中，{@code rowFilter} / {@code masks} 已按
     * 调用者折叠（{@code $principal} 成字面量）；<b>EXEMPT</b> = 调用者在豁免名单内，
     * 引擎按无策略处理。服务错误（拿不到）不在这里——那是 5xx，由引擎 fail-closed 处理。
     */
    public record ResolvedPolicy(String table, String status, Integer version,
                                 Map<String, Object> rowFilter, String rowFilterSql,
                                 List<MaskBinding> masks, List<String> hiddenColumns,
                                 boolean exempted, String enforcedScope) {
    }

    /** 批量解析的响应。{@code principal} 回显的是服务端认定的主体名（排障用）。 */
    public record ResolveResult(String principal, List<ResolvedPolicy> policies) {
    }

    // ------------------------------------------------------------------ 主体试算（dry-run）

    /**
     * 主体试算的请求体（控制台「试算」页，设计 mockup 画面 4）：{@code principal}
     * 是<b>被模拟</b>的主体名（{@code user:}/{@code group:}/{@code role:} 前缀），
     * 策略按它折叠 {@code $principal}、判定豁免与白名单；{@code preview} 是普通
     * 预览请求（列选择、过滤、游标），为空走全默认。
     */
    public record DryRunRequest(String principal, PreviewDtos.PreviewRequest preview) {
    }

    /**
     * 主体试算的响应。{@code status} 语义与 {@link ResolvedPolicy} 一致（四态：
     * NONE / DISABLED / EXEMPT / FOUND），命中时 {@code preview} 已按该主体的
     * 策略求值——行被过滤、敏感列被 mask。试算只读、不落库，除预览自身的
     * {@code TABLE_PREVIEWED} 审计外不产生别的写路径。
     */
    public record DryRunResult(String table, String status, Integer version,
                               String rowFilterSql, String principal,
                               PreviewDtos.PreviewResponse preview) {
    }
}
