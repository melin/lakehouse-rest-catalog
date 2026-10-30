package io.github.melin.lakehouse.rest.service;

import java.util.Locale;
import java.util.Set;

/**
 * 一条 ADLS Gen2 位置拆出来的四件事：容器、账户、DNS 后缀、目录路径。
 *
 * <p>{@code abfss://warehouse@acct.dfs.core.windows.net/wh/db.db/orders} 拆成
 * 容器 {@code warehouse}、账户 {@code acct}、后缀 {@code dfs.core.windows.net}、
 * 路径 {@code wh/db.db/orders}。看起来只是个正则的活，但三个字段各自有独立的下游用途，
 * 混在一起会直接签出错误的 SAS：
 *
 * <ul>
 *   <li>{@code account + "." + host} 是下发键的前缀（{@code adls.sas-token.<account>.<host>}），
 *       也是 {@code canonicalizedResource} 的第二段。**host 要保留 dfs 形态**——
 *       客户端认的是它自己填的那个地址。</li>
 *   <li>换委托密钥的端点在 **blob** 上（{@code https://<account>.blob.core.windows.net/}），
 *       即使数据走的是 dfs 端点。dfs 的服务不提供这个接口，拿 dfs 主机名去调会得到 404，
 *       而现象是「文件在、密钥换不到」。</li>
 *   <li>路径既进签名（决定这张 SAS 能覆盖哪个目录），也决定 {@code sdd}（目录层数）。</li>
 * </ul>
 *
 * <p><b>只认四种 scheme</b>（{@code abfs} / {@code abfss} / {@code wasb} / {@code wasbs}），
 * 认不出来返回 {@code null} 交给调用方决定「不签」。不猜的理由与
 * {@link CredentialScope#resolve} 相同：一个猜出来的账户名会让 SAS 看起来签成功了，
 * 实际覆盖的是另一个位置。
 */
public record AzureLocation(String scheme, String container, String account, String host, String path) {

    /** 支持的 scheme。带 {@code s} 的是 TLS 版本。 */
    private static final Set<String> SCHEMES = Set.of("abfs", "abfss", "wasb", "wasbs");

    public AzureLocation {
        path = path == null ? "" : path;
    }

    /**
     * 解析一条位置；认不出来返回 {@code null}。
     *
     * <p>authority 的形状是 {@code <container>@<account>.<host>}，账户名是 host 的第一段。
     * **不能**按「取 {@code @} 与第一个点之间那段」去解 {@code account.dfs.core.windows.net}
     * ——那样会得到 {@code account}，看着对，但同一个规则套在自定义域名
     * （{@code account.blob.storage.contoso.com}）上就得到 {@code account}，而 host
     * 会变成 {@code blob.storage.contoso.com}，恰好也能用；真正会错的是
     * 账户名里带点的私有云写法。这里只做「第一段是账户、其余是 host」这一种约定，
     * 并把 {@code host} 原样保留给下发键。
     */
    public static AzureLocation parse(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String scheme = trimmed.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            return null;
        }
        String rest = trimmed.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : trimSlashes(rest.substring(slash));
        int at = authority.indexOf('@');
        if (at <= 0 || at == authority.length() - 1) {
            return null;
        }
        String container = authority.substring(0, at);
        String hostAndAccount = authority.substring(at + 1);
        int dot = hostAndAccount.indexOf('.');
        if (dot <= 0 || dot == hostAndAccount.length() - 1) {
            return null;
        }
        String account = hostAndAccount.substring(0, dot);
        String host = hostAndAccount.substring(dot + 1);
        return new AzureLocation(scheme, container, account, host, path);
    }

    /**
     * 数据面是否走 TLS：{@code abfss} / {@code wasbs} 是。
     *
     * <p><b>不能写成 {@code scheme.endsWith("s")}。</b>{@code abfs} 也以 {@code s} 结尾，
     * 而它是明文版本——那样写会把 {@code spr} 收成 {@code https}，于是所有用
     * {@code abfs://} 的部署在第一次读表时 403，报错说「SAS 权限不足」，
     * 与「协议不允许」毫无关系。四种 scheme 一共四种，穷举比推断省事。
     */
    public boolean tls() {
        return "abfss".equals(scheme) || "wasbs".equals(scheme);
    }

    /**
     * 换委托密钥用的主机名：{@code dfs} 换成 {@code blob}，其余原样。
     *
     * <p>两条约定都在这里：
     * <ul>
     *   <li>{@code abfss://...@acct.dfs.core.windows.net} 的账户级 REST 端点在 blob 上，
     *       dfs 端点没有 {@code comp=userdelegationkey}；</li>
     *   <li>中国云等主权云只是后缀不同（{@code dfs.core.chinacloudapi.cn}），
     *       所以替换的是**第一段**而不是整个后缀——写死后缀会让私有云只能改代码。</li>
     * </ul>
     */
    public String blobHost() {
        return host.startsWith("dfs.") ? "blob." + host.substring("dfs.".length()) : host;
    }

    /** 完整存储 DNS 名，形如 {@code acct.dfs.core.windows.net}；下发键与规范化资源都用它。 */
    public String authority() {
        return account + "." + host;
    }

    /**
     * 存储是否启用了分层命名空间（HNS）。
     *
     * <p><b>判据是 scheme，不是 catalog 里的 {@code hierarchical} 字段。</b>
     * {@code abfs}/{@code abfss} 就是 ADLS Gen2 的协议，而 ADLS Gen2 **必然**开着 HNS
     * ——这两件事在 Azure 上是同一个产品定义；{@code wasb}/{@code wasbs} 走的是
     * Blob 端点，那里没有目录这个概念。用 catalog 的布尔字段反而会引入一个新错法：
     * 填了 {@code abfss} 却把 {@code hierarchical} 写成 false 时，我们会去签一个
     * 容器级 SAS，而客户端仍按目录地址访问——票能用，只是覆盖了整个容器。
     */
    public boolean hierarchical() {
        return scheme.startsWith("abfs");
    }

    /**
     * 这张 SAS 是否该按目录签（{@code sr=d}）。
     *
     * <p>容器根没有「目录」可指，flat 命名空间也没有目录这个概念；两种情况都只能
     * 按容器签（{@code sr=c}），那意味着这张票覆盖整个容器——调用方必须把这件事说出来。
     */
    public boolean directory() {
        return hierarchical() && !path.isEmpty();
    }

    /**
     * SAS 的 {@code canonicalizedResource}。
     *
     * <p>容器级（{@code sr=c}）**不能带路径**：签名串里的资源必须与实际访问的容器一致，
     * 多写一段路径会让签名恒不匹配，而报错是 {@code AuthorizationSignatureMismatch}，
     * 看起来像「密钥不对」。
     *
     * <p>固定写 {@code /blob/}：ADLS Gen2 的 SAS 也走 Blob 服务的签名格式，
     * 写 {@code /dfs/} 会得到一个恒不匹配的签名，同一个报错。
     */
    public String sasResource() {
        return "/blob/" + account + "/" + container + (directory() ? "/" + path : "");
    }

    /**
     * {@code sdd}：目录相对容器根的层数（容器根为 0）。
     *
     * <p>目录级 SAS（{@code sr=d}）必须给这个值，且它**不**出现在签名串里——
     * 它只进查询串、并通过「规范化资源必须包含对应层数的子目录」约束签名的作用面。
     * 少给的表现是 400 {@code MissingRequiredQueryParameter}；给多了则签名与实际
     * 使用的 URL 对不上。
     */
    public int depth() {
        if (path.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
