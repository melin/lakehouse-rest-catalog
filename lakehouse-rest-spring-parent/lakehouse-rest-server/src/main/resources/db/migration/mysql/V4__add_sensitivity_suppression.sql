-- V4：人审否决的永久抑制项（P1，设计见 docs/sensitive-data-design.md §7.2）
--
-- 为什么是手写的增量而不是重派生 V1：V1 的 checksum 已被应用过它的库记录，内容一旦
-- 变过，那些库会以 "Migration checksum mismatch for migration version 1" 拒绝启动
-- （validate-on-migrate: true）。V3 同理。因此新增表只能走增量，见
-- scripts/gen-flyway-baseline.py 的 docstring「只到 V1 尚未被任何库应用为止」。
--
-- 本表的建表语句与 sql/schema-mysql.sql 里那份全量快照逐字一致（快照是给人重建
-- 空库用的，这份增量是给存量库升级用的）。改结构仍然只改实体，然后依次跑
-- ./scripts/gen-mysql-ddl.sh 与 scripts/gen-flyway-baseline.py；本文件是手工维护的。
--
-- **纯新增，没有一条 alter table 现有表。**
--
-- 为什么需要这张表：识别器认为是身份证 → 人审判定不是（比如那是「身份证后四位」
-- 的掩码列）→ 第二天重扫又判成敏感 → 人再拒一次。没有抑制项，这个循环每天都要
-- 人手点一遍，而人手是有限资源——最后的结果是没人再看那个人审队列，闸门形同不存在。
--
-- 为什么键是 (column_ref_hash, type_code)：
--   按 finding 记会让抑制项跟着「哪一轮扫描」走，下一轮就没有抑制了；
--   按 tag 记不行——拒绝的语义恰恰是**没有**标签；
--   按列哈希 + 类型记，才表达得出「这一列不是身份证」这个**结论**。
-- 失效只有两条路：列被重命名（哈希变了 = 视为新列）或治理员显式解禁
-- （enabled_flag 置 false，**不删行**——删行等于删掉决策史）。

create table lakehouse_sensitivity_suppression (
        enabled_flag bit not null comment '是否仍生效；解禁置 false，不删行',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        decided_at bigint comment '否决时刻（epoch 毫秒）',
        finding_id bigint comment '来源 finding 的 id（溯源用，可空）',
        id bigint not null auto_increment comment '自增主键',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_name varchar(64) not null comment 'catalog 名',
        column_ref_hash varchar(64) not null comment 'catalog.db.table.column 的 SHA-256（列改名后哈希变，抑制项自动失效）',
        type_code varchar(64) not null comment '被否决的敏感类型',
        reason varchar(512) comment '否决理由（人审备注）',
        table_name varchar(512) not null comment '表名',
        column_name varchar(255) not null comment '列名',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) not null comment '库名',
        decided_by varchar(255) comment '否决人主体名',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='人审否决的永久抑制项（按列哈希 + 类型记，重扫不再自动打标）' engine=InnoDB;

create index ix_sensitivity_suppression_catalog
       on lakehouse_sensitivity_suppression (catalog_name);

alter table lakehouse_sensitivity_suppression
       add constraint uk_sensitivity_suppression unique (column_ref_hash, type_code);
