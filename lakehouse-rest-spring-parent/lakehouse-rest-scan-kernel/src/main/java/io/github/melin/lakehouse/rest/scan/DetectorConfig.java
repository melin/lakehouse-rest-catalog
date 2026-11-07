package io.github.melin.lakehouse.rest.scan;

import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 探测器配置的读写（{@code lakehouse_sensitive_type.detectors_json}）。
 *
 * <p><b>形状。</b>{@code detectors_json} 是一个「按层给分」的配置，不是一个「规则列表」：
 *
 * <pre>{@code
 * {
 *   "name":  { "patterns": ["id_card", "身份证"], "weight": 1.0 },
 *   "type":  { "types": ["char", "varchar"], "lengths": [[15, 18]], "weight": 0.8 },
 *   "value": { "regex": "^[1-9]\\d{5}...$", "validator": "CN_ID_CARD", "weight": 1.0 },
 *   "dict":  { "values": ["男", "女"], "weight": 1.0 },
 *   "stat":  { "maxCardinalityRatio": 0.9, "weight": 1.0 }
 * }
 * }</pre>
 *
 * <p><b>为什么按层而不是按规则。</b>打分公式是固定的加权和
 * （§5.2：{@code 0.35·s_name + 0.10·s_type + 0.40·s_value + 0.10·s_dict + 0.05·s_stat}）——
 * 每一层最多贡献一项。若配成规则列表，「列名命中两次」该怎么算就成了实现细节，
 * 而它直接影响置信度、进而影响「自动落标还是进人审」这个治理决定。
 * 按层配 + 层内取最大命中度，让「这一层有多像」在配置里就是一句话。
 *
 * <p><b>层内 {@code weight} 是「这一层最多算多像」，不是「再加一档权重」。</b>
 * 打分公式里的层系数（0.35 / 0.10 / …）由代码固定（它对应的是「名字比类型可信」
 * 这类领域判断，不该让每个类型各配一套），层内 {@code weight} 只用来表达
 * 「这个类型的名称规则不如那个类型可信」。默认 1.0 = 满值。
 */
public final class DetectorConfig {

    /** 名称 / 类型两层的配置（P0 实现的两层）。 */
    public record LayerConfig(List<String> patterns, List<String> types, List<List<Integer>> lengths,
                              String regex, String validator, List<String> values,
                              Double maxCardinalityRatio, double weight) {

        /** 空配置：这一层不存在，跳过。 */
        public static LayerConfig empty() {
            return new LayerConfig(List.of(), List.of(), List.of(), null, null, List.of(), null, 1.0);
        }

        public boolean isEmpty() {
            return patterns.isEmpty() && types.isEmpty() && lengths.isEmpty()
                    && regex == null && validator == null && values.isEmpty()
                    && maxCardinalityRatio == null;
        }
    }

    private final Map<DetectorKind, LayerConfig> layers;

    private DetectorConfig(Map<DetectorKind, LayerConfig> layers) {
        this.layers = layers;
    }

    /** 全部为空的配置（内置类型没配某一层时）。 */
    public static DetectorConfig empty() {
        return new DetectorConfig(new LinkedHashMap<>());
    }

    /**
     * 由「层 → 配置」的表直接构造（seed 与测试用）。
     *
     * <p>与 {@link #parse(String)} 的关系是「构造 / 解析」而非重复：seed 侧手里本来就是
     * 一份结构化的表（{@code BuiltinSensitiveTypes}），绕一圈先序列化成 JSON 再解析回来
     * 只会让「seed 写进去的东西」与「解析回来的东西」之间多一个可能出错的环节——
     * 而那个环节出错的表现是「内置类型的探测器配了但不生效」，与意图正相反。
     *
     * <p>空的层会被丢掉（与 {@code parse} 的 {@code isEmpty()} 判据一致）：
     * 「没有这一层」与「这一层配了个空对象」在打分上是同一件事（都得 0 分），
     * 保留两种表示只会让往返比较变麻烦。
     */
    public static DetectorConfig of(Map<DetectorKind, LayerConfig> layers) {
        Map<DetectorKind, LayerConfig> kept = new LinkedHashMap<>();
        if (layers != null) {
            layers.forEach((kind, config) -> {
                if (kind != null && config != null && !config.isEmpty()) {
                    kept.put(kind, config);
                }
            });
        }
        return new DetectorConfig(kept);
    }

    /**
     * 从**线格式**（{@code {"name": {...}, "value": {...}}}，键是层名的小写）还原。
     *
     * <p>扫描作业不解析 {@code detectors_json} 原文——那是服务端库里的一列，作业见不到它；
     * 服务端解析好、按这个形状随执行清单下发。两个世界各有自己的 JSON 库（Spring Boot 4 是
     * Jackson 3，Spark 3.5 是 Jackson 2），而**这个类一行 JSON 都不碰**，所以两边都能编。
     * 认不出的层名忽略：与「某一层配坏了不该让整个类型不可用」同一口径。
     */
    public static DetectorConfig ofWire(Map<String, LayerConfig> layers) {
        Map<DetectorKind, LayerConfig> parsed = new LinkedHashMap<>();
        if (layers != null) {
            layers.forEach((name, config) -> {
                if (name == null || config == null) {
                    return;
                }
                // **不能用 DetectorKind.parse(name) 直接吃线格式的键**：那个 parse 只认大写
                // 枚举名（存库口径），而线格式的键是小写（与 detectors_json 逐字一致）。
                // 直接调它的结果是「所有层都被忽略、判定恒空」——不报错、不抛异常，
                // 只是每个类型都扫不出东西，看起来像「这批数据真的不敏感」。
                String wire = name.trim().toLowerCase(Locale.ROOT);
                for (DetectorKind kind : DetectorKind.values()) {
                    if (kind.name().toLowerCase(Locale.ROOT).equals(wire)) {
                        parsed.put(kind, config);
                        return;
                    }
                }
            });
        }
        return of(parsed);
    }

    /**
     * 线格式：层名小写 → 配置。用于随执行清单下发。
     *
     * <p>与 {@code detectors_json} 的键逐字一致（{@code name} / {@code type} / {@code value} / …），
     * 因此服务端的解析与序列化只差一层「结构 vs 文本」，不会出现两套名字。
     */
    public Map<String, LayerConfig> wireLayers() {
        Map<String, LayerConfig> out = new LinkedHashMap<>();
        layers.forEach((kind, config) -> out.put(kind.name().toLowerCase(Locale.ROOT), config));
        return out;
    }

    /** 某一层的配置；没配就是 {@link LayerConfig#empty()}。 */
    public LayerConfig layer(DetectorKind kind) {
        return layers.getOrDefault(kind, LayerConfig.empty());
    }

    /** 该类型实际配了哪几层；控制台与测试用它回答「这个类型靠什么识别」。 */
    public List<DetectorKind> configuredLayers() {
        return List.copyOf(layers.keySet());
    }

    /**
     * 名称层：任一 pattern 命中列名或列注释即算命中。
     *
     * <p>返回「命中了几条 / 共几条」而不是布尔——两个 pattern 都命中比只命中一个
     * 更像真的，但层内不做加权（那会让「一个类型配了 10 条同义正则」只因为
     * 配置得更啰嗦就更容易自动落标）。
     */
    public static double nameHit(List<String> patterns, String columnName, String comment) {
        if (patterns.isEmpty()) {
            return 0.0;
        }
        String haystack = (nz(columnName) + " " + nz(comment)).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return 0.0;
        }
        long hits = patterns.stream()
                .map(pattern -> pattern.toLowerCase(Locale.ROOT))
                .filter(haystack::contains)
                .count();
        return hits == 0 ? 0.0 : 1.0;
    }

    /**
     * 类型层：类型名在 {@code types} 里**且**长度落在某个 {@code [min, max]} 区间内。
     *
     * <p>{@code lengths} 为空 = 只看类型名。区间是闭区间（{@code char(18)} 要能匹配
     * {@code [15,18]}）。
     */
    public static double typeHit(LayerConfig config, String normalizedType, Integer length) {
        if (config.types().isEmpty()) {
            return 0.0;
        }
        String type = nz(normalizedType);
        boolean typeMatches = config.types().stream()
                .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT).equals(type));
        if (!typeMatches) {
            return 0.0;
        }
        if (config.lengths().isEmpty()) {
            return 1.0;
        }
        if (length == null) {
            return 0.0;
        }
        return config.lengths().stream()
                .filter(range -> range.size() >= 2)
                .anyMatch(range -> length >= range.get(0) && length <= range.get(1)) ? 1.0 : 0.0;
    }

    /** 值层的正则是否命中（P1 起有采样才用得上；这里只提供编译好的判定）。 */
    public static Optional<Pattern> compile(String regex) {
        if (regex == null || regex.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (RuntimeException e) {
            // 正则写错 = 这一层没有可用判据，与「没配」等价
            return Optional.empty();
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
