package io.github.melin.lakehouse.rest.support;

import java.util.Locale;

/**
 * Lance REST Catalog 的错误模型：{@code {error, code, detail, instance}} 四字段，
 * HTTP 状态码由 {@code code} **算出来**，而不是各处自己决定。
 *
 * <p>与另两个面的差别值得写清，因为它是本面唯一一处「连 404 都不长一样」的地方：
 *
 * <ul>
 *   <li>Paimon：{@code {message, resourceType, resourceName, code}}，扁平；</li>
 *   <li>Iceberg：{@code {error: {message, type, code}}}，嵌套一层；</li>
 *   <li>Lance：{@code {error, code, detail, instance}}，RFC 7807 风格，且 {@code code}
 *       是一个**独立枚举**（与 HTTP 状态不是同一个数），因此必须有一张表。</li>
 * </ul>
 *
 * <p><b>{@code code 0 → 406} 是这一面最有用的一个位置</b>：它让「本实现不支持这个
 * operation」有一个规范内的表达，而不必编一个假成功、也不必退回 404（那会被客户端
 * 理解成「地址错了」）或 500（那看起来像服务坏了）。本服务端不做的 30 个 operation
 * 全部用它。
 *
 * <p><b>为什么放在 core 而不是 lance 模块。</b>{@code GlobalExceptionHandler} 是全局的、
 * 位于 core，它必须能识别本异常并写出 Lance 形状；把异常放协议面会让 core 反向依赖
 * 协议面。{@code IcebergErrors} 的落点是同一个理由（它只有字符串映射，本类因为
 * 有 {@code code} 枚举而多带一个类型，但仍然只是「翻译表 + 异常」，不含任何协议逻辑）。
 *
 * <p>code 到 HTTP 的映射抄自 {@code lance.org/format/namespace/operations/errors/}。
 * 一处漂移要记着：旧文档里 {@code 12} 是 {@code TableVersionAlreadyExists}，
 * 现行规范里是 {@code TableColumnNotFound}——按现行规范实现。
 */
public final class LanceErrors {

    private LanceErrors() {
    }

    /**
     * Lance 的错误码。
     *
     * @param value      规范里的整数
     * @param status     HTTP 状态码
     * @param wireName   规范里的名字，只用于日志与人读的错误文案
     */
    public enum Code {
        UNSUPPORTED(0, 406),
        NAMESPACE_NOT_FOUND(1, 404),
        NAMESPACE_ALREADY_EXISTS(2, 409),
        NAMESPACE_NOT_EMPTY(3, 409),
        TABLE_NOT_FOUND(4, 404),
        TABLE_ALREADY_EXISTS(5, 409),
        TABLE_INDEX_NOT_FOUND(6, 404),
        TABLE_INDEX_ALREADY_EXISTS(7, 409),
        TABLE_TAG_NOT_FOUND(8, 404),
        TABLE_TAG_ALREADY_EXISTS(9, 409),
        TRANSACTION_NOT_FOUND(10, 404),
        TABLE_VERSION_NOT_FOUND(11, 404),
        TABLE_COLUMN_NOT_FOUND(12, 404),
        INVALID_INPUT(13, 400),
        CONCURRENT_MODIFICATION(14, 409),
        PERMISSION_DENIED(15, 403),
        UNAUTHENTICATED(16, 401),
        SERVICE_UNAVAILABLE(17, 503),
        INTERNAL(18, 500),
        INVALID_TABLE_STATE(19, 409),
        TABLE_SCHEMA_VALIDATION_ERROR(20, 400),
        THROTTLING(21, 429);

        private final int value;
        private final int status;

        Code(int value, int status) {
            this.value = value;
            this.status = status;
        }

        public int value() {
            return value;
        }

        public int status() {
            return status;
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /**
     * Lance 面抛出的失败。
     *
     * <p>它**不**继承 {@link ApiException}：那边的形状是「状态码 + 资源类型 + 消息」，
     * 而这里必须先决定 {@code code}，状态码是它的函数。让两者互相继承只会得到
     * 「两个字段都说自己是真相」的对象。{@code GlobalExceptionHandler} 对它有单独的
     * {@code @ExceptionHandler}，因此不需要继承关系也能被正确处理。
     */
    public static class LanceException extends RuntimeException {

        private final Code code;

        /** 可选的技术细节，进响应体的 {@code detail}。 */
        private final String detail;

        public LanceException(Code code, String message) {
            this(code, message, null);
        }

        public LanceException(Code code, String message, String detail) {
            super(message);
            this.code = code;
            this.detail = detail;
        }

        public Code code() {
            return code;
        }

        public String detail() {
            return detail;
        }

        public int status() {
            return code.status();
        }
    }

    // ------------------------------------------------------------------ 抛出

    /** operation 不在本服务端的实现范围内（30 个不做 + P2 的 3 个都走这里）。 */
    public static LanceException unsupported(String operation) {
        return new LanceException(Code.UNSUPPORTED,
                "operation \"" + operation + "\" is not implemented by this Lance REST catalog",
                "docs/lance-rest-catalog-design.md §3.1 lists which of the 48 operations this server implements");
    }

    public static LanceException namespaceNotFound(String id) {
        return new LanceException(Code.NAMESPACE_NOT_FOUND, "Namespace \"" + id + "\" not found");
    }

    public static LanceException namespaceAlreadyExists(String id) {
        return new LanceException(Code.NAMESPACE_ALREADY_EXISTS, "Namespace \"" + id + "\" already exists");
    }

    public static LanceException namespaceNotEmpty(String id) {
        return new LanceException(Code.NAMESPACE_NOT_EMPTY,
                "Namespace \"" + id + "\" is not empty; drop its tables first",
                "this server only implements the Restrict behaviour; Cascade is not supported");
    }

    public static LanceException tableNotFound(String id) {
        return new LanceException(Code.TABLE_NOT_FOUND, "Table \"" + id + "\" not found");
    }

    public static LanceException tableAlreadyExists(String id) {
        return new LanceException(Code.TABLE_ALREADY_EXISTS, "Table \"" + id + "\" already exists");
    }

    public static LanceException invalidInput(String message) {
        return new LanceException(Code.INVALID_INPUT, message);
    }

    public static LanceException permissionDenied(String message) {
        return new LanceException(Code.PERMISSION_DENIED, message);
    }

    // ------------------------------------------------------------------ 兜底

    /**
     * 由 HTTP 状态码反推一个 {@code code}，用于**不是**由本面抛出的异常
     * （参数绑定失败、405、未捕获异常……）。
     *
     * <p>这一层必须存在：Lance 客户端按四字段反序列化响应体，{@code GlobalExceptionHandler}
     * 若对 {@code /lance/**} 下的请求仍写出 Paimon 形状，客户端只会看到「解析失败」，
     * 而真正的错误（比如 404）被埋在构造异常里。
     *
     * <p>404 的细分按路径判而不是一律 {@code NAMESPACE_NOT_FOUND}：表级路径上的 404
     * 回 {@code TABLE_NOT_FOUND} 才是对的，客户端据此决定「去建表」还是「去建 schema」。
     */
    public static Code fromStatus(int status, boolean tableLevel) {
        return switch (status) {
            case 400 -> Code.INVALID_INPUT;
            case 401 -> Code.UNAUTHENTICATED;
            case 403 -> Code.PERMISSION_DENIED;
            case 404 -> tableLevel ? Code.TABLE_NOT_FOUND : Code.NAMESPACE_NOT_FOUND;
            case 406 -> Code.UNSUPPORTED;
            case 409 -> Code.CONCURRENT_MODIFICATION;
            case 429 -> Code.THROTTLING;
            case 503 -> Code.SERVICE_UNAVAILABLE;
            default -> status >= 500 ? Code.INTERNAL : Code.UNSUPPORTED;
        };
    }
}
