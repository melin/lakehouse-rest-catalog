package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.lance.support.LanceTagFiles;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表标签的**只读代理**（P2 的 L2-2 里能做的那一半：{@code ListTableTags} 与
 * {@code GetTableTagVersion}）。
 *
 * <p><b>「代理」是字面意思：我们只是把数据集里的标签读出来。</b>不落库、不建索引、
 * 不校验标签指向的版本是否存在、也不替客户端补默认值。这一点是这一面唯一容易
 * 被误解的地方——它看起来像「catalog 在管标签」，实际是「catalog 帮你看一眼
 * {@code _refs/tags/}」。所以：
 *
 * <ul>
 *   <li>标签由客户端（Lance SDK / Spark connector）创建与删除，我们不提供写端点，
 *       五个写 operation（{@code tags/create|delete|update} 与索引那一组）仍回
 *       {@code 406 + code 0}；</li>
 *   <li>标签指向一个已被清理的旧版本时，我们照原样报它——目录里就是这么写的，
 *       「这个标签坏了」该由读者判断，而不是由目录替它隐藏。</li>
 * </ul>
 *
 * <p><b>为什么不把索引一起做。</b>{@code ListTableIndices} 要 {@code index_name} /
 * {@code index_uuid} / {@code columns} / {@code index_type} / {@code status}，
 * 而规范对 {@code _indices/{UUID}/} 的内容只写了一句话：different for each index type。
 * 目录名只给得出 uuid，其余字段只能猜——猜出来的响应会让调用方以为索引是健康的。
 * 标签不一样：它的文件名、位置、字段都在规范里写死了（见 {@link LanceTagFiles}）。
 *
 * <p><b>两种失败要分开。</b>{@code tags/list} 里某个文件坏了就跳过（一个坏标签
 * 不该把其余标签一起藏起来）；{@code tags/version} 指名要某一个标签时不能跳过，
 * 因此抛 {@code InvalidTableState}（19 / 409）而不是 {@code TableTagNotFound}（8 / 404）
 * ——「文件坏了」与「标签不存在」对调用方是两个完全不同的处置。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LanceTagService {

    private final LanceCatalogSupport support;

    /**
     * {@code ListTableTags}：{@code tags} 是**名字 → 内容**的 map，不是数组。
     *
     * <p>排序按标签名（升序）后切页。规范只说 {@code page_token} / {@code limit}
     * 生效，没说顺序，因此顺序由我们定——不定的话同一个 {@code page_token}
     * 在两次请求里可能落在不同位置，翻页会重复或漏。
     */
    @Transactional(readOnly = true)
    public LanceDtos.ListTableTagsResponse list(String id, String delimiter, Integer limit,
                                                String pageToken) {
        Resolved resolved = resolve(id, delimiter);
        // 复制一份再排序：support 在「没有标签」时返回的是 List.of()（不可变），
        // 直接 sort 会抛 UnsupportedOperationException——而那只在空表上出现
        List<LanceTagFiles.Tag> tags =
                new ArrayList<>(support.tags(resolved.catalog(), resolved.table().getPath()));
        tags.sort(Comparator.comparing(LanceTagFiles.Tag::name));
        int size = Paging.pageSize(limit, 100, 1000);
        Paging.Slice<LanceTagFiles.Tag> slice = Paging.slice(tags, Paging.offset(pageToken), size);
        Map<String, LanceDtos.TagContents> contents = new LinkedHashMap<>();
        for (LanceTagFiles.Tag tag : slice.items()) {
            contents.put(tag.name(),
                    new LanceDtos.TagContents(tag.branch(), tag.version(), tag.manifestSize()));
        }
        return new LanceDtos.ListTableTagsResponse(contents, slice.nextPageToken());
    }

    /** {@code GetTableTagVersion}：只回版本号与分支，不回 manifest 路径（规范没给这个字段）。 */
    @Transactional(readOnly = true)
    public LanceDtos.GetTableTagVersionResponse version(String id, String delimiter,
                                                        LanceDtos.GetTableTagVersionRequest request) {
        Resolved resolved = resolve(id, delimiter);
        if (request == null || request.tag() == null || request.tag().isBlank()) {
            throw LanceErrors.invalidInput("tag is required");
        }
        String tagName = request.tag().trim();
        // 标签名直接拼成文件名：拒绝而不是清洗（`..`/`/` 会把「读标签」变成「读任意对象」）
        if (!LanceTagFiles.isSafeName(tagName)) {
            throw LanceErrors.invalidInput("invalid tag name: \"" + tagName + "\"");
        }
        LanceTagFiles.Tag tag;
        try {
            tag = support.tag(resolved.catalog(), resolved.table().getPath(), tagName);
        } catch (LanceTagFiles.Unreadable e) {
            throw LanceErrors.invalidTableState("table \"" + resolved.id().join(resolved.sep())
                    + "\" has an unreadable tag file for \"" + tagName + "\": " + e.getMessage());
        }
        if (tag == null) {
            throw LanceErrors.tableTagNotFound(resolved.id().join(resolved.sep()), tagName);
        }
        return new LanceDtos.GetTableTagVersionResponse(tag.version(), tag.branch());
    }

    // ------------------------------------------------------------------ 内部

    /** 与 {@code LanceVersionService} 同一套定位：catalog → schema → table。 */
    private record Resolved(LanceIds.Id id, String sep, CatalogEntity catalog,
                            DatabaseEntity database, TableEntity table) {
    }

    private Resolved resolve(String id, String delimiter) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        String separator = LanceCatalogSupport.sep(delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = support.requireDatabase(catalog, parsee, delimiter);
        TableEntity table = support.requireTable(catalog, database, parsee, delimiter);
        return new Resolved(parsee, separator, catalog, database, table);
    }
}
