package io.github.melin.lakehouse.rest.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫描作业与服务端之间的协议（设计见 {@code docs/sensitive-data-design.md} §5.6.5）。
 *
 * <p><b>为什么这些记录放在内核模块里，而不是服务端的 DTO 里。</b>它们是**两端都要按同名字段
 * 读写**的东西：服务端按它序列化，集群上的作业按它反序列化。各写一份的代价不是「多写一个类」，
 * 而是「字段名改了一边、另一边读成 null」——而 null 在扫描里恰好是最容易被当成「没采到样」
 * 的那个值，症状是「下推之后置信度莫名其妙变低」，查起来没有尽头。
 *
 * <p><b>为什么全是 String 而不是枚举。</b>两个依赖世界的 JSON 库版本不同（Spring Boot 4 用
 * Jackson 3，Spark 3.5 用 Jackson 2），枚举的反序列化对大小写与未知值的容错并不一致。
 * 协议里用 {@link String} 承载 {@code mode} / {@code state} / {@code samplingMode}，
 * 两端各自用 {@link SensitivityEnums} 的 {@code parse} 解释——校验点只有一处，容错行为一致。
 *
 * <p><b>清单为什么分页。</b>一次 catalog 级扫描可能有几千张表、每张几十列；把整份清单
 * 放进一次响应，作业侧要先把几兆 JSON 读进内存再开始干活，而它真正需要的是「下一批要扫的
 * 表」。分页大小由执行集群的 {@code maxScanTablesPerJob} 决定（§5.6.6），作业按
 * {@link ScanJobSpec#hasMore()} 一直拉到没有为止。
 */
public final class ScanProtocol {

    private ScanProtocol() {
    }

    /**
     * 一页执行清单。
     *
     * @param scanId     扫描 id（作业把它回传给每个回调）
     * @param catalog    catalog 名（作业用它建 Spark 会话）
     * @param format     {@code PAIMON} / {@code ICEBERG} / {@code LANCE}：作业按它选
     *                   {@code spark.sql.catalog.<name>} 的实现与扩展。**必须随清单下发**，
     *                   作业侧查不到它（catalog 那一行只在服务端库里）；猜一个的后果不是
     *                   「报错」而是「每张表都以表不存在失败」，因为另一种格式的 catalog
     *                   实现连元数据都解析不了
     * @param mode       {@code METADATA_ONLY} / {@code SAMPLED}；下推通常是后者，
     *                   但 {@code allowMetadataOnSpark=true} 的部署会派发前者，
     *                   作业侧因此不能把它当常量
     * @param sampleSize 每张表最多读多少行
     * @param offset     本页的起始下标（从 0 开始）
     * @param limit      本页上限（= 执行集群的 {@code maxScanTablesPerJob}）
     * @param totalTables 范围内一共多少张表（作业用它算进度与「是否漏扫」）
     * @param hasMore    本页之后还有没有
     * @param types      候选敏感类型（每页都带，省得作业为它多开一个端点）
     * @param tables     本页的表
     */
    public record ScanJobSpec(String scanId,
                              String catalog,
                              String format,
                              String mode,
                              int sampleSize,
                              int offset,
                              int limit,
                              int totalTables,
                              boolean hasMore,
                              List<ScanType> types,
                              List<ScanTableSpec> tables) {
    }

    /**
     * 一个候选类型：判定所需的全部属性。
     *
     * <p>{@code detectors} 是**结构化**的层配置（键是层名小写），不是
     * {@code detectors_json} 原文：原文是服务端库里的一列，解析它需要 JSON 库，
     * 而本模块要同时编进 Jackson 3（Spring Boot）与 Jackson 2（Spark）两个世界。
     * 服务端解析好再下发，作业侧只做 {@link DetectorConfig#ofWire(Map)}。
     */
    public record ScanType(String typeCode,
                           String level,
                           double minConfidence,
                           boolean autoApply,
                           Map<String, DetectorConfig.LayerConfig> detectors) {

        /** 转成探测器要的入参；认不出的层名忽略（与进程内扫描同一口径）。 */
        public SensitivityDetectors.CandidateType toCandidate() {
            return new SensitivityDetectors.CandidateType(typeCode, level, minConfidence, autoApply,
                    DetectorConfig.ofWire(detectors));
        }
    }

    /**
     * 一张待扫的表。
     *
     * <p><b>列是服务端解析好下发的</b>（{@link ScanColumn}），不是 {@code schema_doc} 原文：
     * 作业侧不需要认识三种 schema 写法，也就不需要 JSON 库。代价是清单大一点，换来的是
     * 「格式变了一种，作业不用重新打镜像」。
     */
    public record ScanTableSpec(String database, String table, List<ScanColumn> columns) {

        /** {@code database.table} —— 与执行清单、item 上报、控制台展示同一写法。 */
        public String qualifiedName() {
            return database + "." + table;
        }
    }

    /**
     * 一张表的结果上报。
     *
     * <p><b>「没命中」也要上报</b>（§5.6.5 第 2 条）：{@code findings} 为空、{@code status}
     * 仍是 {@link #STATUS_SCANNED}，与「压根没跑」（{@link #STATUS_FAILED}）在服务端是两件事。
     * 覆盖率靠这个区分才成立——否则「扫完了没发现」与「漏扫」在控制台上长得一模一样。
     *
     * <p><b>三个状态常量是协议的一部分，不是「随便一个非空串」。</b>服务端只认
     * {@code SCANNED} 为成功，其余（含 {@code null}）一律计入 {@code tables_failed}。
     * 这条用常量表示而不是靠注释：一个拼成 {@code SUCCEEDED} 的字符串不会报错，
     * 它只是让整轮扫描在控制台上显示「扫了 0 张、失败 N 张」——数据其实都扫过。
     *
     * <p><b>只有两个状态，没有 {@code SKIPPED}。</b>服务端的计数只有
     * {@code tables_scanned} 与 {@code tables_failed} 两列，没有第三列承载「跳过了」——
     * 一个 {@code SKIPPED} 要么被算成已扫（谎报覆盖），要么被算成失败（谎报故障）。
     * 因此作业**凡是没真正读的表都报 {@code FAILED} 并带上原因**：没读完就是没读完，
     * 让控制台上看得见原因，比给它一个中性的名字有用。
     *
     * @param status {@link #STATUS_SCANNED} / {@link #STATUS_FAILED}
     */
    public record ScanItemReport(String database,
                                 String table,
                                 String status,
                                 int columnsScanned,
                                 String errorMessage,
                                 List<ScanFinding> findings) {

        /** 扫过（无论有没有命中）。服务端据此把这一张表计入 {@code tables_scanned}。 */
        public static final String STATUS_SCANNED = "SCANNED";

        /** 没扫成（读不出来、权限、格式不支持）。计入 {@code tables_failed}，不计入已扫。 */
        public static final String STATUS_FAILED = "FAILED";
    }

    /**
     * 一条命中。
     *
     * <p>{@code maskedSamples} 是**已经打过码**的样本（{@link SensitivityMasking}）：作业在
     * 出 Pod 之前就打完，服务端拿到的永远不是原值。这条不只对服务端成立——同一句话的意思
     * 是「原始行不出 Pod」，而打码是它唯一的执行点。
     *
     * <p>{@code scores} 的键用小写层名（{@code name} / {@code type} / {@code value}），
     * 与 {@code matched_detectors_json} 的既有写法一致，人审界面直接渲染。
     */
    public record ScanFinding(String column,
                             String typeCode,
                             double confidence,
                             String state,
                             Map<String, Double> scores,
                             List<String> maskedSamples,
                             String samplingMode) {

        /** 各层得分转成落库形状；{@code null} 的层不出现（「没跑这一层」与「跑了得 0 分」不同）。 */
        public static Map<String, Double> scores(Map<SensitivityEnums.DetectorKind, Double> layers) {
            Map<String, Double> out = new LinkedHashMap<>();
            if (layers == null) {
                return out;
            }
            for (Map.Entry<SensitivityEnums.DetectorKind, Double> entry : layers.entrySet()) {
                out.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
            }
            return out;
        }

        /** 把一份判定结果包成本次上报的一条（打码在这里做，调用方拿不到原值）。 */
        public static ScanFinding of(String column, SensitivityDetectors.Match match,
                                     List<String> values, String samplingMode) {
            List<String> masked = SensitivityMasking.maskSamples(values);
            return new ScanFinding(column, match.typeCode(), match.confidence(), match.state().name(),
                    scores(match.scores()), new ArrayList<>(masked),
                    masked.isEmpty() ? null : samplingMode);
        }
    }
}
