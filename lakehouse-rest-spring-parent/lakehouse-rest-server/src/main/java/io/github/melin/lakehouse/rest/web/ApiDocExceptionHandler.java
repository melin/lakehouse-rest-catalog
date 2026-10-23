package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.CommonDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.api.OpenApiResourceNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 接口文档端点的「没有这一组」按 404 出去。
 *
 * <p>{@code /v3/api-docs/{group}} 的组名来自 {@code springdoc.group-configs}，写错一个字母
 * springdoc 就抛 {@link OpenApiResourceNotFoundException}。它既不实现 Spring MVC 的
 * {@code ErrorResponse}，也不是 {@code ApiException}，于是会落到
 * {@link GlobalExceptionHandler#handleUnexpected} 的兜底分支，变成
 * {@code 500 Internal Server Error} 外加一条 ERROR 级异常栈。
 *
 * <p>实际语义是「你要的这份文档不存在」，属于调用方错误，应当是 404；日志也只该留一条 debug。
 * 这个处理器<b>必须</b>放在 server 模块：springdoc 只在这里的类路径上，放进 core 会编译不过，
 * 而为此给 core 加 springdoc 依赖更不划算。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 是必需的——Spring 按 advice 的顺序取第一个能处理的
 * 方法，而 {@link GlobalExceptionHandler} 里有一个 {@code Exception} 兜底，排在它后面就永远轮不到。
 *
 * <p>错误体沿用 {@link GlobalExceptionHandler} 的扁平形状：文档路径不在 {@code /v1/} 下，
 * 永远不是 Iceberg 面，不需要那条嵌套形状的分支。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiDocExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiDocExceptionHandler.class);

    @ExceptionHandler(OpenApiResourceNotFoundException.class)
    public ResponseEntity<?> handleUnknownGroup(OpenApiResourceNotFoundException exception) {
        log.debug("api doc requested for an unknown group: {}", exception.getMessage());
        return ResponseEntity.status(404)
                .body(new CommonDtos.ErrorResponse(exception.getMessage(), null, null, 404));
    }
}
