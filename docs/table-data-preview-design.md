# 表数据预览（不依赖外部查询引擎）技术方案

> 状态：**设计稿，未实现**。配套效果图：[`table-data-preview-mockup.html`](table-data-preview-mockup.html)（浏览器直接打开，右上角可切换四种演示状态）。
>
> 关联文档：[`console-table-detail.md`](console-table-detail.md)（表详情页现状）、[`authorization.md`](authorization.md)（判权链）、[`event-framework.md`](event-framework.md)（事件模型）、[`catalog-api-endpoints.md`](catalog-api-endpoints.md)。

---

## 1. 目标与非目标

**目标**

1. 表详情页新增「数据预览」页签：不装引擎、不配客户端，直接看到这张表的真实数据行。
2. 支持**简单条件过滤**（列 + 算子 + 值，AND 组合），过滤对分区表要在**计划阶段**裁掉文件，而不是读完再筛。
3. Paimon 与 Iceberg 两种格式走**同一个端点、同一个前端组件**——格式差异只在服务端的一个分派点上。
4. 全程诚实地表达「这是抽样预览」：读过几个文件、几行、多少字节都写在界面上。
5. **分区表默认只看一层分区切片**（默认最新，见 §7）：条件是自动加的、界面上可见、可一键改成「全部分区」或换一个分区。

**非目标**（第一版明确不做，避免"看起来像查询引擎"）

| 不做 | 原因 |
| --- | --- |
| SQL 输入框（`select … where …`） | 引入 SQL 解析器就等于在控制台里开一个查询口子：算子、函数、JOIN、注入面全部要重新守一遍。结构化过滤器能覆盖 90% 的"看一眼数据"诉求 |
| JOIN / 聚合 / ORDER BY | 这已经是查询引擎的职责。用户可以点「数据访问」拿到连接信息去用 Spark |
| 导出 / 下载结果集 | 导出走「文件列表」的下载（物理文件视角），预览只负责看 |
| 全表精确扫描 | 没有 LIMIT 的请求一律拒绝（见 §9）。"预览"承诺的是抽样，不是正确性 |
| 行级权限 / 列级脱敏 | 需要权限模型先支持谓词与列遮蔽（方案见 [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md)）。第一版靠 TABLE 级 `SELECT` 把门守住 |

---

## 2. 现状与动机

表详情页现有七个页签（[`console-table-detail.md`](console-table-detail.md) §1.1）：详细信息 / 快照 / 标签 / 分支 / 文件列表 / 数据访问 / 存储优化。这些页签有一个共同点——**全是元数据视角**：

| 已有的 | 它回答的问题 | 缺的那一问 |
| --- | --- | --- |
| 详细信息 · 字段列表 | 这张表有哪些列、什么类型 | 列里的值长什么样 |
| 文件列表 | 目录里有哪些 parquet 文件、多大 | 这些文件里是哪几行 |
| 快照 | 第 n 次提交改了什么 | 提交之后数据对不对 |
| 数据访问 | 用 Spark 连上来该填什么 | （要另外起一个引擎才能看到数据） |

最后一列就是本方案要消除的一步：**「表里到底有没有数据、某个字段的值是不是脏的」是排障时最高频的一问**，而现在唯一答案是"你去起个 Spark"。

---

## 3. 方案选型

四个候选，逐个说清为什么不选。

### 候选 A：用各格式自己的读路径（**采纳**）

| 格式 | 读法 | 依赖 |
| --- | --- | --- |
| Paimon | `FileStoreTable#newReadBuilder()` → `newScan().withFilter(predicate).plan().splits()` → `newRead().createReader(split)`，得到 `RecordReader<InternalRow>`（分区条件还可走 `withPartitionFilter`，§7.9） | `paimon-core`（**已有**）+ `paimon-format`（新增） |
| Iceberg | `TableScan.planFiles()` 取 `FileScanTask`，逐个文件读：`GenericDeleteFilter`（删除抵扣）+ `FormatModelRegistry`（parquet 读取与分区常量注入）——**不整表交给 `IcebergGenerics`**，理由见 §14「落地时的两处偏离」 | `iceberg-data` + `iceberg-parquet`（自带 `parquet-avro`，新增） |

**优点（决定性的那条）**：读到的东西与引擎读到的一致。Paimon 的主键表是 merge-on-read（要叠 delete 文件），Iceberg 有 position/equality delete——这些语义**只有格式自己的 reader 会处理**。预览如果显示"已经删掉的行"，比不显示更糟：用户会拿它当"数据是脏的"的证据去排查一个根本不存在的故障。

第二条优点：分区裁剪与谓词下推是格式自带的（`withFilter` / `Expressions`），不需要我们自己实现一遍文件级裁剪。

**代价**：两套 reader 的适配层；jar 体积增加约 34 MB（见 §13）。

### 候选 B：统一用 `parquet-avro` 直读文件（否决）

一个 reader、体积最小，但它只认"parquet 文件"，不认"表"。主键表/upsert 表读出的是**逻辑删除之前**的行；Iceberg 的 delete 文件被完全忽略。**否决理由：会给出错误的答案**，而且错得很像真的。

### 候选 C：嵌入式 DuckDB（否决）

`duckdb` 读 parquet 又快又能下推谓词，看起来很合适。三个否决点：

1. 同上——它只认文件，不认表语义（幽灵行问题一样存在）；
2. 引入平台相关的 native 库（linux/amd64、linux/aarch64、macOS 各一份），Dockerfile、镜像体积、CI 矩阵都要跟着变；
3. 它在控制台里就是一个**完整的 SQL 引擎**。一旦存在，「预览」与「查询」的边界就守不住了——下一步必然是"顺手支持一下 group by"。

### 候选 D：把预览请求交给 k8s 执行器（否决）

项目里已有 `paimon-rest-optimizer` 那套提交路径（优化任务）。复用它不用新增依赖，但那是**批处理**路径：起 pod、拉镜像、等调度，冷启动几十秒。预览是交互式读，RTT 就是体验本身。

### 结论

**A**。分派点放在现有的 `CatalogFormatResolver`——控制台已经按它决定"这个 catalog 走哪套协议面"，预览只是同一件事的第三个消费者。

---

## 4. 架构与一次预览的数据流

```
浏览器（TablePreviewPanel.vue）
   │  POST /api/console/v1/tables/{tableId}/preview   { partition, filters, columns, limit, cursor }
   ▼
TablePreviewController ──► AuthorizationService.require(… TABLE … SELECT …)
   │
   ▼
TablePreviewService
   ├─ 解析表 → (catalogFormat, 物理路径, 当前快照/元数据)
   ├─ 校验请求（列存在性、算子与类型匹配、限额、分区值）→ 规范化 filters
   ├─ 解析分区（§7）──► PartitionPlanner：枚举 → 取最大值 → 按文件数级联 → 回退
   │                       └─ 每级都向格式要一次 plan，只数文件、不读数据
   ├─ 按格式分派 ──► PaimonPreviewReader  |  IcebergPreviewReader
   │                    │                        │
   │                    └──── StorageFileIoFactory（已有：凭据管理器 + FileIO 选型）
   ├─ 逐行读 → 应用残留谓词 → 凑够 limit 就停（不读完）
   ├─ 采样统计（文件数 / 字节数 / 行数 / 耗时）
   └─ 审计事件 table.previewed（异步，值不落库）
   ▼
200 { partition, columns, rows, cursor, hasMore, scanned, sampled, snapshotId, warnings }
```

一次请求的固定步骤与代价（N = 表规模，与响应大小无关）：

| # | 步骤 | 代价 |
| --- | --- | --- |
| 1 | 读表元数据（DB）+ 校验 | 一次索引查询 |
| 2 | 解析分区：枚举 → 取最大值 → 按文件数级联（§7.3） | 只读清单，几十 ms × ≤3 级 |
| 3 | 规划 split（分区条件 + 可下推的列条件一起交给格式） | 与**作用域内文件数**成正比，与行数无关 |
| 4 | 打开 ≤ `maxFiles` 个数据文件 | 对象存储 RTT × 文件数 |
| 5 | 逐行读，凑够 `limit` 就 `break` | 与返回行数成正比，**不读完整文件** |
| 6 | 采样统计 + 审计 | 常数 |

关键设计：**始终「读够就停」**。哪怕这张表有 10 万个文件，一次预览也只打开前 K 个、每个只读到凑够行为止。

---

## 5. API 契约

```
POST /api/console/v1/tables/{tableId}/preview
Cache-Control: no-store
```

**为什么是 POST 而不是 GET。** 三个理由：过滤器是结构化数组（`>=`、日期、中文值放进 query 要转义，长度不可控）；请求语义上是"读一批数据"，会写审计事件；游标是不透明长串。GET 的缓存语义在这里全是负担，所以显式 `no-store`。

### 5.1 请求

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `filters` | array | 否 | 条件数组，**AND** 连接；最多 5 个 |
| `filters[].column` | string | 是 | 列名，必须是当前 schema 里的列（大小写敏感） |
| `filters[].op` | enum | 是 | `eq` `ne` `gt` `ge` `lt` `le` `contains` `startsWith` `isNull` `isNotNull` |
| `filters[].value` | string/number/bool | 条件非 null 判定时 | 与列类型强校验，不做隐式转换 |
| `columns` | string[] | 否 | 投影列；缺省 = 全部列（UI 默认勾前 30 列，见 §12） |
| `limit` | int | 否 | 本页行数，默认 100、上限 200 |
| `cursor` | string | 否 | 上一页返回的游标；给了它就忽略 `filters`/`columns`/`partition`（三者都已绑进游标） |
| `partition` | object | 否 | 分区范围。缺省等于 `{ "mode": "auto" }`，即自动锁最新分区（§7.5）。**与 `filters` 是两个通道**：不要用往 `filters` 里塞分区字段的方式来"锁分区"，理由见 §7.5 |

### 5.2 响应

```json
{
  "snapshotId": 8,
  "partition": {
    "mode": "auto",
    "resolved": [{ "field": "dt", "transform": "identity", "value": "2026-10-21" }],
    "label": "dt = 2026-10-21（最新分区）",
    "auto": true,
    "cascadeDepth": 1,
    "fallback": null,
    "partitionsTotal": 37,
    "partitionsMatched": 1
  },
  "columns": [
    { "name": "id", "type": "INT NOT NULL" },
    { "name": "name", "type": "STRING" },
    { "name": "payload", "type": "STRING" }
  ],
  "rows": [[1, "alice", "{\"a\":1}"], [2, null, null]],
  "cursor": "eyJzIjoxLCJ4IjozLCJyIjo0Mn0.hmac",
  "hasMore": true,
  "scanned": { "filesRead": 3, "filesInScope": 12, "filesInTable": 128, "bytesRead": 12582912, "rowsRead": 420, "elapsedMs": 42 },
  "sampled": true,
  "warnings": ["只读了最近 3/12 个文件（本分区），全表 128 个"]
}
```

`cursor` 为 `null` 表示读完了（`hasMore=false`）。`rowsRead` 与 `rows.length` 的差就是被过滤掉的行数——它是"过滤器真的生效了"的唯一证据，界面上要显示（见 §12）。

`scanned` 里三个文件数必须分开：`filesInTable` 是整张表、`filesInScope` 是分区裁剪后的候选集、`filesRead` 是真正打开的数量。把它们合并成一个 `filesTotal`（旧稿的写法）等于把"分区已经把范围缩小了"这个信息丢掉——用户看到 `3/128` 会以为采样很激进，实际是 `3/12`。

### 5.3 错误码

| 状态 | 场景 | 前端表现 |
| --- | --- | --- |
| 400 | 列不存在 / 算子与类型不匹配 / `limit` 越界 / 过滤条件 > 5 个 | 就地红字标在过滤条上，不整页报错 |
| 400 | 表格式不支持预览（非 parquet/orc/avro 存储） | 页签内空态 + 一句"这张表的数据格式是 X，预览暂不支持" |
| 400 | `partition.values` 里的字段不是分区字段，或 `transform` 与该表的 spec 不符 | 红字标在分区控件下 |
| 400 | 请求的分区值在该快照里不存在（如 `dt=2024-02-30`） | 「这个分区不存在；当前快照共有 37 个分区，最新是 2026-10-21」+ 「打开分区列表」。**不做静默 0 行**（§6.3 同一条原则） |
| 403 | 缺 TABLE 级 `SELECT` | 空态 + 「申请权限」入口（跳权限管理页，带上资源路径） |
| 404 | 表 / 快照不存在，或快照已被清理 | 空态 + 刷新按钮 |
| 409 | 游标对应的快照已不是当前快照（期间发生了 compaction / 覆盖写） | 黄条「表在预览过程中又提交过，结果已重置」并自动重取第一页 |
| 429 | 同一主体并发预览超限 | 「正在预览中，请稍后」 |
| 504 | 超过 `timeoutMs` | 「这次预览超时（5s），换一个更小的分区或加一个过滤条件」+ 保留已加载行 |

---

## 6. 过滤语义

### 6.1 算子表（按列类型给候选，不给不该出现的算子）

| 列类型 | 可用算子 |
| --- | --- |
| 数值（INT/BIGINT/DECIMAL/DOUBLE/FLOAT） | `eq` `ne` `gt` `ge` `lt` `le` `isNull` `isNotNull` |
| 字符串 / 二进制以外的字符类型 | 上述 + `contains` `startsWith` |
| DATE / TIMESTAMP | `eq` `ne` `gt` `ge` `lt` `le` `isNull` `isNotNull`（值用日期选择器，不发裸字符串） |
| BOOLEAN | `eq` `isNull` `isNotNull` |
| 复杂类型（ARRAY/MAP/ROW/BINARY） | 仅 `isNull` `isNotNull` |

`contains` / `startsWith` 只做**后缀匹配语义明确**的实现（`startsWith` 可下推为前缀谓词；`contains` 只能逐行判定）。

### 6.2 三段式执行：分区 → 裁文件 → 筛行

1. **分区阶段**：`partition` 通道（自动或显式，见 §7）解析成一组分区值，交给格式自己的扫描入口（Paimon `withPartitionFilter` / Iceberg `TableScan#filter`）。128 个文件在这一步被裁到 12 个，**而且 delete 文件由格式自己装配**（§7.9 的硬约束）。
2. **裁文件阶段**：`filters` 里命中 **identity 分区字段**的等值 / 范围条件（Paimon 的分区列就是普通列）也下推进 scan，与上一步合并成同一个谓词。
3. **筛行阶段**：非分区列的条件、以及 `contains` 这类无法下推的，在 reader 循环里逐行判定。

> 一个不对称要记住：Iceberg 的 `day(ts)` / `bucket(id)` 这类**非 identity 分区**，源列上的条件（`ts >= '2026-10-21 08:00'`）**不能**直接反推成分区谓词——除非自己实现 `Transforms` 的区间映射。第一版不做这个映射：这类条件只走第 3 步逐行判定，界面上也不声称"已下推"。

> 为什么不在文件级用 Parquet 统计（min/max）跳文件：那需要解析每个文件的 footer，代价与"直接读这个文件的前几行"接近，而收益只在"值域稀疏"时才明显。列为 M3（§14），第一版不做，也不在界面上暗示做了。

### 6.3 值类型强校验

不隐式转换：给 `INT` 列传 `"abc"` 是 400，而不是"筛出 0 行"。**静默的 0 行会被读成"表里没有这个值"**，这类误判比报错贵得多。

---

## 7. 分区：默认预览「最新分区」

> 一句话结论：分区表的第一次预览**不是**"读整张表的前 100 行"，而是"读最新那个分区的前 100 行"。这个条件是**自动加的、写在界面上的、一键可改的**——三者缺一，用户就会把"一个分区的样子"当成"整张表的样子"。

### 7.1 为什么默认必须带分区条件

现有采样策略是"按文件写入时间倒序取前 K 个"（§9）。放到分区表上，它有一个不容易发现的问题：**这 K 个文件会跨分区**。

| 现象 | 后果 |
| --- | --- |
| 一个 `dt` 分区 12 个文件，`maxFiles=8` | 候选集常常落在 2~3 个 `dt` 上，页面上 `dt` 列的值会跳变 |
| 用户看到 `dt` 从 `2026-10-21` 跳到 `2026-10-18` | 第一反应是"表里数据乱了 / 分区写错了"，去查一个不存在的问题 |
| 每个分区都只读前几个文件 | 哪个分区都没看全，"看最新数据"这个最常见的诉求反而没满足 |

连带两个代价：`maxFiles` 的预算被摊薄到多个分区；"只看最新数据"要靠用户手写条件。

所以：**分区表默认把读取范围收窄到一层分区切片**，并在响应里回显这个选择（§7.5）。收窄之后，`scanned.filesInScope` 才是"这次预览的候选集"，与全表文件数分开表达（§9）。

### 7.2 「最新」怎么定义：先判断分区字段有没有序

"最新"并不是所有分区都能定义的。判据是**分区字段可不可排序**：

| 格式 / 分区形式 | 有序？ | 默认锁定 |
| --- | --- | --- |
| Paimon 分区（`partition` 选项里的列，identity） | ✅ | 取其**最大值** |
| Iceberg identity 分区 | ✅ | 取最大值（typed comparator，不是字符串比较） |
| Iceberg `year` / `month` / `day` / `hour` 分区 | ✅ | 取最大值（分区值是日期 / 时间） |
| Iceberg `truncate(N, col)` | ⚠️ 值可比，但语义是"值前缀桶" | 不参与自动锁定，可手动选 |
| Iceberg `bucket(N, col)` | ❌ 哈希，桶之间没有先后 | 不参与自动锁定 |
| 只有 bucket 分区的表 | ❌ | 不锁分区：`mode` 退化为 `all`，提示「该表按哈希分区，分区之间没有先后」 |

**typed comparator 不是细节。** `DATE` 是 int 天数、`TIMESTAMP` 是 long 微秒、`DECIMAL` 是 unscaled + scale；字符串比较会把 `10.5` 排到 `9.1` 前面。Iceberg 用 `Comparators.forType(spec.partitionType().fields().get(i).type())`，Paimon 用 `BinaryRow` 对应 `RowType` 字段类型的比较器——**两边都不许 `toString()` 之后再比**。

**"最新"这个说法只给时间族用。** 判定条件：字段类型是 `DATE` / `TIMESTAMP` / `TIMESTAMP_LTZ`，或字段名命中 `dt` / `ds` / `date` / `day` / `hour` / `month` / `year` 一类**且**值严格匹配 `\d{4}-\d{2}-\d{2}`、`\d{4}-\d{2}-\d{2}[ T]\d{2}`、`\d{8}`、`\d{6}` 之一。满足才写「最新分区」；否则文案写「最大分区值」——在 `region` 分区上说"最新"就是在编。

**null 分区**：Paimon 的 `partition.default-name`（默认 `__DEFAULT_PARTITION__`）、Iceberg 的 null 分区值。排序时 null **不参与**最大值候选（等价 `NULLS LAST`）；只有全部值都是 null 时才选它，文案写「默认（空值）分区」。

### 7.3 多级分区：按文件数预算级联锁定

多级分区（`dt` / `hour` / `region`）既不能"全锁"，也不能"只锁一级"。规则如下（给定快照完全确定）：

1. 按分区字段声明顺序，从第一个**有序**字段开始，锁成 `= 最大值`；遇到无序字段跳过。
2. 交给格式的 scan 规划一次（只读清单），数出当前锁定条件下的文件数 `F`。
3. `F ≤ maxFiles` → 停。否则继续锁下一级有序字段，回到第 2 步。
4. 字段用完仍未收敛（单个分区就超过 `maxFiles`）→ 在分区内部按写入时间倒序取前 `maxFiles` 个，界面注明「该分区 152 个文件，只读了最近 8 个」。
5. 级联深度上限 3：再多就该用引擎了，而且每级一次 plan 是有成本的。

例子（`maxFiles = 8`）：

| 表 | 分区 | 默认锁定 | 候选文件 | 说明 |
| --- | --- | --- | --- | --- |
| `orders` | `dt` | `dt = 2026-10-21` | 12 | 一级即收敛 |
| `logs` | `dt` / `hour` | `dt = 2026-10-21` + `hour = 15` | 6 | 一级 96 个 → 再锁 `hour` |
| `events` | `dt` / `region` | `dt = 2026-10-21` + `region = cn-east` | 8 | 一级 240 个 → 再锁 `region` |
| `big` | `dt` | `dt = 2026-10-21` | 152 → 读 8 | 单分区内部采样 |
| `hashed` | `bucket(64, id)` | 不锁（`all`） | 128 | 没有"最新"，不装懂 |

**为什么按预算级联，而不是固定锁满或只锁一级**：固定锁满会让 `dt/hour/region` 表默认只看 `cn-east` 的 1 个文件——数据量小到看不出分布，用户还得手动放宽；只锁一级又会跨小时。预算驱动的语义是"**少锁即够，够小就停**"：锁几层由数据量决定，不由人猜。

**成本**：每次 plan 只读 manifest（Paimon 的 manifest 树 / Iceberg 的 manifest list），通常几十毫秒；级联最多 3 次。plan 出来的清单**直接用于本次读取**，不重复规划。

### 7.4 自动条件与用户条件谁让位

自动条件最怕"替用户做了决定还不说"。优先级定死：

| 情形 | 行为 |
| --- | --- |
| 用户显式选了分区（`mode = explicit`） | 不加任何自动条件 |
| 用户选「全部分区」（`mode = all`） | 不裁剪；强制显示黄条「全部分区，共 128 个文件，结果从最近写入的文件开始」 |
| 过滤条件里已命中某个分区字段（`dt = 2024-01-01`、`dt isNull`） | **该字段不再自动锁定**，级联从下一个字段继续 |
| 表非分区 | `partition` 忽略，响应回显 `null` |

第三条是必须的：自动条件取 `dt = 2026-10-21`、用户条件写 `dt = 2024-01-01`，AND 起来必然是 0 行——用户看到的是"这个日期没有数据"，真实原因是两个条件打架。这类**静默 0 行**与 §6.3 拒绝隐式转换是同一条原则。

### 7.5 契约变化：`partition` 是独立通道，不塞进 `filters`

请求新增：

```json
"partition": {
  "mode": "auto",
  "values": [{ "field": "dt", "transform": "identity", "value": "2026-10-21" }],
  "cascade": true
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `mode` | 否 | `auto`（默认）/ `explicit` / `all` |
| `values` | 仅 `explicit` | `field` 是分区字段名；`transform` 取 `identity` `year` `month` `day` `hour` `bucket` `truncate`（Paimon 恒 `identity`）；`value` 是**转换后**的分区值（`day(ts)` 传 `2026-10-21`，不是时间戳） |
| `cascade` | 否 | 仅 `auto`：是否允许级联下钻，默认 `true`；`false` 时只锁第一级 |

**为什么不复用 `filters`**：`day(ts)` 分区在 schema 里**没有 `ts_day` 这一列**；`ts = 2026-10-21`（等值，只命中零点那一刻）与 `ts_day = 2026-10-21`（整天）不是同一个谓词。塞进 `filters` 就等于要求前端懂 transform，把格式知识搬到浏览器。分成两个通道后，前端只做"选字段、选值"，映射全在服务端（§7.9）。

响应必须回显解析结果——前端**不许**自己算"最新分区"，两处实现迟早不一致：

```json
"partition": {
  "mode": "auto",
  "resolved": [{ "field": "dt", "transform": "identity", "value": "2026-10-21" }],
  "label": "dt = 2026-10-21（最新分区）",
  "auto": true,
  "cascadeDepth": 1,
  "fallback": null,
  "partitionsTotal": 37,
  "partitionsMatched": 1
}
```

| 字段 | 说明 |
| --- | --- |
| `resolved[]` | 实际生效的分区条件；`auto` 下由服务端解析，`explicit` 下原样回显 |
| `label` | 一句人话，前端直接显示在 chip 上 |
| `auto` | 是否自动加的（决定 chip 是否带「自动」角标） |
| `cascadeDepth` | 锁了几级 |
| `fallback` | 非 null 时是 `{ "reason": …, "detail": … }`，说明为什么没锁成最新（§7.8） |
| `partitionsTotal` / `partitionsMatched` | 该快照的分区总数 / 命中数 |

### 7.6 分区枚举端点（选择器的数据源）

```
GET /api/console/v1/tables/{tableId}/partitions?page=1&pageSize=50&keyword=&snapshotId=
```

响应沿用全站远程分页口径（`PageMeta(total, page, pageSize, hasNext)`），每行：

| 字段 | 说明 |
| --- | --- |
| `fields` | 分区字段 → 值，多级分区就是多键 |
| `files` / `bytes` | 该分区的数据文件数与总大小（"值不值得看"的依据） |
| `lastWriteTime` | 分区内文件的最新写入时间。Paimon 取 `DataFileMeta#creationTime` 的最大值；Iceberg 没有逐文件写入时间，退化为该分区文件所在快照的 `timestamp-ms` |

- 排序：`lastWriteTime` 倒序（最近写的在最前）；`keyword` 按分区值前缀过滤。
- **空分区**（0 个数据文件）没有 `lastWriteTime`，按分区值倒序附在最后并标注「暂无数据」——仍然可选，
  点了就落到「这个分区没有数据」的空态（§7.8），比让它从列表里消失、用户去猜要好。
- 数据来源**只读清单**：Paimon `TableScan#listPartitionEntries()`（`PartitionEntry` 带文件数与总大小）；Iceberg 聚合 `TableScan#planFiles()` 的 `FileScanTask#spec()` 与 `#partition()`。
- **上限**：分区数超过 20000 时只回最近写入的 20000 个，响应里 `truncated: true`（前端提示"分区太多，只列了最近写入的 2 万个"）。不要试图全量回。
- 缓存：`(tableId, snapshotId)` 维度，TTL 60 s，最多 200 条。快照一变键就变，不需要主动失效。
- 权限：与预览同一道门（TABLE 级 `SELECT`）——分区值与文件数本身就是数据分布信息，只有 `DESCRIBE` 不该看到。

### 7.7 分区与游标、快照的三条绑定

1. **解析只做一次**：`auto` 只在该次预览的第一页解析，结果写进游标（`"p": [{"f":"dt","v":"2026-10-21"}]`）；第二页直接用，不重新解析。
2. **游标摘要含解析值**：`f` 算的是 `resolved` 而不是请求里的 `mode`。否则换分区后第 2 页在另一批文件上继续 `skip(r)`，行会错位且不报错。
3. **历史快照按该快照算**：选了快照 #6，"最新分区"必须基于 **#6 的清单**枚举。用当前快照算会筛出一个 #6 里不存在的分区 → 0 行，用户会以为快照坏了。这是本方案里**最容易实现错**的一条。

### 7.8 「最新分区没有数据」的三种情形

| 情形 | 现象 | 处理 |
| --- | --- | --- |
| 分区值存在但 0 个数据文件 | Iceberg 可能出现（分区被删空）；Paimon 分区随文件出现，通常不会 | 跳过，取次新，`fallback.reason = NO_FILES` |
| 有文件但行全被删（delete-only 提交） | 读到 0 行 | 空态给「看上一个分区（dt=2026-10-20）」一键重取，`fallback.reason = EMPTY_RESULT` |
| 分区值全是 null | 只有默认分区 | 选中它，文案「默认（空值）分区」 |

空态文案必须分清"分区空"还是"过滤空"：`「dt=2026-10-22 这个分区里没有数据；表里最近有数据的分区是 2026-10-21」`，而不是笼统的"没有匹配行"。

### 7.9 服务端落点与一条硬约束

| 位置 | 职责 |
| --- | --- |
| `TablePreviewController` | `POST …/preview`、`GET …/partitions` |
| `PartitionPlanner`（新增，格式无关） | 吃 `List<PartitionSlice>`，做排序 / 级联 / 回退，产出 `resolved` 与 `label` |
| `PaimonPreviewReader` | `resolved` → `Predicate` → `newScan().withPartitionFilter(...)` |
| `IcebergPreviewReader` | `resolved` → 在**任务层**按分区键过滤 `planFiles()` 的结果（精确匹配，不用 `Expressions`；理由见 §14） |
| `TablePreviewService` | 解析请求、调用 planner、组装 `scanned` 与回显 |

**硬约束：分区条件必须交给格式的 scan，不许"自己列文件再按分区挑"。** Paimon 的 `DataSplit` 是 `dataFiles + deleteFiles` 的装配体，自己挑文件会把 delete 文件落下——主键表立刻显示已删除的行（§3 否决候选 B/C 的同一个理由，在这里第二次出现）。Iceberg 的 `FileScanTask` 同时携带 `deletes()` 与 sequence 语义，也只能由 `planFiles()` 产出。

> **Iceberg 侧「按分区挑」是在任务层做的，与这条约束不矛盾**：候选集来自 `planFiles()`
> （每个任务已经带着自己的 `deletes()` 与 spec），挑的动作只是按分区键筛这个**已经规划好的**
> 任务列表，不会凭空造出一个缺少删除文件的任务。Paimon 侧则连这一步都不做——
> `withPartitionFilter` 把分区条件交给扫描器，因为它的 `BinaryRow` 分区表示只有扫描器认识。

---

## 8. 游标与「加载更多」

### 8.1 不用 offset 翻页

预览是**流式读**：第 3 页要"跳过前 200 行"，在 Parquet 上没有便宜的做法（不能 seek 到第 200 行，只能读出来丢掉）。用 offset 的话，代价随页数线性增长，而用户看到的只是"越翻越慢"。

### 8.2 无状态游标

游标 = `base64url(JSON) + "." + HMAC-SHA256`，内容：

```json
{ "s": 8, "i": 3, "r": 42, "f": "a1b2c3d4", "p": [{"f":"dt","v":"2026-10-21"}] }
```

| 字段 | 含义 |
| --- | --- |
| `s` | 快照 id（Iceberg 是 snapshot-id） |
| `i` | split / file 序号 |
| `r` | 该 split 内**已经消费**的行数（含被过滤掉的行） |
| `f` | filters + columns + **partition 解析结果**的规范化摘要（8 字节，防篡改后换条件） |
| `p` | 分区解析结果的明文（`auto` 在第一页解析一次后固化，见 §7.7） |

- 服务端**不存任何会话状态**：重启、多实例、滚动发布都不会让用户手里的游标失效。
- 续读时重新 `plan()`（只读清单，几 ms），定位到第 `i` 个 split，`skip(r)` 后继续读。`skip` 是"读出并丢弃"，最坏代价 = 一页行数（≤ 200），所以单页上限封在 200。
- `s` 与当前快照不一致，或 `f` 与本次 filters 不一致 → 409（§5.3）。
- 带 `p` 是刻意的：第 2 页若重新解析一遍 `auto`，一旦期间有分区提交（`s` 变了）就会在新分区集合上 `skip(r)`，行错位且不报错。`p` 让「这一页读的是哪一段」变成游标的一部分。
- 游标签名复用现有的密钥来源（`Secrets` / 配置里的密钥材料），不新造一套加密。

### 8.3 累计展示

前端把每页追加到已有行后面，底部显示"已加载 N 行"；`cursor=null` 后按钮变成"已到末尾（读完 K/M 个文件）"。**不做页码**：预览的语义是"往下看更多"，不是"跳到第 7 页"。

---

## 9. 采样与限额

| 限额 | 默认 | 上限 | 为什么 |
| --- | --- | --- | --- |
| `limit`（单页行数） | 100 | 200 | `skip` 的代价与页大小同阶 |
| `maxFiles`（单次请求打开的数据文件） | 8 | 32 | 对象存储 RTT × 文件数就是主要成本 |
| `maxBytes`（累计读字节） | 64 MB | 256 MB | 防止"宽表 × 大行"把堆打满 |
| `timeoutMs` | 5 000 | 15 000 | 交互式读的耐心上限 |
| 过滤条件数 | — | 5 | 再多就该用引擎了 |
| 投影列数 | 30 | 全部 | 宽表默认只取前 30 列，用户可勾全（见 §12） |
| 响应体 | — | 5 MB | 超出就截断行数并在 `warnings` 里说明 |
| 每主体并发 | 2 | — | 令牌桶，超了 429 |
| 分区枚举页大小 | 50 | 200 | 与全站分页口径一致；分区多时不要一次拉全 |
| 分区枚举条数 | — | 20 000 | 超过就截断并 `truncated=true`（§7.6） |
| 级联深度 | 3 | 3 | 每级一次 plan，再深就该用引擎了 |

**采样策略**：先按 §7 收窄分区（`mode=all` 除外），再在**作用域内**按文件最后写入时间倒序取前 K 个——「看最近的数据」符合直觉。于是"最近"有两层含义，界面上必须分开说：分区是「最近的那个分区」，文件是「这个分区里最近写入的 K 个」。

**诚实话术是硬性要求**：只要 `filesRead < filesInScope`，界面就必须显示黄色提示条「只读了最近 K/M 个文件（本分区），全表 N 个」；`filesInScope < filesInTable` 时还要补一句原因（分区过滤）。不标注采样的预览一定会被人当成全表结论——这是这个功能最容易造成的误判，也是最容易避免的。

---

## 10. 权限、审计与安全

| 项 | 做法 |
| --- | --- |
| 判权 | `authorizationService.require(prefix, ResourceType.TABLE, List.of(database), table, Access.SELECT, "preview table data")`。**用 `SELECT` 而不是 `DESCRIBE`**：能看元数据不等于能看数据，这两件事在客户心里是分开的 |
| 列级挂点 | `Access.allowedFor(COLUMN)` 已经只允许 `SELECT`，`AuthorizationService` 也已按列判定。第一版不做列遮蔽，但接口一落地，列级规则就有地方挂（§15） |
| 审计 | 新增事件类型 `TABLE_PREVIEWED(CatalogEventCategory.TABLE, "table.previewed")`，payload：catalog / database / table、过滤条件**列名与算子**（**值不落库**——值可能就是手机号）、返回行数、读文件数、耗时、是否采样 |
| 二进制列 | 不回传原始字节：`<binary 12 B>`。避免"预览变成文件下载器" |
| 超长字符串 | 截断 512 字符并在尾部加 `…`（前端据此显示省略号，不再回源）。**判定用完整值、只截回给调用方的那一份**：`contains` 命中的位置可能在 512 字符之后，先截再判会把该命中的行静默判成不命中 |
| 物理路径 | 不返回。文件列表页已经有这个信息，预览不需要重复暴露存储布局 |
| 写操作 | 零。不写文件、不提交快照、不做 DDL |
| 重复请求 | 单机 Caffeine：键 `(tableId, snapshotId, filtersHash, limit, cursor)`，TTL 3 秒，条目 ≤ 200。只挡住"连点刷新"，不做长期缓存（用户预期看到的是当前数据） |

---

## 11. 失败模式与用户可见后果

| 场景 | 服务端 | 用户看到 |
| --- | --- | --- |
| 表还没写过数据（无快照 / 无数据文件） | 200，空 rows | 「这张表还没有数据文件」+ 指向「快照」页签 |
| 表的存储格式不受支持（如列式以外的存储） | 400 | 「数据格式 X 暂不支持预览」+ 文件列表入口 |
| 对象存储凭据失效 | 502（透传上游原因摘要） | 「读不到数据文件：<原因>」+ 重试 |
| 预览期间发生 compaction | 409 | 黄条「表刚提交过，已重新取第一页」 |
| 宽表 + 大行导致超时 | 504 | 「超时，建议加一个分区过滤条件」+ 保留已加载行 |
| 缺 SELECT 权限 | 403 | 「没有读数据的权限」+ 申请入口 |
| 请求的分区在该快照里不存在 | 400 | 「这个分区不存在（当前快照 37 个分区）」+ 打开分区列表 |
| 最新分区里没有数据 | 200，空 rows + `partition.fallback` | 「dt=2026-10-22 这个分区还没有数据；最近有数据的是 2026-10-21」+ 一键切过去 |
| 分区字段类型不在时间族 / 无法定序 | 200，`mode=all` | 「这张表按哈希分区，分区之间没有先后」+ 采样提示 |

一条贯穿所有失败态的原则：**失败只影响「数据预览」页签内部**，不把整页变成错误态（与 `ResourceState` 在其它页签的分工一致）。

---

## 12. 前端交互与效果图

效果图：[`table-data-preview-mockup.html`](table-data-preview-mockup.html)（顶栏右侧「演示状态」可切换：正常 / 空结果 / 无权限 / 格式不支持）。

### 12.1 页签位置

「详细信息」之后、「快照」之前——它是详情页里第二个"第一眼要看"的东西。两个表详情页（`TableView` 的 Paimon 侧、`IcebergTableView`）挂**同一个组件** `TablePreviewPanel`，只传 `tableId` 与 `format`；组件内部不判断格式（格式差异全在服务端）。

表格式伸缩：Paimon / Iceberg 表有；Format / Object / Lance 表**没有这个页签**（它们不是快照托管表，数据文件也不是 parquet 格式族），不显示比显示一个"不支持"更干净——这与现有「快照/标签/分支」的伸缩规则同一条。

### 12.2 工具行（面板顶部）

| 控件 | 作用 | 说明 |
| --- | --- | --- |
| 快照选择（默认「最新快照 #8」） | 时间旅行预览 | 下拉列出最近 20 个快照；选历史快照时顶部出现一条「正在看历史快照 #6，只读」 |
| 分区（默认「最新 · 自动」） | 把读取范围收到一层分区切片 | 只有分区表渲染；详情见 §12.3 |
| 列投影（默认「全部列」，宽表默认勾前 30） | 减少响应体积 | 多选，搜索；勾选项即 `columns` |
| 刷新 | 重取第一页 | 与页头的「刷新」区分：页头刷新重载整页，这里只重取数据 |

### 12.3 分区控件（只有分区表渲染）

工具行第三个控件：一个下拉 + 一个 chip。

```
分区  [ dt = 2026-10-21（最新）  ▾ ]
```

点开是分区列表（数据来自 §7.6 的端点）：

| 列 | 内容 |
| --- | --- |
| 分区 | `dt = 2026-10-22`；多级分区显示 `dt = 2026-10-21 / hour = 15` |
| 文件 / 大小 | `12 个 · 86 MB`——"值不值得看"主要看这一列 |
| 最近写入 | `08:12`（相对时间，hover 出绝对时间）；空分区标「暂无数据」并置灰 |

列表底部固定两项：`全部分区（128 个文件）`、`只看最新（自动）`。

- 默认态 chip：`dt = 2026-10-21（最新分区）· 自动`。**「自动」角标只标记"服务端替你选的"**，用户一旦手选就消失。
- chip 上的 `×` 语义是"去掉分区限制"（等价切到 `全部分区`），不是"删掉一个条件"——所以点完会出现黄色提示条，而不是静默变成全表。
- 级联锁了两级时 chip 必须显示全部分量：`dt = 2026-10-21 / hour = 15 · 自动`，hover 显示 `cascadeDepth=2` 与各级候选文件数。**只显示第一级会让用户误以为范围比实际更大。**
- 空分区允许点选：点了就落到"这个分区没有数据"的空态（§11），比在列表里干脆不出现、让用户猜"为什么没有这一天"要好。
- 非分区表：控件不渲染（连"分区：无"都不显示），footer 也只写 `读了 3/128`。

### 12.4 过滤条

`[列 ▾] [算子 ▾] [值] [+ 条件] [应用]`，已生效条件以 chip 形式排在下方，`AND` 连接，可单个 `×` 移除。

- 算子候选**随列类型变**（§6.1），选了复杂类型列时值输入框自动禁用（只剩 null 判定）。
- 值输入控件随类型变：数字 `el-input-number`、日期 `el-date-picker`、布尔 `el-switch`、字符串 `el-input`。
- 「应用」把条件写进请求并回到第一页；**输入未应用时有明显标记**（按钮变主色 + chip 区显示「有未应用的改动」），避免"改完以为生效了"。
- 分区条件**不进过滤条**：它回答的是「读哪一段」而不是「筛哪些行」，放在工具行才贴语义；也避免用户把自动条件当普通条件删掉之后，以为还在最新分区上。

### 12.5 表格渲染规则

| 情况 | 渲染 |
| --- | --- |
| NULL | 灰色斜体 `<null>`，与空字符串（`""`）可区分 |
| 超长文本 | 单行截断 + 省略号，hover 显示全文（512 字符内） |
| 二进制 | `<binary 12 B>`，不可展开 |
| ARRAY / MAP / ROW | 折叠态显示 `[…] 3 项` / `{…} 4 键`，展开为格式化 JSON |
| 日期 / 时间戳 | 按 ISO 显示，时间戳同时显示相对时间 |
| 列头 | 列名 + 类型徽标（`INT` / `STRING` / `DECIMAL(10,2)`），主键列加钥匙图标 |

### 12.6 底部状态行 + 加载更多

```
已加载 100 行 · 读了 3/12 个文件（本分区，全表 128） · 12.0 MB · 过滤掉 320 行 · 42 ms · 快照 #8   [加载更多]
```

「过滤掉 320 行」不是装饰：它是过滤器真的生效的唯一证据（`scanned.rowsRead - rows.length`）。

**两个分母都要在**：只写 `3/12` 会让人以为整张表只有 12 个文件，只写 `3/128` 又抹掉了分区已经缩过范围的事实。

### 12.7 状态矩阵

| 状态 | 表现 |
| --- | --- |
| 加载中 | 表格骨架屏（不发 spinner 盖整块，避免闪） |
| 采样 | 顶部黄条「只读了最近 3/12 个文件（本分区），全表 128 个」 |
| 空结果 | 「过滤后没有匹配行；本次扫了 3 个文件、420 行」+ 「清空过滤」按钮 |
| 表无数据 | 「这张表还没有数据文件」 |
| 无权限 | 锁定图标 + 「没有读数据的权限」+ 申请入口 |
| 格式不支持 | 「数据格式 X 暂不支持预览」+ 文件列表入口 |
| 快照变更 | 黄条「表刚提交过，已重新取第一页」 |
| 超时 | 红条 + 保留已加载行 + 「换一个更小的分区」提示 |
| 分区回退 | 黄条「最新分区 dt=2026-10-22 还没有数据，已回退到 dt=2026-10-21」+ chip 显示实际生效的分区 |
| 分区为空 | 「dt=2026-10-22 这个分区里没有数据；最近有数据的分区是 2026-10-21」+ 「切到这个分区」 |
| 分区不存在 | 分区控件下红字 + 「打开分区列表」 |

---

## 13. 依赖与构建影响

| 模块 | 新增依赖 | 体积 | 说明 |
| --- | --- | --- | --- |
| `lakehouse-rest-protocol-paimon` | `org.apache.paimon:paimon-format:2.0.0` | **25 MB** | 含 shaded parquet / orc / avro 读取器（jar 内 parquet 已 relocate 到 `org.apache.paimon.shade.org.apache.parquet`，**与 Iceberg 的原生 parquet 不冲突**——已核对：1907 个 shade 类） |
| `lakehouse-rest-protocol-iceberg` | `iceberg-data` / `iceberg-parquet`（自带 `parquet-hadoop` + `parquet-avro` 1.17.1） | **约 9 MB** | `iceberg-data` 提供 `GenericDeleteFilter`（position / equality delete 抵扣）与通用记录读取；三者本地仓库已有（离线构建不受影响）。**不引 `iceberg-aws`**：数据文件的 `FileIO` 由 `IcebergPreviewFileIo` 接到 `StorageFileAccess` 上，与元数据、Paimon 面共用同一套凭据解析（省下 AWS SDK 与其自带的凭据逻辑） |
| `lakehouse-rest-server` | `org.apache.hadoop:hadoop-client-api` / `hadoop-client-runtime:3.3.4`（`runtime`） | **50 MB** | 见下面「Hadoop 这一条为什么必须有」。放在 server 是因为「产物里装什么」是部署决策（与 `paimon-s3` 同一个理由），`dependencyManagement` 里两边共用一份坐标 |

合计：可执行 jar **实测 243 MB → 296 MB（+53 MB，+22%）**（`package` 后量的；上面三行是 jar 本身的大小，压进 fat jar 后略有出入）。启动时间影响可忽略（类按需加载）。**不引入 native 库**，镜像仍是纯 JVM。

### Hadoop 这一条为什么必须有（2026-10-21 补）

读 parquet 不只需要 parquet 的 jar，还需要 Hadoop 的三个类：

```
ParquetReadOptions.Builder.<init>   →  ParquetInputFormat.class
                                       └─ extends FileInputFormat        （mapreduce-client-core）
                                          └─ Configuration / Path       （hadoop-common）
```

`parquet-hadoop`（Iceberg 面用的原生 1.17.1）与 `paimon-format` 里 relocate 过的那份副本
**都把 `org.apache.hadoop.*` 声明成 `provided`**——它们假设自己跑在引擎里。而两个协议面
各自的 POM 有意排除了 `hadoop-common`（它带着未 shaded 的 guava 27 / protobuf 2.5，
正是本仓一直在躲的东西），于是：

- **元数据读写一切正常**（create / load / commit / 清单），
- **第一次预览读数据**就 500：`NoClassDefFoundError: org/apache/hadoop/mapreduce/lib/input/FileInputFormat`，
  栈底落在 `ParquetReadOptions$Builder.<init>`。

补的形状是 Hadoop 官方的「可嵌入」产物：`hadoop-client-api` + `hadoop-client-runtime`，
第三方库全部 relocate 到 `org.apache.hadoop.shaded.*`，零传递依赖，不与 Spring Boot /
Paimon 抢 guava。代价是 50 MB；换 `hadoop-common` + `hadoop-client-core` 能省 40 MB，
但会把未 shaded 的 guava / protobuf 放回类路径——那是本节开头排掉的那个问题。

**这条有测试兜着**：`PaimonPreviewReadTests` 在临时目录里写一张主键表（parquet）再按预览
的方式读回来，另有一个点名到类的守卫用例。把这两行依赖删掉，三个用例立刻红
（实测：`NoClassDefFoundError: org/apache/hadoop/conf/Configuration`）；
加回去全绿。教训写在这里：**「排除某个传递依赖」与「不需要这项能力」是两件事**，
前者的验证点必须在运行时，而不是编译通过。

分区端点（§7.6）**不新增任何依赖**：它复用的就是这两个 reader 已经在用的元数据入口（Paimon `listPartitionEntries` / Iceberg `planFiles`），只是把结果聚合成「一个分区一行」。

一条运维影响：预览会让控制台**第一次真正读数据文件**。此前服务端只读元数据与清单，S3 上的权限/网络问题只在引擎侧暴露；上线后 IAM 只给到"清单可读"的环境会在这里报 403。这是功能固有的，不是缺陷——文档（§11）里要写清怎么区分。

---

## 14. 里程碑与验收

| 里程碑 | 内容 | 可持续验证的判据 |
| --- | --- | --- |
| **M1** | Paimon + parquet 读取、等值/范围过滤、**分区默认锁最新（单级）**、采样提示、单页 100 行 | 一张 3 分区的表：默认请求即带 `partition.mode=auto`，`resolved` 指向最新分区；`filesRead < filesInScope` 时界面必出黄条 |
| **M2** | Iceberg 读取；分区选择器 + 多级级联（§7.3）+ 分区枚举端点；游标「加载更多」；409 快照变更 | `dt/hour` 表默认 `cascadeDepth=2` 且 `filesInScope ≤ maxFiles`；同一张表两页取到的行不重不漏；compaction 后旧游标返回 409 |
| **M3** | 列投影、历史快照预览、Parquet 统计跳文件 | 宽表（≥100 列）勾 5 列时响应体 < 50 KB |
| **M4** | 列级 `SELECT` 遮蔽（脱敏）、超时降级为「只读最新分区的前 K 个文件」、`truncate` 分区支持 | 被遮蔽列显示 `***`，且 `table.previewed` 事件里记了遮蔽列与生效的分区 `resolved` |

### 实现状态

**已完成（M1 + M2）**：

- `core/preview` 的 SPI 与 DTO（`PreviewColumn` / `ColumnFilter` / `PartitionSlice` /
  `PreviewChunk` / `PreviewTable` / `PreviewTableFactory` / `PreviewLimits` / `PreviewFilters`
  / `PreviewValues`）；
- Paimon 读取器（`PaimonPreviewTable`，走 `plan() + createReader()`，分区交给
  `withPartitionFilter`）；
- Iceberg 读取器（`rest.iceberg.preview.IcebergPreviewTable`，按 `FileScanTask` 逐文件读、
  `GenericDeleteFilter` 抵扣删除、`planFiles()` 聚合分区、`useSnapshot` 时间旅行）与
  `IcebergPreviewFileIo`（把读取接到 `StorageFileAccess`，见下面"落地时的两处偏离"）；
- `PartitionPlanner`（纯逻辑，22 个单测）、`PreviewCursorCodec`（12 个单测）、
  Iceberg 侧 12 个单测（分区聚合 6 个 + 表契约 6 个）；
- `PaimonPreviewReadTests`（3 个，**真实读写**）：临时目录里建一张 parquet 主键表、写两行、
  按 `ReadSpec` 读回来（另测投影顺序、另测 Hadoop 类路径守卫）。它补上的是"读文件那一步
  一直没人跑过"的空白——2026-10-21 那次 `NoClassDefFoundError: FileInputFormat`
  就是从这里漏出去的，修复见 §13「Hadoop 这一条为什么必须有」；
- `TablePreviewService` / `TablePreviewController`（含 `tables/lookup`：名字 → 内部 id）、
  `TABLE_PREVIEWED` 事件；
- 控制台 `TablePreviewPanel.vue` 一条组件挂**两个**表详情页（Paimon 的 `TableView` 与
  Iceberg 的 `IcebergTableView`），`verify-console.py` 第 20 组（12 项）。

**落地时的两处偏离（都与 §3 的写法不同，理由写在这里）**：

1. **Iceberg 不用 `IcebergGenerics.read(table)`，而是按 `FileScanTask` 自己读。**
   它把整张表交成一个迭代器，回答不了 `filesRead` / `maxFiles` / 游标 `splitOffset`
   这三个问题——而 §10 的全部内容就是"不许猜自己读了多少"。做法是复用
   `GenericReader#open(FileScanTask)` 的四条语句（`GenericDeleteFilter` →
   `requiredSchema` → `idToConstant` → `deletes.filter`），那个类不是 public，
   所以照抄它的实现而不是绕过它。
2. **分区选择在任务层完成，不用 `Expressions`。** `planFiles()` 已经把每个文件的
   分区值算出来了，按它过滤是精确匹配；换 `Expressions.transform(col, transform) = value`
   反而要把显示值逆变换回清单内部表示（日期 → 天数、时间 → 微秒），多一层可能出错的映射。

**未实现（按 §14 的里程碑留在后续）**：

| 项 | 归属 | 缺什么 |
| --- | --- | --- |
| 列级 `SELECT` 遮蔽 | M4 | 接口与判权挂点已留好（`Access.SELECT` 到 TABLE 级） |
| Parquet 统计跳文件 | M3 | 现在按"文件最后写入时间 + 文件数预算"采样，没有用 row-group 统计做二次裁剪 |
| 3 秒去重缓存 | — | 需要引入 Caffeine；当前不加依赖，代价只是连点刷新会真的读两遍 |
| 响应体 5 MB 上限 | — | 现在由"单页 ≤ 200 行 × 单元格 ≤ 512 字符"间接兜住，没有独立的体积检查 |
| 加密表（`encryption-keys`） | — | `Parquet.read(InputFile)` 不解密；这类表会以一个明确的读取错误失败，不会给出错行 |
| 非 parquet 数据文件（avro / orc） | — | 走 `FormatModelRegistry`，是否可读取决于部署里有没有对应格式模块；读不了时 400 并指出文件路径 |


验收清单（M1 完成时逐条过）：

1. 不装任何引擎，在控制台看到表里的真实行；
2. 过滤器命中分区字段时，打开的**文件数**下降（不是只下降返回行数）；
3. 分区表首次打开：chip 带「自动」，且 `partition.resolved` 与用格式 API 手工复算的结果一致；
4. `dt/hour` 表默认 `cascadeDepth=2` 且 `filesInScope ≤ maxFiles`；单分区超预算时降级为分区内采样并注明；
5. 用户条件里已有 `dt = …` 时自动条件让位（请求里不出现两个 `dt` 条件）；
6. 历史快照 #6 下「最新分区」按 #6 的清单算：构造一个 #6 之后才新增的分区，验证它没有被选中；
7. 哈希分区表不编「最新」：`mode=all` + 明确提示语；
8. 采样时界面必须标注，且两个分母都在（`filesInScope` 与 `filesInTable`）；
9. 缺 `SELECT` 权限的用户拿 403，且页签内显示申请入口；
10. 主键表（含 delete 文件）不显示已删除的行；
11. 预览期间的提交不会让用户看到"新旧混杂"的两页（409 + 重取）；
12. 审计事件出现在事件页，过滤条件的**值**不出现在事件里（分区取值的**字段名与值**要不要记，见 §15）。

`scripts/verify-console.py` 建议新增的守卫（沿用现有风格：钉住"最容易静默退化"的几处）：

- 预览面板必须渲染采样提示（源码里存在 `filesRead` 与 `filesInScope` 的比较分支）；
- footer 必须同时出现 `filesInScope` 与 `filesInTable`（只剩一个分母就等于把"3/12"说成"3/128"）；
- 分区控件必须有「自动」角标分支，且 chip 的 `×` 绑定的是"切到全部分区"而不是删除条件；
- 面板**不得**自己算最新分区（源码里不应出现对分区列表排序取 max 的前端逻辑，解析只在服务端）；
- 页签对 Format / Object / Lance 表不渲染（与现有的格式伸缩断言同一处）；
- 面板只能调 `previewTable` 一个端点，**不得**拼接下载端点或直连协议面；
- 错误态文案与 §5.3 的表逐条对齐（缺一条就是"用户看到空白页"）。

---

## 15. 风险与开放问题

| 风险 / 问题 | 现状判断 |
| --- | --- |
| 主键表的读放大：merge-on-read 一次要读多个文件，`maxFiles=8` 可能只够读几百行 | M1 先把 Paimon 主键表的 `maxFiles` 降到 4，观察后再定；必要时在界面上说明「主键表预览范围更小」 |
| 分区枚举的成本（上万分区、宽表 manifest） | 一次全扫可能上百 ms：60 s 缓存 + 20000 条截断兜住；仍不够就只列「最近写入的 N 个」并保留 `truncated` 标记 |
| 级联锁定的"魔法感"：锁几级由数据量决定 | chip 必须能展开看到具体值、响应回显 `cascadeDepth`；用户手选过之后**不再自动**，避免"我选了它又自己变了" |
| 哈希分区没有「最新」 | 不编：`mode=all` + 一句说明。若业务真要"最近写入的桶"，Paimon 有 `creationTime` 可用，Iceberg 没有逐文件写入时间，不可行 |
| 分区字段是 STRING 但值不是规范日期（`2024-1-5`） | 只按元数据类型 + 值的严格格式判定时间族，猜不中就写「最大分区值」——宁可不智能，不要错 |
| 审计事件要不要记分区值 | 分区值本身可能是敏感信息（如 `region` 或按手机号前缀分区）。建议只记 `resolved` 的**字段名**与 `partitionsMatched`，值不落库；这条要跟安全一起定 |
| 宽表（数百列）的投影默认值 | 默认前 30 列 + 明确的「已省略 N 列」提示，而不是悄悄少给 |
| 行级权限（按过滤条件限制可见行） | 需要权限模型支持谓词，`PermissionAssignmentEntity` 目前只有范围（catalog/db/table/column），没有行谓词。已单独出方案并实现：[`row-filter-column-mask-design.md`](row-filter-column-mask-design.md)（预览按策略逐行求值，见其 §6 P1） |
| 列级脱敏策略（手机号/身份证正则、哈希） | 与 M4 一起做；接口里的 `COLUMN` 级 `SELECT` 已经留好挂点。执行侧方案见 [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) §6（带策略的表在 P0 直接拒绝预览；P1 起预览按策略求值，落到被脱敏列的用户条件指名拒绝） |
| 是否最终开放 SQL | 建议长期不做。若业务强需求，走「数据访问」页签交给外部引擎，控制台不做查询入口 |
| 预览与「文件列表下载」的权限一致性 | 两者都只读、都需 TABLE 级权限；下载是 `DESCRIBE` 还是 `SELECT` 目前不一致，M1 顺手统一到 `SELECT` |
