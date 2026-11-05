package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

/**
 * Flyway 迁移基线的唯一执行路径守卫：脚本必须真能用，且建出实体所需的全部表。
 *
 * <p><b>为什么需要这个类。</b>迁移脚本是 MySQL / PostgreSQL 方言，只在真库上执行；
 * 而单元测试与本地调试都跑在 H2 上（{@code application-test.yml} / {@code application-h2.yml}
 * 里 {@code spring.flyway.enabled=false}，建表交给 {@code ddl-auto}）。于是常规回归
 * <b>根本不碰迁移脚本</b>——它能一路绿着，同时脚本里写着一条语法错误，直到某个
 * 生产环境启动时才炸。这类「守卫与执行环境不同源」的缺口，正是本类要堵的。
 *
 * <p><b>执行方式：真 Flyway + H2 的 MySQL 兼容模式。</b>不用 {@code @SpringBootTest}
 * （那需要 Boot 上下文与实体扫描，而迁移与实体无关），直接 {@code Flyway.configure()}
 * 连一个内存 H2，{@code MODE=MySQL} 让它认 MySQL 方言（{@code ENGINE=InnoDB} /
 * {@code auto_increment} / 列内联 {@code comment '...'}）。此前实测：原生模式下
 * 88 条语句全失败、{@code MODE=MySQL} 下全通过，因此这个模式是脚本可执行性的最接近
 * 的廉价载体。
 *
 * <p><b>不是 {@code sql/schema-mysql.sql} 的重复。</b>那个文件是「给人执行的全量快照」，
 * 本类验的是<b>派生出来的 Flyway 基线</b>——剥掉 {@code CREATE DATABASE} / {@code USE} /
 * 注释行后是否仍然自洽（剥离逻辑写错就会缺语句或切坏语句）。
 * {@code gen-flyway-baseline.py --check} 验的是「基线与快照同源」，本类验的是
 * 「同源的那份能被数据库接受」，两条互补，都不能省。
 *
 * <p><b>覆盖边界。</b>这里证明的是「脚本语法与语义在目标方言下可执行、表数正确」。
 * 不覆盖：真 MySQL / PostgreSQL 上的字符集与排序规则、索引键长度上限、外键与
 * 权限、以及 {@code ddl-auto: validate} 是否认可结果——那些要实机（见 README
 * 「数据库迁移」一节的验收步骤）。
 */
class FlywayBaselineTests {

    /** MySQL 基线的 classpath 位置，与 {@code application.yml} 的 {@code locations} 同源。 */
    private static final String MYSQL_LOCATION = "classpath:db/migration/mysql";

    /** PostgreSQL 基线的位置（备用方言，由 postgresql profile 使用）。 */
    private static final String POSTGRES_LOCATION = "classpath:db/migration/postgresql";

    /**
     * 连接串。
     *
     * <p>{@code MODE=MySQL} 是这份脚本能被 H2 接受的前提；
     * {@code DATABASE_TO_LOWER=TRUE} 让标识符按小写存储（脚本里全是小写表名）；
     * {@code DB_CLOSE_DELAY=-1} 让内存库在 Flyway 关闭自己的连接后仍然存活——
     * 缺了它，下面那条用来清点表的 JDBC 连接会连上一个<b>全新的空库</b>，
     * 断言就会以「迁移没建表」的形式失败，而真实原因是库已经被回收。
     */
    private static final String MYSQL_URL =
            "jdbc:h2:mem:flyway-mysql;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** PostgreSQL 基线用的连接串。{@code MODE=PostgreSQL} 让它认 IDENTITY 与 COMMENT ON。 */
    private static final String POSTGRES_URL =
            "jdbc:h2:mem:flyway-pg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private static final String USER = "sa";

    private static final String PASSWORD = "";

    /** 脚本里的建表语句，用来清点「脚本说该有哪些表」。 */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?im)^\\s*create table\\s+(\\w+)");

    /** 脚本里的纯注释行：清点语句数时要先剥掉，否则会与紧邻的语句并成一条。 */
    private static final Pattern COMMENT_LINE = Pattern.compile("(?m)^\\s*--.*$");

    /**
     * MySQL 基线：整份执行一遍，建出全部实体表，且可重复执行。
     *
     * <p>三条断言各堵一个方向：
     * <ol>
     *   <li>脚本自己声明的建表数 == 实体表数（{@link MysqlDdlGeneratorTests#EXPECTED_TABLES}）
     *       ——防「加了实体、重生成快照、忘了重生成基线」；
     *   <li>迁移后库里<b>每张</b>声明的表都真实存在——防「脚本被数据库中途拒绝、
     *       只建成前几张」（Flyway 遇错会回滚并抛异常，但如果脚本里有语句被静默跳过，
     *       表会缺而迁移仍报成功）；
     *   <li>再执行一次迁移是 no-op（{@code migrationsExecuted == 0}）——这就是接
     *       Flyway 相比「手工再跑一遍 SQL」的全部意义：已应用的版本不会重放，
     *       而手工重跑会在第二张表上报 {@code Table already exists}。
     * </ol>
     */
    @Test
    void mysqlBaselineCreatesEveryTable() throws SQLException, IOException {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL_URL, USER, PASSWORD)
                .locations(MYSQL_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();

        MigrateResult first = flyway.migrate();
        assertEquals(2, first.migrationsExecuted,
                "空库上执行迁移应当是「V1 建表 + V2 加列」两条。条数为 0 说明脚本没被发现；"
                        + "条数与预期不符说明有人加了/删了版本文件却没同步这条断言——"
                        + "注意 V1 的 checksum 不可再变，加结构一律新增 V2+。");

        List<String> declared = declaredTables("mysql");
        assertEquals(MysqlDdlGeneratorTests.EXPECTED_TABLES, declared.size(),
                "Flyway 基线里的建表数与实体表数不符：说明脚本已与实体漂移，"
                        + "请依次跑 ./scripts/gen-mysql-ddl.sh 与 scripts/gen-flyway-baseline.py 重生成");

        try (Connection connection = DriverManager.getConnection(MYSQL_URL, USER, PASSWORD)) {
            List<String> missing = missingTables(connection, declared);
            assertTrue(missing.isEmpty(),
                    "下列表在迁移后不存在，说明脚本里的建表语句没有全部生效：" + missing);
        }

        MigrateResult second = flyway.migrate();
        assertEquals(0, second.migrationsExecuted,
                "重复执行迁移不应再应用任何版本（V1 的 checksum 已记录在 flyway_schema_history 里）");
    }

    /**
     * PostgreSQL 基线：同一套断言，换到 {@code MODE=PostgreSQL} 下跑。
     *
     * <p><b>为什么两份都要跑。</b>两条基线由同一份实体派生，但方言不同：
     * PostgreSQL 侧的自增列是 {@code generated by default as identity}、注释是
     * <b>独立的</b> {@code comment on table/column ... is '...'}（439 条），
     * 语句数比 MySQL 侧多一个数量级。只测 MySQL 的话，PostgreSQL 那一份可以在
     * 「语句数为 501 但切分规则漏掉了后半段」的状态下长期存在而不被发现。
     *
     * <p>PostgreSQL 的真实执行环境（真服务器、{@code search_path}、权限）仍不在覆盖内。
     */
    @Test
    void postgresBaselineCreatesEveryTable() throws SQLException, IOException {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES_URL, USER, PASSWORD)
                .locations(POSTGRES_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();

        MigrateResult first = flyway.migrate();
        assertEquals(2, first.migrationsExecuted, "空库上执行 PostgreSQL 迁移应当应用 V1 + V2 两条");

        List<String> declared = declaredTables("postgresql");
        assertEquals(MysqlDdlGeneratorTests.EXPECTED_TABLES, declared.size(),
                "PostgreSQL 基线里的建表数与实体表数不符");

        try (Connection connection = DriverManager.getConnection(POSTGRES_URL, USER, PASSWORD)) {
            List<String> missing = missingTables(connection, declared);
            assertTrue(missing.isEmpty(),
                    "下列表在 PostgreSQL 基线迁移后不存在：" + missing);
        }

        assertEquals(0, flyway.migrate().migrationsExecuted, "重复执行 PostgreSQL 迁移应当是 no-op");
    }

    /**
     * V2 的**存量库升级路径**：先只应用 V1（模拟「上线 M5 之前就建好的库」），
     * 插一行旧结构的数据，再应用 V2，验证「加列成功 + 存量行拿到 DEFAULT 而非 NULL」。
     *
     * <p><b>为什么不能只测「空库跑到最新」。</b>空库路径是 V1 + V2 连着跑，
     * 那条路永远见不到「表里已有数据」这一态；而 V2 的三个 {@code NOT NULL} 列
     * 恰恰只在存量行上才可能失败——`ALTER TABLE ... ADD COLUMN ... NOT NULL`
     * 没有 DEFAULT 时会直接拒绝执行。这条用例就是那一句的守卫。
     *
     * <p>分两次 Flyway 配置：第一次 {@code target("1")} 只到 V1，第二次放开到最新。
     * Flyway 会记 {@code flyway_schema_history}，因此第二次只补跑 V2——
     * 这正是真机上的行为。
     */
    @Test
    void mysqlV2UpgradesAPreExistingV1Database() throws SQLException {
        String url = "jdbc:h2:mem:flyway-mysql-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway v1Only = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(MYSQL_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("1")
                .load();
        assertEquals(1, v1Only.migrate().migrationsExecuted, "第一次应当只应用 V1");

        // 造一行「旧结构」的数据：新列此时还不存在，因此不能出现在 insert 里
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD)) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into lakehouse_catalog_execution_cluster"
                            + " (id, catalog_id, enabled, entity_version, cluster_type, api_server,"
                            + " namespace, image, image_pull_policy, callback_base_url, config_hash,"
                            + " max_tables_per_job, max_concurrent_jobs, job_timeout_seconds)"
                            + " values (?, ?, 1, 0, 'SELF_HOSTED', 'https://k8s:6443',"
                            + " 'default', 'img:1', 'IfNotPresent', 'https://srv:8080', 'hash', 20, 2, 7200)")) {
                insert.setString(1, "existing");
                insert.setString(2, "cat");
                insert.executeUpdate();
            }
        }

        Flyway toLatest = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(MYSQL_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        assertEquals(1, toLatest.migrate().migrationsExecuted,
                "存量库上只应补跑 V2 这一条（V1 已记录，不重放）");

        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             PreparedStatement query = connection.prepareStatement(
                     "select max_scan_tables_per_job, max_concurrent_scans, scan_job_timeout_seconds"
                             + " from lakehouse_catalog_execution_cluster where id = 'existing'");
             ResultSet rows = query.executeQuery()) {
            assertTrue(rows.next(), "存量行在升级后应当还在");
            assertEquals(200, rows.getInt(1), "存量行的 max_scan_tables_per_job 应当拿到 DEFAULT 200，而不是 0/NULL");
            assertEquals(2, rows.getInt(2), "存量行的 max_concurrent_scans 应当拿到 DEFAULT 2");
            assertEquals(3600, rows.getInt(3), "存量行的 scan_job_timeout_seconds 应当拿到 DEFAULT 3600");
        }
    }

    /**
     * 基线必须随 fat jar 走。
     *
     * <p>迁移脚本放在 {@code src/main/resources}（而不是只放在仓库根的 {@code sql/}）
     * 的全部理由就是这一点：Flyway 从 classpath 读，classpath 由 jar 决定。
     * 若有人把脚本挪回 {@code sql/} 下，部署时会表现为「启动成功但一张表都没建」——
     * 因为 {@code baseline-on-migrate} 会把空库标记为已在 V1，然后
     * {@code ddl-auto: validate} 才失败，报错信息离真正原因很远。这条断言把它挡在测试里。
     *
     * <p>同时断言「脚本已被派生过」：存在建表语句与注释头。
     */
    @Test
    void baselinesArePackagedOnTheClasspath() throws IOException {
        for (String vendor : List.of("mysql", "postgresql")) {
            String sql = readClasspath("/db/migration/" + vendor + "/V1__baseline.sql");
            assertTrue(sql.contains("gen-flyway-baseline.py"),
                    vendor + " 基线缺少派生说明头，可能被手工改过或未经过派生脚本生成");
            assertTrue(CREATE_TABLE.matcher(sql).find(),
                    vendor + " 基线里没有任何 create table，脚本内容可疑");
            // V2 是手写的增量，也必须跟着 jar 走，否则存量库启动时不会加列
            String v2 = readClasspath("/db/migration/" + vendor
                    + "/V2__add_scan_strategy_and_spark_conf.sql");
            assertTrue(v2.contains("max_scan_tables_per_job"),
                    vendor + " 的 V2 迁移里没有 scan 策略列，内容可疑");
        }
    }

    /**
     * 脚本里声明的表名，按声明顺序。
     *
     * <p>先剥注释行再匹配：文件头里也提到了 {@code sql/schema-*.sql}，若不剥，
     * 行首匹配虽然不会误命中（注释行以 {@code --} 开头），但把头与首条语句的
     * 边界留在这里迟早会让别的解析出错。
     */
    private static List<String> declaredTables(String vendor) throws IOException {
        String sql = readClasspath("/db/migration/" + vendor + "/V1__baseline.sql");
        Matcher matcher = CREATE_TABLE.matcher(COMMENT_LINE.matcher(sql).replaceAll(""));
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        assertTrue(!tables.isEmpty(), vendor + " 基线里没解析到建表语句，正则可能已失效");
        return tables;
    }

    /**
     * 从 classpath 读脚本 —— 与 Flyway 的读取路径一致。
     *
     * <p>刻意不用 {@code Path.of("src/main/resources/...")}：那样只能证明文件在源码树里，
     * 证明不了它进了产物。
     */
    private static String readClasspath(String path) throws IOException {
        try (InputStream in = FlywayBaselineTests.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path + "，迁移脚本没有被 pack 进来");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 在 {@code declared} 里挑出库里不存在的表。
     *
     * <p>SQLException 在这里收口而不是让两个测试方法各声明一遍：查询失败意味着
     * 断言环境本身坏了（连不上库、information_schema 不可读），把它转成
     * {@link AssertionError} 会让失败信息直指「查不出来」，而不是被 JUnit 报成一个
     * 与脚本无关的异常栈。
     */
    private static List<String> missingTables(Connection connection, List<String> declared) {
        List<String> missing = new ArrayList<>();
        for (String table : declared) {
            try {
                if (!tableExists(connection, table)) {
                    missing.add(table);
                }
            } catch (SQLException e) {
                throw new AssertionError("清点表 " + table + " 时查询 information_schema 失败", e);
            }
        }
        return missing;
    }

    /**
     * 表是否存在。
     *
     * <p>不按 schema 过滤而是按表名精确匹配：H2 在 {@code MODE=MySQL} 与
     * {@code MODE=PostgreSQL} 下默认 schema 的<b>大小写不同</b>（{@code PUBLIC} / {@code public}），
     * 写成常量就会在某一侧查不到任何东西，得到「建表 0 张」这种看起来像脚本坏了的假失败。
     * 表名全部是 {@code lakehouse_*} 前缀，不会与任何系统表重名。
     */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from information_schema.tables where table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }
}
