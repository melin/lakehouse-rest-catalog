-- V5：定时扫描的调度项（P4，设计见 docs/sensitive-data-design.md §4.6 / §5.6.3）
--
-- **纯新增，没有一条 alter table 现有表。**
--
-- 为什么是手写的增量：V1 的 checksum 已被应用过它的库记录，内容一变，那些库会以
-- "Migration checksum mismatch for migration version 1" 拒绝启动（validate-on-migrate:
-- true）。V2–V4 同理。改结构仍然只改实体，然后依次跑 ./scripts/gen-mysql-ddl.sh、
-- ./scripts/gen-postgres-ddl.sh 与 scripts/gen-flyway-baseline.py；本文件手工维护。
--
-- 为什么需要这张表：定时扫描从「外部调度器调 POST /sensitivity-scans」翻成服务端
-- **自建**（本轮拍板）。外置方案里 cron 在别人手里，triggered_by 只能记成「那个
-- Airflow 账号」——「谁在什么时候读了明文」这条审计链上会多一个说不清的环节。
-- 自建之后，调度项本身成为一个可审计、可停启、能在控制台上被看见的对象。
--
-- 两个字段的语义刻意分开，合并会造出「改一次 cron，调度器以为抢占撞车」这类静默故障：
--   next_run_at    = 调度器抢占的 CAS 条件（update ... where next_run_at = <读到的值>），
--                    多副本下靠它保证「一次到期只推进一次」，展示只是它的副作用；
--   entity_version = 人工编辑的乐观锁（两个人同时改同一个调度项，后者收 409）。
--
-- 为什么没有 last_scan_id：一次调度可能因范围变化对应多条扫描，用「扫描表的
-- trigger_type=SCHEDULE + schedule_name」回查更准；存一个 id 会造出两表互相更新。
--
-- 为什么 cron 而不是「间隔秒数」（表优化那条是 schedule_interval_seconds）：
-- interval 表达不了「每天凌晨 3 点」，而那正是扫描最常见的诉求。

create table lakehouse_sensitivity_schedule (
        enabled_flag bit not null comment '是否启用；停用后不再触发，历史扫描仍按 schedule_name 可查',
        entity_version integer not null comment '乐观并发版本号（人工编辑用），与 next_run_at 的抢占语义分开',
        sample_size integer not null comment '采样行上限（默认 2000）',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        last_run_at bigint comment '上次触发的时刻（epoch 毫秒）',
        next_run_at bigint comment '下次触发时刻（epoch 毫秒）；调度器抢占的 CAS 条件',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        execution varchar(16) not null comment '执行位置：IN_PROCESS / SPARK（未填时取三层覆盖的生效值）',
        mode varchar(16) not null comment '扫描模式：METADATA_ONLY / SAMPLED',
        catalog_name varchar(64) not null comment 'catalog 名',
        cron varchar(64) not null comment 'cron 表达式（5 段或 6 段，秒级可选）',
        name varchar(128) not null comment '调度项名（唯一；扫描表按它回查历史，因此不可改名）',
        table_name varchar(512) comment '表名；catalog / 库级调度为空',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) comment '库名；catalog 级调度为空',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='定时扫描的调度项（自建调度：cron + next_run_at 抢占令牌）' engine=InnoDB;

create index ix_sensitivity_schedule_catalog
       on lakehouse_sensitivity_schedule (catalog_name);

alter table lakehouse_sensitivity_schedule
       add constraint uk_sensitivity_schedule unique (name);
