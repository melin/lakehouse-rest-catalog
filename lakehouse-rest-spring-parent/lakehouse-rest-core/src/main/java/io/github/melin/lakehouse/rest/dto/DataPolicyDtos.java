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

    /**
     * 下发给引擎的一条列绑定：列 + mask AST（原文，不含 $principal）。
     *
     * <p><b>{@code origin} 不是给引擎的开关，是给它排障用的。</b>引擎对两种来源一视同仁
     * （都是「这一列套这个 mask」），但一条 SQL 被脱敏之后有人在问「这列为什么是星号」时，
     * 答案是「策略里写了」还是「这列被打了标、catalog 配了绑定」，指向两个完全不同的
     * 处置动作（改策略 vs 改绑定）。没有这个字段，排查必须先去控制台翻两处。
     *
     * <p>它也让 {@code conflicts} 可解释：冲突里的「显式赢」正是靠区分两种来源判出来的。
     */
    public record MaskBinding(String column, Map<String, Object> mask, boolean allowNullOnRequired,
                              String origin) {

        /** 显式写在 {@code lakehouse_column_mask} 里的（表级策略，§5）。 */
        public static final String ORIGIN_EXPLICIT = "EXPLICIT";

        /** 由列标签 + catalog 绑定展开出来的（§8.1）。 */
        public static final String ORIGIN_TAG = "TAG";
    }

    /**
     * 标签展开时被放弃的候选（设计 §8.1 的「冲突不静默吞」）。
     *
     * <p><b>为什么必须下发而不是只记日志。</b>「我给这列配了绑定，为什么脱敏的样子是另一个」
     * 是一个会反复被问的问题，而它有两个完全正常的原因（显式 mask 优先、同列多命中选了
     * 更高的一档）。把原因跟着响应一起回去，控制台才能把它列出来；只写日志的话，
     * 答案在服务端的某个文件里，问问题的人看不到。
     *
     * <p>{@code reason} 三个取值，对应三种处置：
     * <ul>
     *   <li>{@code EXPLICIT_WINS} —— 这列有显式 mask，绑定被放弃。要改用绑定，先删策略里那条；</li>
     *   <li>{@code HIDDEN_CONFLICT} —— 这列在 {@code hidden_json} 里。整列不可见与脱敏叠加
     *       说不清谁生效，所以不展开；选一个；</li>
     *   <li>{@code OUTRANKED} —— 同列命中多条绑定，按 level / priority 选了另一条。</li>
     * </ul>
     *
     * <p><b>{@code tagKey} 是标签的键，{@code bindingKey} 是绑定的键——两者必须分开。</b>
     * 同一条标签（{@code PII.CN_ID_CARD}）可以同时命中 {@code PII.*} 与
     * {@code PII.CN_ID_CARD} 两条绑定，这时只报 tagKey 的话，两条记录一模一样，
     * 「输的是哪条规则」这个问题在响应里无法回答——而它正是治理员要改的那一条。
     */
    public record MaskConflict(String column, String tagKey, String tagValue, String bindingKey,
                               String reason, String detail) {

        public static final String REASON_EXPLICIT_WINS = "EXPLICIT_WINS";
        public static final String REASON_HIDDEN_CONFLICT = "HIDDEN_CONFLICT";
        public static final String REASON_OUTRANKED = "OUTRANKED";
    }

    /**
     * 一张表的解析结果。
     *
     * <p>{@code status} 三值：<b>NONE</b> = 没策略或已停用（引擎放行——「没策略」与
     * 「没数据」等价）；<b>FOUND</b> = 命中，{@code rowFilter} / {@code masks} 已按
     * 调用者折叠（{@code $principal} 成字面量）；<b>EXEMPT</b> = 调用者在豁免名单内，
     * 引擎按无策略处理。服务错误（拿不到）不在这里——那是 5xx，由引擎 fail-closed 处理。
     *
     * <p><b>FOUND 不再等于「有 DataPolicyEntity」。</b>一张从没配过策略的表，只要它的列
     * 被打了标、catalog 又配了对应绑定，也会回 FOUND（{@code version=null}，
     * 且所有 mask 的 {@code origin=TAG}）——这正是「新增列被识别打标后自动纳入覆盖，
     * 不用改策略」的落点。{@code version} 可空因此不是疏忽：没有策略就没有版本号，
     * 而引擎侧缓存必须靠标签变更事件失效，不能靠比对版本。
     */
    public record ResolvedPolicy(String table, String status, Integer version,
                                 Map<String, Object> rowFilter, String rowFilterSql,
                                 List<MaskBinding> masks, List<String> hiddenColumns,
                                 boolean exempted, String enforcedScope,
                                 List<MaskConflict> conflicts) {
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
