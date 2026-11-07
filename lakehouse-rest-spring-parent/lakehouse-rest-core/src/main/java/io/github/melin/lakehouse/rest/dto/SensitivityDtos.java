package io.github.melin.lakehouse.rest.dto;

import io.github.melin.lakehouse.rest.support.PageMeta;
import java.util.List;
import java.util.Map;

/**
 * 敏感数据识别与标签治理的 DTO（设计见 {@code docs/sensitive-data-design.md} §9）。
 *
 * <p><b>端点统一挂 {@code /api/management/v1/**}。</b>设计稿 §9 的表里路径写作
 * {@code /sensitive-types} 等（省略了前缀），但 §9 开头那句「沿用 management 风格
 * （{@code /api/catalogs/{c}/...}）」在真实代码里对应的是
 * {@code /api/management/v1/catalogs/{c}/...}——受管前缀清单（
 * {@code WebConfig.addInterceptors}）只含 {@code /v1/**}、{@code /lance/v1/**}、
 * {@code /api/catalog/v1/**}、{@code /api/management/v1/**}、{@code /api/console/v1/**}，
 * 照字面挂在 {@code /api/catalogs/...} 会是**匿名可调**。因此这里的路径一律带
 * {@code /api/management/v1/} 前缀。
 *
 * <p><b>不新增权限词。</b>设计稿 §10 的四个权限点（{@code CATALOG:SENSITIVE_SCAN} 等）
 * 映射到既有 access 词表：改类型 / 打标 / 批量决策 → {@code CATALOG:ALTER}，
 * 看完整标签 → {@code CATALOG:DESCRIBE}。理由有两处机械约束：
 * {@code scripts/verify-spark-sql-doc.py} 把 {@code allowedFor} 与
 * {@code docs/spark-sql-reference.md} §7.4 的 access 词表逐项比对，
 * {@code ConsoleApiTests} 也断言权限矩阵——加一个词就要同时改这两处与引擎侧文档，
 * 而「谁能发起扫描」这件事用 {@code CATALOG:ALTER} 已经表达得很准
 * （它与「改这个 catalog 的配置」同级）。
 */
public final class SensitivityDtos {

    private SensitivityDtos() {
    }

    // ------------------------------------------------------------------ 敏感类型

    /**
     * 一个敏感类型的读模型。
     *
     * <p>{@code detectors} 直接给结构化对象（不是 JSON 字符串）：控制台的
     * {@code #types} 屏要逐层编辑探测器，给一个需要前端自己 parse 的字符串
     * 等于把 {@link io.github.melin.lakehouse.rest.scan.DetectorConfig} 的
     * 解析规则复制一份到 JS 里，两份迟早漂移。写路径同样收结构化对象。
     *
     * <p>{@code autoApplyAllowed} 是**只读的推断结果**（白名单里有没有它），
     * 与可写的 {@code autoApply} 并列：界面要能回答「这个类型为什么不允许自动落标」
     * ——原因是它在 §5.3 的禁止清单里，而不是「有人把开关关了」。
     * 两者同值时说明没人改过；{@code autoApply=false} 而
     * {@code autoApplyAllowed=true} 说明是治理员显式关的。
     *
     * <p><b>没有 {@code entityVersion}。</b>设计稿 §4 开头那句「全部沿用既有约定
     * （… entity_version 乐观并发 …）」在 §4.1 的字段表里并没有落成类型表的一列，
     * 这里以字段表为准：类型是「几十个」低频对象，两个人同时改同一个类型的窗口
     * 极小，而这类冲突的后果只是「一个版本的探测器配置被覆盖」——它下一轮扫描
     * 就会被发现（识别结果变了），不像标签冲突那样会静默丢一条治理结论。
     * 代价换来的是少一列、少一次 DDL，以及读模型少一个调用方要回填的字段。
     * PUT 因此是 last-write-wins，审计靠 {@code updatedBy} / {@code updatedAt}。
     */
    public record SensitiveType(String typeCode,
                                String typeName,
                                String category,
                                String level,
                                Map<String, Object> detectors,
                                boolean autoApply,
                                boolean autoApplyAllowed,
                                double minConfidence,
                                boolean builtin,
                                boolean enabled,
                                List<String> configuredLayers,
                                String createdBy,
                                Long createdAt,
                                String updatedBy,
                                Long updatedAt) {
    }

    /** {@code GET /sensitive-types}：不分页（类型是「几十」个，与既有列表同一取舍）。 */
    public record SensitiveTypeList(List<SensitiveType> items) {
    }

    /**
     * {@code POST} / {@code PUT /sensitive-types/{code}} 的请求体。
     *
     * <p>{@code detectors} 是「层名 → 层配置」的裸 JSON 节点（{@code Object} 承载），
     * 与 {@code DataPolicyDtos} 的表达式同一做法：形状校验在服务层做
     * （{@code DetectorConfig.parse} 认不出的层忽略、不抛），DTO 层不做形状假设。
     */
    public record SensitiveTypeUpsertRequest(String typeCode,
                                             String typeName,
                                             String category,
                                             String level,
                                             Object detectors,
                                             Boolean autoApply,
                                             Double minConfidence,
                                             Boolean enabled) {
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * {@code POST /sensitivity-scans} 的请求体。
     *
     * <p>{@code scope} 决定后三个字段哪几个必填：{@code CATALOG} 全空、
     * {@code DATABASE} 要库名、{@code TABLE} 要库名 + 表名。**不靠「哪几个字段有值」
     * 反推层级**：{@code TABLE} 级的表名恰好等于某个库名这种巧合不该改变语义。
     *
     * <p>{@code execution} 留空 = 用生效的三层覆盖值（请求 &gt; catalog 的
     * {@code defaultExecution} &gt; 全局）——注意落库的是**生效值**，
     * 因为「三个月后回看这次扫描跑在哪」必须能答上来（设计稿 §4.2）。
     */
    public record ScanRequest(String catalog,
                              String scope,
                              String database,
                              String table,
                              String mode,
                              String execution,
                              Integer sampleSize,
                              String triggerType) {
    }

    /**
     * 扫描的读模型（列表与详情共用；列表页靠它带出 {@code execution} 与 {@code triggerType} 两列）。
     *
     * <p>{@code scheduleName} 是「这条扫描由哪个调度项发起」的答案，只有
     * {@code triggerType=SCHEDULE} 时才有值（列在迁移里从 V1 就有，但一直没进读模型——
     * 于是「停用之后历史仍按 {@code schedule_name} 可查」这句话在 API 上不成立：
     * 库里存着、没人读得到）。调度项控制台按它回查「这条规则到底跑过没有」。
     */
    public record ScanSummary(String id,
                              String catalog,
                              String scope,
                              String database,
                              String table,
                              String mode,
                              String execution,
                              String triggerType,
                              String scheduleName,
                              String status,
                              int sampleSize,
                              int tablesScanned,
                              int columnsScanned,
                              int findingsCreated,
                              int tablesFailed,
                              String triggeredBy,
                              Long startedAt,
                              Long finishedAt,
                              String errorMessage,
                              String executionNamespace,
                              String executionResource,
                              Long createdAt,
                              Long updatedAt) {
    }

    /** {@code GET /sensitivity-scans}：分页走既有 {@code PageMeta} 口径（先过滤再切页）。 */
    public record ScanList(List<ScanSummary> items, PageMeta meta) {
    }

    /** {@code POST /sensitivity-scans} 的 202 响应：只回 id 与生效执行位置，不回结果。 */
    public record ScanAccepted(String scanId, String catalog, String status, String execution) {
    }

    // ------------------------------------------------------------------ 命中（finding）

    /**
     * 一条扫描命中。
     *
     * <p>{@code samples} 是**打码后**的样本（§5.4 的唯一不变量：任何路径都不写原值）。
     * 类型是 {@code List<String>} 而不是原始行的二维数组——它本就只该是
     * 「{@code 110***********1234}」这样的字符串，用更宽的类型会让人以为能拿到原值。
     */
    public record Finding(long id,
                          String scanId,
                          String catalog,
                          String database,
                          String table,
                          String column,
                          String columnRef,
                          String typeCode,
                          double confidence,
                          Map<String, Object> detectors,
                          List<String> samples,
                          String samplingMode,
                          String state,
                          String decidedBy,
                          Long decidedAt,
                          Long createdAt) {
    }

    /** {@code GET /sensitivity-findings}：人审队列（P1 起有队列 UI，P0 只提供读）。 */
    public record FindingList(List<Finding> items, PageMeta meta) {
    }

    /**
     * {@code POST /sensitivity-findings:decide} 的请求体：批量确认 / 拒绝。
     *
     * <p><b>批量而不是单条。</b>人审队列的真实用法是「这一屏 20 条，扫一眼，全确认」
     * ——逐条点一次会让人放弃使用队列，而放弃之后这个闸门就等于不存在。
     * 因此接口以「一组 id + 一个决定」为单位，而不是每条一个请求。
     *
     * <p><b>{@code decision} 只接受 {@code CONFIRMED} / {@code REJECTED}。</b>
     * 把状态机缩到这两个值是有意的：{@code AUTO_APPLIED} 是扫描的产出、
     * {@code PENDING} 是初始态，让人审把它们「改回去」没有任何含义，
     * 而允许任意赋值会让 {@code REJECTED} 的抑制项与状态脱节。
     *
     * <p>{@code note} 会写进抑制项的 {@code reason}（拒绝时）与事件（两者都写）——
     * 三个月后回看「为什么这列被判成不是身份证」时，这是唯一的一手信息。
     */
    public record FindingDecisionRequest(List<Long> ids, String decision, String note) {
    }

    /**
     * 决策结果。
     *
     * <p>{@code skipped} 是**已经被决策过**的条数（幂等重试、或两个人同时点）。
     * 它不是错误——把「重复提交」判成 409 会让批量操作在网络抖动时反复失败，
     * 而重复确认一条已经确认的命中，语义上是无害的。
     *
     * <p>{@code tagIds} / {@code suppressionIds} 是**这次真正产生的行**。
     * 分开回传而不是合并成一个 id 列表：调用方（控制台）要用它们区分
     * 「落了标签」与「落了抑制项」，两者的后续动作不同（前者去标签页看，
     * 后者去抑制项页看）。
     */
    public record FindingDecisionResult(String decision,
                                        int decided,
                                        int skipped,
                                        List<Long> tagIds,
                                        List<Long> suppressionIds) {
    }

    /**
     * 一条永久抑制项（{@code GET /sensitivity-suppressions}）。
     *
     * <p>{@code enabled=false} 表示已解禁：**行不删**，所以界面上能显示
     * 「这条被谁在什么时候解除过」，而不是让它凭空消失。
     */
    public record Suppression(Long id,
                              String catalog,
                              String database,
                              String table,
                              String column,
                              String columnRef,
                              String typeCode,
                              String reason,
                              boolean enabled,
                              String decidedBy,
                              Long decidedAt,
                              Long findingId,
                              String releasedBy,
                              Long releasedAt) {
    }

    /** {@code GET /sensitivity-suppressions}：按 catalog 过滤的抑制项清单（不分页，量级是「几十条」）。 */
    public record SuppressionList(List<Suppression> items) {
    }

    // ------------------------------------------------------------------ 列级标签

    /**
     * 一条列级标签。
     *
     * <p>{@code level} 是从 {@code tagValue} 解出的分级（不是所有标签都有：
     * 手工标签如 {@code owner.team} 就没有）。单独给一个字段，是为了让
     * 「按 {@code level=L4} 过滤」与「分级分布」两个消费方不必各自再解一次。
     *
     * <p>{@code tableLevel} = {@code column} 为空串（表级聚合标签）。
     */
    public record ColumnTag(long id,
                            String catalog,
                            String database,
                            String table,
                            String column,
                            String columnRef,
                            boolean tableLevel,
                            String tagKey,
                            String tagValue,
                            String level,
                            String source,
                            String typeCode,
                            Double confidence,
                            String inheritedFrom,
                            Long findingId,
                            boolean enabled,
                            int entityVersion,
                            String updatedBy,
                            Long updatedAt) {
    }

    /** {@code GET /column-tags}：对外主接口（数据地图、审批流、上线检查都消费它）。 */
    public record ColumnTagList(List<ColumnTag> items, PageMeta meta, ColumnTagCoverage coverage) {
    }

    /**
     * 覆盖率摘要（设计稿 §13「不承诺扫完即全覆盖，控制台报覆盖率」）。
     *
     * <p>{@code classifiedColumns} 是**已打标列数**，{@code totalColumns} 是范围内
     * 已知列数——分母来自 {@code lakehouse_table.schema_doc} 解析出的列，
     * 因此「没扫过的表」会把覆盖率拉低，这正是要表达的东西：
     * 「已分类 / 全部」比「扫描已完成」诚实。
     *
     * <p><b>{@code tablesCounted} / {@code totalTables} 是把「诚不诚实」落到实处的那两列。</b>
     * 分母要对 catalog 里每张表的 {@code schema_doc} 做一次 JSON 解析，而一个几万张表的
     * catalog 会让这一个读接口变成全量元数据扫描。因此分母有一个表数上限
     * （{@code MAX_COVERAGE_TABLES}），超过时**只数到上限为止并把两个数都回传**：
     * 前端据此显示「覆盖率按前 N 张表计算」而不是把一个被截断的分母当成全局值。
     * 只回一个分母、不说是怎么算出来的，就等于让它撒谎——而
     * 「覆盖率是分母问题，不能靠沉默掩盖」正是这一屏存在的理由。
     *
     * <p>{@code tablesWithTags} 与 {@code tablesCounted} 的区别：前者是**有标签**的表数
     * （分子侧），后者是**被计入分母**的表数。两者不同值说明「有表被扫过但没命中任何类型」，
     * 或「还有表没被数进分母」。
     *
     * <p>{@code byLevel} / {@code bySource} 按**已打标列**统计（不是按标签行）：
     * 一列有两个标签时它在两个维度里各算一次，而「L4 有多少列」这个问题的答案是
     * 「有几个列的最高级别是 L4」——按标签行数会把同一列重复计入。
     */
    public record ColumnTagCoverage(int classifiedColumns,
                                    int totalColumns,
                                    int tablesWithTags,
                                    int tablesCounted,
                                    int totalTables,
                                    Map<String, Integer> byLevel,
                                    Map<String, Integer> bySource) {
    }

    /**
     * {@code PUT /column-tags} 的请求体：手工打标（upsert）。
     *
     * <p>唯一键是 {@code (column_ref_hash, tag_key)}，所以「同一列同一键只能有一个值」
     * ——重复 PUT 是更新，不是新增。{@code entityVersion} 做乐观锁，不符回 409。
     *
     * <p>{@code source} **不可由调用方指定**：手工打标恒为 {@code MANUAL}，
     * 否则「谁能伪造一条 AUTO 标签」就成了一个绕过自动应用闸门的口子
     * （AUTO 标签的含义是「扫描算出来并过了白名单」，不是「谁说是就是」）。
     */
    public record ColumnTagUpsertRequest(String catalog,
                                         String database,
                                         String table,
                                         String column,
                                         String tagKey,
                                         String tagValue,
                                         String typeCode,
                                         Boolean enabled,
                                         Integer entityVersion) {
    }

    // ------------------------------------------------------------------ 标签 → 脱敏绑定（P3）

    /**
     * 一条「标签 → 脱敏动作」绑定（设计 §4.5 / §8.1）。
     *
     * <p><b>作用域是 catalog，不是表。</b>绑定表达的是「凡是 {@code PII.CN_ID_CARD} 的列
     * 都这么脱敏」——它是规则，不是某张表的属性。因此这个读模型里没有
     * {@code database} / {@code table}：有的话会让人以为绑定能按表配，
     * 而那正是 {@code DataPolicyEntity} 的职责（两张表的分工见 §4.5 的 blockquote）。
     *
     * <p>{@code wildcardKey} / {@code wildcardValue} 是把「通配」显式说出来：
     * 前者 = {@code tagKey} 以 {@code .*} 结尾（{@code PII.*} 匹配整个域），
     * 后者 = {@code tagValue} 是空串（该键任意分级）。界面要能一眼看出这条绑定
     * 覆盖的是「一个类型」还是「一个域」——两者的爆炸半径差一个数量级，
     * 而它们在字段上只是一个后缀与一个空串。
     *
     * <p><b>没有 {@code entityVersion}。</b>{@code lakehouse_tag_mask_binding} 是 P0 就建的
     * 表，字段表（§4.5）里没有乐观锁列，这里以字段表为准：绑定是「几条到几十条」的
     * 规则配置，两个人同时改同一条的后果是一条规则被覆盖，下一轮 resolve 就能看出来；
     * 它不像标签那样会静默丢一条人工结论。PUT 因此是 last-write-wins，
     * 审计靠 {@code updatedBy} / {@code updatedAt}。
     */
    public record TagMaskBinding(long id,
                                 String catalog,
                                 String tagKey,
                                 String tagValue,
                                 boolean wildcardKey,
                                 boolean wildcardValue,
                                 Map<String, Object> mask,
                                 String maskSql,
                                 int priority,
                                 boolean enabled,
                                 String updatedBy,
                                 Long updatedAt) {
    }

    /** {@code GET /tag-mask-bindings?catalog=}：不分页（量级是「几条到几十条」），含停用项。 */
    public record TagMaskBindingList(List<TagMaskBinding> items) {
    }

    /**
     * {@code PUT /tag-mask-bindings} 的请求体。
     *
     * <p><b>按唯一键 {@code (catalog, tagKey, tagValue)} upsert，不按 id。</b>唯一键本身
     * 就是这条规则的地址——先 POST 拿 id 再 PUT，会让「先 GET 一下自己有没有配过」
     * 变成调用方必做的事，漏做就撞唯一索引（报错形状还是 500，不是业务错误）。
     * 这与 {@code PUT /column-tags} 同一形态。
     *
     * <p>{@code tagValue} 传 {@code null} / 空串都表示「该键任意值」。服务层**统一归一成空串**
     * 落库：MySQL 唯一索引下多行 {@code NULL} 互不冲突，用 {@code null} 会让同一把键
     * 能插两条通配绑定（见实体类注释）。
     */
    public record TagMaskBindingUpsertRequest(String catalog,
                                              String tagKey,
                                              String tagValue,
                                              Object mask,
                                              Integer priority,
                                              Boolean enabled) {
    }

    // ------------------------------------------------------------------ catalog 级设置

    /**
     * {@code GET /sensitivity-settings}：一个 catalog 的治理设置。
     *
     * <p><b>三个字段都是「catalog 覆盖」，即 {@code null} 表示继承全局。</b>读接口
     * 同时回 {@code resolved*} 三列（生效值）：界面要能回答「这个 catalog 现在实际
     * 会怎么跑」，而只给覆盖值的话，答案是「未设置」——那要前端自己再取一次全局配置，
     * 于是同一件事有两个答案源（与 {@code ActionPolicy} 的
     * 「差异 + 生效值并列」同一取舍）。
     */
    public record SensitivitySettings(String catalog,
                                      String defaultExecution,
                                      Integer maxConcurrentScans,
                                      Integer propagationMaxDepth,
                                      String resolvedExecution,
                                      int resolvedMaxConcurrentScans,
                                      int resolvedPropagationMaxDepth,
                                      int entityVersion,
                                      String updatedBy,
                                      Long updatedAt) {
    }

    /**
     * {@code PUT /sensitivity-settings}：整份替换。
     *
     * <p>传 {@code null} = 清除覆盖、回落全局；不传字段（{@code null}）也能表达这一点，
     * 所以**没有单独的「清除」开关**——与执行集群那份请求体刻意不同：
     * 那里的秘密字段留空表示「保持原样」，因为空串是有效的秘密值；
     * 这里的三个字段都是可选覆盖，空就是空。
     *
     * <p>{@code entityVersion} 做乐观锁：改的是「这个 catalog 的扫描怎么跑」，
     * 与改绑定同级，两个治理员同时改必须由人来裁决（设计稿 §9）。
     */
    public record SensitivitySettingsUpdateRequest(String defaultExecution,
                                                   Integer maxConcurrentScans,
                                                   Integer propagationMaxDepth,
                                                   Integer entityVersion) {
    }

    // ------------------------------------------------------------------ 调度项（P4）

    /**
     * 一条调度项（{@code GET /sensitivity-schedules}、{@code GET /sensitivity-schedules/{id}}）。
     *
     * <p><b>{@code scope} 是派生值</b>（库里只存三段名，见实体注释），但读模型必须给出来：
     * 界面上的「catalog 级 / 库级 / 表级」这一列如果由前端自己按「哪些字段有值」推，
     * 同一套推导就有了两份实现，而其中一份迟早会漏掉「表名恰好等于库名」这类情形。
     *
     * <p>{@code nextRunAt} 是**服务端算的那个数**，不是「按 cron 现算」。两者在多副本、
     * 时钟漂移、以及「上一拍被抢占但扫描失败」之后会不一致，而用户关心的恰恰是
     * 「它下一次到底什么时候跑」——那个答案在库里。
     *
     * <p>{@code entityVersion} 只服务人工编辑的乐观锁；调度器推进 {@code nextRunAt}
     * **不会**改它（那是抢占令牌，不是编辑）。因此「看到 3 但实际已是 4」只会发生在
     * 两个人同时改同一条调度项时——这正是 409 要表达的事。
     */
    public record SensitivitySchedule(long id,
                                      String name,
                                      String cron,
                                      String catalog,
                                      String scope,
                                      String database,
                                      String table,
                                      String mode,
                                      String execution,
                                      int sampleSize,
                                      boolean enabled,
                                      Long lastRunAt,
                                      Long nextRunAt,
                                      int entityVersion,
                                      String createdBy,
                                      Long createdAt,
                                      String updatedBy,
                                      Long updatedAt) {
    }

    /**
     * 调度项清单。
     *
     * <p><b>不分页。</b>量级是「十几条到几十条」——每个 catalog 的扫描节奏不会有人配出
     * 上百条；而分页会让「按 catalog 过滤」与「按启用状态过滤」在界面上互相牵制
     * （筛出来 3 条却显示 2 页）。与 {@code /sensitive-types}、{@code /tag-mask-bindings}
     * 同一取舍。
     */
    public record SensitivityScheduleList(List<SensitivitySchedule> items) {
    }

    /**
     * {@code POST} / {@code PUT /sensitivity-schedules[/{id}]} 的请求体。
     *
     * <p>{@code name} 只在新建时有意义：**改名会被拒（400）**，因为扫描表按
     * {@code schedule_name} 回查历史，改名会让「这条扫描是哪条调度项触发的」失去指向。
     * 要换名字请建一条新的、停用旧的——旧名字下的历史仍可查。
     *
     * <p>{@code execution} 留空时取三层覆盖的生效值（请求 &gt; catalog &gt; 全局）并落库：
     * 调度项是长期对象，「它会在哪跑」必须能在库里读出来，不能每次触发时再算一遍
     * （算出来的值与当时那一刻的 catalog 设置有关，事后无法重建）。
     *
     * <p>{@code entityVersion} 只在 {@code PUT} 有意义：{@code null} 表示跳过校验
     * （与 {@code PUT /sensitivity-settings} 同口径）。
     */
    public record SensitivityScheduleUpsertRequest(String name,
                                                   String cron,
                                                   String catalog,
                                                   String scope,
                                                   String database,
                                                   String table,
                                                   String mode,
                                                   String execution,
                                                   Integer sampleSize,
                                                   Boolean enabled,
                                                   Integer entityVersion) {
    }
}
