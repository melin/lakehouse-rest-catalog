package io.github.melin.lakehouse.rest.optimizer;

import java.util.Map;

/**
 * 扫描任务的启动参数。
 *
 * <p>与 {@link OptimizerOptions} 同形，只有第一个参数的名字不同（{@code --scan-id}）：
 * 两个入口是同一个镜像、同一个 jar，靠参数名区分不出来谁是谁，因此名字**必须不同**——
 * 一个扫描作业被误传 {@code --job-id} 时，它应该报「缺 --scan-id」而不是安静地
 * 拿优化批次号去查扫描清单（那会 404，然后按「拉不到清单」退出，日志里一句话都不提参数）。
 *
 * <p><b>表清单不走参数</b>（同 {@link OptimizerOptions} 的理由，且这里的量更大）：
 * 一次 catalog 级扫描可能有几千张表、每张几十列，序列化进命令行既超长度上限，又会让
 * 「集群里跑的那份清单」与「库里的范围」产生第二个版本。作业按 {@code hasMore} 分页拉，
 * 每页拉的都是那一刻的真相。
 *
 * @param scanId         扫描 id
 * @param managementUrl  管理 API 基址（含 {@code /api/management/v1}）
 * @param callbackToken  回调令牌（头 {@code X-Sensitivity-Scan-Token}）
 * @param catalogToken   数据面令牌；没开认证的部署为 {@code null}
 * @param catalogUrl     数据面根地址（由管理 API 基址去掉固定后缀推出）
 */
record ScannerOptions(String scanId,
                      String managementUrl,
                      String callbackToken,
                      String catalogToken,
                      String catalogUrl) {

    /** 管理 API 在服务端上的固定后缀，用来反推 catalog API 基址（与优化任务同一个）。 */
    private static final String MANAGEMENT_SUFFIX = "/api/management/v1";

    /**
     * 解析参数。
     *
     * <p>词法交给 {@link JobArgs}：与优化任务共用一份，两个入口对
     * 「{@code --token=abc} 算不算给了值」的判断因此一致。
     */
    static ScannerOptions parse(String[] args) {
        Map<String, String> values = JobArgs.parse(args, "scan-id");
        String scanId = JobArgs.require(values, "scan-id", "Fabric8ScanJobSubmitter");
        String managementUrl = JobArgs.trimSlash(
                JobArgs.require(values, "management-url", "Fabric8ScanJobSubmitter"));
        String callbackToken = JobArgs.require(values, "token", "Fabric8ScanJobSubmitter");
        String explicitCatalogUrl = values.get("catalog-url");
        String catalogUrl = explicitCatalogUrl == null || explicitCatalogUrl.isBlank()
                ? deriveCatalogUrl(managementUrl)
                : JobArgs.trimSlash(explicitCatalogUrl);
        return new ScannerOptions(scanId, managementUrl, callbackToken,
                JobArgs.blankToNull(values.get("catalog-token")), catalogUrl);
    }

    private static String deriveCatalogUrl(String managementUrl) {
        return managementUrl.endsWith(MANAGEMENT_SUFFIX)
                ? managementUrl.substring(0, managementUrl.length() - MANAGEMENT_SUFFIX.length())
                : managementUrl;
    }
}
