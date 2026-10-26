package io.github.melin.lakehouse.rest.service.exec;

import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Context;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 解析 kubeconfig，对**每一个 context** 给出「服务端能不能用它」的结论。
 *
 * <p><b>为什么不是「解析成功就保存」。</b>一个 kubeconfig 里通常有多个 context，其中只有
 * 一部分是服务端真能用的：别人的 {@code exec: aws eks get-token}、旧式
 * {@code auth-provider: azure}、指向本机文件的 CA 路径。这些在用户机器上都能跑，
 * 在服务端进程里都不能。等到第一次提交才 500，用户看到的是一句与 kubeconfig 无关的报错，
 * 因此解析阶段就逐条给结论，并把不可用的行**显示但禁用**——「我的 context 去哪了」
 * 比「这里有个红叉」更让人困惑。
 *
 * <p><b>{@code exec} 是硬拒，这是安全边界而不是能力缺失。</b>{@code exec:} 段的语义是
 * 「执行这个命令行，读它的 stdout 当令牌」。一旦服务端照着做，任何能改执行集群配置的人
 * 就能在**服务端进程所在的机器**上执行任意命令——这是 RCE，不是「顺便支持一下 AWS CLI」。
 * 因此文案必须给出替代路径（切到 EKS 令牌方式），不能只说「不支持」。
 *
 * <p><b>纯函数。</b>不读库、不连网、不看文件：{@code certificate-authority} 指向的路径
 * 是**调用方机器**上的路径，服务端读它必然失败，因此这里只标警告，不去尝试打开它。
 * 解析结果不落库（保存的是 kubeconfig 原文，`GET` 时再解析一次），
 * 于是「读接口给出 context 列表」这件事不需要第二处解析逻辑。
 */
public final class KubeconfigParser {

    private static final Logger log = LoggerFactory.getLogger(KubeconfigParser.class);

    /** kubeconfig 上限 64 KiB：再大就不是「一份连接配置」而是别的东西了。 */
    public static final int MAX_BYTES = 64 * 1024;

    private KubeconfigParser() {
    }

    /** 解析结果。{@code currentContext} 可能为 null（文件里没写）。 */
    public record Parsed(List<Context> contexts, String currentContext, List<String> warnings) {

        /** 结论为「可用」的 context 名（含带警告的那种）。 */
        public List<String> usableNames() {
            List<String> names = new ArrayList<>();
            for (Context context : contexts) {
                if (isUsable(context.verdict())) {
                    names.add(context.name());
                }
            }
            return names;
        }

        /** 按名字取一个 context 的结论。 */
        public Context find(String name) {
            for (Context context : contexts) {
                if (context.name().equals(name)) {
                    return context;
                }
            }
            return null;
        }
    }

    /** {@code OK} 与 {@code WARN} 都算可用——警告讲的是风险，不是「用不了」。 */
    public static boolean isUsable(String verdict) {
        return "OK".equals(verdict) || "WARN".equals(verdict);
    }

    /**
     * 解析一份 kubeconfig 文本。
     *
     * @throws ApiException 400：不是合法 YAML，或根本不是 kubeconfig（缺 clusters/contexts）
     */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("kubeconfig 内容为空");
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("kubeconfig 超过 64 KiB 上限");
        }
        Map<?, ?> root;
        try {
            Object loaded = new Yaml().load(text);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw ApiException.badRequest("kubeconfig 不是 YAML 映射（顶层应当是 apiVersion / kind / clusters）");
            }
            root = map;
        } catch (YAMLException malformed) {
            // 带行号：kubeconfig 多半是手工拼的，指到行比说「格式错误」有用得多
            throw ApiException.badRequest("kubeconfig 不是合法 YAML：" + malformed.getMessage());
        }

        Map<String, Map<?, ?>> clusters = byName(root.get("clusters"), "clusters");
        Map<String, Map<?, ?>> users = byName(root.get("users"), "users");
        List<?> contexts = asList(root.get("contexts"));
        if (contexts.isEmpty()) {
            throw ApiException.badRequest("kubeconfig 里没有 contexts；它至少要有 current-context 指向的一节");
        }

        List<String> warnings = new ArrayList<>();
        List<Context> result = new ArrayList<>();
        for (Object item : contexts) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String name = string(entry.get("name"));
            Map<?, ?> body = map(entry.get("context"));
            if (name == null || name.isBlank() || body == null) {
                warnings.add("有一个 context 缺少 name 或 context 段，已忽略");
                continue;
            }
            result.add(evaluate(name, body, clusters, users, warnings));
        }
        String current = string(root.get("current-context"));
        if (current != null && !current.isBlank() && result.stream().noneMatch(c -> c.name().equals(current))) {
            warnings.add("current-context " + current + " 指向的 context 不存在");
        }
        return new Parsed(result, current, warnings);
    }

    /**
     * 单个 context 的判定表。
     *
     * <p>顺序有意义：先看 context 指向的 cluster / user 在不在（文件自身坏了），
     * 再看认证方式。反过来的话，一个引用了不存在 user 的 context 会被报成
     * 「匿名访问」，而真正的原因是拼错了名字。
     */
    private static Context evaluate(String name, Map<?, ?> context,
                                    Map<String, Map<?, ?>> clusters, Map<String, Map<?, ?>> users,
                                    List<String> warnings) {
        String clusterName = string(context.get("cluster"));
        String userName = string(context.get("user"));
        Map<?, ?> cluster = clusterName == null ? null : clusters.get(clusterName);
        if (cluster == null) {
            return new Context(name, clusterName, null, "—", "UNAVAILABLE",
                    "context 指向的 cluster " + clusterName + " 不存在（kubeconfig 自身不完整）");
        }
        String server = string(cluster.get("server"));
        boolean insecure = Boolean.TRUE.equals(cluster.get("insecure-skip-tls-verify"));
        String caPath = string(cluster.get("certificate-authority"));
        String caData = string(cluster.get("certificate-authority-data"));
        if (caPath != null && !caPath.isBlank() && (caData == null || caData.isBlank())) {
            warnings.add("context " + name + " 的 certificate-authority 指向路径 " + caPath
                    + "，那是**你机器上**的路径，服务端读不到；请改用 certificate-authority-data"
                    + "（或把 CA 填进「集群 CA」一项）");
        }
        if (insecure) {
            warnings.add("context " + name + " 关闭了 apiserver 证书校验（insecure-skip-tls-verify），"
                    + "中间人可以替换掉它；生产不建议");
        }
        Map<?, ?> user = userName == null ? null : users.get(userName);
        if (user == null) {
            return new Context(name, clusterName, server, "匿名", "UNAVAILABLE",
                    userName == null
                            ? "context 没有 user 段，等于匿名访问；apiserver 默认不给匿名建 Job 的权限"
                            : "context 指向的 user " + userName + " 不存在（kubeconfig 自身不完整）");
        }
        return evaluateAuth(name, clusterName, server, user, insecure);
    }

    /** 认证方式的判定。文案本身就是界面上的那一行结论，因此写全。 */
    private static Context evaluateAuth(String name, String clusterName, String server,
                                        Map<?, ?> user, boolean insecure) {
        String verdict = insecure ? "WARN" : "OK";
        String suffix = insecure ? "（未校验证书）" : "";
        if (user.get("exec") != null) {
            return new Context(name, clusterName, server, "exec: " + execCommand(user.get("exec")), "UNSUPPORTED",
                    "服务端不能替你执行本地命令（那等于让它执行任意命令）；EKS 请把集群类型切成「AWS EKS · 令牌」");
        }
        if (user.get("auth-provider") != null) {
            return new Context(name, clusterName, server,
                    "auth-provider: " + string(map(user.get("auth-provider")).get("name")), "UNSUPPORTED",
                    "旧式 auth-provider 依赖交互式刷新，k8s 1.26 前后已陆续移除；请改用令牌或客户端证书");
        }
        if (notBlank(user.get("client-certificate-data")) && notBlank(user.get("client-key-data"))) {
            return new Context(name, clusterName, server, "client-certificate-data", verdict, "客户端证书认证" + suffix);
        }
        if (notBlank(user.get("client-certificate")) || notBlank(user.get("client-key"))) {
            return new Context(name, clusterName, server, "client-certificate", "UNSUPPORTED",
                    "客户端证书写在文件路径里，那是你机器上的文件；请导出成 client-certificate-data / client-key-data");
        }
        if (notBlank(user.get("token"))) {
            return new Context(name, clusterName, server, "token", verdict, "静态令牌" + suffix);
        }
        if (notBlank(user.get("tokenFile"))) {
            return new Context(name, clusterName, server, "tokenFile", "UNSUPPORTED",
                    "tokenFile 是服务端读不到的本地路径；请把令牌内容直接写进 token 字段");
        }
        if (notBlank(user.get("username")) && notBlank(user.get("password"))) {
            return new Context(name, clusterName, server, "username/password", "WARN",
                    "Basic 认证在 k8s 1.19 起默认关闭，多半会 401；不建议使用");
        }
        return new Context(name, clusterName, server, "—", "UNAVAILABLE",
                "user 段没有任何认证信息（没有证书/令牌/用户名）");
    }

    private static String execCommand(Object exec) {
        Map<?, ?> map = map(exec);
        String command = string(map.get("command"));
        if (command == null) {
            return "?";
        }
        Object args = map.get("args");
        if (args instanceof List<?> list && !list.isEmpty()) {
            return command + " " + list.get(0);
        }
        return command;
    }

    // ------------------------------------------------------------------ YAML 取值的小工具

    /** 取 {@code [{name: x, cluster: {...}}]} 这种列表，按 name 建索引；缺 name 的条目忽略并记一笔。 */
    private static Map<String, Map<?, ?>> byName(Object value, String field) {
        Map<String, Map<?, ?>> result = new LinkedHashMap<>();
        for (Object item : asList(value)) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String name = string(entry.get("name"));
            Map<?, ?> body = singleBody(entry, field);
            if (name == null || name.isBlank() || body == null) {
                log.debug("kubeconfig 的 {} 有一节缺少 name 或内容，已忽略", field);
                continue;
            }
            result.put(name, body);
        }
        return result;
    }

    /** {@code clusters: [{name, cluster: {...}}]}、{@code users: [{name, user: {...}}]}：取单数那一层。 */
    private static Map<?, ?> singleBody(Map<?, ?> entry, String field) {
        String key = field.endsWith("s") ? field.substring(0, field.length() - 1) : field;
        return map(entry.get(key));
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean notBlank(Object value) {
        String text = string(value);
        return text != null && !text.isBlank();
    }
}
