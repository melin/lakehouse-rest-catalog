package io.github.melin.lakehouse.rest.dto;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Paimon 官方 REST Management API 权限端点的 wire 契约。
 *
 * <p>规格来源：{@code https://paimon.apache.org/docs/master/concepts/rest/management-api}
 * 的 Permission model 一节（完整 wire 契约在官方 {@code rest-management-open-api.yaml}）。
 * 与 {@code spec/polaris-management-service.yml} 的 RBAC 是**两套并行的模型**：
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
     * 内建 access 词表。
     *
     * <p>取值与官方文档逐字一致——创建类权限特意用**不带下划线**的持久化名
     * （{@code CREATETABLE}，不是 {@code CREATE_TABLE}）。各资源类型允许的取值
     * 来自文档的 Access/Resource 对照表，服务端按它做入参校验。
     *
     * <p><b>与官方词表的一处刻意偏差：</b>官方对照表里 TABLE 层没有 {@code DESCRIBE}
     * （官方把它定义为「读 database 元数据」）。本服务端允许在 TABLE 上授予
     * DESCRIBE 并映射到 TABLE_READ_PROPERTIES——控制台的表级授权要提供
     * 「Describe」这一项，而它在本工程的授权判定里有明确对应，不存在歧义。
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
        GRANT;

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
         */
        public static Set<Access> allowedFor(ResourceType type) {
            return switch (type) {
                case CATALOG -> set(ALL, ALTER, DROP, GRANT, CREATEDATABASE);
                case CATALOG_ALL -> set(ALL, DESCRIBE, ALTER, DROP, GRANT,
                        CREATETABLE, CREATEVIEW, CREATEFUNCTION, LIST, SELECT, UPDATE);
                case DATABASE -> set(ALL, DESCRIBE, ALTER, DROP, GRANT,
                        CREATETABLE, CREATEVIEW, CREATEFUNCTION, LIST);
                case DATABASE_ALL -> set(ALL, SELECT, UPDATE, ALTER, DROP, GRANT);
                case TABLE -> set(ALL, SELECT, UPDATE, ALTER, DROP, GRANT, DESCRIBE);
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

    /** {@code GET /v1/{prefix}/permissions} 的响应。 */
    public record PermissionList(List<PermissionAssignment> assignments, String nextPageToken) {
    }
}
