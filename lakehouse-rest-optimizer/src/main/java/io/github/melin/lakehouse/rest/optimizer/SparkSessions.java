package io.github.melin.lakehouse.rest.optimizer;

import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark 会话的构建：**优化任务与扫描任务共用这一份**（设计见
 * {@code docs/sensitive-data-design.md} §5.6.5 的「Spark 会话构建：抽公共类，不许复制」）。
 *
 * <p><b>为什么必须是一份。</b>三种格式各有一处「编译器看不见的约定」——
 * Paimon 的 {@code data-token.enabled}、Iceberg 的 {@code X-Iceberg-Access-Delegation}、
 * Lance 的 {@code /lance} + {@code parent} + {@code headers.Authorization}。
 * 这些键写错不会编译失败，只在真机上某一种格式读不到数据。复制一份的代价不是「多两百行」，
 * 而是**下次升级 connector 时分叉**：一份改了、另一份没改，而两者的失败现象完全不同
 * （一个 401、一个「表不存在」）。
 *
 * <p><b>会话按进程建一次。</b>一个 Job 一个 pod，pod 起来之后所有表都在同一个会话里跑——
 * 固定开销（镜像、JVM、SparkContext）与处理几张表无关。
 *
 * <p><b>时区钉 UTC</b>：Iceberg 的 {@code older_than} 是 TIMESTAMP 字面量，Spark 按会话时区
 * 把它折算成时刻。<b>Paimon 不看这个设置</b>（expire 走时长，orphan 按 JVM 默认时区解析），
 * <b>Lance 也不用</b>（{@code before_timestamp_millis} 是 epoch 毫秒）。扫描任务同样钉 UTC：
 * 采样语句里一旦出现时间谓词，时区漂移会让「同一张表两次扫描结果不同」。
 */
final class SparkSessions {

    private static final Logger log = LoggerFactory.getLogger(SparkSessions.class);

    /** Paimon 自己的 Spark 扩展。catalog 初始化会检查它，缺了连库都建不了。 */
    private static final String PAIMON_EXTENSION =
            "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions";

    /**
     * Iceberg 的扩展。{@code CALL} 在 Iceberg 里由这个扩展注册成 procedure，
     * 只把 iceberg-spark-runtime 放进类路径是不够的。
     */
    private static final String ICEBERG_EXTENSION =
            "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions";

    /**
     * Lance 的扩展。它做的是**语法扩展**（{@code OPTIMIZE} / {@code VACUUM} 是 parser 级命令，
     * 不是 procedure），所以这个扩展比另两个更不可省：缺了它，语句会先落到 Spark 自己的
     * {@code SqlBase} 解析器上，报的是 {@code PARSE_SYNTAX_ERROR}——一句话都不提 Lance。
     */
    private static final String LANCE_EXTENSION =
            "org.lance.spark.extensions.LanceSparkSessionExtensions";

    /**
     * Lance 的 catalog 实现类。**对应 connector 0.4.0**：0.0.x 里它是
     * {@code com.lancedb.lance.spark.LanceNamespaceSparkCatalog}，改过包名。
     *
     * <p>类名写错不会被编译发现（它是个字符串），只在建会话时炸，所以钉在这里并写清版本。
     * 镜像里 {@code lance-spark-bundle-3.5_2.12.jar} 的版本必须在 pom 里跟着一起改。
     */
    private static final String LANCE_CATALOG = "org.lance.spark.LanceNamespaceSparkCatalog";

    private SparkSessions() {
    }

    /**
     * 批次 / 扫描所属 catalog 的格式。
     *
     * <p><b>为什么要有这个枚举，而不是继续用 {@code isIceberg(plan)} 这种布尔。</b>
     * 二元判断对第三种格式返回 {@code false}，于是 Lance 批次会安静地走进 Paimon 分支：
     * 会话被配成 {@code org.paimon.spark.SparkCatalog}，去 {@code /v1/{prefix}} 上找表，
     * 而我们的 Lance catalog 在那个前缀下按设计回「表不存在」——失败信息把人指向权限或
     * 元数据，表其实是有的。改成穷尽 {@code switch} 之后，加第四种格式时编译器会把
     * 所有落点指出来。
     *
     * <p>取值与服务端 {@code CatalogFormat} 的 wire 名逐字一致，但**不共享那个类**：
     * 本模块与服务端分属两个依赖世界（另一端是 Spring Boot），为一个三值枚举去引
     * spring-parent 的 core 包，会把 Spring 的传递依赖带进 Spark 容器的类路径。
     */
    enum Format {

        PAIMON, ICEBERG, LANCE;

        /**
         * 解析清单里的格式。
         *
         * <p><b>缺字段回落 Paimon，未知取值失败——这是两件事。</b>缺字段是本特性引入前的
         * 唯一形状（老服务端没有 {@code format} 字段），回落是兼容；给了 {@code LANCE} 却
         * 不认识，说明服务端比任务新，凭猜测挑一个分支只会把排查方向指错。
         */
        static Format parse(String value) {
            if (value == null || value.isBlank()) {
                return PAIMON;
            }
            for (Format format : values()) {
                if (format.name().equalsIgnoreCase(value.trim())) {
                    return format;
                }
            }
            throw new IllegalArgumentException("unknown catalog format in the execution plan: " + value
                    + "; this optimizer only knows " + java.util.Arrays.toString(values())
                    + ", upgrade the optimizer image");
        }
    }

    /**
     * 建会话并按格式注册 catalog。
     *
     * <p>三种格式的差异（写在这里是为了下次只需看这一处）：
     * <ul>
     *   <li>Paimon 用 {@code metastore=rest} + {@code warehouse=prefix}，令牌走
     *       {@code token.provider=bear}；</li>
     *   <li>Iceberg 用 {@code type=rest} + {@code warehouse=prefix}，令牌走
     *       {@code token} 配 {@code rest.auth.type=oauth2}；</li>
     *   <li>Lance 这三件都没有：它的 catalog 是 {@code LanceNamespaceSparkCatalog}，
     *       用 {@code impl=rest} 说「namespace 后端是 REST」，用 {@code parent} 说
     *       「第一级命名空间是 catalog」，令牌塞进 {@code headers.Authorization}</li>
     * </ul>
     *
     * @param appName      出现在 Spark UI / 日志里，取「任务类型 + id」
     * @param catalog      catalog 名（同时是 warehouse 的 prefix）
     * @param format       catalog 的格式
     * @param catalogUrl   数据面地址（服务端根地址，不是管理 API 基址）
     * @param catalogToken 数据面令牌；没开认证的部署传 {@code null}
     */
    static SparkSession open(String appName, String catalog, Format format,
                             String catalogUrl, String catalogToken) {
        SparkSession.Builder builder = SparkSession.builder()
                .appName(appName)
                .config("spark.sql.session.timeZone", "UTC");
        switch (format) {
            case ICEBERG -> icebergCatalog(builder, catalog, catalogUrl, catalogToken);
            case PAIMON -> paimonCatalog(builder, catalog, catalogUrl, catalogToken);
            case LANCE -> lanceCatalog(builder, catalog, catalogUrl, catalogToken);
        }
        SparkSession session = builder.getOrCreate();
        session.sparkContext().setLogLevel("WARN");
        log.info("spark session ready for catalog {} ({})", catalog, format);
        return session;
    }

    private static void icebergCatalog(SparkSession.Builder builder, String catalog,
                                       String catalogUrl, String catalogToken) {
        builder.config("spark.sql.extensions", ICEBERG_EXTENSION)
                .config("spark.sql.catalog." + catalog, "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog." + catalog + ".type", "rest")
                .config("spark.sql.catalog." + catalog + ".uri", catalogUrl)
                // warehouse 填 prefix（catalog 名），与服务端 /v1/config 的约定一致
                .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
                // 批内同一张表会被反复读（procedure 会重新加载元数据），缓存反而会读到旧快照
                .config("spark.sql.catalog." + catalog + ".cache-enabled", "false")
                // 服务端的「不问不给」：带这个头才会在响应里回短期凭据。
                // 无对象存储的部署上它只是一个没有被消费的响应字段。
                .config("spark.sql.catalog." + catalog
                        + ".header.X-Iceberg-Access-Delegation", "vended-credentials");
        if (catalogToken != null) {
            builder.config("spark.sql.catalog." + catalog + ".token", catalogToken)
                    // 显式声明认证方式：只给 token 时客户端会「猜」成 oauth2 并打一条警告
                    .config("spark.sql.catalog." + catalog + ".rest.auth.type", "oauth2");
        } else {
            log.info("no catalog token provided: connecting to {} without data plane credentials,"
                    + " which only works on deployments with authentication disabled", catalogUrl);
        }
    }

    private static void paimonCatalog(SparkSession.Builder builder, String catalog,
                                      String catalogUrl, String catalogToken) {
        builder.config("spark.sql.extensions", PAIMON_EXTENSION)
                .config("spark.sql.catalog." + catalog, "org.apache.paimon.spark.SparkCatalog")
                .config("spark.sql.catalog." + catalog + ".metastore", "rest")
                .config("spark.sql.catalog." + catalog + ".uri", catalogUrl)
                .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
                .config("spark.sql.catalog." + catalog + ".data-token.enabled", "true");
        if (catalogToken != null) {
            builder.config("spark.sql.catalog." + catalog + ".token.provider", "bear")
                    .config("spark.sql.catalog." + catalog + ".token", catalogToken);
        } else {
            log.info("no catalog token provided: connecting to {} without data plane credentials,"
                    + " which only works on deployments with authentication disabled", catalogUrl);
        }
    }

    /**
     * Lance catalog 的会话配置。
     *
     * <p>四件事各自对应的失败形态，写在这里是为了下次只需看这一处：
     *
     * <ol>
     *   <li><b>{@code impl=rest}</b>：不给它，connector 退化成「纯路径模式」（没有 namespace），
     *       于是 {@code OPTIMIZE demo.sales.orders} 会被当成「在名为 demo 的 catalog 里找
     *       sales.orders」，而会话里唯一注册的 catalog 就是 {@code demo}；
     *   <li><b>{@code parent=<catalog>}</b>：REST 面的 id 是 {@code [catalog, schema, table]}，
     *       第一级就是 catalog 的名字。少了它 connector 会把 {@code sales} 当 catalog，
     *       于是每张表都以「不存在」失败；
     *   <li><b>{@code uri = <catalogUrl>/lance}</b>：我们挂的是 {@code /lance/v1/**}，
     *       而 connector 自己拼 {@code /v1/...}。多写一段（{@code /lance/v1}）的表现是
     *       每个请求 404，且 body 是我们自己的错误 JSON（路径没匹配到），很容易被读成
     *       「端点没实现」；
     *   <li><b>认证走 {@code headers.Authorization}</b>：Lance 没有 Iceberg 那种
     *       {@code token}/{@code rest.auth.type} 三个配置键，令牌只能塞进请求头。
     *       这个键名取自设计稿（{@code docs/lance-table-optimization-design.md} §5.2），
     *       **属该稿 §8 R1 待抓包确认的一项**：若不生效，表现是全部请求 401，
     *       而服务端日志里能看到没有 {@code Authorization} 头。
     * </ol>
     *
     * <p>对象存储的静态凭据走 {@code spark.sql.catalog.<c>.storage.*}（connector 会把
     * {@code storage.} 前缀剥掉当 FileIO 选项）——那是 vendor 凭据失效时的兜底，
     * 默认不配：静态凭据是长命密钥，能不用就不用（设计稿 §6.4）。
     */
    private static void lanceCatalog(SparkSession.Builder builder, String catalog,
                                     String catalogUrl, String catalogToken) {
        builder.config("spark.sql.extensions", LANCE_EXTENSION)
                .config("spark.sql.catalog." + catalog, LANCE_CATALOG)
                .config("spark.sql.catalog." + catalog + ".impl", "rest")
                .config("spark.sql.catalog." + catalog + ".uri", catalogUrl + "/lance")
                .config("spark.sql.catalog." + catalog + ".parent", catalog);
        if (catalogToken != null) {
            builder.config("spark.sql.catalog." + catalog + ".headers.Authorization",
                    "Bearer " + catalogToken);
        } else {
            log.info("no catalog token provided: connecting to {} without data plane credentials,"
                    + " which only works on deployments with authentication disabled",
                    catalogUrl + "/lance");
        }
    }
}
