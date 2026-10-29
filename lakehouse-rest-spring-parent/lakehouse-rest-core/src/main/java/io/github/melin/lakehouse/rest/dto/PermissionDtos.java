package io.github.melin.lakehouse.rest.dto;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.melin.lakehouse.rest.support.PageMeta;

/**
 * Paimon 官方 REST Management API 权限端点的 wire 契约。
 *
 * <p>规格来源：{@code https://paimon.apache.org/docs/master/concepts/rest/management-api}
 * 的 Permission model 一节（完整 wire 契约在官方 {@code rest-management-open-api.yaml}）。
 * 与扩展端点上那套 RBAC（形状源自已移除的 Polaris 规格）是**两套并行的模型**：
 * Polaris 把授权挂在 catalog role 上，Paimon 原生模型把授权直接挂在 principal
 * （`user:alice` / `role:sales_writer` 这类不透明字符串）上。
 *
 * <p>赋权身份是三元组 {@code (resource, access, principal)}；重复授予覆盖过期时间，
 * 撤销幂等——这两条语义都由服务层保证。
 */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    /**
     * 权限资源类型。
     *
     * <p>{@code CATALOG_ALL} / {@code DATABASE_ALL} 是**显式的作用域赋权**：
     * 覆盖其下全部（含此后新建的）database / table / view / function 后代，
     * 与「只在被引用资源本身生效」的精确类型相对。
     */
    public enum ResourceType {
        CATALOG,
        CATALOG_ALL,
        DATABASE,
        DATABASE_ALL,
        TABLE,
        COLUMN,
        VIEW,
        FUNCTION;

        public static java.util.Optional<ResourceType> parse(String value) {
            if (value == null || value.isBlank()) {
                return java.util.Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(type -> type.name().equals(value))
                    .findFirst();
        }

        /** 该类型是否要求 {@code database} 定位符。 */
        public boolean needsDatabase() {
            return this != CATALOG && this != CATALOG_ALL;
        }

        /** 该类型是否要求对象级定位符（表 / 视图 / 函数 / 列挂在表上）。 */
        public boolean needsObject() {
            return this == TABLE || this == COLUMN || this == VIEW || this == FUNCTION;
        }
    }

    /**
     * 内建 access 词表：官方的 12 项 + 本工程的 2 项扩展。
     *
     * <p>官方那 12 项取值与官方文档逐字一致——创建类权限特意用**不带下划线**的持久化名
     * （{@code CREATETABLE}，不是 {@code CREATE_TABLE}）。各资源类型允许的取值
     * 来自文档的 Access/Resource 对照表，服务端按它做入参校验。
     *
     * <p><b>两处刻意的偏差：</b>
     *
     * <ol>
     *   <li>官方对照表里 TABLE 层没有 {@code DESCRIBE}（官方把它定义为「读 database 元数据」）。
     *       本服务端允许在 TABLE 上授予 DESCRIBE 并映射到 TABLE_READ_PROPERTIES——控制台的
     *       表级授权要提供「Describe」这一项，而它在本工程的授权判定里有明确对应，无歧义。</li>
     *   <li>{@link #USE_CREDENTIAL} 与 {@link #USE_SECRET} 是**本工程新增的两个词**，
     *       官方 12 项里没有。理由与取值见这两个常量的注释：官方把「取凭据」和「取存的密钥」
     *       放在两个 Java 方法上（{@code getCredentials()} / {@code getSecrets()}），
     *       而本工程的协议面各只有一个端点，两者的区分只能落在**这次要发什么**上。
     *       合成一个词就意味着「能跑查询」和「能拿走长期密钥」是同一件事（设计稿 §3.4.3）。</li>
     * </ol>
     */
    public enum Access {
        ALL,
        CREATEDATABASE,
        DESCRIBE,
        ALTER,
        DROP,
        CREATETABLE,
        CREATEFUNCTION,
        CREATEVIEW,
        LIST,
        SELECT,
        UPDATE,
        GRANT,
        /**
         * 能取票：可以调 {@code .../tables/{t}/token}（Paimon 面）与
         * {@code .../tables/{t}/credentials}（Iceberg 面），也可以让 {@code loadTable}
         * 在 {@code vended-credentials} 委派里带回一份凭据。
         *
         * <p>它管的是「能不能拿到一张票」，不管票里有什么——那是一乘
         * {@link #USE_SECRET} 的事。分开的理由是两者风险不同：前者是日常
         * （引擎每次读表都要一次），后者一次就够（拿到的是没有时限的那对密钥）。
         */
        USE_CREDENTIAL,
        /**
         * 能拿走目录里存的长期密钥对：票里可以含 {@code accessKeyId} / 密钥本体。
         *
         * <p>只给 {@link #USE_CREDENTIAL} 时，短期凭据（STS 现签、有 TTL、policy 收窄到表）
         * 照常下发——那本来就是要发给引擎的东西；但**长期密钥对会被拒**，因为那对密钥
         * 没有时限、也没有作用域收窄，拿到就等于拿到了这台存储上该身份能做的一切。
         *
         * <p>两个词是**并列**而不是包含：只有 {@code USE_SECRET} 不能取票。官方对这两个点的
         * 描述就是互相排斥的（{@code USE_SECRET} 不能调 {@code getCredentials()}），
         * 本工程照抄这个方向——省掉一半授权就不会因为「反正是更宽的那个」而蒙对。
         */
        USE_SECRET;

        public static java.util.Optional<Access> parse(String value) {
            if (value == null || value.isBlank()) {
                return java.util.Optional.empty();
            }
            return Arrays.stream(values())
                    .filter(access -> access.name().equals(value))
                    .findFirst();
        }

        /**
         * 资源类型 → 允许授予的 access 集合，对应官方文档的 Access/Resource 对照表。
         *
         * <p>两个扩展词只挂在能**定位到表**的三种类型上（{@code TABLE} 与两个 {@code _ALL}
         * 后代作用域）。{@code DATABASE} 刻意不给：它的覆盖规则是「该库本身 + 库的集合操作」，
         * 不覆盖库里的具体表，授上去会看起来成功、实际对表级判定毫无作用——
         * 一个能被授出却不生效的权限点比没有它更坏。
         */
        public static Set<Access> allowedFor(ResourceType type) {
            return switch (type) {
                case CATALOG -> set(ALL, ALTER, DROP, GRANT, CREATEDATABASE);
                case CATALOG_ALL -> set(ALL, DESCRIBE, ALTER, DROP, GRANT,
                        CREATETABLE, CREATEVIEW, CREATEFUNCTION, LIST, SELECT, UPDATE,
                        USE_CREDENTIAL, USE_SECRET);
                case DATABASE -> set(ALL, DESCRIBE, ALTER, DROP, GRANT,
                        CREATETABLE, CREATEVIEW, CREATEFUNCTION, LIST);
                case DATABASE_ALL -> set(ALL, SELECT, UPDATE, ALTER, DROP, GRANT,
                        USE_CREDENTIAL, USE_SECRET);
                case TABLE -> set(ALL, SELECT, UPDATE, ALTER, DROP, GRANT, DESCRIBE,
                        USE_CREDENTIAL, USE_SECRET);
                case COLUMN -> set(SELECT);
                case VIEW -> set(ALL, SELECT, ALTER, DROP, GRANT, DESCRIBE);
                case FUNCTION -> set(ALL, SELECT, ALTER, DROP, GRANT, DESCRIBE);
            };
        }

        private static Set<Access> set(Access... values) {
            return new LinkedHashSet<>(List.of(values));
        }
    }

    /**
     * 资源定位器。{@code type} 是判别字段，其余字段按类型取用：
     * DATABASE/DATABASE_ALL 要 {@code database}；TABLE/COLUMN 要
     * {@code database}+{@code table}；VIEW 要 {@code view}；FUNCTION 要 {@code function}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionResource(String type, String database, String table,
                                     String view, String function) {
    }

    /**
     * COLUMN 赋权的列范围：{@code columnNames}（白名单）与
     * {@code excludedColumnNames}（黑名单）互斥，且恰好一个非空。
     * 列范围不属于赋权身份——重复授予整体替换而不是逐列合并。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionColumns(List<String> columnNames, List<String> excludedColumnNames) {
    }

    /**
     * 一条赋权。{@code expireTime} 是 ISO-8601 instant（如 {@code 2027-01-01T00:00:00Z}），
     * 可省略表示永不过期；只在 grant 请求里有意义，list 响应中过期后条目仍会列出
     * （列表反映落库内容，是否生效由判定层按过期时间排除）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PermissionAssignment(PermissionResource resource, String access, String principal,
                                       String expireTime, PermissionColumns columns) {
    }

    /**
     * {@code PermissionList}。两种分页口径并存，因为它们服务两类调用方：
     *
     * <ul>
     *   <li>{@code nextPageToken}——规格里的游标翻页，Spark 过程与控制台以外的
     *       客户端用它，一路向前、不关心总数；</li>
     *   <li>{@code page}——控制台表格的远程分页（页码 + 总数），
     *       {@code el-pagination} 要渲染「共 N 条、第几页」就必须有 total。</li>
     * </ul>
     *
     * <p>两者互斥：给了 {@code page}/{@code pageSize} 就按页码切（此时
     * {@code nextPageToken} 为 {@code null}），否则按游标切、{@code page} 为 {@code null}。
     */
    public record PermissionList(List<PermissionAssignment> assignments, String nextPageToken,
                                 PageMeta page) {
    }

    /**
     * 控制台授权清单的一行：**同一个「主体 × 作用范围」下的若干项权的聚合**。
     *
     * <p>为什么要服务端聚合：控制台的清单一行是「谁对什么有什么」，
     * 由多条赋权（同范围不同 access）折叠而成。如果服务端只按赋权分页，
     * 同一组的几条会被页边界切开，同一个主体在相邻两页各出现一次——
     * 那不是分页，那是把一行拆成两行。所以折叠在服务端做，页再切在折叠之后。
     *
     * <p>{@code accesses} 里的每一项都是**可独立撤销**的赋权身份
     * （资源 + access + principal），行内的 × 就用它去调撤销。
     *
     * @param principal    主体名（官方语义下的不透明字符串，如 {@code user:alice}）
     * @param resourceType 资源类型（{@code CATALOG_ALL} / {@code DATABASE} / {@code TABLE} …）
     * @param database     库名（catalog 级为空）
     * @param object       对象名（表 / 视图 / 函数名；库级与 catalog 级为空）
     * @param scope        界面上的「作用范围」文案：整个 catalog / 库名 / {@code 库.对象}
     * @param accesses     这个范围内的逐项权限
     */
    public record PermissionScope(String principal, String resourceType, String database,
                                  String object, String scope,
                                  List<PermissionAssignment> accesses) {
    }

    /** {@code GET /v1/{prefix}/permissions/scopes} 的响应：一页 {@link PermissionScope}。 */
    public record PermissionScopes(List<PermissionScope> scopes, PageMeta page) {
    }
}
