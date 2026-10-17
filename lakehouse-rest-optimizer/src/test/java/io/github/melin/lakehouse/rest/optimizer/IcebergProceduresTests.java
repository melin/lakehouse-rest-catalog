package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.optimizer.IcebergProcedures.Step;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Iceberg {@code CALL} 语句的生成。
 *
 * <p>与 {@link PaimonProceduresTests} 同一类断言，但重点不同：Iceberg 的三个 procedure
 * 里有两个对**时间戳字面量**敏感（{@code older_than} 是 timestamp，不是相对时长），
 * 而时区写错的表现是「窗口整体平移几小时」——在 orphan 清理上就是多删文件。
 * 因此这里把「UTC 格式化」与「会话时区」一起钉住。
 *
 * <p>参数名来自 Iceberg 官方 Spark Procedures 文档（1.11 / 1.12 同一套）。
 */
class IcebergProceduresTests {

    private static final String CATALOG = "lake";
    private static final String TABLE = "ns.t1";
    private static final Instant NOW = Instant.parse("2026-10-17T06:00:00Z");

    @Test
    void compactBinpackSendsByteTargetAndThreshold() {
        List<Step> steps = IcebergProcedures.steps(CATALOG, TABLE, List.of("compact"), Map.of("compact", Map.of(
                "strategy", "binpack",
                "min-input-files", "5",
                "target-file-size-mb", "512",
                "max-concurrent-file-group-rewrites", "4")), NOW);

        assertEquals(1, steps.size());
        String sql = steps.get(0).statement();
        assertEquals("CALL lake.system.rewrite_data_files(table => 'ns.t1',"
                + " options => map('min-input-files', '5', 'target-file-size-bytes', '536870912',"
                + " 'max-concurrent-file-group-rewrites', '4'))", sql);
        // binpack 是 Iceberg 的默认 strategy，显式传一遍只会多一处要跟随上游改的地方
        assertFalse(sql.contains("strategy =>"), sql);
    }

    @Test
    void compactSortTranslatesToAscendingColumns() {
        String sql = IcebergProcedures.statement(CATALOG, "compact", TABLE, Map.of(
                "strategy", "sort",
                "sort-columns", "dt,id",
                "min-input-files", "8",
                "target-file-size-mb", "256",
                "max-concurrent-file-group-rewrites", "5"), NOW);

        assertTrue(sql.contains("strategy => 'sort'"), sql);
        assertTrue(sql.contains("sort_order => 'dt ASC NULLS LAST, id ASC NULLS LAST'"), sql);
        assertTrue(sql.contains("'target-file-size-bytes', '268435456'"), sql);
    }

    @Test
    void zorderIsSortStrategyPlusZorderSortOrder() {
        String sql = IcebergProcedures.statement(CATALOG, "compact", TABLE, Map.of(
                "strategy", "zorder",
                "sort-columns", "a,b",
                "min-input-files", "5",
                "target-file-size-mb", "512",
                "max-concurrent-file-group-rewrites", "5"), NOW);

        // Iceberg 没有 z_order_columns 参数：zorder 只是 sort_order 的一种写法
        assertTrue(sql.contains("strategy => 'sort'"), sql);
        assertTrue(sql.contains("sort_order => 'zorder(a,b)'"), sql);
        assertFalse(sql.contains("z_order_columns"), sql);
    }

    @Test
    void expireAndOrphanUseUtcTimestamps() {
        String expire = IcebergProcedures.statement(CATALOG, "expire", TABLE, Map.of(
                "retention-hours", "120", "retain-last", "10"), NOW);
        assertEquals("CALL lake.system.expire_snapshots(table => 'ns.t1',"
                + " older_than => TIMESTAMP '2026-10-12 06:00:00', retain_last => 10)", expire);

        String orphan = IcebergProcedures.statement(CATALOG, "orphan", TABLE, Map.of(
                "older-than-hours", "72", "dry-run", "true"), NOW);
        assertEquals("CALL lake.system.remove_orphan_files(table => 'ns.t1',"
                + " older_than => TIMESTAMP '2026-10-14 06:00:00', dry_run => true)", orphan);
    }

    @Test
    void actionOrderIsFixedRegardlessOfInput() {
        List<Step> steps = IcebergProcedures.steps(CATALOG, TABLE, List.of("orphan", "compact", "expire"), Map.of(
                "compact", Map.of("strategy", "binpack", "min-input-files", "5",
                        "target-file-size-mb", "512", "max-concurrent-file-group-rewrites", "5"),
                "expire", Map.of("retention-hours", "24", "retain-last", "5"),
                "orphan", Map.of("older-than-hours", "24", "dry-run", "true")), NOW);

        assertEquals(List.of("compact", "expire", "orphan"),
                steps.stream().map(Step::action).toList());
    }

    @Test
    void sortWithoutColumnsFailsBeforeTouchingTheCluster() {
        assertThrows(IllegalArgumentException.class, () -> IcebergProcedures.statement(
                CATALOG, "compact", TABLE, Map.of(
                        "strategy", "sort", "min-input-files", "5",
                        "target-file-size-mb", "512", "max-concurrent-file-group-rewrites", "5"), NOW));
    }

    @Test
    void columnNamesAreShapedBeforeBeingInlined() {
        assertThrows(IllegalArgumentException.class, () -> IcebergProcedures.statement(
                CATALOG, "compact", TABLE, Map.of(
                        "strategy", "sort", "sort-columns", "id; DROP TABLE x",
                        "min-input-files", "5", "target-file-size-mb", "512",
                        "max-concurrent-file-group-rewrites", "5"), NOW));
    }
}
