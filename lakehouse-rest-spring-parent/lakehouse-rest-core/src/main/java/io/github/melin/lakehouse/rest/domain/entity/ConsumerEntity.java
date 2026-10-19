package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 流式消费进度：记录某个消费者下一次应读取的快照 id。
 */
@Entity
@Table(name = "lakehouse_consumer",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumer", columnNames = {"table_id", "consumer_id"}),
        indexes = @Index(name = "ix_consumer_table", columnList = "table_id"),
        comment = "流式消费进度：消费者下一次应读取的快照")
@Getter
@Setter
public class ConsumerEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "table_id", length = 64, nullable = false, comment = "所属表 id")
    private String tableId;

    @Column(name = "consumer_id", length = 512, nullable = false,
            comment = "消费者标识，由客户端生成的字符串")
    private String consumerId;

    @Column(name = "next_snapshot", comment = "该消费者下一次应读取的快照 id")
    private Long nextSnapshot;

    @Column(name = "updated_at", comment = "进度最后更新时刻（epoch 毫秒）")
    private Long updatedAt;
}
