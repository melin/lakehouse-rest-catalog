package io.github.melin.lakehouse.rest.support;

import java.util.List;
import java.util.Locale;

/**
 * 存储路径推导与 {@code allowedLocations} 白名单判定。
 *
 * <p>Paimon 的目录约定是 {@code warehouse/database.db/table}，
 * 表路径模板可通过 {@code lakehouse.rest.path-template} 覆盖。
 *
 * <p><b>白名单判定只有一份实现</b>（{@link #isWithin} / {@link #requireWithin}）。
 * 三个协议面各有入口会收客户端给的位置——Iceberg 的 {@code createTable} / {@code createView} /
 * {@code registerTable}、Paimon 面的非 Paimon 表 {@code create} 与 {@code register}、Lance 的
 * {@code DeclareTable} / {@code RegisterTable} / {@code CreateTableVersion}——收敛到这里的理由
 * 不是省几行，而是**判据写多份时的漂移方向永远是「悄悄地变松」**：Lance 曾经自己实现过一次，
 * 用的是裸 {@code startsWith}，于是 {@code s3://b/ab} 被判定落在白名单 {@code s3://b/a} 之内。
 * 那个漏洞不会报错、不会让任何用例变红，只会让「允许写哪几个前缀」这句话变成一句谎言。
 *
 * <p>判定规则（每条都有对应用例，见 {@code AllowedLocationTests}）：
 * <ul>
 *   <li><b>段边界</b>：{@code target} 等于前缀，或以 {@code 前缀 + "/"} 开头。裸前缀比较
 *       就是上面那个 bug；</li>
 *   <li><b>scheme 与 authority 小写后比较</b>：{@code S3://Bucket/wh} 与 {@code s3://bucket/wh}
 *       是同一个位置，而 {@code http} 头写全大写的客户端确实存在；</li>
 *   <li><b>path 部分保持原样</b>：对象存储的 key **区分大小写**，把 path 也小写会让
 *       {@code s3://b/Warehouse} 与 {@code s3://b/warehouse} 混为一谈；</li>
 *   <li><b>去尾斜杠</b>：白名单里写成 {@code s3://b/wh/}（表单里很常见）与
 *       {@code s3://b/wh} 等价；</li>
 *   <li><b>空白名单 = 不限制</b>：与 catalog 创建时的语义一致（FILE / HDFS 允许没有
 *       {@code allowedLocations}），这条不能改成「空即全拒」，否则存量 catalog 建表全废。</li>
 * </ul>
 *
 * <p><b>刻意不做两件事</b>：
 * <ol>
 *   <li><b>不做 scheme 别名归一</b>（{@code s3a} / {@code s3n} 不当成 {@code s3}）。它们在上游
 *       是同一种存储，但本服务端的 {@code CredentialScope.resolve} 只认字面的
 *       {@code scheme://authority/path}，把 {@code s3a://} 当成 {@code s3://} 放行会让这张表
 *       拿到票、却签不出对应前缀——「校验通过、事后 403」比一眼拒绝难查得多。宁可让写
 *       {@code s3a://} 的人改成 {@code s3://}；</li>
 *   <li><b>不做 {@code ..} 归一</b>。对象存储的 key 里 {@code ..} 是两个普通字符，不是目录
 *       穿越，把它当穿越去处理反而会把一个合法 key 判成越界。</li>
 * </ol>
 *
 * <p><b>它只回答「这个位置在不在白名单里」，不回答「这个位置能不能被访问」。</b>
 * 后者取决于凭据档位：签发档的票按表路径收窄，转发档（{@code relay}）转发的是长期密钥，
 * 白名单对数据面没有约束力。这句话在 {@code docs/spark-iceberg-rest-e2e.md} §1.2 与
 * {@code docs/spark-lance-rest-e2e.md} 都写了——本次实现之前，前者的说法与代码不一致。
 */
public final class Paths {

    private Paths() {
    }

    public static String databaseLocation(String warehouse, String database) {
        return trimTrailingSlash(warehouse) + "/" + database + ".db";
    }

    public static String tableLocation(String template, String warehouse, String database, String table) {
        String pattern = template == null || template.isBlank()
                ? "{warehouse}/{database}.db/{table}"
                : template;
        return pattern
                .replace("{warehouse}", trimTrailingSlash(warehouse))
                .replace("{database}", database)
                .replace("{table}", table);
    }

    /**
     * {@code location} 是否落在白名单内的某个前缀下。
     *
     * <p>不抛异常的那一版：Lance 面要把同一个判定结果包成自己的错误形状
     * （{@code LanceException} 的 status 由 code 决定，与 Spring 的 400 不是一条通道），
     * 因此它用本方法取值、自己抛错；另外两个面直接用 {@link #requireWithin}。
     *
     * @param location        待判定的位置；{@code null} 或空白视为「没有位置要校验」，返回 {@code true}
     * @param allowedLocations 白名单；{@code null} 或空表示不限制
     */
    public static boolean isWithin(String location, List<String> allowedLocations) {
        if (location == null || location.isBlank()) {
            return true;
        }
        if (allowedLocations == null || allowedLocations.isEmpty()) {
            return true;
        }
        String target = normalize(location);
        if (target == null) {
            return false;
        }
        for (String allowed : allowedLocations) {
            String prefix = normalize(allowed);
            if (prefix == null) {
                continue;
            }
            // 等于，或「前缀 + /」。多加那一个斜杠就是段边界：没有它，
            // s3://b/ab 会命中前缀 s3://b/a。
            if (target.equals(prefix) || target.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 {@code location} 落在 {@code allowedLocations} 内，否则 400。
     *
     * <p>报错里**同时**给出被拒的位置与整份白名单。只给前者时，调用方要么去查控制台、
     * 要么去猜是哪个前缀写错了；而这条 400 的典型场景恰恰是「我以为我配的是这个前缀」。
     *
     * @throws ApiException 400，400 而不是 403：这是请求参数（位置）与 catalog 配置不匹配，
     *                      与「你有没有权限建这张表」是两个问题，授权拦截器在那之前已经判过了
     */
    public static void requireWithin(String location, List<String> allowedLocations) {
        if (isWithin(location, allowedLocations)) {
            return;
        }
        throw ApiException.badRequest("location \"" + location
                + "\" is outside the allowed locations of this catalog: " + allowedLocations
                + " (a location is allowed when it equals one of them or sits under it,"
                + " compared segment by segment)");
    }

    /**
     * 归一成可比较的字符串：{@code scheme://authority} 小写 + path 原样 + 去尾斜杠。
     *
     * <p>认不出 {@code scheme://} 形状的输入（相对路径、明显的笔误）按原样去尾斜杠后参与比较，
     * 而不是直接判越界：白名单本身可能是那种形状，而「猜一个」正是这个类要避免的事。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) {
            return trimTrailingSlash(value);
        }
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        String rest = value.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        // abfss://container@account.dfs.core.windows.net/wh：只有 @ 之后是 host。
        // 整段小写会把容器名一起改掉，而容器是**资源名**、不是 host——Container 与 container
        // 是两个不同的容器（Azure 只是恰好在命名规则上要求小写），混为一谈就是一次误放行。
        int at = authority.lastIndexOf('@');
        authority = at < 0
                ? authority.toLowerCase(Locale.ROOT)
                : authority.substring(0, at + 1) + authority.substring(at + 1).toLowerCase(Locale.ROOT);
        // 只削 path 的尾斜杠，不削整串：削整串会把 {@code s3://} 削成 {@code s3:}，
        // 而那条畸形白名单项随后会以 {@code s3://} + "/" 的形式命中一切 s3 位置。
        return scheme + "://" + authority + trimTrailingSlash(path);
    }

    public static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
