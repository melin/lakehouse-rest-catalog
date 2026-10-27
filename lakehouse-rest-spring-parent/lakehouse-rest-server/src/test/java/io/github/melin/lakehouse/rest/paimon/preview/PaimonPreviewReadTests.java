package io.github.melin.lakehouse.rest.paimon.preview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.preview.PreviewChunk;
import io.github.melin.lakehouse.rest.preview.PreviewTable;
import java.nio.file.Path;
import java.util.List;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.FileSystemSchemaManager;
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

/**
 * 数据预览的**真实读路径**：在本地仓库写一张 parquet 表，再按预览的方式读回来。
 *
 * <p><b>为什么必须有这个测试。</b>预览原有的用例都停在「服务端编排」这一层
 * （{@code PartitionPlanner} / 游标 / 分区聚合），读文件那一步用的是桩。于是
 * 2026-10-21 线上暴露了一个只在真读时才出现的问题：类路径里没有 Hadoop，
 * {@code ParquetReadOptions$Builder} 的构造函数解析 {@code FileInputFormat} 时抛
 * {@code NoClassDefFoundError}，接口 500——而所有单测都是绿的。
 *
 * <p>这个用例把「写 parquet → 按 DataSplit 读回来」整条链路跑一遍，
 * <b>它红了就说明预览在读数据这一步已经不通</b>，不必等到有人打开页面。
 *
 * <p>包名与 {@code PaimonPreviewTable} 相同：它的构造器是包私有的（外部只该通过
 * {@code PaimonPreviewTableFactory} 拿实例），测试用同一个包名而不是把它改成 public——
 * 为一个测试放宽生产类的可见性，会让「只有工厂能造它」这条约束失效。
 */
class PaimonPreviewReadTests {

    @Test
    void readsRowsFromALocalParquetTable(@TempDir Path dir) throws Exception {
        FileStoreTable table = parquetTable(dir);
        write(table,
                GenericRow.of(1, BinaryString.fromString("alpha")),
                GenericRow.of(2, BinaryString.fromString("beta")));

        try (PaimonPreviewTable preview = new PaimonPreviewTable(table, null)) {
            PreviewChunk chunk = preview.read(spec(100));

            assertEquals(2, chunk.rows().size(), "两行都应读出来");
            assertEquals(List.of(1, "alpha"), chunk.rows().get(0));
            assertEquals(List.of(2, "beta"), chunk.rows().get(1));
            assertTrue(chunk.exhausted(), "只有两个文件、预算充足，应读完");
            assertEquals(1, chunk.filesRead(), "bucket=1 且一次写入，应只有一个数据文件");
            assertTrue(chunk.bytesRead() > 0, "parquet 文件大小应被统计到");
        }
    }

    @Test
    void projectionKeepsColumnOrder(@TempDir Path dir) throws Exception {
        FileStoreTable table = parquetTable(dir);
        write(table, GenericRow.of(7, BinaryString.fromString("gamma")));

        try (PaimonPreviewTable preview = new PaimonPreviewTable(table, null)) {
            // 只读 name，但要按 projection 的顺序回：这是「为过滤补读列」的机制基础
            PreviewChunk chunk = preview.read(new PreviewTable.ReadSpec(
                    new int[] {1}, 1, List.of(), List.of(),
                    0, 0, 10, 10, 64L << 20, System.nanoTime() + 30_000_000_000L));

            assertEquals(1, chunk.rows().size());
            assertEquals(List.of("gamma"), chunk.rows().get(0));
        }
    }

    /**
     * 类路径守卫：这几个类缺任一，预览读 parquet 就会在
     * {@code ParquetReadOptions.builder(...)} 里抛 NoClassDefFoundError。
     *
     * <p>与上面的用例是两件事：上面证明「写读链路通」，这里给出**点名到类**的失败信息——
     * 有人把 {@code hadoop-client-api} / {@code hadoop-client-runtime} 从 server 的
     * 依赖里删掉时，红的那一行直接写着少了谁（见 {@code lakehouse-rest-server/pom.xml}）。
     *
     * <p>两个 {@code ParquetInputFormat} 都要点名：Paimon 面用的是 paimon-format 里
     * relocate 过的 shade 副本，Iceberg 面用的是原生 parquet-hadoop，它们是两份 jar、
     * 同一个父类——只验一份会漏掉另一半。
     */
    @Test
    void parquetReaderFindsHadoopOnTheRuntimeClasspath() {
        assertDoesNotThrow(
                () -> Class.forName("org.apache.hadoop.conf.Configuration"),
                "ParquetReadOptions 要 Hadoop 的 Configuration");
        assertDoesNotThrow(
                () -> Class.forName("org.apache.hadoop.mapreduce.lib.input.FileInputFormat"),
                "存在 ParquetInputFormat extends FileInputFormat，加载它必须能解析父类");
        assertDoesNotThrow(
                () -> Class.forName("org.apache.paimon.shade.org.apache.parquet.hadoop.ParquetInputFormat"),
                "Paimon 面的 parquet 读取器（shade 副本）");
        assertDoesNotThrow(
                () -> Class.forName("org.apache.parquet.hadoop.ParquetInputFormat"),
                "Iceberg 面的 parquet 读取器（原生 parquet-hadoop）");
    }

    private static PreviewTable.ReadSpec spec(int limit) {
        return new PreviewTable.ReadSpec(
                new int[] {0, 1}, 2, List.of(), List.of(),
                0, 0, limit, 10, 64L << 20, System.nanoTime() + 30_000_000_000L);
    }

    /**
     * 建一张最小的 parquet 表：两列、主键、单 bucket、非分区。
     *
     * <p>刻意用**主键表**：它的 {@code DataSplit} 是 {@code dataFiles + deleteFiles}
     * 的装配体，正是「必须走格式自己的扫描器」那条约束的现场（设计文档 §3）。
     * 追加表（append）反而测不到这一层。
     *
     * <p>走 {@code FileStoreTableFactory.create(FileIO, Path, TableSchema)} 而不是
     * {@code Catalog}：这个用例要验的是读，不是元数据目录。
     */
    private static FileStoreTable parquetTable(Path dir) throws Exception {
        Schema schema = Schema.newBuilder()
                .column("id", DataTypes.INT())
                .column("name", DataTypes.STRING())
                .primaryKey("id")
                .option("bucket", "1")
                .option("file.format", "parquet")
                .build();
        FileIO fileIO = LocalFileIO.create();
        org.apache.paimon.fs.Path tablePath =
                new org.apache.paimon.fs.Path(dir.resolve("t").toUri().toString());
        // 先落 schema-0 再开表：FileStoreTableFactory 只按内存里的 TableSchema 组装对象，
        // 不写文件；少了这一步，第一次 commit 会以「Cannot get latest schema」收场
        TableSchema tableSchema = new FileSystemSchemaManager(fileIO, tablePath).createTable(schema);
        return FileStoreTableFactory.create(fileIO, tablePath, tableSchema);
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
}
