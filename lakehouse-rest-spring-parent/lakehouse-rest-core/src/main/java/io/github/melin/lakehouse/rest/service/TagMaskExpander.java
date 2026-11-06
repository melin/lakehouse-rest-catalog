package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import io.github.melin.lakehouse.rest.domain.entity.TagMaskBindingEntity;
import io.github.melin.lakehouse.rest.domain.repo.ColumnTagRepository;
import io.github.melin.lakehouse.rest.domain.repo.TagMaskBindingRepository;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskConflict;
import io.github.melin.lakehouse.rest.support.ColumnRefs;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 标签 → 列 mask 的展开（设计见 {@code docs/sensitive-data-design.md} §8.1，P3 的核心价值）。
 *
 * <p><b>它只做一件纯计算的事</b>：给定一个 catalog 的启用绑定、一张表的列标签、以及这张表
 * 已有的显式 mask / 不可见列清单，算出「哪些列该套哪条 mask」以及「哪些候选被放弃了」。
 * 它不落库、不发事件、不判权——把这些留在 {@code DataPolicyService} 与
 * {@code TagMaskBindingService}，是因为展开逻辑会被三条读路径（引擎 resolve、
 * 控制台预览、主体试算）与两条写路径（建绑定、打标签）共用，
 * 混进副作用就会有一份「先写了才发现不该写」的分支。
 *
 * <p><b>展开是查询期行为，不是落库行为。</b>打标签时<b>不</b>把 mask 写进策略表：
 * 写进去之后「改一条 catalog 级绑定」要改回多少张表、多少行没人知道，
 * 而漏改的那几张不会报错，只是继续按老规则脱敏。查询期展开的代价是每次 resolve
 * 多两次查询（绑定按 catalog 一次、标签按表一次），换来「改一处，全 catalog 立刻一致」。
 *
 * <p><b>三条排序规则与三条冲突，全部在这里唯一实现</b>（§8.1 / §8.2）：
 * <ol>
 *   <li>显式 mask 优先——同列有显式 mask 时绑定直接放弃（记 {@code EXPLICIT_WINS}）；</li>
 *   <li>不可见列不展开——mask 与 hidden 叠加在同一列上说不清谁生效（记 {@code HIDDEN_CONFLICT}）；</li>
 *   <li>同列多命中按 {@code level desc, priority desc, 精确匹配优先, tag_key asc} 取一条，
 *       其余记 {@code OUTRANKED}。</li>
 * </ol>
 * 第 3 条里「精确匹配优先」是设计稿三项之外的第四个 tie-breaker：只在级别与优先级
 * 都相等时才生效，而这时「写死 {@code PII.CN_ID_CARD} 的人」比「写 {@code PII.*} 的人」
 * 更具体，让通配赢会让一条泛化规则悄悄盖掉一条精确规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagMaskExpander {

    /** 前缀通配的后缀：{@code PII.*} 表示 {@code PII} 域下任意类型。 */
    public static final String WILDCARD_KEY_SUFFIX = ".*";

    /** 未指定优先级时的取值（与实体默认一致）。 */
    public static final int DEFAULT_PRIORITY = 0;

    private final ColumnTagRepository tagRepository;
    private final TagMaskBindingRepository bindingRepository;

    /**
     * 一条展开出来的 mask。
     *
     * <p>{@code level} / {@code tagValue} 可能为 {@code null}：手工标签（{@code owner.team}）
     * 没有分级，但它照样可以配绑定（「凡是这个键的列都脱敏」）。
     * {@code wildcardKey} 记录命中是否来自前缀通配——冲突报告要能说清
     * 「赢的是 {@code PII.*} 还是 {@code PII.CN_ID_CARD}」。
     */
    public record TagMask(String column,
                          String tagKey,
                          String tagValue,
                          String typeCode,
                          SensitivityLevel level,
                          int priority,
                          boolean wildcardKey,
                          Map<String, Object> mask,
                          String maskSql) {
    }

    /**
     * 一次展开的结果。{@code masks} 按列名有序（{@link LinkedHashMap}），
     * 保证同一份输入两次下发的 JSON 字节一致——引擎侧按字节比对的话不会抖。
     */
    public record Expansion(Map<String, TagMask> masks, List<MaskConflict> conflicts) {

        public static Expansion empty() {
            return new Expansion(Map.of(), List.of());
        }

        public boolean isEmpty() {
            return masks.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 读

    /** 某 catalog 的全部启用绑定，已按优先级降序（catalog 级规则，一次取全量）。 */
    public List<TagMaskBindingEntity> bindingsOf(String catalogName) {
        return bindingRepository.findByCatalogNameAndEnabledTrueOrderByPriorityDesc(catalogName);
    }

    /**
     * 展开一张表：只用显式 mask 与标签做冲突判定，不涉及 hidden。
     *
     * <p>等价于 {@code expand(catalog, db, table, bindings, Set.of(), List.of())}，
     * 供「只想知道这张表有没有 tag mask」的调用方使用（如快照统计打码的判据）。
     */
    public Expansion expand(String catalogName, String databaseName, String tableName,
                            List<TagMaskBindingEntity> bindings) {
        return expand(catalogName, databaseName, tableName, bindings, Set.of(), List.of());
    }

    /**
     * 展开一张表。
     *
     * @param bindings        该 catalog 的启用绑定；传 {@code null} 时现取（逐表调用者用）
     * @param explicitColumns 这张表的策略里已经有显式 mask 的列（显式优先）
     * @param hiddenColumns   这张表的 {@code hidden_json}（与 mask 互斥）
     */
    public Expansion expand(String catalogName, String databaseName, String tableName,
                            List<TagMaskBindingEntity> bindings,
                            Collection<String> explicitColumns, Collection<String> hiddenColumns) {
        List<TagMaskBindingEntity> effectiveBindings =
                bindings == null ? bindingsOf(catalogName) : bindings;
        // 快路径：绝大多数 catalog 没配绑定。这里直接返回，等于让 P3 对既有部署零成本
        // ——没有绑定就没有额外的标签查询，读路径的开销与 P3 之前完全一致。
        if (effectiveBindings.isEmpty()) {
            return Expansion.empty();
        }
        List<ColumnTagEntity> tags = tagRepository
                .findByCatalogNameAndDatabaseNameAndTableName(catalogName, databaseName, tableName);
        if (tags.isEmpty()) {
            return Expansion.empty();
        }
        Set<String> explicit = Set.copyOf(explicitColumns);
        Set<String> hidden = Set.copyOf(hiddenColumns);

        List<MaskConflict> conflicts = new ArrayList<>();
        Map<String, List<Candidate>> candidatesByColumn = new LinkedHashMap<>();
        for (ColumnTagEntity tag : tags) {
            if (!tag.isEnabled()) {
                continue;
            }
            // 表级标签（column_name 为空串）**显式排除**，这是设计 §8.1 那条 continue。
            // 不能靠「表级标签恰好没有 binding 命中」侥幸：万一有人给
            // sensitivity.level 配了绑定，空串列名会落进 masks[""]——要么静默不生效，
            // 要么（更糟）被某个实现当成「所有列」的前缀去匹配。
            if (ColumnRefs.isTableLevel(tag.getColumnName())) {
                continue;
            }
            for (TagMaskBindingEntity binding : effectiveBindings) {
                if (!matches(binding, tag)) {
                    continue;
                }
                if (explicit.contains(tag.getColumnName())) {
                    conflicts.add(new MaskConflict(tag.getColumnName(), tag.getTagKey(),
                            tag.getTagValue(), binding.getTagKey(),
                            MaskConflict.REASON_EXPLICIT_WINS,
                            "column already has an explicit mask in the table policy;"
                                    + " the binding " + label(binding) + " is not applied"));
                    continue;
                }
                if (hidden.contains(tag.getColumnName())) {
                    conflicts.add(new MaskConflict(tag.getColumnName(), tag.getTagKey(),
                            tag.getTagValue(), binding.getTagKey(),
                            MaskConflict.REASON_HIDDEN_CONFLICT,
                            "column is in hiddenColumns; a hidden column is never returned,"
                                    + " so masking it has no observable effect — choose one"));
                    continue;
                }
                candidatesByColumn.computeIfAbsent(tag.getColumnName(), key -> new ArrayList<>())
                        .add(new Candidate(tag, binding));
            }
        }

        Map<String, TagMask> masks = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candidate>> entry : candidatesByColumn.entrySet()) {
            List<Candidate> candidates = entry.getValue();
            candidates.sort(ORDER);
            Candidate winner = candidates.get(0);
            masks.put(entry.getKey(), winner.toTagMask());
            for (int i = 1; i < candidates.size(); i++) {
                Candidate loser = candidates.get(i);
                conflicts.add(new MaskConflict(entry.getKey(), loser.tag().getTagKey(),
                        loser.tag().getTagValue(), loser.binding().getTagKey(),
                        MaskConflict.REASON_OUTRANKED,
                        "another binding matched this column with a higher level/priority: "
                                + label(winner.binding()) + " wins over " + label(loser.binding())));
            }
        }
        return new Expansion(masks, List.copyOf(conflicts));
    }

    /**
     * 这张表有没有任何由标签展开出来的 mask（快路径：不构造 mask AST）。
     *
     * <p>用于「有没有 column 级保护」的布尔判据（快照统计打码、分区列举拒绝），
     * 那些地方不需要 mask 的内容，只需要一个是/否。
     */
    public boolean hasAnyMask(String catalogName, String databaseName, String tableName) {
        List<TagMaskBindingEntity> bindings = bindingsOf(catalogName);
        if (bindings.isEmpty()) {
            return false;
        }
        return !expand(catalogName, databaseName, tableName, bindings).isEmpty();
    }

    /**
     * 该 catalog 下所有会被标签绑定脱敏的表（{@code database.table}）。
     *
     * <p>catalog 级文件下载的收口判据要它（§7.2：一个 parquet 原文件就是全表明文）。
     * 只按「有标签且命中启用绑定」算，不需要真的展开每条 mask——判的是
     * 「这个表的文件值不值得保护」，不是「保护成什么样」。
     */
    public Set<String> tablesWithMask(String catalogName) {
        List<TagMaskBindingEntity> bindings = bindingsOf(catalogName);
        if (bindings.isEmpty()) {
            return Set.of();
        }
        Set<String> tables = new LinkedHashSet<>();
        for (ColumnTagEntity tag : tagRepository.findByCatalogName(catalogName)) {
            if (!tag.isEnabled() || ColumnRefs.isTableLevel(tag.getColumnName())) {
                continue;
            }
            if (matchesAny(bindings, tag)) {
                tables.add(tag.getDatabaseName() + "." + tag.getTableName());
            }
        }
        return tables;
    }

    // ------------------------------------------------------------------ 匹配

    /**
     * 一条绑定是否命中一条标签：键要匹配，值要匹配（空 = 任意值）。
     */
    public static boolean matches(TagMaskBindingEntity binding, ColumnTagEntity tag) {
        if (binding == null || tag == null) {
            return false;
        }
        if (!keyMatches(binding.getTagKey(), tag.getTagKey())) {
            return false;
        }
        String wanted = binding.getTagValue();
        // 空串与 null 都表示「该键任意值」：服务层写入时归一成空串，
        // 但 P0 建的存量行里可能有 null（那时还没有归一规则），读侧两种都认。
        return wanted == null || wanted.isEmpty() || wanted.equals(tag.getTagValue());
    }

    /**
     * 标签键匹配：精确相等，或绑定键以 {@code .*} 结尾时按段前缀匹配。
     *
     * <p>前缀保留末尾的点（{@code PII.*} 的前缀是 {@code PII.}），因此
     * {@code PII.*} 不会匹配 {@code PII}（少了层级）也不会匹配 {@code PIIX.FOO}
     * （少了个边界）——直接用 {@code startsWith("PII")} 这两种都会误命中。
     */
    public static boolean keyMatches(String bindingKey, String tagKey) {
        if (bindingKey == null || tagKey == null) {
            return false;
        }
        if (bindingKey.equals(tagKey)) {
            return true;
        }
        if (bindingKey.endsWith(WILDCARD_KEY_SUFFIX)) {
            String prefix = bindingKey.substring(0, bindingKey.length() - 1);
            return tagKey.length() > prefix.length() && tagKey.startsWith(prefix);
        }
        return false;
    }

    private static boolean matchesAny(List<TagMaskBindingEntity> bindings, ColumnTagEntity tag) {
        for (TagMaskBindingEntity binding : bindings) {
            if (matches(binding, tag)) {
                return true;
            }
        }
        return false;
    }

    /** 冲突报告里的「哪条绑定」：{@code PII.*=L4}（值通配时不带等号）。 */
    private static String label(TagMaskBindingEntity binding) {
        String value = binding.getTagValue();
        return value == null || value.isEmpty()
                ? binding.getTagKey()
                : binding.getTagKey() + "=" + value;
    }

    // ------------------------------------------------------------------ 选档

    /**
     * 同列多命中的选档顺序：{@code level desc, priority desc, 精确优先, tag_key asc}。
     *
     * <p>{@code level} 取<b>标签</b>的分级（不是绑定的）：同一个类型被标成 L3 还是 L4
     * 是数据本身的结论，绑定只是「这个级别怎么脱敏」的映射。手工标签没有分级，
     * 按 0 参与排序——它会被任何带分级的命中压过，这正是「敏感标签优先于业务标签」。
     */
    private static final Comparator<Candidate> ORDER =
            Comparator.<Candidate>comparingInt(Candidate::levelRank).reversed()
                    .thenComparing(Comparator.<Candidate>comparingInt(
                            candidate -> candidate.binding().getPriority()).reversed())
                    // 精确匹配排在前（0 < 1）：级别与优先级都相同时，具体规则赢过泛化规则
                    .thenComparingInt(candidate -> candidate.exact() ? 0 : 1)
                    .thenComparing(candidate -> candidate.binding().getTagKey());

    private record Candidate(ColumnTagEntity tag, TagMaskBindingEntity binding) {

        int levelRank() {
            SensitivityLevel level = tag.level();
            return level == null ? 0 : level.rank();
        }

        boolean exact() {
            return binding.getTagKey().equals(tag.getTagKey());
        }

        TagMask toTagMask() {
            return new TagMask(tag.getColumnName(), tag.getTagKey(), tag.getTagValue(),
                    tag.getTypeCode(), tag.level(), binding.getPriority(),
                    binding.getTagKey().endsWith(WILDCARD_KEY_SUFFIX),
                    maskOf(binding), binding.getMaskSql());
        }

        /**
         * 绑定里的 mask AST。
         *
         * <p><b>读不出来就抛，不跳过。</b>跳过等于「这一列本该脱敏，但这条绑定坏了，
         * 所以下发了明文」——而它不会报错，只会在某次排查时被发现。抛出会让整批
         * resolve 5xx，引擎侧 fail-closed 拒读（§5.4 的失败模型）。写入路径已经用
         * {@code PolicyExpressions.parseMaskTemplate} 校验过，坏数据只可能来自绕过服务层的写。
         */
        private static Map<String, Object> maskOf(TagMaskBindingEntity binding) {
            String json = binding.getMaskJson();
            if (json == null || json.isBlank()) {
                throw new IllegalStateException("tag mask binding " + binding.getId()
                        + " has no mask_json; refusing to resolve a policy that would leak"
                        + " the column in clear text");
            }
            try {
                return Json.read(json, Map.class);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("tag mask binding " + binding.getId()
                        + " has an unreadable mask_json: " + exception.getMessage(), exception);
            }
        }
    }
}
