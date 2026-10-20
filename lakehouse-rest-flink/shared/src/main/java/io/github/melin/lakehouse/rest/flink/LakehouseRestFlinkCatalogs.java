package io.github.melin.lakehouse.rest.flink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 Flink SQL 访问 Lakehouse REST catalog 时需要的两个选项表，以及 {@code CREATE CATALOG} 的拼装。
 *
 * <p><b>为什么单独有这个类。</b>Paimon 与 Iceberg 的「怎么连 REST 目录」用的是**两套完全
 * 不同的键名**，写混了不会报「参数不认识」，而是静默退化成别的实现：
 *
 * <ul>
 *   <li>Paimon：{@code metastore=rest}（不是 {@code type=rest}）；
 *   <li>Iceberg：{@code type=iceberg} + {@code catalog-type=rest}（不是 {@code metastore}）。
 * </ul>
 *
 * 两边的 {@code warehouse} 也都**不是仓库路径**，而是 catalog 的 prefix：服务端的
 * {@code GET /v1/config?warehouse=<prefix>} 拿它换回 prefix，客户端后续所有路径都以它开头。
 * 写成一个真实存在的文件路径不会报错，只会在服务端找不到同名 catalog，症状是 404 或
 * 「catalog 不存在」——离真正的原因（warehouse 传错了）很远。
 *
 * <p>本类**不 import 任何 Flink 类型**：一旦引了，它就必须选一个 Flink 版本编译，
 * 而 1.20 与 2.x 的字节码互不兼容，两个版本模块就没法共用这一份源码了。
 * 这里只产出 {@code Map} 与 SQL 字符串，与版本无关。
 */
public final class LakehouseRestFlinkCatalogs {

    /**
     * Paimon 的令牌提供者实现标识。
     *
     * <p>Paimon 把「怎么把令牌放进请求头」做成了 SPI，内置实现的标识是 {@code bear}
     * （Bearer 认证，<b>不是</b> {@code bearer}）。不写这个键时默认值不是它，
     * 客户端会尝试自己的凭据链，结果是服务端 401，而日志里只会说「未授权」。
     */
    public static final String PAIMON_TOKEN_PROVIDER = "bear";

    private LakehouseRestFlinkCatalogs() {
    }

    /**
     * Paimon catalog 的选项。
     *
     * @param uri     服务端基址，例如 {@code http://127.0.0.1:18081}
     * @param catalog catalog 的 prefix（服务端上的 catalog 名），同时作为 {@code warehouse}
     * @param token   Bearer 令牌；服务端未开认证时随便给一个非空值即可
     */
    public static Map<String, String> paimonOptions(String uri, String catalog, String token) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("type", "paimon");
        options.put("metastore", "rest");
        options.put("uri", uri);
        options.put("warehouse", catalog);
        options.put("token.provider", PAIMON_TOKEN_PROVIDER);
        options.put("token", token);
        // 让服务端下发表级数据访问凭据。FILE 仓库用不到，但打开它对结果没有影响；
        // 关掉的话换到对象存储仓库时，客户端会拿本机凭据链去连，必然失败。
        options.put("data-token.enabled", "true");
        return Collections.unmodifiableMap(options);
    }

    /**
     * Iceberg catalog 的选项。
     *
     * <p>{@code cache-enabled=false} 是刻意的：端到端用例要的是「每次读都真的发请求」，
     * 开着缓存时 {@code INSERT} 之后的 {@code SELECT} 可能命中本地旧 metadata，
     * 表现是「写入成功但读不到」——与服务端的回归长得一模一样。
     */
    public static Map<String, String> icebergOptions(String uri, String catalog, String token) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("type", "iceberg");
        options.put("catalog-type", "rest");
        options.put("uri", uri);
        options.put("warehouse", catalog);
        // 只给 token 也能用：客户端会猜成 oauth2 并打一条警告。显式写出来是为了
        // 日志干净，也为了把「属性全名是 rest.auth.type」这条坑留在代码里。
        options.put("rest.auth.type", "oauth2");
        options.put("token", token);
        options.put("cache-enabled", "false");
        return Collections.unmodifiableMap(options);
    }

    /** {@code CREATE CATALOG IF NOT EXISTS <name> WITH ('k'='v', ...)}。 */
    public static String createCatalogIfNotExists(String catalogName, Map<String, String> options) {
        StringBuilder sql = new StringBuilder("CREATE CATALOG IF NOT EXISTS ")
                .append(quoteIdentifier(catalogName))
                .append(" WITH (");
        boolean first = true;
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            first = false;
            sql.append(quoteLiteral(entry.getKey())).append(" = ").append(quoteLiteral(entry.getValue()));
        }
        return sql.append(')').toString();
    }

    /**
     * 反引号包住标识符。
     *
     * <p>catalog 名是服务端上的 prefix，通常就是普通小写标识符，但用户可能用
     * {@code my-catalog} 这类带连字符的名字；不转义会在 SQL 解析阶段就失败。
     */
    public static String quoteIdentifier(String identifier) {
        return '`' + identifier.replace("`", "``") + '`';
    }

    /** 单引号字符串字面量；值里的 {@code '} 按 SQL 规则翻倍。 */
    public static String quoteLiteral(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }
}
