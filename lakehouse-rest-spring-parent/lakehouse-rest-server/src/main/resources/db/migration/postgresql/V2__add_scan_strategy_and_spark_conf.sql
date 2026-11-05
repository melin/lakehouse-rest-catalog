-- V2：执行集群扩两组批次策略 + 两组 Spark 参数（M5，随敏感数据识别引入）
--
-- 为什么是手写的增量而不是重派生 V1：V1 的 checksum 已被应用过它的库记录，
-- 内容一旦变过，那些库会以 "Migration checksum mismatch for migration version 1"
-- 拒绝启动（validate-on-migrate: true）。因此加列只能走增量，见
-- scripts/gen-flyway-baseline.py 的 docstring「只到 V1 尚未被任何库应用为止」。
--
-- 新增的四列与 sql/schema-postgres.sql 里那份全量快照逐字一致（快照是给人重建空库用的，
-- 这份增量是给存量库升级用的）。改结构仍然只改实体，然后依次跑
-- ./scripts/gen-postgres-ddl.sh 与 scripts/gen-flyway-baseline.py；本文件是手工维护的。
--
-- 两个 NOT NULL 列必须带 DEFAULT：存量行在 ADD COLUMN ... NOT NULL 时会被要求
-- 填一个值，没有 DEFAULT 那句直接执行失败。DEFAULT 与实体的字段默认值同值。
--
-- **这里没有 max_concurrent_scans**：扫描作业与优化批次共用 max_concurrent_jobs 一把闸门
-- （两者跑在同一台集群上、争同一份算力），独立列已废弃，见 sensitive-data-design.md §5.6.6。

alter table lakehouse_catalog_execution_cluster
    add column max_scan_tables_per_job integer default 200 not null;

alter table lakehouse_catalog_execution_cluster
    add column scan_job_timeout_seconds integer default 3600 not null;

alter table lakehouse_catalog_execution_cluster
    add column optimization_spark_conf text;

alter table lakehouse_catalog_execution_cluster
    add column scan_spark_conf text;

comment on column lakehouse_catalog_execution_cluster.max_scan_tables_per_job is
    '敏感识别单批最多扫描几张表';

comment on column lakehouse_catalog_execution_cluster.scan_job_timeout_seconds is
    '敏感识别批次超时秒数';

comment on column lakehouse_catalog_execution_cluster.optimization_spark_conf is
    '表优化 Spark 参数（逐行 k=v，前缀白名单过滤）';

comment on column lakehouse_catalog_execution_cluster.scan_spark_conf is
    '敏感识别 Spark 参数（逐行 k=v，前缀白名单过滤）';
