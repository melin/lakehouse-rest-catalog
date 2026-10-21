package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.event.CatalogEventCategory;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.PageMeta;
import io.github.melin.lakehouse.rest.support.Paging;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 控制台的事件增量拉取。
 *
 * <p>契约见 {@code docs/event-framework.md} §4：游标是 {@code lakehouse_audit_event.id}，
 * 首次不带 {@code since} 回当前最新一页（内部倒序取再反转成升序，游标落在「现在」），
 * 之后带 {@code since} 只回比它新的事件。不提供往回翻页——事件页要的是
 * 「接下来发生了什么」；要回放历史就重置游标从头来。
 *
 * <p><b>过滤条件在服务端做。</b>catalog 与 category 都是查询参数，两者可以同时给；
 * 谓词动态拼（见 {@link #filter}），因为「目录 × 类别 × 有无游标」的组合写派生方法
 * 要八条。类别取值按枚举校验——写错回 400 而不是静默返回空页：空页会被读成
 * 「这一类事件还没发生过」。
 *
 * <p>两种表格式的事件在同一张表、同一个流里，catalog 过滤只是查询参数，
 * 不是两套 API。
 */
@Service
@RequiredArgsConstructor
public class ConsoleEventService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final AuditEventRepository repository;

    @Transactional(readOnly = true)
    public ConsoleDtos.EventPage events(String catalog, String category, String type, Long since,
                                        Integer limit, Integer page, Integer pageSize) {
        Specification<AuditEventEntity> filter = filter(catalog, category, type);

        // 控制台的事件表是「倒序翻页」：第 1 页就是最新的那批，页码往前走就是往历史走。
        // 与增量流用的是同一份过滤条件，只是排序方向与翻法不同——两者互斥。
        if (page != null || pageSize != null) {
            int size = Paging.pageSize(pageSize, Paging.DEFAULT_PAGE_SIZE, Paging.MAX_PAGE_SIZE);
            int current = Paging.page(page);
            long total = repository.count(filter);
            List<AuditEventEntity> rows = repository.findAll(filter,
                    PageRequest.of(current - 1, size, Sort.by(Sort.Direction.DESC, "id"))).getContent();
            // nextCursor 在页码模式下没有意义（往回翻不靠游标），填本页最新的那条 id，
            // 这样同一个响应在两个模式下都是自洽的：想接着「看更新的事件」就带它。
            long cursor = rows.isEmpty() ? 0L : rows.get(0).getId();
            return new ConsoleDtos.EventPage(toEvents(rows), cursor, (long) current * size < total,
                    PageMeta.of(total, current, size));
        }

        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (since == null) {
            return latestPage(filter, size);
        }
        List<AuditEventEntity> rows = repository.findAll(
                filter.and((root, query, cb) -> cb.greaterThan(root.get("id"), since)),
                PageRequest.of(0, size, Sort.by(Sort.Direction.ASC, "id"))).getContent();
        long cursor = rows.isEmpty() ? since : rows.get(rows.size() - 1).getId();
        return new ConsoleDtos.EventPage(toEvents(rows), cursor, rows.size() >= size, null);
    }

    /**
     * 首页：倒序取最新 {@code size} 条再反转。游标落在最新一条的 {@code id}
     * （结果为空时是 0，客户端下次带 {@code since=0} 拉到的仍是「之后的」事件）。
     */
    private ConsoleDtos.EventPage latestPage(Specification<AuditEventEntity> filter, int size) {
        List<AuditEventEntity> rows = new ArrayList<>(repository.findAll(filter,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "id"))).getContent());
        if (rows.isEmpty()) {
            return new ConsoleDtos.EventPage(List.of(), 0L, false, null);
        }
        long cursor = rows.get(0).getId();
        Collections.reverse(rows);
        return new ConsoleDtos.EventPage(toEvents(rows), cursor, rows.size() >= size, null);
    }

    /** 可选过滤：{@code catalog} 精确匹配、{@code category} 按枚举规范化后匹配。 */
    private static Specification<AuditEventEntity> filter(String catalog, String category, String type) {
        String catalogName = blankToNull(catalog);
        String categoryName = categoryOf(category);
        String typeName = blankToNull(type);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(3);
            if (catalogName != null) {
                predicates.add(cb.equal(root.get("catalog"), catalogName));
            }
            if (categoryName != null) {
                predicates.add(cb.equal(root.get("category"), categoryName));
            }
            // 事件类型（38 种）也在这里筛：界面上的类型下拉是「看某一类事件」，
            // 与类别一样属于筛选条件，不该退化成只筛当前页。
            if (typeName != null) {
                predicates.add(cb.equal(root.get("type"), typeName));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 类别参数 → 枚举名。大小写不敏感（{@code table} 与 {@code TABLE} 都收），
     * 取不到就 400——这类错误静默成空页最难排查。
     */
    private static String categoryOf(String category) {
        String value = blankToNull(category);
        if (value == null) {
            return null;
        }
        try {
            return CatalogEventCategory.valueOf(value.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("Unknown event category: " + category + "; expected one of "
                    + java.util.Arrays.toString(CatalogEventCategory.values()));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<ConsoleDtos.Event> toEvents(List<AuditEventEntity> rows) {
        return rows.stream()
                .map(row -> new ConsoleDtos.Event(row.getId(), row.getEventId(), row.getType(),
                        row.getCategory(), row.getCatalog(), row.getPrincipal(), row.getRequestId(),
                        row.getOccurredAt(), row.getTargetType(), row.getTarget(),
                        row.getAttributes() == null ? java.util.Map.of() : row.getAttributes()))
                .toList();
    }
}
