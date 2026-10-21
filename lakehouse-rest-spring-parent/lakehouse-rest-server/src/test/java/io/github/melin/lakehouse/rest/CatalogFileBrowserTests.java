package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.service.CatalogFileService;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.service.StorageFileIoFactory;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * catalog 仓库文件浏览（{@code GET /api/console/v1/catalogs/{name}/files}）的集成测试。
 *
 * <p>被测的是一条**只读但直连存储**的链路：控制台点开一个目录，服务端按 catalog 的
 * {@code warehouse} 拼出绝对路径、用与引擎同一份凭据列一层目录、把结果折成相对路径
 * 返回。它要回答的问题只能由它回答——「库里说的」与「盘上写的」对不对得上
 * （表建没建出 schema 文件、有没有残留目录）。用本地文件系统（Paimon 内置
 * {@code LocalFileIO}）就能测到位，不需要对象存储、插件与网络。
 *
 * <p>四类断言各自堵一种错法：
 *
 * <ul>
 *   <li><b>下钻</b>：根 → 库目录 → 表目录逐层下去都能列出，且路径是相对仓库根的
 *       （绝对路径一旦泄进响应，控制台就得知道 scheme 与桶名才能继续下钻）。
 *   <li><b>越界</b>：{@code ..}、绝对路径、带 scheme 的地址都必须被拒。这是本端点
 *       唯一的真实安全点——本服务端能访问的位置远不止这个仓库，而授权面只到
 *       「认证过的控制台用户」。
 *   <li><b>不存在与不是目录</b>：分别回 404 与 400，而不是 500。运维看状态码就知道
 *       该改路径还是该查存储。
 *   <li><b>上限与开关</b>：条目截断要带标记（否则「少了一些文件」会被读成「文件不存在」），
 *       关掉能力时要说清是哪个配置项。
 * </ul>
 *
 * <p>后两类里的「上限」与「开关」不用 {@code @SpringBootTest(properties=...)} 各起一个
 * 上下文：那两处纯属配置驱动的分支，用同一份依赖直接构造一个带不同配置的服务实例即可，
 * 少两个 Spring 上下文（每个都要起 H2 与 JPA）。
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogFileBrowserTests {

    private static final String PREFIX = "paimon";

    @Autowired
    private CatalogFileService catalogFileService;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private CatalogRepository catalogRepository;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private DatabaseRepository databaseRepository;
    @Autowired
    private StorageFileIoFactory fileIoFactory;

    private String database;

    @BeforeEach
    void createDatabase() {
        database = "fb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(database, Map.of("owner", "paimon")));
    }

    /**
     * 删掉本用例建的表、库与它们的目录。
     *
     * <p>删目录是测试自己的清理，不是被测行为：{@code purge-on-drop} 默认关闭
     * （删数据不可逆，默认值不给它会删数据的取值），所以 {@code drop} 之后目录仍在原处。
     * 不清理的话，下一次跑会在仓库根下堆出几十个 {@code fb_*.db}，
     * 而这个类的断言恰恰是「根目录里有什么」。
     */
    @AfterEach
    void dropDatabase() throws IOException {
        if (database == null || database.isBlank()) {
            return;
        }
        try {
            for (String table : tableService.list(PREFIX, database, null, null, null, null, null).tables()) {
                try {
                    tableService.drop(PREFIX, database, table);
                } catch (RuntimeException ignored) {
                    // 用例自己已经删掉了，这里只为兜底
                }
            }
            databaseService.drop(PREFIX, database);
        } catch (RuntimeException ignored) {
            // 库可能已被用例删掉；清理不该让用例失败
        }
        deleteRecursively(databaseDirectory());
    }

    // ------------------------------------------------------------------ 下钻

    @Test
    void listsTheWarehouseRootAndDrillsDownIntoATableDirectory() {
        // 建一张表：仓库里因此出现 <库>.db/<表>/ 与其中的 schema/ 目录
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));

        ConsoleDtos.CatalogFileListing root = catalogFileService.list(PREFIX, null);
        assertEquals(PREFIX, root.catalog());
        assertEquals("", root.path(), "仓库根的相对路径是空串，不是 null 或 /");
        assertEquals(catalogWarehouse(), root.warehouse(), "响应要带一次仓库位置，供界面显示「这是哪儿」");
        assertFalse(root.truncated());

        ConsoleDtos.CatalogFileEntry databaseEntry = entry(root, database + ".db");
        assertTrue(databaseEntry.directory(), "库在仓库里是 <库名>.db 目录");
        assertEquals(database + ".db", databaseEntry.path(), "根下的条目路径就是它自己的名字");
        assertEquals(0L, databaseEntry.size(), "目录没有长度语义，填 0 并由界面显示占位符");

        // 下钻一层：库目录里是表目录
        ConsoleDtos.CatalogFileListing inDatabase = catalogFileService.list(PREFIX, database + ".db");
        assertEquals(database + ".db", inDatabase.path());
        ConsoleDtos.CatalogFileEntry tableEntry = entry(inDatabase, "orders");
        assertTrue(tableEntry.directory());
        assertEquals(database + ".db/orders", tableEntry.path(),
                "条目路径是相对仓库根的，控制台原样回传就能继续下钻");

        // 再下钻一层：表目录里是 Paimon 自己写下的目录
        ConsoleDtos.CatalogFileListing inTable = catalogFileService.list(PREFIX, database + ".db/orders");
        assertTrue(inTable.entries().stream().anyMatch(item -> item.name().equals("schema") && item.directory()),
                "服务端物化 schema 之后，表目录里必须能看到 schema/；看不到就说明物化没生效");
        ConsoleDtos.CatalogFileEntry schemaEntry = entry(inTable, "schema");
        ConsoleDtos.CatalogFileListing inSchema = catalogFileService.list(PREFIX, schemaEntry.path());
        ConsoleDtos.CatalogFileEntry schemaFile = entry(inSchema, "schema-0");
        assertFalse(schemaFile.directory());
        assertTrue(schemaFile.size() > 0, "schema 文件有内容，大小应该读得出来");
        assertTrue(schemaFile.modificationTime() > 0, "修改时间要能读出来，界面按它排序排查最近改动");
    }

    @Test
    void entriesAreSortedWithDirectoriesFirst() throws IOException {
        Path databaseDirectory = databaseDirectory();
        // 建库只在元数据里登记，不产生物理目录（目录由建表写 schema 时才出现），
        // 因此这里自己把它建出来
        Files.createDirectories(databaseDirectory);
        // 造两个文件与一个子目录：目录必须排在前面，同类按名称升序（大小写不敏感）
        Files.writeString(databaseDirectory.resolve("b.txt"), "b");
        Files.writeString(databaseDirectory.resolve("A.txt"), "a");
        Files.createDirectories(databaseDirectory.resolve("z-sub"));

        List<ConsoleDtos.CatalogFileEntry> entries =
                catalogFileService.list(PREFIX, database + ".db").entries();
        List<String> names = entries.stream().map(ConsoleDtos.CatalogFileEntry::name).toList();

        assertEquals(List.of("z-sub", "A.txt", "b.txt"), names,
                "目录在前、同类按名称升序：被上限截断时至少能看到全部目录");
    }

    @Test
    void redundantSeparatorsAndDotSegmentsAreNormalised() throws IOException {
        Files.createDirectories(databaseDirectory());
        // `//` 与 `/./` 是同一个位置，归一化而不是报错——界面上一个手抖的 URL
        // 不该得到一句看不懂的 400
        ConsoleDtos.CatalogFileListing listing =
                catalogFileService.list(PREFIX, database + ".db//./");
        assertEquals(database + ".db", listing.path());
    }

    // ------------------------------------------------------------------ 越界与错误

    @Test
    void parentReferencesAreRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, "../" + database + ".db"));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("parent reference"), error.getMessage());

        // 藏在中间也算：`a/../../b` 与开头的 `..` 是同一件事
        assertThrows(ApiException.class, () -> catalogFileService.list(PREFIX, database + ".db/../../etc"));
    }

    @Test
    void absolutePathsAndSchemesAreRejected() {
        ApiException absolute = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, "/etc"));
        assertEquals(400, absolute.getStatus());

        ApiException withScheme = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, "file:///etc"));
        assertEquals(400, withScheme.getStatus());
        assertTrue(withScheme.getMessage().contains("relative"), withScheme.getMessage());

        // 另一个桶也不行：位置由 catalog 的 warehouse 决定，不由请求决定
        assertThrows(ApiException.class, () -> catalogFileService.list(PREFIX, "s3://other-bucket/x"));
    }

    @Test
    void missingDirectoryIsNotFoundAndAFileIsNotADirectory() throws IOException {
        ApiException missing = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, database + ".db/not-there"));
        assertEquals(404, missing.getStatus(), "不存在的目录是 404，不是 500：调用方据此改路径");

        Files.createDirectories(databaseDirectory());
        Files.writeString(databaseDirectory().resolve("plain.txt"), "x");
        ApiException file = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, database + ".db/plain.txt"));
        assertEquals(400, file.getStatus(), "对着文件列目录是请求形状错了，回 400");
    }

    @Test
    void unknownCatalogIsNotFound() {
        ApiException error = assertThrows(ApiException.class,
                () -> catalogFileService.list("no-such-catalog-" + database, null));
        assertEquals(404, error.getStatus());
    }

    // ------------------------------------------------------------------ 表目录为根

    @Test
    void tableListingIsRootedAtTheTableDirectory() {
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        TableDtos.GetTableResponse detail = tableService.get(PREFIX, database, "orders");

        ConsoleDtos.TableFileListing root = catalogFileService.listTable(detail.id(), null);
        assertEquals(PREFIX, root.catalog());
        assertEquals(database, root.database(), "库名随响应带回去，界面才不用自己查");
        assertEquals("orders", root.table());
        assertEquals(detail.path(), root.root(), "根是表元数据里的 path，界面显示「这是哪儿」");
        assertEquals("", root.path(), "表目录本身的相对路径是空串");
        // 建表即物化 schema，表目录里因此有 schema/——这正好是这页要核对的东西
        assertTrue(root.entries().stream()
                        .anyMatch(item -> item.name().equals("schema") && item.directory()),
                "表目录里必须能看到 schema/，看不到说明物化没生效");

        // 下钻一层：条目路径相对**表目录**，不带库名前缀——控制台原样回传就能继续下钻
        ConsoleDtos.TableFileListing inSchema = catalogFileService.listTable(detail.id(), "schema");
        assertEquals("schema", inSchema.path());
        ConsoleDtos.CatalogFileEntry schemaFile = entry(inSchema.entries(), "schema-0");
        assertEquals("schema/schema-0", schemaFile.path(),
                "条目路径相对表目录而不是仓库根：与 catalog 端点的约定一致，只是换了根");
        assertFalse(schemaFile.directory());
        assertTrue(schemaFile.size() > 0, "schema 文件有内容，大小应该读得出来");
    }

    @Test
    void tableListingCannotEscapeTheTableDirectory() {
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String tableId = tableService.get(PREFIX, database, "orders").id();

        // `..` 被拒：以表目录为根时这个端点天生够不到库目录与仓库根，这是它的安全边界
        ApiException up = assertThrows(ApiException.class,
                () -> catalogFileService.listTable(tableId, "../" + database + ".db"));
        assertEquals(400, up.getStatus());
        assertTrue(up.getMessage().contains("parent reference"), up.getMessage());

        // 绝对路径与带 scheme 的地址同样被拒：位置由表元数据决定，不由请求决定
        assertThrows(ApiException.class, () -> catalogFileService.listTable(tableId, "file:///etc"));

        // 表目录下不存在的子目录是 404
        ApiException missing = assertThrows(ApiException.class,
                () -> catalogFileService.listTable(tableId, "not-there"));
        assertEquals(404, missing.getStatus());
    }

    @Test
    void unknownTableIsNotFound() {
        ApiException error = assertThrows(ApiException.class,
                () -> catalogFileService.listTable("no-such-table-" + database, null));
        assertEquals(404, error.getStatus(), "未知表与未知 catalog 一样是 404，不是 500");
    }

    // ------------------------------------------------------------------ 下载

    @Test
    void downloadOpensTheFileBytesAndRejectsDirectories() throws IOException {
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String schemaFile = database + ".db/orders/schema/schema-0";

        CatalogFileService.FileDownload download = catalogFileService.openFile(PREFIX, schemaFile);
        assertEquals("schema-0", download.name(), "文件名取路径最后一段，进 Content-Disposition");
        assertTrue(download.size() > 0);
        // 流里的字节数必须与元数据长度一致：Content-Length 就是按它填的，差了会截断或挂起
        assertEquals(download.size(), download.stream().transferTo(OutputStream.nullOutputStream()),
                "流读完的字节数要与元数据长度一致");

        // 对目录下载是 400：目录用列表去看
        ApiException directory = assertThrows(ApiException.class,
                () -> catalogFileService.openFile(PREFIX, database + ".db/orders/schema"));
        assertEquals(400, directory.getStatus());

        // 不存在是 404，与列举同一说法
        ApiException missing = assertThrows(ApiException.class,
                () -> catalogFileService.openFile(PREFIX, database + ".db/orders/schema/not-there"));
        assertEquals(404, missing.getStatus());

        // 仓库根本身是目录：下载必须给到文件
        ApiException root = assertThrows(ApiException.class, () -> catalogFileService.openFile(PREFIX, ""));
        assertEquals(400, root.getStatus());
    }

    @Test
    void tableListingFiltersByPrefixToo() {
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String tableId = tableService.get(PREFIX, database, "orders").id();

        ConsoleDtos.TableFileListing filtered = catalogFileService.listTable(tableId, null, "sch");
        assertEquals("sch", filtered.prefix(), "表目录这条端点的过滤语义与仓库那条一致");
        assertEquals(List.of("schema"),
                filtered.entries().stream().map(ConsoleDtos.CatalogFileEntry::name).toList());

        // 匹配不到就是空列表（不是 404）：过滤是「当前这层里没有符合的」，不是「目录不在」
        ConsoleDtos.TableFileListing empty = catalogFileService.listTable(tableId, null, "no-such-prefix");
        assertTrue(empty.entries().isEmpty());
        assertEquals(0, empty.total());
    }

    @Test
    void tableDownloadIsRootedAtTheTableDirectory() throws IOException {        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String tableId = tableService.get(PREFIX, database, "orders").id();

        CatalogFileService.FileDownload download = catalogFileService.openTableFile(tableId, "schema/schema-0");
        assertEquals("schema-0", download.name());
        assertTrue(download.size() > 0);
        download.stream().close();

        ApiException directory = assertThrows(ApiException.class,
                () -> catalogFileService.openTableFile(tableId, "schema"));
        assertEquals(400, directory.getStatus());

        ApiException missing = assertThrows(ApiException.class,
                () -> catalogFileService.openTableFile(tableId, "not-there"));
        assertEquals(404, missing.getStatus());
    }

    @Test
    void disabledBrowserAlsoDisablesDownload() {
        CatalogFileService disabled = serviceWith(browser -> browser.setEnabled(false));

        ApiException error = assertThrows(ApiException.class,
                () -> disabled.openFile(PREFIX, "some-file.txt"));
        assertEquals(501, error.getStatus(), "下载与列举共用同一个开关，关掉是一起关");
    }

    @Test
    void catalogWithoutWarehouseExplainsWhatToSet() {
        catalogRepository.findByPrefix(PREFIX).ifPresent(catalog -> {
            String warehouse = catalog.getWarehouse();
            catalog.setWarehouse(null);
            catalogRepository.saveAndFlush(catalog);
            try {
                ApiException error = assertThrows(ApiException.class,
                        () -> catalogFileService.list(PREFIX, null));
                assertEquals(400, error.getStatus());
                assertTrue(error.getMessage().contains("warehouse"), error.getMessage());
            } finally {
                catalog.setWarehouse(warehouse);
                catalogRepository.saveAndFlush(catalog);
            }
        });
    }

    // ------------------------------------------------------------------ 上限与开关

    @Test
    void entriesBeyondTheLimitAreCutWithATruncatedFlag() throws IOException {
        Path databaseDirectory = databaseDirectory();
        Files.createDirectories(databaseDirectory);
        for (int index = 0; index < 5; index++) {
            Files.writeString(databaseDirectory.resolve("f" + index + ".txt"), "x");
        }
        CatalogFileService limited = serviceWith(browser -> browser.setMaxEntries(2));

        ConsoleDtos.CatalogFileListing listing = limited.list(PREFIX, database + ".db");
        assertEquals(2, listing.entries().size(), "返回前 N 条而不是报错：列表页的价值在能下钻");
        assertEquals(5, listing.total(), "total 是发现的总数，界面据此说明「只显示了前 N 条」");
        assertTrue(listing.truncated());
        assertEquals(2, listing.maxEntries());
    }

    // ------------------------------------------------------------------ 名称前缀过滤

    @Test
    void namePrefixFiltersEntriesAndEchoesBack() throws IOException {
        Path databaseDirectory = databaseDirectory();
        Files.createDirectories(databaseDirectory);
        for (String name : List.of("part-0001.parquet", "part-0002.parquet", "bucket-0", "manifest-avro")) {
            Files.writeString(databaseDirectory.resolve(name), "x");
        }

        ConsoleDtos.CatalogFileListing filtered = catalogFileService.list(PREFIX, database + ".db", "part-");
        assertEquals("part-", filtered.prefix(), "过滤条件回显，界面据此确认「看的是过滤后的结果」");
        assertEquals(List.of("part-0001.parquet", "part-0002.parquet"),
                filtered.entries().stream().map(ConsoleDtos.CatalogFileEntry::name).toList());
        assertEquals(2, filtered.total(), "total 是过滤后的条数，不是目录里的总条数");
        assertFalse(filtered.truncated());

        // 不过滤时回显空串而不是 null：界面统一按字符串比较
        assertEquals("", catalogFileService.list(PREFIX, database + ".db", null).prefix(),
                "没传 prefix 时回显空串");
        assertEquals("", catalogFileService.list(PREFIX, database + ".db", "  ").prefix(),
                "只有空白也当没过滤");
        assertEquals(4, catalogFileService.list(PREFIX, database + ".db", null).total());
    }

    @Test
    void namePrefixAppliesBeforeTheLimit() throws IOException {
        Path databaseDirectory = databaseDirectory();
        Files.createDirectories(databaseDirectory);
        for (int index = 0; index < 5; index++) {
            Files.writeString(databaseDirectory.resolve("keep-" + index + ".txt"), "x");
        }
        for (int index = 0; index < 5; index++) {
            Files.writeString(databaseDirectory.resolve("drop-" + index + ".txt"), "x");
        }
        CatalogFileService limited = serviceWith(browser -> browser.setMaxEntries(3));

        // 上限是对**过滤后**的结果截断：先过滤，剩下的 5 条里取前 3 条
        ConsoleDtos.CatalogFileListing listing = limited.list(PREFIX, database + ".db", "keep-");
        assertEquals(3, listing.entries().size());
        assertEquals(5, listing.total(), "total 是过滤后的总数：界面据此说明「只显示了前 3 条（共 5 条）」");
        assertTrue(listing.truncated());
    }

    @Test
    void namePrefixIsRootedInTheCurrentDirectoryOnly() throws IOException {
        Path databaseDirectory = databaseDirectory();
        Files.createDirectories(databaseDirectory.resolve("part-sub"));
        Files.writeString(databaseDirectory.resolve("part-sub").resolve("inner.txt"), "x");
        Files.writeString(databaseDirectory.resolve("part-top.txt"), "x");

        // 前缀只与**本层**条目名比较，不下钻：与「按路径前缀跨层过滤」是两件事
        ConsoleDtos.CatalogFileListing listing = catalogFileService.list(PREFIX, database + ".db", "part-");
        assertEquals(List.of("part-sub", "part-top.txt"),
                listing.entries().stream().map(ConsoleDtos.CatalogFileEntry::name).toList(),
                "目录也参与名称前缀过滤，且仍排在文件前面");
        assertTrue(entry(listing.entries(), "part-sub").directory());
    }

    @Test
    void namePrefixWithASeparatorIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> catalogFileService.list(PREFIX, database + ".db", "part-0/"));
        assertEquals(400, error.getStatus(),
                "含分隔符的前缀永远匹配不到条目（条目名不含 /）：直说比给个静默空结果好");
        assertTrue(error.getMessage().contains("path"), error.getMessage());
    }

    @Test
    void disabledBrowserSaysWhichPropertyTurnsItBackOn() {
        CatalogFileService disabled = serviceWith(browser -> browser.setEnabled(false));

        ApiException error = assertThrows(ApiException.class, () -> disabled.list(PREFIX, null));
        assertEquals(501, error.getStatus(), "能力关掉是「本部署不提供」，不是「你请求错了」");
        assertTrue(error.getMessage().contains("lakehouse.rest.file-browser.enabled"),
                "错误里要写出配置项：否则现象只是「那个页签永远报错」，看不出是部署刻意关的");
    }

    // ------------------------------------------------------------------ 辅助

    /** 用同一份依赖 + 一份改过的配置构造服务实例，避免为此新起一个 Spring 上下文。 */
    private CatalogFileService serviceWith(java.util.function.Consumer<RestServerProperties.FileBrowser> customise) {
        RestServerProperties custom = new RestServerProperties();
        customise.accept(custom.getFileBrowser());
        return new CatalogFileService(catalogRepository, tableRepository, databaseRepository,
                fileIoFactory, custom);
    }

    private static ConsoleDtos.CatalogFileEntry entry(ConsoleDtos.CatalogFileListing listing, String name) {
        return entry(listing.entries(), name);
    }

    private static ConsoleDtos.CatalogFileEntry entry(List<ConsoleDtos.CatalogFileEntry> entries, String name) {
        return entries.stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("目录里没有 " + name + "，实际是 "
                        + entries.stream().map(ConsoleDtos.CatalogFileEntry::name).toList()));
    }

    private String catalogWarehouse() {
        return catalogService.resolve(PREFIX).getWarehouse();
    }

    private Path databaseDirectory() {
        return Paths.get(URI.create(catalogWarehouse() + "/" + database + ".db"));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 与 {@code PaimonRestCatalogApiTests.ordersSchema()} 保持同一份形状。 */
    private static TypeDtos.Schema ordersSchema() {
        TypeDtos.Schema schema = new TypeDtos.Schema();
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
}
