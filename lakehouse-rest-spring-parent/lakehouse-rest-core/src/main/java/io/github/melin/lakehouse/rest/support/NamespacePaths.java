package io.github.melin.lakehouse.rest.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Iceberg 多级 namespace 与「库里那一列」之间的编解码。
 *
 * <p>Iceberg 的 namespace 是**多级**的（{@code ["accounting","tax"]}），而
 * {@code paimon_database.name} 是单级字符串。两边的最简对应是**点号拼接**：
 * {@code ["accounting","tax"]} ↔ {@code accounting.tax}。选点号而不是别的字符，
 * 是为了与 Spark SQL 的 {@code catalog.db.table} 三段名、以及
 * {@code paimon_resource_grant.namespace_key}（本来就是「命名空间路径的拼接」）
 * 口径一致——控制台与 SQL 里都直接可读。
 *
 * <p><b>路径里的分隔符有两个来源，都要认。</b>
 *
 * <ul>
 *   <li>规格默认是 {@code 0x1F}（URL 编码 {@code %1F}），并且要求服务端即便
 *       {@code /config} 里声明了别的分隔符，**也必须同时接受 {@code 0x1F}**
 *       （兼容旧客户端）。本服务端不声明 override，因此默认值就是对外契约。</li>
 *   <li>点号：本服务端**对外不声明**点号分隔符，但解析时接受它。原因是
 *       服务端自己存储的就是点号拼接形式，控制台与手工 {@code curl} 用点号最自然，
 *       而 Iceberg 允许单个 level 内含点号这件事在**路径**这一侧不构成歧义——
 *       路径里的点号本来就只能是分隔符（真正的 level 内容会被客户端 URL 编码）。</li>
 * </ul>
 *
 * <p>为什么不把 {@code namespace-separator} 声明成点号、让 URL 更好读：那要求
 * 服务端对**请求体里的** namespace 也按点号切，而请求体是 JSON 数组
 * （{@code "namespace": ["accounting","tax"]}），切不切都一样；真正的代价在
 * 「客户端本地校验」一侧——它会把含点号的单个 level 拆错。保持规格默认值最安全。
 *
 * <p><b>为什么这个类在 core 而不是 Iceberg 面。</b>两个使用者都在 core：
 * {@link io.github.melin.lakehouse.rest.config.CatalogAccessRules} 要把路径里的
 * {@code {namespace}} 段切成层级才能与授权条目比对，异常处理器要认「格式不匹配」
 * 这类消息。让 Iceberg 面自己持一份、core 再持一份，两份迟早漂移。
 */
public final class NamespacePaths {

    /** 规格默认的多级分隔符。 */
    public static final char SEPARATOR = (char) 0x1F;

    /** 库里那一列用的分隔符。 */
    public static final char STORAGE_SEPARATOR = '.';

    private NamespacePaths() {
    }

    /**
     * 把路径里的 {@code {namespace}} 段切成层级。
     *
     * <p>入参是**已经 URL 解码过**的路径段（Servlet 容器与 Spring 的 {@code @PathVariable}
     * 都会先解码，{@code CatalogAccessRules} 解析原始 URI 时也自己解过了），因此这里
     * **不再解码一次**：再解一遍会把 level 里真实存在的 {@code %25} 变成 {@code %}，
     * 而这种错只在名字里含百分号的表上出现。切分按 {@code 0x1F} 与点号两种分隔符，
     * 混用也认——没有理由为此报错。
     *
     * @param decoded 已解码的路径段原文，可为 {@code null}
     * @return 层级列表；入参为空时返回空列表
     */
    public static List<String> parse(String decoded) {
        if (decoded == null || decoded.isBlank()) {
            return List.of();
        }
        List<String> levels = new ArrayList<>();
        for (String level : split(decoded)) {
            if (!level.isEmpty()) {
                levels.add(level);
            }
        }
        return levels;
    }

    /**
     * 把层级列表编码成路径段：层级之间用 {@code 0x1F}，并做 URL 编码。
     *
     * <p>{@code 0x1F} 是控制字符，直接写进 URL 会被容器拒绝，因此必须编码成
     * {@code %1F}——这正是规格默认分隔符「URL 编码 {@code %1F}」那句话的含义。
     */
    public static String format(List<String> levels) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < levels.size(); index++) {
            if (index > 0) {
                builder.append("%1F");
            }
            builder.append(urlEncode(levels.get(index)));
        }
        return builder.toString();
    }

    /**
     * 把层级列表拼成库里那一列的存储形式（点号拼接）。
     *
     * <p>不做转义：单个 level 里出现点号的表，落库后与「两个 level」无法区分。
     * 这是刻意接受的取舍——Iceberg 客户端实际不生成含点号的 level，而为了一个
     * 不存在的场景把列内容变成读者看不懂的形式，代价更大。风险与对策见
     * {@code docs/iceberg-rest-catalog-design.md} §8 的风险 10。
     */
    public static String toStorageName(List<String> levels) {
        return String.join(String.valueOf(STORAGE_SEPARATOR), levels);
    }

    /** {@link #toStorageName} 的反向：把库里那一列还原成层级列表。 */
    public static List<String> fromStorageName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return parse(name);
    }

    /** 按 {@code 0x1F} 与点号两种分隔符切分。 */
    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == SEPARATOR || character == STORAGE_SEPARATOR) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /**
     * URL 编码一个路径段，只保留「不能直接出现在路径段里」的字符之外的可见字符。
     *
     * <p>只编码不保留字符（{@code a-zA-Z0-9-_.~}）之外的一切——{@code 0x1F} 因此会变成
     * {@code %1F}，这正是规格所说的「默认分隔符 URL 编码后是 {@code %1F}」。
     */
    private static String urlEncode(String value) {
        StringBuilder builder = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int character = raw & 0xFF;
            boolean unreserved = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_' || character == '.' || character == '~';
            if (unreserved) {
                builder.append((char) character);
            } else {
                builder.append('%');
                builder.append(Character.toUpperCase(Character.forDigit((character >> 4) & 0xF, 16)));
                builder.append(Character.toUpperCase(Character.forDigit(character & 0xF, 16)));
            }
        }
        return builder.toString();
    }
}
