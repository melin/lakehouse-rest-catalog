package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.paimon.preview.PaimonPreviewTableFactory;
import io.github.melin.lakehouse.rest.service.DataPolicyService;
import io.github.melin.lakehouse.rest.service.PreviewCursorCodec;
import io.github.melin.lakehouse.rest.service.StorageFileIoFactory;
import io.github.melin.lakehouse.rest.service.TablePreviewService;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.PolicyExpressionEvaluator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * 预览 × 数据策略的**真实读路径**（设计 §6 P1）：本地写一张带数据的 parquet 表，
 * 把策略（折叠后的行过滤 AST + mask 集）直接交给 {@link TablePreviewService}，
 * 断言控制台拿到的就是「行过滤后 + 脱敏后」的数据——不弹桩：mask 的字符映射、
 * 行过滤读原值、补读列的裁切，都只在真读时才看得到。
 *
 * <p>豁免与「没策略」在入口处已经折成 {@code policy == null}（见
 * {@code DataPolicyService#previewPolicyOf}），所以对照组就是传 {@code null}：
 * 两条「放行」在执行上没有区别，这正是豁免语义要的性质。
 */
class PolicyPreviewTests {

    // ------------------------------------------------------------------ 用例数据

    /** id / name / phone / region / day。region=CN 的两行可见（行过滤），phone / day / name 有 mask。 */
    private static final LocalDate DAY_1 = LocalDate.of(2026, 10, 24);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 9, 1);
    private static final LocalDate DAY_3 = LocalDate.of(2025, 1, 15);

    private static DataPolicyService.PreviewPolicy policy() {
        // 行过滤：region = 'CN'（外层已包 coalesce——与 PolicyExpressions.parseRowFilter 的产物同形）
        Map<String, Object> rowFilter = coalesceFalse(op("eq", col("region"), lit("CN")));
        Map<String, Map<String, Object>> masks = new LinkedHashMap<>();
        masks.put("phone", Map.of("fn", "mask_alphanum"));
        masks.put("day", Map.of("fn", "truncate_to_year"));
        // 单元格级语义（§5.3.5）：name 的 mask 引用 region，读到的是原值
        masks.put("name", Map.of("expr", Map.of(
                "fn", "case_when",
                "branches", List.of(Map.of(
                        "when", op("eq", col("region"), lit("US")),
                        "then", col("name"))),
                "else", lit("HIDDEN"))));
        return new DataPolicyService.PreviewPolicy("hash", 1, rowFilter, Map.copyOf(masks),
                List.of());
    }

    // ------------------------------------------------------------------ 集成：真实读路径

    @Test
    void previewAppliesRowFilterThenMasksOnRealRows(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        PreviewTarget target = target(dir);
        PreviewDtos.PreviewResponse response = service.preview(target,
                new PreviewDtos.PreviewRequest(null, null, null, 100, null, null), policy());

        assertEquals(2, response.rows().size(), "region='CN' 的两行可见，US 行被行过滤挡下");
        Map<Object, List<Object>> byId = byId(response.rows());
        List<Object> alice = byId.get(1);
        // mask 在同一层、参数读原值：phone 全数字 → 全 n；day 截到年；name 走 else 分支
        assertEquals("nnnnnnnnnnn", alice.get(2), "phone 应按 mask_alphanum 逐字符替换");
        assertEquals(LocalDate.of(2026, 1, 1), alice.get(4), "day 应截到年且保持 DATE 类型");
        assertEquals("HIDDEN", alice.get(1), "name 的 mask 引用 region（原值 CN）→ else 分支");
        List<Object> carol = byId.get(3);
        assertEquals("nnnnnnnnnnn", carol.get(2));
        assertEquals(LocalDate.of(2025, 1, 1), carol.get(4));
        // 列的形状不变：名字 / 顺序 / 个数与表 schema 一致（§5.3.3 透明性）
        assertEquals(List.of("id", "name", "phone", "region", "day"),
                response.columns().stream().map(c -> c.name()).toList());
        // 策略提示进 warnings：读出 3 行、返回 2 行
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("行过滤挡下 1 行")),
                "warnings 应说明策略处理：" + response.warnings());
    }

    @Test
    void previewWithoutPolicyReturnsRawRows(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        PreviewDtos.PreviewResponse response = service.preview(target(dir),
                new PreviewDtos.PreviewRequest(null, null, null, 100, null, null), null);

        assertEquals(3, response.rows().size());
        Map<Object, List<Object>> byId = byId(response.rows());
        assertEquals("13800000001", byId.get(1).get(2), "没有策略（或豁免）时读到原值");
        assertEquals("bob", byId.get(2).get(1));
        assertEquals(DAY_2, byId.get(2).get(4));
    }

    @Test
    void policyColumnsReadButNotReturnedWhenNotSelected(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        // 只看 id 与 phone：行过滤引用的 region 不在投影里，仍要补读（§5.3.3），
        // 但返回给调用方的行只有两列——补读列被切掉，不泄露
        PreviewDtos.PreviewResponse response = service.preview(target(dir),
                new PreviewDtos.PreviewRequest(null, null, List.of("id", "phone"), 100, null, null),
                policy());

        assertEquals(2, response.rows().size());
        for (List<Object> row : response.rows()) {
            assertEquals(2, row.size(), "返回行只含请求的两列：" + row);
        }
        Map<Object, List<Object>> byId = byId(response.rows());
        assertEquals("nnnnnnnnnnn", byId.get(1).get(1), "phone 在两列投影里依然被脱敏");
    }

    // ------------------------------------------------------------------ 拒绝面

    @Test
    void filtersOnMaskedColumnsAreRejected(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        PreviewDtos.PreviewRequest request = new PreviewDtos.PreviewRequest(
                List.of(new io.github.melin.lakehouse.rest.preview.ColumnFilter(
                        "phone", "eq", "13800000001")),
                null, null, 100, null, null);
        ApiException exception = assertThrows(ApiException.class,
                () -> service.preview(target(dir), request, policy()));
        assertTrue(exception.getMessage().contains("masked column 'phone'"),
                "用户条件落到被脱敏列必须指名拒绝（§5.7 语义对不上的那半）：" + exception.getMessage());
    }

    @Test
    void hiddenColumnsAreCroppedAndRefused(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        DataPolicyService.PreviewPolicy hidden = new DataPolicyService.PreviewPolicy(
                "hash", 1, coalesceFalse(op("eq", col("region"), lit("CN"))),
                Map.of(), List.of("phone"));

        // 默认投影：phone 从输出里消失，行过滤照常生效，行宽 = 可读列数
        PreviewDtos.PreviewResponse response = service.preview(target(dir),
                new PreviewDtos.PreviewRequest(null, null, null, 100, null, null), hidden);
        assertEquals(List.of("id", "name", "region", "day"),
                response.columns().stream().map(c -> c.name()).toList(),
                "默认输出不应含整列不可见列（§5.8）");
        assertEquals(2, response.rows().size(), "行过滤不受 hidden 裁剪影响");
        for (List<Object> row : response.rows()) {
            assertEquals(4, row.size(), "返回行只含可读列：" + row);
        }
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("整列不可见")),
                "warnings 应说明 hidden 裁剪：" + response.warnings());

        // 显式勾选不可见列 → 指名拒绝（与引擎的「显式引用直接失败」同语义）
        ApiException explicit = assertThrows(ApiException.class, () -> service.preview(target(dir),
                new PreviewDtos.PreviewRequest(null, null, List.of("id", "phone"), 100, null, null),
                hidden));
        assertTrue(explicit.getMessage().contains("phone"),
                "显式勾选 hidden 列要指名拒绝：" + explicit.getMessage());

        // 过滤条件引用不可见列 → 指名拒绝（行数分布也是泄露面）
        PreviewDtos.PreviewRequest filterRequest = new PreviewDtos.PreviewRequest(
                List.of(new io.github.melin.lakehouse.rest.preview.ColumnFilter(
                        "phone", "eq", "13800000001")),
                null, null, 100, null, null);
        ApiException filtered = assertThrows(ApiException.class,
                () -> service.preview(target(dir), filterRequest, hidden));
        assertTrue(filtered.getMessage().contains("hidden column 'phone'"),
                "条件引用 hidden 列要指名拒绝：" + filtered.getMessage());
    }

    @Test
    void policiesReferencingMissingColumnsFailClosed(@TempDir Path dir) throws Exception {
        prepare(dir);
        TablePreviewService service = service(dir);
        Map<String, Object> rowFilter = coalesceFalse(
                op("eq", col("ghost"), lit("CN")));
        DataPolicyService.PreviewPolicy stale = new DataPolicyService.PreviewPolicy(
                "hash", 1, rowFilter, Map.of(), List.of());
        ApiException exception = assertThrows(ApiException.class,
                () -> service.preview(target(dir),
                        new PreviewDtos.PreviewRequest(null, null, null, 100, null, null), stale));
        assertTrue(exception.getMessage().contains("ghost"),
                "策略引用不存在的列是 fail-closed，不是静默跳过：" + exception.getMessage());
    }

    // ------------------------------------------------------------------ 元数据打码（§7.3）

    @Test
    void partitionEchoIsMaskedWhenPartitionColumnIsMasked(@TempDir Path dir) throws Exception {
        preparePartitioned(dir);
        TablePreviewService service = service(dir);
        // region 既是分区列又是脱敏目标：分区回显里的值就是明文，必须打码
        DataPolicyService.PreviewPolicy regionMasked = new DataPolicyService.PreviewPolicy(
                "hash", 1,
                coalesceFalse(op("eq", col("region"), lit("CN"))),
                Map.of("region", Map.of("fn", "mask_alphanum")), List.of());
        PreviewDtos.PreviewRequest request = new PreviewDtos.PreviewRequest(null,
                new PreviewDtos.PartitionSelectionRequest("explicit",
                        List.of(new PreviewDtos.PartitionValue("region", "identity", "CN")), null),
                null, 100, null, null);
        PreviewDtos.PreviewResponse response = service.preview(target(dir, "customers_part"),
                request, regionMasked);

        assertEquals(2, response.rows().size(), "region='CN' 分区的两行可见（分区裁剪照常工作）");
        List<PreviewDtos.PartitionValue> resolved = response.partition().resolved();
        assertEquals(1, resolved.size());
        assertEquals("region", resolved.get(0).field());
        assertEquals("▮▮▮▮", resolved.get(0).value(),
                "分区列是被脱敏列，回显值必须打码（§7.3）");
        assertTrue(response.partition().label().contains("▮▮▮▮"),
                () -> "label 要同步打码: " + response.partition().label());
        assertFalse(response.partition().label().contains("CN"),
                () -> "label 里的原值不能留在明文里: " + response.partition().label());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("分区回显的值已打码")),
                "warnings 要说明打码这件事：" + response.warnings());
    }

    @Test
    void partitionEchoStaysRawWithoutMaskOnPartitionColumn(@TempDir Path dir) throws Exception {
        preparePartitioned(dir);
        TablePreviewService service = service(dir);
        // 对照组：同一张分区表，策略只 mask phone（不是分区列）——分区回显不打码。
        // 打码判据是「分区列 ∈ 脱敏目标」，不是「有策略」：误伤会把好端端的分区选择器搞瞎
        DataPolicyService.PreviewPolicy phoneMasked = new DataPolicyService.PreviewPolicy(
                "hash", 1,
                coalesceFalse(op("eq", col("region"), lit("CN"))),
                Map.of("phone", Map.of("fn", "mask_alphanum")), List.of());
        PreviewDtos.PreviewRequest request = new PreviewDtos.PreviewRequest(null,
                new PreviewDtos.PartitionSelectionRequest("explicit",
                        List.of(new PreviewDtos.PartitionValue("region", "identity", "CN")), null),
                null, 100, null, null);
        PreviewDtos.PreviewResponse response = service.preview(target(dir, "customers_part"),
                request, phoneMasked);

        assertEquals(2, response.rows().size());
        assertEquals("CN", response.partition().resolved().get(0).value(),
                "分区列不在脱敏目标里，回显保持原值");
        assertTrue(response.warnings().stream().noneMatch(w -> w.contains("分区回显的值已打码")));
    }

    // ------------------------------------------------------------------ 求值器 × 预览行类型

    @Test
    void evaluatorUnderstandsPreviewRowTypes(@TempDir Path dir) throws Exception {
        // 预览行里的值是 reader 翻出来的普通 Java 值（Integer / String / LocalDate）；
        // 策略字面量是 JSON 的 Long / String——求值器必须把两边的类型对上（数值升位、
        // 日期字面量按列类型解析），这是「双端用例表」在服务端这一侧的锚点
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("day", DAY_1);
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("id"), lit(1L))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("day"), lit("2026-10-24"))), row));
    }

    // ------------------------------------------------------------------ 装置

    private static TablePreviewService service(Path dir) {
        StorageFileIoFactory fileIo = Mockito.mock(StorageFileIoFactory.class);
        try {
            FileIO local = LocalFileIO.create();
            when(fileIo.open(any(), any())).thenReturn(local);
        } catch (Exception ignored) {
            throw new IllegalStateException("mock setup failed", ignored);
        }
        when(fileIo.optionsFor(any(), anyString(), anyString())).thenReturn(Map.of());
        PaimonPreviewTableFactory factory = new PaimonPreviewTableFactory(fileIo);
        return new TablePreviewService(
                Mockito.mock(TableRepository.class),
                Mockito.mock(DatabaseRepository.class),
                Mockito.mock(CatalogRepository.class),
                List.of(factory),
                new CatalogEventPublisher(List.of(), null),
                new PreviewCursorCodec(new RestServerProperties()));
    }

    private static PreviewTarget target(Path dir) {
        return target(dir, "customers");
    }

    private static PreviewTarget target(Path dir, String name) {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setPrefix("paimon");
        catalog.setCatalogFormat("PAIMON");
        TableEntity table = new TableEntity();
        table.setName(name);
        table.setPath(dir.resolve(name).toUri().toString());
        return new PreviewTarget(table, catalog, "sales");
    }

    /**
     * 建表 + 写数据 + 落 schema：{@code FileStoreTableFactory.create(FileIO, Path)}（服务端
     * 工厂走的两参重载）按目录里的 schema 文件开表，所以先经 {@link #parquetTable(Path)}
     * 把目录变成"可打开的表"，其余用例才能走真实读路径。
     */
    private static void prepare(Path dir) throws Exception {
        parquetTable(dir);
    }

    private static void preparePartitioned(Path dir) throws Exception {
        partitionedTable(dir);
    }

    // ------------------------------------------------------------------ Paimon 装置（与 PaimonPreviewReadTests 同形，多三列）

    private static GenericRow[] rows() {
        return new GenericRow[] {
                GenericRow.of(1, BinaryString.fromString("alice"),
                        BinaryString.fromString("13800000001"),
                        BinaryString.fromString("CN"), (int) DAY_1.toEpochDay()),
                GenericRow.of(2, BinaryString.fromString("bob"),
                        BinaryString.fromString("13900000002"),
                        BinaryString.fromString("US"), (int) DAY_2.toEpochDay()),
                GenericRow.of(3, BinaryString.fromString("carol"),
                        BinaryString.fromString("13700000003"),
                        BinaryString.fromString("CN"), (int) DAY_3.toEpochDay()),
        };
    }

    /**
     * 表路径必须先落 schema-0：{@link FileStoreTableFactory#create(FileIO,
     * org.apache.paimon.fs.Path)}（服务端工厂走的两参重载）按目录里的 schema 文件开表，
     * 所以写完数据后目录本身就是"可打开的表"。
     */
    static FileStoreTable parquetTable(Path dir) throws Exception {
        Schema schema = Schema.newBuilder()
                .column("id", DataTypes.INT())
                .column("name", DataTypes.STRING())
                .column("phone", DataTypes.STRING())
                .column("region", DataTypes.STRING())
                .column("day", DataTypes.DATE())
                .primaryKey("id")
                .option("bucket", "1")
                .option("file.format", "parquet")
                .build();
        FileIO fileIO = LocalFileIO.create();
        org.apache.paimon.fs.Path tablePath =
                new org.apache.paimon.fs.Path(dir.resolve("customers").toUri().toString());
        TableSchema tableSchema = new SchemaManager(fileIO, tablePath).createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
        write(table, rows());
        return table;
    }

    private static void write(FileStoreTable table, GenericRow... rows) throws Exception {
        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite()) {
            for (GenericRow row : rows) {
                write.write(row);
            }
            List<CommitMessage> messages = write.prepareCommit();
            try (BatchTableCommit commit = builder.newCommit()) {
                commit.commit(messages);
            }
        }
    }

    /**
     * 分区表装置（§7.3 元数据打码用例）：region 既是分区列又是主键的一部分
     * （Paimon 要求分区键 ⊆ 主键），没有 day 列——分区回显打码只关心分区列本身。
     */
    static FileStoreTable partitionedTable(Path dir) throws Exception {
        Schema schema = Schema.newBuilder()
                .column("id", DataTypes.INT())
                .column("name", DataTypes.STRING())
                .column("phone", DataTypes.STRING())
                .column("region", DataTypes.STRING())
                .primaryKey("id", "region")
                .partitionKeys("region")
                .option("bucket", "1")
                .option("file.format", "parquet")
                .build();
        FileIO fileIO = LocalFileIO.create();
        org.apache.paimon.fs.Path tablePath =
                new org.apache.paimon.fs.Path(dir.resolve("customers_part").toUri().toString());
        TableSchema tableSchema = new SchemaManager(fileIO, tablePath).createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
        write(table,
                GenericRow.of(1, BinaryString.fromString("alice"),
                        BinaryString.fromString("13800000001"), BinaryString.fromString("CN")),
                GenericRow.of(2, BinaryString.fromString("bob"),
                        BinaryString.fromString("13900000002"), BinaryString.fromString("US")),
                GenericRow.of(3, BinaryString.fromString("carol"),
                        BinaryString.fromString("13700000003"), BinaryString.fromString("CN")));
        return table;
    }

    // ------------------------------------------------------------------ AST 工具

    private static Map<String, Object> col(String name) {
        return Map.of("col", name);
    }

    private static Map<String, Object> lit(Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put("lit", value);
        return out;
    }

    private static Map<String, Object> op(String op, Object left, Object right) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", op);
        out.put("left", left);
        out.put("right", right);
        return out;
    }

    private static Map<String, Object> coalesceFalse(Object inner) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fn", "coalesce");
        out.put("args", List.of(inner, lit(Boolean.FALSE)));
        return out;
    }

    private static Map<Object, List<Object>> byId(List<List<Object>> rows) {
        // id 在第 0 列；用 id 做键来对账，避免依赖主键表读出的行序
        Map<Object, List<Object>> out = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            out.put(row.get(0), row);
        }
        return out;
    }
}
