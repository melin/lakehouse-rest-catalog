package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 标签 → 脱敏动作的绑定（{@code lakehouse_tag_mask_binding}，设计见
 * {@code docs/sensitive-data-design.md} §4.5）。
 *
 * <p><b>为什么单独一张表，而不是给 {@link DataPolicyEntity} 加列。</b>绑定是
 * <b>catalog 级规则</b>（「凡是 {@code PII.CN_ID_CARD} 的列都脱敏」），
 * 策略是<b>表级对象</b>。把绑定塞进策略意味着 200 张表各存一份同样的绑定，
 * 改一次要改 200 行，且「漏改了两张表」不会有任何提示。二者的作用域不同，
 * 生命周期也不同：策略随表删而删，绑定不。
 *
 * <p><b>{@link #maskJson} 与 {@link ColumnMaskEntity#getMaskJson()} 同构</b>
 * （{@code {"fn": ..., "args": [...]}} 或 {@code {"expr": ...}}），因此
 * <b>两者的渲染与类型校验必须是同一份实现</b>。分叉的后果是：策略里的 mask
 * 校验能过，绑定里的同样内容被拒（或反过来）——同一个 {@code mask_json} 形状
 * 有两种解释，任何一次排查都要先问「这是哪条路的」。
 *
 * <p><b>{@link #tagValue} 可空 = 「该键任意值」。</b>这不是「没填」，而是通配：
 * {@code {tag_key: "PII.*", tag_value: null}} 表示凡 {@code PII} 域下的任何类型
 * 都按这条脱敏。唯一键 {@code (catalog_name, tag_key, tag_value)} 用空串还是
 * {@code null} 落库必须固定一侧——MySQL 唯一索引下多行 {@code NULL} 互不冲突，
 * 用 {@code null} 会让「同一键的通配绑定」可以插两条，所以服务层写入时把
 * 通配归一成空串，让唯一键真正生效。
 */
@Entity
@Table(name = "lakehouse_tag_mask_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_tag_mask_binding",
                columnNames = {"catalog_name", "tag_key", "tag_value"}),
        indexes = @Index(name = "ix_tag_mask_binding_catalog", columnList = "catalog_name"),
        comment = "标签到脱敏动作的绑定（catalog 级规则，按标签键/值匹配）")
@Getter
@Setter
public class TagMaskBindingEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    /** 绑定作用域：**catalog 级，不按表**（按表就退化成策略了，见类注释）。 */
    @Column(name = "catalog_name", length = 64, nullable = false,
            comment = "绑定作用域（catalog 级，不按表）")
    private String catalogName;

    /** 匹配的标签键，敏感标签形如 {@code PII.CN_ID_CARD}。 */
    @Column(name = "tag_key", length = 128, nullable = false,
            comment = "匹配的标签键；敏感标签形如 PII.CN_ID_CARD")
    private String tagKey;

    /** 匹配的标签值（分级）。**空串 = 该键任意值**（通配），见类注释。 */
    @Column(name = "tag_value", length = 255,
            comment = "匹配的标签值；空串 = 该键任意值（通配）")
    private String tagValue;

    /** 与 {@link ColumnMaskEntity#getMaskJson()} 同构；渲染与类型校验必须共用一份实现。 */
    @Column(name = "mask_json", length = 65535,
            comment = "脱敏定义（与 lakehouse_column_mask.mask_json 同构，校验共用一份实现）")
    private String maskJson;

    /** 渲染后的 SQL，只用于展示；语义以 {@link #maskJson} 为准。 */
    @Column(name = "mask_sql", length = 2048, comment = "脱敏的 SQL 渲染（展示用）")
    private String maskSql;

    /**
     * 同列命中多条绑定时的排序，**大者优先**。
     *
     * <p>为什么要有优先级：「凡 {@code PII.*} 都脱敏」与「{@code CN_ID_CARD} 保留
     * 前 6 后 4」两条绑定会同时命中一个身份证列。没有优先级就得靠插入顺序，
     * 而那在并发写入下不确定——同一列在不同实例上可能被脱敏成不同样子，
     * 是最难排查的一类问题。
     */
    @Column(name = "priority", nullable = false,
            comment = "多条绑定命中同列时的排序，大者优先")
    private int priority;

    @Column(name = "enabled_flag", nullable = false,
            comment = "是否启用；false = 保留绑定但不参与脱敏展开")
    private boolean enabled = true;
}
