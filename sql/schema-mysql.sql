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
        row_filter_json text comment '行过滤表达式（白名单 AST 的 JSON；空 = 不过滤行）',
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
        table_count integer not null comment '批次覆盖的表数',
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
        updated_at bigint comment '最后更新时刻（epoch 毫秒）',
        id varchar(64) not null comment '主键',
        secret_salt varchar(64) comment '密钥盐，与 secret_hash 配套',
        secret_hash varchar(128) comment '密钥摘要 SHA-256(salt || secret)，不存明文',
        client_id varchar(255) comment 'OAuth2 client id，全局唯一',
        created_by varchar(255) comment '创建者主体名（写入前已按列宽归一化）',
        name varchar(255) not null comment '主体名，全局唯一',
        owner varchar(255) comment '记录归属主体（展示与过滤用，不参与授权判定）',
        updated_by varchar(255) comment '最后更新者主体名（写入前已按列宽归一化）',
        properties_json text comment '主体属性（JSON 对象）',
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
        primary key (id)
    ) comment='主体与主体角色的成员关系（多对多）' engine=InnoDB;

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
        created_at bigint comment '创建时刻（epoch 毫秒）',
        latest_snapshot_id bigint comment '最新快照 id；未提交过快照时为空',
        schema_id bigint not null comment '当前 schema 版本号，从 0 递增',
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

    alter table lakehouse_column_mask 
       add constraint uk_column_mask_column unique (policy_id, column_name);

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

    create index ix_view_database 
       on lakehouse_view (database_id);

    alter table lakehouse_view 
       add constraint uk_view unique (catalog_id, database_id, name);
