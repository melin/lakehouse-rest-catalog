-- V2：执行集群扩两组批次策略 + 两组 Spark 参数（M5，随敏感数据识别引入）
--
-- 为什么是手写的增量而不是重派生 V1：V1 的 checksum 已被应用过它的库记录，
-- 内容一旦变过，那些库会以 "Migration checksum mismatch for migration version 1"
-- 拒绝启动（validate-on-migrate: true）。因此加列只能走增量，见
-- scripts/gen-flyway-baseline.py 的 docstring「只到 V1 尚未被任何库应用为止」。
--
-- 新增的五列与 sql/schema-mysql.sql 里那份全量快照逐字一致（快照是给人重建空库用的，
-- 这份增量是给存量库升级用的）。改结构仍然只改实体，然后依次跑
-- ./scripts/gen-mysql-ddl.sh 与 scripts/gen-flyway-baseline.py；本文件是手工维护的。
--
-- 三个 NOT NULL 列必须带 DEFAULT：存量行在 ADD COLUMN ... NOT NULL 时会被要求
-- 填一个值，没有 DEFAULT 那句直接执行失败。DEFAULT 与实体的字段默认值同值。

alter table lakehouse_catalog_execution_cluster
    add column max_scan_tables_per_job integer default 200 not null comment '敏感识别单批最多扫描几张表';

alter table lakehouse_catalog_execution_cluster
    add column max_concurrent_scans integer default 2 not null comment '敏感识别同时运行几个扫描';

alter table lakehouse_catalog_execution_cluster
    add column scan_job_timeout_seconds integer default 3600 not null comment '敏感识别批次超时秒数';

alter table lakehouse_catalog_execution_cluster
    add column optimization_spark_conf text comment '表优化 Spark 参数（逐行 k=v，前缀白名单过滤）';

alter table lakehouse_catalog_execution_cluster
    add column scan_spark_conf text comment '敏感识别 Spark 参数（逐行 k=v，前缀白名单过滤）';
