package io.github.melin.lakehouse.rest.lance.support;

import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paths;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * {@code _refs/tags/} 下的标签文件：命名规则与 JSON 形状。
 *
 * <p><b>标签是本面唯一「盘上格式完全够用」的附属对象</b>，所以它读得起、
 * 而索引读不起。区别不是难易，而是规范写没写：
 *
 * <ul>
 *   <li>标签：{@code _refs/tags/{tag_name}.json}，字段 {@code branch} / {@code version} /
 *       {@code manifestSize} / {@code createdAt} / {@code updatedAt} / {@code metadata}
 *       ——名字、位置、字段全在
 *       <a href="https://lance.org/format/table/branch_tag/">Branch and Tag Specification</a> 里；</li>
 *   <li>索引：{@code _indices/{UUID}/} 底下「different for each index type」，
 *       连文件名都不定（{@code .idx} 只是一种），因此 {@code ListTableIndices} 仍回 406。</li>
 * </ul>
 *
 * <p><b>{@code manifestSize} 是驼峰，不是 {@code manifest_size}。</b>这是规范里少见的
 * 一处不一致：{@code TableVersion.manifest_size} 是下划线，而 {@code TagContents.manifestSize}
 * 是驼峰（盘上 JSON 也是驼峰，与 Rust 侧 {@code serde(rename_all = "camelCase")} 一致）。
 * 客户端按规范拼字段名，写错不会有任何编译期提示，只会安静地拿到 {@code null}。
 *
 * <p><b>只解三个字段。</b>盘上还有 {@code createdAt} / {@code updatedAt} / {@code metadata}，
 * 但 {@code TagContents} 不声明它们，因此不透传——多给的字段对严格的生成式客户端是
 * 反序列化风险，而对「这个标签指向第几版」这个唯一用途没有帮助。
 */
public final class LanceTagFiles {

    /** 标签目录，相对数据集根；<b>永远在根级</b>，不论标签指向哪个分支。 */
    public static final String DIRECTORY = "_refs/tags";

    private static final String SUFFIX = ".json";

    private LanceTagFiles() {
    }

    /**
     * 一个标签。
     *
     * @param branch       标签所在的分支；{@code null} = 主分支（规范里「缺该键」的含义）
     * @param version      指向的版本号（规范里 required，读不到即视为文件坏了）
     * @param manifestSize 该版本 manifest 的字节数；文件里没有时为 {@code null}
     *                     ——它只是「省一次 LIST」的提示，缺了不影响标签的语义
     */
    public record Tag(String name, String branch, long version, Long manifestSize) {
    }

    /**
     * 标签文件读不懂。
     *
     * <p>与 {@link LanceManifest.Unreadable} 是同一个态度：**读不出就说读不出**，
     * 不要拿 {@code version = 0} 之类的默认值凑一个答案——那会把「标签坏了」
     * 变成「标签指向第 0 版」，客户端会去读一个不存在的版本。
     */
    public static final class Unreadable extends RuntimeException {

        public Unreadable(String message) {
            super(message);
        }

        public Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 数据集里标签目录的绝对位置。 */
    public static String directoryOf(String location) {
        return Paths.trimTrailingSlash(location) + "/" + DIRECTORY;
    }

    /**
     * 从文件名反解标签名。
     *
     * @return 不是 {@code .json}、或去掉后缀后为空时返回 {@code null}（调用方跳过）
     */
    public static String nameOf(String fileName) {
        if (fileName == null || fileName.length() <= SUFFIX.length()
                || !fileName.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            return null;
        }
        String name = fileName.substring(0, fileName.length() - SUFFIX.length());
        return name.isEmpty() ? null : name;
    }

    /** 一个标签在存储里的位置。 */
    public static String pathOf(String location, String tagName) {
        return directoryOf(location) + "/" + tagName + SUFFIX;
    }

    /**
     * 标签名是否可以安全地拼进路径。
     *
     * <p>规范对标签名的限制（字母数字与 {@code . - _}，不能以 {@code .} 开头/结尾、
     * 不能有连续的 {@code ..}）在我们的位置上不是「要不要宽容」的问题，而是**路径穿越**：
     * 名字直接拼成文件名，一个 {@code ../../_versions/1.manifest} 就能让「读标签」
     * 变成「读任意对象」。这里做的是拒绝而不是清洗——清洗会把调用方的错变成一个
     * 他能看到结果的请求。
     */
    public static boolean isSafeName(String tagName) {
        if (tagName == null || tagName.isEmpty()) {
            return false;
        }
        if (tagName.indexOf('/') >= 0 || tagName.indexOf('\\') >= 0 || tagName.contains("..")) {
            return false;
        }
        return !tagName.startsWith(".") && !tagName.endsWith(".");
    }

    /**
     * 解析一个标签文件。
     *
     * <p>入参是**文本**而不是字节：标签是 JSON，不是 manifest 那种二进制。
     * 二进制那条路（{@code readBytes}）是为了不让 protobuf 过一遍 UTF-8 解码，
     * 这里没有那个顾虑，而文本让批量读（一次 FileIO 读整目录）变自然。
     *
     * @throws Unreadable 不是 JSON 对象、或没有数值型的 {@code version}
     */
    public static Tag parse(String tagName, String json) {
        if (json == null || json.isBlank()) {
            throw new Unreadable("tag file for \"" + tagName + "\" is empty");
        }
        JsonNode root;
        try {
            root = Json.readTree(json);
        } catch (RuntimeException e) {
            throw new Unreadable("tag file for \"" + tagName + "\" is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new Unreadable("tag file for \"" + tagName + "\" is not a JSON object");
        }
        JsonNode version = root.get("version");
        if (version == null || !version.isNumber()) {
            throw new Unreadable("tag file for \"" + tagName + "\" has no numeric \"version\"");
        }
        JsonNode branch = root.get("branch");
        JsonNode manifestSize = root.get("manifestSize");
        return new Tag(tagName,
                branch == null || branch.isNull() ? null : branch.asString(),
                version.asLong(),
                manifestSize == null || !manifestSize.isNumber() ? null : manifestSize.asLong());
    }
}
