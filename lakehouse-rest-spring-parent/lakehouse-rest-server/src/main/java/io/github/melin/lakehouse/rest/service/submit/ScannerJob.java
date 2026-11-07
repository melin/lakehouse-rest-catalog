package io.github.melin.lakehouse.rest.service.submit;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.exec.ResolvedCluster;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感识别扫描作业的 k8s 报文（设计见 {@code docs/sensitive-data-design.md} §5.6.5 / §5.6.7）。
 *
 * <p><b>骨架照抄 {@link OptimizerJob}，三处刻意不同：</b>
 * <ol>
 *   <li><b>资源名前缀 {@code paimon-scan-}、容器名 {@code scanner}。</b>两类作业在同一台集群、
 *       同一个 namespace 里并存，名字撞上时 k8s 只会给你一个 409，而排查者看不出这两件事
 *       本来就不该撞；</li>
 *   <li><b>超时与 TTL 读扫描那一组</b>（{@code scanJobTimeoutSeconds}），不读优化那组。
 *       一次采样扫描和一次压缩批次的耗时差一个量级，共用一个数会让「把优化超时调短」
 *       把扫描也判超时；</li>
 *   <li><b>多一段 {@code --conf}</b>。扫描作业的 Spark 参数（内存 / 并行度）按 catalog 配，
 *       默认段是 {@code 1g / local[1]}（§5.6.7）——不预置 {@code shuffle.partitions}：
 *       小数据量下 200 个分区是纯开销，而扫描的 shuffle 量比 compaction 更小。</li>
 * </ol>
 *
 * <p><b>入口是同一个镜像里的另一个 main class</b>（{@code SensitivityScannerJob}）：
 * 这里用 {@code --class} 指定它，工作负载参数只有 {@code --scan-id} 等应用参数；
 * 镜像级 ENTRYPOINT 仍指向优化任务，扫描作业靠 {@code command} 覆盖它。
 * 用 {@code command} 而不是在镜像里加第二个 ENTRYPOINT：镜像只有一个 ENTRYPOINT，
 * 而「同一份运行时 + 另一个入口」正是 §5.6.5 选择共用镜像的原因（三种格式的运行时与
 * Lance 的 arrow 类路径排序都白拿）。
 */
public final class ScannerJob {

    /** 资源名前缀。k8s 名字上限 63 字符，扫描 id 是 32 位十六进制，加前缀仍在限内。 */
    public static final String NAME_PREFIX = "paimon-scan-";

    /** 容器名。与优化任务的 {@code optimizer} 分开，{@code kubectl logs} 时一眼能认出是哪个。 */
    public static final String CONTAINER_NAME = "scanner";

    /** 扫描作业的入口类（与优化任务同一个 jar）。 */
    public static final String MAIN_CLASS = "io.github.melin.lakehouse.rest.optimizer.SensitivityScannerJob";

    /** 镜像里的 jar 路径，与 {@code lakehouse-rest-optimizer/Dockerfile} 的 ENTRYPOINT 一致。 */
    private static final String OPTIMIZER_JAR = "/opt/optimizer/optimizer.jar";

    /**
     * Lance 的 spark bundle 路径，与 Dockerfile 里 {@code --conf spark.driver.extraClassPath}
     * 那一行是**同一个文件**。
     *
     * <p>必须在这里重写一遍：本作业用 {@code command} 覆盖了镜像的 ENTRYPOINT，覆盖是整条
     * 命令的替换而不是追加，所以 ENTRYPOINT 里的 {@code extraClassPath} 不会自动继承——
     * 漏了它的症状是 Lance 表扫描时 {@code NoSuchMethodError}（bundle 里那份未 relocate 的
     * arrow 没能排到 Spark 自带 {@code arrow-vector-12.0.1.jar} 前面）。
     */
    private static final String LANCE_BUNDLE = "/opt/spark/jars/lance-spark-bundle-3.5_2.12.jar";

    /** 跑完后至少留一小时给排障；与优化任务同一个下限。 */
    public static final int TTL_SECONDS_AFTER_FINISHED = 3600;

    /** TTL 相对超时的余量：覆盖「deadline 到点后一到两拍才被收尾」的窗口。 */
    public static final int TTL_MARGIN_SECONDS = 600;

    /**
     * 默认 Spark 参数段（§5.6.7 拍板：{@code 1g} / {@code local[1]}）。
     *
     * <p><b>为什么是这两个数而不是 4g / local[4]。</b>负载特征是「拉一批列的样本回来打分」，
     * 峰值取决于「列数 × 采样行上限」，不取决于表本身多大；而容器就是 driver
     * （镜像 ENTRYPOINT 是 {@code --master local[*]}，这里配 {@code local[N]} 收窄它）。
     * {@code N=1} 的含义是**单 pod 内串行**，与「一个扫描 = 一个 Job，不组批」是同一句话的
     * 两面：横向扩展的单位是 pod 数，而「这台集群上真正并行跑着几个作业」的答案因此
     * 完全留在 {@code maxConcurrentJobs} 一处，而不是「作业数 × 每作业线程数」两处相乘。
     *
     * <p>没有 {@code spark.sql.shuffle.partitions}：它不是「能跑起来的起点」而是一个
     * 性能陷阱——留着等于每次小扫描都白起 200 个分区。需要它的部署按 catalog 显式写上
     * （该键在白名单里）。
     */
    private static final Map<String, String> DEFAULT_CONF = Map.of(
            "spark.driver.memory", "1g",
            "spark.driver.cores", "1");

    private ScannerJob() {
    }

    /** 资源名。提交与回收共用它——同名才有「重投命中已存在 Job」这条幂等。 */
    public static String resourceName(String scanId) {
        return NAME_PREFIX + scanId;
    }

    /**
     * 清理延迟：{@code max(1 小时, 扫描超时 + 10 分钟)}。
     *
     * <p>与 {@link OptimizerJob#ttlSecondsAfterFinished} 同一条推导，只是超时取扫描那一组：
     * TTL 小于超时会造出「执行其实成功了、服务端只能看到 UNKNOWN、最后按 deadline 判超时」
     * 这种把成功记成失败的坑。
     */
    public static int ttlSecondsAfterFinished(ResolvedCluster cluster) {
        return Math.max(TTL_SECONDS_AFTER_FINISHED, cluster.scanJobTimeoutSeconds() + TTL_MARGIN_SECONDS);
    }

    /**
     * 合并后的 {@code --conf} 列表：默认段垫底，catalog 配置覆盖它。
     *
     * <p><b>白名单在这里再兜一次</b>（{@link ExecutionClusterService#allowedSparkConfKey}）：
     * 写入口已经过滤过，但手工改库能绕过 API，而 {@code scan_spark_conf} 一旦承载
     * {@code spark.sql.catalog.*.token} 这类键，它就从「调内存」变成了「改认证目标」的旁路。
     * 这里丢弃的键只记日志——提交路径上抛异常会把一次扫描变成一条只能靠删配置恢复的失败。
     */
    public static List<String> confEntries(ResolvedCluster cluster) {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULT_CONF);
        for (String entry : cluster.scanSparkConf()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = entry.substring(0, eq).trim();
            if (!ExecutionClusterService.allowedSparkConfKey(key)) {
                continue;
            }
            merged.put(key, entry.substring(eq + 1).trim());
        }
        List<String> conf = new ArrayList<>(merged.size());
        merged.forEach((key, value) -> conf.add(key + "=" + value));
        return conf;
    }

    /**
     * 构造 Job。
     *
     * @param callbackToken 本次扫描的回调令牌（作用域 = 这一次扫描），落进 {@code --token}
     */
    public static Job build(String scanId, ResolvedCluster cluster, String callbackToken) {
        List<String> command = new ArrayList<>();
        command.add("/opt/spark/bin/spark-submit");
        // local[1] 是**收窄**而不是放开：镜像 ENTRYPOINT 写的是 local[*]，容器有几个 CPU
        // 就开几个线程；扫描的横向扩展单位是 pod 数，单 pod 内串行（§5.6.7）
        command.add("--master");
        command.add("local[1]");
        command.add("--class");
        command.add(MAIN_CLASS);
        command.add("--conf");
        command.add("spark.ui.enabled=false");
        command.add("--conf");
        command.add("spark.driver.extraClassPath=" + LANCE_BUNDLE);
        for (String conf : confEntries(cluster)) {
            command.add("--conf");
            command.add(conf);
        }
        // jar 必须在应用参数之前、Spark 参数之后：args 里的 --scan-id 是**应用**参数，
        // 提前出现会被 spark-submit 当成自己的参数而报 "Unrecognized option"
        command.add(OPTIMIZER_JAR);

        List<String> args = new ArrayList<>();
        args.add("--scan-id");
        args.add(scanId);
        args.add("--management-url");
        args.add(OptimizerJob.managementApiUrl(cluster.callbackBaseUrl()));
        args.add("--token");
        args.add(callbackToken);
        // 数据面令牌可选：没开认证的部署不需要它。传空串会让作业以为「有令牌但为空」
        if (cluster.catalogToken() != null && !cluster.catalogToken().isBlank()) {
            args.add("--catalog-token");
            args.add(cluster.catalogToken());
        }

        List<LocalObjectReference> pullSecrets = new ArrayList<>();
        for (String name : cluster.imagePullSecrets()) {
            pullSecrets.add(new LocalObjectReferenceBuilder().withName(name).build());
        }

        return new JobBuilder()
                .withNewMetadata()
                    .withName(resourceName(scanId))
                    .withNamespace(cluster.namespace())
                    .addToLabels("app", "paimon-scanner")
                    .addToLabels("lakehouse-rest-scan", scanId)
                .endMetadata()
                .withNewSpec()
                    // 重试是扫描内的逐表语义（一张表失败不该重跑整轮），k8s 重试只会
                    // 把「哪张表失败」的事实冲掉，并重复回调一遍已经上报过的表
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(ttlSecondsAfterFinished(cluster))
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "paimon-scanner")
                            .addToLabels("lakehouse-rest-scan", scanId)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withServiceAccountName(blankToNull(cluster.serviceAccount()))
                            .withImagePullSecrets(pullSecrets)
                            .addNewContainer()
                                .withName(CONTAINER_NAME)
                                .withImage(cluster.image())
                                .withImagePullPolicy(cluster.imagePullPolicy())
                                // 覆盖 ENTRYPOINT：同一个镜像、另一个 main class（§5.6.5）
                                .withCommand(command)
                                .withArgs(args)
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
