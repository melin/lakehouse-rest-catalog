package io.github.melin.lakehouse.rest;

import io.github.melin.lakehouse.rest.support.DetectorConfigJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.scan.DetectorConfig;
import io.github.melin.lakehouse.rest.support.SchemaColumns;
import io.github.melin.lakehouse.rest.scan.ScanColumn;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.CandidateType;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.Match;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.FindingState;
import io.github.melin.lakehouse.rest.scan.SensitivityScorer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 识别引擎的纯函数行为：D1/D2 探测器、打分公式与三条决策带
 * （设计见 {@code docs/sensitive-data-design.md} §5.1–§5.3）。
 *
 * <p><b>为什么这类断言值得写这么细。</b>这一层的输出直接决定「要不要给一列
 * 自动打上脱敏标签」——阈值或系数改动一点点，线上就多落或少落几百条标签，
 * 而这类变化不会让任何构建失败。把它们钉在单元测试里，是唯一能让
 * 「这次改的是不是只是措辞」立刻看出答案的办法。
 *
 * <p>分组：schema 三形态解析 → 打分公式 → 决策带 → 白名单闸门。
 */
class SensitivityDetectionTests {

    // ------------------------------------------------------------- schema_doc 三形态

    /**
     * Paimon 形态：{@code TypeDtos.Schema}，列注释在 {@code description}。
     *
     * <p>这是本工程自己写进去的那一份，形状最可控——但它也最容易被顺手改坏
     * （改 {@code DataField} 字段名会同时影响写侧与读侧，读侧改错了不会有编译错）。
     */
    @Test
    void parsesPaimonSchemaDoc() {
        String doc = "{\"fields\":["
                + "{\"id\":1,\"name\":\"id_card_no\",\"type\":\"CHAR(18)\",\"description\":\"身份证号\"},"
                + "{\"id\":2,\"name\":\"amount\",\"type\":{\"type\":\"DECIMAL\",\"precision\":12},"
                + "\"description\":null}],"
                + "\"partitionKeys\":[],\"primaryKeys\":[],\"options\":{}}";

        List<ScanColumn> columns = SchemaColumns.parse(doc);

        assertEquals(2, columns.size());
        assertEquals("id_card_no", columns.get(0).name());
        assertEquals("CHAR(18)", columns.get(0).type());
        assertEquals("身份证号", columns.get(0).comment());
        assertEquals("char", columns.get(0).normalizedType());
        assertEquals(18, columns.get(0).length(), "CHAR(18) 的长度应当是 18");
        // 嵌套类型形态：只取最外层 type 名。长度在 Paimon 侧由同级字段表达，
        // 不在括号里，因此这里是 null——D2 的长度判据只对字符型有意义（见 ScanColumn#length()）
        assertEquals("decimal", columns.get(1).normalizedType());
        assertNull(columns.get(1).length(), "嵌套形态的类型名里没有括号，取不到长度");
        assertNull(columns.get(1).comment());
    }

    /**
     * Iceberg 形态：原始 schema，列注释在 {@code doc}，类型全小写。
     *
     * <p>这一份是 {@code SchemaParser.toJson} 的产物，因此键名与 Paimon 侧不一样。
     * 两条分支都要真的读到注释——只认 {@code description} 的话，Iceberg 侧的注释
     * 会被静默忽略，表现为「同一张表在 Paimon 上判得出、在 Iceberg 上判不出」。
     */
    @Test
    void parsesIcebergSchemaDoc() {
        String doc = "{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
                + "{\"id\":1,\"name\":\"mobile\",\"required\":false,\"type\":\"string\",\"doc\":\"手机号码\"},"
                + "{\"id\":2,\"name\":\"tags\",\"required\":false,"
                + "\"type\":{\"type\":\"list\",\"element-id\":3,\"element\":\"string\",\"element-required\":false}}]}";

        List<ScanColumn> columns = SchemaColumns.parse(doc);

        assertEquals(2, columns.size());
        assertEquals("手机号码", columns.get(0).comment(), "Iceberg 的列注释在 doc 键上");
        assertEquals("string", columns.get(0).normalizedType());
        assertEquals("list", columns.get(1).normalizedType());
        assertNull(columns.get(1).comment());
    }

    /**
     * 空 / 非法 / Lance 形态（压根不写这一列）一律返回空列表，**不抛异常**。
     *
     * <p>理由是「一次格式变更不该让一个 catalog 的治理停摆」：Lance 面根本不写
     * {@code schema_doc}，如果这里抛异常，整个 Lance 目录的扫描会全部失败。
     */
    @Test
    void emptyOrUnknownSchemaDocYieldsNoColumns() {
        assertTrue(SchemaColumns.parse(null).isEmpty());
        assertTrue(SchemaColumns.parse("").isEmpty());
        assertTrue(SchemaColumns.parse("   ").isEmpty());
        assertTrue(SchemaColumns.parse("not json at all").isEmpty());
        assertTrue(SchemaColumns.parse("{\"fields\":\"nope\"}").isEmpty());
        assertTrue(SchemaColumns.parse("{\"unrelated\":1}").isEmpty());
    }

    // ------------------------------------------------------------------ 打分公式

    /**
     * 公式就是 §5.2 那一行，逐项验。
     *
     * <p>用「只有 name」与「name + type」两条对照：0.35 / 0.10 这两个系数
     * 一旦被改，这里是第一个红的地方。
     */
    @Test
    void weightsMatchTheDesignFormula() {
        Map<DetectorKind, Double> nameOnly = scores(Map.of(DetectorKind.NAME, 1.0));
        assertEquals(0.35, SensitivityScorer.confidence(nameOnly, 0, false), 1e-9);

        Map<DetectorKind, Double> nameAndType = scores(Map.of(
                DetectorKind.NAME, 1.0, DetectorKind.TYPE, 1.0));
        assertEquals(0.45, SensitivityScorer.confidence(nameAndType, 0, false), 1e-9);

        Map<DetectorKind, Double> all = scores(Map.of(
                DetectorKind.NAME, 1.0, DetectorKind.TYPE, 1.0, DetectorKind.VALUE, 1.0,
                DetectorKind.DICT, 1.0, DetectorKind.STAT, 1.0));
        assertEquals(1.0, SensitivityScorer.confidence(all, 0, false), 1e-9,
                "五层全满应当恰好是 1.0（系数之和为 1）");
    }

    /**
     * 值命中率的分段线性：95% 满分、60% 归零、中间线性。
     *
     * <p>刻意断言中间点（0.775 处应得 0.5）而不是只测两端：两端在「一刀切」
     * 的实现下也是对的，只有中间点能区分「线性」与「阈值」。
     */
    @Test
    void valueScoreIsPiecewiseLinear() {
        assertEquals(1.0, SensitivityScorer.valueScore(95, 100, null), 1e-9);
        assertEquals(1.0, SensitivityScorer.valueScore(100, 100, null), 1e-9);
        assertEquals(0.0, SensitivityScorer.valueScore(60, 100, null), 1e-9);
        assertEquals(0.0, SensitivityScorer.valueScore(10, 100, null), 1e-9);
        // (0.775 - 0.60) / (0.95 - 0.60) = 0.5
        assertEquals(0.5, SensitivityScorer.valueScore(775, 1000, null), 1e-6);
        // 分母是**非空行**：1000 行里 500 空、命中 490 → 命中率 490/500 = 0.98 ≥ 0.95，满分。
        // 若分母错用总行数，命中率会变成 0.49 直接归零——这条断言就是为了钉住「分母是非空行」
        assertEquals(1.0, SensitivityScorer.valueScore(490, 500, null), 1e-9);
        // 真正落在中间区间的：0.90 → (0.90 - 0.60) / (0.95 - 0.60) = 0.857142...
        assertEquals(0.8571428571428571, SensitivityScorer.valueScore(900, 1000, null), 1e-9);
    }

    /**
     * 校验器通过率不够 99% 时把整层得分乘上去——**校验位是区分「真身份证」
     * 与「随便编的 18 位」的唯一手段**（§5.1 的注）。
     */
    @Test
    void validatorPassRateDampensValueScore() {
        assertEquals(1.0, SensitivityScorer.valueScore(100, 100, 1.0), 1e-9);
        assertEquals(0.9, SensitivityScorer.valueScore(100, 100, 0.9), 1e-9,
                "通过率 90% 应当把满分拉到 0.9");
        assertEquals(0.693, SensitivityScorer.valueScore(100, 100, 0.693), 1e-9);
    }

    /**
     * 样本量不足 200 行时整体打七折，**但只在采样模式下**。
     *
     * <p>元数据模式没有「样本量」这个概念，不该因为「没采样」被罚——
     * 那条折损的前提是「样本太少所以置信度不可信」，而元数据模式压根没有样本。
     */
    @Test
    void smallSampleDampensOnlyWhenSampled() {
        Map<DetectorKind, Double> perfect = scores(Map.of(DetectorKind.NAME, 1.0, DetectorKind.VALUE, 1.0));
        // 未折损的基准：0.35 + 0.40 = 0.75
        assertEquals(0.75, SensitivityScorer.confidence(perfect, 100, false), 1e-9,
                "元数据模式不做样本量折损");
        assertEquals(0.75, SensitivityScorer.confidence(perfect, 200, true), 1e-9,
                "恰好 200 行是够的（阈值是「少于」）");
        // 采样模式下样本不足：0.75 × 0.7 = 0.525
        assertEquals(0.525, SensitivityScorer.confidence(perfect, 100, true), 1e-9,
                "0.35 + 0.40 = 0.75，样本 100 行 < 200 → ×0.7 = 0.525");
        assertEquals(0.525, SensitivityScorer.confidence(perfect, 0, true), 1e-9,
                "一行样本都没有时同样折损，且不比 100 行更狠（折扣是定值不是比例）");
    }

    // ------------------------------------------------------------------ 三条决策带

    /**
     * 三条带的边界值：阈值处自动落标、0.60 处进人审、0.599 丢弃。
     *
     * <p>阈值与下界都是**闭区间下界**（{@code >=}）。边界方向搞反的表现是
     * 「置信度恰好等于阈值的那些列全部进人审」，而它们本该是最该自动落标的一批。
     */
    @Test
    void decisionBandsAreInclusiveAtTheLowerBound() {
        assertEquals(FindingState.AUTO_APPLIED,
                SensitivityScorer.decide(0.85, 0.85, true), "恰好到阈值应当自动落标");
        assertEquals(FindingState.PENDING,
                SensitivityScorer.decide(0.849, 0.85, true), "差一点应当进人审");
        assertEquals(FindingState.PENDING,
                SensitivityScorer.decide(0.60, 0.85, true), "恰好 0.60 应当进人审");
        assertNull(SensitivityScorer.decide(0.599, 0.85, true), "低于 0.60 应当丢弃");
        assertNull(SensitivityScorer.decide(0.0, 0.85, true));
    }

    /**
     * {@code auto_apply=false} 的类型**哪怕置信度 1.0 也只进人审**（§5.3）。
     *
     * <p>这是防误脱敏的核心闸门：`PERSON_NAME` 这类类型的误报率天然高，
     * 一旦自动应用就是「数据被 mask 掉但没人知道为什么」。
     */
    @Test
    void autoApplyFalseNeverAutoApplies() {
        assertEquals(FindingState.PENDING, SensitivityScorer.decide(1.0, 0.85, false));
        assertEquals(FindingState.PENDING, SensitivityScorer.decide(0.99, 0.5, false));
        assertNull(SensitivityScorer.decide(0.10, 0.5, false));
    }

    /** 分级是否允许自动产出：{@code L1}–{@code L4} 可以，{@code CORE}/{@code IMPORTANT} 不行。 */
    @Test
    void onlyL1ToL4AreAutomatable() {
        for (String level : List.of("L1", "L2", "L3", "L4")) {
            assertTrue(SensitivityScorer.levelAutomatable(level), level + " 应当允许自动产出");
        }
        for (String level : List.of("CORE", "IMPORTANT", "l1", "", "X")) {
            assertTrue(!SensitivityScorer.levelAutomatable(level),
                    level + " 不该被当成可自动产出的分级（CORE/IMPORTANT 只能人工打）");
        }
    }

    // --------------------------------------------------- D1/D2 端到端（探测器组合）

    /**
     * 列名命中且类型形状吻合 → 置信度 0.45，够不着 0.85 阈值 → 人审。
     *
     * <p>这是**纯元数据模式的真实上限**：名字 + 类型最多 0.45，永远不到 0.85。
     * 这条断言把「P0 只做到建议、做不到自动落标」这件事钉住——
     * 想自动落标必须等 D3（值层）上来（P1）。
     */
    @Test
    void metadataOnlyNeverReachesAutoApply() {
        List<CandidateType> types = List.of(idCardType(true));

        List<Match> matches = SensitivityDetectors.detect("cat", "user_profile",
                new ScanColumn("id_card_no", "CHAR(18)", "身份证号"), types);

        assertEquals(1, matches.size());
        Match match = matches.get(0);
        assertEquals("CN_ID_CARD", match.typeCode());
        assertEquals(0.45, match.confidence(), 1e-9);
        assertEquals(FindingState.PENDING, match.state(),
                "元数据模式上限 0.45，只能进人审——自动落标要等值层（P1）");
        assertEquals(1.0, match.scores().get(DetectorKind.NAME), 1e-9);
        assertEquals(1.0, match.scores().get(DetectorKind.TYPE), 1e-9);
    }

    /**
     * 名字不像、类型也不像 → 不产出任何命中（**不产出一条 0 分的记录**）。
     *
     * <p>finding 表按「类型数 × 列数」写的话，一次 catalog 扫描能在一天内
     * 被噪声填满；只有真正命中过至少一层的列才落库。
     */
    @Test
    void unrelatedColumnProducesNoMatch() {
        List<Match> matches = SensitivityDetectors.detect("cat", "orders",
                new ScanColumn("order_status", "INT", "订单状态"), List.of(idCardType(true)));
        assertTrue(matches.isEmpty(), "一层都没命中不该产出 finding");
    }

    /**
     * 名字命中但类型不对（{@code INT} 而非定长字符）→ 只有 0.35，仍进人审。
     *
     * <p>与上一条的区别是「命中了一层」：这条要产出 finding（供人审看到
     * 「列名叫身份证但类型是整数」这个可疑信号），上一条完全不产出。
     */
    @Test
    void nameOnlyHitStillProducesMatch() {
        List<Match> matches = SensitivityDetectors.detect("cat", "t",
                new ScanColumn("id_card", "INT", null), List.of(idCardType(true)));

        assertEquals(1, matches.size());
        assertEquals(0.35, matches.get(0).confidence(), 1e-9);
        assertEquals(FindingState.PENDING, matches.get(0).state());
        assertTrue(!matches.get(0).scores().containsKey(DetectorKind.TYPE),
                "类型层没命中就不该出现在 scores 里（与「得 0 分」不是一回事）");
    }

    /**
     * 分级为 {@code CORE} 的类型：哪怕分数已经过了阈值也只能落到 {@code PENDING}。
     *
     * <p>这是 §5.5 的最后一道闸门——探测器没有资格产出「核心数据」这个结论。
     * 类型表本身不该配出 CORE（只有 L1–L4），这里防的是有人绕过类型表直写配置。
     *
     * <p><b>刻意用「同一份配置 + 同一个分数」的对照组</b>：把 {@code level} 从
     * {@code L4} 换成 {@code CORE}，分数一字不变，只有 {@code state} 变。
     * 这样断言失败时能直接指出是分级闸门坏了，而不是分数算错了。
     */
    @Test
    void coreLevelIsDowngradedToPending() {
        DetectorConfig config = DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\"]},"
                + "\"type\":{\"types\":[\"char\"],\"lengths\":[[15,18]]}}");
        // 阈值压到 0.40：0.45 的元数据判定本该自动落标，好让分级闸门成为唯一变量
        CandidateType core = new CandidateType("CN_ID_CARD", "CORE", 0.40, true, config);
        CandidateType l4 = new CandidateType("CN_ID_CARD", "L4", 0.40, true, config);
        ScanColumn column = new ScanColumn("id_card_no", "CHAR(18)", null);

        Match l4Match = SensitivityDetectors.detect("cat", "t", column, List.of(l4)).get(0);
        Match coreMatch = SensitivityDetectors.detect("cat", "t", column, List.of(core)).get(0);

        assertEquals(0.45, l4Match.confidence(), 1e-9);
        assertEquals(l4Match.confidence(), coreMatch.confidence(), 1e-9,
                "两个对照的分数必须完全相同");
        assertEquals(FindingState.AUTO_APPLIED, l4Match.state(), "L4 + 过阈值 → 自动落标");
        assertEquals(FindingState.PENDING, coreMatch.state(),
                "CORE 分级即使同一份配置、同一个分数也必须降级为待审");
        assertTrue(!SensitivityScorer.levelAutomatable(core.level()));
    }

    /** 多个类型同时命中时按置信度降序——人审队列先看最可能对的。 */
    @Test
    void matchesAreSortedByConfidenceDescending() {
        CandidateType idCard = idCardType(true);
        CandidateType weaker = new CandidateType("SOME_TYPE", "L2", 0.85, false,
                DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\"]}}"));

        List<Match> matches = SensitivityDetectors.detect("cat", "t",
                new ScanColumn("id_card_no", "CHAR(18)", null), List.of(weaker, idCard));

        assertEquals(2, matches.size());
        assertEquals("CN_ID_CARD", matches.get(0).typeCode(), "0.45 应当排在 0.35 前面");
        assertEquals(0.45, matches.get(0).confidence(), 1e-9);
        assertEquals(0.35, matches.get(1).confidence(), 1e-9);
    }

    // ------------------------------------------------------------- 探测器配置往返

    /** 配置序列化—反序列化往返一致（控制台编辑保存的路径）。 */
    @Test
    void detectorConfigRoundTrips() {
        String json = "{\"name\":{\"patterns\":[\"id_card\",\"身份证\"]},"
                + "\"type\":{\"types\":[\"char\"],\"lengths\":[[15,18]]},"
                + "\"value\":{\"regex\":\"^[1-9]\\\\d{5}$\",\"validator\":\"CN_ID_CARD\"}}";

        DetectorConfig parsed = DetectorConfigJson.parse(json);
        assertEquals(List.of(DetectorKind.NAME, DetectorKind.TYPE, DetectorKind.VALUE),
                parsed.configuredLayers());

        DetectorConfig again = DetectorConfigJson.parse(DetectorConfigJson.toJson(parsed));
        assertEquals(parsed.configuredLayers(), again.configuredLayers());
        assertEquals(List.of("id_card", "身份证"), again.layer(DetectorKind.NAME).patterns());
        assertEquals(List.of("char"), again.layer(DetectorKind.TYPE).types());
        assertEquals(List.of(List.of(15, 18)), again.layer(DetectorKind.TYPE).lengths());
        assertEquals("CN_ID_CARD", again.layer(DetectorKind.VALUE).validator());
    }

    /** 配坏的形状被忽略（该层消失），而不是让整个类型不可用。 */
    @Test
    void malformedDetectorConfigIsIgnoredNotFatal() {
        assertEquals(List.of(), DetectorConfigJson.parse("not json").configuredLayers());
        assertEquals(List.of(), DetectorConfigJson.parse("{\"name\":\"not-an-object\"}").configuredLayers());
        assertEquals(List.of(), DetectorConfigJson.parse("{}").configuredLayers());
        // 空 patterns 的层等于没配
        assertEquals(List.of(), DetectorConfigJson.parse("{\"name\":{\"patterns\":[]}}").configuredLayers());
    }

    /** 名称层命中判定不看大小写，且列注释也算依据。 */
    @Test
    void nameHitIsCaseInsensitiveAndLooksAtComments() {
        assertEquals(1.0, DetectorConfig.nameHit(List.of("id_card"), "ID_CARD_NO", null), 1e-9);
        assertEquals(1.0, DetectorConfig.nameHit(List.of("身份证"), "c1", "这是身份证号"), 1e-9);
        assertEquals(0.0, DetectorConfig.nameHit(List.of("id_card"), "mobile", "手机号"), 1e-9);
        assertEquals(0.0, DetectorConfig.nameHit(List.of(), "id_card", null), 1e-9);
    }

    /** 类型层：类型名必须命中，长度区间是闭区间。 */
    @Test
    void typeHitRespectsBothTypeAndLength() {
        DetectorConfig.LayerConfig config = new DetectorConfig.LayerConfig(
                List.of(), List.of("char", "varchar"), List.of(List.of(15, 18)),
                null, null, List.of(), null, 1.0);

        assertEquals(1.0, DetectorConfig.typeHit(config, "char", 18), 1e-9);
        assertEquals(1.0, DetectorConfig.typeHit(config, "varchar", 15), 1e-9);
        assertEquals(0.0, DetectorConfig.typeHit(config, "char", 19), 1e-9, "19 超出闭区间");
        assertEquals(0.0, DetectorConfig.typeHit(config, "int", 18), 1e-9, "类型名不符");
        assertEquals(0.0, DetectorConfig.typeHit(config, "char", null), 1e-9,
                "配了长度区间却没长度 → 不命中（而不是「忽略长度」）");
    }

    /**
     * 层内 {@code weight} 真的参与打分，且默认 1.0 时逐字不变。
     *
     * <p>这是 §12 P0 验收断言「改一个内置类型的探测器权重后，下一轮扫描的判定随之
     * 改变」的那条链路的单元证明。漏乘 weight 的表现极其隐蔽：权重配了、存了、
     * 控制台也显示得出来，**但判定一字不变**——失败方式是「看起来一切正常，
     * 只是改什么都不生效」。
     *
     * <p><b>两个方向都要断言</b>：只测「乘了」不足以防回归，还要测「默认不乘成别的东西」——
     * 把 weight 写进公式时最容易出的错是把它当成**额外的一档层系数**
     * （比如 0.35 × 1.0 写成 0.35 + 1.0）。默认值那一组用 0.45 / 0.35
     * 这两个与既有用例相同的数把公式钉回原位。
     */
    @Test
    void layerWeightScalesTheLayerScore() {
        ScanColumn column = new ScanColumn("id_card_no", "CHAR(18)", null);

        // 默认 1.0：与 metadataOnlyNeverReachesAutoApply 里的 0.45 完全一致
        Match defaults = SensitivityDetectors.detect("cat", "t", column, List.of(idCardType(true))).get(0);
        assertEquals(0.45, defaults.confidence(), 1e-9, "weight=1.0 时公式必须逐字不变");
        assertEquals(1.0, defaults.scores().get(DetectorKind.NAME), 1e-9);

        // 名称层权重降到 0.8：s_name 由 1.0 变 0.8 → 0.35×0.8 + 0.10 = 0.38，仍在 0.35 下界之上。
        // 挑 0.8 而不是 0.5 是因为 0.5 会把总分压到 0.275，**低于元数据档下界**，
        // 那样命中的是「被丢弃」这条另一路的逻辑，测不到「分数变了」（见下一条）。
        CandidateType softerName = new CandidateType("CN_ID_CARD", "L4", 0.85, true,
                DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\"],\"weight\":0.8},"
                        + "\"type\":{\"types\":[\"char\"],\"lengths\":[[15,18]]}}"));
        Match softened = SensitivityDetectors.detect("cat", "t", column, List.of(softerName)).get(0);
        assertEquals(0.38, softened.confidence(), 1e-9,
                "名称层权重 0.8 → 该层得分 0.8，总分应当是 0.35×0.8 + 0.10 = 0.38");
        assertEquals(0.8, softened.scores().get(DetectorKind.NAME), 1e-9);
        assertEquals(FindingState.PENDING, softened.state());

        // 权重砍到 0.5：总分 0.275 **低于元数据档下界 0.35** → 整条命中消失。
        // 这条断言把「权重参与打分」与「下界决定是否产出」两件事绑在一起——它们是两次
        // 独立的判定，只测其中一个会让另一个的回归静默通过（改动权重是否影响产出）。
        CandidateType halfName = new CandidateType("CN_ID_CARD", "L4", 0.85, true,
                DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\"],\"weight\":0.5},"
                        + "\"type\":{\"types\":[\"char\"],\"lengths\":[[15,18]]}}"));
        assertTrue(SensitivityDetectors.detect("cat", "t", column, List.of(halfName)).isEmpty(),
                "名称层权重 0.5 → 0.275 < 0.35 下界，该列不再产出命中");

        // 权重压到 0 = 这一层被关掉。只剩类型层时总分 0.10，同样低于下界——
        // 这与「定长字符列满库都是，光凭形状提不出候选」是同一条规则（见 METADATA_FLOOR）。
        CandidateType zeroName = new CandidateType("CN_ID_CARD", "L4", 0.85, true,
                DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\"],\"weight\":0.0},"
                        + "\"type\":{\"types\":[\"char\"],\"lengths\":[[15,18]]}}"));
        assertTrue(SensitivityDetectors.detect("cat", "t", column, List.of(zeroName)).isEmpty(),
                "名称层权重 0 后只剩类型层（0.10 < 0.35 下界），该列不再产出命中");
    }

    /**
     * {@code weight} 往返可存：0.0 也要能存回来。
     *
     * <p>{@code DetectorConfig.toJson} 只在 {@code weight != 1.0} 时写这个键，
     * 而 {@code 0.0 != 1.0} 为真，所以 0 会被写出去。这条断言防的是有人把判据
     * 从「不等于 1」改成「大于 0」——那样 {@code weight: 0}（关掉一层）
     * 保存后会被静默抹成默认 1.0，即**「我明明关掉了它，重启后它又回来了」**。
     */
    @Test
    void zeroWeightSurvivesRoundTrip() {
        DetectorConfig parsed = DetectorConfigJson.parse(
                "{\"name\":{\"patterns\":[\"id_card\"],\"weight\":0.0}}");
        assertEquals(0.0, parsed.layer(DetectorKind.NAME).weight(), 1e-9);

        DetectorConfig again = DetectorConfigJson.parse(DetectorConfigJson.toJson(parsed));
        assertEquals(0.0, again.layer(DetectorKind.NAME).weight(), 1e-9,
                "weight=0 必须能被存回来；被抹成 1.0 就是「关掉的一层自己复活了」");
    }

    // ------------------------------------------------------------------ 夹具

    private static Map<DetectorKind, Double> scores(Map<DetectorKind, Double> raw) {
        Map<DetectorKind, Double> out = new EnumMap<>(DetectorKind.class);
        out.putAll(raw);
        return out;
    }

    /** 内置身份证类型：名字两条正则 + char(15–18) 形状 + 允许自动应用。 */
    private static CandidateType idCardType(boolean autoApply) {
        return new CandidateType("CN_ID_CARD", "L4", 0.85, autoApply,
                DetectorConfigJson.parse("{\"name\":{\"patterns\":[\"id_card\",\"id_no\",\"身份证\"]},"
                        + "\"type\":{\"types\":[\"char\",\"varchar\",\"string\"],\"lengths\":[[15,18]]}}"));
    }
}
