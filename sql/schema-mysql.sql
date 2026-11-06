-- LakeHouse Catalog Server 元数据表结构（MySQL 8.0）
--
-- 本文件由 scripts/gen-mysql-ddl.sh 从 JPA 实体元数据生成，请勿手工修改：
-- 手工改动会在下次生成时丢失，需要改结构请改实体后重新生成。
--
-- 应用方式（库不存在时会一并创建，库名可用 DB_NAME=... 重新生成来改）：
--   mysql -h 127.0.0.1 -u root -p < sql/schema-mysql.sql
--
-- 这份文件是「给人手工重建空库」用的快照。应用**正常启动不需要执行它**：
-- 库结构由 Flyway 在启动时应用 jar 里的 db/migration/mysql/V1__baseline.sql，
-- 那是从本文件纯派生出来的同一份结构（scripts/gen-flyway-baseline.py）。
--
-- 几点说明：
--   - 库、表都必须使用 utf8mb4，否则中文标识符与 JSON 文本会乱码；
--   - 本文件用于初始化空库，重复执行会因表已存在而失败；要重建请先 drop database；
--   - 应用启动时的 ddl-auto 是 validate：迁移后的库与实体不一致时会直接启动失败，
--     而不是悄悄改表。升级代码时**不要改本文件对应的 V1**（checksum 会变，
--     已应用过 V1 的库将拒绝启动），要新增 V2__xxx.sql 增量；
--   - 唯一约束里的 locator_hash / spec_hash 是定长 SHA-256 摘要：
--     MySQL 的索引键上限是 3072 字节（utf8mb4 下 768 字符），
--     而命名空间路径与分区 spec 的长度没有上界，直接用原文建唯一索引会被
--     ERROR 1071 拒绝。原文字段仍然保留，供等值查询使用；
--   - 表与列的注释来自实体的 @Table(comment = ...) / @Column(comment = ...)，
--     要改注释请改实体：改这个文件会在下次生成时丢失。
--
CREATE DATABASE IF NOT EXISTS `lakehouse_catalog`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `lakehouse_catalog`;

    create table lakehouse_audit_event (
        id bigint not null auto_increment comment '自增主键，同时是增量拉取的游标（顺序即写入顺序）',
        occurred_at bigint not null comment '事件发生时刻（epoch 毫秒）',
        category varchar(32) not null comment '事件类别，由 type 决定（冗余列，供过滤走索引）',
        target_type varchar(32) not null comment '事件目标类型，如 CATALOG / TABLE / PRINCIPAL_ROLE',
        event_id varchar(64) not null comment '事件唯一标识（UUID），供外部引用，不参与分页',
        request_id varchar(64) comment '触发该事件的 HTTP 请求 id，便于与访问日志对齐',
        type varchar(64) not null comment '事件类型 wire 值，如 table.committed',
        target varchar(1024) not null comment '事件目标名称',
        attributes_json text comment '事件附加属性（JSON 对象）',
        catalog varchar(255) not null comment '所属 catalog；空串表示与 catalog 无关的全局事件',
        principal varchar(255) not null comment '触发该事件的主体名',
        primary key (id)
    ) comment='领域事件审计流水：一行一条，只增不改' engine=InnoDB;

    create table lakehouse_branch (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        snapshot_id bigint comment '分支当前指向的快照 id',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '所属表 id',
        name varchar(512) not null comment '分支名，同表内唯一',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        options_json text comment '分支选项（JSON 对象）',
        primary key (id)
    ) comment='表分支：指向某个快照的命名历史线' engine=InnoDB;

    create table lakehouse_catalog (
        entity_version integer not null comment '乐观并发版本号，对应管理 API 的 entityVersion',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_format varchar(32) default 'PAIMON' not null comment 'REST 协议格式 PAIMON / ICEBERG，创建后不可变',
        catalog_type varchar(32) comment 'INTERNAL：元数据由本服务托管；EXTERNAL：外部同步、只读',
        storage_type varchar(32) comment '存储实现类型（storage_config_json 的投影，读取以真相源为准）',
        id varchar(64) not null comment '主键',
        warehouse varchar(1024) comment '默认仓库根路径',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        prefix_value varchar(255) not null comment '对外 prefix，客户端后续所有 REST 路径都以它开头（全局唯一）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        allowed_locations_json text comment '允许使用的存储位置白名单（storage_config_json 的投影）',
        defaults_json text comment '下发给客户端的默认配置（合并顺序的第一层，可被客户端覆盖）',
        overrides_json text comment '强制覆盖的配置（合并顺序的最后一层，优先级最高）',
        properties_json text comment '管理 API 视角下的 catalog 通用属性',
        storage_config_json text comment '存储配置完整形态，存储配置的唯一真相源',
        primary key (id)
    ) comment='catalog 实例，REST 路径中的 {prefix} 即其对外标识' engine=InnoDB;

    create table lakehouse_catalog_execution_cluster (
        enabled bit not null comment '是否启用；停用即该 catalog 不跑优化，配置保留',
        entity_version integer not null comment '乐观并发版本号，对应管理 API 的 entityVersion',
        job_timeout_seconds integer not null comment '批次超时秒数',
        max_concurrent_jobs integer not null comment '该 catalog 同时运行几个批次',
        max_scan_tables_per_job integer default 200 not null comment '敏感识别单批最多扫描几张表',
        max_tables_per_job integer not null comment '单批最多几张表',
        scan_job_timeout_seconds integer default 3600 not null comment '敏感识别批次超时秒数',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        probe_status varchar(8) comment '最近一次探活 PASS / FAIL；空 = 从未探过',
        probed_at bigint comment '最近一次探活时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        cluster_type varchar(16) not null comment '集群类型 SELF_HOSTED / EKS / AKS，决定用哪种认证',
        image_pull_policy varchar(16) not null comment '镜像拉取策略 IfNotPresent / Always / Never',
        probe_reason varchar(32) comment '探活失败的短因码',
        namespace varchar(63) not null comment '提交 Job 的命名空间',
        service_account varchar(63) comment 'Pod 的 serviceAccountName；空 = 命名空间默认',
        catalog_id varchar(64) not null comment '所属 catalog 的内部 id，一行一 catalog（唯一约束）',
        config_hash varchar(64) not null comment '连接与运行字段的 SHA-256，用于判定「改完没验证」',
        id varchar(64) not null comment '主键',
        probe_config_hash varchar(64) comment '探活时的 configHash',
        context_name varchar(128) comment 'kubeconfig 里选中的 context（仅 SELF_HOSTED）',
        probe_message varchar(512) comment '探活结论（一句话，不含原始响应体）',
        api_server varchar(255) not null comment 'apiserver 地址（SELF_HOSTED 时是 kubeconfig 的投影）',
        auth_config_sealed text comment 'EKS / AKS 认证配置的 JSON（CredentialCipher 密封）',
        ca_cert_pem text comment '集群 CA 的 PEM 文本；空 = 用 JVM 信任库',
        callback_base_url varchar(255) not null comment '任务回调服务端的基址（集群里的 Pod 能访问到的地址）',
        catalog_token_sealed text comment '下发给任务的 catalog 数据面令牌（CredentialCipher 密封）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        image varchar(255) not null comment '优化任务镜像（含 Spark 运行时）',
        image_pull_secrets varchar(255) comment 'imagePullSecrets，逗号分隔的 Secret 名',
        kubeconfig_sealed text comment 'kubeconfig 原文（CredentialCipher 密封），仅 SELF_HOSTED',
        optimization_spark_conf text comment '表优化 Spark 参数（逐行 k=v，前缀白名单过滤）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        scan_spark_conf text comment '敏感识别 Spark 参数（逐行 k=v，前缀白名单过滤）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='catalog 的执行集群配置（优化任务提交到哪里、用什么身份），一行一 catalog' engine=InnoDB;

    create table lakehouse_column_mask (
        allow_null_on_required bit not null comment '置空 mask 落在非空列时的显式确认（默认 false = 拒绝）',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        policy_id bigint not null comment '所属表级策略的 id（DataPolicyEntity 的自增主键）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        column_name varchar(512) not null comment '被脱敏的列名；与 policy_id 组成唯一键（一列一个 mask）',
        mask_sql varchar(2048) comment 'mask 的 SQL 渲染（展示用）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        mask_json text comment 'mask 定义（标准集 fn/args 或白名单表达式 expr；类型规则见设计稿 §5.3.3）',
        primary key (id)
    ) comment='列脱敏定义（一列一个 mask，挂在表级策略下）' engine=InnoDB;

    create table lakehouse_column_tag (
        confidence float(53) comment '置信度 0-1；AUTO / INHERITED 才有',
        enabled_flag bit not null comment '是否参与策略展开；false = 保留记录但不生效',
        entity_version integer not null comment '乐观并发版本号，同时作为 etag',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        finding_id bigint comment '溯源到哪条 finding',
        id bigint not null auto_increment comment '自增主键',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        source varchar(16) not null comment '来源：AUTO（自动应用）/ MANUAL（人工）/ INHERITED（血缘继承）',
        catalog_name varchar(64) not null comment '绑定的 catalog 名；列表页按它过滤',
        column_ref_hash varchar(64) not null comment 'catalog.db.table.column 的 SHA-256（唯一键，索引长度与值长度解耦）',
        type_code varchar(64) comment '关联的敏感类型；手工标签可空',
        tag_key varchar(128) not null comment '标签键；敏感标签形如 PII.CN_ID_CARD，手工标签自由',
        table_name varchar(512) not null comment '表名',
        inherited_from varchar(1024) comment '继承来源列全名（INHERITED 才有）',
        column_name varchar(255) not null comment '列名；空串 = 表级标签',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) not null comment '库名',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        tag_value varchar(255) comment '标签值；敏感标签存分级 L1-L4（CORE / IMPORTANT 仅人工）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='列级标签（治理标签；与快照标签 lakehouse_tag 无关）' engine=InnoDB;

    create table lakehouse_consumer (
        next_snapshot bigint comment '该消费者下一次应读取的快照 id',
        updated_at bigint comment '进度最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '所属表 id',
        consumer_id varchar(512) not null comment '消费者标识，由客户端生成的字符串',
        primary key (id)
    ) comment='流式消费进度：消费者下一次应读取的快照' engine=InnoDB;

    create table lakehouse_data_policy (
        enabled_flag bit not null comment '策略开关；false = 暂停执行但定义保留（只在控制台提供）',
        entity_version integer not null comment '乐观并发版本号；改策略时比对，下发时作为 etag',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        enforced_scope varchar(16) not null comment '强制范围声明：engine（引擎内强制）；表达谁承诺执行，不是实际强度',
        catalog_name varchar(64) not null comment '绑定的 catalog 名；列表页按它过滤',
        full_name_hash varchar(64) not null comment 'catalog.database.table 的 SHA-256，唯一键（索引长度与值长度解耦）',
        table_name varchar(512) not null comment '绑定的表名',
        row_filter_sql varchar(2048) comment '行过滤的 SQL 渲染（展示用；语义以 row_filter_json 为准）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) not null comment '绑定的库名（Iceberg 多级 namespace 按点号拼成一段）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        exempt_json text comment '豁免主体名单（JSON 字符串数组；名单内的人不走策略）',
        hidden_json text comment '整列不可见清单（JSON 字符串数组；空 = 全部列可见；P3）',
        row_filter_json text comment '行过滤表达式（白名单 AST 的 JSON；空 = 不过滤行）',
        subjects_json text comment '主体白名单（JSON 字符串数组；空 = 全部主体生效；SQL 的 FOR 子句写入）',
        primary key (id)
    ) comment='行过滤/列脱敏的表级策略（一表一行，行过滤 + 豁免名单 + 版本）' engine=InnoDB;

    create table lakehouse_database (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '所属 catalog id',
        id varchar(64) not null comment '主键',
        name varchar(512) not null comment '库名，catalog 内唯一',
        location_value varchar(1024) comment '库的根路径；为空表示沿用 catalog 的 warehouse',
        comment_text varchar(4096) comment '库注释',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        options_json text comment '库选项（JSON 对象）',
        primary key (id)
    ) comment='database（命名空间），表/视图/函数均归属于它' engine=InnoDB;

    create table lakehouse_function (
        deterministic_flag bit comment '是否确定性函数',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '所属 catalog id',
        database_id varchar(64) not null comment '所属库 id',
        id varchar(64) not null comment '主键',
        name varchar(512) not null comment '函数名，库内唯一',
        comment_text varchar(4096) comment '函数注释',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        options_json text comment '函数选项（JSON 对象）',
        definitions_doc mediumtext comment '函数定义（JSON，sql / file / lambda 三种定义并列）',
        input_params_doc mediumtext comment '入参列表（JSON 文档）',
        return_params_doc mediumtext comment '返回值列表（JSON 文档）',
        primary key (id)
    ) comment='函数定义（sql / file / lambda）' engine=InnoDB;

    create table lakehouse_group_role_mapping (
        enabled bit not null comment '是否生效；停用不删除',
        entity_version integer not null comment '乐观并发版本号，每次变更自增',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        provider_name varchar(128) not null comment '身份提供方标识，单 IdP 部署恒为 default',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        idp_group varchar(255) not null comment 'IdP 组名，精确匹配（区分大小写、不 trim）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        role_name varchar(255) not null comment '目标主体角色名',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='IdP 组到主体角色的映射（组同步的翻译表）' engine=InnoDB;

    create table lakehouse_lance_table_version (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        manifest_size bigint comment 'manifest 字节数（客户端给了才记）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        version bigint not null comment 'Lance 版本号',
        catalog_id varchar(64) not null comment '所属 catalog id（与表冗余，删表时按 catalog 清理）',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '关联的表 id',
        e_tag varchar(128) comment 'manifest 的 ETag（乐观并发用）',
        manifest_path varchar(1024) not null comment '该版本 manifest 的完整路径',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        metadata_json text comment '版本级 metadata（JSON 对象）',
        primary key (id)
    ) comment='Lance 托管版本（managed_versioning）的版本账；删行不动数据集' engine=InnoDB;

    create table lakehouse_lineage_column_edge (
        id bigint not null auto_increment comment '自增主键（列边按 ref 摘要 + 列名查，不用它）',
        last_seen bigint not null comment '最近一次观测时刻（epoch 毫秒）',
        transform_kind varchar(16) not null comment 'DIRECT / DERIVED / AGGREGATE / CONSTANT / UNRESOLVED',
        last_run_id varchar(64) comment '最近一次写它的 run，供下钻',
        source_ref_hash varchar(64) not null comment 'source_ref 的 SHA-256',
        target_ref_hash varchar(64) not null comment 'target_ref 的 SHA-256',
        expression varchar(512) comment '最近一次的规范化表达式文本，仅供展示',
        source_ref varchar(1024) not null comment '上游表全名；常量列（无源）为空串',
        target_ref varchar(1024) not null comment '下游表全名',
        source_column varchar(255) not null comment '上游列名；常量列为空串',
        target_column varchar(255) not null comment '下游列名',
        primary key (id)
    ) comment='列级血缘边：一对 (源列, 目标列) 一行；常量列用空 source 占一行' engine=InnoDB;

    create table lakehouse_lineage_run (
        columns_truncated bit not null comment '列级边是否因超上限被丢弃',
        finished_at bigint not null comment '结束时刻（epoch 毫秒），列表与清理都按它',
        id bigint not null auto_increment comment '自增主键，同时是游标的第二段（同毫秒靠它定序）',
        started_at bigint not null comment '开始时刻（epoch 毫秒）',
        engine varchar(16) not null comment '引擎：本期恒为 SPARK，枚举留扩展位',
        mode varchar(16) not null comment 'BATCH / STREAMING',
        statement_type varchar(16) not null comment 'INSERT / MERGE / CTAS',
        write_mode varchar(16) not null comment 'APPEND / OVERWRITE / UPSERT / CREATE',
        engine_version varchar(32) not null comment '引擎自报版本',
        run_id varchar(64) not null comment '客户端生成的 UUID，幂等键',
        sql_hash varchar(64) comment 'SQL 文本的 SHA-256；没有 SQL 文本时为空',
        attributes_json text comment '自由属性（JSON 对象）',
        catalog varchar(255) not null comment '主 catalog（第一个目标表所在的那个），供按目录过滤',
        principal varchar(255) not null comment '上报者，由服务端从令牌解析（不采信报文）',
        sql_text text comment 'SQL 文本，截断 8KB',
        target_refs text comment '目标表列表（竖线包夹，供 ?ref= 过滤）',
        primary key (id)
    ) comment='血缘执行记录：一行一次执行，只增不改（边表才是状态）' engine=InnoDB;

    create table lakehouse_lineage_table_edge (
        first_seen bigint not null comment '首次观测时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键（不做外键引用，边表按 ref 的摘要查）',
        last_seen bigint not null comment '最近一次观测时刻，图的「新鲜度」靠它',
        run_count bigint not null comment '累计被观测到的次数',
        edge_kind varchar(16) not null comment 'TABLE / EXTERNAL',
        engine varchar(16) not null comment '上报引擎；进唯一键，将来加引擎不改表',
        write_mode varchar(16) not null comment '最近一次的写模式',
        last_run_id varchar(64) comment '最近一次写它的 run，供下钻',
        source_ref_hash varchar(64) not null comment 'source_ref 的 SHA-256，唯一键的一半',
        target_ref_hash varchar(64) not null comment 'target_ref 的 SHA-256',
        source_ref varchar(1024) not null comment '源表全名（可含 ~ 前缀的外部节点）',
        target_ref varchar(1024) not null comment '目标表全名',
        primary key (id)
    ) comment='表级血缘边：一对 (源, 目标) 一行，重复执行只累加计数' engine=InnoDB;

    create table lakehouse_optimization_job (
        claim_attempt integer not null comment '提交代数（fencing）：每次重新抢占 +1',
        table_count integer not null comment '批次覆盖的表数',
        claim_expires_at bigint comment '提交租约到期时刻（epoch 毫秒）；到期后可被别的实例回收',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        deadline_at bigint comment '超时时刻，超过仍未终态则判失败并清理资源',
        finished_at bigint comment '进入终态的时刻（epoch 毫秒）',
        started_at bigint comment '开始执行时刻（epoch 毫秒）',
        submitted_at bigint comment '提交时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        trigger_type varchar(16) not null comment '触发方式：SCHEDULED（调度出批）/ MANUAL（控制台立即优化）',
        status_value varchar(24) not null comment '批次状态：PENDING / SUBMITTING / RUNNING / 终态',
        submitter varchar(32) comment '提交方式标识（k8s / log）',
        actions varchar(64) not null comment '批次覆盖的动作集合，逗号分隔（compact,expire,orphan）',
        callback_token varchar(64) not null comment '批次级回调凭据，随批次终态失效',
        catalog_id varchar(64) not null comment '所属 catalog id',
        id varchar(64) not null comment '主键',
        retry_of varchar(64) comment '重试来源批次 id（只重跑失败表时指向原批次）',
        claim_owner varchar(128) comment '当前提交租约持有者（实例标识 = hostname/pid）；只在 SUBMITTING 期间有值',
        k8s_namespace varchar(128) comment 'k8s 命名空间',
        spark_app_id varchar(128) comment 'Spark 应用 id',
        k8s_resource varchar(256) comment 'k8s 侧资源名（Job 或 SparkApplication），用于跟踪与取消',
        error_message varchar(1000) comment '失败原因',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='存储优化批次：一次 Spark 任务覆盖的一组表' engine=InnoDB;

    create table lakehouse_optimization_job_item (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        finished_at bigint comment '执行结束时刻（epoch 毫秒）',
        reported_at bigint comment '优化任务上报结果的时刻（epoch 毫秒）',
        started_at bigint comment '开始执行时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        status_value varchar(16) not null comment '明细状态：SUCCESS / SKIPPED / FAILED 等',
        actions varchar(64) not null comment '这张表实际执行的动作，逗号分隔',
        id varchar(64) not null comment '主键',
        job_id varchar(64) not null comment '所属批次 id',
        table_id varchar(64) not null comment '表 id',
        error_message varchar(1000) comment '失败原因',
        table_full_name varchar(1024) comment 'catalog.database.table，冗余存储以便删表后历史仍可读',
        metrics_json varchar(8192) comment '执行前后指标（JSON，形状随动作变化）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='优化批次的单表明细与执行指标' engine=InnoDB;

    create table lakehouse_partition (
        done_flag bit not null comment '分区是否已提交完成',
        total_buckets integer comment '桶总数',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        file_count bigint comment '分区内文件数',
        file_size_in_bytes bigint comment '分区内文件总字节数',
        last_file_creation_time bigint comment '分区内最近一次文件创建时刻（epoch 毫秒）',
        record_count bigint comment '分区内记录数',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        spec_hash varchar(64) not null comment 'spec_key 的 SHA-256 摘要，唯一约束落在它上面',
        table_id varchar(64) not null comment '所属表 id',
        spec_key varchar(1024) not null comment '规范化后的分区 spec 文本，用于按分区等值定位',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        options_json text comment '分区选项（JSON 对象，如自定义路径）',
        spec_json text comment '分区字段映射的完整 JSON',
        primary key (id)
    ) comment='表分区及其统计信息' engine=InnoDB;

    create table lakehouse_permission_assignment (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        expire_time bigint comment '过期时刻（epoch 毫秒）；为空表示永不过期',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        access varchar(32) not null comment '官方 access 词表取值：SELECT / UPDATE / ALTER / ...',
        resource_type varchar(32) not null comment '官方资源类型：CATALOG / CATALOG_ALL / DATABASE / ...',
        catalog_id varchar(64) not null comment '所属 catalog id，赋权的作用域',
        id varchar(64) not null comment '主键',
        locator_hash varchar(64) not null comment 'database_name 与 object_name 拼接后的 SHA-256 摘要，唯一约束落在它上面',
        principal varchar(128) not null comment '收权方 principal 原文（user:alice / role:sales_writer）',
        columns_json varchar(4096) comment 'COLUMN 赋权的列范围 JSON；非 COLUMN 赋权为空',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) not null comment 'database 定位符；catalog 级为空串',
        object_name varchar(255) not null comment '对象名（表/视图/函数）；catalog 与 database 级为空串',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='唯一授权表：principal 在某个资源上的某项 access' engine=InnoDB;

    create table lakehouse_principal (
        credential_rotation_required bit not null comment '是否要求下次使用时轮换凭据',
        entity_version integer not null comment '乐观并发版本号，每次变更自增',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        last_login_at bigint comment '最后一次成功 SSO 认证时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        secret_salt varchar(64) comment '密钥盐，与 secret_hash 配套',
        secret_hash varchar(128) comment '密钥摘要 SHA-256(salt || secret)，不存明文',
        external_id varchar(512) comment '外部身份标识 issuer + "|" + sub，仅 OIDC 主体有值',
        client_id varchar(255) comment 'OAuth2 client id，全局唯一',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        display_name varchar(255) comment '显示名（email / preferred_username），仅展示用，不参与授权',
        email varchar(255) comment '邮箱，OIDC 主体来自令牌 email claim，本地主体由创建请求提供',
        name varchar(255) not null comment '主体名，全局唯一',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        properties_json text comment '主体属性（JSON 对象）',
        source varchar(16) default 'LOCAL' not null comment '主体来源：LOCAL（管理面创建）/ OIDC（首次 SSO 登录自动创建）',
        primary key (id)
    ) comment='管理主体（访问服务的身份）' engine=InnoDB;

    create table lakehouse_principal_role (
        entity_version integer not null comment '乐观并发版本号，每次变更自增',
        federated bit not null comment '是否来自外部身份提供方（本服务只作标记保存与回显）',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        name varchar(255) not null comment '角色名，全局唯一',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        properties_json text comment '角色属性（JSON 对象）',
        primary key (id)
    ) comment='主体角色：纯分组，自身不携带权限' engine=InnoDB;

    create table lakehouse_principal_role_grant (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        principal_id varchar(64) not null comment '主体 id',
        principal_role_id varchar(64) not null comment '主体角色 id',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        granted_by varchar(16) default 'MANUAL' not null comment '授予来源：MANUAL（管理面手工）/ IDP（登录时组同步）',
        primary key (id)
    ) comment='主体与主体角色的成员关系（多对多）' engine=InnoDB;

    create table lakehouse_scheduler_lease (
        acquired_at bigint comment '本轮租约开始时刻（epoch 毫秒）',
        expires_at bigint comment '租约到期时刻（epoch 毫秒）；到期即可被他人抢走',
        fencing_token bigint not null comment '代数：每次易主 +1，用于识别过期持有者',
        updated_at bigint comment '最后续约时刻（epoch 毫秒）',
        name varchar(64) not null comment '通道名',
        lease_owner varchar(128) comment '当前持有者（实例标识 = hostname/pid）',
        primary key (name)
    ) comment='调度通道租约（多副本部署下的单写者）' engine=InnoDB;

    create table lakehouse_semantic_view (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '所属 catalog id',
        database_id varchar(64) not null comment '所属库 id',
        id varchar(64) not null comment '主键',
        name varchar(512) not null comment '语义视图名，库内唯一，允许含点号',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        format_name varchar(255) not null comment '模型文档格式，如 databricks-yaml / snowflake-yaml / ossie-yaml',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        content_doc mediumtext not null comment '语义视图定义原文，单篇上限 1 MiB UTF-8 字节',
        primary key (id)
    ) comment='语义视图定义（整篇保存，不做解析）' engine=InnoDB;

    create table lakehouse_sensitive_type (
        auto_apply bit not null comment '是否允许自动应用（默认 false；仅强格式类型应开，见设计稿 §5.3）',
        builtin bit not null comment '内置类型：随启动 seed，不可删只可停用（删除返 409）',
        enabled_flag bit not null comment '是否参与识别；停用后不再命中，但历史标签保留',
        min_confidence float(53) not null comment '自动应用阈值（默认 0.85；与 auto_apply 同时满足才自动落标）',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        level varchar(8) not null comment '默认分级 L1-L4；CORE / IMPORTANT 只人工打，不进类型表',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        category varchar(32) not null comment '类别：PII / PCI / PHI / SECRET / FINANCIAL',
        type_code varchar(64) not null comment '类型编码（稳定标识，如 CN_ID_CARD）；标签键形如 PII.CN_ID_CARD',
        type_name varchar(128) not null comment '展示名（如 中国大陆居民身份证号）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        detectors_json text comment '五层探测器配置（JSON；缺一层 = 这层没开）',
        primary key (id)
    ) comment='敏感类型与探测器配置（内置类型不可删、只可停用）' engine=InnoDB;

    create table lakehouse_sensitivity_finding (
        confidence float(53) not null comment '置信度 0-1',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        decided_at bigint comment '审核时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        sampling_mode varchar(16) comment '采样方式：TABLESAMPLE / HEAD（降级）',
        state varchar(16) not null comment '人审状态：PENDING / AUTO_APPLIED / CONFIRMED / REJECTED',
        catalog_name varchar(64) not null comment 'catalog 名',
        column_ref_hash varchar(64) not null comment 'catalog.db.table.column 的 SHA-256（唯一键，索引长度与值长度解耦）',
        scan_id varchar(64) not null comment '所属扫描的 id',
        type_code varchar(64) not null comment '命中的敏感类型',
        table_name varchar(512) not null comment '表名',
        column_name varchar(255) not null comment '列名；空串 = 表级聚合结论',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) not null comment '库名',
        decided_by varchar(255) comment '审核人主体名',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        masked_samples_json text comment '打码后的样本（至多 5 个，永不写原值）',
        matched_detectors_json text comment '各探测器得分与命中率（{"name":0.9,"value":0.97}）',
        primary key (id)
    ) comment='敏感数据扫描命中（每轮扫描的历史，与 lakehouse_column_tag 分工）' engine=InnoDB;

    create table lakehouse_sensitivity_scan (
        claim_attempt integer comment 'B 路线：提交代数（fencing）',
        columns_scanned integer not null comment '扫过的列数',
        findings_created integer not null comment '本轮新增的 finding 数',
        sample_size integer not null comment '采样行上限（默认 2000，硬上限 10000）',
        tables_failed integer not null comment '读失败的表数；与 tables_scanned 分开，否则「扫了 100 张其中 3 张失败」无法表达',
        tables_scanned integer not null comment '扫过的表数（含读失败的）',
        claim_expires_at bigint comment 'B 路线：提交租约到期（epoch 毫秒）；到期后可被别的实例回收',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        finished_at bigint comment '结束时刻（epoch 毫秒）',
        started_at bigint comment '开始时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        execution varchar(16) not null comment '执行位置生效值：IN_PROCESS（A）/ SPARK（B）；落生效值不是请求原值',
        mode varchar(16) not null comment '扫描模式：METADATA_ONLY（只 D1/D2）/ SAMPLED（加 D3/D4/D5）',
        scope varchar(16) not null comment '范围层级：CATALOG / DATABASE / TABLE',
        status_value varchar(16) not null comment '状态：PENDING / SUBMITTING（仅 B）/ RUNNING / SUCCEEDED / FAILED / CANCELLED',
        trigger_type varchar(16) not null comment '触发方式：MANUAL / LIFECYCLE（P2）/ SCHEDULE（P4）',
        callback_token_hash varchar(64) comment 'B 路线：回调令牌的 SHA-256（只存哈希，令牌本身只在创建响应里出现一次）',
        catalog_name varchar(64) not null comment '扫描的 catalog 名',
        id varchar(64) not null comment '主键（UUID）',
        schedule_name varchar(128) comment '调度项名（仅 SCHEDULE 有值，不建外键）',
        table_name varchar(512) comment '表名；CATALOG / DATABASE 级扫描为空（是否为空不代表层级，看 scope）',
        error_message varchar(2048) comment '失败原因（截断）；A 与 B 的形状不同',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        database_name varchar(255) comment '库名；CATALOG 级扫描为空（是否为空不代表层级，看 scope）',
        execution_namespace varchar(255) comment 'B 路线：提交后的 Job 命名空间（A 路线为空）',
        execution_resource varchar(255) comment 'B 路线：提交后的 Job 资源名（A 路线为空）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        triggered_by varchar(255) comment '发起扫描的主体名',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        primary key (id)
    ) comment='敏感数据扫描：一次扫描的范围、模式、生效执行位置与计数' engine=InnoDB;

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

    create table lakehouse_snapshot (
        version_number integer comment '快照文件格式版本，不是自增序号',
        changelog_record_count bigint comment '本次提交的 changelog 记录数',
        delete_file_count bigint comment 'Iceberg 的 total-delete-files；Paimon 快照与缺失键为空',
        delta_record_count bigint comment '本次提交的增量记录数',
        file_count bigint comment '文件数',
        file_size_in_bytes bigint comment '文件总字节数',
        last_file_creation_time bigint comment '最近一次文件创建时刻（epoch 毫秒）',
        parent_snapshot_id bigint comment 'Iceberg 的 parent-snapshot-id；Paimon 快照为空',
        record_count bigint comment '记录数',
        schema_id bigint not null comment '该快照对应的 schema 版本号',
        sequence_number bigint comment 'Iceberg v2 的 sequence-number，记录元数据提交次序（v1 恒为 0）',
        snapshot_id bigint not null comment '快照 id，表内唯一',
        time_millis bigint comment '快照生成时刻（epoch 毫秒）',
        total_record_count bigint comment '全量记录数',
        watermark_value bigint comment '水位值（流式写入）',
        commit_kind varchar(32) comment '提交类型：APPEND / COMPACT / OVERWRITE / ANALYZE',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '所属表 id',
        uuid_value varchar(64) comment '快照 uuid',
        base_manifest_list varchar(1024) comment '基础清单文件路径',
        changelog_manifest_list varchar(1024) comment '变更日志清单文件路径',
        delta_manifest_list varchar(1024) comment '增量清单文件路径',
        index_manifest varchar(1024) comment '索引清单文件路径',
        commit_identifier varchar(255) comment '提交标识，用于幂等去重',
        commit_user varchar(255) comment '提交者',
        log_offsets_json text comment '各分区的日志偏移（JSON 对象）',
        summary_json text comment 'Iceberg snapshot summary 原文 JSON',
        statistics_doc mediumtext comment '快照统计信息（JSON 文档）',
        primary key (id)
    ) comment='表快照：Paimon 与 Iceberg 共用同一张表' engine=InnoDB;

    create table lakehouse_table (
        external_flag bit not null comment '是否为 register 注册进来的外部表',
        format_version integer comment 'Iceberg format-version（1/2/3）；Paimon 表为空',
        managed_versioning bit not null comment 'Lance：版本是否由服务端记账（managed_versioning）',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        latest_snapshot_id bigint comment '最新快照 id；未提交过快照时为空',
        schema_id bigint not null comment '当前 schema 版本号，从 0 递增',
        table_version bigint comment 'Lance：最近一次读到的版本号（纯展示缓存，不参与判定）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '所属 catalog id',
        database_id varchar(64) not null comment '所属库 id',
        id varchar(64) not null comment '主键，即规格的 GetTableResponse.id',
        latest_snapshot_uuid varchar(64) comment '最新快照 uuid',
        table_type varchar(64) comment '表类型，取自 schema options 的 type，默认 PAIMON',
        table_uuid varchar(64) comment 'Iceberg table-uuid，跨重命名与改位置保持不变',
        name varchar(512) not null comment '表名，库内唯一',
        metadata_location varchar(1024) comment '当前元数据文件路径，同时是提交时乐观并发的 CAS 条件',
        path_value varchar(1024) comment '表的物理根路径',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        properties_json text comment 'Lance 表 / 通用表的属性（JSON 对象）',
        metadata_doc mediumtext comment 'Iceberg TableMetadata 原文 JSON，Iceberg 表的唯一真相源',
        schema_doc mediumtext comment '当前 schema 的 JSON 文档',
        primary key (id)
    ) comment='表实体（Paimon 表与 Iceberg 表共用）' engine=InnoDB;

    create table lakehouse_table_optimization (
        compaction_enabled_flag bit not null comment 'compaction 开关',
        enabled_flag bit not null comment '总开关；关掉后三项动作全部停止，已配置参数保留',
        orphan_remove_enabled_flag bit not null comment '孤儿文件清理开关',
        schedule_interval_seconds integer not null comment '调度间隔（秒），即这张表多久参与一次组批',
        snapshot_expire_enabled_flag bit not null comment 'snapshot expire 开关',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        next_run_at bigint comment '下次到期时刻（epoch 毫秒），兼作分布式抢占的 CAS 条件',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '冗余的 catalog 归属，组批与按 catalog 过滤都读它',
        id varchar(64) not null comment '主键',
        last_job_id varchar(64) comment '最近一次把这张表纳入的批次 id',
        table_id varchar(64) not null comment '所属表 id，一表一行',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        compaction_config_json text comment 'compaction 的参数差异（只存用户显式改过的键）',
        orphan_remove_config_json text comment '孤儿文件清理的参数差异（只存显式改过的键）',
        snapshot_expire_config_json text comment 'snapshot expire 的参数差异（只存显式改过的键）',
        primary key (id)
    ) comment='表级存储优化策略（总开关 + 三项动作的差异配置）' engine=InnoDB;

    create table lakehouse_table_schema (
        created_at bigint not null comment '该版本写入时刻（epoch 毫秒）',
        schema_id bigint not null comment 'schema 版本号',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '关联的表 id',
        schema_doc mediumtext comment '该版本 schema 的 JSON 文档',
        primary key (id)
    ) comment='表 schema 的历史版本，用于 rollback-schema' engine=InnoDB;

    create table lakehouse_tag (
        snapshot_id bigint not null comment '标签指向的快照 id',
        tag_create_time bigint comment '标签创建时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        table_id varchar(64) not null comment '所属表 id',
        tag_time_retained varchar(64) comment '保留时长，形如 1d / 12h / 30m',
        tag_name varchar(512) not null comment '标签名，同表内唯一',
        primary key (id)
    ) comment='快照标签：给快照起的不可变名称' engine=InnoDB;

    create table lakehouse_tag_mask_binding (
        enabled_flag bit not null comment '是否启用；false = 保留绑定但不参与脱敏展开',
        priority integer not null comment '多条绑定命中同列时的排序，大者优先',
        created_at bigint comment '创建时刻（epoch 毫秒）',
        id bigint not null auto_increment comment '自增主键',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_name varchar(64) not null comment '绑定作用域（catalog 级，不按表）',
        tag_key varchar(128) not null comment '匹配的标签键；敏感标签形如 PII.CN_ID_CARD',
        mask_sql varchar(2048) comment '脱敏的 SQL 渲染（展示用）',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        tag_value varchar(255) comment '匹配的标签值；空串 = 该键任意值（通配）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        mask_json text comment '脱敏定义（与 lakehouse_column_mask.mask_json 同构，校验共用一份实现）',
        primary key (id)
    ) comment='标签到脱敏动作的绑定（catalog 级规则，按标签键/值匹配）' engine=InnoDB;

    create table lakehouse_view (
        created_at bigint comment '创建时刻（epoch 毫秒）',
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        catalog_id varchar(64) not null comment '所属 catalog id',
        database_id varchar(64) not null comment '所属库 id',
        id varchar(64) not null comment '主键',
        name varchar(512) not null comment '视图名，库内唯一',
        metadata_location varchar(1024) comment 'Iceberg 视图的元数据文件路径；Paimon 视图为空',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        view_schema_doc mediumtext comment '字段列表与各方言查询语句（JSON 文档）',
        primary key (id)
    ) comment='SQL 视图（含 Iceberg 视图的元数据文件指针）' engine=InnoDB;

    create index ix_audit_event_catalog 
       on lakehouse_audit_event (catalog, id);

    create index ix_audit_event_type 
       on lakehouse_audit_event (type);

    create index ix_audit_event_category 
       on lakehouse_audit_event (category, id);

    create index ix_branch_table 
       on lakehouse_branch (table_id);

    alter table lakehouse_branch 
       add constraint uk_branch unique (table_id, name);

    alter table lakehouse_catalog 
       add constraint uk_catalog_prefix unique (prefix_value);

    alter table lakehouse_catalog_execution_cluster 
       add constraint uk_exec_cluster_catalog unique (catalog_id);

    alter table lakehouse_column_mask 
       add constraint uk_column_mask_column unique (policy_id, column_name);

    create index ix_column_tag_catalog 
       on lakehouse_column_tag (catalog_name);

    alter table lakehouse_column_tag 
       add constraint uk_column_tag unique (column_ref_hash, tag_key);

    create index ix_consumer_table 
       on lakehouse_consumer (table_id);

    alter table lakehouse_consumer 
       add constraint uk_consumer unique (table_id, consumer_id);

    create index ix_data_policy_catalog 
       on lakehouse_data_policy (catalog_name);

    alter table lakehouse_data_policy 
       add constraint uk_data_policy_table unique (full_name_hash);

    create index ix_database_catalog 
       on lakehouse_database (catalog_id);

    alter table lakehouse_database 
       add constraint uk_database unique (catalog_id, name);

    create index ix_function_database 
       on lakehouse_function (database_id);

    alter table lakehouse_function 
       add constraint uk_function unique (catalog_id, database_id, name);

    create index ix_group_role_mapping_group 
       on lakehouse_group_role_mapping (provider_name, idp_group);

    alter table lakehouse_group_role_mapping 
       add constraint uk_group_role_mapping unique (provider_name, idp_group, role_name);

    create index ix_lance_table_version_table 
       on lakehouse_lance_table_version (table_id);

    alter table lakehouse_lance_table_version 
       add constraint uk_lance_table_version unique (table_id, version);

    create index ix_lineage_column_edge_target 
       on lakehouse_lineage_column_edge (target_ref_hash);

    create index ix_lineage_column_edge_source 
       on lakehouse_lineage_column_edge (source_ref_hash);

    alter table lakehouse_lineage_column_edge 
       add constraint uk_lineage_column_edge unique (source_ref_hash, source_column, target_ref_hash, target_column);

    create index ix_lineage_run_catalog 
       on lakehouse_lineage_run (catalog, finished_at);

    create index ix_lineage_run_finished 
       on lakehouse_lineage_run (finished_at);

    alter table lakehouse_lineage_run 
       add constraint uk_lineage_run unique (run_id);

    create index ix_lineage_table_edge_target 
       on lakehouse_lineage_table_edge (target_ref_hash);

    create index ix_lineage_table_edge_source 
       on lakehouse_lineage_table_edge (source_ref_hash);

    alter table lakehouse_lineage_table_edge 
       add constraint uk_lineage_table_edge unique (source_ref_hash, target_ref_hash, engine);

    create index ix_optimization_job_catalog 
       on lakehouse_optimization_job (catalog_id);

    create index ix_optimization_job_status 
       on lakehouse_optimization_job (status_value);

    create index ix_optimization_item_job 
       on lakehouse_optimization_job_item (job_id);

    create index ix_optimization_item_table 
       on lakehouse_optimization_job_item (table_id);

    create index ix_partition_table 
       on lakehouse_partition (table_id);

    alter table lakehouse_partition 
       add constraint uk_partition unique (table_id, spec_hash);

    create index ix_permission_assignment_catalog 
       on lakehouse_permission_assignment (catalog_id);

    create index ix_permission_assignment_principal 
       on lakehouse_permission_assignment (principal);

    alter table lakehouse_permission_assignment 
       add constraint uk_permission_assignment unique (catalog_id, resource_type, locator_hash, access, principal);

    alter table lakehouse_principal 
       add constraint uk_principal_name unique (name);

    alter table lakehouse_principal 
       add constraint uk_principal_client_id unique (client_id);

    alter table lakehouse_principal_role 
       add constraint uk_principal_role_name unique (name);

    create index ix_principal_role_grant_role 
       on lakehouse_principal_role_grant (principal_role_id);

    alter table lakehouse_principal_role_grant 
       add constraint uk_principal_role_grant unique (principal_id, principal_role_id);

    create index ix_semantic_view_database 
       on lakehouse_semantic_view (database_id);

    alter table lakehouse_semantic_view 
       add constraint uk_semantic_view unique (catalog_id, database_id, name);

    alter table lakehouse_sensitive_type 
       add constraint uk_sensitive_type_code unique (type_code);

    create index ix_sensitivity_finding_scan 
       on lakehouse_sensitivity_finding (scan_id);

    alter table lakehouse_sensitivity_finding 
       add constraint uk_sensitivity_finding unique (scan_id, column_ref_hash, type_code);

    create index ix_sensitivity_scan_catalog 
       on lakehouse_sensitivity_scan (catalog_name);

    create index ix_sensitivity_scan_status 
       on lakehouse_sensitivity_scan (status_value);

    create index ix_sensitivity_schedule_catalog 
       on lakehouse_sensitivity_schedule (catalog_name);

    alter table lakehouse_sensitivity_schedule 
       add constraint uk_sensitivity_schedule unique (name);

    create index ix_sensitivity_suppression_catalog 
       on lakehouse_sensitivity_suppression (catalog_name);

    alter table lakehouse_sensitivity_suppression 
       add constraint uk_sensitivity_suppression unique (column_ref_hash, type_code);

    create index ix_snapshot_table 
       on lakehouse_snapshot (table_id);

    alter table lakehouse_snapshot 
       add constraint uk_snapshot unique (table_id, snapshot_id);

    create index ix_table_catalog 
       on lakehouse_table (catalog_id);

    create index ix_table_database 
       on lakehouse_table (database_id);

    alter table lakehouse_table 
       add constraint uk_table unique (catalog_id, database_id, name);

    create index ix_table_optimization_catalog 
       on lakehouse_table_optimization (catalog_id);

    create index ix_table_optimization_due 
       on lakehouse_table_optimization (enabled_flag, next_run_at);

    alter table lakehouse_table_optimization 
       add constraint uk_table_optimization unique (table_id);

    create index ix_table_schema_table 
       on lakehouse_table_schema (table_id);

    alter table lakehouse_table_schema 
       add constraint uk_table_schema unique (table_id, schema_id);

    create index ix_tag_table 
       on lakehouse_tag (table_id);

    alter table lakehouse_tag 
       add constraint uk_tag unique (table_id, tag_name);

    create index ix_tag_mask_binding_catalog 
       on lakehouse_tag_mask_binding (catalog_name);

    alter table lakehouse_tag_mask_binding 
       add constraint uk_tag_mask_binding unique (catalog_name, tag_key, tag_value);

    create index ix_view_database 
       on lakehouse_view (database_id);

    alter table lakehouse_view 
       add constraint uk_view unique (catalog_id, database_id, name);
