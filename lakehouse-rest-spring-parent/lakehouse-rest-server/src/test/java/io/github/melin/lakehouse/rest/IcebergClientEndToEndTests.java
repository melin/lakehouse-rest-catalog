package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 用 **Iceberg 自己的 Java 客户端**（{@code RESTCatalog}）走一遍本服务端，
 * 全程真 HTTP。
 *
 * <p><b>为什么 MockMvc 那一层不够。</b>{@link IcebergRestCatalogApiTests} 验的是
 * 「服务端在收到某个请求时回了什么」，它自己拼 URL、自己读 JSON——
 * 于是它对**两件最关键的事**天然无感：
 *
 * <ul>
 *   <li><b>能力清单的协商</b>。客户端的每一次写入前都会
 *       {@code Endpoint.check(endpoints, ENDPOINT)}，而 {@code endpoints} 就取自
 *       {@code GET /v1/config} 里下发的那份清单（清单为空时才退回「假定全支持」）。
 *       也就是说：清单**少了**一条，对应的操作会在客户端本地就被拒掉，
 *       请求根本发不出来；清单**多了**一条、而服务端其实没有，客户端就会真的发出去、
 *       然后拿到 404。MockMvc 不看这份清单，两个方向它都发现不了。</li>
 *   <li><b>错误模型的可用性</b>。服务端回嵌了一层的 {@code {"error":{...}}} 之后，
 *       客户端真的能把它翻成 {@code NoSuchTableException} 吗？这一层才回答得了——
 *       MockMvc 只断言 JSON 的形状，形状对而语义没接上（例如 {@code type} 写成上游没有的
 *       名字）在那条断言里是绿的，客户端却会退化成通用的 {@code RESTException}。</li>
 * </ul>
 *
 * <p>另外它顺带压住了「更新代数真的能跑」：{@code table.updateSchema()...commit()}
 * 会由客户端自己生成 {@code requirements}（{@code assert-table-uuid} 等）与
 * {@code updates}（{@code add-schema} / {@code set-current-schema}），
 * 服务端要把它们解出来、校验、应用、落库，再把新一代元数据回给客户端解析。
 * 这条链上任何一环错位，客户端都会在**解析响应**时炸——那是手工拼 JSON 的测试
 * 最不容易覆盖的部分。
 *
 * <p>建 catalog 走管理 API 的真 HTTP（JDK 的 {@code HttpClient}），
 * 因此本类不依赖任何测试专用的 HTTP 客户端封装，也不依赖 {@code MockMvc}。
 *
 * <p>用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-iceberg-client;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class IcebergClientEndToEndTests {

    private static final String M = "/api/management/v1";

    /** 本类自己的 catalog。名字即 {@code /v1/{prefix}} 里的 prefix。 */
    private static final String CATALOG = "iceberg-e2e";

    private static final String NS = "ice";
    private static final String TABLE = "orders";

    /**
     * 表 schema 直接用 Iceberg 自己的类型系统构造，不再拼 JSON。
     *
     * <p>这与 MockMvc 那边刻意相反：那边手写字符串是为了把「报文长什么样」钉死；
     * 这边让客户端自己序列化，测的才是「两端对同一份 schema 的理解是否一致」。
     */
    private static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "data", Types.StringType.get()));

    /**
     * 仓库根：本 catalog 的 {@code allowedLocations[0]}。
     *
     * <p>用 {@code java.io.tmpdir} 而不是写死 {@code /tmp}：仓库路径要随机器变化。
     */
    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/iceberg-e2e";

    /** 建 catalog 是「一次就好」的副作用，用旗标而不是 {@code @BeforeAll}，见 {@link #openCatalogOnce()}。 */
    private static boolean catalogCreated;

    @LocalServerPort
    private int port;

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * 建一个 Iceberg catalog，只做一次。
     *
     * <p><b>为什么不是 {@code @BeforeAll}。</b>{@code @BeforeAll} 必须是静态方法，
     * 而端口是实例字段（{@code @LocalServerPort} 由 Spring 在实例创建后注入）——
     * 静态方法先于实例存在，那时端口还是 0，拿到的地址是死的。
     * 这是 JUnit 与 Spring 的一个固有摩擦：改成 {@code Lifecycle.PER_CLASS}
     * 能让 {@code @BeforeAll} 变成实例方法，但那要把整个类的语义都改成单实例，
     * 代价远大于收益。一个幂等旗标就够，而且顺带让每个用例都不依赖执行顺序。
     *
     * <p>客户端的 {@code warehouse} 传的是 catalog 的 **prefix**（不是仓库路径）——
     * 与 Paimon 侧同一套约定：客户端先请求 {@code GET /v1/config?warehouse=<prefix>}，
     * 服务端用它换回 {@code defaults.prefix} 与 {@code defaults.warehouse}，
     * 之后所有路径以 prefix 开头。真正的仓库路径由服务端按 catalog 配置给出，
     * 客户端不需要知道。
     */
    @BeforeEach
    void openCatalogOnce() throws Exception {
        if (catalogCreated) {
            return;
        }
        String body = """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"ICEBERG","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(CATALOG, WAREHOUSE);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + M + "/catalogs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        catalogCreated = true;
    }

    /**
     * 客户端实例。
     *
     * <p>刻意不调 {@code setConf}，也刻意指定 {@code io-impl}：
     *
     * <ul>
     *   <li>{@code setConf} 不必要——{@code RESTCatalog.initialize} 并不读它，
     *       只有 FileIO 的构造会用到。</li>
     *   <li>{@code io-impl} 必须指定，否则客户端会装默认的 {@code ResolvingFileIO}，
     *       而那个类声明了 {@code implements HadoopConfigurable}，**类加载**阶段就要
     *       {@code org.apache.hadoop.conf.Configurable}——本仓库刻意不让 Hadoop 客户端栈
     *       进服务端类路径（见父 POM 对 {@code paimon-format} 的排除）。
     *       这是一个**客户端环境**上的要求（Spark / Flink 自带 Hadoop），
     *       与服务端的协议实现无关，所以用一个测试桩顶上，理由见
     *       {@link io.github.melin.lakehouse.rest.support.TestOnlyFileIo}。</li>
     * </ul>
     *
     * <p>{@code warehouse} 传 catalog 名（即 prefix），见 {@link #openCatalogOnce()}。
     * 关掉客户端的元数据缓存：本类要的就是「每一次都真的发请求」，
     * 否则 {@code loadTable} 可能命中本地缓存、把服务端的回归藏起来。
     */
    private RESTCatalog client() {
        RESTCatalog catalog = new RESTCatalog();
        catalog.initialize("e2e", Map.of(
                CatalogProperties.URI, base(),
                CatalogProperties.WAREHOUSE_LOCATION, CATALOG,
                CatalogProperties.FILE_IO_IMPL,
                io.github.melin.lakehouse.rest.support.TestOnlyFileIo.class.getName(),
                CatalogProperties.CACHE_ENABLED, "false"));
        return catalog;
    }

    @Test
    void aRealClientWalksNamespacesTablesAndSchemaEvolution() {
        RESTCatalog catalog = client();

        // ---- namespace：建 / 存在性 / 列举 / 读属性，四个端点
        //
        // 属性 map 用可变的 HashMap，不能用 Map.of(...)：上游的
        // CreateNamespaceRequest.Builder.setProperties 会做 props.containsKey(null)
        // 来挡 null 键，而 Java 的不可变 Map（Map.of / Map1 / MapN）对
        // containsKey(null) 是直接抛 NPE 而不是返回 false —— 传 Map.of 就会在
        // 客户端本地炸掉，请求根本发不出来。Spark/Flink 传的都是可变 map，
        // 所以这条坑只有手写客户端的人才会踩到（上游问题，记在这里免得下次再踩）。
        catalog.createNamespace(Namespace.of(NS), new HashMap<>(Map.of("owner", "e2e")));
        assertTrue(catalog.namespaceExists(Namespace.of(NS)));
        assertTrue(catalog.listNamespaces().contains(Namespace.of(NS)));
        assertEquals(Map.of("owner", "e2e"), catalog.loadNamespaceMetadata(Namespace.of(NS)),
                "属性要原样往返：服务端不要替客户端补自己的键");

        // ---- table：建 / 存在性 / 读 / 列举
        Table table = catalog.buildTable(TableIdentifier.of(NS, TABLE), SCHEMA).create();
        assertTrue(catalog.tableExists(TableIdentifier.of(NS, TABLE)));
        assertTrue(catalog.listTables(Namespace.of(NS)).contains(TableIdentifier.of(NS, TABLE)));

        Table loaded = catalog.loadTable(TableIdentifier.of(NS, TABLE));
        assertEquals(SCHEMA.asStruct(), loaded.schema().asStruct(),
                "客户端读回的 schema 要与建表时提交的逐字段一致");
        assertEquals(table.location(), loaded.location(),
                "两次拿到的位置必须一致：建表响应与载入响应不能各推一套");
        assertTrue(loaded.location().startsWith(WAREHOUSE + "/" + NS + ".db/" + TABLE),
                "表位置由服务端按 path-template 推出来: " + loaded.location());

        // ---- 更新代数：客户端生成 requirements + updates，服务端校验、应用、落库
        loaded.updateSchema().addColumn("extra", Types.StringType.get()).commit();
        Table evolved = catalog.loadTable(TableIdentifier.of(NS, TABLE));
        assertNotNull(evolved.schema().findField("extra"),
                "add-schema 之后客户端要能读到新列——这条链上服务端返回的元数据必须能被客户端解析");
        assertEquals(3, evolved.schema().columns().size());

        // ---- rename：走两条同路径端点里 core 分派的那一条（Iceberg 的成功码是 204）
        String locationBefore = evolved.location();
        catalog.renameTable(TableIdentifier.of(NS, TABLE), TableIdentifier.of(NS, TABLE + "_v2"));
        assertTrue(catalog.tableExists(TableIdentifier.of(NS, TABLE + "_v2")));
        assertEquals(locationBefore, catalog.loadTable(TableIdentifier.of(NS, TABLE + "_v2")).location(),
                "rename 只改标识，不动数据位置");

        // ---- 非空的 namespace 不能被删：规格要求 409，客户端要认出这个语义
        assertThrows(NamespaceNotEmptyException.class,
                () -> catalog.dropNamespace(Namespace.of(NS)),
                "非空 namespace 必须 409 NamespaceNotEmptyException，而不是静默级联删表");

        // ---- 删表、再删 namespace
        assertTrue(catalog.dropTable(TableIdentifier.of(NS, TABLE + "_v2")));
        catalog.dropNamespace(Namespace.of(NS));
        assertTrue(!catalog.namespaceExists(Namespace.of(NS)));
    }

    @Test
    void theNestedErrorEnvelopeIsUnderstoodByTheClient() {
        RESTCatalog catalog = client();
        catalog.createNamespace(Namespace.of("e2e_errors"), new HashMap<>());

        // 404 的三种细分必须各自翻成客户端认的异常类型——服务端那边的 type 字符串
        // 就取自这几个类的类名，写错了这里会退化成通用的 RESTException 而不是具体异常
        assertThrows(NoSuchNamespaceException.class,
                () -> catalog.loadNamespaceMetadata(Namespace.of("e2e_nowhere")));
        assertThrows(NoSuchTableException.class,
                () -> catalog.loadTable(TableIdentifier.of("e2e_errors", "nowhere")));

        // 409：重名
        assertThrows(AlreadyExistsException.class,
                () -> catalog.createNamespace(Namespace.of("e2e_errors"), new HashMap<>()));

        catalog.dropNamespace(Namespace.of("e2e_errors"));
    }

    /**
     * 能力清单是服务端的**承诺**，客户端据此决定要不要发某个请求。
     *
     * <p>这里直接读原始报文而不是走客户端：客户端只在「清单与它自己要用到的端点不一致」
     * 时才会抱怨。清单里每个字符串都是双向承诺——声明了的（如 {@code transactions/commit}，
     * 二期已落地）客户端就允许用户发起对应请求；这里钉住的是「哪些字符串不许出现」。
     */
    @Test
    void theClientSeesExactlyTheDeclaredCapabilityList() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build().send(
                        HttpRequest.newBuilder(URI.create(base() + "/v1/config?warehouse=" + CATALOG)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        assertTrue(body.contains("\"endpoints\""), "Iceberg 的 /v1/config 必须下发能力清单: " + body);
        for (String unsupported : List.of("/plan", "/tasks", "/sign")) {
            assertTrue(!body.contains(unsupported),
                    unsupported + " 未实现，不能出现在能力清单里: " + body);
        }
    }
}
