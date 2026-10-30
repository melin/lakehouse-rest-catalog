package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次签发请求的上下文：给谁、覆盖哪些位置、要不要写。
 *
 * <p>比 {@code vend(storage, tableId, tablePath)} 多几样东西，都有明确用途：
 * <ul>
 *   <li>{@code principal} 进 {@code RoleSessionName}。云侧审计日志里能直接看出
 *       「谁读了哪张表」，成本为零；不做的唯一理由是忘了。注意**不要把敏感元数据
 *       塞进 session name 或 session tag**——它们会进云侧审计日志，也可能被 ABAC
 *       策略引用。</li>
 *   <li>{@code allowWrite} 决定 {@link CredentialScope} 的写路径是否非空。
 *       只读请求不签写权限是收窄里最便宜的一档，也最容易被忽略。</li>
 *   <li>{@code scopePaths} 是一**组**位置而不是一个。表级作用域只有一项；
 *       服务端自己取票时是 catalog 的整份 {@code allowedLocations}（见
 *       {@link #forCatalog}）。</li>
 * </ul>
 *
 * <p><b>两种作用域不能混用，判据是「谁在用」：</b>引擎的票按「用户 + 表」签——
 * 一个人读一张表，票就该只覆盖那一张表；服务端自己的票按「catalog 的允许位置」签
 * ——浏览目录、删一张表的整个目录都要跨出单个表前缀。前者是本类的主用法，
 * 后者是 {@link #forCatalog}。两者在缓存里也必须分开（见
 * {@code StorageCredentialCache#catalogKey}），否则一张表的票会被拿去当 catalog 票用，
 * 表现是「预览能读、列目录 403」，而日志里一切正常。
 *
 * <p><b>{@code allowWrite} 目前只有服务端这条链路会传 {@code true}。</b>
 * 引擎侧的读写路径在协议上分不开（{@code loadTable} 之后客户端既读也写），
 * 那里仍然一律按只读签——先收紧，等有了「写请求」的判据再放开。
 */
public record CredentialContext(StorageConfigInfo storage,
                                String scopeId,
                                List<String> scopePaths,
                                String principal,
                                boolean allowWrite) {

    public CredentialContext {
        scopePaths = scopePaths == null ? List.of() : List.copyOf(scopePaths);
    }

    /**
     * 表级作用域（既有签名）。
     *
     * <p>保留三参构造是为了让所有引擎侧的调用点（Paimon {@code /token}、
     * Iceberg {@code /credentials}、Lance {@code storage_options}）一行都不用改：
     * 它们本来就是「一张表一份票」。
     */
    public CredentialContext(AwsStorageConfigInfo storage,
                             String tableId,
                             String tablePath,
                             String principal,
                             boolean allowWrite) {
        this((StorageConfigInfo) storage, tableId,
                tablePath == null || tablePath.isBlank() ? List.<String>of() : List.of(tablePath),
                principal, allowWrite);
    }

    /**
     * 表级作用域的通用构造：存储类型不必是 S3。
     *
     * <p>做成静态工厂而不是第二个构造器，是为了避开一个安静的重载陷阱：
     * 「表级」的第三个参数是 {@code String}，「catalog 级」的是 {@code List<String>}，
     * 两者写成同一个名字的构造器时，任何传 {@code null} 的调用点都会变成编译歧义
     * （或者更糟——按某个版本的选择规则静默选中另一个）。名字分开就没有这个问题。
     */
    public static CredentialContext forTable(StorageConfigInfo storage,
                                             String tableId,
                                             String tablePath,
                                             String principal,
                                             boolean allowWrite) {
        return new CredentialContext(storage, tableId,
                tablePath == null || tablePath.isBlank() ? List.<String>of() : List.of(tablePath),
                principal, allowWrite);
    }

    /**
     * S3 那份配置；存储不是 S3 时返回 {@code null}。
     *
     * <p>本类的作用域、会话名、审计 tag 是**各云通用**的（谁、哪张表、读还是写），
     * 只有「怎么换票」是各云自己的事——所以存储配置按共用父类型持有，
     * 需要 {@code roleArn} / {@code region} 这类 S3 专有字段的 provider 从本方法取。
     * 返回 {@code null} 而不是抛异常：一个 provider 被链调起来时，
     * 「类型不对」是最正常不过的一条路由（写错存储类型的人不该看到 500）。
     */
    public AwsStorageConfigInfo aws() {
        return storage instanceof AwsStorageConfigInfo aws ? aws : null;
    }

    /**
     * catalog 级作用域：服务端自己访问仓库时用。
     *
     * <p>{@code scopeId} 加 {@code catalog:} 前缀不是为了好看——它会进
     * {@code RoleSessionName}，云侧审计日志里因此能分辨「这是服务端自己取的票」
     * 还是「某个用户读某张表」；同时它也让缓存键与表级票天然不同名。
     *
     * @param allowedLocations catalog 的允许位置；为空时 {@link #scopes()} 返回空，
     *                         签发方应当拒绝签发而不是发一张不收窄的票
     */
    public static CredentialContext forCatalog(StorageConfigInfo storage,
                                              String catalogId,
                                              List<String> allowedLocations,
                                              String principal,
                                              boolean allowWrite) {
        return new CredentialContext(storage, "catalog:" + catalogId, allowedLocations,
                principal, allowWrite);
    }

    /** 作用域列表；一个位置都认不出来时为空（调用方据此拒绝签发）。 */
    public List<CredentialScope> scopes() {
        return CredentialScope.resolveAll(scopePaths, allowWrite);
    }

    /** 第一个作用域，兼容「只有一个位置」的既有调用；空作用域时为 {@code null}。 */
    public CredentialScope scope() {
        List<CredentialScope> scopes = scopes();
        return scopes.isEmpty() ? null : scopes.get(0);
    }

    /** 表 id（表级作用域）。catalog 级作用域下它是 {@code catalog:<id>}。 */
    public String tableId() {
        return scopeId;
    }

    /** 表的第一个位置，用于日志；没有位置时为 {@code null}。 */
    public String tablePath() {
        return scopePaths.isEmpty() ? null : scopePaths.get(0);
    }

    /**
     * {@code RoleSessionName}：{@code lh-<principal>-<scopeId>}，按 AWS 的字符集规整。
     *
     * <p>规整而不是直接拼：STS 对 session name 的约束是
     * {@code [\w+=,.@-]{2,64}}，主体标识里常见的 {@code /}、{@code :}、空格都在集合外，
     * 直接拼会在签名阶段以 {@code ValidationError} 失败——一个看起来与授权毫无关系的错。
     * 超长时保留尾部（表 id 更有辨识度），并保证至少 2 个字符。
     */
    public String roleSessionName() {
        String name = "lh-" + (principal == null ? "service" : principal) + "-" + scopeId;
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = c == '_' || c == '+' || c == '=' || c == ','
                    || c == '.' || c == '@' || c == '-' || Character.isLetterOrDigit(c);
            sanitized.append(allowed ? c : '-');
        }
        String value = sanitized.toString();
        if (value.length() > 64) {
            value = value.substring(value.length() - 64);
        }
        return value.length() < 2 ? "lh" : value;
    }

    /**
     * 审计用的 session tags：{@code lh:principal} / {@code lh:scope} / {@code lh:access}。
     *
     * <p>与 {@link #roleSessionName()} 是同一件事的两种表达，但**能力不同**：
     * session name 是一个字符串，云侧只能做前缀/包含匹配；tag 是结构化的键值对，
     * 能按 {@code lh:principal = alice} 直接过滤，也能被 ABAC 策略当条件引用。
     * 所以两者都发，不互相替代。
     *
     * <p><b>值要规整，理由与 session name 相同但要按 tag 的字符集</b>：STS 允许的
     * 是字母、数字、空格与 {@code _ . : / = + - @}，逗号、括号、引号都在集合外。
     * 主体标识常见 {@code user:alice@corp/x}——这个恰好全在集合内，但目录服务里
     * 带逗号的 DN 不在，直接发会在签名阶段 {@code ValidationError}。
     * 非法字符一律换成 {@code -}，长度按 STS 上限截断（值 256）。
     *
     * <p>**不塞敏感元数据**：tag 会进云侧审计日志，也可能被策略引用。
     * 这里只放主体标识、作用域标识与读写方向。
     */
    public Map<String, String> sessionTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("lh:principal", tagValue(principal == null || principal.isBlank() ? "service" : principal));
        tags.put("lh:scope", tagValue(scopeId));
        // 「读还是写」是最值得单独成键的一维：只读票被拿去写会 403，
        // 而那 403 发生在存储侧，与「签票时给了什么权限」隔着好几层。
        tags.put("lh:access", allowWrite ? "write" : "read");
        return tags;
    }

    /** 按 STS 的 tag 字符集规整一个值（见 {@link #sessionTags()} 的说明）。 */
    private static String tagValue(String raw) {
        String value = raw == null ? "" : raw;
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = c == '_' || c == '.' || c == ':' || c == '/'
                    || c == '=' || c == '+' || c == '-' || c == '@'
                    || c == ' ' || Character.isLetterOrDigit(c);
            sanitized.append(allowed ? c : '-');
        }
        String trimmed = sanitized.toString().trim();
        if (trimmed.isEmpty()) {
            // tag 的值不能为空串：STS 会拒，而报错只提 value 不提是哪个 tag
            return "unknown";
        }
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
    }
}
