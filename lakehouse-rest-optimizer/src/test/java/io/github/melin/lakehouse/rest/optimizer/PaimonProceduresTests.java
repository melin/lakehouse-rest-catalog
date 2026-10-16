package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.optimizer.PaimonProcedures.Step;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code CALL} 语句的生成。
 *
 * <p>这类断言看起来像「把实现抄一遍」，但它挡住的是一类**很难在运行前发现**的错误：
 * 参数名拼错、参数类型写成字符串、动作顺序调换——三者都不会让本模块编译失败，
 * 只会在集群上以「procedure 参数不匹配」或「多删了文件」的形式出现。
 * 参数名与类型是照着 Paimon 2.0.0 的 class 文件反解出来的（见 {@link PaimonProcedures}
 * 的类注释），因此这里的断言同时也是「我们对着哪个版本写的」这份记录。
 */
class PaimonProceduresTests {

    private static final String CATALOG = "paimon";
    private static final String TABLE = "db.t1";

    @Test
    void compactBinpackOnlySendsThresholds() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"), Map.of("compact", Map.of(
                "strategy", "binpack",
                "min-input-files", "5",
                "target-file-size-mb", "128")));

        assertEquals(1, steps.size());
        assertEquals("compact", steps.get(0).action());
        assertEquals("CALL paimon.sys.compact(table => 'db.t1',"
                        + " options => 'compaction.min.file-num=5,target-file-size=128 mb')",
                steps.get(0).statement());
        // binpack 不传 compact_strategy：让 Paimon 按表类型自己选，
        // 替它决定会在主键表上选错（主键表走 LSM compaction，不是 full）。
        assertFalse(steps.get(0).statement().contains("compact_strategy"));
    }

    @Test
    void compactSortTranslatesStrategyAndColumns() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"), Map.of("compact", Map.of(
                "strategy", "sort",
                "sort-columns", "dt,id",
                "min-input-files", "8",
                "target-file-size-mb", "256")));

        String sql = steps.get(0).statement();
        // compact_strategy 只接受 full/minor（排序意图由 order_strategy 表达），
        // 而且 order_strategy 的取值是 CoreOptions.OrderType 的枚举名（大写）。
        assertTrue(sql.contains("compact_strategy => 'full'"), sql);
        assertTrue(sql.contains("order_strategy => 'ORDER'"), sql);
        assertTrue(sql.contains("order_by => 'dt,id'"), sql);
        assertTrue(sql.contains("options => 'compaction.min.file-num=8,target-file-size=256 mb'"), sql);
    }

    @Test
    void sortStrategyWithoutColumnsFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"),
                        Map.of("compact", Map.of("strategy", "sort", "min-input-files", "5",
                                "target-file-size-mb", "128"))));
        assertTrue(error.getMessage().contains("sort-columns"), error.getMessage());
    }

    @Test
    void expireUsesIntegerLiteralsForCounts() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("expire"), Map.of("expire", Map.of(
                "retention-hours", "24",
                "retain-min", "10")));

        // retain_min 的形参是整数，带引号在 ANSI 模式下会直接抛转换错误
        assertEquals("CALL paimon.sys.expire_snapshots(table => 'db.t1',"
                + " older_than => '24 h', retain_min => 10)", steps.get(0).statement());
        // retain_max 刻意不传：它会额外限制「最多留几个快照」，替用户猜一个上限
        // 会在快照变多时删掉仍在窗口内的快照。
        assertFalse(steps.get(0).statement().contains("retain_max"));
    }

    @Test
    void orphanDryRunIsBooleanLiteral() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("orphan"), Map.of("orphan", Map.of(
                "older-than-hours", "48",
                "dry-run", "false")));

        assertEquals("CALL paimon.sys.remove_orphan_files(table => 'db.t1',"
                + " older_than => '48 h', dry_run => false)", steps.get(0).statement());
    }

    @Test
    void actionsRunInFixedOrder() {
        Map<String, Map<String, String>> options = Map.of(
                "compact", Map.of("min-input-files", "5", "target-file-size-mb", "128"),
                "expire", Map.of("retention-hours", "24", "retain-min", "10"),
                "orphan", Map.of("older-than-hours", "24", "dry-run", "true"));

        // 传入顺序无关，输出顺序固定为 compact → expire → orphan：
        // compaction 产生新快照后 expire 才能判过期，orphan 最后跑才安全。
        // 注意步骤上的 action 是「动作的 wire 名」（与服务端契约同一套词），
        // procedure 名（expire_snapshots / remove_orphan_files）只出现在语句里。
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("orphan", "expire", "compact"), options);

        assertEquals(List.of("compact", "expire", "orphan"), steps.stream().map(Step::action).toList());
        assertEquals("CALL paimon.sys.expire_snapshots(table => 'db.t1', older_than => '24 h', retain_min => 10)",
                steps.get(1).statement());
        assertEquals("CALL paimon.sys.remove_orphan_files(table => 'db.t1',"
                + " older_than => '24 h', dry_run => true)", steps.get(2).statement());
    }

    @Test
    void missingActionIsNotEmitted() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"),
                Map.of("compact", Map.of("min-input-files", "5", "target-file-size-mb", "128")));
        assertEquals(1, steps.size());
        assertEquals(List.of(), PaimonProcedures.steps(CATALOG, TABLE, List.of(), Map.of()));
    }

    @Test
    void unknownActionFailsInsteadOfBeingIgnored() {
        // 服务端已经裁剪过动作集合，这里是第二道闸：拼错的动作名不该被静默跳过
        // （跳过等于「以为跑过了」）。
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.normalizeActions(List.of("compact-all")));
        assertEquals(List.of("compact"), PaimonProcedures.normalizeActions(List.of("COMPACT", "compact")));
    }

    @Test
    void tableNameMustBelongToTheBatchCatalog() {
        assertEquals("db.t1", PaimonProcedures.withinCatalog("paimon", "paimon.db.t1"));

        // 前缀对不上意味着任务拿到的清单与它连的 catalog 不是一回事，
        // 继续执行会去优化另一张同名表——必须失败而不是「去掉第一段试试」。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.withinCatalog("paimon", "other.db.t1"));
        assertTrue(error.getMessage().contains("does not belong"), error.getMessage());

        // 只有库名、没有表名
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.withinCatalog("paimon", "paimon.db"));
    }

    @Test
    void singleQuotesInValuesAreEscaped() {
        // 值全部来自用户配置，按 SQL 惯例用两个单引号转义，而不是把字符串直接拼进去
        String sql = PaimonProcedures.statement(CATALOG, "compact", TABLE, Map.of(
                "strategy", "sort", "sort-columns", "a'b", "min-input-files", "5",
                "target-file-size-mb", "128"));
        assertTrue(sql.contains("order_by => 'a''b'"), sql);

        // 反例的护栏：正常取值里不该出现转义后的引号
        String normal = PaimonProcedures.statement(CATALOG, "expire", TABLE,
                Map.of("retention-hours", "24", "retain-min", "10"));
        assertFalse(normal.contains("''"), normal);
    }

    @Test
    void illegalTableNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PaimonProcedures.steps(CATALOG, "db.t; DROP", List.of() , Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PaimonProcedures.steps(CATALOG, "db.t 1", List.of(), Map.of()));
    }
}
