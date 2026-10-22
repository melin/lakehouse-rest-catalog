package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.support.AuditPrincipal;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/**
 * 带审计字段的实体基类。
 *
 * <p>时间戳统一为 epoch 毫秒，与规格中 {@code int64} 的 {@code createdAt} /
 * {@code updatedAt} 语义一致。{@code owner} / {@code createdBy} / {@code updatedBy}
 * 取自当前请求的认证主体。
 *
 * <p><b>三个主体名列的长度上限是 255，且这里就是收口点。</b>主体名在此之前可能很长
 * （认不出的令牌会退化为「令牌即主体名」，控制台访问令牌就有 272 个字符），
 * 直接写库会以 {@code Data too long for column 'created_by'} 失败——把业务写入
 * 打挂在审计字段上显然不对。因此写入前一律经 {@link AuditPrincipal#of} 归一化，
 * 任何调用方传什么进来都不会越过列宽——{@link #touch} / {@link #markCreated} 是入口，
 * {@link #normalizeAuditColumns()} 是兜底（连直接用 setter 的调用点也覆盖）。
 * 列宽显式写出来（而不依赖 Hibernate 的默认值），
 * 是为了让「实体声明」与 {@code sql/schema-mysql.sql} 里的 {@code varchar(255)}
 * 之间的对应关系在代码里看得见。
 *
 * <p>各列带 {@code comment}，它们是 {@code sql/schema-mysql.sql} 里列注释的来源：
 * DDL 由 Hibernate 从实体生成，注释只有写在实体上才不会在下次生成时丢失。
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AuditedEntity {

    @Column(name = "owner", length = AuditPrincipal.MAX_LENGTH,
            comment = "记录归属主体（展示与过滤用，不参与授权判定）")
    private String owner;

    @Column(name = "created_at", comment = "创建时刻（epoch 毫秒）")
    private Long createdAt;

    @Column(name = "created_by", length = AuditPrincipal.MAX_LENGTH,
            comment = "创建者主体名（写入前已按列宽归一化）")
    private String createdBy;

    @Column(name = "updated_at", comment = "最后更新时刻（epoch 毫秒）")
    private Long updatedAt;

    @Column(name = "updated_by", length = AuditPrincipal.MAX_LENGTH,
            comment = "最后更新者主体名（写入前已按列宽归一化）")
    private String updatedBy;

    /** 记录创建审计信息。 */
    public void markCreated(String principal, long now) {
        String name = AuditPrincipal.of(principal);
        this.owner = name;
        this.createdBy = name;
        this.createdAt = now;
        // 传已归一化的值：of 幂等，不会再摘一次
        touch(name, now);
    }

    /** 记录更新审计信息。 */
    public void touch(String principal, long now) {
        this.updatedBy = AuditPrincipal.of(principal);
        this.updatedAt = now;
    }

    /**
     * 写库前再归一化一次三列——<b>这才是「收口点」真正生效的地方</b>。
     *
     * <p>为什么单靠 {@link #touch} / {@link #markCreated} 不够：Lombok 生成的
     * {@code setUpdatedBy} 是公开的，调用方完全可以先 {@code setUpdatedAt} 再
     * {@code setUpdatedBy} 而绕过 {@link #touch}（服务里就有这样的调用点）。
     * 漏一次的表现是 {@code Data too long for column 'updated_by'}，
     * 而调用方看到的是一次与审计字段毫无关系的 500。JPA 的生命周期回调是唯一能
     * 覆盖「所有实体写入」的位置：{@code save} 走 {@code @PrePersist} / {@code @PreUpdate}，
     * 任何调用方、任何顺序都跑不掉。
     *
     * <p>{@code null} 保持 {@code null}：有些实体在某一列上本来就没有值
     * （例如未经 {@code markCreated} 建出来的行），把它填成
     * {@link AuditPrincipal#ANONYMOUS} 会把「没有记录」说成「anonymous 干的」。
     *
     * <p><b>批量更新覆盖不到。</b>JPQL 的 {@code update ...} 直接发 SQL，不加载实体、
     * 不触发回调——那一条路径必须自己归一化，用
     * {@code RequestContext.auditPrincipal()}（见那里的注释）。
     */
    @PrePersist
    @PreUpdate
    public void normalizeAuditColumns() {
        this.owner = normalize(this.owner);
        this.createdBy = normalize(this.createdBy);
        this.updatedBy = normalize(this.updatedBy);
    }

    private static String normalize(String value) {
        return value == null ? null : AuditPrincipal.of(value);
    }
}
