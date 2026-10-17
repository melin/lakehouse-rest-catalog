package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.service.CatalogFormatResolver;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.IcebergErrors;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应。
 *
 * <p><b>两种形状，按请求所属协议面二选一。</b>失败都以规格定义的 {@code ErrorResponse}
 * 形态出去，但「规格」有两份：
 *
 * <ul>
 *   <li>Paimon：{@code {message, resourceType, resourceName, code}}——扁平，
 *       HTTP 状态码与 {@code code} 保持一致；</li>
 *   <li>Iceberg：{@code {error: {message, type, code}}}——<b>嵌套一层</b>。
 *       Spark 的 Iceberg 客户端把响应体反序列化成 {@code ErrorResponse}，
 *       拿不到 {@code error} 字段就只报一句「Unable to process: …」，
 *       看不出真正的原因。</li>
 * </ul>
 *
 * <p>判据见 {@link CatalogFormatResolver}：路径形状优先、catalog 格式兜底。
 * 管理 API 与控制台扩展端点不在 {@code /v1/} 下，因此恒为 Paimon 形状——
 * 与它们此前的行为完全一致。
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final CatalogFormatResolver formatResolver;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApiException(ApiException exception, HttpServletRequest request) {
        String message = exception.getMessage();
        return respond(request, exception.getStatus(),
                new Failure(message,
                        exception.getResourceType() == null ? null : exception.getResourceType().name(),
                        exception.getResourceName(),
                        IcebergErrors.typeOf(exception)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException exception,
                                              HttpServletRequest request) {
        return respond(request, 400, failure("Malformed request", 400));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException exception,
                                                   HttpServletRequest request) {
        String message = exception.getMessage() == null ? "Malformed request" : exception.getMessage();
        return respond(request, 400, failure(message, 400));
    }

    /**
     * 兜底处理。
     *
     * <p>Spring MVC 自身的错误（找不到路由、方法不支持等）已实现 {@link ErrorResponse} 并携带状态码，
     * 这里沿用其状态码而不是一律返回 500——例如访问不存在的路径应当是 404。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            int status = statusCode.value();
            var problem = errorResponse.getBody();
            String message = problem != null && problem.getDetail() != null
                    ? problem.getDetail()
                    : exception.getMessage();
            if (status >= 500) {
                log.error("Catalog request failed with status {}", status, exception);
            } else {
                log.debug("Catalog request rejected with status {}: {}", status, message);
            }
            return respond(request, status, failure(message, status));
        }
        log.error("Unhandled error while serving catalog request", exception);
        return respond(request, 500, failure("Internal Server Error", 500));
    }

    /**
     * 按协议面写出响应体。
     *
     * <p>两个形状都从同一个 {@link Failure} 转出来，是为了让「哪条异常路径忘了转换」
     * 这件事不可能发生——分支只有这里一处。
     */
    private ResponseEntity<?> respond(HttpServletRequest request, int status, Failure failure) {
        if (formatResolver.of(request) == CatalogFormat.ICEBERG) {
            return ResponseEntity.status(status).body(new CommonDtos.IcebergErrorResponse(
                    new CommonDtos.IcebergErrorResponse.IcebergError(
                            failure.message(), failure.icebergType(), status)));
        }
        return ResponseEntity.status(status).body(new CommonDtos.ErrorResponse(
                failure.message(), failure.resourceType(), failure.resourceName(), status));
    }

    /** 一次失败在两套形状里各自需要的那些值，先算齐再分派。 */
    private record Failure(String message, String resourceType, String resourceName, String icebergType) {
    }

    private static Failure failure(String message, int status) {
        return new Failure(message, null, null, IcebergErrors.typeOf(status, null));
    }
}
