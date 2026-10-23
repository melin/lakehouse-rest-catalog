package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 一列的脱敏定义（{@code lakehouse_column_mask}，设计见
 * {@code docs/row-filter-column-mask-design.md} §5.2）。
 *
 * <p><b>一行 = 一列一个 mask</b>，挂在 {@link DataPolicyEntity} 下。一列两条 mask
 * 「挑一条执行」从数据模型上就不存在（唯一键挡住）——与 Databricks 的
 * 「同列多 mask 报错」同语义，但把冲突挡在写入时而不是查询时。
 *
 * <p><b>mask 有两种存储形态</b>（§5.3.2.1「标准集 + 逃生口」）：
 * {@code mask_json} 里是 {@code {"fn": ..., "args": [...]}}（标准集，控制台渲染成
 * 下拉 + 参数）或 {@code {"expr": ...}}（白名单表达式逃生口，控制台只读）。
 * 两种形态的<b>类型规则相同</b>：mask 结果类型必须等于原列类型（§5.3.3），
 * 校验在建策略时做（fail-closed），不是查询时。
 */
@Entity
@Table(name = "lakehouse_column_mask",
        uniqueConstraints = @UniqueConstraint(name = "uk_column_mask_column",
                columnNames = {"policy_id", "column_name"}),
        comment = "列脱敏定义（一列一个 mask，挂在表级策略下）")
@Getter
@Setter
public class ColumnMaskEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    @Column(name = "policy_id", length = 64, nullable = false,
            comment = "所属表级策略的 id（DataPolicyEntity 的自增主键）")
    private Long policyId;

    @Column(name = "column_name", length = 512, nullable = false,
            comment = "被脱敏的列名；与 policy_id 组成唯一键（一列一个 mask）")
    private String columnName;

    /** mask 定义：{@code {"fn": ..., "args": [...]}}（标准集）或 {@code {"expr": ...}}（逃生口）。 */
    @Column(name = "mask_json", length = 65535,
            comment = "mask 定义（标准集 fn/args 或白名单表达式 expr；类型规则见设计稿 §5.3.3）")
    private String maskJson;

    /** mask 的 SQL 渲染（只用于展示，语义以 mask_json 为准）。 */
    @Column(name = "mask_sql", length = 2048,
            comment = "mask 的 SQL 渲染（展示用）")
    private String maskSql;

    /**
     * NOT NULL 列 + 置空的显式确认（§5.3.3 与 SPIP 的冲突取舍）。
     *
     * <p>SPIP 的 fail-closed 清单明确「{@code REPLACE_WITH_NULL} on a required column
     * aborts」，直接硬拒会让「身份证号置空」这个最常见需求变成不可能；默认允许
     * 又会产出 P2 切官方 API 时注定失效的策略。所以让操作者勾一次：这个标志为
     * {@code true} 才允许把置空 mask 建在非空列上。SQL 侧对应的确认写进语法
     * （{@code SET MASK replace_with_null ALLOW NULL ON REQUIRED}，§13.5）。
     */
    @Column(name = "allow_null_on_required", nullable = false,
            comment = "置空 mask 落在非空列时的显式确认（默认 false = 拒绝）")
    private boolean allowNullOnRequired;
}
