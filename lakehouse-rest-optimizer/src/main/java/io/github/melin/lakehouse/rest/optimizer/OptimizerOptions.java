package io.github.melin.lakehouse.rest.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化任务的启动参数。
 *
 * <p>三个必需参数与服务端 {@code KubernetesJobSubmitter} 生成的容器参数一一对应：
 * {@code --job-id}、{@code --management-url}、{@code --token}。刻意只有这三个——
 * 表清单与每表配置都通过回调管理 API 拉取，不走命令行。
 *
 * <p>为什么表清单不走参数：一批最多 20 张表、每张表三项配置，序列化进参数会让
 * 命令行迅速失控，而且「集群里跑的那份参数」会与「库里的策略」产生第二个版本。
 * 拉取的方式保证「库里当时是什么就执行什么」，也让任务将来支持断点续跑时
 * 只需重拉一次清单。
 *
 * <p>为什么还接受 {@code --catalog-token}：管理 API 的批次令牌只能调优化回调端点，
 * 不能当 catalog 的数据面凭据。任务要访问 {@code /v1/{prefix}} 才能拿到 Paimon 表，
 * 这一步需要 **catalog 侧**的令牌。两者作用域不同，因此是两个参数；部署没开认证时
 * 省略 {@code --catalog-token} 即可。
 */
public final class OptimizerOptions {

    /** 管理 API 在服务端上的固定后缀，用来反推 catalog API 基址。 */
    private static final String MANAGEMENT_SUFFIX = "/api/management/v1";

    private final String jobId;
    private final String managementUrl;
    private final String callbackToken;
    private final String catalogToken;
    private final String catalogUrl;

    private OptimizerOptions(String jobId, String managementUrl, String callbackToken,
                             String catalogToken, String catalogUrl) {
        this.jobId = jobId;
        this.managementUrl = managementUrl;
        this.callbackToken = callbackToken;
        this.catalogToken = catalogToken;
        this.catalogUrl = catalogUrl;
    }

    public String jobId() {
        return jobId;
    }

    /** 管理 API 基址，形如 {@code http://lakehouse-rest-server:8080/api/management/v1}。 */
    public String managementUrl() {
        return managementUrl;
    }

    /** 批次级回调令牌（请求头 {@code X-Optimization-Token}）。 */
    public String callbackToken() {
        return callbackToken;
    }

    /** catalog 数据面令牌；未配置时为 {@code null}，表示目标部署不需要。 */
    public String catalogToken() {
        return catalogToken;
    }

    /** catalog API 基址，默认由管理 API 基址去掉固定后缀推出。 */
    public String catalogUrl() {
        return catalogUrl;
    }

    /**
     * 解析参数，缺必需项时抛出带提示的异常。
     *
     * <p>不使用第三方 CLI 库：三个参数、两处可选，手写比引依赖划算，而且这里能给出
     * 「谁负责传这个参数」的提示——看到这条错误的人通常是在改 k8s Job 定义，
     * 告诉他参数由服务端生成，比给他一份用法更有用。
     */
    public static OptimizerOptions parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> bare = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                bare.add(arg);
                continue;
            }
            String name = arg.substring(2);
            int equals = name.indexOf('=');
            if (equals > 0) {
                values.put(name.substring(0, equals), name.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + arg);
            }
            values.put(name, args[++i]);
        }
        // 手工调试时最常忘的是 --，放行「第一个位置参数当 job id」但不宣导。
        if (!values.containsKey("job-id") && !bare.isEmpty()) {
            values.put("job-id", bare.get(0));
        }
        String jobId = require(values, "job-id");
        String managementUrl = trimSlash(require(values, "management-url"));
        String callbackToken = require(values, "token");
        String explicitCatalogUrl = values.get("catalog-url");
        String catalogUrl = explicitCatalogUrl == null || explicitCatalogUrl.isBlank()
                ? deriveCatalogUrl(managementUrl)
                : trimSlash(explicitCatalogUrl);
        return new OptimizerOptions(jobId, managementUrl, callbackToken,
                blankToNull(values.get("catalog-token")), catalogUrl);
    }

    /**
     * 从管理 API 基址推出 catalog API 基址。
     *
     * <p>两者挂在同一个服务端上，只需要传一个地址。推导失败时（比如基址被网关改写过）
     * 用 {@code --catalog-url} 显式指定——这个参数的存在就是为了让推导不必猜。
     */
    private static String deriveCatalogUrl(String managementUrl) {
        return managementUrl.endsWith(MANAGEMENT_SUFFIX)
                ? managementUrl.substring(0, managementUrl.length() - MANAGEMENT_SUFFIX.length())
                : managementUrl;
    }

    private static String require(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument --" + name
                    + "; the server passes it when creating the optimizer job"
                    + " (see KubernetesJobSubmitter), so when running by hand copy the arguments"
                    + " from the generated job definition");
        }
        return value.trim();
    }

    private static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
