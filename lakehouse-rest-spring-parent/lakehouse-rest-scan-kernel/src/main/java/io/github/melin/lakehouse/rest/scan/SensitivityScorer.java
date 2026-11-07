package io.github.melin.lakehouse.rest.scan;

import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.FindingState;
import java.util.EnumMap;
import java.util.Map;

/**
 * 打分与三条决策带（设计见 {@code docs/sensitive-data-design.md} §5.2）。
 *
 * <p><b>公式是固定的加权和，不是可配置的规则引擎：</b>
 *
 * <pre>{@code
 * confidence = clamp(0.35·s_name + 0.10·s_type + 0.40·s_value + 0.10·s_dict + 0.05·s_stat, 0, 1)
 * }</pre>
 *
 * <p>这五个系数对应的是领域判断而不是环境配置：「名字像」比「类型像」可信得多
 * （{@code id_card} 这个列名本身就说明问题，而 {@code char(18)} 满库都是），
 * 「值像」是唯一接近确证的证据（0.40）。把系数做成每个类型可配，
 * 等于允许「A 库的身份证判定比 B 库松」，而这件事应该由 {@code min_confidence}
 * （阈值）表达——阈值是治理决定、系数是领域知识，混在一起就分不清
 * 「这次判定为什么松」是配置的还是算法的。
 *
 * <p><b>三条决策带的边界是闭开区间：</b>
 *
 * <table>
 *   <tr><td>{@code >= minConfidence} 且类型允许自动应用</td><td>落 tag，{@code AUTO_APPLIED}</td></tr>
 *   <tr><td>{@code >= 0.60} 但够不着阈值（或类型不允许自动）</td><td>{@code PENDING}，进人审队列</td></tr>
 *   <tr><td>{@code < 0.60}</td><td>丢弃，只计入扫描统计</td></tr>
 * </table>
 *
 * <p>「类型不允许自动应用」这条**不能靠调低阈值绕过**：{@code PERSON_NAME} 的
 * {@code auto_apply} 恒为 false，因此哪怕置信度 0.99 也只会进人审——它落在
 * 中间带，不是落标。这正是 §5.3 说的「防误脱敏」。
 */
public final class SensitivityScorer {

    /** 层系数（§5.2）。名字与值是设计稿的一部分，改这里等于改判定口径。 */
    private static final double WEIGHT_NAME = 0.35;
    private static final double WEIGHT_TYPE = 0.10;
    private static final double WEIGHT_VALUE = 0.40;
    private static final double WEIGHT_DICT = 0.10;
    private static final double WEIGHT_STAT = 0.05;

    /** 中间带下界（§5.2）：低于它就是「不值得让人看」。 */
    public static final double REVIEW_FLOOR = 0.60;

    /**
     * {@code METADATA_ONLY} 模式的丢弃下界。
     *
     * <p><b>为什么必须与 0.60 分开。</b>只跑 D1/D2 时，能达到的最高分是
     * {@code 0.35 + 0.10 = 0.45}（值层 0.40 需要采样，元数据模式下恒为 0）。
     * 沿用 0.60 的直接后果是**每次元数据扫描的结果恒为空**——P0 的
     * 「D1/D2 元数据扫描」就变成一条永远不产出结论的路径，而它恰恰是
     * 「刚接入一个 catalog、第一次想看看有什么敏感数据」时唯一能跑的路径
     * （采样要读明文，通常没人愿意在第一次接入时就全库采样）。
     *
     * <p>取 {@code 0.35} 的含义是「**名称层命中是元数据模式下最低的证据要求**」：
     * <ul>
     *   <li>{@code 0.45}（名字 + 形状都对）与 {@code 0.35}（只有名字对）
     *       都会产出 finding，进人审队列；</li>
     *   <li>{@code 0.10}（只有类型像，如 {@code char(18)}）**不产出**——
     *       定长字符列满库都是，光凭形状提不出任何有用的候选；</li>
     *   <li>一层都没命中（0）自然也不产出。</li>
     * </ul>
     *
     * <p>这些结论**永远不会自动落标**——它们到不了 {@code min_confidence}（默认 0.85），
     * 所以「元数据模式只提候选、不自动改数据」这条实务约束不依赖这个下界，
     * 由阈值保证（见 {@code metadataOnlyNeverReachesAutoApply} 那条用例）。
     */
    public static final double METADATA_FLOOR = 0.35;

    /** 有效样本少于这么多行时整体打折（§5.2）。 */
    public static final int MIN_TRUSTED_SAMPLES = 200;

    /** 样本不足时的整体折损系数。 */
    private static final double SMALL_SAMPLE_FACTOR = 0.7;

    /** 值命中率的满分门槛：占非空行 95% 以上算满分。 */
    private static final double HIT_RATE_FULL = 0.95;

    /** 值命中率的下限：低于 60% 记 0 分。 */
    private static final double HIT_RATE_FLOOR = 0.60;

    /** 校验器通过率门槛：不到 99% 不给满分（§5.2「校验器比正则重要」）。 */
    private static final double VALIDATOR_FULL = 0.99;

    private SensitivityScorer() {
    }

    /**
     * 各层得分（0–1）。缺的层按 0 计——**「没配」与「配了但没命中」在加权和里同值**，
     * 这与 §5.2 的公式一致：某类型不靠字典判，它的字典层就不该贡献分数。
     */
    public static Map<DetectorKind, Double> normalize(Map<DetectorKind, Double> raw) {
        Map<DetectorKind, Double> scores = new EnumMap<>(DetectorKind.class);
        for (DetectorKind kind : DetectorKind.values()) {
            Double value = raw.get(kind);
            scores.put(kind, clamp(value == null ? 0.0 : value));
        }
        return scores;
    }

    /**
     * 加权求和。
     *
     * @param scores        各层得分（见 {@link #normalize}）
     * @param effectiveRows 有效样本行数（非空行）；{@code METADATA_ONLY} 下传 0
     * @param sampled       {@code SAMPLED} 模式才做样本量折损——元数据模式没有
     *                     样本量的概念，不该因为「没采样」被打七折
     */
    public static double confidence(Map<DetectorKind, Double> scores, int effectiveRows, boolean sampled) {
        Map<DetectorKind, Double> s = normalize(scores);
        double total = WEIGHT_NAME * s.get(DetectorKind.NAME)
                + WEIGHT_TYPE * s.get(DetectorKind.TYPE)
                + WEIGHT_VALUE * s.get(DetectorKind.VALUE)
                + WEIGHT_DICT * s.get(DetectorKind.DICT)
                + WEIGHT_STAT * s.get(DetectorKind.STAT);
        if (sampled && effectiveRows < MIN_TRUSTED_SAMPLES) {
            total *= SMALL_SAMPLE_FACTOR;
        }
        return clamp(total);
    }

    /**
     * 值层的得分：由命中率与校验器通过率合成（§5.2）。
     *
     * <p>命中率部分：{@code hitRate >= 0.95} 记 1.0，线性降到 {@code 0.60} 记 0——
     * 是**分段线性**而不是「>0.95 满分、否则 0」，因为 0.93 与 0.30 显然不是一回事，
     * 一刀切会把「大部分是、少量脏数据」直接判死。
     *
     * <p>校验器部分：通过率 {@code < 0.99} 时把整层得分**乘上通过率**，而不是
     * 只判「过没过」——校验位是区分「真身份证」与「随便编的 18 位」的唯一手段，
     * 抽样里 3 成过不了校验的列，不该拿到与 100% 通过一样的分数。
     *
     * @param hits              值层命中的行数
     * @param nonNullRows       非空行数（**分母是非空行，不是总行数**：一列
     *                          90% 是 NULL 的表不该因为空值多而判不出敏感）
     * @param validatorPassRate 校验器通过率（无校验器时传 {@code null}）
     */
    public static double valueScore(long hits, long nonNullRows, Double validatorPassRate) {
        if (nonNullRows <= 0) {
            return 0.0;
        }
        double hitRate = (double) hits / nonNullRows;
        double score;
        if (hitRate >= HIT_RATE_FULL) {
            score = 1.0;
        } else if (hitRate <= HIT_RATE_FLOOR) {
            score = 0.0;
        } else {
            score = (hitRate - HIT_RATE_FLOOR) / (HIT_RATE_FULL - HIT_RATE_FLOOR);
        }
        if (validatorPassRate != null && validatorPassRate < VALIDATOR_FULL) {
            score *= clamp(validatorPassRate);
        }
        return clamp(score);
    }

    /**
     * 三条决策带（§5.2）。
     *
     * @param confidence  置信度
     * @param minConfidence 该类型（或 catalog 覆盖后的）自动应用阈值
     * @param autoApply   类型是否允许自动应用（**必须同时满足**才算自动落标）
     * @return {@code AUTO_APPLIED} / {@code PENDING} / {@code null}（丢弃）
     */
    public static FindingState decide(double confidence, double minConfidence, boolean autoApply) {
        return decide(confidence, minConfidence, autoApply, REVIEW_FLOOR);
    }

    /**
     * 三条决策带，下界可指定。
     *
     * <p>下界之所以要能传，是因为两条跑法的**可得分上限不同**：
     * {@code SAMPLED} 有五层、上限 1.0；{@code METADATA_ONLY} 只有 D1/D2、上限 0.45。
     * 用同一个下界会让其中一条恒定产不出结论（见 {@link #METADATA_FLOOR}）。
     *
     * @param floor 丢弃下界：{@code SAMPLED} 传 {@link #REVIEW_FLOOR}，
     *              {@code METADATA_ONLY} 传 {@link #METADATA_FLOOR}
     */
    public static FindingState decide(double confidence, double minConfidence,
                                      boolean autoApply, double floor) {
        if (confidence >= minConfidence && autoApply) {
            return FindingState.AUTO_APPLIED;
        }
        if (confidence >= floor) {
            return FindingState.PENDING;
        }
        return null;
    }

    /**
     * 分级是否允许自动产出。
     *
     * <p>{@code CORE} / {@code IMPORTANT} 只能人工打（§5.5 的对齐说明），
     * 而这两个取值不在任何类型的 {@code level} 配置里（类型表只有 {@code L1}–{@code L4}）。
     * 这一层断言防的是「有人手工往 detectors_json 或类型表里写了 CORE」——
     * 那时应当直接拒绝，而不是让扫描产出一条只有人能下的结论。
     */
    public static boolean levelAutomatable(String level) {
        return SensitivityEnums.SensitivityLevel.parse(level)
                .map(SensitivityEnums.SensitivityLevel::automatable)
                .orElse(false);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
