package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.DetectorConfig.LayerConfig;
import io.github.melin.lakehouse.rest.support.SchemaColumns.Column;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.DetectorKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D1（名称·注释）与 D2（类型·形状）两层探测器：**零 I/O，纯函数**（设计见
 * {@code docs/sensitive-data-design.md} §5.1）。
 *
 * <p><b>为什么这两层先做。</b>它们只读已经落库的 {@code schema_doc}，
 * 不需要采样、不需要凭据、不需要引擎支持，因此 {@code METADATA_ONLY} 模式
 * 也能跑、整轮没有存储侧压力。这也意味着它可以在「第一次接入某个 catalog」时
 * 立即跑一遍——没有这一层，用户要先等一次全量采样才能看到任何结论。
 *
 * <p><b>它的天花板也是明确的：</b>列名叫 {@code col1} 而值是身份证的表，
 * 这一层永远判不出来。所以它是「便宜的第一遍」，不是「够用的那一遍」——
 * D3–D5 补的正是这个缺口（P1）。
 *
 * <p><b>纯函数带来的可测性。</b>本类不依赖 Spring、不碰数据库，输入是
 * 「一个列 + 一份类型配置」，输出是各层得分。打分口径的任何调整都能被单元测试
 * 逐字钉住——而这类「阈值调一点点，线上就多落 200 条标签」的改动，
 * 恰恰是最需要被钉住的。
 */
public final class SensitivityDetectors {

    private SensitivityDetectors() {
    }

    /**
     * 一个列在同一份类型配置下的判定结果。
     *
     * @param typeCode 命中的类型编码
     * @param scores   各层得分；没跑的层不出现（{@link SensitivityScorer#normalize} 会补 0）
     * @param confidence 加权后的置信度
     * @param state    {@code AUTO_APPLIED} / {@code PENDING} / {@code null}（丢弃）
     */
    public record Match(String typeCode, Map<DetectorKind, Double> scores,
                        double confidence, SensitivityEnums.FindingState state) {
    }

    /**
     * 对一列跑 D1 + D2。
     *
     * <p><b>只返回「至少命中一层」的类型命中</b>：一层都没命中的类型不产出 finding，
     * 否则一次 catalog 扫描会给「类型数 × 列数」张表各留一条 0 分的记录，
     * finding 表会在一天内被噪声填满。
     *
     * @param catalogName  只用于组装日志与返回值，判定本身不看它
     * @param tableName    表名上下文（D1 的可选依据，当前不参与打分但保留给将来的
     *                     「表名是 {@code user_profile} 时姓名列更可信」这类规则）
     * @param column       列（名称 / 类型 / 注释）
     * @param types        候选类型：编码 + 分级 + 阈值 + 是否允许自动 + 探测器配置
     * @return 命中的类型，按置信度降序；空列表表示这列没被任何类型认出来
     */
    public static List<Match> detect(String catalogName, String tableName, Column column,
                                     List<CandidateType> types) {
        List<Match> matches = new ArrayList<>();
        for (CandidateType type : types) {
            Optional<Match> match = detectOne(column, type);
            match.ifPresent(matches::add);
        }
        matches.sort((left, right) -> Double.compare(right.confidence(), left.confidence()));
        return matches;
    }

    /**
     * 一个候选类型：判定所需的全部属性。
     *
     * <p>刻意不直接吃 {@code SensitiveTypeEntity}：core 的探测器不该依赖 core 的实体
     * （实体带审计列、带 JPA 注解，而判定只需要这几个值）。用 record 做参数，
     * 测试里可以一行构造出一个类型，不必去 new 一个 JPA 实体再逐字段 set。
     */
    public record CandidateType(String typeCode, String level, double minConfidence,
                                boolean autoApply, DetectorConfig detectors) {
    }

    private static Optional<Match> detectOne(Column column, CandidateType type) {
        DetectorConfig config = type.detectors();
        LayerConfig name = config.layer(DetectorKind.NAME);
        LayerConfig typeLayer = config.layer(DetectorKind.TYPE);

        Map<DetectorKind, Double> scores = new EnumMap<>(DetectorKind.class);
        // 层内 weight 乘在层得分上，**语义是「这一层最多算多像」而不是「再加一档权重」**
        // （见 DetectorConfig 的类注释）：层系数（0.35 / 0.10）由 SensitivityScorer 固定，
        // weight 只表达「这个类型的名称规则不如那个类型可信」。默认 1.0 = 满值。
        //
        // 漏乘这一步的表现很隐蔽：权重配了、库里存了、控制台也显示得出来，
        // **但判定结果一字不变**——而 §12 的 P0 验收断言恰恰是「改一个内置类型的
        // 探测器权重后，下一轮扫描的判定随之改变」。没有这一步，那条断言永远失败，
        // 失败的方式还是「看起来一切正常，只是改什么都不生效」。
        double sName = DetectorConfig.nameHit(name.patterns(), column.name(), column.comment())
                * name.weight();
        double sType = DetectorConfig.typeHit(typeLayer, column.normalizedType(), column.length())
                * typeLayer.weight();
        if (sName > 0) {
            scores.put(DetectorKind.NAME, sName);
        }
        if (sType > 0) {
            scores.put(DetectorKind.TYPE, sType);
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }

        // METADATA_ONLY 不采样：sampled=false 让打分器跳过「样本量不足打折」——
        // 一层都没采样的时候，那个折损是没有依据的惩罚。
        double confidence = SensitivityScorer.confidence(scores, 0, false);
        // 下界用元数据档（0.35）而不是采样档（0.60）：只跑 D1/D2 时最高只能到 0.45，
        // 用 0.60 会让这条路径恒不产出结论（见 SensitivityScorer.METADATA_FLOOR）。
        // 它们仍然到不了 min_confidence，所以「只提候选、不自动落标」不受影响。
        SensitivityEnums.FindingState state = SensitivityScorer.decide(
                confidence, type.minConfidence(), type.autoApply(), SensitivityScorer.METADATA_FLOOR);
        if (state == null) {
            // 低于中间带下界：丢弃，只计入扫描统计（§5.2）。返回空而不是带一个
            // null state 的 Match，调用方就不必再判一次。
            return Optional.empty();
        }
        // 分级本身不接受自动产出的类型（CORE / IMPORTANT）一律降级为待审：
        // 这两档按国标目录人工认定，探测器没有资格产出它们（§5.5）。
        if (state == SensitivityEnums.FindingState.AUTO_APPLIED
                && !SensitivityScorer.levelAutomatable(type.level())) {
            state = SensitivityEnums.FindingState.PENDING;
        }
        return Optional.of(new Match(type.typeCode(), scores, confidence, state));
    }
}
