package io.github.melin.lakehouse.rest.lance.web;

import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.service.LanceTableService;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lance 的表端点（8 个 operation）。
 *
 * <p><b>query 参数逐条列出而不是收一个 map。</b>它们各自有默认值语义
 * （{@code load_detailed_metadata=false}、{@code check_declared=false}、
 * {@code vend_credentials=false}），收成 map 之后「没传」与「传了 false」就分不开了，
 * 而这三处恰恰是默认值与显式 {@code false} 必须等价、显式 {@code true} 才改变行为的地方。
 *
 * <p><b>{@code GET /lance/v1/table}（不带 id）单独占一条映射并回 400</b>，
 * 而不是让它落进 {@code /lance/v1/table/{id}} 的通配里被当成「id 为空」：
 * 两者的错误文案不同——前者是「必须带 catalog 前缀」，后者是「id 不能为空」。
 */
@RestController
@RequestMapping("/lance/v1/table")
@RequiredArgsConstructor
public class LanceTableController {

    private final LanceTableService tableService;

    /** 跨命名空间列举。 */
    @GetMapping("/{id}")
    public LanceDtos.ListTablesResponse search(@PathVariable String id,
                                                @RequestParam(required = false) String delimiter,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) String page_token) {
        return tableService.search(id, delimiter, limit, page_token);
    }

    /** 不带 catalog 前缀的全库列举：拒绝，不静默扫描。 */
    @GetMapping("")
    public LanceDtos.ListTablesResponse searchWithoutPrefix() {
        throw LanceErrors.invalidInput("listing all tables requires a catalog prefix in the id");
    }

    @PostMapping("/{id}/describe")
    public LanceDtos.DescribeTableResponse describe(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) Boolean load_detailed_metadata,
            @RequestParam(required = false) Boolean check_declared,
            @RequestParam(required = false) Boolean with_table_uri,
            @RequestParam(required = false) Boolean vend_credentials) {
        return tableService.describe(id, delimiter,
                Boolean.TRUE.equals(load_detailed_metadata),
                Boolean.TRUE.equals(check_declared),
                Boolean.TRUE.equals(with_table_uri),
                Boolean.TRUE.equals(vend_credentials));
    }

    @PostMapping("/{id}/exists")
    public ResponseEntity<Void> exists(@PathVariable String id,
                                       @RequestParam(required = false) String delimiter) {
        tableService.requireExists(id, delimiter);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/declare")
    public LanceDtos.TableRef declare(@PathVariable String id,
                                       @RequestParam(required = false) String delimiter,
                                       @RequestParam(required = false) String mode,
                                       @RequestParam(required = false) Boolean vend_credentials,
                                       @RequestBody(required = false) LanceDtos.DeclareTableRequest request) {
        return tableService.declare(id, delimiter, request, mode, Boolean.TRUE.equals(vend_credentials));
    }

    @PostMapping("/{id}/register")
    public LanceDtos.TableRef register(@PathVariable String id,
                                        @RequestParam(required = false) String delimiter,
                                        @RequestParam(required = false) String mode,
                                        @RequestParam(required = false) Boolean vend_credentials,
                                        @RequestBody(required = false) LanceDtos.RegisterTableRequest request) {
        return tableService.register(id, delimiter, request, mode, Boolean.TRUE.equals(vend_credentials));
    }

    @PostMapping("/{id}/deregister")
    public LanceDtos.DeregisterTableResponse deregister(@PathVariable String id,
                                                        @RequestParam(required = false) String delimiter) {
        return tableService.deregister(id, delimiter);
    }

    @PostMapping("/{id}/rename")
    public LanceDtos.TableRef rename(@PathVariable String id,
                                     @RequestParam(required = false) String delimiter,
                                     @RequestParam(required = false) Boolean vend_credentials,
                                     @RequestBody(required = false) LanceDtos.RenameTableRequest request) {
        return tableService.rename(id, delimiter, request, Boolean.TRUE.equals(vend_credentials));
    }
}
