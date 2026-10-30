package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableSnapshotEntity;
import io.github.melin.lakehouse.rest.domain.repo.TableSnapshotRepository;
import io.github.melin.lakehouse.rest.paimon.dto.BranchDtos;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.paimon.dto.ConsumerDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.FunctionDtos;
import io.github.melin.lakehouse.rest.dto.ManagementDtos;
import io.github.melin.lakehouse.rest.paimon.dto.PartitionDtos;
import io.github.melin.lakehouse.rest.paimon.dto.SemanticViewDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TagDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.ViewDtos;
import io.github.melin.lakehouse.rest.paimon.service.BranchService;
import io.github.melin.lakehouse.rest.service.CatalogProtocols;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.paimon.service.ConsumerService;
import io.github.melin.lakehouse.rest.paimon.service.CredentialService;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.FunctionService;
import io.github.melin.lakehouse.rest.service.ManagementCatalogService;
import io.github.melin.lakehouse.rest.paimon.service.PartitionService;
import io.github.melin.lakehouse.rest.paimon.service.SemanticViewService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.paimon.service.TagService;
import io.github.melin.lakehouse.rest.paimon.service.ViewService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.TableDefaults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 覆盖 REST Catalog 主要资源生命周期的集成测试。
 *
 * <p>直接调用服务层，验证 JPA 持久化、schema 变更语义、快照提交与回滚、
 * 以及各类错误码是否符合规格。
 *
 * <p>{@code test} profile 把数据源从默认的 MySQL 换成内存 H2，
 * 使本类不依赖本机是否有 MySQL 实例。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaimonRestCatalogApiTests {

    private static final String PREFIX = "paimon";

    @Autowired
    private CatalogService catalogService;
    @Autowired
    private CatalogProtocols protocols;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private PartitionService partitionService;
    @Autowired
    private TagService tagService;
    @Autowired
    private BranchService branchService;
    @Autowired
    private ViewService viewService;
    @Autowired
    private FunctionService functionService;
    @Autowired
    private SemanticViewService semanticViewService;
    @Autowired
    private ConsumerService consumerService;
    @Autowired
    private CredentialService credentialService;
    /** 只有「模拟升级前写下的快照行」这一个用例需要直接改库，用它是为了不新开一条服务端入口。 */
    @Autowired
    private TableSnapshotRepository snapshotRepository;
    /** 「catalog 默认表属性」用例要改 catalog 的属性，走的就是控制台配置页那条写入路径。 */
    @Autowired
    private ManagementCatalogService catalogManagementService;

    @Test
    void configExposesPrefixForClientBootstrap() {
        // 与 ConfigController 一样的组装方式：业务侧解析 prefix，装配侧拼出响应体
        CatalogEntity catalog = catalogService.resolveForConfig("paimon");
        CommonDtos.ConfigResponse config = (CommonDtos.ConfigResponse)
                protocols.configResponse(catalogService.formatOf(catalog), catalog);
        assertEquals("paimon", config.defaults().get("prefix"));
        assertNotNull(config.defaults().get("warehouse"));
        assertNotNull(config.overrides());
    }

    @Test
    void tableLifecycleCoversSchemaChangesSnapshotsAndTags() {
        String database = "it_lifecycle";
        createDatabase(database);

        // 建表
        TableDtos.CreateTableRequest createRequest = new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema());
        tableService.create(PREFIX, database, createRequest);

        TableDtos.GetTableResponse created = tableService.get(PREFIX, database, "orders");
        assertEquals("orders", created.name());
        assertEquals(0L, created.schemaId());
        assertEquals(3, created.schema().getFields().size());
        assertEquals(List.of("dt"), created.schema().getPartitionKeys());
        assertTrue(created.path().endsWith("orders"), "表路径应包含表名: " + created.path());
        assertFalse(created.isExternal());

        // 表已存在 -> 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> tableService.create(PREFIX, database, createRequest));
        assertEquals(409, conflict.getStatus());
        assertEquals(ResourceType.TABLE, conflict.getResourceType());

        // schema 变更：加列 / 改名 / 改类型 / 改可空性 / 加属性
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("action", "addColumn", "fieldNames", List.of("email"), "dataType", "VARCHAR(100)"));
        changes.add(change("action", "setOption", "key", "bucket", "value", "4"));
        changes.add(change("action", "renameColumn", "fieldNames", List.of("name"), "newName", "full_name"));
        changes.add(change("action", "updateColumnType", "fieldNames", List.of("id"),
                "newDataType", "BIGINT", "keepNullability", true));
        changes.add(change("action", "updateColumnNullability", "fieldNames", List.of("email"),
                "newNullability", false));
        changes.add(change("action", "updateColumnComment", "fieldNames", List.of("full_name"),
                "newComment", "客户全名"));
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(changes));

        TableDtos.GetTableResponse altered = tableService.get(PREFIX, database, "orders");
        assertEquals(1L, altered.schemaId(), "一次变更请求只产生一个新 schema 版本");
        assertEquals(4, altered.schema().getFields().size());
        assertEquals("4", altered.schema().getOptions().get("bucket"));

        assertEquals("BIGINT NOT NULL", typeOf(altered.schema(), "id"),
                "keepNullability=true 时应保留原有的 NOT NULL 标记");
        assertEquals("VARCHAR(100) NOT NULL", typeOf(altered.schema(), "email"),
                "updateColumnNullability=false 应写入 NOT NULL 标记");
        TypeDtos.DataField fullName = altered.schema().getFields().stream()
                .filter(field -> "full_name".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("VARCHAR(20)", fullName.getType(), "只改名不应改变类型");
        assertEquals("客户全名", fullName.getDescription());

        // keepNullability=false 时丢弃原有的 NOT NULL 标记
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "updateColumnType", "fieldNames", List.of("id"),
                        "newDataType", "BIGINT", "keepNullability", false))));
        TableDtos.GetTableResponse retyped = tableService.get(PREFIX, database, "orders");
        assertEquals(2L, retyped.schemaId());
        assertEquals("BIGINT", typeOf(retyped.schema(), "id"));

        // 复杂类型：ARRAY 列正常加入，可空性变更则被拒绝
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("tags"),
                        "dataType", Map.of("type", "ARRAY", "element", "VARCHAR(10)")))));
        TypeDtos.DataField tags = fieldOf(tableService.get(PREFIX, database, "orders").schema(), "tags");
        assertTrue(tags.getType() instanceof Map, "复杂类型以 object 形式回传");
        assertEquals("ARRAY", ((Map<?, ?>) tags.getType()).get("type").toString());

        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "updateColumnNullability",
                        "fieldNames", List.of("tags"), "newNullability", false)))));

        // 嵌套字段：ROW 类型下的子字段按路径新增
        tableService.alter(PREFIX, database, "orders", new TableDtos.AlterTableRequest(List.of(
                change("action", "addColumn", "fieldNames", List.of("addr"),
                        "dataType", Map.of("type", "ROW", "fields", List.of())),
                change("action", "addColumn", "fieldNames", List.of("addr", "city"),
                        "dataType", "VARCHAR(50)"))));
        Object rowFields = ((Map<?, ?>) fieldOf(
                tableService.get(PREFIX, database, "orders").schema(), "addr").getType()).get("fields");
        assertTrue(rowFields instanceof List, "ROW 类型的子字段应为列表");
        assertEquals(1, ((List<?>) rowFields).size());
        assertEquals("city", ((TypeDtos.DataField) ((List<?>) rowFields).get(0)).getName());

        // 路径不存在或列重复时返回 400
        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "addColumn",
                        "fieldNames", List.of("nope", "child"), "dataType", "INT")))));
        assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "addColumn",
                        "fieldNames", List.of("email"), "dataType", "INT")))));

        // 未支持的 action -> 400
        ApiException badAction = assertThrows(ApiException.class, () -> tableService.alter(PREFIX, database, "orders",
                new TableDtos.AlterTableRequest(List.of(change("action", "explode",
                        "fieldNames", List.of("email"))))));
        assertEquals(400, badAction.getStatus());

        // 提交快照。commitIdentifier 取数字：它连同快照原文一起会被写进仓库，
        // 并被 Paimon 自己的 Snapshot 模型反解（那个字段是 long，字符串型值解析不过）
        TableDtos.Snapshot snapshot = new TableDtos.Snapshot(1, "uuid-1", 1L, 1L,
                "base.manifest", "delta.manifest", null, "index.manifest",
                "tester", "1", "APPEND", System.currentTimeMillis(),
                Map.of("bucket-0", 12L), 100L, 100L, 0L, 5L, null);
        TableDtos.CommitTableResponse committed = tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), null, snapshot, null));
        assertTrue(committed.success());

        TableDtos.TableSnapshot latest = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(1L, latest.snapshot().id());
        assertEquals(100L, latest.recordCount());
        assertEquals(1, tableService.listSnapshots(PREFIX, database, "orders", null, null, null, null).snapshots().size());

        // 乐观并发：baseSnapshotUuid 不匹配时提交被拒绝
        TableDtos.CommitTableResponse stale = tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), "uuid-stale", snapshot, null));
        assertFalse(stale.success());

        // 打标签
        tagService.create(PREFIX, database, "orders",
                new TagDtos.CreateTagRequest("v1", null, "7d", false));
        TagDtos.GetTagResponse tag = tagService.get(PREFIX, database, "orders", "v1");
        assertEquals(1L, tag.snapshot().id());
        assertEquals("7d", tag.tagTimeRetained());
        assertEquals(List.of("v1"), tagService.list(PREFIX, database, "orders", null, null, null).tags());

        // 分支
        branchService.create(PREFIX, database, "orders", new BranchDtos.CreateBranchRequest("dev", "v1"));
        assertEquals(List.of("dev"), branchService.list(PREFIX, database, "orders").branches());
        branchService.rename(PREFIX, database, "orders", "dev", new BranchDtos.RenameBranchRequest("dev2"));
        assertEquals(List.of("dev2"), branchService.list(PREFIX, database, "orders").branches());
        assertThrows(ApiException.class, () -> branchService.create(PREFIX, database, "orders",
                new BranchDtos.CreateBranchRequest("dev2", null)));

        // 第二个快照后回滚到第一个
        TableDtos.Snapshot second = new TableDtos.Snapshot(2, "uuid-2", 2L, 1L,
                "base2.manifest", "delta2.manifest", null, "index2.manifest",
                "tester", "2", "APPEND", System.currentTimeMillis(),
                Map.of(), 150L, 50L, 0L, 9L, null);
        tableService.commit(PREFIX, database, "orders",
                new TableDtos.CommitTableRequest(created.id(), "uuid-1", second, null));
        assertEquals(2, tableService.listSnapshots(PREFIX, database, "orders", null, null, null, null).snapshots().size());

        tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "snapshot", "snapshotId", 1L), null));
        List<TableDtos.Snapshot> afterRollback =
                tableService.listSnapshots(PREFIX, database, "orders", null, null, null, null).snapshots();
        assertEquals(1, afterRollback.size(), "回滚应丢弃目标之后的快照");
        assertEquals(100L, tableService.getTableSnapshot(PREFIX, database, "orders").snapshot().recordCount());

        // 按标签回滚
        tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "tag", "tagName", "v1"), null));
        assertThrows(ApiException.class, () -> tableService.rollback(PREFIX, database, "orders",
                new TableDtos.RollbackTableRequest(Map.of("type", "tag", "tagName", "missing"), null)));

        // schema 回滚到版本 0
        long schemaIdBeforeRollback = tableService.get(PREFIX, database, "orders").schemaId();
        tableService.rollbackSchema(PREFIX, database, "orders", new TableDtos.RollbackSchemaRequest(0L));
        TableDtos.GetTableResponse rolledBack = tableService.get(PREFIX, database, "orders");
        assertEquals(schemaIdBeforeRollback + 1, rolledBack.schemaId(),
                "回滚 schema 本身也是一次变更，版本号继续递增");
        assertEquals(3, rolledBack.schema().getFields().size());
        assertEquals("name", rolledBack.schema().getFields().get(1).getName());
        assertNotNull(rolledBack.updatedAt());
    }

    @Test
    void partitionLifecycleAndStatisticsAccumulate() {
        String database = "it_partition";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "events"), ordersSchema()));

        Map<String, String> january = Map.of("dt", "2024-01-01");
        Map<String, String> february = Map.of("dt", "2024-02-01");

        PartitionDtos.CreatePartitionsResponse created = partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january, february), false, null, null, null));
        assertEquals(2, created.created().size());
        assertTrue(created.existed().isEmpty());

        // 重复创建 -> existed；ignoreIfExists=false 时 409
        PartitionDtos.CreatePartitionsResponse again = partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, null, null, null));
        assertEquals(1, again.existed().size());
        ApiException conflict = assertThrows(ApiException.class, () -> partitionService.create(PREFIX, database,
                "events", new PartitionDtos.CreatePartitionsRequest(List.of(january), false, null, null, null)));
        assertEquals(409, conflict.getStatus());

        // 统计累加
        PartitionDtos.PartitionStatistics stats = new PartitionDtos.PartitionStatistics(
                Map.of("dt", "2024-01-01"), 10L, 1024L, 2L, 1000L, 4);
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(stats), true, null));
        PartitionDtos.Partition partition = partitionService.list(PREFIX, database, "events", null, null, null, null, null)
                .partitions().stream()
                .filter(item -> "2024-01-01".equals(String.valueOf(item.spec().get("dt"))))
                .findFirst()
                .orElseThrow();
        assertEquals(10L, partition.recordCount());
        assertEquals(4, partition.totalBuckets());
        assertEquals(Boolean.FALSE, partition.done());

        // replaceStatistics=true 覆盖而非累加
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(stats), true, null));
        assertEquals(10L, firstPartition(january).recordCount(), "replaceStatistics=true 应覆盖存储值");

        // 负值表示未测量，不覆盖已有值
        PartitionDtos.PartitionStatistics unknown = new PartitionDtos.PartitionStatistics(
                Map.of("dt", "2024-01-01"), -1L, -1L, -1L, -1L, null);
        partitionService.create(PREFIX, database, "events",
                new PartitionDtos.CreatePartitionsRequest(List.of(january), true, List.of(unknown), true, null));
        assertEquals(10L, firstPartition(january).recordCount(), "未测量的字段应保留原值");

        // 按名称查询
        PartitionDtos.ListPartitionsResponse byNames = partitionService.listByNames(PREFIX, database, "events",
                new PartitionDtos.ListPartitionsByNamesRequest(List.of(february)));
        assertEquals(1, byNames.partitions().size());

        // mark done
        partitionService.markDone(PREFIX, database, "events",
                new PartitionDtos.MarkDonePartitionsRequest(List.of(Map.of("dt", "2024-01-01"))));
        assertEquals(Boolean.TRUE, firstPartition(january).done());
        ApiException missing = assertThrows(ApiException.class, () -> partitionService.markDone(PREFIX, database,
                "events", new PartitionDtos.MarkDonePartitionsRequest(List.of(Map.of("dt", "2099-01-01")))));
        assertEquals(ResourceType.PARTITION, missing.getResourceType());

        // drop：存在与不存在分别进 dropped / missing
        PartitionDtos.DropPartitionsResponse dropped = partitionService.drop(PREFIX, database, "events",
                new PartitionDtos.DropPartitionsRequest(List.of(january, Map.of("dt", "2099-01-01")), true));
        assertEquals(1, dropped.dropped().size());
        assertEquals(1, dropped.missing().size());
        assertEquals(1, partitionService.list(PREFIX, database, "events", null, null, null, null, null).partitions().size());

        // 名称模式匹配
        PartitionDtos.ListPartitionsResponse matched = partitionService.list(PREFIX, database, "events",
                null, null, "dt=2024-02%", null, null);
        assertEquals(1, matched.partitions().size());
        assertEquals(0, partitionService.list(PREFIX, database, "events", null, null, "dt=2099%", null, null).partitions().size());
    }

    /**
     * 快照行的文件统计（{@code TableSnapshot} 的 fileCount / fileSizeInBytes /
     * lastFileCreationTime）由提交携带的分区统计**增量**累计而来。
     *
     * <p>快照自己只带记录数，另外三个数不在快照里——客户端一次提交只报它改动过的分区，
     * 服务端把这份增量叠到上一个快照的累计值上。因此五件事要一起钉住：累加、未测量的
     * 字段（负值）被跳过、没带统计的提交沿用上一个快照的值而不是清空、基线那一行
     * 本身没有文件统计时（写在统计落库之前的行）退回按分区求合计，以及最新快照的行
     * 缺文件统计时读路径按分区合计兜底（老服务端写的表升级后立即有值，不必等新提交）。
     */
    @Test
    void snapshotFileStatisticsAccumulateAcrossCommits() {
        String database = "it_snapshot_stats";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        TableDtos.GetTableResponse table = tableService.get(PREFIX, database, "orders");

        // 还没有快照：统计取不到就是 404，控制台据此把统计块留空
        assertEquals(404, assertThrows(ApiException.class,
                () -> tableService.getTableSnapshot(PREFIX, database, "orders")).getStatus());

        // 第一次提交：一个分区带统计、一个分区的字段全是未测量
        commitSnapshot(database, "orders", 1L, 100L, List.of(
                new PartitionDtos.PartitionStatistics(Map.of("dt", "2024-01-01"), 100L, 2048L, 3L, 1000L, 2),
                new PartitionDtos.PartitionStatistics(Map.of("dt", "2024-02-01"), -1L, -1L, -1L, -1L, null)));
        TableDtos.TableSnapshot first = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(100L, first.recordCount(), "记录数始终取快照自带的 totalRecordCount");
        assertEquals(3L, first.fileCount(), "未测量的分区字段不进合计");
        assertEquals(2048L, first.fileSizeInBytes());
        assertEquals(1000L, first.lastFileCreationTime());

        // 第二次提交：在上一个快照的累计值上继续加，时间戳取较晚者
        commitSnapshot(database, "orders", 2L, 150L, List.of(
                new PartitionDtos.PartitionStatistics(Map.of("dt", "2024-01-01"), 50L, 1024L, 2L, 3000L, 2)));
        TableDtos.TableSnapshot second = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(150L, second.recordCount());
        assertEquals(5L, second.fileCount());
        assertEquals(3072L, second.fileSizeInBytes());
        assertEquals(3000L, second.lastFileCreationTime());

        // 没带统计的提交（老客户端、或只改了元数据的空提交）：沿用上一个快照的累计值
        commitSnapshot(database, "orders", 3L, 150L, null);
        TableDtos.TableSnapshot third = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(5L, third.fileCount());
        assertEquals(3072L, third.fileSizeInBytes());
        assertEquals(3000L, third.lastFileCreationTime());

        // 基线那一行没有文件统计（统计落库之前写的行，升级后的表就是这种形状）：
        // 退回按分区求合计当基线，而不是只算本次增量——否则历史表会一直显示「从升级点起」的小值。
        TableSnapshotEntity legacy = snapshotRepository
                .findByTableIdAndSnapshotId(table.id(), third.snapshot().id())
                .orElseThrow();
        legacy.setFileCount(null);
        legacy.setFileSizeInBytes(null);
        legacy.setLastFileCreationTime(null);
        snapshotRepository.save(legacy);

        commitSnapshot(database, "orders", 4L, 200L, List.of(
                new PartitionDtos.PartitionStatistics(Map.of("dt", "2024-01-01"), 25L, 4096L, 4L, 5000L, 2)));
        TableDtos.TableSnapshot fourth = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(9L, fourth.fileCount(), "分区合计 5 + 本次 4");
        assertEquals(7168L, fourth.fileSizeInBytes(), "分区合计 3072 + 本次 4096");
        assertEquals(5000L, fourth.lastFileCreationTime());

        // 读时兜底：最新快照的行没有文件统计（老服务端写的行，分区统计倒是早就在维护），
        // 读路径按分区合计现算——升级后的历史表不必等下一次提交才有值。
        // 分区合计与提交累计同源同值（都是同一批增量），dt=2024-02-01 全未测量被跳过。
        TableSnapshotEntity stale = snapshotRepository
                .findByTableIdAndSnapshotId(table.id(), fourth.snapshot().id())
                .orElseThrow();
        stale.setFileCount(null);
        stale.setFileSizeInBytes(null);
        stale.setLastFileCreationTime(null);
        snapshotRepository.save(stale);
        TableDtos.TableSnapshot fallback = tableService.getTableSnapshot(PREFIX, database, "orders").snapshot();
        assertEquals(9L, fallback.fileCount(), "读时按分区合计兜底：3 + 2 + 4");
        assertEquals(7168L, fallback.fileSizeInBytes(), "读时按分区合计兜底：2048 + 1024 + 4096");
        assertEquals(5000L, fallback.lastFileCreationTime(), "时间戳取各分区里较晚者");
    }

    /** 提交一个 APPEND 快照，可随带该次提交的分区统计。 */
    private void commitSnapshot(String database, String name, long snapshotId, long totalRecordCount,
                                List<PartitionDtos.PartitionStatistics> statistics) {
        TableDtos.GetTableResponse table = tableService.get(PREFIX, database, name);
        TableDtos.Snapshot snapshot = new TableDtos.Snapshot(1, "uuid-" + snapshotId, snapshotId, table.schemaId(),
                "base-" + snapshotId + ".manifest", "delta-" + snapshotId + ".manifest", null,
                "index-" + snapshotId + ".manifest", "tester", String.valueOf(snapshotId), "APPEND",
                1700000000000L + snapshotId, Map.of(), totalRecordCount, totalRecordCount, 0L, null, null);
        TableDtos.CommitTableResponse committed = tableService.commit(PREFIX, database, name,
                new TableDtos.CommitTableRequest(table.id(), null, snapshot, statistics));
        assertTrue(committed.success());
    }

    @Test
    void viewsFunctionsConsumersAndCredentials() {
        String database = "it_objects";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema()));

        // 视图
        TypeDtos.ViewSchema viewSchema = new TypeDtos.ViewSchema();
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(0);
        field.setName("id");
        field.setType("BIGINT");
        viewSchema.getFields().add(field);
        viewSchema.setQuery("SELECT id FROM orders");
        viewService.create(PREFIX, database, new ViewDtos.CreateViewRequest(
                new CommonDtos.Identifier(database, "orders_view"), viewSchema));

        viewService.alter(PREFIX, database, "orders_view", new ViewDtos.AlterViewRequest(List.of(
                change("action", "addDialect", "dialect", "spark", "query", "SELECT id FROM spark.orders"),
                change("action", "setOption", "key", "comment", "value", "视图"))));

        ViewDtos.GetViewResponse view = viewService.get(PREFIX, database, "orders_view");
        assertEquals("SELECT id FROM orders", view.schema().getQuery());
        assertEquals("SELECT id FROM spark.orders", view.schema().getDialects().get("spark"));
        assertEquals(List.of("orders_view"), viewService.list(PREFIX, database, null, null, null, null, null).views());

        // 视图重命名
        viewService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "orders_view"),
                new CommonDtos.Identifier(database, "orders_view_v2")));
        assertEquals(List.of("orders_view_v2"), viewService.list(PREFIX, database, null, null, null, null, null).views());

        // 函数
        Map<String, Object> definitions = new LinkedHashMap<>();
        definitions.put("default", Map.of("type", "sql", "definition", "a + b"));
        functionService.create(PREFIX, database, new FunctionDtos.CreateFunctionRequest(
                "add_one", List.of(field(0, "a", "INT")), List.of(field(0, "result", "INT")),
                true, definitions, "加法", Map.of("owner", "paimon")));
        functionService.alter(PREFIX, database, "add_one", new FunctionDtos.AlterFunctionRequest(List.of(
                change("action", "addDefinition", "name", "spark",
                        "definition", Map.of("type", "lambda", "language", "java")),
                change("action", "updateComment", "comment", "整数加法"))));

        FunctionDtos.GetFunctionResponse function = functionService.get(PREFIX, database, "add_one");
        assertEquals("整数加法", function.comment());
        assertEquals(2, function.definitions().size());
        assertTrue(function.definitions().containsKey("spark"));
        assertEquals(1, function.inputParams().size());

        ApiException dup = assertThrows(ApiException.class, () -> functionService.create(PREFIX, database,
                new FunctionDtos.CreateFunctionRequest("add_one", null, null, null, null, null, null)));
        assertEquals(409, dup.getStatus());
        assertThrows(ApiException.class, () -> functionService.alter(PREFIX, database, "add_one",
                new FunctionDtos.AlterFunctionRequest(List.of(change("action", "dropDefinition", "name", "nope")))));

        // 消费者
        consumerService.reset(PREFIX, database, "orders",
                new ConsumerDtos.ResetConsumerRequest("c1", 5L));
        assertEquals(1, consumerService.list(PREFIX, database, "orders", null, null).consumers().size());
        consumerService.reset(PREFIX, database, "orders",
                new ConsumerDtos.ResetConsumerRequest("c1", null));
        assertTrue(consumerService.list(PREFIX, database, "orders", null, null).consumers().isEmpty());

        // 凭证下发：范围限定到单表，带过期时间
        TableDtos.GetTableDataTokenResponse token = credentialService.token(PREFIX, database, "orders");
        assertNotNull(token.token().get("securityToken"));
        assertTrue(token.token().get("accessKeyId").startsWith("PAIMON-"));
        assertTrue(token.expiresAt() > System.currentTimeMillis());
        // 同一个时刻要同时回规格名与客户端实际读的名字（见 GetTableDataTokenResponse 的注释）：
        // 少了后者，Paimon 客户端会把有效期读成 0，从而每次文件操作前都重新取一次凭据。
        assertEquals(token.expiresAt(), token.expiresAtMillis(),
                "响应要同时给 expiresAt 与 expiresAtMillis");

        // 查询鉴权：请求未知列 -> 403
        TableDtos.AuthTableQueryResponse auth = credentialService.auth(PREFIX, database, "orders",
                new TableDtos.AuthTableQueryRequest(List.of("id", "dt")));
        assertTrue(auth.filter().isEmpty());
        ApiException forbidden = assertThrows(ApiException.class, () -> credentialService.auth(PREFIX, database,
                "orders", new TableDtos.AuthTableQueryRequest(List.of("nope"))));
        assertEquals(403, forbidden.getStatus());
    }

    @Test
    void semanticViewsAreUpsertedAndSizeLimited() {
        String database = "it_semantic";
        createDatabase(database);

        semanticViewService.upsert(PREFIX, database, "sales_model", new SemanticViewDtos.UpsertSemanticViewRequest(
                new SemanticViewDtos.SemanticViewDefinition("databricks-yaml", "version: 1\nmodel: sales")));
        assertEquals("version: 1\nmodel: sales",
                semanticViewService.get(PREFIX, database, "sales_model").definition().content());

        // 同名再提交即覆盖
        semanticViewService.upsert(PREFIX, database, "sales_model", new SemanticViewDtos.UpsertSemanticViewRequest(
                new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", "model: sales_v2")));
        assertEquals("ossie-yaml", semanticViewService.get(PREFIX, database, "sales_model").definition().format());
        assertEquals(List.of("sales_model"),
                semanticViewService.list(PREFIX, database, null, null).semanticViews());

        // 超过 1 MiB -> 413，且不落库
        String tooLarge = "x".repeat(1048577);
        ApiException tooLargeError = assertThrows(ApiException.class, () -> semanticViewService.upsert(
                PREFIX, database, "huge", new SemanticViewDtos.UpsertSemanticViewRequest(
                        new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", tooLarge))));
        assertEquals(413, tooLargeError.getStatus());
        assertThrows(ApiException.class, () -> semanticViewService.get(PREFIX, database, "huge"));

        ApiException blank = assertThrows(ApiException.class, () -> semanticViewService.upsert(
                PREFIX, database, "blank", new SemanticViewDtos.UpsertSemanticViewRequest(
                        new SemanticViewDtos.SemanticViewDefinition("ossie-yaml", " "))));
        assertEquals(400, blank.getStatus());

        semanticViewService.drop(PREFIX, database, "sales_model");
        assertThrows(ApiException.class, () -> semanticViewService.get(PREFIX, database, "sales_model"));
    }

    @Test
    void missingResourcesReturn404WithResourceType() {
        String database = "it_missing";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "t"), ordersSchema()));

        ApiException databaseMissing = assertThrows(ApiException.class,
                () -> databaseService.get(PREFIX, "no_such_database"));
        assertEquals(404, databaseMissing.getStatus());
        assertEquals(ResourceType.DATABASE, databaseMissing.getResourceType());

        ApiException tableMissing = assertThrows(ApiException.class,
                () -> tableService.get(PREFIX, database, "no_such_table"));
        assertEquals(ResourceType.TABLE, tableMissing.getResourceType());

        ApiException tagMissing = assertThrows(ApiException.class,
                () -> tagService.get(PREFIX, database, "t", "no_such_tag"));
        assertEquals(ResourceType.TAG, tagMissing.getResourceType());

        ApiException branchMissing = assertThrows(ApiException.class,
                () -> branchService.drop(PREFIX, database, "t", "no_such_branch"));
        assertEquals(ResourceType.BRANCH, branchMissing.getResourceType());

        ApiException viewMissing = assertThrows(ApiException.class,
                () -> viewService.get(PREFIX, database, "no_such_view"));
        assertEquals(ResourceType.VIEW, viewMissing.getResourceType());

        ApiException functionMissing = assertThrows(ApiException.class,
                () -> functionService.get(PREFIX, database, "no_such_function"));
        assertEquals(ResourceType.FUNCTION, functionMissing.getResourceType());

        ApiException semanticViewMissing = assertThrows(ApiException.class,
                () -> semanticViewService.get(PREFIX, database, "no_such_semantic_view"));
        assertEquals(ResourceType.SEMANTIC_VIEW, semanticViewMissing.getResourceType());

        ApiException tableIdMissing = assertThrows(ApiException.class,
                () -> tableService.getById(PREFIX, "no-such-table-id"));
        assertEquals(ResourceType.TABLE, tableIdMissing.getResourceType());
    }

    @Test
    void databaseAlterReportsRemovedUpdatedAndMissingKeys() {
        String database = "it_alter";
        DatabaseDtos.CreateDatabaseRequest request =
                new DatabaseDtos.CreateDatabaseRequest(database, Map.of("owner", "paimon", "keep", "1"));
        databaseService.create(PREFIX, request);

        DatabaseDtos.AlterDatabaseResponse response = databaseService.alter(PREFIX, database,
                new DatabaseDtos.AlterDatabaseRequest(List.of("keep", "absent"), Map.of("tier", "gold")));
        assertEquals(List.of("keep"), response.removed());
        assertEquals(List.of("tier"), response.updated());
        assertEquals(List.of("absent"), response.missing());

        DatabaseDtos.GetDatabaseResponse loaded = databaseService.get(PREFIX, database);
        assertNull(loaded.options().get("keep"));
        assertEquals("gold", loaded.options().get("tier"));
        assertEquals("paimon", loaded.options().get("owner"));
    }

    @Test
    void databaseDropCascadesToContainedObjects() {
        String database = "it_cascade";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "t1"), ordersSchema()));

        databaseService.drop(PREFIX, database);
        ApiException missing = assertThrows(ApiException.class, () -> databaseService.get(PREFIX, database));
        assertEquals(404, missing.getStatus());
        assertFalse(databaseService.list(PREFIX, null, null, null, null).databases().contains(database));
    }

    /**
     * 表重命名只改目录里的名称，不动数据位置（README 第 9 节第 5 条，与 Paimon 服务端一致）。
     *
     * <p>控制台的「重命名」按钮走的就是这条路，因此这里把它依赖的性质全部钉住：
     * 新名字可读、旧名字消失、{@code id} 与 {@code path} 不变（不是建了新表，也没搬动数据）。
     */
    @Test
    void renamingATableChangesTheNameButNotTheDataLocation() {
        String database = "it_rename";
        createDatabase(database);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "before"), ordersSchema()));
        TableDtos.GetTableResponse before = tableService.get(PREFIX, database, "before");
        assertTrue(before.path().endsWith("before"), "表路径应包含表名: " + before.path());

        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "before"),
                new CommonDtos.Identifier(database, "after")));

        TableDtos.GetTableResponse after = tableService.get(PREFIX, database, "after");
        assertEquals("after", after.name());
        assertEquals(before.id(), after.id(), "重命名是改名字，不是建新表");
        assertEquals(before.path(), after.path(), "重命名不该搬动数据");
        assertEquals(List.of("after"), tableService.list(PREFIX, database, null, null, null, null, null).tables());
        assertEquals(404, assertThrows(ApiException.class,
                () -> tableService.get(PREFIX, database, "before")).getStatus());

        // 目标名被占用 -> 409。「改成与原名相同」也落在这一条上，所以控制台在本地就拦掉了，
        // 不发这个注定失败的请求——否则用户点一下确定就收到一个「表已存在」的红报错。
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "taken"), ordersSchema()));
        ApiException conflict = assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "after"),
                        new CommonDtos.Identifier(database, "taken"))));
        assertEquals(409, conflict.getStatus());
        assertEquals(ResourceType.TABLE, conflict.getResourceType());
        assertEquals("taken", conflict.getResourceName());
        assertEquals(409, assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "after"),
                        new CommonDtos.Identifier(database, "after")))).getStatus());

        // 源表不存在 -> 404；请求不完整 -> 400
        assertEquals(404, assertThrows(ApiException.class, () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(new CommonDtos.Identifier(database, "ghost"),
                        new CommonDtos.Identifier(database, "somewhere")))).getStatus());
        assertEquals(400, assertThrows(ApiException.class,
                () -> tableService.rename(PREFIX, null)).getStatus());

        // 跨库重命名：规格允许（控制台没有暴露这个入口），数据位置同样不动
        createDatabase("it_rename_target");
        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(database, "after"),
                new CommonDtos.Identifier("it_rename_target", "moved")));
        TableDtos.GetTableResponse moved = tableService.get(PREFIX, "it_rename_target", "moved");
        assertEquals("moved", moved.name());
        assertEquals(before.path(), moved.path(), "跨库重命名同样不搬数据");
    }

    /**
     * 建表支持四种表格式：Paimon 表（{@code type} 缺席）、{@code format-table}、
     * {@code object-table}、{@code lance-table}。
     *
     * <p>类型由 {@code schema.options.type} 承载（与 Paimon 的 {@code CoreOptions.TYPE} 同一个键），
     * 三种非 Paimon 表在建表时都要补 {@code path}——引擎侧构造它们时只读 options 里的位置，
     * 而客户端不会带这个选项。规则集中在 {@code TableKinds}，这里钉住它对外的表现：
     * 位置补齐、Format 表禁主键、Object 表用固定六列覆盖用户字段、Lance 表保留字段与主键、
     * 以及不认识的类型直接 400（而不是写进库等引擎来炸）。
     */
    @Test
    void createTableWithNonPaimonFormats() {
        String database = "it_table_kinds";
        createDatabase(database);

        // Format 表：位置补齐，字段与 file.format 原样保留，无主键
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "csv_files"),
                tableSchema("format-table", "csv",
                        List.of(field(0, "id", "BIGINT"), field(1, "name", "VARCHAR(20)")))));
        TableDtos.GetTableResponse format = tableService.get(PREFIX, database, "csv_files");
        assertEquals("format-table", format.schema().getOptions().get("type"));
        assertEquals("csv", format.schema().getOptions().get("file.format"));
        assertEquals(format.path(), format.schema().getOptions().get("path"),
                "内部 format 表的位置由目录补，客户端不带 path");
        assertTrue(format.schema().getPrimaryKeys().isEmpty());
        assertEquals(List.of("id", "name"), format.schema().getFields().stream()
                .map(TypeDtos.DataField::getName).toList());

        // 不认识的类型：400。写进库的话，引擎读表时报的是「枚举不认识」，出错现场离原因太远
        ApiException unknown = assertThrows(ApiException.class, () -> tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "weird"),
                        tableSchema("no-such-table", "parquet", List.of(field(0, "id", "BIGINT"))))));
        assertEquals(400, unknown.getStatus());
        assertTrue(unknown.getMessage().contains("no-such-table"));

        // Format 表带主键：400（上游 CatalogUtils 同样禁止，本服务端不经由 AbstractCatalog 建表，得自己拦）
        TypeDtos.Schema withPrimaryKey =
                tableSchema("format-table", "parquet", List.of(field(0, "id", "BIGINT NOT NULL")));
        withPrimaryKey.getPrimaryKeys().add("id");
        assertEquals(400, assertThrows(ApiException.class, () -> tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "csv_pk"), withPrimaryKey)))
                .getStatus());

        // Object 表：不声明列，落库的是固定六列（Paimon 的 ObjectTable.SCHEMA）
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "objects"),
                tableSchema("object-table", null, List.of())));
        TableDtos.GetTableResponse object = tableService.get(PREFIX, database, "objects");
        assertEquals("object-table", object.schema().getOptions().get("type"));
        assertEquals(object.path(), object.schema().getOptions().get("path"));
        assertEquals(List.of("path", "name", "length", "mtime", "atime", "owner"),
                object.schema().getFields().stream().map(TypeDtos.DataField::getName).toList(),
                "Object 表的列是固定的，用户声明的字段被忽略");
        assertTrue(object.schema().getPrimaryKeys().isEmpty());
        assertTrue(object.schema().getPartitionKeys().isEmpty());

        // Lance 表：字段、非空、主键都保留（与 Paimon 表一致），位置同样补齐
        TypeDtos.Schema lance = tableSchema("lance-table", null,
                List.of(field(0, "id", "BIGINT NOT NULL"), field(1, "name", "VARCHAR(20)")));
        lance.getPrimaryKeys().add("id");
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "vectors"), lance));
        TableDtos.GetTableResponse lanceTable = tableService.get(PREFIX, database, "vectors");
        assertEquals("lance-table", lanceTable.schema().getOptions().get("type"));
        assertEquals(lanceTable.path(), lanceTable.schema().getOptions().get("path"));
        assertEquals(List.of("id"), lanceTable.schema().getPrimaryKeys());
        assertEquals("BIGINT NOT NULL", typeOf(lanceTable.schema(), "id"));

        // Paimon 表不受影响：options 里不会被塞进 path
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "plain"), ordersSchema()));
        TableDtos.GetTableResponse plain = tableService.get(PREFIX, database, "plain");
        assertFalse(plain.schema().getOptions().containsKey("path"), "Paimon 表的位置由目录决定，不写进 options");

        // 表类型落到 table_type 列上，listTableDetails 的过滤因此可用
        assertEquals(List.of("csv_files"), tableService
                .listDetails(PREFIX, database, null, null, null, "format-table", null, null).tableDetails().stream()
                .map(TableDtos.GetTableResponse::name).toList());
        assertEquals(List.of("objects"), tableService
                .listDetails(PREFIX, database, null, null, null, "object-table", null, null).tableDetails().stream()
                .map(TableDtos.GetTableResponse::name).toList());
        assertEquals(List.of("vectors"), tableService
                .listDetails(PREFIX, database, null, null, null, "lance-table", null, null).tableDetails().stream()
                .map(TableDtos.GetTableResponse::name).toList());
    }

    /**
     * 非 Paimon 表带 {@code path} 建表：客户端指的那个目录就是表位置，表按外部表登记。
     *
     * <p>为什么非要把客户端的目录写成表位置：Paimon 的 REST 客户端在 load 表时用响应里的
     * 表位置**覆盖** {@code options.path}（{@code RESTCatalog.toTableMetadata} 用的是
     * {@code put}，不是 {@code putIfAbsent}）。只把位置留在 options 里是没用的——引擎读的
     * 仍会是目录按模板分配的那个目录，对象目录里的东西一个也看不到（Spark 侧实测如此）。
     *
     * <p>按外部表登记的三条后果一起钉住：{@code isExternal=true}、不往那个目录写
     * {@code schema-0}、{@code options.path} 与表位置一致。那个目录是用户自己的数据目录，
     * 写元数据进去既污染对象列表，也会在开了 purge-on-drop 时被连目录一起删掉。
     *
     * <p>Paimon 表不适用这条规则：它的位置由目录决定，要指向一份已经存在的 Paimon 数据
     * 应该走 {@code register}（见 {@link TableService#register}）。
     */
    @Test
    void nonPaimonTableHonoursClientPathAsItsLocation() {
        String database = "it_client_path";
        createDatabase(database);
        // 客户端目录必须落在 catalog 的 allowedLocations 内（Paths.requireWithin，越界 400）。
        // 因此取仓库下的一个子目录：被测语义是「客户端给的位置优先于模板分配的位置」，
        // 与「能任意指定到仓库之外」无关——后者是本次去掉的能力。
        String objectDir = catalogService.resolve(PREFIX).getWarehouse().replaceAll("/+$", "")
                + "/object-dir-" + UUID.randomUUID().toString().substring(0, 8);

        TypeDtos.Schema objectSchema = tableSchema("object-table", null, List.of());
        objectSchema.getOptions().put("path", objectDir);
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "images"), objectSchema));

        TableDtos.GetTableResponse object = tableService.get(PREFIX, database, "images");
        assertEquals(objectDir, object.path(), "客户端指的对象目录应当就是表位置");
        assertEquals(objectDir, object.schema().getOptions().get("path"));
        assertTrue(object.isExternal(), "数据目录由客户端给定 → 按外部表登记");
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(objectDir, "schema")),
                "外部表不会往数据目录里写 schema-0");
        assertEquals(List.of("path", "name", "length", "mtime", "atime", "owner"),
                object.schema().getFields().stream().map(TypeDtos.DataField::getName).toList());

        // 位置给空白等于没给：照旧用目录分配的位置，也照旧是内部表（schema-0 会写进去）
        TypeDtos.Schema blankPath = tableSchema("format-table", "csv", List.of(field(0, "id", "BIGINT")));
        blankPath.getOptions().put("path", "   ");
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "blank_path"), blankPath));
        TableDtos.GetTableResponse blank = tableService.get(PREFIX, database, "blank_path");
        assertFalse(blank.isExternal(), "空白 path 不算客户端指定了位置");
        assertTrue(blank.path().endsWith("/" + database + ".db/blank_path"),
                "空白 path 应回落到路径模板，实际: " + blank.path());
        assertEquals(blank.path(), blank.schema().getOptions().get("path"));

        // 越界位置一律 400：非 Paimon 表的 path 是三个面里最容易绕过白名单的入口，
        // 这条断言钉住「TableService.create 真的调了 Paths.requireWithin」这件事本身——
        // 判定规则在 AllowedLocationTests 里，这里验的是接线。
        TypeDtos.Schema outside = tableSchema("object-table", null, List.of());
        outside.getOptions().put("path", "file:///tmp/lakehouse-outside-wh/object-dir");
        ApiException rejected = assertThrows(ApiException.class, () -> tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "outside"), outside)));
        assertEquals(400, rejected.getStatus());
        assertTrue(rejected.getMessage().contains("allowed locations"), rejected.getMessage());
    }

    /**
     * catalog 级的表属性默认值（{@code table-default.*}）。
     *
     * <p>语义照 Paimon 自己的 {@code Catalog.TABLE_DEFAULT_OPTION_PREFIX}：建表时去掉前缀
     * 写进表属性，表级同名参数优先。四条边界逐条断言——它们都是「不写明就会被人改错」的约定：
     * 只对 Paimon 表生效、只在建表时合并（已建成的表不受影响）、{@code type} 不接受默认、
     * 前缀不会原样留在表属性里。
     */
    @Test
    void catalogTableDefaultsApplyToNewPaimonTables() {
        String database = "it_table_defaults";
        createDatabase(database);

        // 配置之前建的表：后来的默认值不该动它
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "before_defaults"), plainSchema(Map.of())));

        Map<String, String> catalogProperties = new LinkedHashMap<>();
        catalogProperties.put("owner", "paimon");
        catalogProperties.put("table-default.deletion-vectors.enabled", "true");
        catalogProperties.put("table-default.bucket", "4");
        catalogProperties.put("table-default.", "没有属性名的项，忽略");
        catalogProperties.put("table-default.type", "object-table");
        catalogManagementService.update(PREFIX,
                new ManagementDtos.UpdateCatalogRequest(null, null, catalogProperties, null));
        try {
            // Paimon 表：默认参数剥掉前缀进表属性；表级 bucket=8 覆盖默认的 4
            tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                    new CommonDtos.Identifier(database, "with_defaults"), plainSchema(Map.of("bucket", "8"))));
            Map<String, String> options = tableService.get(PREFIX, database, "with_defaults").schema().getOptions();
            assertEquals("true", options.get("deletion-vectors.enabled"), "默认参数去掉前缀写进表属性");
            assertEquals("8", options.get("bucket"), "表级参数优先于 catalog 默认值");
            assertFalse(options.containsKey(TableDefaults.PREFIX + "deletion-vectors.enabled"),
                    "带前缀的键不该留在表属性里——表自己不认识它");
            assertFalse(options.containsKey("type"), "type 由建表请求决定，不接受 table-default.type");

            // 之前建的表不受影响：默认值只在建表时合并，改一张已存在的表属于 alter 的范畴
            Map<String, String> before =
                    tableService.get(PREFIX, database, "before_defaults").schema().getOptions();
            assertFalse(before.containsKey("deletion-vectors.enabled"));

            // 非 Paimon 表不合并：这些键描述的是 Paimon 表的属性，Format 表的 options 不读它们
            tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                    new CommonDtos.Identifier(database, "csv_with_defaults"),
                    tableSchema("format-table", "csv", List.of(field(0, "id", "BIGINT")))));
            Map<String, String> formatOptions =
                    tableService.get(PREFIX, database, "csv_with_defaults").schema().getOptions();
            assertEquals("format-table", formatOptions.get("type"));
            assertEquals("csv", formatOptions.get("file.format"));
            assertFalse(formatOptions.containsKey("deletion-vectors.enabled"));
            assertFalse(formatOptions.containsKey("bucket"));
        } finally {
            // 默认参数是 catalog 的属性，留着会影响同类里的其它用例
            catalogManagementService.update(PREFIX,
                    new ManagementDtos.UpdateCatalogRequest(null, null, Map.of(), null));
        }
    }

    // ------------------------------------------------------------------ 辅助

    private void createDatabase(String name) {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(name)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(name, Map.of("owner", "paimon")));
        }
    }

    private PartitionDtos.Partition firstPartition(Map<String, String> spec) {
        String dt = spec.get("dt");
        return partitionService.list(PREFIX, "it_partition", "events", null, null, null, null, null).partitions().stream()
                .filter(item -> dt.equals(String.valueOf(item.spec().get("dt"))))
                .findFirst()
                .orElseThrow();
    }

    private static TypeDtos.DataField fieldOf(TypeDtos.Schema schema, String name) {
        return schema.getFields().stream()
                .filter(field -> name.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static String typeOf(TypeDtos.Schema schema, String name) {
        return String.valueOf(fieldOf(schema, name).getType());
    }

    /** 以 {@code key, value, key, value, ...} 形式构造变更项；首个键必须是 {@code action}。 */
    private static Map<String, Object> change(Object... keyValues) {
        Map<String, Object> change = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            change.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return change;
    }

    /** 一张最简 Paimon 表（只有一列主键），可带若干表属性；用于「默认参数」这类只关心 options 的用例。 */
    private static TypeDtos.Schema plainSchema(Map<String, String> options) {
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getPrimaryKeys().add("id");
        schema.getOptions().putAll(options);
        return schema;
    }

    private static TypeDtos.Schema ordersSchema() {        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(20)"));
        schema.getFields().add(field(2, "dt", "VARCHAR(10)"));
        schema.getPrimaryKeys().add("id");
        schema.getPartitionKeys().add("dt");
        schema.getOptions().put("bucket", "1");
        schema.setComment("orders");
        return schema;
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }

    /**
     * 非 Paimon 表格式的 schema：{@code options.type} 指明格式，{@code file.format} 只有 Format 表要。
     *
     * <p>刻意不给 {@code path}——建表请求里客户端本就不带它，由服务端按表位置补上。
     */
    private static TypeDtos.Schema tableSchema(String type, String fileFormat,
                                              List<TypeDtos.DataField> fields) {
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().addAll(fields);
        schema.getOptions().put("type", type);
        if (fileFormat != null) {
            schema.getOptions().put("file.format", fileFormat);
        }
        return schema;
    }
}
