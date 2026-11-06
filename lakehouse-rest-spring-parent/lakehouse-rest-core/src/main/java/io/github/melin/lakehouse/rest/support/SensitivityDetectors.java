package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.DetectorConfig.LayerConfig;
import io.github.melin.lakehouse.rest.support.SchemaColumns.Column;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.DetectorKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * D1（名称·注释）、D2（类型·形状）与 D3（值模式）三层探测器：**零 I/O，纯函数**
 * （设计见 {@code docs/sensitive-data-design.md} §5.1）。
 *
 * <p><b>为什么前两层先做、第三层后补。</b>D1/D2 只读已经落库的 {@code schema_doc}，
 * 不需要采样、不需要凭据、不需要引擎支持，因此 {@code METADATA_ONLY} 模式
 * 也能跑、整轮没有存储侧压力。这也意味着它可以在「第一次接入某个 catalog」时
 * 立即跑一遍——没有这一层，用户要先等一次全量采样才能看到任何结论。
 *
 * <p><b>它的天花板也是明确的：</b>列名叫 {@code col1} 而值是身份证的表，
 * 前两层永远判不出来。D3（本类 P1 补上的那一层）是唯一接近确证的证据，
 * 但它需要**采样读明文**——因此它只在 {@code SAMPLED} 模式、且这一列真的
 * 读到了样本时才参与打分（见 {@link ColumnValues}）。
 *
 * <p><b>纯函数带来的可测性。</b>本类不依赖 Spring、不碰数据库，输入是
 * 「一个列 + 一份类型配置 + 这一列的样本」，输出是各层得分。打分口径的任何调整
 * 都能被单元测试逐字钉住——而这类「阈值调一点点，线上就多落 200 条标签」的改动，
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
     * 一列的采样值（**原值，只在内存里活一次**）。
     *
     * <p><b>它不是 DTO，也不许被序列化。</b>传进本类是为了算值层命中率；出了
     * {@link SensitivityDetectors} 之后，能落到任何地方的只有
     * {@link SensitivityMasking#maskSamples} 打码过的那一份（§5.4 的唯一不变量）。
     * 之所以不直接传 {@code List<String>}：那样「这是原值」这件事就只剩参数名，
     * 而参数名不会拦住有人顺手把它塞进日志。
     *
     * <p><b>{@code null} 元素保留</b>：值层的分母是「非空行」
     * （{@code SensitivityScorer#valueScore}），把 null 在入口就滤掉会让
     * 「一列 90% 是 NULL、非空部分全是身份证」的表算出一个虚高的命中率。
     *
     * @param values 采样读到的值，按行顺序
     */
    public record ColumnValues(List<String> values) {

        public ColumnValues {
            // 刻意不用 List.copyOf：它会拒绝 null 元素，而 null 元素恰恰是分母的一部分
            // （见类注释）。这里要的是「不可变但允许 null」的副本。
            values = values == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(values));
        }

        /** 元数据模式：没有采样。{@link #sampled()} 为 {@code false}。 */
        public static ColumnValues none() {
            return new ColumnValues(List.of());
        }

        /** 这一列到底采到样没有——**空列表也算没采到**（读失败与没读是同一件事）。 */
        public boolean sampled() {
            return !values.isEmpty();
        }

        /** 非空行数：值层命中率的分母。 */
        public long nonNullRows() {
            long count = 0;
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    count++;
                }
            }
            return count;
        }
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
        return detect(catalogName, tableName, column, types, ColumnValues.none());
    }

    /**
     * 对一列跑 D1 + D2 + D3（值层）。
     *
     * <p><b>{@code values} 为空时的行为与 P0 完全一致</b>：只有两层参与打分，
     * 丢弃下界用 {@link SensitivityScorer#METADATA_FLOOR}。这一点很重要——
     * {@code METADATA_ONLY} 与「{@code SAMPLED} 但这一列读失败」走的是同一条路，
     * 不该因为「这一轮本来想采样」就把一条本可以提出的候选判死。
     *
     * @param values 这一列的采样值（原值，只在内存里活一次，见 {@link ColumnValues}）
     */
    public static List<Match> detect(String catalogName, String tableName, Column column,
                                     List<CandidateType> types, ColumnValues values) {
        ColumnValues samples = values == null ? ColumnValues.none() : values;
        List<Match> matches = new ArrayList<>();
        for (CandidateType type : types) {
            Optional<Match> match = detectOne(column, type, samples);
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

    private static Optional<Match> detectOne(Column column, CandidateType type, ColumnValues values) {
        DetectorConfig config = type.detectors();
        LayerConfig name = config.layer(DetectorKind.NAME);
        LayerConfig typeLayer = config.layer(DetectorKind.TYPE);
        LayerConfig valueLayer = config.layer(DetectorKind.VALUE);

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
        // ---- D3 值层（P1）。这一段只在真的采到样时才算：没采样时它必须完全不出分，
        // 否则 METADATA_ONLY 会被「值层 0 分」拉低——而 0 分与「没这一层」在加权和里
        // 同值，但在**解释**上不同：人审看到 {"name":0.35,"value":0.0} 会以为
        // 「值不像」，而实际是「没看值」。
        ValueTally tally = tally(valueLayer, values);
        if (tally.score() > 0) {
            scores.put(DetectorKind.VALUE, tally.score());
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }

        // 有效样本行数只影响「样本量不足打折」，因此只在采样时传
        // （METADATA_ONLY 传 0 + sampled=false，见 SensitivityScorer.confidence 的注释）。
        double confidence = SensitivityScorer.confidence(scores, (int) Math.min(tally.nonNullRows(),
                Integer.MAX_VALUE), values.sampled());
        // 下界随「这一列到底看没看到值」走，而不是随这次扫描的模式走：
        // 模式是 SAMPLED 但这一列读失败时，它实际只有元数据证据，用 0.60 会把
        // 一条本可提出来的候选判死（与 P0 的 METADATA_FLOOR 同一理由）。
        double floor = values.sampled()
                ? SensitivityScorer.REVIEW_FLOOR
                : SensitivityScorer.METADATA_FLOOR;
        SensitivityEnums.FindingState state = SensitivityScorer.decide(
                confidence, type.minConfidence(), type.autoApply(), floor);
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

    /**
     * 值层的统计结果。
     *
     * @param score       乘过层 {@code weight} 的层得分（0–1）
     * @param nonNullRows 非空行数（样本量折损与命中率的分母）
     */
    private record ValueTally(double score, long nonNullRows) {
    }

    /**
     * 统计一列在一份类型配置下的值层得分。
     *
     * <p><b>「正则命中」与「校验器通过」是两件事，都要算。</b>命中率回答「有多少像」，
     * 校验率回答「像的那些里有多少是真的」。{@code SensitivityScorer.valueScore}
     * 把后者乘在整层上（§5.2 的「校验器比正则重要」）：抽样里三成过不了校验的列，
     * 不该拿到与 100% 通过一样的分数。
     *
     * <p><b>只配了校验器、没配正则时，所有非空值都算命中</b>——此时校验器本身就是
     * 判据（它比正则更严）。反过来只配正则、没配校验器时通过率传 {@code null}
     * （不打折），因为「没有校验器」不等于「校验不通过」。
     *
     * <p>正则用 {@link java.util.regex.Matcher#matches()} 而不是 {@code find()}：
     * 内置类型的值层正则都是整串锚定的，{@code find} 会让「串里含一段像身份证的
     * 子串」也计入命中，而那一列很可能是一段日志文本。
     */
    private static ValueTally tally(LayerConfig valueLayer, ColumnValues values) {
        if (valueLayer == null || valueLayer.isEmpty() || !values.sampled()) {
            return new ValueTally(0.0, 0);
        }
        Optional<Pattern> pattern = DetectorConfig.compile(valueLayer.regex());
        boolean hasValidator = SensitivityValidators.supports(valueLayer.validator());
        if (pattern.isEmpty() && !hasValidator) {
            // 这一层配了个认不出的形状：不是错误，但必须让它显式地不出分，
            // 而不是把「配错」表现成「所有值都像」。
            return new ValueTally(0.0, values.nonNullRows());
        }
        long nonNull = 0;
        long hits = 0;
        long checked = 0;
        long passed = 0;
        for (String raw : values.values()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            nonNull++;
            String text = raw.trim();
            boolean hit = pattern.map(regex -> regex.matcher(text).matches()).orElse(true);
            if (!hit) {
                continue;
            }
            hits++;
            if (hasValidator) {
                checked++;
                if (SensitivityValidators.isValid(valueLayer.validator(), text)) {
                    passed++;
                }
            }
        }
        Double passRate = checked == 0 ? null : (double) passed / checked;
        double score = SensitivityScorer.valueScore(hits, nonNull, passRate) * valueLayer.weight();
        return new ValueTally(score, nonNull);
    }
}
