# 优化任务的分布式调度（多节点部署）

> **状态：已实现（P0 + P1）。** 落地记录与对本文的偏差见 §13。
> 本文描述「多副本同时跑调度时，怎么保证同一张表、同一个批次不会被两台机器各跑一遍」，
> 以及多节点上线前必须先补的部署前提。§2 现状审计表记录的是**改造前**的代码事实，
> 保留它是为了让后来的人看清「哪些是原本就对的、哪些是补的」。

## 1. 结论

**现状是「考虑了一半」。** 出批与提交两个环节已经有条件更新（CAS）作为互斥，
这是多副本方案里最难的部分，做对了；但**跟踪收尾、提交中断回收、配额闸门、
手动入口**四处没有考虑，其中跟踪收尾在多副本下不是偶发问题，而是**必然发生**：
每台机器都会 poll 到同一批 `RUNNING` 批次，于是同一个批次被收尾两次。

一句话结论：**出批不会重复，提交不会重复，但收尾会重复、提交会卡死。**

推荐方案（§5）：不引入 ZooKeeper / Redis / 选举组件，全部落在 MySQL 上——
「调度通道租约 + 批次提交租约（fencing） + 终态收尾 CAS」。
`@Scheduled` 那句 `synchronized` / 单机锁一律不加，它们只在单进程里有意义。

## 2. 现状审计

以 `paimon-rest` 当前代码为准，逐环节看「多副本同时跑会怎样」：

| 环节 | 现有防线 | 多副本下的结果 | 判定 |
| --- | --- | --- | --- |
| 出批·逐表抢占 | `TableOptimizationRepository.claim(id, expectedNextRunAt, next)`，`UPDATE ... WHERE next_run_at = :expected`，返回行数判定 | 同一张表只会被一台机器推进游标，另一个拿到 0 行并剔除 | ✅ 已有 |
| 出批·游标初始化 | `initializeCursor`，`WHERE next_run_at IS NULL` | 幂等，两台机器都执行也只有一台改到 | ✅ 已有 |
| 出批·**配额闸门** | `countByCatalogIdAndStatusIn(catalog, active) >= maxConcurrentJobs` 后 `continue`（`OptimizationBatchService#scanAndCreateBatches`） | 读-判-写：两台同时读到 1/2 → 各出一个批次 → 实际 3 个，**突破上限** | ❌ 缺口（超额，不是重复） |
| 提交 | `OptimizationJobService#claimForSubmission` → `transition(PENDING → SUBMITTING)`，条件更新 | 同一批次只有一台能推进，另一台 0 行跳过 | ✅ 已有 |
| 提交·**崩溃回收** | 无。`findTop50ByStatus(PENDING)` 不再包含 `SUBMITTING`，`pollRunning` 只取 `RUNNING` | 机器在 `submit()` 与 `markSubmitted()` 之间被杀 → 批次**永久停在 SUBMITTING**，控制台永远显示「提交中」 | ❌ 缺口（漏执行） |
| 提交·执行侧幂等 | `Fabric8JobSubmitter#submit` 先查同名资源，存在即视为已提交；资源名 = `OptimizerJob.resourceName(jobId)` | 重复提交不会造出第二个 Job | ✅ 已有（这是能安全回收的前提） |
| 跟踪·**终态收尾** | `finishFromSubmitter` 只做了「读到的状态是终态就返回」，**没有条件更新** | 两台同时读到 `RUNNING` → 都走完 `failOpenItems` + `save` + `publishFinished` → **事件发两次**；若另一台恰好判超时（`markTimedOut` 把 `SUCCESS` 改写成 `PARTIAL_SUCCESS`），**一个成功的批次被改写成部分成功** | ❌ 缺口（重复 + 状态被覆盖） |
| 跟踪·明细上报 | `report` 拒绝改写已终态明细（幂等） | 单行更新，「最后写赢」；重复上报不会翻状态 | ⚠️ 基本可用，竞态见 §5.4 |
| 手动「立即优化」 | `TableOptimizationService` 直接 `jobService.createJob(...)`，**不经过 `claim`、也不查 ACTIVE 明细** | 与调度批次同表双跑（**单机也存在**：调度侧 `busy` 判定是读后写） | ❌ 缺口（与副本数无关，但同属「同表被发起两次」） |
| 血缘过期清理 | `LineageRetentionJob#purgeExpiredRuns`，`@Scheduled(cron)` | 每台机器同时跑同一段批量 DELETE：重复扫表、有 gap lock 死锁风险 | ❌ 缺口（幂等但会互相阻塞） |
| 部署形态 | `deploy/kubernetes/lakehouse-rest.yaml`：`replicas: 1`，warehouse 是 hostPath + `file://` | 改大副本数会各写一份仓库，**先坏的是数据不是调度** | ❌ 前提未满足 |
| 多实例基础设施 | JWT 签名密钥的多实例告警、`ddl-auto: validate`、startup/liveness/readiness 探针、preStop sleep、graceful shutdown | 基本齐备；缺「调度线程在 SIGTERM 时让出租约」 | ⚠️ 部分 |

另外一处文档与代码的偏差：`Fabric8JobSubmitter` 的类注释写着「进程在提交成功、回写之前崩掉
会让批次回到 PENDING（乐观锁失效后重新抢）」——**这个语义目前没有实现**。`claimForSubmission`
之后没有任何回退路径。本文 §5.3 补的就是它。

## 3. 为什么必须治：三类重复的实际后果

不是「多跑一次浪费点算力」这么轻：

1. **同表并发 compaction**（出批重复）：两个 Spark 任务同时改同一张 Paimon 表，
   后提交的一方要么撞上提交冲突，要么把对方刚合好的文件再合一遍；
   若其中一个批次带 `expire_snapshots`，还可能把对方正在读的快照删掉。
2. **同批次双跑**（提交重复）：会覆盖同一批明细的指标，历史里出现两条互相矛盾的结论。
   当前这条已被 `transition` 与 Job 同名幂等挡住。
3. **收尾重复**（本文重点）：事件订阅方（告警、Webhook、审计）收到两条 `job_succeeded`；
   更糟的是超时判定与成功判定打架，**把一个成功的批次写成 `PARTIAL_SUCCESS`**，
   而 `failOpenItems` 还会把仍在执行的表明细标成 FAILED —— 执行侧随后上报成功时
   会被 `report` 拒绝（明细已终态），数据上留下「失败但其实成功」的记录。

## 4. 设计原则

- **权威只有数据库**。互斥靠「一行被谁改成了自己的名字」，不靠内存标志、不靠谁先启动。
- **租约而非锁**。凡是「我拿住了」的东西都带过期时间：持有者崩了，别人要能接手。
  凡是「我接手了」的东西都带**代数（fencing token）**：上一代持有者复活也不能再写。
- **外部动作必须幂等**。回收必然带来「可能重复提交」，所以提交动作本身要幂等——
  这一点 `Fabric8JobSubmitter` 已经满足（Job 名由批次 id 唯一决定），否则不能做回收。
- **租约只降噪，CAS 才保正确**。即使租约完全失效（时钟漂移、表被清空），
  正确性仍由各处的条件更新兜住。这条决定了代码结构：租约是「提前返回」的优化，
  不是 `if` 里唯一的判断。

## 5. 方案

### 5.1 调度通道租约

新增一张表，一行一个「调度通道」，用来回答「这一拍该不该由我来跑」：

```sql
create table lakehouse_scheduler_lease (
    name          varchar(64)  not null comment '通道名：optimization.scan / optimization.dispatch / lineage.cleanup',
    lease_owner   varchar(128) comment '当前持有者（实例标识 = hostname + 进程 pid）；不叫 owner 以免与审计列的「归属主体」混淆',
    acquired_at   bigint comment '本轮租约开始时刻（epoch 毫秒）',
    expires_at    bigint comment '租约到期时刻（epoch 毫秒）；到期即可被他人抢走',
    fencing_token bigint not null default 0 comment '代数：每次易主 +1，用于识别过期持有者',
    updated_at    bigint comment '最后续约时刻（epoch 毫秒）',
    primary key (name)
) comment='调度通道租约（多副本部署下的单写者）' engine=InnoDB;
```

抢与续拆成两条语句，先抢后续，任一返回 1 就有资格跑这一拍：

```sql
-- 抢（我本来不持有，且它已过期；或它还是无主的）
update lakehouse_scheduler_lease
   set lease_owner = :me, acquired_at = :now, expires_at = :now + :ttl,
       fencing_token = fencing_token + 1, updated_at = :now
 where name = :name and (lease_owner is null or lease_owner <> :me)
   and (expires_at is null or expires_at <= :now);

-- 续（我持有且未过期）
update lakehouse_scheduler_lease
   set expires_at = :now + :ttl, updated_at = :now
 where name = :name and lease_owner = :me and expires_at > :now;
```

返回 1 = 本拍由我跑；两条都 0 行 = 别的实例持有，**整拍直接 return（`log.debug`，不报错）**。

要点：

- **拆两条而不是合成一条**：合起来写时「易主才 +1」需要在 `set` 里判断旧值，
  而 MySQL 的 `UPDATE` 赋值按从左到右生效，后一个表达式会读到前面刚写的新值——
  这种依赖顺序的写法迟早被人改错。两条语句各自只有一个条件，读起来就是它的语义。
- `fencing_token` **只在易主时 +1**，所以「我是第几代持有者」成立。本拍结束时可以再查一次，
  变了就说明中途被抢过，本拍的结果要丢弃（这条是可选的自检，正确性不依赖它）。
- **TTL 取节拍的 3 倍**：`scan` 1 分钟节拍 → TTL 3 分钟；`dispatch` 10 秒 → TTL 60 秒。
  代价是「持有者崩溃后最多空窗一个 TTL」，收益是网络抖动不会造成双持有。
- **启动时确保行存在**：`existsById` 否则 `save`，主键冲突直接吞掉（两台同时启动的幂等）。
  不写进 DDL 的初始数据——`sql/schema-mysql.sql` 是生成物，塞种子数据会被下次生成覆盖。
- **停机让出**：`@PreDestroy` 里 `update ... set expires_at = 0 where name = :name and lease_owner = :me`。
  滚动发布时新 Pod 立刻接手，不用等 TTL 走完。
  这也是 §9 里「不加租约、只让一个副本开调度」那种做法做不到的——那种做法在重启期间必然空窗。

**强杀时拿不到 `@PreDestroy`（`kill -9`、OOMKilled、节点宕机）**：这条只是加速器，不是正确性机制。
租约天然到期失效，所以最坏结果是一个 TTL 的**时延**，不是一次重复执行：

| 通道 | 没让出的代价 | 后果 |
| --- | --- | --- |
| `optimization.dispatch`（TTL 60s） | 6 拍没人提交，且拖慢 `SUBMITTING` 回收 | 批次晚 60~90 秒启动或收尾，不双跑 |
| `optimization.scan`（TTL 3min） | 3 拍不出批 | 表晚 3 分钟被优化，下一拍自然补上 |
| `lineage.cleanup`（TTL 1h） | 当天 cron 可能整轮空窗 | 幂等 DELETE + 日级节拍，次日重跑等价；嫌长就缩到 2× 预估运行时长，或在批之间续约 |

两种「没跑 `@PreDestroy`」要分开看：

- **进程真的死了**：除上面三个窗口外没有残留——在途事务被 MySQL 回滚、catalog 行锁释放、
  `next_run_at` 的 CAS 一起回滚，下一拍重出。唯一像 bug 的是 `SUBMITTING` 多停几十秒，
  回收器一到就回 `PENDING`（§5.3）。
- **进程假死**（长 GC、网络分区、被 `SIGSTOP`）：租约过期被接管，旧持有者还活着继续跑。
  `fencing_token` 的自检**只能发现、不能阻止**已经发生的副作用——这时正确性仍由
  「出批 `next_run_at` CAS + 提交 `PENDING→SUBMITTING` CAS + 终态 CAS」兜住，
  即 §4「租约只降噪」那条。`@PreDestroy` 在假死场景同样不会被调用，所以它不是任何一处的补丁。

唯一由强杀留下、且 `@PreDestroy` 也补不了的缺口：**CAS 成功之后、事件发出之前被杀**——
状态已是终态而订阅方收不到通知（丢一条，不是重一条；I3 只保证不重复）。
要补只能上 outbox（同事务写事件表 + 异步投递），不在 P0 范围。

若希望同名 Pod 重启也不等满 TTL，可加一条启动自愈：启动时若本通道 `lease_owner`
与自己的实例标识同名（`hostname` 相同即视为前任）且 `expires_at` 在未来，直接清 0。
这是可选项——`hostname` 复用会误清「另一个同名实例」的租约，只在 StatefulSet 或
`pod-name` 稳定的部署下启用。

`optimization.scan` 与 `optimization.dispatch` 分成两个通道，而不是合成一个：
扫描是分钟级、提交是十秒级，合成一个会让「扫描卡在某个慢 catalog 上」把提交也堵住。
实际部署中通常同一台机器同时拿到两个通道（先到先得），但语义上它们是独立的。

### 5.2 出批的配额闸门

目标是消除 `count` 与 `create` 之间的窗口。取 **per-catalog 行锁**，不用预算表：

```java
// CatalogRepository 新增
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from CatalogEntity c where c.id = :id")
Optional<CatalogEntity> lockById(@Param("id") String id);
```

`createBatch` 开头先 `lockById(catalogId)`，锁内再 `countByCatalogIdAndStatusIn`，
不足配额才继续。锁的粒度是**一个 catalog 的一次出批事务**（毫秒级），不是整轮扫描。

这要求把 `scanAndCreateBatches` 现在的「一个大事务包住所有 catalog」拆成
**每个 catalog 一个独立事务**（`TransactionTemplate` 或独立的 `@Transactional(REQUIRES_NEW)` 方法）。
不拆的话，两台机器会在整轮扫描上互相排队：锁持有到方法结束，而方法里还有 N 个 catalog
与一遍 `initializeCursors`。

**锁顺序纪律**（防死锁，写进代码注释）：`catalog → policy/明细`。
所有同时碰这两类行的路径（出批、手动立即优化）都按这个顺序加锁。
`claim` 的 `UPDATE` 是行锁也是按 policy 行拿，排在后面对。

如果不想引入悲观锁，还有一个更轻的取舍：**接受上限被小幅突破**（最多超出「节点数 - 1」个
批次），因为它是超额而不是重复执行。但既然要多节点部署，一台机器 20 个连接的代价很小，
建议一次做对。

拆事务的两个实现坑，写在这里省的开发时再踩：

- **Spring 的自调用不走代理**。把 `createBatch` 标成 `@Transactional(REQUIRES_NEW)` 放在
  `scanAndCreateBatches` 同一个类里是无效的——`this.createBatch(...)` 绕过了代理，
  仍在大事务里。要么把它移到独立 bean（`OptimizationBatchCreator`），要么注入
  `TransactionTemplate` 显式开事务。前者更清晰。
- **`createJob` 与 `assignJob` 必须留在同一个 catalog 事务里**。拆事务的对象是「catalog」，
  不是「语句」：批次与明细原子落库这条性质不能因为拆事务而丢。

### 5.3 提交租约与孤儿回收

批次表加三列：

```sql
alter table lakehouse_optimization_job
    add column claim_owner      varchar(128) comment '当前提交租约持有者（实例标识）',
    add column claim_expires_at bigint       comment '提交租约到期时刻（epoch 毫秒）',
    add column claim_attempt    int not null default 0 comment '提交代数（fencing）：每次重新抢占 +1';
```

状态流转改成「带代数」的三条语句：

```sql
-- 1) 抢占（替代现在的 transition(PENDING, SUBMITTING)）
update lakehouse_optimization_job
   set status_value = 'SUBMITTING', claim_owner = :me,
       claim_expires_at = :now + :leaseTtl, claim_attempt = claim_attempt + 1, updated_at = :now
 where id = :id and status_value = 'PENDING';
-- 返回 1 后回读 claim_attempt，作为本次提交的代数
-- （MySQL 没有 RETURNING，回读要在同一事务里 select；读到的必然是自己写的那一代）

-- 2) 回写成功（替代现在的 markSubmitted 无条件 save）
update lakehouse_optimization_job
   set status_value = 'RUNNING', submitter = :submitter, k8s_namespace = :ns, k8s_resource = :res,
       submitted_at = :now, started_at = :now, deadline_at = :deadline, updated_at = :now
 where id = :id and status_value = 'SUBMITTING' and claim_attempt = :attempt;
-- 0 行 = 租约已被回收（我没资格写），丢弃并记 WARN

-- 3) 回收（dispatch 节拍里，提交完之后跑）
update lakehouse_optimization_job
   set status_value = 'PENDING', claim_owner = null, claim_expires_at = null, updated_at = :now
 where id = :id and status_value = 'SUBMITTING' and claim_attempt = :attempt
   and claim_expires_at < :now;
-- attempt 在这里是第二道保险：过期后若别的实例已经抢占并续上新租约，
-- 那条的 expires_at 在未来，条件自然不成立；带上 attempt 是为了让「我回收的是我读到的那一代」
-- 这件事在 SQL 里可读，而不是靠推理
```

回收的输入是 `findTop50ByStatusAndClaimExpiresAtLessThan('SUBMITTING', now)`。

关键论证——**回收不会造成双跑**：

1. `submit()` 的资源名只由批次 id 决定（`OptimizerJob.resourceName(jobId)`），
   而且提交前会先查同名资源，存在即复用。
2. 回收窗口（30 秒）远小于 Job 的 `ttlSecondsAfterFinished`（3600 秒），
   所以被回收时那个 Job 一定还在集群里，重投必然命中「已存在」分支。
3. 即使回写真的丢了，接管者走的是同一条路：抢占 → `submit()` → 命中同名 → `markSubmitted`。

只在一种情况下会真的跑第二遍：提交成功、进程崩溃、**且**超过 1 小时没人回收
（Job 已被 TTL 清掉），这时同名查询落空会重建。所以回收器必须常开，
且 `claim-expires` 不能配得比 Job 的 TTL 还大。

回收次数要封顶：`claim_attempt >= 3` 时不再回 PENDING，直接置 `FAILED`，
错误写「提交阶段反复中断（已重试 3 次），请检查服务端与执行集群的连接」。
无限重投会把执行集群刷满失败记录，也会把 `deadline` 计算搞乱。

还有一个边界要写进注释：**已失败的 Job 会被当成「已提交」接管**。
`Fabric8JobSubmitter` 现在对同名资源一律返回 `Submitted`，于是回收后的重投会接上一个
`Failed` 的 Job，下一拍 `pollRunning` 立刻判批次失败。结果正确但用户会看到一次「无用的重试」。
修法（P2）：`submit` 时若 existing 的 `Complete`/`Failed` 条件已成立，先删再建。

### 5.4 终态收尾的 CAS

这是多副本下**必然发生**的那一处。三个入口全部改成「条件更新 + 行数判定」，
只有改到 1 行的那台机器才做**不可重复的副作用**（写批次终态、发事件）：

```sql
-- 执行侧到达终态（轮询或回调都走这里）
update lakehouse_optimization_job
   set status_value = :final, finished_at = :now, error_message = :err, updated_at = :now
 where id = :id and status_value in ('RUNNING', 'SUBMITTING');

-- 超时收尾
update lakehouse_optimization_job
   set status_value = :final, finished_at = :now, error_message = :err, updated_at = :now
 where id = :id and status_value in ('PENDING', 'SUBMITTING', 'RUNNING')
   and deadline_at is not null and deadline_at < :now;

-- 取消
update lakehouse_optimization_job
   set status_value = 'CANCELED', finished_at = :now, updated_at = :now, updated_by = :principal
 where id = :id and status_value in ('PENDING', 'SUBMITTING', 'RUNNING');
```

**顺序很重要**：`final` 由 `statusFromItems` 算（明细的终态分布是唯一依据），
而算之前要先把「没上报结果的明细」折成失败。所以完整流程是：

1. **补失败**：`update ... set status_value = 'FAILED' where job_id = :id and status_value in ('PENDING','RUNNING')`
   —— 这一步是**幂等**的，两台机器都执行没有害处，第一条把行改完，第二条 0 行；
2. 算 `final`：此时两台机器读到的是同一份明细，算出的结论一致；
3. **写批次终态**（上面三条 CAS 之一）：只有一台能改到 1 行；
4. 改到的那台发事件。

第 1 步允许双写，前提是**文案归一化**：`failOpenItems` 现在由调用点各写各的
（「任务已结束但这张表没有上报结果」/「任务在执行侧失败」/「批次超过配置的超时时间仍未结束」），
两台机器会互相覆盖成不同措辞。做法是：明细这一层只写一个固定原因
（「这张表没有上报结果」），真正的失败原因留在批次的 `error_message` 上——
那里由 CAS 胜出者写，不会打架。

这样带来的三条性质：

- **事件只发一次**：`publishFinished` 只在批次 CAS 成功之后调用，两台机器里只有一台能成功。
- **成功不会被打成部分成功**：`markTimedOut` 的 `WHERE` 里带 `deadline_at < now`；
  一台机器已经把状态改成 `SUCCESS` 之后，另一台的条件更新返回 0 行，改不动。
- **明细不会被两套结论各写一遍**：补失败是幂等的同一条语句，终态判定读的是同一份明细。

顺带修两个已有的竞态（都在 `OptimizationJobService`）：

- **`markSubmitted` 与回调赛跑**：执行侧启动后可能很快失败并回调 `report`，
  这次回调可能早于 `markSubmitted` 落库；而 `markSubmitted` 是无条件 `setStatus(RUNNING)`，
  会把回调之后的状态推回 `RUNNING`。用 §5.3 的第 2 条语句（带 `claim_attempt`）即可，
  0 行时若批次已终态就丢弃。
- **`report` 的「读终态再写」**：两个回调同时到达时都能通过终态检查。
  改成 `update ... where id = :itemId and status_value not in ('SUCCESS','FAILED','SKIPPED')`，
  0 行时按「已终结」返回 400（语义不变，竞态收口）。

### 5.5 手动入口与同表互斥

`TableOptimizationService` 的「立即优化」与 `OptimizationJobService#retry` 都是
**直接建批次**，不经过 `claim`。于是存在这样的交错：

```
扫描线程：读 ACTIVE 明细（表 T 不在里面）→ … → createJob(含 T)
手动请求：读 ACTIVE 明细（同样读不到）→ createJob(含 T)
```

两台机器各建一个批次，表 T 被两个 Spark 任务同时处理。这与副本数无关，
单机也能复现（两个线程）。

修法（与 §5.2 同一把锁，同一顺序）：

1. `createJob` 的调用方在事务内先 `lockById(catalogId)`；
2. 锁内**复查**这组 `tableId` 是否已有 `PENDING/RUNNING` 明细；
3. 有 → 抛 409，报文带已有批次 id（`ApiException.conflict("table X is already in batch Y")`），
   控制台据此把用户导到那个批次，而不是让他盯着一个「什么都没发生」的按钮。

不建议把「同表唯一活跃」做成数据库约束：MySQL 没有部分索引，要做得另建一张
`lakehouse_optimization_active(table_id PK, job_id)` 登记簿，进批 `INSERT`（主键冲突即占用），
终态 `DELETE`，再配一个对账器清崩溃残留。它判得比「扫明细表」准，但多一张需要
与状态机同步的表；在「每分钟只出几批」的量级下，锁内复查已经够。
登记簿作为 P2 备选写进 §10。

### 5.6 单实例任务

`LineageRetentionJob` 用同一个租约机制，通道名 `lineage.cleanup`，TTL 1 小时
（cron 是日级，TTL 长一点没有副作用）。它虽然是幂等的 DELETE，但两台机器同时扫同一段
范围会撞上 InnoDB 的 gap lock，出现死锁与回滚日志——这类「幂等但互相阻塞」的任务，
单写者是更省心的选择。

将来任何新增的 `@Scheduled`，都必须先在租约通道清单里登记（§10 P2 的静态校验管这个）。

### 5.7 RUNNING 批次的接管（A 提交并运行，A 挂掉）

结论：**会接管，而且 job1 的成败由执行集群决定，与 A 是否活着无关。**

三条原因：

1. 批次是数据库里的一行，k8s Job 在 catalog 自己的执行集群里——两样都不在 A 的进程里。
2. 跟踪路径没有内存状态：`pollRunning` 每拍 `findTop50ByStatus(RUNNING)` 再按行里的
   `namespace/resource` 调 `submitter.state(catalogId, ns, res)`。
3. 收尾靠 CAS（§5.4），谁读到 `Complete`/`Failed` 谁写终态，只有一台能写成。

时序：

```
t0       A 挂（kill -9 / 节点宕机）；job1 仍 RUNNING，k8s Job 照跑
t0+≤60s  B 抢到 optimization.dispatch 租约（A 的租约到期）
t0+≤70s  B 的 dispatch 拍扫到 job1 → state() → RUNNING → 什么都不做
...
tN       driver 结束 → B 读到 Complete → finishFromSubmitter(SUCCEEDED)
         → 终态由明细分布决定（SUCCESS / PARTIAL_SUCCESS）
tN'      或读到 Failed（backoffLimit=0，Pod 一失败 Job 立刻带 Failed condition）→ FAILED
```

四条路径的成败判定都不依赖 A：

| 情形 | 结果 | 依据 |
| --- | --- | --- |
| Job 正常结束 | `SUCCESS` / `PARTIAL_SUCCESS` | 明细由 driver 回调写入，回调走 Service DNS 落到存活节点 |
| Job 失败 | `FAILED` | k8s 侧 `backoffLimit=0`，REST 侧只需读 condition |
| Job 卡住不结束 | deadline 到点 → `cancel` + 超时收尾 | `deadline_at` 是 `markSubmitted` 时写进行里的绝对时刻，与谁在跟踪无关 |
| A 死在 `submit()` 后、`markSubmitted` 前 | 走 §5.3 回收 → 重投命中同名 Job | 批次不会永久停在 `SUBMITTING` |

两个真缺口——不是「A 挂了」特有，而是这件事把它暴露出来：

1. **回调丢一次 = 批次降级。** `OptimizationApiClient.report` 没有重试（`send()` 非 2xx 直接抛），
   A 恰好在处理某个 item 的上报时死掉，driver 那一次上报就丢了。收尾时 `failOpenItems`
   把「任务已结束但没上报结果」的明细标 `FAILED`，于是一个**实际成功**的批次落成
   `PARTIAL_SUCCESS`。要让「支持成功」真正成立，得给 `report` 加 3 次退避重试
   （服务端是幂等写入，重试安全），列 P1。
2. **TTL 与 deadline 错配。** Job 是 `ttlSecondsAfterFinished = 3600`，而 `jobTimeoutSeconds`
   默认 7200、最大可配 86400。若整个服务端停摆超过 1 小时（A 挂了且没有别的节点），
   Job 已被 k8s 删掉，`state()` 只能返回 `UNKNOWN`（`Fabric8JobSubmitter` 里 `job == null`），
   而 `pollRunning` 对 `UNKNOWN` 的处置是「不动它」→ 一路转到 deadline 才判超时，
   于是**已经成功**的批次被判成超时。修法二选一：`ttlSecondsAfterFinished` 配成
   `> jobTimeoutSeconds + 余量`（最简单，代价是 Job 对象多留一会儿）；或收尾时显式
   `delete`（`cancel` 已有实现），把 TTL 从「清理手段」降级为「兜底」。

还有一个小的活性限制：`findTop50ByStatusOrderByCreatedAtAsc` 每拍只看最早的 50 条。
活跃批次长期超过 50 时，排在后面的要等前面的收尾才被跟踪——不影响正确性，
但会拉长这 50 名之外的感知延迟，多 catalog 大规模部署时值得改成按 `submitted_at` 分页。

**一条可选的简化，值得先评估**：跟踪其实不必占租约。`pollRunning` 的每一处写都已是 CAS，
多节点同时 poll 只是多做几次 GET，不会写出第二个终态。把它从 `dispatch` 租约里放出来
（或给它自己的无锁节拍），接管时间从「≤70 秒」变成「≤10 秒」，也不再依赖 §5.1 是否可用。
代价是 apiserver 的 GET 量变成 `副本数 × RUNNING 批次数 / 拍`——10 秒拍、3 副本、
10 个批次是 3 QPS 量级，可以接受。`submitPending` 仍必须留在租约里（提交有外部副作用）。

## 6. 时序：两台机器、一次崩溃

正常路径：

```
t0  机器 A 抢到 optimization.scan（fencing 7）
    A: 锁 catalog C → 数 active=1 < 2 → claim 表 T（next_run_at 推进）→ 建批次 J（PENDING）
t1  A 抢到 optimization.dispatch
    A: claim J（PENDING→SUBMITTING，attempt=1）→ submit → 命中/新建 Job J → markSubmitted(attempt=1)
t2  A、B 都会 poll 到 J（RUNNING）
    A、B 各自问 k8s，都拿到 SUCCEEDED
    A: finish CAS 成功（1 行）→ 写明细 → 发事件 ✅
    B: finish CAS 返回 0 行 → 直接返回，不写、不发 ✅
```

崩溃路径：

```
t0  A claim J（SUBMITTING，attempt=1，租约到 t0+30s）
t1  A 在 submit 返回之后、markSubmitted 之前被 kill -9
t2  t0+30s 之后，B 的回收节拍扫到 J：CAS(PENDING, attempt=1, expires<now) 成功
t3  B 下一拍 claim J（attempt=2）→ submit → 同名 Job 已存在 → 返回 Submitted
    → markSubmitted(attempt=2) 成功 → 批次回到 RUNNING，正常收尾
```

注意 t3 的「同名 Job 已存在」是整条链路安全的支点，写代码时不能改成
「无条件 create + 捕获 409 后重建」——那会把一个正在跑的 Job 删掉。

## 7. 不变式（落地后应有测试守着）

- **I1** 同一张表不会同时出现在两个活跃明细里（`status in (PENDING, RUNNING)`）。
- **I2** 一个批次从 `PENDING` 到终态，只会有一个 `claim_attempt` 对应的写者成功回写。
- **I3** 批次的每个终态只产生一条事件。
- **I4** `SUBMITTING` 不会滞留超过 `claim-ttl × (max-attempt + 1)`。
- **I5** `SUCCESS` 一旦写入，不会被任何路径改成其它终态。
- **I6** 任意时刻，一个 catalog 的活跃批次数 ≤ `maxConcurrentJobs`。

I1/I6 依赖 §5.2 的锁与 §5.3 的 claim；I2/I3/I5 依赖 §5.3 与 §5.4 的 CAS；
I4 依赖回收器常开。

## 8. 部署前提（不满足这些，改代码也没用）

| # | 前提 | 现在是什么 | 不满足的后果 |
| --- | --- | --- | --- |
| 1 | warehouse 必须是对象存储（S3 / OSS / OBS / ABFS） | `replicas: 1` + hostPath + `file://` | 两个 Pod 各写一份仓库：建的表在另一个节点看不见 |
| 2 | `management-url`（catalog 执行集群配置）填 **Service DNS** | 由使用者手填，可能是 IP | 滚动更新后回调打到已销毁的 Pod，批次全部走超时收尾 |
| 3 | `lakehouse.rest.auth.access-token.signing-key` 显式配置 | 留空则每次启动随机 | 两个实例互不认令牌，控制台随机 401 |
| 4 | 共享 MySQL 单库，`ddl-auto: validate` | 已是 validate | 各节点内嵌库 = 各跑各的；DDL 未执行则新版本启动失败 |
| 5 | 发布顺序：**先执行 DDL，再滚动镜像** | — | `validate` 会挡启动，两个新 Pod 都起不来 |
| 6 | 节点时钟同步（NTP/chrony） | — | 租约与 `deadline` 都按毫秒比较，漂移几秒会让回收误判 |
| 7 | 停机让出：`@PreDestroy` 释放租约 | 只有 web 层的 graceful shutdown | 滚动期间调度空窗一个 TTL（默认 3 分钟）；强杀时退化为同一行为，只慢不错 |

补充两条与本文直接相关的部署建议：

- **按节拍配 TTL**：`scan = 3 × scan-interval`，`dispatch = 3 × dispatch-interval`。
  改了节拍要同步 TTL，否则「慢节拍 + 短 TTL」会导致每拍都换人（不是错误，但白跑）。
- **灰度做法**：先起 2 个副本，其中 1 个 `scheduler-enabled=false`，
  确认服务本身能多副本工作（缓存、令牌、回调）；再打开第二个的调度，
  用 §10 的断言确认没有重复。

## 9. 备选方案与被否的理由

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A. 单写者（只让一个副本开调度）** | 一个副本 `scheduler-enabled=false`，或按 Pod 序号判断 | 最省事，能挡住「出批/提交」的重复；**挡不住收尾重复与手动入口**（这两条与调度开关无关），且滚动发布期间必然空窗。适合当灰度期措施，不适合当终态。 |
| **B. DB 租约 + 全程 CAS（本文）** | 一张租约表 + 三条 CAS 语句 | 无新增组件，故障自动切换，滚动发布无空窗。代价是两处 `PESSIMISTIC_WRITE` 与一张表。 |
| **C. 外置调度** | K8s CronJob 定时打一个内部端点触发出批 | 把「什么时候该出批」交给 k8s，服务端只做「出一次批」。问题是这个端点的鉴权、幂等、审计要再做一套，且 `scan-interval` 的双份配置（env 与 CronJob）会漂移。适合「已经有企业调度系统」的部署，不是默认路径。 |
| **D. Redis / ZooKeeper 分布式锁** | `SET NX PX` / 临时节点 | 多一个中间件。本项目没有 Redis 依赖，也不打算为一个锁引入它。 |
| **E. ShedLock** | 注解 + 一张 `shedlock` 表 | 成熟、代码量最小（`@SchedulerLock`）。但它是「新依赖 + 新表」，且只解决 §5.1（谁跑），解决不了 §5.3/§5.4（崩溃回收与收尾 CAS）。若不想自己写租约，E 可以作为 §5.1 的替代，其余照旧。 |

结论：**B，租约部分允许用 E 替换，但 §5.2–§5.5 必须自己做。**

## 10. 分期与验收

### P0（多副本上线门槛）

| 项 | 改动位置 | 验收 |
| --- | --- | --- |
| 终态收尾 CAS | `OptimizationJobService` 的 `finishFromSubmitter` / `markTimedOut` / `cancel` | 两实例同时 poll，订阅方只收到一条终态事件；`SUCCESS` 不被改成 `PARTIAL_SUCCESS` |
| 提交租约 + 回收 | 批次表加 3 列、`claimForSubmission`、`markSubmitted`、`OptimizationDispatcher` 新增 `reapStaleSubmissions` | `kill -9` 提交中的实例，60 秒内批次回到 PENDING 并被接管，k8s 里始终只有一个 Job |
| 出批配额串行化 | `CatalogRepository#lockById`、`OptimizationBatchService#createBatch`、事务拆分 | 两实例同拍扫描，catalog 活跃批次数不超过 `maxConcurrentJobs`（I6） |
| 血缘清理单实例 | `LineageRetentionJob` + 租约通道 `lineage.cleanup` | 两实例同时到点，只有一个执行 DELETE |
| 部署前提 1–6 | `deploy/kubernetes/lakehouse-rest.yaml`、`README.md` 部署章 | 两副本跑通建表、写数据、执行优化批次 |

### P1（正确性加固）

- 调度租约（`optimization.scan` / `optimization.dispatch`）+ 停机释放（部署前提第 7 条随之落地）。
- `markSubmitted` 带 `claim_attempt`；`report` 的终态判定改成条件更新。
- 手动「立即优化」/ 重试：锁内复查活跃明细，冲突返回 409。
- `Fabric8JobSubmitter`：接管的同名 Job 若已 `Failed`/`Complete`，先删再建（避免无谓重试）。
- `OptimizationApiClient.report` 加 3 次退避重试（服务端幂等）：否则换节点那一次上报丢失，
  会把一个成功的批次降级成 `PARTIAL_SUCCESS`（§5.7 缺口 1）。
- `ttlSecondsAfterFinished` 与 `jobTimeoutSeconds` 解耦（前者 > 后者 + 余量，或收尾时显式删 Job）：
  否则服务端停摆超 1 小时后，成功的批次只能被 deadline 判成超时（§5.7 缺口 2）。
- 可选：把 `pollRunning` 移出 `dispatch` 租约（写全是 CAS，多节点并发安全），
  接管时延从 ≤70 秒降到 ≤10 秒；`submitPending` 必须留在租约内。

### P2（可观测与长期一致性）

- `scripts/verify-scheduling.py`：静态校验「每个 `@Scheduled` 方法都在租约通道清单里」，
  与 `verify-console.py` 同风格（机械一致性，防止以后新加定时任务忘登记）。
- 控制台：优化任务列表显示 `claim_owner`（只在 `SUBMITTING` 时可见）与 `claim_attempt`，
  排查「谁卡住了」不用连库。
- 事件 outbox：若将来事件要投递到外部（Webhook），把「CAS 成功后写 outbox」作为唯一出口，
  而不是在监听器里直发。
- `lakehouse_optimization_active` 登记簿（§5.5 备选），若活跃明细的扫描成为瓶颈再上。
- 指标：多实例下每个 Pod 各算一份 Gauge，Prometheus 侧不要对无 `instance` 标签的 Gauge 直接求和
  （与 `docs/metrics-design.md` 的口径对齐）。

### 双实例验证方法（P0 的验收脚本）

1. 同一个 MySQL，两个进程：`--server.port=8080/8081`，
   `scan-interval=PT5S`、`dispatch-interval=PT2S`，20 张表策略间隔 60 秒。
2. 跑 10 分钟，断言：
   ```sql
   -- I1：无同表双活跃
   select table_id from lakehouse_optimization_job_item
    where status_value in ('PENDING','RUNNING')
    group by table_id having count(*) > 1;            -- 期望 0 行

   -- I4：无 SUBMITTING 孤儿
   select id from lakehouse_optimization_job
    where status_value = 'SUBMITTING' and claim_expires_at < :now - 60000;  -- 期望 0 行

   -- I6：不超配额（按 catalog 与自己的 maxConcurrentJobs 比）
   select catalog_id, count(*) from lakehouse_optimization_job
    where status_value in ('PENDING','SUBMITTING','RUNNING') group by catalog_id;
   ```
3. 缺陷注入：提交路径加一个调试开关（`sleep 35s`）后 `kill -9`，观察回收与接管。
4. 事件去重：桩 `OptimizationJobSubmitter` 让两实例同时看到 `SUCCEEDED`，
   断言 `audit_event` 里同一 `jobId` 的 `optimization.job_succeeded` 只有一条。
5. 接管（§5.7）：A 提交出 `RUNNING` 批次后 `kill -9` A，断言 B 在 `dispatch-ttl + 一拍`
   内接手跟踪，批次最终落 `SUCCESS`/`FAILED` 且只有一条终态事件；期间 k8s 里始终只有一个 Job。

## 11. 风险与回退

| 风险 | 触发条件 | 缓解 |
| --- | --- | --- |
| 悲观锁拖长扫描事务 | 一个 catalog 有几十张到期表，`createJob` 写 50 行明细持锁 | 锁范围限定在单个 catalog 的出批事务；`maxTablesPerJob` 已是上限；必要时把锁退化成「先占坑后出批」 |
| 回收把正在提交的批次抢走 | `claim-ttl` 配得比 `submit()` 的实际耗时还短（k8s API 慢） | TTL ≥ 3× 节拍且 ≥ 30 秒；`markSubmitted` 带 attempt，抢走后旧写者的回写会被丢弃而不是覆盖 |
| 租约表被手工清空 | 运维误操作 | 启动时 `existsById` 否则 `save`（并发启动时吞掉主键冲突），并在启动日志里打印通道清单；行缺失期间退化为「全部实例都跑」，正确性仍由 CAS 兜住 |
| 强杀未让出租约 | `kill -9` / OOMKilled / 节点宕机（`@PreDestroy` 不执行） | 只损失一个 TTL 的调度时延（dispatch 60s、scan 3min、lineage 1h），无重复执行；详见 §5.1「强杀」一段 |
| 时钟漂移导致双持有 | 未配 NTP | 租约带 3 倍余量；正确性仍由 CAS 兜住（§4 第 4 条） |
| DDL 与代码不同步 | 先滚镜像后执行 DDL | 发布顺序写进 §8 第 5 条；`validate` 会直接挡启动，不会带病运行 |

回退：所有改动都是「加列 + 加表 + 收紧 WHERE」，没有删除路径。
回退到单副本只需把 `replicas` 改回 1 并关掉第二个副本的调度；
新增的列与表留着不影响旧逻辑（`claim_*` 为空即视为「无租约」）。

## 12. 与现有文档的关系

- `docs/table-optimization.md` §9 开放问题 1 原先把「多副本调度互斥」整体标为已解决——
  它实际只解决了**出批**这一环（`next_run_at` 条件更新）。该条已补上范围说明并链接本文；
  本文落地后应把它改成「出批见该文 §6，其余见本文」。
- `docs/data-model.md` 需要登记新表（26 → 27）与批次表新增的 3 列。
- `docs/catalog-execution-cluster-design.md` §12.2 的「回调地址」一项要与本文 §8 第 2 条对齐：
  多副本下它必须是 Service DNS。
- `docs/metrics-design.md` 的 Gauge 口径与本文 §10 P2 的指标条目一致。

## 13. 落地记录（实现与本文的对应关系）

代码落点：

| 本文 | 代码 |
| --- | --- |
| §5.1 调度通道租约 | `domain/entity/SchedulerLeaseEntity` + `domain/repo/SchedulerLeaseRepository`（两条 UPDATE）+ `service/SchedulerLease`（启动补齐行、`tryAcquire`、`@PreDestroy` 让出、TTL 按节拍派生） |
| §5.2 配额闸门 | `CatalogRepository#lockById`（`PESSIMISTIC_WRITE`）+ 独立 bean `service/OptimizationBatchCreator`（`REQUIRES_NEW`，锁内 count 再 create）；`OptimizationBatchService` 不再包大事务 |
| §5.3 提交租约与回收 | `lakehouse_optimization_job` 三列 + `OptimizationJobRepository#claimForSubmission / markRunning / reclaimSubmission`；`OptimizationJobService#reapStaleSubmissions` 由 `OptimizationDispatcher#dispatch` 每拍驱动 |
| §5.4 终态 CAS | `OptimizationJobRepository#finish / finishClaimed / finishAfterDeadline / cancel` + `OptimizationJobItemRepository#report`（条件更新）；`finishFromSubmitter / markTimedOut / cancel / report` 四个入口改为「算 → CAS → 补明细 → 发事件」 |
| §5.5 同表互斥 | `TableOptimizationService#run` 与 `OptimizationJobService#retry` 锁内复查活跃明细，冲突 409（`ApiException.conflict`） |
| §5.6 单实例任务 | `LineageRetentionJob` 挂 `lineage.cleanup` 通道 |
| §5.7 接管 | `pollRunning` 移出租约（见下），TTL 与 `jobTimeoutSeconds` 解耦 |
| §10 P1 | `OptimizerJob#ttlSecondsAfterFinished`；`OptimizationApiClient#report` 加退避 |

**与本文的六处偏差，都是实现时才看清的：**

1. **跟踪直接移出了 dispatch 租约。** 本文 §5.7 把它列为「可选、值得先评估」，实现时
   采纳了：`dispatch()` 只把 `submitPending` + `reapStaleSubmissions` 放在租约里，
   `pollRunning` 每拍无条件跑。收益是接管时延从 ≤70 秒降到 ≤10 秒、不再依赖租约可用；
   代价只是几倍 GET。前提写在代码注释里：**跟踪路径的每一处写必须是条件更新**，
   谁往里面加一条无条件 `save`，这个简化立即变成 bug。
2. **收尾顺序是「算 → CAS → 补明细 → 发事件」，不是本文 §5.4 的「补明细 → 算 → CAS」。**
   原因是提交阶段的失败（`markSubmitFailed`、回收判死）也走同一套收尾：如果先补明细，
   而 CAS 返回 0 行（批次已被别的实例接管并重新提交），那些明细就被错误地标成了失败。
   改成先 CAS 之后，「未终结明细按失败计」的结论改在 `statusFromItems` 里算——
   它本来就读同一份明细，两台机器算出的值一致，结论不变而副作用只落在胜者身上。
3. **明细补失败的文案按 cause 分成四个常量，而不是一个。** 本文要求「文案归一化」是为了
   避免两台机器互相覆盖；只要不含动态插值就满足这个要求，因此保留
   「任务已结束但这张表没有上报结果」/「任务在执行侧失败，这张表没有上报结果」/
   「批次超过配置的超时时间仍未结束」/「提交优化任务失败，未开始执行」四条，
   比一条笼统文案更容易排障。真正的错误原因仍在批次的 `error_message` 上（由 CAS 胜者写）。
4. **`ttlSecondsAfterFinished` 改为 `max(3600, jobTimeoutSeconds + 600)`。** 本文 §5.7 缺口 2
   给的两个修法里选了「配长」：收尾时显式删 Job 会让「重投命中同名」这条安全前提
   （§5.3 论证第 2 条）依赖回收器跑得更勤，耦合更紧。
5. **租约表在 schema 未就绪时不让启动失败。** `SchedulerLease` 的 `@PostConstruct` 吞掉
   「表不存在」并记 ERROR，第一个节拍再试一次。两个原因：`MysqlDdlGeneratorTests` 要在
   空库上生成 DDL；升级时「先滚镜像、后执行 DDL」也不该让两个新 Pod 都起不来。
6. **`report` 原本就有 3 次重试，缺的只是退避**，本次补上 `300ms × 第几次` 的等待。

**仍未做（P2）**：`scripts/verify-scheduling.py` 的静态校验（目前靠 `SchedulerLease.channels()`
这份清单 + code review）、控制台展示 `claim_owner` / `claim_attempt`、事件 outbox、
`lakehouse_optimization_active` 登记簿、多实例下的 Gauge 口径。

