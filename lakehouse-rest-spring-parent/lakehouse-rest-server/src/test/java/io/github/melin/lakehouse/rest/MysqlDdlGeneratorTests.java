package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 从实体元数据生成 MySQL DDL，产出仓库内的 {@code sql/schema-mysql.sql}。
 *
 * <p><b>为什么用测试来生成。</b>Hibernate 7 已移除 {@code org.hibernate.tool.hbm2ddl.SchemaExport}，
 * 官方路径只剩 {@code SchemaManagementToolCoordinator} 配合 JPA 的
 * {@code jakarta.persistence.schema-generation.*} 属性。而实体清单由 Spring Boot 的
 * {@code @SpringBootApplication} 包扫描给出，因此走一次真实的上下文启动，
 * 新增实体会被自动纳入，不会出现「加了实体却忘了加进生成器」的漏项。
 *
 * <p><b>连接用 H2、方言用 MySQL。</b>脚本生成只读元数据，不需要连到 MySQL，
 * 这样生成过程不依赖数据库可用性。但方言必须显式钉住 MySQL 8.0，见
 * {@link MySql8DialectResolver}——否则 Hibernate 会把 H2 上报的版本号当成 MySQL 版本。
 *
 * <p><b>H2 上看不见、只有真库才报的那类错误，在生成侧补一条断言。</b>MySQL 的表行大小
 * 上限（65535 字节）是存储层的约束：方言生成不报、在 H2 上跑测试也不报，只有在真库
 * 执行建表时才炸——{@code lakehouse_table_optimization} 的三个 {@code varchar(8192)}
 * 配置列就是这么漏出去的（算术见 {@link #everyTableFitsTheMysqlRowSizeLimit()}）。
 * 其余「真库才有的行为」（外键、字符集、索引键长度）仍需实机步骤验证。
 *
 * <p>数据源相关属性在这里全部显式给出，不使用 {@code application.yml} 的默认值——
 * 默认值已经指向 MySQL，若沿用会让本测试要求一个真实数据库才能跑。
 */
@SpringBootTest(properties = {
        // 生成脚本只需要元数据，不连真实库；用 H2 保证任何环境都能执行
        "spring.datasource.url=jdbc:h2:mem:ddlgen;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // 不设 spring.jpa.database-platform：改由下面的 DialectResolver 决定方言，
        // 这样方言版本可控，也不会触发 HHH90000025（不该显式指定 hibernate.dialect）
        "spring.jpa.properties.hibernate.dialect_resolvers="
                + "io.github.melin.lakehouse.rest.MysqlDdlGeneratorTests$MySql8DialectResolver",
        // 关掉自动建表，只写脚本
        "spring.jpa.hibernate.ddl-auto=none",
        // hibernate.format_sql 除了美化日志里的 SQL，也决定 DDL 脚本是否换行缩进。
        // 不打开的话每条 create table 会挤成一整行（最长近 3000 字符），无法审阅与 diff。
        "spring.jpa.properties.hibernate.format_sql=true",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.jakarta.persistence.schema-generation.create-source=metadata",
        // 每次覆盖而不是追加，保证脚本可重复生成
        "spring.jpa.properties.hibernate.hbm2ddl.schema-generation.script.append=false",
        // 关掉启动期预置数据：此时库里没有表，预置会失败
        "lakehouse.rest.initial-catalog.prefix=",
        "lakehouse.rest.authorization.enabled=false",
        "lakehouse.rest.auth.enabled=false"
})
class MysqlDdlGeneratorTests {

    /**
     * 把方言钉到 MySQL 8.0。
     *
     * <p><b>为什么需要它。</b>若只写 {@code hibernate.dialect=MySQLDialect}，
     * Hibernate 会用 {@code MySQLDialect(DialectResolutionInfo)} 构造方言，
     * 而这里的连接是 H2，于是它把 H2 上报的 {@code 2.4.240} 当成 MySQL 版本，
     * 输出 {@code HHH000511: The 2.4.240 version for MySQLDialect is no longer supported}，
     * 并可能按 MySQL 2.x/5.x 的旧路径生成 DDL。DDL 的目标版本必须是确定的，
     * 所以这里显式返回 8.0。
     *
     * <p>必须是 public static 且有无参构造函数，{@code hibernate.dialect_resolvers}
     * 按类名实例化它。
     */
    public static class MySql8DialectResolver implements DialectResolver {

        @Override
        public Dialect resolveDialect(DialectResolutionInfo info) {
            return new MySQLDialect(DatabaseVersion.make(8, 0));
        }
    }


    /**
     * 脚本输出位置。
     *
     * <p>默认写到 {@code target/}（构建产物，不入仓库）；
     * {@code scripts/gen-mysql-ddl.sh} 通过 {@code -Dmysql.ddl.output=...} 指到交付路径。
     */
    private static final Path OUTPUT = Path.of(
            System.getProperty("mysql.ddl.output", "target/mysql-schema.sql"))
            .toAbsolutePath();

    /**
     * 实体的表数量；新增/删除实体时应同步调整，否则本测试会失败提醒。
     *
     * <p>2026-10 删掉 catalog role 整层后从 23 降到 20
     * （{@code lakehouse_catalog_role} / {@code lakehouse_catalog_role_grant} /
     * {@code lakehouse_resource_grant} 三张表随之移除，授权只剩
     * {@code lakehouse_permission_assignment} 一张）。
     */
    private static final int EXPECTED_TABLES = 20;

    /** MySQL 一张表一行的最大字节数（存储层上限，与列长度上限是两件事）。 */
    private static final long MYSQL_ROW_SIZE_LIMIT = 65535L;

    /** 每行的固定开销：InnoDB 记录头与 trx id/roll ptr、null 位图、变长列的长度前缀。 */
    private static final long ROW_OVERHEAD_BYTES = 64L;

    /**
     * 一条建表语句：表名 + 括号内的所有定义。
     *
     * <p>表注释（{@code @Table(comment = ...)} 的产物）排在 {@code engine=InnoDB} 之前，
     * 形如 {@code ) comment='...' engine=InnoDB}，因此这里要把注释段跳过去，
     * 否则整张表都匹配不上——断言会退化成「没解析到任何建表语句」。
     */
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "create table\\s+(\\w+)\\s*\\((.*?)\\)\\s*(?:comment\\s*=\\s*'[^']*'\\s*)?engine=InnoDB",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 行首的「列名 + 类型」，其余修饰（not null 等）不关心。 */
    private static final Pattern COLUMN_DEFINITION = Pattern.compile("(\\S+)\\s+(\\S+)");

    private static final Pattern VARCHAR = Pattern.compile("varchar\\((\\d+)\\)");

    /** 表级注释：{@code comment='...'}（出现在 {@code engine=InnoDB} 之前）。 */
    private static final Pattern TABLE_COMMENT = Pattern.compile("comment\\s*=\\s*'[^']*'");

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void schemaGenerationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                () -> OUTPUT.toString());
    }

    @Test
    void generatesMysqlDdlForEveryEntity() throws IOException {
        assertTrue(Files.exists(OUTPUT), "未生成 DDL 脚本：" + OUTPUT);
        String ddl = Files.readString(OUTPUT, StandardCharsets.UTF_8);

        Matcher tables = Pattern.compile("(?im)^\\s*create table\\s+", Pattern.MULTILINE).matcher(ddl);
        int count = 0;
        while (tables.find()) {
            count++;
        }
        assertEquals(EXPECTED_TABLES, count,
                "DDL 中的建表语句数量与实体数量不符，说明有实体未被扫描到或已被删除：" + OUTPUT);

        // 方言必须真的生效：H2 会写成 character varying，MySQL 是 varchar / mediumtext
        assertTrue(ddl.toLowerCase().contains("engine=innodb") || ddl.toLowerCase().contains("engine = innodb"),
                "生成的 DDL 不是 MySQL 方言（缺少 ENGINE=InnoDB）：" + OUTPUT);
        assertTrue(ddl.toLowerCase().contains("mediumtext"),
                "生成的 DDL 不是 MySQL 方言（超长文本列应为 mediumtext）：" + OUTPUT);
    }

    /**
     * 每张表、每一列都必须带注释。
     *
     * <p><b>为什么这条要当成断言。</b>注释来自实体的 {@code @Table(comment = ...)} /
     * {@code @Column(comment = ...)}，是这份 DDL 里唯一由人写给读者看的东西：库里
     * 没有注释的表就是「一串看不出用途的列」，而 {@code show create table} 是排查线上
     * 数据问题的第一条命令。漏写注释不会让任何构建失败，只会安静地少一行说明，
     * 因此把它挡在生成侧。
     *
     * <p>约束子句（{@code primary key} 等）没有注释，按 {@link #isConstraintClause} 排除。
     */
    @Test
    void everyTableAndColumnIsCommented() throws IOException {
        String ddl = Files.readString(OUTPUT, StandardCharsets.UTF_8);
        Matcher tables = TABLE_BLOCK.matcher(ddl);
        List<String> tablesWithoutComment = new ArrayList<>();
        List<String> columnsWithoutComment = new ArrayList<>();
        int examined = 0;
        while (tables.find()) {
            examined++;
            String table = tables.group(1);
            if (!TABLE_COMMENT.matcher(tables.group(0)).find()) {
                tablesWithoutComment.add(table);
            }
            for (String raw : tables.group(2).split("\n")) {
                String definition = raw.strip();
                if (definition.endsWith(",")) {
                    definition = definition.substring(0, definition.length() - 1).strip();
                }
                if (definition.isEmpty() || isConstraintClause(definition)) {
                    continue;
                }
                if (!definition.contains(" comment '")) {
                    columnsWithoutComment.add(table + "." + definition.split("\\s+")[0]);
                }
            }
        }

        assertTrue(examined > 0, "没解析到任何建表语句，断言会变成空转：" + OUTPUT);
        assertTrue(tablesWithoutComment.isEmpty(),
                "下列表没有注释，请在实体的 @Table 上补 comment：" + tablesWithoutComment);
        assertTrue(columnsWithoutComment.isEmpty(),
                "下列列没有注释，请在实体的 @Column 上补 comment：" + columnsWithoutComment);
    }

    /**
     * 每张表都要装得进 MySQL 的行大小上限：一行的最大字节数不能超过 65535。
     *
     * <p><b>为什么「单列看着合法」却建不出表。</b>这条上限是<b>整行</b>的，而且
     * {@code varchar(N)} 按 {@code N × 4} 计入（库是 utf8mb4），{@code text} 家族
     * 只计 9~12 字节的指针。于是三个 {@code varchar(8192)} 的配置列各 32768、
     * 加起来 98304，在真库上建表直接失败：
     *
     * <pre>
     * ERROR 1118 (42000): Row size too large. The maximum row size for the used table
     * type, not counting BLOBs, is 65535. ... You have to change some columns to TEXT or BLOBs
     * </pre>
     *
     * <p><b>为什么必须在生成侧拦。</b>这是存储层的约束：Hibernate 生成 DDL 时不检查，
     * 在 H2 上跑测试也检查不到——工程全绿，只有在真库上执行才炸，而那时建表脚本已经
     * 交付出去了（{@code lakehouse_table_optimization} 就是这么漏出去的）。本断言只解析
     * 自己刚生成的文本，不新增环境依赖。
     *
     * <p>计入规则见 {@link #columnBytes(String)}，另加每行固定开销
     * {@link #ROW_OVERHEAD_BYTES}。刻意不设「宽松余量」：这条断言的价值就是越界即失败。
     */
    @Test
    void everyTableFitsTheMysqlRowSizeLimit() throws IOException {
        String ddl = Files.readString(OUTPUT, StandardCharsets.UTF_8);
        Matcher tables = TABLE_BLOCK.matcher(ddl);
        List<String> violations = new ArrayList<>();
        int examined = 0;
        while (tables.find()) {
            examined++;
            Map<String, Long> bytes = new LinkedHashMap<>();
            for (String raw : tables.group(2).split("\n")) {
                String definition = raw.strip();
                if (definition.endsWith(",")) {
                    definition = definition.substring(0, definition.length() - 1).strip();
                }
                if (definition.isEmpty() || isConstraintClause(definition)) {
                    continue;
                }
                Matcher column = COLUMN_DEFINITION.matcher(definition);
                if (column.lookingAt()) {
                    bytes.put(column.group(1), columnBytes(column.group(2)));
                }
            }
            long total = ROW_OVERHEAD_BYTES
                    + bytes.values().stream().mapToLong(Long::longValue).sum();
            if (total > MYSQL_ROW_SIZE_LIMIT) {
                violations.add(tables.group(1) + " 约 " + total + " 字节（最大的几列："
                        + largest(bytes) + "）");
            }
        }

        assertTrue(examined > 0, "没解析到任何建表语句，断言会变成空转：" + OUTPUT);
        assertTrue(violations.isEmpty(),
                "下列表的列宽之和超过 MySQL 的行大小上限 " + MYSQL_ROW_SIZE_LIMIT
                        + " 字节，在真库上建表会报 ERROR 1118；"
                        + "把最大的文本列改成 text（@Column 的 length 写 65535）：" + violations);
    }

    /**
     * 表内的约束子句不是列定义，别把 {@code primary key (id)} 当成一个叫 primary 的列。
     *
     * <p>按行首关键字排除而不是「认不出就跳过」：列名一旦被引号包起来（MySQL 保留字
     * 做列名时 Hibernate 会加反引号）就不再命中这些前缀，能正常计入。
     */
    private static boolean isConstraintClause(String definition) {
        String lower = definition.toLowerCase(Locale.ROOT);
        return lower.startsWith("primary key")
                || lower.startsWith("foreign key")
                || lower.startsWith("unique ")
                || lower.startsWith("constraint ")
                || lower.startsWith("key ")
                || lower.startsWith("index ");
    }

    /** 贡献最大的几列，让失败信息直接指出该改哪一列。 */
    private static String largest(Map<String, Long> bytes) {
        return bytes.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    /**
     * 一列计入行大小的字节数（MySQL 文档的算法，库是 utf8mb4）。
     *
     * <p>未列出的类型直接抛错而不是按 0 计：漏算会让上限悄悄失守，而这条断言存在的
     * 全部意义就是「越界即失败」。新增列类型时在这里补一行即可。
     */
    private static long columnBytes(String type) {
        Matcher varchar = VARCHAR.matcher(type);
        if (varchar.matches()) {
            return 4L * Long.parseLong(varchar.group(1));
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            // BLOB / TEXT 家族只计 9~12 字节的指针，取上界
            case "tinytext", "text", "mediumtext", "longtext" -> 12L;
            case "bigint" -> 8L;
            case "integer", "int" -> 4L;
            case "bit" -> 1L;
            default -> throw new IllegalArgumentException("未建模的列类型 " + type
                    + "：请在 columnBytes 里补上它计入行大小的字节数");
        };
    }

    /**
     * 锁住方言版本。
     *
     * <p>这条断言防的是「方言退化成按 H2 上报的版本号解释」：一旦解除
     * {@link MySql8DialectResolver}，Hibernate 会把 H2 的 {@code 2.4.240} 当成 MySQL 版本，
     * 生成的 DDL 就按旧版本路径走。建表语句数量之类的断言不会发现这种偏移，必须直接查版本。
     */
    @Test
    void pinsTheDialectToMysql8() {
        Dialect dialect = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
        assertInstanceOf(MySQLDialect.class, dialect, "方言不是 MySQLDialect");
        assertEquals(8, dialect.getVersion().getMajor(), "方言主版本不是 8（可能被 H2 的版本号污染）");
        assertEquals(0, dialect.getVersion().getMinor(), "方言次版本不是 0");
    }
}
