package io.github.melin.lakehouse.rest.iceberg.support;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.MetadataUpdateParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirementParser;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg 的「更新代数」：把 {@code updates} / {@code requirements} 报文解析成上游类型、
 * 校验并应用。
 *
 * <p><b>为什么全部委托给 {@code iceberg-core} 而不是自己写。</b>报文里的
 * {@code update} 有 25 种、{@code requirement} 有 8 种，每种都有精确语义：
 * {@code add-snapshot} 要处理 {@code sequence-number} 与父快照，
 * {@code set-snapshot-ref} 要处理保留策略，{@code remove-snapshots} 要连带清掉指向它的 ref。
 * 手写等于把上游的元数据代数重写一遍，而且**随上游版本漂移**
 * （1.9→1.10 之间就新增了 {@code next-row-id} / {@code encryption-keys} /
 * {@code read-restrictions}）。这里的每一行都是转发。
 *
 * <p><b>报文体里的 {@code updates} 元素刻意用 JSON 树而不是建类型。</b>它们的形状由
 * Iceberg 的元数据代数定义，建一套 DTO 等于把上游 schema 抄一份并承诺它不变。
 * 转发时只做一次「JSON 树 → 文本」，交给上游的 parser——它报的错也比我们自己校验得准
 * （{@code Cannot convert metadata update action to json: nonsense} 比一句
 * {@code invalid updates} 有用得多）。
 *
 * <p><b>「明确报错而不是静默忽略」这条由上游自然满足。</b>把 view 的 update
 * （{@code add-view-version} / {@code set-current-view-version}）塞进**表**提交里时，
 * {@code applyTo(TableMetadata.Builder)} 的默认实现直接抛
 * {@code UnsupportedOperationException: Cannot apply update … to a table}（实测）。
 * 这里把它翻成 400 而不是吞掉：静默吞掉一个 update，等于让服务端返回一份与客户端预期
 * 不符的元数据，而客户端会拿它继续往下算。
 *
 * <p>三类失败的映射与 Iceberg 自己的服务端（{@code CatalogHandlers} + 适配层）一致：
 *
 * <ul>
 *   <li><b>报文解析失败</b>（未知 action、字段类型不对）→ 400 {@code BadRequestException}；
 *       客户端送错了东西，服务端没有可重试的动作。</li>
 *   <li><b>{@code requirements} 校验失败</b> → 409 {@code CommitFailedException}。
 *       上游 {@code UpdateRequirement.validate} 抛的就是 {@code CommitFailedException}；
 *       它的语义是「你手上的元数据已经过期，回去 refresh 再来」，
 *       而客户端的重试逻辑（{@code Tasks.foreach(...).onlyRetryOn(CommitFailedException)}）
 *       正是挂在 409 上的。</li>
 *   <li><b>update 应用失败</b>（例如把 view 的 update 用到表上）→ 400 带上游的原文；
 *       {@code UnsupportedOperationException} 另带同名 {@code type}，便于客户端区分
 *       「我不支持这个能力」与「你的报文写错了」。</li>
 * </ul>
 */
public final class IcebergUpdateCodec {

    /**
     * {@code add-view-version} 这条 update 的 action 取值（规格里逐字的值）。
     *
     * <p>只在本类的 {@link #parseViewVersion} 里用来构造一个包装报文，
     * 见那个方法的注释。写成常量而不是内联，是为了让「这是一处刻意的构造」
     * 在调用点上看得出来。
     */
    private static final String ACTION_ADD_VIEW_VERSION = "add-view-version";

    private IcebergUpdateCodec() {
    }

    // ------------------------------------------------------------------ 解析

    /** 解析 {@code updates}；{@code null} 视作空列表。 */
    public static List<MetadataUpdate> parseUpdates(List<JsonNode> nodes) {
        List<MetadataUpdate> updates = new ArrayList<>();
        if (nodes == null) {
            return updates;
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            try {
                updates.add(MetadataUpdateParser.fromJson(Json.write(node)));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("Cannot parse table update " + Json.write(node) + ": " + e.getMessage());
            }
        }
        return updates;
    }

    /** 解析 {@code requirements}；{@code null} 视作空列表。 */
    public static List<UpdateRequirement> parseRequirements(List<JsonNode> nodes) {
        List<UpdateRequirement> requirements = new ArrayList<>();
        if (nodes == null) {
            return requirements;
        }
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            try {
                requirements.add(UpdateRequirementParser.fromJson(Json.write(node)));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("Cannot parse table requirement " + Json.write(node)
                        + ": " + e.getMessage());
            }
        }
        return requirements;
    }

    /**
     * 把建视图报文里的 {@code view-version} 解析成上游类型。
     *
     * <p><b>为什么要绕一层 {@code add-view-version} 包装。</b>上游的
     * {@code ViewVersionParser} 只公开了 {@code fromJson(JsonNode)} 这个入口，
     * 而那个 {@code JsonNode} 是 **Jackson 2** 的类型——本模块对外用的是
     * Jackson 3（{@code tools.jackson.databind}），为这一处把 Jackson 2 的
     * databind 引进来，等于让两套 Jackson 的边界从「Iceberg 内部」扩散到本模块。
     *
     * <p>而 {@code view-version} 的**外层形态**在 Iceberg 的更新代数里是有定义的：
     * {@code add-view-version} 就是「把一个 view-version 加进视图」。于是把它包成
     * 那一条 update（字符串拼接，只加一个 action 字段），交给已经在用的
     * {@link MetadataUpdateParser}，再从结果里取出 {@code viewVersion()}。
     * 走的仍然是上游自己的解析器，报文校验的准确性一分不减，
     * 而 Jackson 2 的类型一个都没有进到本模块。
     *
     * <p>取出来的 {@code ViewVersion} 里的 {@code schema-id} 是客户端发来的占位值，
     * 真正的 schema id 由 {@code ViewMetadata.Builder#setCurrentVersion(version, schema)}
     * 在分配 schema 时覆盖（实测：客户端送 99，最终落到 0）。
     */
    public static ViewVersion parseViewVersion(JsonNode node) {
        if (node == null || node.isNull()) {
            throw ApiException.badRequest("view-version is required to create a view");
        }
        try {
            MetadataUpdate update = MetadataUpdateParser.fromJson(
                    "{\"action\":\"" + ACTION_ADD_VIEW_VERSION + "\",\"view-version\":" + Json.write(node) + "}");
            return ((MetadataUpdate.AddViewVersion) update).viewVersion();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse view-version: " + messageOf(e));
        }
    }

    // ------------------------------------------------------------------ 表

    /**
     * 逐条校验 {@code requirements} 对当前表的元数据是否成立。
     *
     * <p>校验的是**基线**（客户端上次读到的那一份），不是应用 updates 之后的结果——
     * requirements 的语义是「我基于这个状态提交」，与 Paimon 侧 {@code baseSnapshotUuid}
     * 的位置完全一样。
     */
    public static void validate(List<UpdateRequirement> requirements, TableMetadata metadata) {
        for (UpdateRequirement requirement : requirements) {
            try {
                requirement.validate(metadata);
            } catch (CommitFailedException e) {
                throw new ApiException(409, ResourceType.TABLE, null, e.getMessage(), "CommitFailedException");
            } catch (RuntimeException e) {
                throw ApiException.badRequest(messageOf(e));
            }
        }
    }

    /** 把 {@code updates} 依次作用在基线上，返回新的元数据。 */
    public static TableMetadata apply(TableMetadata base, List<MetadataUpdate> updates) {
        TableMetadata.Builder builder = TableMetadata.buildFrom(base);
        for (MetadataUpdate update : updates) {
            try {
                update.applyTo(builder);
            } catch (CommitFailedException e) {
                throw new ApiException(409, ResourceType.TABLE, null, e.getMessage(), "CommitFailedException");
            } catch (UnsupportedOperationException e) {
                throw new ApiException(400, ResourceType.TABLE, null, e.getMessage(), "UnsupportedOperationException");
            } catch (ValidationException | IllegalArgumentException | IllegalStateException e) {
                throw ApiException.badRequest(messageOf(e));
            }
        }
        try {
            return builder.build();
        } catch (ValidationException | IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 视图

    /** 视图侧的 requirements 校验，语义与表一致。 */
    public static void validate(List<UpdateRequirement> requirements, ViewMetadata metadata) {
        for (UpdateRequirement requirement : requirements) {
            try {
                requirement.validate(metadata);
            } catch (CommitFailedException e) {
                throw new ApiException(409, ResourceType.VIEW, null, e.getMessage(), "CommitFailedException");
            } catch (RuntimeException e) {
                throw ApiException.badRequest(messageOf(e));
            }
        }
    }

    /** 视图侧的 update 应用。 */
    public static ViewMetadata apply(ViewMetadata base, List<MetadataUpdate> updates) {
        ViewMetadata.Builder builder = ViewMetadata.buildFrom(base);
        for (MetadataUpdate update : updates) {
            try {
                update.applyTo(builder);
            } catch (CommitFailedException e) {
                throw new ApiException(409, ResourceType.VIEW, null, e.getMessage(), "CommitFailedException");
            } catch (UnsupportedOperationException e) {
                throw new ApiException(400, ResourceType.VIEW, null, e.getMessage(), "UnsupportedOperationException");
            } catch (ValidationException | IllegalArgumentException | IllegalStateException e) {
                throw ApiException.badRequest(messageOf(e));
            }
        }
        try {
            return builder.build();
        } catch (ValidationException | IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * 取异常的说明文本，为空时退到类名。
     *
     * <p>上游有几处 `Preconditions.checkArgument` 不带消息，异常文本是 {@code null}。
     * 直接把它交给 {@code ApiException} 会让错误响应体里的 {@code message} 变成
     * 一个 JSON null——排障时看到的是一个没有任何信息的 400。
     */
    private static String messageOf(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
