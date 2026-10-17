package io.github.melin.lakehouse.rest.support;

/**
 * 把内部的失败信息翻译成 Iceberg {@code ErrorModel.type}。
 *
 * <p>Iceberg 的错误响应是**嵌套一层**的
 * （{@code {"error": {"message", "type", "code"}}}），与 Paimon 侧扁平的
 * {@code {message, resourceType, resourceName, code}} 不同。Spark 的 Iceberg 客户端会把
 * 响应体反序列化成 {@code ErrorResponse}，拿不到 {@code error} 字段就只报一句
 * 「Unable to process: …」，看不出真正的原因。因此错误响应的形状必须按请求所属的
 * catalog 格式选，见 {@code GlobalExceptionHandler}。
 *
 * <p>{@code type} 是一个**类名字符串**（如 {@code NoSuchNamespaceException}）。规格写明
 * 它是「a string that represents the type of the error」，客户端不按它做分支，
 * 只是原样展示给使用者。因此这里的映射只需要「说清是哪一类错」，不需要与
 * Iceberg 的 Java 异常类名逐字相同——但尽量对齐，因为它会出现在人看的错误信息里。
 *
 * <p>{@code code} 用 HTTP 状态码本身（规格的 {@code ErrorModel.code} 就是可选的 HTTP 状态）。
 */
public final class IcebergErrors {

    private IcebergErrors() {
    }

    /**
     * 由状态码与资源类型推出 {@code ErrorModel.type}。
     *
     * <p>优先用 {@link ApiException#getErrorCode()}：它承载的是「该请求所属协议的
     * 协议级错误码」，OAuth 端点往里放 RFC 6749 的 {@code error} 取值，Iceberg 端点
     * 往里放更精确的 {@code ErrorModel.type}（例如 {@code CommitFailedException}
     * 与 {@code AlreadyExistsException} 的 HTTP 状态都是 409，只有抛异常的那一处知道
     * 该报哪一个）。没给就按状态码与资源类型推断。
     */
    public static String typeOf(ApiException exception) {
        if (exception.getErrorCode() != null && !exception.getErrorCode().isBlank()) {
            return exception.getErrorCode();
        }
        return typeOf(exception.getStatus(), exception.getResourceType());
    }

    /** 纯按状态码与资源类型推断。 */
    public static String typeOf(int status, ResourceType resourceType) {
        return switch (status) {
            case 400 -> "BadRequestException";
            case 401 -> "NotAuthorizedException";
            case 403 -> "ForbiddenException";
            case 404 -> notFound(resourceType);
            case 405, 406, 501 -> "UnsupportedOperationException";
            case 409 -> "AlreadyExistsException";
            case 412 -> "CommitFailedException";
            case 413 -> "UnprocessableEntityException";
            case 422 -> "UnprocessableEntityException";
            default -> status >= 500 ? "InternalServerError" : "RESTException";
        };
    }

    /**
     * 404 的细分。
     *
     * <p>Iceberg 的客户端按这个字符串给使用者看，而「namespace 不存在」与「表不存在」
     * 的处理方式完全不同（前者要建的可能是 namespace），因此值得细分。
     *
     * <p>没有对应细分时回落到 {@code NotFoundException}——规格的示例里出现过它。
     * 不去虚构 {@code NoSuchSnapshotException} 这类名字：Iceberg 没有这些异常类，
     * 编一个只会让人去上游找一个不存在的东西。
     */
    private static String notFound(ResourceType resourceType) {
        if (resourceType == null) {
            return "NotFoundException";
        }
        return switch (resourceType) {
            case DATABASE -> "NoSuchNamespaceException";
            case TABLE -> "NoSuchTableException";
            case VIEW -> "NoSuchViewException";
            case CATALOG -> "NoSuchWarehouseException";
            default -> "NotFoundException";
        };
    }
}
