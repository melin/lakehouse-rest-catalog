package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import java.util.Optional;

/**
 * 标签键与标签值的构造口径（设计见 {@code docs/sensitive-data-design.md} §4.4 / §5.5）。
 *
 * <p><b>为什么标签键要有一个构造入口。</b>标签键现在有三处产生者与两处消费者：
 * 扫描落自动标签、{@code PUT /column-tags} 落手工标签、表级聚合落表级标签；
 * 而标签驱动脱敏（§8.1，P3）要按键前缀匹配。三处各拼一次字符串，
 * 漂移的表现是「这条标签策略匹配不上」——它不报错，只是那一列不被脱敏。
 * 收在这里，改口径只需要改一个地方。
 *
 * <p><b>键的形状是 {@code 类别.类型编码}</b>（{@code PII.CN_ID_CARD}）。
 * 用点号而不是冒号或斜杠：既有标签键（{@code owner.team}）已经是点号分层，
 * 同一张表里的键混用两种分隔符，按前缀筛就要写两套。
 *
 * <p><b>表级标签用 {@code sensitivity.level}</b>，它刻意**不**遵守类别.编码的形状：
 * 表级标签描述的不是「这一列是什么类型」，而是「这张表里最敏感的一列有多敏感」，
 * 它没有对应的敏感类型（§5.5「表级级别 = 该表所有列标签 max(level)」）。
 * 把它塞进 {@code PII.*} 下面会让人以为表级标签也能被 D1/D2 命中了。
 */
public final class SensitivityTags {

    /**
     * 表级标签的键。
     *
     * <p>{@code column_name} 为空串时用这个键（见 {@link ColumnRefs#TABLE_LEVEL_COLUMN}）：
     * 表级标签的值是该表所有列标签的 {@code max(level)}，供目录检索与
     * 「这张表能不能对外」的粗判。**它不参与策略展开**（设计稿 §13 末段的待确认项，
     * 当前取「不参与」）——脱敏是列级的动作，而表级标签不知道是哪一列。
     */
    public static final String TABLE_LEVEL_TAG_KEY = "sensitivity.level";

    private SensitivityTags() {
    }

    /**
     * 敏感标签键：{@code 类别.类型编码}。
     *
     * <p>类别取自**类型表当前的值**而不是探测器的推断：同一份类型配置改了类别之后，
     * 新落下的标签用新类别。这不是不一致——标签键记录的是「落标签那一刻这个类型属于哪一类」，
     * 而历史标签保留旧键正是「历史结论不被追溯改写」这条规则的体现。
     *
     * @param category 类别名（{@code SensitiveCategory} 的 name）
     * @param typeCode 类型编码（如 {@code CN_ID_CARD}）
     */
    public static String sensitiveTagKey(String category, String typeCode) {
        return (category == null || category.isBlank() ? "SENSITIVE" : category.trim())
                + "." + (typeCode == null ? "" : typeCode.trim());
    }

    /**
     * 从标签值里解出分级。
     *
     * <p>为什么要一个专门的解值方法：标签值是自由文本（手工标签如 {@code owner.team}
     * 的值根本不是分级），而「按分级过滤」「分级分布」「表级 max(level)」三处都要
     * 同一个判定。让它们各自 try/catch 一次，就会出现「一处把认不出的值当 L1、
     * 另一处当 null」。
     *
     * @return 分级；值不是 L1–L4 / CORE / IMPORTANT 时为空
     */
    public static Optional<SensitivityLevel> parseLevel(String tagValue) {
        return SensitivityLevel.parse(tagValue);
    }

    /**
     * 两个分级取更高的一档。
     *
     * <p>表级级别用 max 而不是「最后一个」：一列被降级、另一列是 L4 时，
     * 表级结论必须还是 L4——「这张表里有 L4」这件事不会因为别的地方改小了而改变。
     * {@code null} 按「没有分级」处理（既不属于更高也不属于更低）。
     */
    public static SensitivityLevel max(SensitivityLevel left, SensitivityLevel right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.rank() >= right.rank() ? left : right;
    }
}
