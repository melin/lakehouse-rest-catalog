package io.github.melin.paimonrest.support;

import io.github.melin.paimonrest.dto.TypeDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.apache.paimon.table.object.ObjectTable;
import org.apache.paimon.types.DataField;

/**
 * 表格式（table type）：Paimon 表 / Format 表 / Object 表 / Lance 表。
 *
 * <p>类型由 schema 的 {@code options.type} 承载，与 Paimon 自己的用法一致
 * （{@code CoreOptions.TYPE}，键 {@code type}，取值见 {@link org.apache.paimon.TableType}）。
 * 规格里的 {@code Schema} 没有独立的类型字段，SQL 侧也是这么表达的：
 * {@code CREATE TABLE ... WITH ('type' = 'format-table')}。因此建表请求里
 * {@code options.type} 缺席即 Paimon 表，服务端不额外发明协议字段。
 *
 * <p>三种非 Paimon 表在建表时都需要一点只有目录才知道的信息，这些归一化集中在本类：
 *
 * <ul>
 *   <li><b>缺省时补 {@code path}</b>。引擎侧构造这三种表时只读 {@code options.path}
 *       （{@code CatalogUtils.toFormatTable/toObjectTable/toLanceTable}）。客户端不给就用目录
 *       分配的位置，给了就用客户端的（{@link #honorsClientPath}）——与上游
 *       {@code JdbcCatalog.createObjectTable} 的 {@code options.putIfAbsent(PATH, tablePath)}
 *       同一个次序：先客户端、后目录。</li>
 *   <li><b>Object 表用固定 schema</b>。它对外暴露的是「目录里对象的元数据」而不是用户声明的列，
 *       Paimon 的 {@code AbstractCatalog.buildObjectTableSchema} 就是拿
 *       {@link ObjectTable#SCHEMA} 覆盖掉用户字段的（六个固定列：
 *       {@code path / name / length / mtime / atime / owner}）。
 *       这里镜像同一套做法，好处是落库的 schema、仓库里的 {@code schema-0}、
 *       引擎读到的列三者一致；若顺着用户字段写下去，仓库里会留下一张没有列的 Paimon 表文件。</li>
 *   <li><b>Format 表不能有主键</b>。上游在 {@code CatalogUtils.validateFormatTableOptions}
 *       里把这条当作硬约束（{@code Cannot define primaryKey for format table.}）。
 *       本服务端不经由 {@code AbstractCatalog} 建表，因此必须自己拦下——
 *       否则写出去的 schema 文件在引擎侧读的时候才报错。</li>
 * </ul>
 *
 * <p>这里<b>不</b>做的事：给 Paimon 表补 {@code path}（它不需要，且会改变既有表的 options）、
 * 校验 Paimon 表的其余选项（那些由 Paimon 自己的校验负责）。
 */
public final class TableKinds {

    /** {@code options.type} 的键，等价于 {@code CoreOptions.TYPE}。 */
    public static final String TYPE_OPTION = "type";

    /** {@code CoreOptions.PATH} 的键；三种非 Paimon 表都靠它定位数据。 */
    public static final String PATH_OPTION = "path";

    /** Paimon 表：{@code options.type} 缺席或显式写 {@code table}。 */
    public static final String PAIMON = "table";

    /** 目录里的既有文件（CSV / Parquet / ORC / JSON / text），不受快照管理。 */
    public static final String FORMAT_TABLE = "format-table";

    /** 目录里非结构化对象的元数据表，列固定。 */
    public static final String OBJECT_TABLE = "object-table";

    /** Lance 数据集表。 */
    public static final String LANCE_TABLE = "lance-table";

    /** 表格式。 */
    public enum Kind {
        PAIMON,
        FORMAT,
        OBJECT,
        LANCE,
    }

    private TableKinds() {
    }

    /**
     * 归一化一次建表请求的 schema，返回它的表格式。
     *
     * <p>{@code kind} 由调用方先用 {@link #parse} 拿到再传进来（而不是在这里解析）：
     * 建表前还有一步要按表格式决定做不做——catalog 级的 {@code table-default.*}
     * 只合并到 Paimon 表（见 {@link TableDefaults}）——两边各自解析一遍同一份 options
     * 是没必要的重复。
     *
     * @param schema    已被 {@link SchemaSupport#normalize} 归一化过的 schema，就地修改
     * @param tablePath 这张表的**表位置**——客户端显式给的，或目录按模板分配的
     *                  （由调用方用 {@link #honorsClientPath} / {@link #explicitPath} 定下来），
     *                  {@code path} 缺省时用它
     * @param kind      这次要建的表格式
     * @throws ApiException 该类型不允许的入参（如 Format 表带主键）
     */
    public static Kind prepareCreate(TypeDtos.Schema schema, String tablePath, Kind kind) {
        switch (kind) {
            case PAIMON -> {
                // Paimon 表不需要 path：它的位置由目录决定，options 里多一个 path 反而是噪音。
            }
            case FORMAT -> {
                if (!schema.getPrimaryKeys().isEmpty()) {
                    throw ApiException.badRequest(
                            "A format table does not support primary keys: " + schema.getPrimaryKeys());
                }
                defaultPath(schema, tablePath);
            }
            case LANCE -> defaultPath(schema, tablePath);
            case OBJECT -> {
                defaultPath(schema, tablePath);
                schema.setFields(objectTableFields());
                schema.getPrimaryKeys().clear();
                schema.getPartitionKeys().clear();
            }
        }
        return kind;
    }

    /**
     * 读 {@code options.type}。
     *
     * <p>这个取值不认识时直接 400，而不是「存下来再说」：写进库的类型若不认识，
     * 引擎侧会在读表时才炸（{@code CoreOptions.TYPE} 是枚举），而那时错误现场离原因很远。
     */
    public static Kind parse(java.util.Map<String, String> options) {
        String type = options == null ? null : options.get(TYPE_OPTION);
        if (type == null || type.isBlank()) {
            return Kind.PAIMON;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case PAIMON -> Kind.PAIMON;
            case FORMAT_TABLE -> Kind.FORMAT;
            case OBJECT_TABLE -> Kind.OBJECT;
            case LANCE_TABLE -> Kind.LANCE;
            default -> throw ApiException.badRequest(
                    "Unsupported table type: " + type + ". Use one of " + PAIMON + ", "
                            + FORMAT_TABLE + ", " + OBJECT_TABLE + ", " + LANCE_TABLE
                            + ", or omit " + TYPE_OPTION + " for a Paimon table");
        };
    }

    /**
     * 这张表的「表位置」是否该由客户端决定。
     *
     * <p>三种非 Paimon 表的位置就是数据目录本身——Format 表读的是一批同格式文件所在的目录、
     * Object 表索引的是一个对象目录、Lance 表指向一份 Lance 数据集。这些数据本来就在那里，
     * 不是本服务端写出来的，所以客户端比目录更清楚位置（它可能就是那个桶的 owner）。
     *
     * <p>Paimon 表不在此列：它的位置由目录决定，客户端给的 {@code path} 会被忽略——
     * 要让一张表指向另一份已经存在的 Paimon 数据，走 {@code register}。
     */
    public static boolean honorsClientPath(Kind kind) {
        return kind != Kind.PAIMON;
    }

    /**
     * 客户端在建表请求里显式给的位置（{@code options.path}）；没给、或给的是空白，返回 {@code null}。
     *
     * <p>只负责读，不判断这个值该不该用——用不用看 {@link #honorsClientPath}。
     */
    public static String explicitPath(TypeDtos.Schema schema) {
        if (schema == null || schema.getOptions() == null) {
            return null;
        }
        String path = schema.getOptions().get(PATH_OPTION);
        return path == null || path.isBlank() ? null : path;
    }

    /** Object 表的固定列，直接取 Paimon 的 {@link ObjectTable#SCHEMA}，避免手抄一套枚举。 */
    private static List<TypeDtos.DataField> objectTableFields() {
        List<TypeDtos.DataField> fields = new ArrayList<>();
        for (DataField field : ObjectTable.SCHEMA.getFields()) {
            TypeDtos.DataField target = new TypeDtos.DataField();
            target.setId(field.id());
            target.setName(field.name());
            // Paimon 的类型名自带可空性（STRING NOT NULL），与 DTO 的既有约定一致
            target.setType(field.type().toString());
            target.setDescription(field.description());
            fields.add(target);
        }
        return fields;
    }

    /** 客户端已经给了位置就用它的（对象目录未必在表目录下），没有才用目录分配的位置。 */
    private static void defaultPath(TypeDtos.Schema schema, String tablePath) {
        if (schema.getOptions() == null) {
            schema.setOptions(new LinkedHashMap<>());
        }
        String path = schema.getOptions().get(PATH_OPTION);
        if (path == null || path.isBlank()) {
            schema.getOptions().put(PATH_OPTION, tablePath);
        }
    }
}
