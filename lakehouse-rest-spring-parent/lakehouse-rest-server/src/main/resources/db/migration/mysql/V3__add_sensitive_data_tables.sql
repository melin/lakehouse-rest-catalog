-- V3：敏感数据识别与标签治理五张新表（P0，设计见 docs/sensitive-data-design.md §4）
--
-- 为什么是手写的增量而不是重派生 V1：V1 的 checksum 已被应用过它的库记录，内容一旦
-- 变过，那些库会以 "Migration checksum mismatch for migration version 1" 拒绝启动
-- （validate-on-migrate: true）。因此新增表只能走增量，见
-- scripts/gen-flyway-baseline.py 的 docstring「只到 V1 尚未被任何库应用为止」。
--
-- 五张新表的建表语句与 sql/schema-mysql.sql 里那份全量快照逐字一致（快照是给人重建
-- 空库用的，这份增量是给存量库升级用的）。改结构仍然只改实体，然后依次跑
-- ./scripts/gen-mysql-ddl.sh 与 scripts/gen-flyway-baseline.py；本文件是手工维护的。
--
-- **这是纯新增，没有一条 alter table 现有表。**三张表在 core 模块
-- （sensitive_type / column_tag / tag_mask_binding）、两张在 server 模块
-- （sensitivity_scan / sensitivity_finding），本迁移不关心这个分界——
-- 库结构是一个整体。
--
-- 为什么 finding 与 tag 是两张表：扫描每天重跑，finding 会重复产生；
-- 而「这列是身份证」这个结论只该有一份。合并成一张会出现「同列 30 条 pending」
-- 或「重扫把人工拒绝洗回待审」（见 lakehouse_column_tag 的表注释）。
--
-- 为什么新增 NOT NULL 列这次不需要 DEFAULT：这里建的是**空表**，
-- 不存在「存量行要填个值」的问题（那条约束只对 alter table 加列成立）。

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

create index ix_column_tag_catalog 
       on lakehouse_column_tag (catalog_name);

alter table lakehouse_column_tag 
       add constraint uk_column_tag unique (column_ref_hash, tag_key);

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

create index ix_tag_mask_binding_catalog 
       on lakehouse_tag_mask_binding (catalog_name);

alter table lakehouse_tag_mask_binding 
       add constraint uk_tag_mask_binding unique (catalog_name, tag_key, tag_value);
