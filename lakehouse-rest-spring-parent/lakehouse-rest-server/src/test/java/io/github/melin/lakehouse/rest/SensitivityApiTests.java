package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.SensitiveTypeEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.domain.repo.ColumnTagRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitiveTypeRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 敏感数据识别与标签治理的 HTTP 层验收（设计见 {@code docs/sensitive-data-design.md}）。
 *
 * <p><b>这一层补的是 §12 P0 行的验收断言，它们只有走完整链路才看得见。</b>
 * {@code SensitivityDetectionTests} 覆盖的是识别引擎本身（打分公式、决策带、层内权重），
 * 全是纯函数；而下面这几条断言的落点在实体、端点、审计与并发闸门上，桩掉任何一段
 * 都测不到：
 *
 * <ol>
 *   <li><b>内置类型不可删（409）。</b>这是「删了它、下次启动 seed 又按唯一键建回来」
 *       这个更难理解的状态的守卫。断言必须打在 HTTP 状态码上——服务层抛的
 *       {@code ApiException.conflict} 与控制器把它翻成 409 是两件事。
 *   <li><b>内置类型可改，且改动进审计。</b>改权重与不可删是同一枚硬币的两面：
 *       前者不许改，§12 那条「改权重后下一轮判定随之改变」就永远无法验证。
 *       这里的「审计」落在 {@code AuditedEntity} 的 {@code updatedBy} /
 *       {@code updatedAt} 上（类型表不发事件，见 {@code SensitiveTypeService}）。
 *   <li><b>{@code defaultExecution} 三层覆盖（请求 &gt; catalog &gt; 全局）。</b>
 *       只测两层的话，「请求级覆盖被忽略」这条会漏——而它的表现是
 *       「我明明选了 SPARK，跑的还是 A」。
 *   <li><b>{@code maxConcurrentScans} 按 catalog 覆盖生效，且与执行集群的
 *       {@code maxConcurrentJobs} 无关。</b>两者守的是完全不同的东西
 *       （进程内并发 vs 那台集群上的作业数），混成一个数的后果是
 *       「调大集群并发」意外放开对对象存储的并发读。
 *   <li><b>P0 的元数据档永不自动落标。</b>D1 + D2 的分数上限是 0.45，够不到默认阈值
 *       0.85——这是「P0 只产候选、不产标签」以及「P0 不需要抑制项」的共同根因。
 *   <li><b>同一列重扫不产生重复标签，且扫描不覆盖人工结论。</b>幂等由唯一键
 *       {@code uk_column_tag}（{@code column_ref_hash} + {@code tag_key}）承担，
 *       而唯一键只在真库上才生效——内存模型里「重扫产生两条」看起来完全正常。
 *   <li><b>改一个内置类型的探测器权重后，下一轮扫描的判定随之改变。</b>
 *       {@code SensitivityDetectors} 漏乘 {@code weight} 时这条会失败，而失败的方式
 *       极隐蔽：权重配了、库里存了、控制台也显示得出来，判定却一字不变。
 * </ol>
 *
 * <p>扫描是 {@code METADATA_ONLY}（P0 唯一实现的档）：只读 {@code schema_doc} 里的
 * 列名 / 类型 / 注释，不碰任何行数据，因此本类不需要真存储。
 *
 * <p><b>本类的每个断言都不依赖「其它测试类没动过这些表」</b>：类型表按
 * {@code typeCode} 定位、finding 按 {@code scanId} 过滤、标签断言前先断言表为空
 * （见 {@link #manualTagIsUpsertedNotDuplicated} 的前提检查）。用到的 {@code paimon}
 * catalog 由 {@code DataInitializer} 按 {@code application.yml} 的
 * {@code initial-catalog.prefix} 预置，不由本类创建。
 */
@SpringBootTest(properties = {
        // 本类不测鉴权：application-test.yml 已是这个值，写出来是让「为什么这里不需要令牌」显式
        "lakehouse.rest.auth.enabled=false",
        // 内置 seed 必须开：409 那条断言建立在「它是内置的」之上，
        // 而 application-test.yml 的默认值也是 true——写成显式是为了让本类不受那份文件将来的改动影响
        "lakehouse.rest.sensitivity.seed-builtin-types=true",
        // 全局默认设成 SPARK，这样「catalog 覆盖成 IN_PROCESS」与「请求级覆盖」
        // 两条断言都指向一个与全局**不同**的值，能真正验出覆盖是否生效。
        // 注意：METADATA_ONLY 下推到 Spark 恒被拒（allowMetadataOnSpark=false），
        // 因此这个全局值同时给了「请求级 SPARK → 400」一个确定的落点。
        "lakehouse.rest.sensitivity.scan.execution=SPARK"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitivityApiTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "sens_db";
    private static final String TABLE = "people";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private SensitiveTypeRepository typeRepository;
    @Autowired
    private ColumnTagRepository tagRepository;
    @Autowired
    private AuditEventRepository eventRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 1. 内置类型：不可删、可改、改动进审计

    @Test
    @Order(1)
    void builtinTypesAreSeededAndCannotBeDeleted() throws Exception {
        JsonNode list = json(200, "GET", M + "/sensitive-types", null);
        List<String> codes = new java.util.ArrayList<>();
        list.get("items").forEach(item -> codes.add(item.get("typeCode").asString()));
        assertTrue(codes.containsAll(List.of("CN_ID_CARD", "CN_MOBILE", "PERSON_NAME",
                        "MEDICAL_DIAGNOSIS")),
                () -> "内置类型要随启动 seed 出来，实际=" + codes);
        assertEquals(11, codes.size(), () -> "内置类型共 11 类，实际=" + codes);

        // builtin 这个标记不能少：控制台那一屏「点编辑才解锁删除」全靠它，
        // 而它只在 seed 路径上被置 true（API 建的恒为 false，见 Order(2)）
        JsonNode idCard = find(list, "CN_ID_CARD");
        assertTrue(idCard.get("builtin").asBoolean(), "CN_ID_CARD 应是内置的");
        assertEquals("L4", idCard.get("level").asString(), "身份证是 L4");
        assertEquals("PII", idCard.get("category").asString());
        assertTrue(idCard.get("detectors").has("name"),
                "探测器配置要以结构化对象回（控制台 #types 的编辑器逐层读它）");
        // configuredLayers 是「实际配了哪几层」的摘要：界面要能一眼看出
        // 「这个类型只靠列名判」，而不必展开 JSON 自己数
        List<String> layers = new java.util.ArrayList<>();
        idCard.get("configuredLayers").forEach(node -> layers.add(node.asString()));
        assertTrue(layers.contains("NAME") && layers.contains("TYPE"),
                () -> "CN_ID_CARD 至少配了名称层与类型层，实际=" + layers);

        // ★ 验收断言一：内置类型不可删 → 409（不是 400、不是 403、不是静默改成停用）
        assertEquals(409, status("DELETE", M + "/sensitive-types/CN_ID_CARD", null),
                "内置类型删除要返 409：删了它下次启动 seed 会按 type_code 唯一键建回来");

        // ★ 验收断言二：内置类型**可改**。改的是 CN_MOBILE 而不是 CN_ID_CARD——
        // Order(6) 要拿 CN_ID_CARD 的 seed 原值做「改权重前后」的对照，
        // 在这里先把它改掉会让那条断言的两个值都变成人造的。
        json(200, "PUT", M + "/sensitive-types/CN_MOBILE",
                """
                {"detectors": {"name": {"patterns": ["mobile", "phone"], "weight": 0.8}}}
                """);
        // 探查器配置要**真的落库**：服务层会把请求里的 detectors 收一遍
        // （DetectorConfig.parse 再 toJson），因此回读的是归一后的文本
        SensitiveTypeEntity mobile = typeRepository.findByTypeCode("CN_MOBILE").orElseThrow();
        assertTrue(mobile.getDetectorsJson().contains("0.8"),
                () -> "PUT 之后的权重必须落库，实际=" + mobile.getDetectorsJson());
        // 变更进审计：类型表不发事件（SensitiveTypeService 没有 events 字段），
        // 这条断言是「改动有人负责」在本模块的唯一落点
        assertNotNull(mobile.getUpdatedAt(), "类型改动要写 updatedAt");
        assertNotNull(mobile.getUpdatedBy(), "类型改动要写 updatedBy");
        assertNotNull(mobile.getCreatedAt(), "seed 时写过 createdAt");
    }

    @Test
    @Order(2)
    void customTypeRoundTripsAndCanBeDeleted() throws Exception {
        json(201, "POST", M + "/sensitive-types",
                """
                {"typeCode": "custom_empno", "typeName": "员工工号", "category": "PII",
                 "level": "L2", "detectors": {"name": {"patterns": ["emp_no", "employee_id"]}}}
                """);
        // 编码被归一成大写（CODE_PATTERN 要求首字母大写）——自定义类型也不能例外，
        // 因为 type_code 会进标签键 PII.CN_ID_CARD 这种跨系统契约
        JsonNode created = json(200, "GET", M + "/sensitive-types/CUSTOM_EMPNO", null);
        assertFalse(created.get("builtin").asBoolean(),
                "API 建的恒为非内置：否则「标成非内置再删掉」就是一条绕过 409 的路");
        assertFalse(created.get("autoApplyAllowed").asBoolean(),
                "不在 §5.3 白名单里的类型不允许自动应用（防误脱敏）");

        // 同一编码再建 → 409（唯一键是 type_code）
        assertEquals(409, status("POST", M + "/sensitive-types",
                """
                {"typeCode": "CUSTOM_EMPNO", "typeName": "重复", "category": "PII", "level": "L2"}
                """), "同一编码重复创建要 409");

        // 停用走 PUT 且 enabled=false；**逐字段语义**：没提交的 detectors 必须保持原值
        JsonNode disabled = json(200, "PUT", M + "/sensitive-types/CUSTOM_EMPNO",
                "{\"enabled\": false}");
        assertFalse(disabled.get("enabled").asBoolean());
        assertTrue(disabled.get("detectors").has("name"),
                "逐字段更新不该把没提交的 detectors 清掉");

        json(204, "DELETE", M + "/sensitive-types/CUSTOM_EMPNO", null);
        assertEquals(404, status("GET", M + "/sensitive-types/CUSTOM_EMPNO", null),
                "自定义类型物理删除后 404");
    }

    // ------------------------------------------------------------------ 2. 三层覆盖

    @Test
    @Order(3)
    void executionOverrideIsThreeLayered() throws Exception {
        // 第一层（全局）与「没有覆盖」的形状
        JsonNode global = json(200, "GET", M + "/sensitivity-settings?catalog=" + PREFIX, null);
        assertEquals("SPARK", global.get("resolvedExecution").asString(),
                "没有任何覆盖时生效值是全局默认（本类把它设成 SPARK 正是为了验覆盖）");
        assertEquals("paimon", global.get("catalog").asString());

        // 第二层：catalog 覆盖。不带 entityVersion = 跳过乐观锁
        // （与 ManagementCatalogService.update 的 currentEntityVersion 同口径）
        JsonNode catalogLevel = json(200, "PUT", M + "/sensitivity-settings?catalog=" + PREFIX,
                """
                {"defaultExecution": "IN_PROCESS", "maxConcurrentScans": 3}
                """);
        assertEquals("IN_PROCESS", catalogLevel.get("defaultExecution").asString());
        assertEquals("IN_PROCESS", catalogLevel.get("resolvedExecution").asString(),
                "catalog 覆盖要压过全局默认");
        assertEquals(3, catalogLevel.get("resolvedMaxConcurrentScans").asInt(),
                "maxConcurrentScans 也要按 catalog 覆盖生效");

        // 重新读一遍：覆盖是**落库**的，不是只在 PUT 的响应里对
        JsonNode reread = json(200, "GET", M + "/sensitivity-settings?catalog=" + PREFIX, null);
        assertEquals("IN_PROCESS", reread.get("resolvedExecution").asString(),
                "重读必须还是覆盖值");
        assertEquals(3, reread.get("resolvedMaxConcurrentScans").asInt());

        // 读接口同时回「覆盖值」与「生效值」：否则界面回答问题要取两次配置，
        // 同一件事就有了两个答案源
        assertNotNull(reread.get("resolvedPropagationMaxDepth"), "血缘跳数的生效值也要回");
        assertTrue(reread.get("entityVersion").asInt() > 0, "改设置要递增 catalog 的版本");

        // 变更进审计（设计稿 §9：PUT 走乐观锁且变更进审计）
        assertTrue(eventTypes().contains("sensitivity.binding.changed"),
                "治理设置变更要发 sensitivity.binding.changed");
    }

    // ------------------------------------------------------------------ 3. 扫描（P0 只有元数据档）

    @Test
    @Order(4)
    void scanRunsMetadataOnlyAndIsIdempotent() throws Exception {
        createPaimonTable();

        // P0 未实现采样档：必须明确回 501，**不静默降级**成元数据档——
        // 降级会得到一个「扫完了、只发现 0 列」的结论，而调用方以为已按值判过
        assertEquals(501, status("POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "SAMPLED"}
                """), "P0 的采样档未实现，要 501 而不是当成元数据档跑一遍");

        // ★ 第三层覆盖的证明：catalog 上写的是 IN_PROCESS，请求里显式要 SPARK。
        // 而 METADATA_ONLY + SPARK 恒被拒（元数据下推没有收益）→ 400。
        // 若请求级被忽略，生效值会是 IN_PROCESS，于是这里会**跑成功**而不是 400。
        assertEquals(400, status("POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY", "execution": "SPARK"}
                """), "请求级 execution=SPARK 与元数据档不兼容 → 400（不静默退回 A）");

        JsonNode accepted = json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY", "execution": "IN_PROCESS"}
                """);
        String scanId = accepted.get("scanId").asString();
        assertNotNull(scanId);
        assertEquals("SUCCEEDED", accepted.get("status").asString(),
                "P0 是同步执行：响应回来时已经是终态");
        assertEquals("IN_PROCESS", accepted.get("execution").asString());

        // 刻意加的两条：第二次扫**同一张表**，且**请求里仍然带 execution=IN_PROCESS**。
        // 这是为了证明「没在请求里指定时用 catalog 覆盖」不会退化成「恒用全局值」——
        // 若第三层被忽略、生效值回落全局的 SPARK，这次请求就是 #4 那个 400；
        // 而若同表闸门把上一轮算成「还在跑」，这里会 409。
        assertEquals(202, status("POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY", "execution": "IN_PROCESS"}
                """), "同表重扫要放行（上一轮已 SUCCEEDED），且请求级 IN_PROCESS 仍生效");

        JsonNode detail = json(200, "GET", M + "/sensitivity-scans/" + scanId, null);
        assertEquals("SUCCEEDED", detail.get("status").asString());
        assertEquals("IN_PROCESS", detail.get("execution").asString(),
                "落库的是**生效值**，这样「三个月后回看这次扫描跑在哪」答得上来");
        assertEquals("MANUAL", detail.get("triggerType").asString(),
                "没传 triggerType 时是手工触发");
        assertEquals("METADATA_ONLY", detail.get("mode").asString());
        assertEquals(1, detail.get("tablesScanned").asInt());
        assertTrue(detail.get("columnsScanned").asInt() >= 3,
                () -> "元数据档至少扫到 id / name / id_card 三列：" + detail);
        assertEquals(0, detail.get("tablesFailed").asInt(),
                "people 表有 schema_doc，不该落进 tables_failed");
        assertTrue(detail.get("findingsCreated").asInt() >= 1,
                () -> "id_card 列应被 CN_ID_CARD 命中：" + detail);

        // finding 是「判过什么」的历史，它确实产出了
        List<JsonNode> findings = findingsOf(scanId, "CN_ID_CARD");
        assertEquals(1, findings.size(), () -> "这一轮应恰好有一条 CN_ID_CARD 命中：" + findings);
        assertEquals("id_card", findings.get(0).get("column").asString());
        assertEquals("PENDING", findings.get(0).get("state").asString(),
                "0.45 够不到 0.85，只能进人审；P0 没有人审 UI，因此它停在 PENDING");

        // ★ P0 的元数据档得分上限 0.45 < 默认阈值 0.85 → 自动落标数为 0。
        // 这正是「P0 只产候选、不产标签」与「P0 不需要抑制项」的共同根因。
        JsonNode tags = json(200, "GET", M + "/column-tags?catalog=" + PREFIX, null);
        assertEquals(0, tags.get("items").size(),
                "元数据档够不到 0.85，因此这一屏应当是空的（命中去 finding 里看）");
        assertEquals(0, tagRepository.count(),
                "库里的标签行数也应为 0（不是只在响应里被过滤掉）");
    }

    // ------------------------------------------------------------------ 4. 手工打标与只读

    @Test
    @Order(5)
    void manualTagIsUpsertedNotDuplicated() throws Exception {
        // 前提检查：本类的前一个用例已经断言过标签表为空，
        // 这里再确认一次是为了让「重扫不复制标签」那条断言的基数是确定的
        assertEquals(0, tagRepository.count(), "本用例的前提是标签表为空");

        JsonNode written = json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "sens_db", "table": "people",
                 "column": "id_card", "tagKey": "PII.CN_ID_CARD", "tagValue": "L4"}
                """);
        long id = written.get("id").asLong();
        assertTrue(id > 0);
        assertEquals("MANUAL", written.get("source").asString(),
                "来源恒为 MANUAL，且不由请求体决定");
        assertEquals(0, written.get("entityVersion").asInt(), "新建的标签版本从 0 起");
        assertEquals("L4", written.get("level").asString(), "tagValue 就是分级");

        // ★ 幂等：同一列同一 tag_key 再写一次是 upsert 而不是新增
        // （唯一键 uk_column_tag = column_ref_hash + tag_key）
        JsonNode again = json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "sens_db", "table": "people",
                 "column": "id_card", "tagKey": "PII.CN_ID_CARD", "tagValue": "L4",
                 "entityVersion": 0}
                """);
        assertEquals(id, again.get("id").asLong(), "同一列同一 tag_key 复用的是同一行");
        assertEquals(1, again.get("entityVersion").asInt(), "改过之后版本 +1");
        assertEquals(1, tagRepository.count(), "重写不该产生第二行");

        // 已存在的标签**必须**带版本：丢的是一条人工治理结论，而人工结论
        // 无法从任何地方重建（所以这里选「不给版本就不让改」这一侧）
        assertEquals(409, status("PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "sens_db", "table": "people",
                 "column": "id_card", "tagKey": "PII.CN_ID_CARD", "tagValue": "L3"}
                """), "已存在的标签不带 entityVersion → 409");

        // 表级标签：column 为空串。它进不了「已分类列」的分子（分母是列数），
        // 但要让「这张表里有 L3」这件事有地方存
        json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "sens_db", "table": "people",
                 "column": "", "tagKey": "PII.TABLE_LEVEL", "tagValue": "L3"}
                """);
        assertEquals(2, tagRepository.count());

        // ★ 重扫一次：finding 是逐轮追加的历史，但**标签不该被复制、也不该被覆盖**
        json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY"}
                """);
        assertEquals(2, tagRepository.count(),
                "重扫只写 finding，不动标签表——自动路径在 P0 够不到阈值，"
                        + "因此它也没有资格覆盖人工结论");

        // level 过滤下推到服务端（不是把全部取回来在前端筛）
        JsonNode l4 = json(200, "GET", M + "/column-tags?catalog=" + PREFIX + "&level=L4", null);
        assertEquals(1, l4.get("items").size(), "L4 只有那条列级标签");
        assertEquals("id_card", l4.get("items").get(0).get("column").asString());
        JsonNode l3 = json(200, "GET", M + "/column-tags?catalog=" + PREFIX + "&level=L3", null);
        assertEquals(1, l3.get("items").size(), "L3 只有那条表级标签");

        // 覆盖率：分子只算「已分类的列」，表级标签不进分子——
        // 把它算进去会让分子（列 + 表）与分母（列数）不可比
        JsonNode coverage = l4.get("coverage");
        assertEquals(1, coverage.get("classifiedColumns").asInt(),
                "分母来自 schema_doc，分子只算打了标签的列");
        assertTrue(coverage.get("tablesWithTags").asInt() >= 1);
        assertTrue(coverage.path("bySource").path("MANUAL").asInt() >= 1,
                () -> "来源分布要能看出是人工打的：" + coverage);

        // 打标要发事件（§8.4：下游订阅方据此增量更新）
        assertTrue(eventTypes().contains("column.tag.changed"), "打标要发 column.tag.changed");

        json(204, "DELETE", M + "/column-tags/" + id, null);
        assertEquals(1, tagRepository.count());
        assertEquals(404, status("DELETE", M + "/column-tags/" + id, null),
                "删一个不存在的标签 → 404（资源类型是 COLUMN_TAG）");
    }

    // ------------------------------------------------------------------ 5. 改权重 → 下一轮判定改变

    @Test
    @Order(6)
    void changingBuiltinWeightChangesTheNextScan() throws Exception {
        createPaimonTable();

        // 第一次扫：CN_ID_CARD 还是 seed 原值
        // （name 权重 1.0 + type 层命中 varchar(18)）→ 0.35 + 0.10 = 0.45
        JsonNode before = json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY"}
                """);
        double firstConfidence = confidenceOf(before.get("scanId").asString(), "CN_ID_CARD");
        assertEquals(0.45, firstConfidence, 1e-9,
                "seed 原值下 name 层满分 + type 层命中 = 0.35 + 0.10");

        // 把名称层的权重调到 0.8（类型层原样保留，避免把「权重变了」与
        // 「层被删了」两件事混在一起）→ 0.35 × 0.8 + 0.10 = 0.38
        json(200, "PUT", M + "/sensitive-types/CN_ID_CARD",
                """
                {"detectors": {"name": {"patterns": ["id_card", "idno", "id_no", "idcard",
                                                     "identity", "身份证", "证件号", "公民身份号码"],
                                        "weight": 0.8},
                               "type": {"types": ["char", "varchar", "string"],
                                        "lengths": [[15, 18]]}}}
                """);

        JsonNode after = json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "sens_db",
                 "table": "people", "mode": "METADATA_ONLY"}
                """);
        double afterConfidence = confidenceOf(after.get("scanId").asString(), "CN_ID_CARD");

        // ★ 验收断言：改一个内置类型的探测器权重后，下一轮扫描的判定随之改变。
        // 这一步漏了的表现最隐蔽——权重配了、库里存了、控制台也显示得出来，
        // 而判定一字不变（SensitivityDetectors 漏乘 weight 时就是这样）。
        assertEquals(0.38, afterConfidence, 1e-9,
                "新权重下应当是 0.35 × 0.8 + 0.10");
        assertTrue(afterConfidence < firstConfidence,
                () -> "权重调低，置信度应下降：" + firstConfidence + " -> " + afterConfidence);

        // 两次扫描各自的 finding 都留着（finding 是逐轮的历史，不是「当前值」）；
        // 「现在这列是什么」问的是 /column-tags
        assertEquals(1, findingsOf(after.get("scanId").asString(), "CN_ID_CARD").size());
        assertEquals(1, findingsOf(before.get("scanId").asString(), "CN_ID_CARD").size(),
                "上一轮的结论不该被这一轮改写");
    }

    // ------------------------------------------------------------------ 夹具与工具

    private List<String> eventTypes() {
        return eventRepository.findAll().stream()
                .map(event -> event.getType())
                .toList();
    }

    private static JsonNode find(JsonNode list, String typeCode) {
        for (JsonNode item : list.get("items")) {
            if (typeCode.equals(item.get("typeCode").asString())) {
                return item;
            }
        }
        throw new AssertionError("type not found: " + typeCode);
    }

    /**
     * 取某次扫描里某个类型的命中。
     *
     * <p>{@code /sensitivity-findings} 的查询轴只有 catalog / state / table / type
     * （**没有 scanId**，因为它的设计用途是「这个 catalog 目前判过什么」而不是
     * 「这一次判过什么」），所以按扫描过滤要在这里做。用 {@code type} 收窄是为了
     * 让返回集与扫描次数同阶，而不是与「历次扫描命中的全部列」同阶。
     */
    private List<JsonNode> findingsOf(String scanId, String typeCode) throws Exception {
        JsonNode list = json(200, "GET", M + "/sensitivity-findings?catalog=" + PREFIX
                + "&type=" + typeCode, null);
        List<JsonNode> matched = new java.util.ArrayList<>();
        for (JsonNode item : list.get("items")) {
            if (scanId.equals(item.get("scanId").asString())) {
                matched.add(item);
            }
        }
        return matched;
    }

    private double confidenceOf(String scanId, String typeCode) throws Exception {
        List<JsonNode> matched = findingsOf(scanId, typeCode);
        if (matched.isEmpty()) {
            throw new AssertionError("no " + typeCode + " finding in scan " + scanId);
        }
        return matched.get(0).get("confidence").asDouble();
    }

    /** 建一张含 {@code id_card} 列的表：列名要能被 CN_ID_CARD 的 name 层命中。 */
    private void createPaimonTable() {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(DB)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(DB, Map.of()));
        }
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(
                catalogId(), databaseId(DB), TABLE).isPresent()) {
            return;
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(100)"));
        // 长度落在 15–18 里：CN_ID_CARD 的 type 层区间正是 [15, 18]
        schema.getFields().add(field(2, "id_card", "VARCHAR(18)"));
        schema.getPrimaryKeys().add("id");
        schema.getOptions().put("bucket", "1");
        tableService.create(PREFIX, DB,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(DB, TABLE), schema));
    }

    private String catalogId() {
        return databaseService.require(PREFIX, DB).getCatalogId();
    }

    private String databaseId(String database) {
        return databaseService.require(PREFIX, database).getId();
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }

    private int status(String method, String path, String body) throws Exception {
        return mockMvc.perform(request(method, path, body)).andReturn().getResponse().getStatus();
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(request(method, path, body)).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> method + " " + path + " unexpected status, body=" + responseBody);
        if (expected == 204 || responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path);
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        return body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
