package io.github.melin.lakehouse.rest.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内部 JSON 编解码入口。
 *
 * <p>仅用于把结构化元数据（表 schema、分区 spec、函数定义等）序列化成列内文本，
 * 以及反向还原。HTTP 请求/响应的绑定交给 Spring MVC 自身的消息转换器处理，
 * 两者互不影响。
 *
 * <p>唯一一处跨界的是 {@code POST /v1/{prefix}/databases/{db}/tables/{table}/commit}：
 * MVC 已经把报文解析成 JSON 树，服务端还要把同一棵树还原成 DTO，因为同一段文本要存两份
 * （字段落库、快照原文写进仓库）。那是一次内部映射，不是 HTTP 绑定——报文的解析仍然是 MVC 做的。
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 序列化为 JSON 文本。 */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * 反序列化为无类型对象树：JSON object 得到 {@code Map}，数组得到 {@code List}，
     * 其余为 {@code String} / {@code Integer} / {@code Long} / {@code Boolean} / {@code null}。
     */
    public static Object read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return MAPPER.readValue(json, Object.class);
    }

    /** 反序列化为指定类型。 */
    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /**
     * 把一份 JSON 文本读成无类型的树，用于「整份文档原样嵌进另一份报文」。
     *
     * <p>目前的唯一使用者是 Iceberg 侧：服务端存的是整份 {@code TableMetadata} 文档
     * （{@code paimon_table.metadata_doc}），返回给客户端时要把它当作**对象**
     * 嵌进 {@code LoadTableResult.metadata}，而不是当字符串。走一次解析—
     * 再序列化看起来绕，但它保证了「引擎读到的字段与服务端存的那一份一致」——
     * 若改成按建模过的字段重新拼一份，Iceberg 那些随版本增删的字段
     * （{@code next-row-id} / {@code encryption-keys} / {@code read-restrictions}）
     * 会在每一次读写之间被悄悄丢掉。
     */
    public static JsonNode readTree(String json) {
        return MAPPER.readTree(json);
    }
}
