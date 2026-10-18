package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 控制台的事件增量拉取。
 *
 * <p>契约见 {@code docs/event-framework.md} §4：游标是 {@code audit_event.id}，
 * 首次不带 {@code since} 回当前最新一页（内部倒序取再反转成升序，游标落在「现在」），
 * 之后带 {@code since} 只回比它新的事件。不提供往回翻页——事件页要的是
 * 「接下来发生了什么」；要回放历史就重置游标从头来。
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
    public ConsoleDtos.EventPage events(String catalog, Long since, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String name = catalog == null || catalog.isBlank() ? null : catalog.trim();
        Pageable page = PageRequest.of(0, size);

        if (since == null) {
            return latestPage(name, size);
        }
        List<AuditEventEntity> rows = name == null
                ? repository.findByIdGreaterThanOrderByIdAsc(since, page)
                : repository.findByCatalogAndIdGreaterThanOrderByIdAsc(name, since, page);
        long cursor = rows.isEmpty() ? since : rows.get(rows.size() - 1).getId();
        return new ConsoleDtos.EventPage(toEvents(rows), cursor, rows.size() >= size);
    }

    /**
     * 首页：倒序取最新 {@code size} 条再反转。游标落在最新一条的 {@code id}
     * （表为空时是 0，客户端下次带 {@code since=0} 拉到的仍是「之后的」事件）。
     */
    private ConsoleDtos.EventPage latestPage(String catalog, int size) {
        Optional<AuditEventEntity> top = catalog == null
                ? repository.findTopByOrderByIdDesc()
                : topOfCatalog(catalog);
        if (top.isEmpty()) {
            return new ConsoleDtos.EventPage(List.of(), 0L, false);
        }
        List<AuditEventEntity> rows = new ArrayList<>(catalog == null
                ? repository.findAllByOrderByIdDesc(PageRequest.of(0, size))
                : repository.findByCatalogOrderByIdDesc(catalog, PageRequest.of(0, size)));
        Collections.reverse(rows);
        return new ConsoleDtos.EventPage(toEvents(rows), top.get().getId(), rows.size() >= size);
    }

    private Optional<AuditEventEntity> topOfCatalog(String catalog) {
        List<AuditEventEntity> rows = repository.findByCatalogOrderByIdDesc(catalog, PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static List<ConsoleDtos.Event> toEvents(List<AuditEventEntity> rows) {
        return rows.stream()
                .map(row -> new ConsoleDtos.Event(row.getId(), row.getEventId(), row.getType(),
                        row.getCatalog(), row.getPrincipal(), row.getRequestId(), row.getOccurredAt(),
                        row.getTargetType(), row.getTarget(),
                        row.getAttributes() == null ? java.util.Map.of() : row.getAttributes()))
                .toList();
    }
}
