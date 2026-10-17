-- LakeHouse Catalog Server 元数据表结构（MySQL 8.0）
--
-- 本文件由 scripts/gen-mysql-ddl.sh 从 JPA 实体元数据生成，请勿手工修改：
-- 手工改动会在下次生成时丢失，需要改结构请改实体后重新生成。
--
-- 应用方式（库不存在时会一并创建，库名可用 DB_NAME=... 重新生成来改）：
--   mysql -h 127.0.0.1 -u root -p < sql/schema-mysql.sql
--
-- 几点说明：
--   - 库、表都必须使用 utf8mb4，否则中文标识符与 JSON 文本会乱码；
--   - 本文件用于初始化空库，重复执行会因表已存在而失败；要重建请先 drop database；
--   - 应用启动时的 ddl-auto 是 validate：本文件与实体不一致时会直接启动失败，
--     而不是悄悄改表，因此升级代码后若实体有变，需要重新生成并迁移；
--   - 唯一约束里的 namespace_hash / spec_hash 是定长 SHA-256 摘要：
--     MySQL 的索引键上限是 3072 字节（utf8mb4 下 768 字符），
--     而命名空间路径与分区 spec 的长度没有上界，直接用原文建唯一索引会被
--     ERROR 1071 拒绝。原文字段仍然保留，供等值查询使用。
--
CREATE DATABASE IF NOT EXISTS `paimon_catalog`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `paimon_catalog`;

    create table paimon_branch (
        created_at bigint,
        snapshot_id bigint,
        updated_at bigint,
        id varchar(64) not null,
        table_id varchar(64) not null,
        name varchar(512) not null,
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        options_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_catalog (
        entity_version integer not null,
        created_at bigint,
        updated_at bigint,
        catalog_format varchar(32) default 'PAIMON' not null,
        catalog_type varchar(32),
        storage_type varchar(32),
        id varchar(64) not null,
        warehouse varchar(1024),
        created_by varchar(255),
        owner varchar(255),
        prefix_value varchar(255) not null,
        updated_by varchar(255),
        allowed_locations_json text,
        defaults_json text,
        overrides_json text,
        properties_json text,
        storage_config_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_catalog_role (
        entity_version integer not null,
        created_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        id varchar(64) not null,
        created_by varchar(255),
        name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        properties_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_catalog_role_grant (
        created_at bigint,
        updated_at bigint,
        catalog_role_id varchar(64) not null,
        id varchar(64) not null,
        principal_role_id varchar(64) not null,
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_consumer (
        next_snapshot bigint,
        updated_at bigint,
        id varchar(64) not null,
        table_id varchar(64) not null,
        consumer_id varchar(512) not null,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_database (
        created_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        id varchar(64) not null,
        name varchar(512) not null,
        location_value varchar(1024),
        comment_text varchar(4096),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        options_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_function (
        deterministic_flag bit,
        created_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        database_id varchar(64) not null,
        id varchar(64) not null,
        name varchar(512) not null,
        comment_text varchar(4096),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        options_json text,
        definitions_doc mediumtext,
        input_params_doc mediumtext,
        return_params_doc mediumtext,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_optimization_job (
        table_count integer not null,
        created_at bigint,
        deadline_at bigint,
        finished_at bigint,
        started_at bigint,
        submitted_at bigint,
        updated_at bigint,
        trigger_type varchar(16) not null,
        status_value varchar(24) not null,
        submitter varchar(32),
        actions varchar(64) not null,
        callback_token varchar(64) not null,
        catalog_id varchar(64) not null,
        id varchar(64) not null,
        retry_of varchar(64),
        k8s_namespace varchar(128),
        spark_app_id varchar(128),
        k8s_resource varchar(256),
        error_message varchar(1000),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_optimization_job_item (
        created_at bigint,
        finished_at bigint,
        reported_at bigint,
        started_at bigint,
        updated_at bigint,
        status_value varchar(16) not null,
        actions varchar(64) not null,
        id varchar(64) not null,
        job_id varchar(64) not null,
        table_id varchar(64) not null,
        error_message varchar(1000),
        table_full_name varchar(1024),
        metrics_json varchar(8192),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_partition (
        done_flag bit not null,
        total_buckets integer,
        created_at bigint,
        file_count bigint,
        file_size_in_bytes bigint,
        last_file_creation_time bigint,
        record_count bigint,
        updated_at bigint,
        id varchar(64) not null,
        spec_hash varchar(64) not null,
        table_id varchar(64) not null,
        spec_key varchar(1024) not null,
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        options_json text,
        spec_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_permission_assignment (
        created_at bigint,
        expire_time bigint,
        updated_at bigint,
        access varchar(32) not null,
        resource_type varchar(32) not null,
        catalog_id varchar(64) not null,
        id varchar(64) not null,
        locator_hash varchar(64) not null,
        principal varchar(128) not null,
        columns_json varchar(4096),
        created_by varchar(255),
        database_name varchar(255) not null,
        object_name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_principal (
        credential_rotation_required bit not null,
        entity_version integer not null,
        created_at bigint,
        updated_at bigint,
        id varchar(64) not null,
        secret_salt varchar(64),
        secret_hash varchar(128),
        client_id varchar(255),
        created_by varchar(255),
        name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        properties_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_principal_role (
        entity_version integer not null,
        federated bit not null,
        created_at bigint,
        updated_at bigint,
        id varchar(64) not null,
        created_by varchar(255),
        name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        properties_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_principal_role_grant (
        created_at bigint,
        updated_at bigint,
        id varchar(64) not null,
        principal_id varchar(64) not null,
        principal_role_id varchar(64) not null,
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_resource_grant (
        created_at bigint,
        updated_at bigint,
        resource_type varchar(32) not null,
        catalog_role_id varchar(64) not null,
        id varchar(64) not null,
        namespace_hash varchar(64) not null,
        privilege varchar(64) not null,
        namespace_json varchar(4096),
        namespace_key varchar(4096) not null,
        created_by varchar(255),
        object_name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table paimon_semantic_view (
        created_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        database_id varchar(64) not null,
        id varchar(64) not null,
        name varchar(512) not null,
        created_by varchar(255),
        format_name varchar(255) not null,
        owner varchar(255),
        updated_by varchar(255),
        content_doc mediumtext not null,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_snapshot (
        version_number integer,
        changelog_record_count bigint,
        delta_record_count bigint,
        file_count bigint,
        file_size_in_bytes bigint,
        last_file_creation_time bigint,
        parent_snapshot_id bigint,
        record_count bigint,
        schema_id bigint not null,
        sequence_number bigint,
        snapshot_id bigint not null,
        time_millis bigint,
        total_record_count bigint,
        watermark_value bigint,
        commit_kind varchar(32),
        id varchar(64) not null,
        table_id varchar(64) not null,
        uuid_value varchar(64),
        base_manifest_list varchar(1024),
        changelog_manifest_list varchar(1024),
        delta_manifest_list varchar(1024),
        index_manifest varchar(1024),
        commit_identifier varchar(255),
        commit_user varchar(255),
        log_offsets_json text,
        summary_json text,
        statistics_doc mediumtext,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_table (
        external_flag bit not null,
        format_version integer,
        created_at bigint,
        latest_snapshot_id bigint,
        schema_id bigint not null,
        updated_at bigint,
        catalog_id varchar(64) not null,
        database_id varchar(64) not null,
        id varchar(64) not null,
        latest_snapshot_uuid varchar(64),
        table_type varchar(64),
        table_uuid varchar(64),
        name varchar(512) not null,
        metadata_location varchar(1024),
        path_value varchar(1024),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        metadata_doc mediumtext,
        schema_doc mediumtext,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_table_optimization (
        compaction_enabled_flag bit not null,
        enabled_flag bit not null,
        orphan_remove_enabled_flag bit not null,
        schedule_interval_seconds integer not null,
        snapshot_expire_enabled_flag bit not null,
        created_at bigint,
        next_run_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        id varchar(64) not null,
        last_job_id varchar(64),
        table_id varchar(64) not null,
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        compaction_config_json text,
        orphan_remove_config_json text,
        snapshot_expire_config_json text,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_table_schema (
        created_at bigint not null,
        schema_id bigint not null,
        id varchar(64) not null,
        table_id varchar(64) not null,
        schema_doc mediumtext,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_tag (
        snapshot_id bigint not null,
        tag_create_time bigint,
        id varchar(64) not null,
        table_id varchar(64) not null,
        tag_time_retained varchar(64),
        tag_name varchar(512) not null,
        primary key (id)
    ) engine=InnoDB;

    create table paimon_view (
        created_at bigint,
        updated_at bigint,
        catalog_id varchar(64) not null,
        database_id varchar(64) not null,
        id varchar(64) not null,
        name varchar(512) not null,
        metadata_location varchar(1024),
        created_by varchar(255),
        owner varchar(255),
        updated_by varchar(255),
        view_schema_doc mediumtext,
        primary key (id)
    ) engine=InnoDB;

    create index ix_branch_table 
       on paimon_branch (table_id);

    alter table paimon_branch 
       add constraint uk_branch unique (table_id, name);

    alter table paimon_catalog 
       add constraint uk_catalog_prefix unique (prefix_value);

    alter table paimon_catalog_role 
       add constraint uk_catalog_role_name unique (catalog_id, name);

    create index ix_catalog_role_grant_role 
       on paimon_catalog_role_grant (catalog_role_id);

    alter table paimon_catalog_role_grant 
       add constraint uk_catalog_role_grant unique (principal_role_id, catalog_role_id);

    create index ix_consumer_table 
       on paimon_consumer (table_id);

    alter table paimon_consumer 
       add constraint uk_consumer unique (table_id, consumer_id);

    create index ix_database_catalog 
       on paimon_database (catalog_id);

    alter table paimon_database 
       add constraint uk_database unique (catalog_id, name);

    create index ix_function_database 
       on paimon_function (database_id);

    alter table paimon_function 
       add constraint uk_function unique (catalog_id, database_id, name);

    create index ix_optimization_job_catalog 
       on paimon_optimization_job (catalog_id);

    create index ix_optimization_job_status 
       on paimon_optimization_job (status_value);

    create index ix_optimization_item_job 
       on paimon_optimization_job_item (job_id);

    create index ix_optimization_item_table 
       on paimon_optimization_job_item (table_id);

    create index ix_partition_table 
       on paimon_partition (table_id);

    alter table paimon_partition 
       add constraint uk_partition unique (table_id, spec_hash);

    create index ix_permission_assignment_catalog 
       on paimon_permission_assignment (catalog_id);

    create index ix_permission_assignment_principal 
       on paimon_permission_assignment (principal);

    alter table paimon_permission_assignment 
       add constraint uk_permission_assignment unique (catalog_id, resource_type, locator_hash, access, principal);

    alter table paimon_principal 
       add constraint uk_principal_name unique (name);

    alter table paimon_principal 
       add constraint uk_principal_client_id unique (client_id);

    alter table paimon_principal_role 
       add constraint uk_principal_role_name unique (name);

    create index ix_principal_role_grant_role 
       on paimon_principal_role_grant (principal_role_id);

    alter table paimon_principal_role_grant 
       add constraint uk_principal_role_grant unique (principal_id, principal_role_id);

    create index ix_resource_grant_role 
       on paimon_resource_grant (catalog_role_id);

    alter table paimon_resource_grant 
       add constraint uk_resource_grant unique (catalog_role_id, resource_type, namespace_hash, object_name, privilege);

    create index ix_semantic_view_database 
       on paimon_semantic_view (database_id);

    alter table paimon_semantic_view 
       add constraint uk_semantic_view unique (catalog_id, database_id, name);

    create index ix_snapshot_table 
       on paimon_snapshot (table_id);

    alter table paimon_snapshot 
       add constraint uk_snapshot unique (table_id, snapshot_id);

    create index ix_table_catalog 
       on paimon_table (catalog_id);

    create index ix_table_database 
       on paimon_table (database_id);

    alter table paimon_table 
       add constraint uk_table unique (catalog_id, database_id, name);

    create index ix_table_optimization_catalog 
       on paimon_table_optimization (catalog_id);

    create index ix_table_optimization_due 
       on paimon_table_optimization (enabled_flag, next_run_at);

    alter table paimon_table_optimization 
       add constraint uk_table_optimization unique (table_id);

    create index ix_table_schema_table 
       on paimon_table_schema (table_id);

    alter table paimon_table_schema 
       add constraint uk_table_schema unique (table_id, schema_id);

    create index ix_tag_table 
       on paimon_tag (table_id);

    alter table paimon_tag 
       add constraint uk_tag unique (table_id, tag_name);

    create index ix_view_database 
       on paimon_view (database_id);

    alter table paimon_view 
       add constraint uk_view unique (catalog_id, database_id, name);
