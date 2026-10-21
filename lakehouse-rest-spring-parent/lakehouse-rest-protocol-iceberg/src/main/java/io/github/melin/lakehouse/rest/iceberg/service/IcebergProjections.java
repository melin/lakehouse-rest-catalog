package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.BranchEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableSchemaVersionEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableSnapshotEntity;
import io.github.melin.lakehouse.rest.domain.entity.TagEntity;
import io.github.melin.lakehouse.rest.domain.repo.BranchRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableSchemaVersionRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableSnapshotRepository;
import io.github.melin.lakehouse.rest.domain.repo.TagRepository;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 Iceberg 的整份 {@code TableMetadata} 派生成本工程既有的**投影行**。
 *
 * <p><b>为什么要有投影。</b>Iceberg 的元数据是一份整体快照（每次 commit 一份新 JSON），
 * 而本工程的控制台（表列表、快照页签、标签/分支页签、统计卡片）是围绕「逐资源建行」
 * 写的。让控制台为了 Iceberg 表去解析几十 MB 的 JSON 是不可行的——那意味着每次翻页
 * 都要把文档搬进内存。因此照抄本工程已有的那一套
 * （{@code lakehouse_catalog.storage_config_json} 是真相源、{@code storage_type} 与
 * {@code allowed_locations_json} 是它的投影，读取时一律以真相源为准）：
 *
 * <blockquote>
 * <b>{@code lakehouse_table.metadata_doc} 是唯一真相源；快照 / refs / schema 三类行是它的派生投影。</b>
 * </blockquote>
 *
 * <p><b>派生是「对账」而不是「追加」。</b>每次提交都按文档的当前内容与库里已有的行做一次
 * diff：文档里没有的行删掉，文档里有而没有的行插入或更新。不做增量追加的理由很直接：
 * Iceberg 的 {@code remove-snapshots} / {@code remove-schemas} / {@code remove-snapshot-ref}
 * 是常规操作，只追加会让库里留下一批文档里已经没有的快照——控制台上看得见、点进去
 * 却发现元数据里不存在，这种「看得见但打不开」的状态比少一行更难排障。
 *
 * <p><b>只改自己该管的列。</b>更新已有行时不动
 * {@code fileCount} / {@code fileSizeInBytes} / {@code lastFileCreationTime} 三列：
 * 它们的来源是「上一次派生时算出来的」或「metrics 上报过的」，而本次派生的输入
 * （文档里的 snapshot summary）不一定覆盖它们。宁可留旧值也不要写 null——
 * 写 null 会让控制台的统计卡片在两次提交之间忽然清零。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IcebergProjections {

    /**
     * {@code SnapshotRef.MAIN_BRANCH} 的同义常量。
     *
     * <p>不引用上游那个常量：它的值是 {@code "main"}，而本工程的「分支表」语义里
     * **没有 main 这一行**（Paimon 的分支表同样只有显式创建的分支）。
     * 写成本地常量可以让这条业务规则与上游的字符串常量解耦——上游哪天改了默认分支名，
     * 受影响的也只是「哪个 ref 不进分支表」这一处显式判断，而不是静默地多出一行。
     */
    private static final String MAIN_BRANCH = "main";

    // ---------------------------------------------------------------- summary 的键
    //
    // 这几个键由 Iceberg 的 SnapshotSummary 定义，v1 与 v2 的键集合不同
    // （v1 没有 total-data-files）。取不到就是 null，不编默认值：
    // 控制台把 null 显示成 "-"，而 0 会被读成「这张表是空的」。

    private static final String KEY_TOTAL_RECORDS = "total-records";
    private static final String KEY_TOTAL_DATA_FILES = "total-data-files";
    private static final String KEY_TOTAL_FILES_SIZE = "total-files-size";
    private static final String KEY_TOTAL_DELETE_FILES = "total-delete-files";
    private static final String KEY_OPERATION = "operation";

    private final TableSnapshotRepository snapshotRepository;
    private final TableSchemaVersionRepository schemaVersionRepository;
    private final BranchRepository branchRepository;
    private final TagRepository tagRepository;

    /**
     * 按文档重建一张表的全部投影行。
     *
     * <p>调用点在**每一次写路径**上（建表 / 提交 / 注册），因此它必须是幂等的：
     * 内容没变时 diff 的结果是空的，不会产生任何写语句。
     */
    @Transactional
    public void rebuild(TableEntity table, TableMetadata metadata) {
        syncSchemas(table, metadata);
        syncSnapshots(table, metadata);
        syncRefs(table, metadata);
    }

    /** 删表时清掉三类投影行。表的实体由调用方负责删。 */
    @Transactional
    public void deleteAll(String tableId) {
        snapshotRepository.deleteByTableId(tableId);
        schemaVersionRepository.deleteByTableId(tableId);
        branchRepository.deleteByTableId(tableId);
        tagRepository.deleteByTableId(tableId);
    }

    // ------------------------------------------------------------------ schema

    /**
     * {@code lakehouse_table_schema} ← {@code metadata.schemas[]}，逐个 schema-id 一行。
     *
     * <p>schema 的数量是个位数量级（一次 {@code add-schema} 加一个），
     * 因此这里用「全量对账」而不是增量：读全部、比对、只写差异。代价可忽略，
     * 换来的是「{@code remove-schemas} 之后库里也真的少了」这件不需要额外证明的事。
     */
    private void syncSchemas(TableEntity table, TableMetadata metadata) {
        List<TableSchemaVersionEntity> existing = schemaVersionRepository
                .findAllByTableIdOrderBySchemaIdAsc(table.getId());
        Map<Long, TableSchemaVersionEntity> byId = new LinkedHashMap<>();
        for (TableSchemaVersionEntity version : existing) {
            byId.put(version.getSchemaId(), version);
        }
        Set<Long> live = new LinkedHashSet<>();
        List<TableSchemaVersionEntity> changed = new ArrayList<>();
        for (Schema schema : metadata.schemas()) {
            long schemaId = schema.schemaId();
            live.add(schemaId);
            String document = SchemaParser.toJson(schema);
            TableSchemaVersionEntity version = byId.remove(schemaId);
            if (version == null) {
                version = new TableSchemaVersionEntity();
                version.setId(Paging.newId());
                version.setTableId(table.getId());
                version.setSchemaId(schemaId);
                version.setCreatedAt(metadata.lastUpdatedMillis());
            }
            if (!document.equals(version.getSchemaDoc())) {
                version.setSchemaDoc(document);
            }
            changed.add(version);
        }
        // byId 里剩下的都是文档已经没有的 schema（remove-schemas 干的事）
        if (!byId.isEmpty()) {
            schemaVersionRepository.deleteAll(byId.values());
        }
        if (!changed.isEmpty()) {
            schemaVersionRepository.saveAll(changed);
        }
    }

    // ------------------------------------------------------------------ 快照

    /**
     * {@code lakehouse_snapshot} ← {@code metadata.snapshots[]}。
     *
     * <p>列的映射关系（设计文档 §4.2 那张表）：
     *
     * <table border="1">
     *   <caption>既有列与 Iceberg 字段的对应</caption>
     *   <tr><th>既有列</th><th>Iceberg 来源</th></tr>
     *   <tr><td>{@code snapshot_id}</td><td>{@code snapshot-id}</td></tr>
     *   <tr><td>{@code parent_snapshot_id}</td><td>{@code parent-snapshot-id}</td></tr>
     *   <tr><td>{@code sequence_number}</td><td>{@code sequence-number}</td></tr>
     *   <tr><td>{@code schema_id}</td><td>{@code schema-id}</td></tr>
     *   <tr><td>{@code time_millis}</td><td>{@code timestamp-ms}</td></tr>
     *   <tr><td>{@code base_manifest_list}</td><td>{@code manifest-list}（Iceberg 每个快照只有
     *       一个清单，复用 Paimon 的 base 列，其余三个清单列留空）</td></tr>
     *   <tr><td>{@code summary_json}</td><td>{@code summary} 原样</td></tr>
     *   <tr><td>{@code total_record_count}</td><td>{@code summary["total-records"]}</td></tr>
     *   <tr><td>{@code file_count}</td><td>{@code summary["total-data-files"]}（v1 无 → null）</td></tr>
     *   <tr><td>{@code file_size_in_bytes}</td><td>{@code summary["total-files-size"]}</td></tr>
     *   <tr><td>{@code commit_kind}</td><td>{@code summary["operation"]}</td></tr>
     *   <tr><td>{@code commit_user}</td><td><b>认证主体</b>——服务端自己知道是谁发的提交，
     *       比从 summary 里猜可靠</td></tr>
     * </table>
     *
     * <p>这一列映射让控制台「详细信息」的四张统计卡片对 Iceberg 表**零改动就能出数**。
     * 唯一出不了数的是「最近文件写入」：Iceberg 的 summary 里没有文件 mtime
     * （快照时间戳是提交时刻，不是文件写入时刻），因此该列留 {@code null} 而不是拿
     * 提交时间冒充——一个看起来有值、实际是另一个量的数字，比空着更难发现。
     */
    private void syncSnapshots(TableEntity table, TableMetadata metadata) {
        List<TableSnapshotEntity> existing = snapshotRepository
                .findAllByTableIdOrderBySnapshotIdDesc(table.getId());
        Map<Long, TableSnapshotEntity> byId = new LinkedHashMap<>();
        for (TableSnapshotEntity snapshot : existing) {
            byId.put(snapshot.getSnapshotId(), snapshot);
        }
        List<TableSnapshotEntity> changed = new ArrayList<>();
        for (Snapshot snapshot : metadata.snapshots()) {
            long snapshotId = snapshot.snapshotId();
            TableSnapshotEntity entity = byId.remove(snapshotId);
            boolean created = entity == null;
            if (created) {
                entity = new TableSnapshotEntity();
                entity.setId(Paging.newId());
                entity.setTableId(table.getId());
                entity.setSnapshotId(snapshotId);
            }
            Map<String, String> summary = snapshot.summary() == null
                    ? Map.of() : snapshot.summary();
            boolean touched = created;
            touched |= assign(entity::getParentSnapshotId, snapshot.parentId(), entity::setParentSnapshotId);
            touched |= assign(entity::getSequenceNumber, snapshot.sequenceNumber(), entity::setSequenceNumber);
            touched |= assign(entity::getSchemaId,
                    snapshot.schemaId() == null ? (long) metadata.currentSchemaId() : (long) snapshot.schemaId(),
                    entity::setSchemaId);
            touched |= assign(entity::getTimeMillis, snapshot.timestampMillis(), entity::setTimeMillis);
            touched |= assign(entity::getBaseManifestList, snapshot.manifestListLocation(),
                    entity::setBaseManifestList);
            touched |= assign(entity::getCommitKind, summary.get(KEY_OPERATION), entity::setCommitKind);
            touched |= assign(entity::getSummaryJson, Json.write(summary), entity::setSummaryJson);
            Long totalRecords = longOf(summary.get(KEY_TOTAL_RECORDS));
            touched |= assign(entity::getTotalRecordCount, totalRecords, entity::setTotalRecordCount);
            touched |= assign(entity::getRecordCount, totalRecords, entity::setRecordCount);
            touched |= assign(entity::getFileCount, longOf(summary.get(KEY_TOTAL_DATA_FILES)),
                    entity::setFileCount);
            touched |= assign(entity::getFileSizeInBytes, longOf(summary.get(KEY_TOTAL_FILES_SIZE)),
                    entity::setFileSizeInBytes);
            touched |= assign(entity::getDeleteFileCount, longOf(summary.get(KEY_TOTAL_DELETE_FILES)),
                    entity::setDeleteFileCount);
            if (touched) {
                changed.add(entity);
            }
        }
        if (!byId.isEmpty()) {
            snapshotRepository.deleteAll(byId.values());
        }
        if (!changed.isEmpty()) {
            snapshotRepository.saveAll(changed);
        }
    }

    // ------------------------------------------------------------------ refs

    /**
     * {@code lakehouse_branch} / {@code lakehouse_tag} ← {@code metadata.refs}，按 {@code type} 分。
     *
     * <p><b>{@code main} 不进分支表。</b>它是 Iceberg 的默认分支，每个表都有；
     * 写进来会让控制台的分支页签永远多一行「main」，而那一行的「删除」按钮
     * 在 Iceberg 里是非法操作（默认分支不能删）。Paimon 侧的分支表同样只有显式创建的分支，
     * 两边在这一点上是一致的。
     *
     * <p><b>保留策略落进 {@code options}。</b>refs 带的 {@code min-snapshots-to-keep} /
     * {@code max-snapshot-age-ms} / {@code max-ref-age-ms} 用 Iceberg 自己的键名存进
     * 分支的 options 列——它们就是「保留策略」这三个字在各自体系里的取值，
     * 换个名字存只会让对不上号。标签表没有 options 列，因此标签的 {@code max-ref-age-ms}
     * 留在文档里（{@code metadata_doc} 是真相源，投影列不是）。
     */
    private void syncRefs(TableEntity table, TableMetadata metadata) {
        List<BranchEntity> branches = branchRepository.findAllByTableIdOrderByNameAsc(table.getId());
        Map<String, BranchEntity> branchByName = new LinkedHashMap<>();
        for (BranchEntity branch : branches) {
            branchByName.put(branch.getName(), branch);
        }
        List<TagEntity> tags = tagRepository.findAllByTableIdOrderByTagNameAsc(table.getId());
        Map<String, TagEntity> tagByName = new LinkedHashMap<>();
        for (TagEntity tag : tags) {
            tagByName.put(tag.getTagName(), tag);
        }

        List<BranchEntity> changedBranches = new ArrayList<>();
        List<TagEntity> changedTags = new ArrayList<>();
        for (Map.Entry<String, SnapshotRef> entry : metadata.refs().entrySet()) {
            String name = entry.getKey();
            SnapshotRef ref = entry.getValue();
            if (ref.isBranch()) {
                if (MAIN_BRANCH.equals(name)) {
                    continue;
                }
                BranchEntity branch = branchByName.remove(name);
                boolean created = branch == null;
                if (created) {
                    branch = new BranchEntity();
                    branch.setId(Paging.newId());
                    branch.setTableId(table.getId());
                    branch.setName(name);
                    branch.markCreated(RequestContext.principal(), RequestContext.now());
                }
                Map<String, String> options = new LinkedHashMap<>();
                if (ref.minSnapshotsToKeep() != null) {
                    options.put("min-snapshots-to-keep", String.valueOf(ref.minSnapshotsToKeep()));
                }
                if (ref.maxSnapshotAgeMs() != null) {
                    options.put("max-snapshot-age-ms", String.valueOf(ref.maxSnapshotAgeMs()));
                }
                if (ref.maxRefAgeMs() != null) {
                    options.put("max-ref-age-ms", String.valueOf(ref.maxRefAgeMs()));
                }
                boolean touched = created;
                touched |= assign(branch::getSnapshotId, ref.snapshotId(), branch::setSnapshotId);
                // options 列在库里有可能是 null（这一行由更早的版本写入），比对前归一化
                if (!options.equals(branch.getOptions() == null ? Map.of() : branch.getOptions())) {
                    branch.setOptions(options);
                    touched = true;
                }
                if (touched) {
                    changedBranches.add(branch);
                }
            } else {
                TagEntity tag = tagByName.remove(name);
                boolean created = tag == null;
                if (created) {
                    tag = new TagEntity();
                    tag.setId(Paging.newId());
                    tag.setTableId(table.getId());
                    tag.setTagName(name);
                }
                boolean touched = created;
                touched |= assign(tag::getSnapshotId, ref.snapshotId(), tag::setSnapshotId);
                if (touched) {
                    changedTags.add(tag);
                }
            }
        }
        if (!branchByName.isEmpty()) {
            branchRepository.deleteAll(branchByName.values());
        }
        if (!tagByName.isEmpty()) {
            tagRepository.deleteAll(tagByName.values());
        }
        if (!changedBranches.isEmpty()) {
            branchRepository.saveAll(changedBranches);
        }
        if (!changedTags.isEmpty()) {
            tagRepository.saveAll(changedTags);
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 「值变了才写回」：返回是否发生了改动。
     *
     * <p>为什么在意这个：投影的重建发生在每一次提交上，而 Hibernate 的脏检查会把
     * 「赋了一个相同的值」也算成一次 update 吗——不会。但**新行**必须被 save，
     * 而已有行若一个字段都没变就完全不必进 {@code saveAll}。于是这里统一返回
     * 「是否改动」，调用方据此决定要不要把实体交给仓储。这在提交很频繁的表上
     * 省下的是每次一整套无意义的 update 语句。
     */
    private static <T> boolean assign(java.util.function.Supplier<T> current, T value,
                                     java.util.function.Consumer<T> setter) {
        if (value == null) {
            return false;
        }
        if (value.equals(current.get())) {
            return false;
        }
        setter.accept(value);
        return true;
    }

    /**
     * 把 summary 里的数字读成 {@code Long}。
     *
     * <p>iceberg 的 summary 的值都是**字符串**（{@code "total-records": "20"}），
     * 而且 v1 表根本没有 {@code total-data-files} 这个键。读不出来就是 {@code null}，
     * 不回落成 0：0 在控制台上是一个有意义的数（这张表空的），而不是「不知道」。
     */
    private static Long longOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.debug("iceberg summary value is not a number: {}", value);
            return null;
        }
    }
}
