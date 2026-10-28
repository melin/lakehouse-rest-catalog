# Lance 表的存储优化（存储优化第三个格式面）

> 状态：**已落地**。四处代码都在：服务端 `LanceOptimizationOptions` + `OptimizationOptions` 的
> 穷尽 `switch`，执行侧 `LanceProcedures` + `TableOptimizerJob` 的三元分派，控制台
> `LANCE_FIELDS` + 卡片按 `supported` 置灰，镜像带 `lance-spark-bundle`。验收面见 §9
> （optimizer 49 项、`OptimizationApiTests` 16 项、`verify-console` 第 11 组、`npm test` 60 项）。
>
> 前置依赖 [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) 的 P0（`/lance/v1`
> 的 13 个端点）**也已落地**——没有元数据面，优化任务的 Spark 会话连 catalog 都建不起来（§8 R2）。
>
> 本文回答一件事：**Lance 表的 compaction 怎么接进已有的存储优化链路**（服务端策略 → 组批 →
> k8s 上的 Spark 任务 → 控制台）。已有的三份底稿是
> [`table-optimization.md`](table-optimization.md)（Paimon/Iceberg 的动作映射与执行架构）、
> [`optimization-multi-instance-design.md`](optimization-multi-instance-design.md)（多实例调度）与
> [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md)（Lance 元数据面）。
> 本文不重复它们的结论，只写**Lance 逼出来的差异**。
>
> **落地阶段更正了三处设计稿的事实错误**，正文里就地改掉并注明原因，不另开勘误表：
> `max_source_fragments`（connector 0.4.0 的 Spark 侧没接出来，§3.1 / §4.1）、
> compact 的键数（8 → 7，§0 / §4.1）、以及控制台「存储优化页签对 Lance 表放开」这一条
> 的边界（§7.5——真正的 Lance 表今天到不了表详情页）。

---

## 0. 结论速览

| 问题 | 定案 |
| --- | --- |
| 复不复用现有链路 | **复用**。`lakehouse_table_optimization` / `optimization_job` / `optimization_job_item` 一张表都不加，组批、提交、跟踪、重试、回调全部照旧 |
| 动作怎么对 | Lance 只有**两条**维护语句：`OPTIMIZE`（合并碎片）与 `VACUUM`（删旧版本 + 回收无引用文件）。因此 **compact → `OPTIMIZE`，expire → `VACUUM`** |
| `orphan` 怎么办 | **恒不可用**。`VACUUM` 一步就做完了「过期版本 + 孤儿文件」，再暴露第三张卡只会让同一件事跑两遍 |
| UI 三张卡变几张 | 还是三张，**第三张置灰并写原因**。动作名、执行顺序、历史三块都是格式无关的契约，藏起一张会让「同一张表在两个页面不一样」 |
| 参数键数 | compact **7 个**（5 个恒进语句 + 1 个可选的 `batch-size` + 1 个调度间隔）、expire **3 个**（1 个进语句 + 1 个恒定的安全常量 + 1 个调度间隔）。§4.3 另列了 12 个**刻意不给**的键（归成 7 条理由），其中 `max_source-fragments` 是落地时从设计稿里删掉的 |
| 指标从哪来 | 语句自己的返回行：compact 是**四元增量**（`fragments_removed` / `fragments_added` / `files_removed` / `files_added`），expire 是二元（`old_versions` / `bytes_removed`）。**没有 before/after 快照**，理由是 Lance 在 Spark 里没有 Paimon `$files` 那样的系统表（§2、§4.5） |
| 时间窗口怎么写 | `before_timestamp_millis`——**epoch 毫秒，唯一不需要对齐时区的格式**（Paimon 按 JVM 默认时区解析、Iceberg 按会话时区；Lance 压根没有时区这个概念） |
| 干跑 | **没有**。`VACUUM` 不可逆且无 dry-run，替代方案是「窗口保守 + 标签保护 + 提交前用 P1 的 `ListTableVersions` 数一遍」（§3.4） |
| 执行侧改动 | 新增 `LanceProcedures`（语句翻译）；`TableOptimizerJob` 里 **3 处二元分派改三元**、`spark()` 加一个分支、指标读取加一个分支。**最大的坑是 `isIceberg()` 那个布尔判断**——不改它，Lance 批次会静默走 Paimon 分支（§5.1） |
| 服务端改动 | `CatalogFormat` 加 `LANCE`；`OptimizationOptions` 里 **7 处 `isIceberg(format) ? … : …` 全部改 switch**；`isOptimizableFormat` 加一种；`formatOf` 的回落要挡住（§6.1） |
| 数据模型 | **0 张新表、0 个新列**（`orphan_remove_enabled` 对 Lance 恒 false，不写库） |
| 镜像 | 优化任务镜像要加 `lance-spark-bundle`，并且 **`--add-opens` 必须写在镜像 ENV 里**——它不能由 `SparkSession.builder` 生效（§5.6） |
| 能不能现在就做 | **能，已做完**：`/lance/v1` 先落地，然后按 §8 R1 的三条实测回答了「会话怎么建、语句什么形状、指标从哪读」。三条答案都已写成测试断言（`LanceProceduresTests` 用真 connector 的 lexer/parser 逐条解析生成的语句） |

---

## 1. 事实基线

以下每条都来自 Lance 官方文档或源码，不是推测。分三类标注：**规范**（文档明写）、**源码**（读实现得到）、**待实测**（必须在目标镜像上验）。

### 1.1 Spark 侧的维护面只有两条语句

| 语句 | 作用 | 来源 |
| --- | --- | --- |
| `OPTIMIZE <table> [WITH (...)]` | 合并小 Fragment、物化删除、丢掉已删除列 | 规范 |
| `VACUUM <table> [WITH (...)]` | 删旧版本 + 回收不被任何保留版本引用的文件 | 规范 |
| `OPTIMIZE INDEX` | 增量维护某个索引（覆盖未索引片段、合并段） | 规范，**我们不做**（§1.5） |

关键点：**这两条都是解析器级命令，不是 `CALL` procedure**。后果有三条，都会落到实现上：

1. 必须装 Lance 的 Spark 扩展（`spark.sql.extensions`）才认这两条语句——只把 jar 放进类路径不够（与 Iceberg 的 `CALL` 需要扩展是同一类坑，但**失败点不同**：Iceberg 缺扩展是「procedure 不存在」，Lance 缺扩展是**解析期语法错**，报错里根本不会提「扩展」两个字）。
2. 参数走 `WITH (key = value)`，不是 `名字 => 值` 的具名实参，所以没有「参数名随版本变」这层风险，但仍然有「**选项名随版本变**」——`defer_index_remap` 在 Lance 7.0.0 就被判为无效选项（Volcengine 文档原文举的例子），写错的表现是执行期报 `Invalid compaction option`，不是编译期。
3. 语句里的表名要**逐段加反引号**（§5.3）。

### 1.2 参数分四层，其中两层在 Spark 侧根本不存在

Volcengine 那篇把 compact 的参数按生效层分四类，并明确说「作用范围不同的参数不能互相替代」。对我们最要紧的是它自己划的那条界线：

| 层 | 参数 | 我们能不能用 |
| --- | --- | --- |
| Lance Compaction Plan | `target_rows_per_fragment`、`materialize_deletions_threshold`、`max_source_fragments` | ✅ / ✅ / ❌ **Spark 侧没接出来**（见下） |
| Lance Rewrite | `max_bytes_per_file`、`materialize_deletions`、`batch_size`、`num_threads` | ✅ 能用（`num_threads` 故意不给，§4.3） |
| **Daft 调度** | `num_partitions`、`concurrency` | ❌ **不存在**。原文：「使用 Lance-Ray、Spark 或其他引擎时须改用对应引擎的调度与提交参数」 |
| **Daft 提交** | `micro_commit_batch_size` | ❌ **不存在**。它是 Daft 把 Compaction Plan 拆成多个 Commit Batch 的参数；Spark 侧没有对应物 |
| 进程 I/O | `LANCE_IO_THREADS`、`LANCE_DEFAULT_IO_BUFFER_SIZE`、`LANCE_DEFAULT_FRAGMENT_READAHEAD` | ⚠️ 能用，但**不是 SQL 参数**：只能走容器环境变量（§5.6） |

这条界线决定了控制台上「并行度」这类字段一个都不能出现。给用户一个不生效的输入框，比不给更坏——他会以为调大就有用，然后得出「这套优化没效果」的结论。

**`max_source_fragments` 的前两层都能用，第三层不成立。** `CompactionOptions` 有这个字段，但 Spark 的 `OptimizeExec.buildOptions` 只从 `WITH` 里读 8 个参数，**不含它**（落地时查的 0.4.0 源码）——写进语句会被静默忽略。这正是上面那句话的实例：**「Lance 支持它」与「我们能配它」不是一件事**，判据只能是 `<格式> connector 的 Spark 入口是否读这个键`。因此参数表里没有它（§4.3）。

### 1.3 指标是增量，不是快照

源码 `lance/dataset/optimize.rs` 里 `CompactionMetrics` 的定义就四个字段，且注释写明 `files_added` **恒等于** `fragments_added`（一个 Fragment 一个新文件）：

```rust
pub struct CompactionMetrics {
    fragments_removed: usize,  // 被覆盖的旧 Fragment 数
    fragments_added: usize,    // 新 Fragment 数
    files_removed: usize,      // 删掉的文件数（含 deletion file）
    files_added: usize,        // 新文件数
}
```

`VACUUM` 返回 `bytes_removed` / `old_versions`。

**没有「合并后一共几个 Fragment」这个数**。Paimon 侧能报 `files-before` / `files-after`，是因为它能查 `$files` 系统表；Lance 在 Spark 里没有等价物（**待实测**：见 §8 R1，若连接器后续提供 `SHOW FRAGMENTS` 之类再补）。

所以控制台上 Lance 的 compact 历史不显示「文件数 12 → 4」，显示「碎片 −8 / +2，文件 −12 / +3」。这不是偷懒，是把语句真实给出的东西如实展示——硬凑一个 before/after，就等于自己算一个「结果」，而算错的方向永远是多报收益。

### 1.4 空跑不会提交新版本

`compact_files` 的实现里有一句 `if compaction_plan.tasks().is_empty() { return Ok(CompactionMetrics::default()); }`，注释是「If nothing to compact, don't make a commit」。

这条对我们是好消息：`SKIPPED` 的判定（「扫了一遍没动」）在 Lance 上是**语义精确**的——没动就是没提交，不会像某些引擎那样空转出一个新版本，让「版本越来越多」变成 compaction 自己的副作用。

### 1.5 为什么不做 `OPTIMIZE INDEX`

Lance 的索引住在 `_indices/` 里，compact 会改变 Row Address，所以 Commit 阶段要么立即 Remap，要么延迟（`defer_index_remap=true` 记一层映射）。

不做索引维护有三个理由：

1. **它还是 beta 面的接口**：`ALTER TABLE ... OPTIMIZE INDEX` 到 2026 年 8 月还在 issue 里讨论（`lance-format/lance-spark#742`），语义（`target_segment_bytes` 是软目标、size-tiered 合并）与结果列（`index_name` / `fragments_indexed` / `segments_before` / `segments_after`）都没定稿。把它写进策略表等于把一个 draft 契约焊进我们的数据库。
2. **我们的目录面根本不碰索引**：`lance-rest-catalog-design.md` §3.1 把 5 个索引 operation 全部划进「恒不做（406）」。目录看不见的东西，优化面去维护它，用户没有任何地方能验证结果。
3. **风险不对称**：索引 Remap 出错的表现是**查询返回错误的向量邻居**，不是报错。这类故障没有告警、没有失败率，只有下游的模型效果变差。

但**必须承认它的存在**：`defer_index_remap` 我们默认 `false`（立即 Remap），正是为了让「对已有索引的表跑 compact」这件事至少是自洽的；代价是 driver 内存与 Commit 耗时（Volcengine 文档把它归到「Head OOM」那一类，见 §8 R6）。

---

## 2. 与两种既有格式的结构性差异

先给一张总表，后面各节都在填它的细节。

| 维度 | Paimon | Iceberg | **Lance** |
| --- | --- | --- | --- |
| 语句形状 | `CALL sys.<proc>(…)` | `CALL <cat>.system.<proc>(…)` | **`OPTIMIZE` / `VACUUM`，解析器命令** |
| 语句条数 | 3（compact / expire / orphan） | 3 | **2**（compact / vacuum），第三项恒不可用 |
| 参数语法 | `名字 => 值` | `名字 => 值`，map 形参用 `map(...)` | **`WITH (k = v)`** |
| 时间窗口 | expire 给**时长**（表选项）、orphan 给**绝对时间戳**（JVM 默认时区） | 绝对时间戳（UTC 会话） | **epoch 毫秒**，无时区 |
| 合并前/后读数 | `$files` 系统表 | procedure 返回的重写计数 | **无**，只有增量四元组 |
| 干跑 | orphan 有 `dry_run` | orphan 有 `dry_run` | **无** |
| 会话扩展 | `PaimonSparkSessionExtensions` | `IcebergSparkSessionExtensions` | `LanceSparkSessionExtensions` |
| catalog 配法 | `metastore=rest` + `data-token.enabled` | `type=rest` + `header.X-Iceberg-Access-Delegation` | **`impl=rest` + `uri=…/lance` + `parent=<catalog>`** |
| 表名 | `db.tbl`（`$files` 要额外反引号） | `db.tbl` | **`ns.tbl`，但语句里要 `cat`.`ns`.`tbl` 逐段反引号** |
| 额外 JVM 参数 | 无 | 无 | **`--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`** |

三条差异值得单独解释，因为它们各自对应一个「不看就会写错」的地方。

**（1）两条语句覆盖三件事，是格式本身的形态，不是我们偷懒。** Paimon 与 Iceberg 把「过期快照」与「孤儿文件」拆成两个 procedure，是因为它们的孤儿判定要扫整个仓库目录、且要单独给一个时间窗口；Lance 的版本清理本来就要读 manifest 判断「文件还被谁引用」，再单独扫一遍目录是重复劳动。`VACUUM` 一步做完，语义上更安全（同一次扫描给出同一份引用集）。

**（2）`OPTIMIZE` 不是 procedure，所以 `CALL` 那套「交给目标集群解释」的理由要换一种说法。** 我们坚持用 SQL（而不是 Lance 的 Java API）的理由不变：选项名与语义随软件版本变，交给目标镜像里的 connector 解释，本模块的产物就不会把 Lance 版本焊死。但失败面变了——`CALL` 写错是「procedure 找不到」，`OPTIMIZE` 写错是「语法错」或「无效选项」，两者的排查路径不同（§5.5 的失败信息因此必须带语句）。

**（3）表名拼法在三个面上都不一样，这是最容易写错的一处。**

| 面 | 正确写法 | 写错的后果 |
| --- | --- | --- |
| Lance REST 请求 | `demo%24sales%24orders`（`delimiter=$`，URL 编码） | 段数不对 → 400 |
| Spark 里引用 | `` `demo`.`sales`.`orders` ``（三段，逐段反引号） | 名字里带 `-` 或含保留字 → `PARSE_SYNTAX_ERROR` |
| 用 `$` 拼给 Spark | ~~`demo$sales$orders`~~ | **变成一个单段标识符**，解析能过、语义全错——与 Paimon `$files` 那次正好相反：那边必须让 `$` 留在标识符里，这边 `$` 绝不能出现在 Spark 名字里 |

Spark 侧之所以是「catalog 名 + 点号」而不是 `$` 拼接，是因为 Lance 的 connector 用 `spark.sql.catalog.<name>.parent=<catalog>` 表达第一级命名空间（与 Gravitino 的示例一致），`$` 只活在 REST 协议的 id 里。

---

## 3. 动作映射

### 3.1 `compact` → `OPTIMIZE`

```
OPTIMIZE `demo`.`sales`.`orders`
WITH (target_rows_per_fragment = 1048576,
      max_rows_per_group = 1024,
      materialize_deletions = TRUE,
      materialize_deletions_threshold = 0.1,
      defer_index_remap = FALSE)
-- interval-hours 是调度参数，不进语句；batch-size 不配置时不出现（用 connector 默认）
```

三件事要定：

- **`target_rows_per_fragment` 是我们唯一的「目标大小」旋钮**，而它是**行数**不是字节数。Paimon/Iceberg 用 `target-file-size-mb`（字节），这里换成行数，是格式决定的口径差；控制台上必须换单位与文案，否则用户会拿 128 去填一个行数。默认取 Lance 自己的 `1024*1024`（源码默认值），不发明新数字。
- **`max_source_fragments` 不给**（设计稿本来给了，落地时删掉）。它是 `CompactionOptions` 的字段，但 connector 0.4.0 的 `OptimizeExec.buildOptions` 只读 8 个 `WITH` 参数、**不含它**——写进语句会被静默忽略。而不生效的旋钮的害处是双向的：用户配了 200，页面上显示生效，实际每轮照旧规划全表，于是「怎么调都不收敛」永远查不出来。宁可少一个键，也不给一个不生效的（§1.2、§4.3）。
- **`max_bytes_per_file` 默认不传**（源码里它是 `Option`，不传就用写入侧的默认）。给了会让输出文件按字节截断，与 `target_rows_per_fragment` 一起用容易产出「行数没到但文件满了」的碎片，反而让下一轮再次成为候选。同理 `batch-size` 默认也不传，只在 OOM 时调（§4.1）。

生成语句的四条硬规则（都是落地时用真 lexer 跑出来的，不是读文档猜的）：

| 规则 | 写错的后果 |
| --- | --- |
| 表名**逐段**加反引号 | 整串包成一个标识符 → 当成一张名叫 `demo.sales.orders` 的表 |
| 布尔写 `TRUE`/`FALSE` | 写 `'true'` → `Cannot cast STRING to BOOLEAN` |
| 浮点**必须带小数点**（`1` → `1.0`） | 裸 `1` 被词法分析成 `BIGINT_LITERAL`，connector 在 exec 里 `asInstanceOf[Float]` 抛 `ClassCastException`，栈里不会提我们 |
| 语句里**不能有分号与注释** | 它是 parser 级命令，不是 `CALL`；`CALL` 容忍的两样东西它不容忍 |

### 3.2 `expire` → `VACUUM`

```
VACUUM `demo`.`sales`.`orders`
WITH (before_timestamp_millis = 1793001600000,
      delete_unverified = FALSE,
      error_if_tagged_old_versions = TRUE)
```

- **窗口语义**：`before_timestamp_millis` 是「删掉早于这个时刻的版本」，所以它对应我们的 `retention-hours`（保留时长），窗口起点 = `now - retentionHours`，由任务侧算成毫秒（`Instant.minus(...).toEpochMilli()`）。**没有格式化、没有时区**——这是三个格式里唯一一处不用操心时区的地方（Paimon 用 JVM 默认时区解析、Iceberg 用会话时区，两个都踩过坑）。
- **`delete_unverified` 恒 `false`**（也是 Spark 文档的默认值）。`true` 的含义是「删掉验证不了引用关系的文件」——那是给「反正我要腾空间」的场景准备的，我们是定期维护，没有理由承担这个风险。
- **`error_if_tagged_old_versions` 恒 `true`**（Spark 文档默认 `false`）。设成 `true` 时，若待删的版本里有打了标签的，**整个 VACUUM 报错退出**。我们要的正是这个：标签是用户手动留的回滚点，静默删掉它比这次维护失败严重得多。失败会进 item 的 `error_message`，文案里点名「有标签保护的版本」，用户去删标签或调窗口后重试。
- **没有 `retain_n` 这类「最少保留几个版本」**。Spark 的 `VACUUM` 只有 `before_version` / `before_timestamp_millis` 两个判据，没有条数。因此 Lance 的参数表里**不出现** `retain-min` / `retain-last`——不是「先留着以后再说」，是给了也不会生效。

### 3.3 `orphan` 恒不可用

`VACUUM` 已经把「旧版本 + 无引用文件」一起做了。如果还允许 `orphan`，两种做法都错：

- 把它映射成第二次 `VACUUM`：同一批里对同一张表删两遍，第二遍一定是空跑；更糟的是**两个窗口**（expire 的保留时长 vs orphan 的清理早于）会互相矛盾，用户看到两条历史记录，分不清哪条真正删了文件。
- 把它映射成「不做任何事但报 SUCCESS」：那是假装成功。

所以采用第三种：**动作存在、但被标记为不支持**，控制台渲染成置灰的卡，标题下写一句「Lance 的 `VACUUM` 已同时清理过期版本与孤儿文件，此动作不单独提供」。

这要求 `ActionPolicy` 多两个字段（§6.3）。**不能只在控制台硬编码「format=LANCE 时隐藏第三张卡」**：那样 API 消费者（以及下一版控制台）会看到一个可用但一跑就失败的开关，而失败原因是「服务端没实现」——两种表达，前者是能力边界，后者是 bug。

### 3.4 顺序与「没有干跑」的补偿

批内顺序仍是 **compact → expire**（`orphan` 不出现）。理由与 Paimon 侧相同：compact 先产生新版本，expire 才基于最新状态判过期。

`VACUUM` 不可逆且没有 dry-run，用三层补偿，全部在提交前完成：

| 补偿 | 做法 | 谁做 | 落地状态 |
| --- | --- | --- | --- |
| 窗口保守 | expire 的 `retention-hours` 默认 **72**（不是 Paimon 的 24、Iceberg 的 120），执行间隔默认 **7 天** | 参数默认值（§4.1） | ✅ |
| 窗口下限 | 出批时把 `retention-hours` 抬到 ≥ catalog 的 `optimization.expire.max-commit-interval-hours`（默认 24） | `LanceOptimizationOptions#enforceRetentionFloor` | ✅ |
| 标签保护 | `error_if_tagged_old_versions=true` 恒开 | 语句翻译（§3.2） | ✅ |
| 提交前预览 | 组批时用 `ListTableVersions` 数一遍「窗口外的版本数」，写进 item 的「预计清理 N 个版本」 | 服务端（§6.5） | ❌ **未落地**，理由见 §6.5 |
| 不可逆提示 | 勾上 expire 时卡片内提示 + 确认框标题写「不可撤销」 | 控制台（§7.3） | ✅ |

第三条与第四条的分工值得记一笔：**「会不会删到正在写入的文件」是正确性问题，必须有代码兜底；
「这次大概会删几个版本」只是提示**。所以下限先做、预览后做——反过来（先做预览、靠用户
自己看数字决定窗口）就是把安全责任推给用户。

第三条的耦合点也值得说清：Lance 的版本列表在 REST 面是只读的（P1 就做
`ListTableVersions` / `DescribeTableVersion`），服务端本来就能回答「这张表有几个版本、
最老的是什么时候」。用它做预览不需要任何新端点，也不需要打开数据集——正好落在
「catalog 只回答元数据问题」的边界内。只是因为「服务端调自己这一面」这条跨面依赖的
代价大于一句提示的收益，本轮先不做。

反过来，**不能用「先跑一次不带窗口的 VACUUM 看会删什么」来模拟干跑**：那已经删了。

---

## 4. 参数表（`LanceOptimizationOptions`）

新增一个类，位置与 `IcebergOptimizationOptions` 并列（`lakehouse-rest-server/.../support/`）。**不做「一张表加 if」**——理由与 Iceberg 那份类头注释说的一样：键名能对上「策略/阈值/窗口」三件事，但取值单位甚至类型都不同（行数 vs 字节、毫秒 vs 时间戳字面量），塞进一张表就让「某个键属于哪种格式」变成隐式真相表，写错的那一侧不报错、只表现为行为悄悄变成另一种格式。

### 4.1 键表

**compact（7 个键，其中 1 个是调度键）**

| 键（策略层） | 对应 Spark 选项 | 类型 | 默认 | 校验 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `target-rows-per-fragment` | `target_rows_per_fragment` | long | `1048576` | 1 000 – 100 000 000 | 目标碎片行数；Lance 默认值就是 1M |
| `max-rows-per-group` | `max_rows_per_group` | long | `1024` | 64 – 1 000 000 | 碎片内 Row Group 行数；影响重写后的编码 |
| `materialize-deletions` | `materialize_deletions` | bool | `true` | — | 是否把软删的行真正去掉 |
| `materialize-deletions-threshold` | `materialize_deletions_threshold` | float | `0.1` | 0 – 1 | 删除比例达到阈值才物化；`≥1` 等于关闭 |
| `defer-index-remap` | `defer_index_remap` | bool | `false` | — | 延迟索引 Remap，见 §8 R6 |
| `batch-size` | `batch_size` | long | **不传** | 1 – 1 000 000 | 扫描批次；OOM 时才调 |
| `interval-hours` | —（调度用） | int | `1` | 1 – 8760 | 两次执行的最小间隔 |

> **`max-source-fragments` 不在表里，这是查过源码的结论而不是遗漏。** 设计稿原先给了它
> （默认 200），但 connector 0.4.0 的 `OptimizeExec.buildOptions` 只认 8 个 `WITH` 参数，
> 不含 `max_source_fragments`——`CompactionOptions` 有这个字段，Spark 侧没接出来。
> 落地时连同常量、默认值、校验一起从 `LanceOptimizationOptions` 删掉，`OptimizationApiTests`
> 也从「断言存在」改成 `absent(...)` 并写清理由。

**expire（3 个键）**

| 键 | 对应 Spark 选项 | 类型 | 默认 | 校验 |
| --- | --- | --- | --- | --- |
| `retention-hours` | `before_timestamp_millis`（换算） | int | `72` | 1 – 8760，且 ≥ catalog 的 `max-commit-interval-hours` |
| `delete-unverified` | `delete_unverified` | bool | `false`（**只读**，UI 上锁死） | 只接受 `false` |
| `interval-days` | —（调度用） | int | `7` | 1 – 365 |

`error_if_tagged_old_versions` **不暴露**：它恒 `true`，是安全属性不是可调参数。把它做成开关，等于提供一个「关掉标签保护」的按钮，而点它的用户在点之前不会知道自己在关什么。同理 `delete-unverified` 虽然出现在键表里（键表要与表单逐字一致，见 §9），但控制台把它**锁在 `false` 上**（`disabled: true`），不给一个点了就 400 的把手。

### 4.2 键名 kebab，选项名 snake

策略层的键一律 kebab-case（`target-rows-per-fragment`），与 Paimon/Iceberg 两套参数表同一个风格；翻译到 Spark 选项时下划线化。这一层转换看着多余，但它保证了**同一份策略在三张参数表里的样子一致**：控制台的表单、catalog 属性名（`optimization.compact.target-rows-per-fragment`）、API 的 JSON 键都用同一个词，只有执行侧那一个函数知道 Spark 要下划线。

反过来若直接照抄 Spark 的 snake_case 当策略键，catalog 属性就会变成 `optimization.compact.target_rows_per_fragment`——与同名的 Iceberg 键（kebab）在一张属性表里混排，运维改错一次不会有任何提示。

### 4.3 刻意不给的键

| 不给的键 | 为什么 |
| --- | --- |
| `max-source-fragments` | connector 的 Spark 入口不读它（§1.2、§4.1）。**这一条是落地时才发现的**：设计稿把它当成「单次工作量上限」，实际上写进语句会被静默忽略 |
| `min-input-files` | Lance 的候选判定是「行数 < `target_rows_per_fragment`」，没有「至少几个小文件才合并」这个门槛。硬塞一个，用户会以为它生效 |
| `target-file-size-mb` | 对应物是 `target_rows_per_fragment`，单位不同（行 vs 字节）。同一个名字两种单位是本设计最不想制造的歧义 |
| `retain-min` / `retain-last` | `VACUUM` 只按版本号或时刻判，没有条数（§3.2） |
| `dry-run` | 不存在（§3.4）。补偿是把「不可撤销」写进确认框标题（§7.3） |
| `num-partitions` / `concurrency` / `micro-commit-batch-size` | Daft 的参数，Spark 侧没有（§1.2） |
| `strategy` / `sort-columns` | `OPTIMIZE` 不做排序合并，也没有 z-order。Lance 的重排属于「写入时按列排序」或索引，不是 compaction 的策略 |
| `num-threads` | 默认 = 核数，而 Pod 的核数由 k8s limits 决定。暴露它等于让用户绕开资源配额 |
| `max-bytes-per-file` | 给了会让输出文件按字节截断，与 `target_rows_per_fragment` 一起用容易产出「行数没到但文件满了」的碎片（§3.1） |

要展开一句：**「不给」与「先不给」是三种承诺**。

- **格式层面没有**（`min-input-files`、`retain-*`、`dry-run`、Daft 的三个、`strategy`）：
  文案写「Lance 没有这个概念」；
- **有但 Spark 入口没接出来**（`max-source-fragments`）：也是「现在没有」，但它会随
  connector 升级而变——所以文案要写「connector 的 Spark 入口不读这个选项」，
  而不是「Lance 不支持」。前者指向升级，后者指向死路；
- **有但故意不给**（`num-threads`、`max-bytes-per-file`）：文案写「由容器 CPU limit 决定」
  /「与目标碎片行数一起用会产出坏碎片」。

三种混着写，用户就分不清哪些是「等以后」哪些是「永远没有」——而这三类的后续动作
完全不同：等升级、改配置、还是根本不该动它。

### 4.4 三层合并同构

内置默认 → catalog 属性（`optimization.<action>.<key>`）→ 表级差异，合并仍只发生在 `OptimizationOptions#resolve` 一处，只多一个分支（§6.1）。表级「自定义」档只提交改动过的键，规则与另两种格式完全一致（整份回填会把当前默认值抄成差异，之后调 catalog 默认值就再也影响不到这张表）。

### 4.5 指标键

| 动作 | 指标键（冒号后是来源列） | 判 `changed` |
| --- | --- | --- |
| compact | `fragments-removed`、`fragments-added`、`files-removed`、`files-added`、`duration-ms` | 四者之和 > 0 |
| expire | `old-versions`、`bytes-removed`（人类可读）、`duration-ms` | `old-versions > 0` |

键名与另两套**不重叠**，所以控制台的 `metricSummary()` 仍然是「不判格式、只判键存在」——它只需要认得新键（§7.4）。这条性质很重要：批次详情页里一批可能混着三种格式的表，页面不该为此知道每张表是什么格式。

---

## 5. 执行侧：`lakehouse-rest-optimizer` 的第三个分支

### 5.1 最大的坑：`isIceberg()` 这个布尔

现在 `TableOptimizerJob` 有 4 处按格式分派，其中 3 处是这个形状：

```java
result = isIceberg(plan) ? runIcebergStep(...) : runPaimonStep(...);
```

`isIceberg(plan)` 的实现是 `ICEBERG.equalsIgnoreCase(plan.format())`。加第三种格式时，**这个表达式对 `LANCE` 返回 `false`**——Lance 批次会安静地走进 Paimon 分支。后果不是「报个错」，而是：

1. `spark()` 会把 catalog 配成 `org.paimon.spark.SparkCatalog` + `metastore=rest`，连一个 `/v1/{prefix}` 的 REST catalog；
2. 我们的 Lance catalog 在 `/v1/{prefix}` 下**按设计回「表不存在」**（`lance-rest-catalog-design.md` §4.3）；
3. 于是失败信息是「表 demo.sales.orders 不存在」，而表明明存在——**排查方向被指错到权限或元数据上**。

所以本设计的第一条实现约束：**把 4 处分派全部改成对 `CatalogFormat` 的穷尽 switch，并让 `default` 抛异常而不是回落 Paimon**。

| 位置 | 现在 | 改成 |
| --- | --- | --- |
| `execute()` 的步骤执行 | `isIceberg(plan) ? runIcebergStep : runPaimonStep` | `switch (format) { PAIMON, ICEBERG, LANCE }` |
| `steps()` 的语句生成 | `if (isIceberg(plan)) … else …` | 同上，每格式一份 |
| `spark()` 的会话 | `if (isIceberg(plan)) … else …` | 同上，每格式一份 |
| `isIceberg(plan)` 本身 | 布尔判断 | **删掉**——`switch` 穷尽之后它没有调用者了（设计稿原本打算保留，落地时发现留着它就是在给下一次「二元分派」留一个入口） |

**落地结果**：`TableOptimizerJob` 里新增 `enum Format { PAIMON, ICEBERG, LANCE }` 与
`Format.parse()`，`run()` 里解析**一次**、失败即整批退出；三处分派全部改穷尽 `switch`；
`Plan` 的 javadoc 改成三值并写明「给了不认识的取值时要失败」。`steps()` 里的分派只是
「把 `LanceProcedures.Step` 搬进 `Step`」，因为三种格式的动作顺序与上报结构完全一样——
把执行流程也拆成三套，只会让「跳过 / 部分成功 / 耗时」这些共同语义各写一遍。

`Plan.format` 缺字段时回落到 `PAIMON` 这条规则**保留**（`table-optimization.md` §6.5 的理由：那是本特性引入前的唯一形状，老服务端没有这个字段）。但「显式给了 `LANCE` 却走进 Paimon 分支」必须消失——两者要分开：**缺字段回落**是兼容，**未知取值回落**是 bug。落地时这条分离写进了 `Format.parse()` 与它的用例（`formatParsingSeparatesMissingFromUnknown`）：缺字段/空串 → PAIMON，`lance`（大小写不敏感）→ LANCE，`HUDI` → 抛错并提示「upgrade the optimizer image」。

### 5.2 会话配置

```java
spark.sql.extensions                              = org.lance.spark.extensions.LanceSparkSessionExtensions
spark.sql.catalog.<catalog>                       = org.lance.spark.LanceNamespaceSparkCatalog
spark.sql.catalog.<catalog>.impl                  = rest
spark.sql.catalog.<catalog>.uri                   = <catalogUrl>/lance
spark.sql.catalog.<catalog>.parent                = <catalog>        // 第一级命名空间 = catalog
spark.sql.catalog.<catalog>.headers.Authorization = Bearer <catalogToken>
```

五条都要对上，逐条说：

- **`uri` 是 catalog API 基址 + `/lance`**。我们的 Lance 面挂在 `/lance/v1/**`（`lance-rest-catalog-design.md` §4.1），而 connector 自己拼 `/v1/...`。写错成 `…/lance/v1` 的表现是每个请求都 404，且 404 的 body 是我们自己的 `ErrorResponse`（路径没匹配到），很容易被当成「端点没实现」。
- **`parent` 就是 catalog 名**。它对应 REST 面的「id 第一级 = catalog」，与 Gravitino 示例（`.parent=lance_catalog`）同形。省了它，connector 会把 `sales` 当 catalog——于是 `OPTIMIZE sales.orders` 永远找不到表。
- **认证走 `headers.*`**，没有 Iceberg 那种 `.token` / `.credential` / `rest.auth.type` 三个配置键。我们把 catalog 令牌塞进 `Authorization: Bearer`。**待实测**：connector 是否接受自定义头覆盖（若不接受，就只能用 `x-api-key` 这类约定头，需要服务端认——这是抓包第一件事，§8 R1）。
- **`spark.sql.extensions` 的值按批次格式换**，与 Iceberg/Paimon 一样。因为一个批次只属于一个 catalog、一个 catalog 只有一种格式，不需要把三个扩展用逗号拼起来（那会让三个 connector 在同一个会话里抢同一个 parser 扩展点，失败模式无法预测）。
- **不设 `cache-enabled=false`**：那是 Iceberg 客户端的键，Lance connector 没有对应物。批内同一张表只被执行一次（一条语句），不存在「读到上一步之前的快照」的问题。

### 5.3 语句形状

新增 `LanceProcedures`，与另两份 `*Procedures` 同构（`steps()` + `statement()` + `Step` record），但渲染方式不同：

```java
// OPTIMIZE `demo`.`sales`.`orders` WITH (k = v, ...)
// VACUUM   `demo`.`sales`.`orders` WITH (k = v, ...)
```

三条渲染规则：

1. **三段名逐段反引号**。段内字符来自我们的 id 白名单（字母数字与 `_ - .`），`-` 在 Spark 未加引号标识符里非法；且表名可能撞保留字（`order`、`table`）。逐段包比整串包正确（整串包会变成单段标识符——与 Paimon `$files` 那次踩的是同一个坑的镜像：那边要**只包最后一段**，这边要**逐段都包**）。
2. **类型按选项逐个渲染**：整数/浮点裸写、布尔写 `TRUE` / `FALSE`。**不要**把布尔写成字符串 `'true'`——`WITH` 子句里字符串与布尔是不同字面量，写错报的是 `Cannot cast STRING to BOOLEAN`。
3. **不写分号、不写注释**。这两条对 `CALL` 是无害的，对解析器级命令是语法错。

`interval-*` 与 `delete-unverified` 不进语句（前者是调度参数、后者是常量）。这条与 Iceberg 侧的 `interval-hours` 处理一致：策略层有、执行侧不翻译。

### 5.4 指标读取

沿用现有的 `value(row, field)`（按名取、找不到按位置兜底）。之所以仍然要兜底：`fragments_removed` 这类列名在 connector 的不同版本里是否改过大小写，我们无法预先承诺；而**结果只有这四/两列**这件事是 `CompactionMetrics` 的结构决定的，短期内不会变。

`VACUUM` 的 `bytes_removed` 用 `humanSize()` 转成人类可读再入库，与 Paimon 的 `released-size` 同一口径（`table-optimization.md` §6 的「采集口径在任务侧定，一次采集到处展示」）。

### 5.5 失败信息

沿用 `StepFailure`（消息是 `<action> failed. SQL: <statement>`，语句排最前）。

这条在 Lance 上比另两种格式更必要：`OPTIMIZE` 的失败常常是**解析期**的（扩展没装、名字要反引号、选项名写错），而 Spark 的语法错消息只给位置不给原文。用户拿到「extraneous input '(' expecting ...」时，唯一能自证的就是那条被重放的语句。

### 5.6 镜像与 JVM 参数

三件事，都不在 Java 代码里：

| 项 | 做法 | 写错的后果 |
| --- | --- | --- |
| `lance-spark-bundle` | `pom.xml` 加依赖，`Dockerfile` 从 `target/jars/` 拷（沿用 `stripVersion=true` 的固定名） | `ClassNotFoundException: org.lance.spark.LanceNamespaceSparkCatalog`，整批失败 |
| `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` | **写在 `Dockerfile` 的 `ENV JAVA_TOOL_OPTIONS`** | 运行到打开数据集时才抛 `InaccessibleObjectException`，栈里没有一句话提到 Lance |
| 进程级 I/O 调优（`LANCE_IO_THREADS` 等） | 同上的 `ENV`，或执行集群的容器环境变量 | 只能在「Pod 内存被打满」时才有感知 |

第二条要强调：**它不能由 `SparkSession.builder().config(...)` 生效**。`--add-opens` 是 JVM 启动参数，进程起来之后再设没用；`spark.driver.extraJavaOptions` 只对**它自己 fork 的 driver** 有效，而我们的镜像是 `java -cp … TableOptimizerJob` 直接起进程，没有那层 fork。放在 `ENV` 里是唯一可靠的位置，且对 `java` 与将来可能的 `spark-submit` 都生效。

关于版本：connector 的 catalog 类名在 0.0.x 里是 `com.lancedb.lance.spark.LanceNamespaceSparkCatalog`，0.4.0 起是 `org.lance.spark.LanceNamespaceSparkCatalog`。**类名写错不会被编译发现**（它是个字符串），只在建会话时炸。因此镜像里 pin 一个版本、并在 `LanceProcedures` 的类注释里写清「这个类名对应哪个 connector 版本」。

---

## 6. 服务端

### 6.1 格式枚举与「二元分支」清单

`CatalogFormat` 加 `LANCE` 之后，真正的工作量不在新代码，而在**把既有的二元判断逐个改掉**。当前 checkout 里的二元分派点（`grep isIceberg` 的结果，逐个交代）：

| 位置 | 现状 | 加 `LANCE` 后必须怎么改 | 不改的后果 |
| --- | --- | --- | --- |
| `OptimizationOptions` 7 个重载（`defaults` / `catalogDefaults` / `resolve` / `validate` / `isKnownOption` / `knownKeys` / `interval`） | `isIceberg(format) ? iceberg : paimon` | 全部改 `switch`，`LANCE` 进第三支 | Lance 的策略读写会**用 Paimon 的键表校验**：`target-rows-per-fragment` 被判未知键 → 保存 400 |
| `OptimizationOptions` 的 `isIceberg(format)` 私有方法 | 布尔 | 保留，但只用于「不是 Paimon」的场合 | — |
| `TableOptimizationService.isOptimizableFormat` | `PAIMON || ICEBERG` | 加 `LANCE` | 策略页签整块报「只有 Paimon 与 Iceberg 支持」 |
| `TableOptimizationService.formatOf(TableEntity)` | `CatalogFormat.of(table.getTableType())` | `of()` 认不出时回落 `PAIMON`——**Lance 表会拿到 PAIMON** | 参数表用错（同第 1 行），且 `TablePolicy.format` 回 `PAIMON`，控制台渲染 Paimon 表单 |
| `OptimizationJobService` 构造 `Submission` | `format.wireName()` | 无需改（枚举自己带） | — |
| `OptimizationDispatcher` / `OptimizationBatchCreator` | 按 catalog 组批 | 无需改（不判格式） | — |

**`formatOf` 那一行是本设计最危险的一处**，因为它现在有注释写着「其余回落 Paimon」——那是一年前的正确行为（只有两种格式）。加了第三种格式后，回落的目标从「唯一可能的另一侧」变成了「一个错误答案」。修法不是改 `of()`（它还要给旧数据兜底），而是在优化链路上**先判空再判等**：认不出的格式一律 400 并指名，别让它悄悄走 Paimon。

**落地结果（这一行按上面的说法改了，但改法与设计稿不同）**：没有让 `formatOf` 变成「先判空再判等」，
而是拆成两个方法——

| 方法 | 语义 | 调用方 |
| --- | --- | --- |
| `recognizedFormatOf(TableEntity)` | 认不出返回 `null` | 读路径（`policy`）把 `null` 表达成「不支持 + 原因」；写路径直接 400 并指名取值 |
| `formatOf(TableEntity)` | 认不出就抛 400，**不再回落** | 只在已过可优化判定的路径上调用 |

`CatalogFormat.of` 本身一个字没动（它还要给 `catalog_format` 列的旧数据兜底）。
这样「回落」这件事只留在一个地方，而优化链路上没有任何一处能用上它。

另一处与设计稿的差异：`OptimizationOptions` 里的 `isIceberg(format)` 私有方法**已删除**，
7 个重载全部改成穷尽 `switch`。保留一个「用来判断不是 Paimon」的布尔，就是给下一次
「二元分派」留一个入口——而这次的 bug 正是那么长出来的。

`TableOptimizationService.isOptimizableFormat` 也加了 `LANCE`，且它现在与
`defaultEnabled` / `isSupported` 三者共用同一处「三种格式」的判断（`OptimizationOptions`）。

### 6.2 可优化判定

除格式外还要判两件事，两件都不能照抄 Paimon 的规则：

| 判定 | Paimon/Iceberg 的规则 | Lance 的规则 | 为什么不能照抄 |
| --- | --- | --- | --- |
| 表类型 | `table.getTableType()` ∈ {PAIMON, ICEBERG} | `LANCE` | — |
| **外部表** | `table.isExternal()` 为真则拒（「位置不归本服务所有」） | **不按 `isExternal` 拒** | Lance 的**所有**数据都是客户端写的（`CreateTable` 我们不做，见 `lance-rest-catalog-design.md` §3.2）。若沿用这条规则，Lance 表一张也开不了，而且 400 的文案在骗人：「位置不归本服务所有」听起来像权限问题，实际是规则不适用 |
| 位置可得 | 由 catalog 的 warehouse 推出 | **必须能从 `DescribeTable` 拿到 `location` + `storage_options`** | 拿不到就建不起会话。这条是运维前提，不是代码判定：应该在**启用策略时探一次**（`describe` 一次这张表），失败则 fail-closed 并说明「Lance 表的元数据面尚未就绪」 |

最后一条顺手解决了 `table-optimization.md` §3.4 那套「配置不可派发 ≠ 配置能提交」的一个盲区：那边探的是 k8s 与回调，这边要探的是**catalog 能不能把这行表描述出来**。

**落地时的差异：这条「启用策略时探一次」没有做。** 理由是它需要先知道 R1 第 1 条
（`storage_options` 到底走不走 vendor 下发）：探一次 `DescribeTable` 只能证明「元数据面在」，
证明不了「executor 能读到数据」；而一个只证明一半的探活，比「批次失败时带完整语句的
`error_message`」（§5.5，已落地）更容易误导。等 R1 有答案之后，这条探活应该按
「能不能建会话」来设计，而不是按「能不能 describe 到」。

### 6.3 API：0 个新端点，`ActionPolicy` 加两个字段

| 端点 | 变化 |
| --- | --- |
| `GET/PUT …/policy` | 响应里的 `format` 可能是 `LANCE`；`actions[]` 每项多 `supported` / `unsupportedReason` |
| `POST …/run` | body 里给 `orphan` → **400**，文案指名「Lance 不支持该动作，VACUUM 已包含」 |
| 其余（jobs / retry / cancel / 回调两端点） | 不变 |

```java
public record ActionPolicy(String action, String displayName, boolean enabled,
                           Map<String,String> config, Map<String,String> effective,
                           boolean usingDefaults,
                           boolean supported,          // 新增
                           String unsupportedReason)   // 新增
```

加在**动作级**而不是表级：表级的 `supported` 说的是「这张表能不能开优化」（已经存在），动作级的说的是「这一项在这种格式上存不存在」。两个粒度混成一个字段，控制台就得在「表不支持」和「动作不支持」之间猜。

### 6.4 数据模型：0 张新表

| 表 | 变化 |
| --- | --- |
| `lakehouse_table_optimization` | 复用。Lance 表的 `orphan_remove_enabled` 恒 `false`（读时算，不落库——落库会让「换格式」留下脏开关，而格式本来就不可变） |
| `optimization_job` / `_item` | 复用。`actions` 列最多出现 `["COMPACT","EXPIRE"]` |
| `lakehouse_catalog_execution_cluster` | 复用。**Lance 批次要用它**：`catalog_token` 变成 `Authorization` 头的值（§5.2），`image` 必须带 Lance connector（§5.6） |

第三条值得单独记一笔：Lance 是第一个**必须**用到 `catalog_token` 才能跑维护的格式吗？不是——Iceberg 也要。但 Lance 多一层：`storage_options` 由 namespace 在 `DescribeTable` 里下发，而 executor 能不能拿到它取决于 connector 版本（§8 R1）。在确认之前，catalog 级执行集群配置里要预留「静态 storage 凭据」的位置（`spark.sql.catalog.<c>.storage.*`），**只在 vended 凭据验证失败时启用**，并且默认关闭——静态凭据是长命密钥，能不用就不用。

### 6.5 组批时的那次预览（复用 P1 的只读版本端点）—— **未落地**

```
出批前（仅 LANCE 的 expire 动作）：
  ListTableVersions(prefix=catalog, id=catalog$schema$table, limit=N)
  → 数出 "timestamp < now - retentionHours" 的版本数
  → 写进 item 的描述/指标：预计清理 N 个版本
```

三条约束：

- **失败不影响出批**。数不出来（端点还没实现、分页没翻完、网络抖动）就写「未预估」，照常出批——因为这只是提示，不是判定。
- **不用 `DescribeTableVersion` 逐个拉**（那是 N 次请求）。`ListTableVersions` 一次给版本号 + 时间戳就够了。
- **只在 expire 启用时做**，且只在有 `retention-hours` 的表上做。compact 不需要预览（它不改数据内容，且空跑不提交）。

**落地的取舍：这一条本轮没做，控制台上的「预计清理 N 个版本」也就没有。** 原因是它要求
服务端的组批路径在**出批那一刻**用 catalog 的令牌反向调自己这一面的
`/lance/v1/namespace/{ns}/table/{id}/versions`——一条「服务端调自己」的跨面依赖，
而它的产物只是一句提示。在有一台真 Lance 仓库能验证分页与时间戳之前，加这条依赖
换来的风险（出批变慢、失败路径多一条）大于收益。

替代做法是把不确定性写进 UI：`VACUUM` 的保留时长字段下方说明「填得比 catalog 的最大
提交间隔小会被服务端抬高」，确认框里写「早于 X 小时前的旧版本」（X 取当前生效值），
不承诺「具体几个版本」。等 §6.5 做出来时，那句话升级成「预计清理 N 个版本」即可。

**已落地的替代物**（本轮）：`LanceOptimizationOptions#enforceRetentionFloor` —— 出批时把
`retention-hours` 抬到不低于 catalog 的 `optimization.expire.max-commit-interval-hours`
（默认 24），方向是「少删」，即安全的那一侧。它比预览更值得先做：预览只影响「用户心不心疼」，
下限影响「会不会删到正在写入的文件」。

---

## 7. 控制台

### 7.1 三张卡，第三张置灰（已落地）

沿用 `TableOptimizationPanel`，不新做组件。落地后变化的清单：

| 处 | 变化 |
| --- | --- |
| 卡片渲染 | `actionSupported(spec)` 读服务端 `ActionPolicy.supported`（响应还没到的那一帧回落字段表里的静态位）；`false` 的卡压暗一档、勾选框禁用、标题旁带「不可用」标签、标题下写 `ActionPolicy.unsupportedReason` |
| 档位与保存 | 不支持的动作**不给**「默认设置 / 自定义」与保存按钮——留一个点了就 400 的按钮，比不给更糟（用户会以为是参数填错了） |
| 参数区 | 不支持的动作连参数区一起收起来（没有参数可填，显示一排「—」只是噪音） |
| 历史表 | 不支持的动作不渲染历史表：它永远不会有记录，一张永远空的表只是噪音 |
| 指标展示 | `metricSummary()` 认得 §4.5 的新键（`fragments-removed` 等），见 §7.4 |

**为什么不直接少渲染一张卡**：一致性。同一个面板在三种格式下长得一样，用户学一次就够；而且「Lance 没有孤儿清理」这件事本身是**需要被知道的**——把它藏起来，用户会去别处找，或者以为我们漏做了（这个能力是 DLF/AWS 都有的三项之一）。置灰 + 一句原因是唯一同时满足「不用学新界面」与「说得清边界」的做法。

### 7.2 `LANCE_FIELDS` + `actionsFor` 改三元（已落地）

改动之前，`lakehouse-rest-console/src/utils/optimization.js` 里是：

```js
export function actionsFor(format) {
  return format === 'ICEBERG' ? ICEBERG_ACTIONS : ACTIONS   // ← LANCE 会落到 Paimon
}
```

这是**前端版的 `isIceberg()` 陷阱**（§5.1），后果是渲染 Paimon 的表单：多出 `min-input-files`、`retain-min`、`dry-run`，少了 `target-rows-per-fragment`。用户填完保存，服务端（改好之后）用 Lance 键表校验 → 400「Unknown compact option: min-input-files」。**前端与后端两处都改，缺一处就是「表单填不了 / 填了存不下」的循环**。

现在的实现：

```js
export function actionsFor(format) {
  if (format === 'ICEBERG') return ICEBERG_ACTIONS
  if (format === 'LANCE') return LANCE_ACTIONS
  return ACTIONS
}
```

保留「认不出 → Paimon」的兜底（与服务端 `CatalogFormat.of` 的语义一致），但 `LANCE` 是显式的一支。这一条现在有两道闸：`tools/optimization-tests` 里「LANCE 不再落到 Paimon」与「认不出的格式与缺省都按 Paimon」两条用例（`npm test`），加上 `scripts/verify-console.py` 第 11 组把三份字段表与服务端三份 `KNOWN_KEYS` 逐字比一遍、并断言两两不相等。

`LANCE_FIELDS` 的两处结构差异（不在别的格式里出现）：

- `orphan` 是**空数组**——不是缺这一项。动作名是格式无关的契约；
- `delete-unverified` 是 `disabled: true` 的开关——键在表里（键表要逐字一致），但锁在 `false` 上。

### 7.3 不可逆提示替代干跑（已落地）

Paimon/Iceberg 的孤儿清理有「干跑预览 → 确认」两步。Lance 没有 dry-run，于是：

| 项 | 现状（Paimon/Iceberg） | Lance |
| --- | --- | --- |
| 交代位置 | 关掉干跑的开关下方一条 `TipAlert` | **两处**：勾上 expire 时卡片内一条 `TipAlert`（写清「删掉的版本无法恢复」）；点「立即优化」时确认框标题直接是「立即优化（含不可撤销的 VACUUM）」 |
| 确认按钮 | 「立即优化」 | 「确认执行（不可撤销）」 |
| 说明段 | 批次节拍与取消方式 | 再加上「将删除早于 X 小时前的旧版本及其文件，时间旅行窗口会随之缩短，且无法恢复」（X 取当前生效的 `retention-hours`，不承诺具体版本数，见 §6.5） |
| 预览来源 | 语句 `dry_run=true` 的结果 | **没有**（§6.5 未落地） |
| 强制约束 | `older-than-hours` 有最小值 1 | `retention-hours` 最小值 1、默认 72，且由服务端抬到 ≥ catalog 的最大提交间隔 |

「不可撤销」四个字出现在确认框**标题**里，不只放在说明段——这是这整套能力里唯一一个**点了就回不去**的按钮，而说明段是可以被跳过的。

### 7.4 指标展示

`metricSummary(item, action)` 加一组键映射（已落地）：

| 动作 | 展示 | 例 |
| --- | --- | --- |
| compact | `碎片 −{fragments-removed} / +{fragments-added}` · `文件 −{files-removed} / +{files-added}` | 碎片 −8 / +2 · 文件 −12 / +3 |
| expire | `清理 {old-versions} 个版本` · `释放 {bytes-removed}` | 清理 3 个版本 · 释放 1.2 GB |

与另两种格式的展示**不同构**（那边是「12 → 4」），这是数据本身决定的，不是忘了对齐（§1.3）。函数本身仍是「不判格式、只判键存在」——三套键在同一动作下互不重叠，一批里混着三种格式的表也不需要知道每张表是什么格式。

### 7.5 表详情页签的渲染条件（已落地其一，另一条**有意不做**）

- **独立页签还是并进详情页**：独立页签。面板本身（三张卡 + 三块历史）与详情页不是一个量级。
- **白名单**：`TableView` 的「存储优化」页签仍然只对 Paimon 表打开（Iceberg 表走 `IcebergTableView`、显式传 `format="ICEBERG"`），**没有**把 `lance-table` 加进去。这不是遗漏，两个理由：

  1. `options.type=lance-table` 是 Paimon 面给一个 Lance 目录做的**元数据包装**
     （`catalog-api-endpoints.md` 建表一节：「能建不能读写」），它不在 Lance 的 REST
     目录里；而执行侧是通过 `spark.sql.catalog.<c>`（`impl=rest`，指向
     `<catalogUrl>/lance`）解析表名的。让它进来只会把一张**永远跑不了**的表显示成
     「可以开」——比不给页签更糟。
  2. 真正的 Lance 表（`table_type=LANCE`，由 `LanceTableService` 登记）挂在 LANCE 格式的
     catalog 下。Lance 的目录浏览已经落地（`LanceBrowseView`，除建命名空间外只读，
     `lance-rest-catalog-design.md` §7），但它点开的是**详情抽屉**而不是表详情页——
     `table_type=LANCE` 的表今天没有 `TableView` 路由，因此到不了「存储优化」页签。

  面板本身对 Lance 已经完全就绪（`LANCE_ACTIONS` + 第三张卡置灰 + 指标口径），
  等 Lance 表详情页落地时，那一行加上 `|| isLanceTable` 就通了——`TableView.vue` 里
  这段判断上方的注释记着同一件事。
- 页签上「当前版本数」这类信息仍靠 `ListTableVersions`（`lance-rest-catalog-design.md`
  P1 已落地），与 §6.5 是同一个依赖。

---

## 8. 风险与未决

按「会不会让设计作废」排序。

### R1（门禁）三条必须实测的事 —— **仍未实测，已用可验证的替代办法绕开**

在目标镜像上跑一张真表，只回答三个问题，**答案决定后面所有细节**：

| # | 问题 | 为什么是门禁 | 若答案是否 | 落地时的处理 |
| --- | --- | --- | --- | --- |
| 1 | `DescribeTable` 的 `storage_options` 会不会被 connector 传给 executor？ | 若不会，S3 仓库上 executor 读不到数据，`OPTIMIZE` 只能靠静态凭据 | 执行集群配置要加静态 `storage.*`（§6.4），且要在 UI 上明示「这是长命密钥」 | **未实测**（手里没有真 S3 仓库 + 真 connector）。会话里已经留了静态凭据的位置（`spark.sql.catalog.<c>.storage.*`），默认不写 |
| 2 | `OPTIMIZE` 在 driver 跑还是切成 Spark task 分布式跑？ | 决定 Pod 资源画像与单次工作量上限 | 按「单进程」保守配置 | **未实测**。而原本用来兜这件事的 `max_source_fragments` 也**不存在**（§4.1），因此策略层的对策只剩「目标碎片行数 + `batch-size`」两个间接旋钮；真跑之前，这一条在文档里保持「未知」而不是假装有答案 |
| 3 | 自定义 `headers.Authorization` 会不会被接受？ | 决定认证怎么配（§5.2） | 认证面要改，属于 `lance-rest-catalog-design.md` 的接口变动 | **按「接受」实现**：`spark.sql.catalog.<c>.headers.Authorization=Bearer <catalog_token>`。服务端侧这条头是标准的 `Authorization`（与 Paimon/Iceberg 面同一套过滤器），风险在 connector 是否原样透传 |

三条都**不需要写代码**就能答：起一个装了 connector 的 `pyspark`，手工建会话、跑一条 `OPTIMIZE`，开 debug 日志看请求与 task 分布。这也是 `lance-rest-catalog-design.md` §8 那条「P0 第一步是抓包，不是写代码」的同一件事——只是这次抓的是**优化任务的位置**。

**为什么可以带着这三条未决就落地**：它们影响的是「跑起来顺不顺」（凭据怎么来、资源够不够），
不影响「跑得对不对」（语句形状、参数键表、指标口径）。后者已经用真 connector 的
lexer/parser 钉死在 `LanceProceduresTests` 里，是确定的；前者在第一次真跑时就会暴露，
且失败信息落在 item 的 `error_message` 上，不需要改表结构就能观察。

### R2（依赖）`/lance/v1` 未落地 —— **已解除**

P0 的 13 个端点已落地（`lance-rest-catalog-design.md` §7「已落地」），`DescribeTable` 在、
`parent` 对应的命名空间在，因此 `spark()` 那五条会话配置与 `OPTIMIZE` 的表名解析都有依据了。

仍未验证的是**它们连起来跑通**（R1），而那是环境的事，不是端点的事。

### R3（产品）`VACUUM` 不可逆且无干跑

补偿方案见 §3.4，但三个补偿都**不是等价替换**：

- 窗口保守只能降低概率，不能防住「用户把窗口改成 1 小时」；
- 窗口下限（catalog 的 `max-commit-interval-hours`）能防住「删到正在写入的文件」，防不住「用户把 catalog 的下限也改小」；
- 标签保护只在用户打了标签时生效；
- 版本计数预览**未落地**（§6.5）。

**结论（已落地）**：Lance 的 expire 默认**关闭**（`OptimizationOptions#defaultEnabled` 里
`case LANCE -> action == Action.COMPACT`，与 Paimon 的 orphan 同为「有风险的那一项」），
开启与手动执行都要过一道确认框，且确认框标题里带「不可撤销」（§7.3）。

### R4（正确性）`VACUUM` 与写入并发

官方文档明写「VACUUM 可与读并发，但**不应与写并发**」。

我们的批次是同一张表顺序执行（compact 完再 vacuum），但**外部写入者不受我们控制**：用户可能正在往这张表 append。窗口（默认 72h）远大于任何合理的写入间隔，所以正常情况下安全；但这不是「设计保证」，是「参数保证」。因此：

- `retention-hours` 的下限不是 1 小时，而是**必须大于该表的最大提交间隔**——与 Paimon orphan 的护栏同一条理由（`table-optimization.md` §2.3）。差别是 Lance 没有「干跑」兜底，所以这条校验要从「提示」升级成硬校验。
- 需要一个新输入：catalog 级的 `optimization.expire.max-commit-interval-hours`（默认 24）。这是本设计唯一新增的 catalog 属性键。
- **落地时的取舍：写成「抬高」而不是「400」。** 保存时若填得比下限小，服务端在 `resolve`
  里把生效值抬到下限（`enforceRetentionFloor`），并在返回值里体现；不拒绝保存。理由是
  这个键的语义是「早于多久可以删」，抬高只会少删——方向安全；而 400 会让「运维事后把
  下限调大」变成所有已存策略的保存失败（它们存的时候是合法的）。代价是用户填 24 而页面
  显示 48 时可能困惑，因此控制台在字段说明里写明这条规则。

### R5（兼容）connector 版本、类名、Spark 版本

| 项 | 事实 | 应对 |
| --- | --- | --- |
| 类名 | `com.lancedb.…`（≤0.0.x）→ `org.lance.spark.…`（0.4.0+） | 镜像 pin 版本；类名写进类注释；建会话失败时错误文案点名「检查 connector 版本」 |
| Spark 版本 | bundle 按 `3.5_2.12` / `4.0_2.13` 分开发布 | 优化任务镜像与 `lakehouse-rest-spark` 的 Spark 版本对齐（现有约定，不引第二套） |
| 选项名 | 随软件版本变（`defer_index_remap` 在 7.0.0 被判无效） | 语句仍然走 SQL，让目标镜像解释；失败信息带语句（§5.5） |

### R6（性能）compact 会改 Row Address

有索引的表跑 compact，Commit 阶段要做 Remap。`defer_index_remap=false`（我们的默认）意味着**立即 Remap**：Commit 变慢、Driver 内存占用升高。

Volcengine 那篇把这类问题归到「Head OOM」，并提醒一件事：**即使表上没有索引，某些版本仍会构造 Row Address 映射，分布式执行器还可能一次收集大量 RewriteResult**——所以「没有索引就不会有 Driver 内存问题」是错的推论。

**设计稿原本的对策（`max_source_fragments` 默认 200，给一次 Commit 的 RewriteResult 数量一个上界）
在落地时失效了**：那个旋钮在 Spark 侧不存在（§4.1）。现在的对策只剩三个，都是间接的：

| 对策 | 怎么用 |
| --- | --- |
| `defer-index-remap=true` | 有索引的大表打开它：立刻见效，代价是索引在一段时间里指向旧位置 |
| `batch-size` 调小 | 只在真 OOM 时用；给值会按字节截断输出，可能产出坏碎片（§4.1），所以它不是「随手调」的旋钮 |
| 目标碎片行数调小 | 一次处理的数据量随之变小，也意味着「碎片多的表要跑多轮才收敛」——这个取舍现在没有显式旋钮，只能靠目标值间接表达 |

这条是本文**落地后仍然开放**的风险：R1 第 2 条（driver 还是 executor）一旦有答案，
要么 upstream 把 `max_source_fragments` 接出来（那时加回键表），要么我们自己在服务端
把一张大表拆成多批（改的是组批而不是语句）。

### R7（口径）指标没有 before/after

见 §1.3。这是**不可消除**的差异：要么如实报增量，要么自己算一个 before（从哪算？没有系统表）。选择如实报，并在控制台文案里说清「Lance 只报增量」。

---

## 9. 机械校验与验收（已落地）

| 项 | 做法 | 结果 |
| --- | --- | --- |
| 参数键一致性 | `scripts/verify-console.py` 第 11 组比对三套（`OptimizationOptions.KNOWN_KEYS` / `IcebergOptimizationOptions.KNOWN_KEYS` / `LanceOptimizationOptions.KNOWN_KEYS`），并断言三套两两不相等、Lance 的 `orphan` 是空集 | ✅ 3 条全绿；脚本 170 → **171 项**，`README.md` 与 `docs/console.md` §5.2 的数已同步 |
| 语句形状 | `LanceProceduresTests`：把生成的每条语句**喂回 connector 自己的 lexer/parser**（复刻 `UpperCaseCharStream` 那一层），断言能解析 | ✅ 10 条。真正踩到的坑写进了断言：不套 `UpperCaseCharStream` 时小写字母全是 token recognition error |
| 参数翻译 | `LanceProceduresTests`：compact 五个恒出现 + `batch_size` 缺省不出现、配置后出现；`interval-hours` 永不出现；`VACUUM` 两个常量恒 `FALSE`/`TRUE`；`before_timestamp_millis` 是 epoch 毫秒；浮点必带小数点；逐段反引号；未知键与 `orphan` 直接失败 | ✅ |
| 服务端键表 | `OptimizationApiTests`：Lance catalog 的策略读写（默认值、未知键 400、`orphan` 保存 400、三层合并、`max-source-fragments` 被拒） | ✅ 16 条 |
| 执行侧分派 | `TableOptimizerJobTests`：`Format.parse` 把「缺字段 → Paimon」与「未知取值 → 抛错」分开 | ✅ 11 条（optimizer 合计 49 条全绿） |
| 控制台 | `npm test` 的 `tools/optimization-tests`：`actionsFor('LANCE')` 走第三支、Lance 两套键、第三张卡静态不可用、指标口径 | ✅ 7 条（合计 60 条） |
| 端到端 | `e2e-lance-optimize.sh`（写入 → 造小碎片 → `OPTIMIZE` → `VACUUM`） | ❌ **未做**：需要真 Lance 仓库 + 带 connector 的镜像（R1），本轮没有环境 |

端到端脚本是这份设计里**唯一能证明整条链路真的通**的东西——前面所有「未实测」都靠它收口。
在它跑通之前，`LanceProceduresTests` 能证明的是「语句对了」，不能证明「任务跑得成」；
这两个结论的差距就是 R1 那三条。

---

## 10. 需求清单（能做的）

### P0：接进链路（前置：`/lance/v1` 的 P0）

| 编号 | 需求 | 验收 | 状态 |
| --- | --- | --- | --- |
| L-OPT-1 | 三条门禁实测（§8 R1） | 三个问题各有明确答案 | ⚠️ **未实测**，用可验证的替代办法绕开（§8 R1 的处理列） |
| L-OPT-2 | `CatalogFormat.LANCE` + `OptimizationOptions` 七处 switch 化 + `isOptimizableFormat` | Lance catalog 的策略读写 200；`orphan` 保存 400 且文案指名原因 | ✅ |
| L-OPT-3 | `LanceOptimizationOptions`（键表 / 默认值 / 校验 / 区间） | 非法取值 400；键表 7 + 3 + 0 | ✅（`max-source-fragments` 已删，见 §4.1） |
| L-OPT-4 | `LanceProcedures`（语句翻译） | `LanceProceduresTests` 用真 lexer/parser 断言形状 | ✅ 10 条 |
| L-OPT-5 | `TableOptimizerJob` 三处二元分派改穷尽 switch + `spark()` 的 Lance 分支 | `Format.parse` 的两类失败分开；三处 `switch` 穷尽 | ✅ |
| L-OPT-6 | 镜像加 connector + `JAVA_TOOL_OPTIONS` | 容器里能建 Lance 会话 | ⚠️ 镜像已改（bundle + `extraClassPath` 前置 + `ENV JAVA_TOOL_OPTIONS`），**未在真集群上验证** |
| L-OPT-7 | 控制台 `LANCE_FIELDS` + `actionsFor` 三元 + 置灰卡 | 三张卡在 LANCE 下渲染正确；保存往返一致 | ✅（面板就绪，入口见 §7.5） |
| L-OPT-8 | `ActionPolicy` 加 `supported` / `unsupportedReason` | Lance 的 `orphan` 返回 `supported=false` 且带原因；另两种格式恒 `true` | ✅ |

### P1：把「不可逆」这件事做扎实

| 编号 | 需求 | 验收 | 状态 |
| --- | --- | --- | --- |
| L-OPT-9 | 出批前的版本计数预览（§6.5） | item 上有「预计清理 N 个版本」 | ❌ 未做，理由见 §6.5 |
| L-OPT-10 | `retention-hours` 的相对下限（§8 R4） | 小于 catalog 的 `max-commit-interval-hours` 时**抬到下限**（不是 400，理由见 §8 R4） | ✅ |
| L-OPT-11 | `e2e-lance-optimize.sh` | 写入 → compact → vacuum 三段断言全绿 | ❌ 未做（缺环境） |
| L-OPT-12 | 控制台不可逆确认流（§7.3） | 确认框标题含「不可撤销」 | ✅（「预计数」一项随 L-OPT-9 缺席） |

### P2：暂不做，理由先记下

| 项 | 为什么现在不做 |
| --- | --- |
| `OPTIMIZE INDEX` | 接口仍是 draft（§1.5） |
| 分布式 compact 的并行度调优 | 要先知道 R1 第 2 条（driver 还是 executor） |
| `LANCE_IO_THREADS` 等进程级调优 | 属于「Pod 内存被打满」时的应急手段，不是默认配置 |
| 按 `before_version` 而非时间窗清理 | 版本号是客户端视角的内部量，用户看不懂；时间窗与另两种格式同构 |

---

## 11. 与既有文档的关系

| 文档 | 关系 |
| --- | --- |
| [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) | **前置**。P0 的 13 个端点不落地，本文的执行侧无法验证；本文复用它的 P1 两个版本只读端点做预览（§6.5） |
| [`table-optimization.md`](table-optimization.md) | **母文档**。本文是它的第三个格式面；动作名、批次状态机、回调协议、并发闸门、`SKIPPED` 语义全部不变 |
| [`optimization-multi-instance-design.md`](optimization-multi-instance-design.md) | 不受影响：租约、CAS、收尾顺序都与格式无关 |
| [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) | 要补一条：Lance 批次的镜像必须带 connector；`catalog_token` 的用法从「Paimon token provider」扩到「Authorization 头」 |
| `docs/spark-sql-reference.md` | 不涉及：那是 Paimon 的 SQL 语法参考。Lance 的 `OPTIMIZE` / `VACUUM` 语法在上游文档，不在我们这 |

一条总的判断：**这套能力的成本比 Iceberg 那次低，风险比它高。** 低成本是因为复用到底（0 张新表、0 个新端点、执行侧只加一个 `*Procedures`）；高风险是因为 `VACUUM` 是三个格式里唯一「一条语句同时删版本和文件、且没有干跑」的维护操作——所以 P0 里凡是与「不可逆」相关的项都不是可选项：已落地的 L-OPT-8（动作级 `supported`）、L-OPT-10（窗口下限）、L-OPT-12（不可逆确认）撑住了「不会悄悄删」；**未落地的 L-OPT-9（版本计数预览）与 L-OPT-11（端到端脚本）是这套能力目前最大的两个缺口**——前者影响「用户心不心疼」，后者影响「我们敢不敢说它能跑」。
