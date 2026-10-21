package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.Fallback;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PartitionSelectionRequest;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PartitionValue;
import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.preview.PreviewColumn.Kind;
import io.github.melin.lakehouse.rest.service.PartitionPlanner;
import io.github.melin.lakehouse.rest.service.PartitionPlanner.Decision;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 「默认预览最新分区」这套判断的单元测试——**不起服务端、不建表**。
 *
 * <p>这是本功能里最容易实现错的一块（设计文档 §7 与 §14 的验收清单第 3–7 条），
 * 而它的输入只有三样：分区字段、切片、请求。因此把逻辑抽成 {@link PartitionPlanner}、
 * 用纯单测钉住，比"起一个 Paimon 表再看界面"便宜得多，也能覆盖那些**造不出来**的场景
 * （分区全空、最新分区没有文件、哈希分区）。
 *
 * <p>每个用例的方法名就是那条规则，失败时不必回头看断言。
 */
class PartitionPlannerTests {

    private static final List<String> DT = List.of("dt");

    private static final List<PreviewColumn> COLUMNS = List.of(
            column("dt", "STRING", Kind.TEXT),
            column("hour", "INT", Kind.NUMBER),
            column("region", "STRING", Kind.TEXT),
            column("id", "BIGINT", Kind.NUMBER),
            column("amount", "DECIMAL(10,2)", Kind.NUMBER));

    private static final int MAX_FILES = 8;

    private static final int MAX_DEPTH = 3;

    // ------------------------------------------------------------------ 基本：锁最新

    /**
     * 单级分区：默认锁最新那一个，回显带「最新分区」，depth=1。
     *
     * <p>这是验收清单第 3 条：不指定分区时 {@code resolved} 必须指向最新分区。
     */
    @Test
    void autoLocksTheNewestIdentityPartition() {
        Decision decision = decide(DT, null, null, List.of(
                slice("dt=2026-10-19", Map.of("dt", "2026-10-19"), 4),
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3)));

        assertTrue(decision.auto());
        assertEquals(1, decision.depth());
        assertEquals(1, decision.selection().size());
        assertEquals("dt=2026-10-21", decision.selection().get(0).key());
        assertEquals(List.of(new PartitionValue("dt", "identity", "2026-10-21")), decision.resolved());
        assertEquals("dt = 2026-10-21（最新分区）", decision.label());
        assertEquals(3, decision.partitionsTotal());
        assertEquals(1, decision.partitionsMatched());
        assertNull(decision.fallback());
        assertTrue(decision.warnings().isEmpty());
    }

    /**
     * {@code mode=all} 一个都不锁：用户明确说"看全部"，自动条件必须让位。
     */
    @Test
    void allModeLocksNothing() {
        Decision decision = decide(DT, "all", null, List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3)));

        assertFalse(decision.auto());
        assertEquals(0, decision.depth());
        assertEquals(2, decision.selection().size());
        assertTrue(decision.resolved().isEmpty());
        assertNull(decision.label());
    }

    // ------------------------------------------------------------------ 级联

    /**
     * 多级分区：一级还超预算就锁第二级，停在"文件数够了"的那一层。
     *
     * <p>验收清单第 4 条。这里锁满 {@code dt} 的 3 个小时才降到 3 个文件，
     * 所以 depth=2：固定锁一级会跨小时（每个小时各 3 个文件 = 9 个，超预算），
     * 固定锁满会在数据量小的表上莫名其妙只看一个 region。
     */
    @Test
    void cascadeStopsAtTheDepthWhereTheBudgetFits() {
        List<String> fields = List.of("dt", "hour");
        List<PartitionSlice> slices = new ArrayList<>();
        for (String hour : List.of("08", "09", "10")) {
            slices.add(slice("dt=2026-10-21/hour=" + hour,
                    Map.of("dt", "2026-10-21", "hour", Integer.parseInt(hour)), 3));
        }
        slices.add(slice("dt=2026-10-20/hour=10", Map.of("dt", "2026-10-20", "hour", 10), 3));

        Decision decision = decide(fields, null, null, slices);

        assertTrue(decision.auto());
        assertEquals(2, decision.depth(), "锁到 hour 才能把 9 个文件降到 3 个");
        assertEquals(List.of(
                new PartitionValue("dt", "identity", "2026-10-21"),
                new PartitionValue("hour", "identity", "10")), decision.resolved());
        assertEquals("dt = 2026-10-21 / hour = 10（自动收敛）", decision.label());
        assertEquals(1, decision.selection().size());
    }

    /**
     * 只有一级时不会"多锁一级"：{@code candidates} 用完就停，depth 不会超过字段数。
     */
    @Test
    void cascadeNeverExceedsTheNumberOfPartitionFields() {
        List<PartitionSlice> slices = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            String dt = "2026-10-" + (index + 1);
            slices.add(slice("dt=" + dt, Map.of("dt", dt), 5));
        }

        Decision decision = decide(DT, null, null, slices);

        assertEquals(1, decision.depth());
        // 单个分区仍有 5 个文件，没超预算 → 不该有"读不完"的提示
        assertTrue(decision.warnings().isEmpty());
    }

    /**
     * 单个分区就超预算：锁到最深一级仍然读不完，必须留下提示——否则用户以为看到了全量。
     */
    @Test
    void aSinglePartitionOverBudgetIsReportedInWarnings() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 20));

        Decision decision = decide(DT, null, null, slices);

        assertEquals(1, decision.selection().size());
        assertEquals(20, decision.selection().get(0).files());
        assertEquals(1, decision.warnings().size());
        assertTrue(decision.warnings().get(0).contains("20"), decision.warnings().get(0));
    }

    /**
     * {@code cascade=false} 只锁第一级：这是"我知道我在看什么，别替我下钻"的开关。
     */
    @Test
    void cascadeCanBeTurnedOff() {
        List<String> fields = List.of("dt", "hour");
        List<PartitionSlice> slices = new ArrayList<>();
        for (String hour : List.of("08", "09", "10")) {
            slices.add(slice("dt=2026-10-21/hour=" + hour,
                    Map.of("dt", "2026-10-21", "hour", Integer.parseInt(hour)), 3));
        }

        Decision decision = decide(fields, null, Boolean.FALSE, slices);

        assertEquals(1, decision.depth());
        assertEquals(3, decision.selection().size());
        assertEquals(List.of(new PartitionValue("dt", "identity", "2026-10-21")), decision.resolved());
    }

    // ------------------------------------------------------------------ 谁让位

    /**
     * 过滤条件里已经有 {@code dt = …}：自动条件**不再**锁 {@code dt}。
     *
     * <p>验收清单第 5 条。不让位就会 AND 出静默的 0 行——用户会以为"这一天没数据"，
     * 而真实原因是自动条件把它筛没了。
     */
    @Test
    void aFilterOnAPartitionFieldWinsOverTheAutomaticCondition() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        Decision decision = decide(DT, null, null, slices,
                List.of(new ColumnFilter("dt", "eq", "2026-10-20")));

        assertFalse(decision.auto(), "用户已经指定了分区，服务端不该再帮一次");
        assertEquals(1, decision.selection().size());
        assertEquals("dt=2026-10-20", decision.selection().get(0).key());
        assertEquals(List.of(new PartitionValue("dt", "identity", "2026-10-20")), decision.resolved());
        // 不标「最新分区」（不是服务端挑的），但要标「随过滤条件」：分区控件若显示"全部"，
        // 用户会以为读的是全表，而实际只读了 1 个分区
        assertEquals("dt = 2026-10-20（随过滤条件）", decision.label());
    }

    /**
     * 过滤条件命中的分区**不存在**时不当成"分区锁定"。
     *
     * <p>这是一条普通行过滤，允许筛出 0 行。若拿它去裁文件，就会走进
     * "这个分区不存在"的 400——把"值打错了"说成"分区不存在"，报错指向了错的地方。
     */
    @Test
    void aFilterValueThatIsNotAPartitionFallsBackToRowFiltering() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        Decision decision = decide(DT, null, null, slices,
                List.of(new ColumnFilter("dt", "eq", "1999-01-01")));

        // 没锁成 → 自动条件接手，读最新分区；命中的 0 行留给逐行判定
        assertTrue(decision.auto());
        assertEquals("dt=2026-10-21", decision.selection().get(0).key());
    }

    /**
     * 多级分区里只有一个字段被用户锁住时，另一个字段仍然自动锁——两次判断互不干扰。
     */
    @Test
    void theAutomaticConditionOnlyLocksTheFieldsTheUserDidNot() {
        List<String> fields = List.of("dt", "hour");
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20/hour=08", Map.of("dt", "2026-10-20", "hour", 8), 4),
                slice("dt=2026-10-20/hour=09", Map.of("dt", "2026-10-20", "hour", 9), 4));

        Decision decision = decide(fields, null, null, slices,
                List.of(new ColumnFilter("dt", "eq", "2026-10-20")));

        assertTrue(decision.auto());
        assertEquals(1, decision.depth());
        assertEquals(List.of(
                new PartitionValue("dt", "identity", "2026-10-20"),
                new PartitionValue("hour", "identity", "9")), decision.resolved());
    }

    /**
     * 非等值条件（{@code dt >= …}）不算"锁定分区"：它可能横跨很多分区。
     */
    @Test
    void aRangeFilterOnAPartitionFieldDoesNotPinIt() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        Decision decision = decide(DT, null, null, slices,
                List.of(new ColumnFilter("dt", "ge", "2026-10-01")));

        assertTrue(decision.auto());
        assertEquals("dt=2026-10-21", decision.selection().get(0).key());
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 最新分区里一个数据文件都没有（Iceberg 可能：分区被删空）：退到有文件的次新一个。
     *
     * <p>不退的话默认视图永远一片空白，而用户不知道为什么——这是"看起来表坏了"的最短路径。
     */
    @Test
    void aNewestPartitionWithoutFilesFallsBackToThePreviousOne() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3),
                slice("dt=2026-10-22", Map.of("dt", "2026-10-22"), 0));

        Decision decision = decide(DT, null, null, slices);

        assertEquals("dt=2026-10-21", decision.selection().get(0).key());
        Fallback fallback = decision.fallback();
        assertNotNull(fallback);
        assertEquals("NO_FILES", fallback.reason());
        assertTrue(fallback.detail().contains("2026-10-22"), fallback.detail());
        assertTrue(fallback.detail().contains("2026-10-21"), fallback.detail());
    }

    /**
     * 哈希分区不编"最新"：{@code bucket} 之间没有先后，一个都不锁，并说明原因。
     *
     * <p>验收清单第 7 条。这里最容易犯的错是按桶号数字取最大——那会得到"最新桶 7"，
     * 一个不存在于业务语义里的东西。
     */
    @Test
    void aHashPartitionIsNotOrderable() {
        List<String> fields = List.of("id");
        List<PartitionSlice> slices = List.of(
                slice("id=0", Map.of("id", 0), 4),
                slice("id=7", Map.of("id", 7), 4));
        Map<String, String> transforms = new LinkedHashMap<>();
        transforms.put("id", "bucket(4, id)");

        Decision decision = PartitionPlanner.decide(fields, partitionedOn("id", Kind.NUMBER), transforms,
                slices, null, null, MAX_FILES, MAX_DEPTH);

        assertFalse(decision.auto());
        assertEquals(0, decision.depth());
        assertEquals(2, decision.selection().size(), "不锁就等于全读");
        assertNull(decision.label());
        assertNotNull(decision.fallback());
        assertEquals("NOT_ORDERABLE", decision.fallback().reason());
    }

    /**
     * 分区值全是 null（只有默认分区）：读出它，并说清"这是默认（空值）分区"。
     */
    @Test
    void anAllNullPartitionUsesTheDefaultPartitionWording() {
        List<PartitionSlice> slices = List.of(slice("dt=<null>", new LinkedHashMap<>(), 5));

        Decision decision = decide(DT, null, null, slices);

        assertFalse(decision.auto());
        assertNotNull(decision.fallback());
        assertEquals("DEFAULT_PARTITION", decision.fallback().reason());
        assertEquals(1, decision.selection().size());
    }

    // ------------------------------------------------------------------ 显式与错误

    /**
     * 显式点名一个不存在的分区：400 并给出最新分区提示。
     *
     * <p>不做静默 0 行——那会被读成"这个分区没数据"，而真实原因是它不在这个快照里
     * （设计文档 §5.3 的同一条原则）。
     */
    @Test
    void anExplicitPartitionThatDoesNotExistIsA400WithANewestHint() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        ApiException exception = assertThrows(ApiException.class,
                () -> decide(DT, "explicit", null, slices,
                        List.of(new PartitionValue("dt", "identity", "2024-02-30")), null));

        assertEquals(400, exception.getStatus());
        assertTrue(exception.getMessage().contains("2026-10-21"), exception.getMessage());
    }

    /**
     * 显式分区原样回显：用户给的就是用户给的那个值（不替换成切片里的规范化文本）。
     */
    @Test
    void anExplicitPartitionIsEchoedBack() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        Decision decision = decide(DT, "explicit", null, slices,
                List.of(new PartitionValue("dt", "identity", "2026-10-20")), null);

        assertFalse(decision.auto());
        assertEquals(List.of(new PartitionValue("dt", "identity", "2026-10-20")), decision.resolved());
        assertEquals("dt=2026-10-20", decision.selection().get(0).key());
    }

    /**
     * {@code mode=explicit} 不给值时是 400：这条路径没有"随机挑一个"的合理默认。
     */
    @Test
    void explicitModeWithoutValuesIsA400() {
        ApiException exception = assertThrows(ApiException.class,
                () -> decide(DT, "explicit", null, List.of(
                        slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3))));

        assertEquals(400, exception.getStatus());
    }

    /**
     * 点名的字段不是分区字段：400，消息里列出真正的分区字段。
     */
    @Test
    void anExplicitValueOnANonPartitionFieldIsA400() {
        ApiException exception = assertThrows(ApiException.class,
                () -> decide(DT, "explicit", null, List.of(
                        slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3)),
                        List.of(new PartitionValue("region", "identity", "cn-east")), null));

        assertEquals(400, exception.getStatus());
        assertTrue(exception.getMessage().contains("dt"), exception.getMessage());
    }

    /**
     * 未知的 mode 是 400，而不是悄悄按 auto 处理。
     */
    @Test
    void anUnknownModeIsA400() {
        ApiException exception = assertThrows(ApiException.class,
                () -> decide(DT, "newest", null, List.of(
                        slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3))));

        assertEquals(400, exception.getStatus());
    }

    // ------------------------------------------------------------------ 续读

    /**
     * 续读：按游标里固化的取值还原切片，**不重新规划**。
     *
     * <p>期间新提交了一个更新的分区也不该改变第二页读哪一段——{@code r} 是旧分区里
     * 已消费的行数，换到新分区上 {@code skip(r)} 会跳错位置，行错位且不报错。
     */
    @Test
    void resolveRebuildsTheSelectionFromTheCursorValues() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-20", Map.of("dt", "2026-10-20"), 4),
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3),
                // 期间新提交的分区：不该被"续读"看见
                slice("dt=2026-10-22", Map.of("dt", "2026-10-22"), 1));

        List<PartitionSlice> selection = PartitionPlanner.resolve(slices,
                List.of(new PartitionValue("dt", "identity", "2026-10-21")));

        assertEquals(1, selection.size());
        assertEquals("dt=2026-10-21", selection.get(0).key());
    }

    /** 游标里的分区已经不存在了（被清理）：返回空，由调用方决定 409。 */
    @Test
    void resolveReturnsNothingWhenThePartitionIsGone() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        assertTrue(PartitionPlanner.resolve(slices,
                List.of(new PartitionValue("dt", "identity", "2026-10-19"))).isEmpty());
    }

    /** 没有分区条件时续读等于不裁：两者都返回空列表，语义一致。 */
    @Test
    void resolveOfAnEmptySelectionIsEmpty() {
        List<PartitionSlice> slices = List.of(
                slice("dt=2026-10-21", Map.of("dt", "2026-10-21"), 3));

        assertTrue(PartitionPlanner.resolve(slices, List.of()).isEmpty());
        assertTrue(PartitionPlanner.resolve(List.of(), List.of(
                new PartitionValue("dt", "identity", "2026-10-21"))).isEmpty());
    }

    // ------------------------------------------------------------------ 非分区表

    /** 非分区表：没有分区可锁，响应里的分区信息全是空，不该报错。 */
    @Test
    void aNonPartitionedTableHasNoPartitionSelection() {
        Decision decision = PartitionPlanner.decide(List.of(), COLUMNS, Map.of(), List.of(),
                null, null, MAX_FILES, MAX_DEPTH);

        assertEquals(0, decision.partitionsTotal());
        assertEquals(0, decision.partitionsMatched());
        assertTrue(decision.selection().isEmpty());
        assertTrue(decision.resolved().isEmpty());
        assertNull(decision.label());
        assertNull(decision.fallback());
    }

    // ------------------------------------------------------------------ 夹具

    private static Decision decide(List<String> fields, String mode, Boolean cascade,
                                   List<PartitionSlice> slices) {
        return decide(fields, mode, cascade, slices, null, List.of());
    }

    private static Decision decide(List<String> fields, String mode, Boolean cascade,
                                   List<PartitionSlice> slices, List<ColumnFilter> filters) {
        return decide(fields, mode, cascade, slices, null, filters);
    }

    private static Decision decide(List<String> fields, String mode, Boolean cascade,
                                   List<PartitionSlice> slices,
                                   List<PartitionValue> values, List<ColumnFilter> filters) {
        PartitionSelectionRequest request = mode == null && cascade == null && values == null
                ? null
                : new PartitionSelectionRequest(mode, values, cascade);
        return PartitionPlanner.decide(fields, columnsFor(fields), Map.of(), slices, request,
                filters, MAX_FILES, MAX_DEPTH);
    }

    private static List<PreviewColumn> columnsFor(List<String> fields) {
        List<PreviewColumn> columns = new ArrayList<>();
        for (String field : fields) {
            columns.add(column(field, "STRING", Kind.TEXT));
        }
        columns.addAll(COLUMNS);
        return columns;
    }

    private static List<PreviewColumn> partitionedOn(String field, Kind kind) {
        return List.of(column(field, "BIGINT", kind));
    }

    private static PreviewColumn column(String name, String type, Kind kind) {
        return new PreviewColumn(name, type, kind, true, false);
    }

    private static PartitionSlice slice(String key, Map<String, Object> values, long files) {
        return new PartitionSlice(key, new LinkedHashMap<>(values), files, files * 1024L, -1, 0);
    }
}
