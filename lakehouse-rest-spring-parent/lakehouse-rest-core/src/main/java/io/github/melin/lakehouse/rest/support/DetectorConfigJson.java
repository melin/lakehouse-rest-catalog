package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.scan.DetectorConfig;
import io.github.melin.lakehouse.rest.scan.DetectorConfig.LayerConfig;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * {@code detectors_json} 的文本 ↔ 结构转换。
 *
 * <p><b>为什么在 core 而不在 {@code lakehouse-rest-scan-kernel}。</b>内核模块要同时编进
 * 服务端（Spring Boot 4，Jackson 3 的 {@code tools.jackson}）与集群上的扫描作业
 * （Spark 3.5，Jackson 2 的 {@code com.fasterxml.jackson}）。它一旦引了其中任何一个，
 * 另一个世界里就会少类——所以内核里一行 JSON 都没有，判定只吃结构化的
 * {@link DetectorConfig}。
 *
 * <p>解析留在服务端还有一个更实的原因：{@code detectors_json} 是
 * {@code lakehouse_sensitive_type} 的一列，作业根本见不到它。作业拿到的是服务端解析好、
 * 随执行清单下发的结构化配置（{@link DetectorConfig#wireLayers()}）。
 *
 * <p><b>认不出的层直接忽略，不抛异常。</b>同一份 JSON 由控制台编辑、由 seed 生成、
 * 也可能被人手工改成写错的形状；把「某一层配坏了」升级成「整个类型不可用」会让一次笔误
 * 把已跑通的识别能力整个关掉。忽略的层表现为该层得 0 分——置信度会掉下来，但不会静默
 * 变成满分：<b>掉分比报错更安全，因为掉分只会让它进人审队列。</b>
 */
public final class DetectorConfigJson {

    private DetectorConfigJson() {
    }

    /** 解析 {@code detectors_json}；空串、非法 JSON、形状不对都折成「没有配置」。 */
    public static DetectorConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return DetectorConfig.empty();
        }
        JsonNode root;
        try {
            root = Json.readTree(json);
        } catch (RuntimeException e) {
            return DetectorConfig.empty();
        }
        Map<DetectorKind, LayerConfig> layers = new LinkedHashMap<>();
        for (DetectorKind kind : DetectorKind.values()) {
            JsonNode node = root.path(kind.name().toLowerCase(Locale.ROOT));
            if (node.isMissingNode() || node.isNull() || !node.isObject()) {
                continue;
            }
            LayerConfig config = new LayerConfig(
                    strings(node.path("patterns")),
                    strings(node.path("types")),
                    ranges(node.path("lengths")),
                    text(node.path("regex")),
                    text(node.path("validator")),
                    strings(node.path("values")),
                    number(node.path("maxCardinalityRatio")),
                    node.path("weight").asDouble(1.0));
            if (!config.isEmpty()) {
                layers.put(kind, config);
            }
        }
        return DetectorConfig.of(layers);
    }

    /**
     * 序列化回 {@code detectors_json}。
     *
     * <p>只写非空字段（{@code weight} 仅在不为 1.0 时写）：这份文本会被人审界面展示、
     * 被治理员编辑，满屏的默认值会让「这个类型到底配了什么」看不出重点。
     */
    public static String toJson(DetectorConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config != null) {
            for (Map.Entry<String, LayerConfig> entry : config.wireLayers().entrySet()) {
                LayerConfig layer = entry.getValue();
                Map<String, Object> body = new LinkedHashMap<>();
                if (!layer.patterns().isEmpty()) {
                    body.put("patterns", layer.patterns());
                }
                if (!layer.types().isEmpty()) {
                    body.put("types", layer.types());
                }
                if (!layer.lengths().isEmpty()) {
                    body.put("lengths", layer.lengths());
                }
                if (layer.regex() != null) {
                    body.put("regex", layer.regex());
                }
                if (layer.validator() != null) {
                    body.put("validator", layer.validator());
                }
                if (!layer.values().isEmpty()) {
                    body.put("values", layer.values());
                }
                if (layer.maxCardinalityRatio() != null) {
                    body.put("maxCardinalityRatio", layer.maxCardinalityRatio());
                }
                if (layer.weight() != 1.0) {
                    body.put("weight", layer.weight());
                }
                out.put(entry.getKey(), body);
            }
        }
        return Json.write(out);
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static List<List<Integer>> ranges(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<List<Integer>> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isArray() || item.size() < 2) {
                continue;
            }
            out.add(List.of(item.get(0).asInt(), item.get(1).asInt()));
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }
}
