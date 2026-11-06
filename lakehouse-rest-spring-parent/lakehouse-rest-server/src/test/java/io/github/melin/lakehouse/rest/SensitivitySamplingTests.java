package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.paimon.preview.PaimonPreviewTableFactory;
import io.github.melin.lakehouse.rest.service.PreviewCursorCodec;
import io.github.melin.lakehouse.rest.service.SensitivitySampler;
import io.github.melin.lakehouse.rest.service.StorageFileIoFactory;
import io.github.melin.lakehouse.rest.service.TablePreviewService;
import io.github.melin.lakehouse.rest.support.BuiltinSensitiveTypes;
import io.github.melin.lakehouse.rest.support.DetectorConfig;
import io.github.melin.lakehouse.rest.support.SchemaColumns.Column;
import io.github.melin.lakehouse.rest.support.SensitivityDetectors;
import io.github.melin.lakehouse.rest.support.SensitivityDetectors.CandidateType;
import io.github.melin.lakehouse.rest.support.SensitivityDetectors.ColumnValues;
import io.github.melin.lakehouse.rest.support.SensitivityDetectors.Match;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.FindingState;
import io.github.melin.lakehouse.rest.support.SensitivityMasking;
import io.github.melin.lakehouse.rest.support.SensitivityScorer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.schema.FileSystemSchemaManager;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.apache.paimon.table.sink.BatchTableCommit;
import org.apache.paimon.table.sink.BatchTableWrite;
import org.apache.paimon.table.sink.BatchWriteBuilder;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * P1 的 D3 值层：**真实读路径**（{@code SensitivitySampler} → {@code TablePreviewService}
 * → Paimon parquet）→ 打码 → 打分（设计见 {@code docs/sensitive-data-design.md}
 * §5.1 / §5.2 / §5.4 / §12）。
 *
 * <p><b>为什么这一层不能用桩。</b>值层的三个失败模式全都藏在「真读」里：
 * <ol>
 *   <li>列转置把 {@code BinaryString} 直接塞进 {@code ColumnValues}（{@code String.valueOf}
 *       得到的是 {@code BinaryString@1a2b} 而不是值本身）→ 命中率恒 0，
 *       而表现为「这张表没有敏感数据」；</li>
 *   <li>采样样本没打码就落库 → 功能全对，只是库里多了一张明文身份证表；</li>
 *   <li>分母取总行数而不是非空行 → 一列 90% 是 NULL 的真身份证列被判死。</li>
 * </ol>
 * 三条都不是单元桩能造出来的形状。所以本类搭一张真的 parquet 表（500 行），
 * 按扫描服务的方式读一遍、判一遍。
 *
 * <p><b>§12 的两条 P1 验收断言就在这里落地：</b>
 * <ul>
 *   <li>「500 条造数的身份证列，命中率 100%、误报 0」→
 *       {@link #fiveHundredRealIdCardsAreAutoApplied}（100% 命中、自动落标）
 *       与 {@link #lookalikesThatFailTheChecksumAreNotTreatedAsIdCards}（误报 0）；</li>
 *   <li>「{@code PERSON_NAME} 即使置信度 0.99 也进 PENDING」→
 *       {@link #personNameNeverAutoApplies}。</li>
 * </ul>
 *
 * <p>纯 Mockito 装配、不起 Spring 上下文：与 {@code PolicyPreviewTests} 同一手法
 * （它是预览 × 策略那条链路的同一层测试）。
 */
class SensitivitySamplingTests {

    /** 采样量：比 {@code MIN_TRUSTED_SAMPLES}（200）大，避免「样本量不足打折」混进断言。 */
    private static final int ROWS = 500;

    private static final String CATALOG = "paimon";
    private static final String DATABASE = "sales";
    private static final String TABLE = "customers";

    // ------------------------------------------------------------------ 读通道

    /**
     * 采样把「一行一行」转成「一列一组值」，并当场打码。
     *
     * <p>三条断言各守一件事：行数如实（{@code rowsRead} 是样本量折损的输入）、
     * 转置按列名对得上（对不上就是「这一列一直在判别人的值」）、
     * 落库的那份只有打码样本（{@link SensitivityMaskingTests} 守打码本身的形状）。
     */
    @Test
    void sampleTransposesRowsIntoColumnsAndMasksThem(@TempDir Path dir) throws Exception {
        writeTable(dir, validIdCards(ROWS), mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler sampler = sampler(dir);

        SensitivitySampler.TableSample sample = sampler.sample(CATALOG, DATABASE, TABLE,
                List.of("id", "id_card_no", "mobile", "note"), ROWS);

        assertEquals(ROWS, sample.rowsRead(), "500 行的表，采样量也是 500，应当全读到");
        assertEquals("HEAD", sample.samplingMode(),
                "读通道是按文件顺序读前 N 行，没有抽样算子——如实标 HEAD，不编一个更聪明的模式名");

        ColumnValues idCards = sample.values("id_card_no");
        assertEquals(ROWS, idCards.nonNullRows(), "非空行数是值层的分母，500 行里没有空值");
        assertEquals(validIdCards(ROWS).get(0), idCards.values().get(0),
                "转置后第一行的值必须还是原始字符串（BinaryString 没被 String.valueOf 掉）");

        List<String> masked = sample.maskedSamples("id_card_no");
        assertEquals(SensitivityMasking.MAX_SAMPLES, masked.size(), "至多留 5 个样本");
        assertEquals("110***********0011", masked.get(0),
                "样本是打码后的形状（头 3 尾 4），不是原值");
        assertFalse(masked.contains(validIdCards(ROWS).get(0)),
                "5 个样本里不能出现完整原值");
        assertEquals(validIdCards(ROWS).get(0).length(), masked.get(0).length(),
                "打码不改变长度，人审仍能看出「这是不是一个 18 位身份证」");
    }

    /**
     * 没有这一列时不返回 {@code null}，而是 {@link ColumnValues#none()}。
     *
     * <p>这一条看似琐碎，但它是「{@code SAMPLED} 模式下某列读失败」的兜底形状：
     * 那一列的 values 为空 → 只跑 D1/D2 → 下界回落到元数据档的 0.35。
     * 若这里返回 {@code null}，扫描服务会在下一行抛 NPE，整张表记 {@code tables_failed}。
     */
    @Test
    void missingColumnYieldsEmptyValuesNotEmptyNull(@TempDir Path dir) throws Exception {
        writeTable(dir, validIdCards(ROWS), mobiles(ROWS), dirtyIdCards(ROWS));

        SensitivitySampler.TableSample sample = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);

        assertNotNull(sample.values("no_such_column"));
        assertFalse(sample.values("no_such_column").sampled(), "没采到就是没采到");
        assertEquals(0, sample.values("no_such_column").nonNullRows());
        assertEquals(List.of(), sample.maskedSamples("no_such_column"));
    }

    // ------------------------------------------------------------------ 值层打分

    /**
     * ★ §12 验收断言之一：**500 条造数的身份证列命中率 100%、判为可自动应用**。
     *
     * <p>这条断言的分量在于它走的是**内置 seed 的那份配置**（正则 + 类型区间 +
     * 校验器名都从 {@code BuiltinSensitiveTypes} 读），不是测试里手搓的一份。
     * 因此它同时钉住四件事：seed 的正则能匹配真实形态、类型区间覆盖 18 位、
     * 校验器名对得上实现、以及「值层满分的类型恰好够到 0.85 阈值」这个等式。
     */
    @Test
    void fiveHundredRealIdCardsAreAutoApplied(@TempDir Path dir) throws Exception {
        writeTable(dir, validIdCards(ROWS), mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler.TableSample sample = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);

        List<Match> matches = SensitivityDetectors.detect(CATALOG, TABLE,
                new Column("id_card_no", "VARCHAR(18)", "身份证号"),
                List.of(candidate("CN_ID_CARD")), sample.values("id_card_no"));

        assertEquals(1, matches.size(), () -> "500 个真身份证应当恰好命中一个类型：" + matches);
        Match match = matches.get(0);
        assertEquals("CN_ID_CARD", match.typeCode());
        assertEquals(1.0, match.scores().get(DetectorKind.VALUE), 1e-9,
                "500 条全部命中正则且全部通过校验 → 值层满分");
        assertEquals(0.85, match.confidence(), 1e-9,
                "0.35（名字）+ 0.10（类型）+ 0.40（值）= 0.85，恰好等于默认阈值");
        assertEquals(FindingState.AUTO_APPLIED, match.state(),
                "采样档的上限恰好是 0.85，因此强格式类型在默认阈值下能自动落标");
    }

    /**
     * ★ §12 验收断言之二：**误报 0** —— 形状一样但校验位不对的列，一条结论都不产出。
     *
     * <p>对照组的设计是「只差校验位」：列名同样像身份证（{@code id_card_bak} 命中
     * {@code id_card} 这条 pattern）、类型同样是 {@code varchar(18)}、值同样是 18 位
     * 数字且能过 seed 的正则——唯一不同的是第 18 位与模 11 的结果不符。
     *
     * <p>此时 name + type = 0.45，值层被校验通过率乘成 0，于是总分 0.45 **低于采样档
     * 下界 0.60** → 整条命中被丢弃。这正是「校验器比正则重要」的直接后果：
     * 换成只比正则的实现，这一列会拿到 0.85 并被**自动**打上脱敏标签，
     * 也就是一列无意义的数据被永久遮住。
     */
    @Test
    void lookalikesThatFailTheChecksumAreNotTreatedAsIdCards(@TempDir Path dir) throws Exception {
        writeTable(dir, dirtyIdCards(ROWS), mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler.TableSample sample = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);

        // 前提检查：这些值确实「像」（过 seed 的正则），只是校验位不对。
        // 没有这一步，下面的「不产出」也可能是因为正则压根没匹配上——那就测不到校验器了
        assertTrue(dirtyIdCards(ROWS).stream().allMatch(value ->
                        value.matches("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$")),
                "造数的前提：500 个值都能过 seed 的正则，只有校验位是错的");

        List<Match> matches = SensitivityDetectors.detect(CATALOG, TABLE,
                new Column("id_card_no", "VARCHAR(18)", null),
                List.of(candidate("CN_ID_CARD")), sample.values("id_card_no"));

        assertTrue(matches.isEmpty(),
                () -> "校验位全错的列不该产出任何结论（0.45 < 0.60 下界），实际：" + matches);
    }

    /**
     * 500 条里脏 4 条（0.8%）→ **仍然自动落标**；脏 6 条（1.2%）→ 退到人审。
     *
     * <p>这两条断言合起来才说明「校验通过率 99%」这条门槛在哪：
     * {@code SensitivityScorer} 在通过率 &lt; 0.99 时才把值层整层乘上通过率，因此
     * <ul>
     *   <li>4 / 500 → 通过率 0.992 ≥ 0.99 → 不打折，值层仍是 0.40，总分 0.85，
     *       照旧自动落标；</li>
     *   <li>6 / 500 → 通过率 0.988 &lt; 0.99 → 值层 0.3952，总分 0.8452，差 0.0048
     *       够不到阈值，落到人审。</li>
     * </ul>
     * 一列里 1% 的脏值就足以让它交给人看一眼——这是刻意的口径：
     * 「99% 是身份证」与「100% 是身份证」在自动脱敏这件事上不是同一件事。
     * 同时它也是**不该过松**的守卫：把门槛改成 0.999 会让正常埋着少量脏数据的
     * 生产表全部落到人审，队列立刻爆掉。
     */
    @Test
    void dirtyValuesAroundOnePercentDecideBetweenAutoApplyAndReview(@TempDir Path dir) throws Exception {
        // 0.8% 脏：仍然自动落标
        List<String> slightlyDirty = spoil(validIdCards(ROWS), 4);
        writeTable(dir, slightlyDirty, mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler.TableSample tolerable = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);
        Match tolerant = SensitivityDetectors.detect(CATALOG, TABLE,
                        new Column("id_card_no", "VARCHAR(18)", null),
                        List.of(candidate("CN_ID_CARD")), tolerable.values("id_card_no"))
                .get(0);
        assertEquals(0.85, tolerant.confidence(), 1e-9,
                "通过率 99.2% ≥ 99% → 值层不被打折");
        assertEquals(FindingState.AUTO_APPLIED, tolerant.state());
    }

    /** 越过 1% 的那一侧：同样 500 行，脏 6 条 → 0.8452，进人审。 */
    @Test
    void dirtyValuesAboveOnePercentCostTheAutoApply(@TempDir Path dir) throws Exception {
        List<String> dirtier = spoil(validIdCards(ROWS), 6);
        writeTable(dir, dirtier, mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler.TableSample sample = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);

        Match match = SensitivityDetectors.detect(CATALOG, TABLE,
                        new Column("id_card_no", "VARCHAR(18)", null),
                        List.of(candidate("CN_ID_CARD")), sample.values("id_card_no"))
                .get(0);

        assertEquals(0.35 + 0.10 + 0.40 * (494.0 / 500), match.confidence(), 1e-9,
                "通过率 98.8% < 99% → 值层从 0.40 乘到 0.3952");
        assertTrue(match.confidence() < 0.85, "差一点点就是够不到阈值");
        assertEquals(FindingState.PENDING, match.state(),
                "够不到阈值 → 进人审（而不是被丢弃：0.8452 远高于 0.60 下界）");
    }

    /**
     * 只有值层像的列**连候选都提不出来**（0.40 &lt; 0.60）。
     *
     * <p>这不是遗漏，是刻意的：一列叫 {@code col1} 的字符串里全是身份证号，
     * 说明这张表的命名一团糟，而「值像」单独不足以支撑一条治理结论——
     * 它可能是日志文本、可能是某个导出文件里的拼接字段。这类列要被判出来，
     * 需要的是人工发起一次针对性的扫描（P2 的范围选择），而不是让识别引擎
     * 在没有任何结构性证据时就开始提候选。
     */
    @Test
    void valueEvidenceAloneIsNotEnoughToPropose(@TempDir Path dir) throws Exception {
        writeTable(dir, validIdCards(ROWS), mobiles(ROWS), dirtyIdCards(ROWS));
        SensitivitySampler.TableSample sample = sampler(dir)
                .sample(CATALOG, DATABASE, TABLE, List.of("id_card_no"), ROWS);

        List<Match> matches = SensitivityDetectors.detect(CATALOG, TABLE,
                new Column("col1", "VARCHAR(18)", null),
                List.of(candidate("CN_ID_CARD")), sample.values("id_card_no"));

        assertTrue(matches.isEmpty(),
                () -> "值层满分也只有 0.40，低于采样档下界 0.60，不产出结论：" + matches);
    }

    /**
     * ★ §12 验收断言之三：{@code PERSON_NAME} **哪怕 0.99 也只进人审**。
     *
     * <p>「0.99」在今天的实现里到不了（D4 字典、D5 统计两层属 P4，值层对姓名也没有
     * 可用判据），但这条断言的落点从来不是那个数，而是**闸门**：{@code autoApply}
     * 为 {@code false} 的类型，把阈值调到多低都换不来自动落标。因此这里同时断言
     * seed 的取值与打分器的行为——前者是数据，后者是逻辑，两边脱节的后果是
     * 「白名单说不行、seed 说行」这种只有上线后才发现的漂移。
     */
    @Test
    void personNameNeverAutoApplies() {
        BuiltinSensitiveTypes.Builtin personName = BuiltinSensitiveTypes.byTypeCode("PERSON_NAME");
        assertNotNull(personName);
        assertFalse(personName.autoApply(), "姓名属于「永不自动应用」的四类之一");
        assertEquals(0.85, personName.minConfidence(), 1e-9);

        assertEquals(FindingState.PENDING, SensitivityScorer.decide(0.99, 0.85, false),
                "哪怕置信度 0.99，auto_apply=false 的类型也只能进人审");
        assertEquals(FindingState.PENDING, SensitivityScorer.decide(0.99, 0.10, false),
                "把阈值调到 0.10 也一样——这条闸门不能靠调阈值绕过");

        // 四个「永不自动」的类型都在白名单之外（§5.3）：写在这里是为了让新增类型时
        // 「白名单说可以、seed 说不行」这种漂移在构建期就红
        for (String typeCode : List.of("PERSON_NAME", "ADDRESS", "SALARY", "MEDICAL_DIAGNOSIS")) {
            assertFalse(BuiltinSensitiveTypes.byTypeCode(typeCode).autoApply(), typeCode);
        }
        for (String typeCode : List.of("CN_ID_CARD", "CN_MOBILE", "BANK_CARD", "CN_USCC",
                "EMAIL", "IP", "CREDENTIAL")) {
            assertTrue(BuiltinSensitiveTypes.byTypeCode(typeCode).autoApply(),
                    typeCode + " 在自动应用白名单里");
        }
    }

    // ------------------------------------------------------------------ 装置

    /** 一个内置类型 → 探测器要的候选形状（阈值 / 白名单开关都取 seed 原值）。 */
    private static CandidateType candidate(String typeCode) {
        BuiltinSensitiveTypes.Builtin builtin = BuiltinSensitiveTypes.byTypeCode(typeCode);
        return new CandidateType(builtin.typeCode(), builtin.level().name(),
                builtin.minConfidence(), builtin.autoApply(),
                DetectorConfig.parse(builtin.detectorsJson()));
    }

    private static SensitivitySampler sampler(Path dir) {
        StorageFileIoFactory fileIo = Mockito.mock(StorageFileIoFactory.class);
        try {
            FileIO local = LocalFileIO.create();
            when(fileIo.open(any(), any())).thenReturn(local);
        } catch (Exception exception) {
            throw new IllegalStateException("mock setup failed", exception);
        }
        when(fileIo.optionsFor(any(), anyString(), anyString())).thenReturn(Map.of());

        // 三个仓储按「采样器走的那条定位路」打桩：SensitivitySampler 只拿得到
        // (catalog, 库名, 表名)，因此它必须经 resolve(prefix, database, table) 换成实体。
        // 这里不打桩 setPrefix 那条捷径，是为了让本测试真的走扫描时会走的那条路。
        CatalogEntity catalog = new CatalogEntity();
        catalog.setId("cat-1");
        catalog.setPrefix(CATALOG);
        catalog.setCatalogFormat("PAIMON");
        DatabaseEntity database = new DatabaseEntity();
        database.setId("db-1");
        database.setCatalogId("cat-1");
        database.setName(DATABASE);
        TableEntity table = new TableEntity();
        table.setId("tbl-1");
        table.setCatalogId("cat-1");
        table.setDatabaseId("db-1");
        table.setName(TABLE);
        table.setPath(dir.resolve(TABLE).toUri().toString());

        CatalogRepository catalogs = Mockito.mock(CatalogRepository.class);
        when(catalogs.findByPrefix(CATALOG)).thenReturn(Optional.of(catalog));
        DatabaseRepository databases = Mockito.mock(DatabaseRepository.class);
        when(databases.findByCatalogIdAndName("cat-1", DATABASE)).thenReturn(Optional.of(database));
        TableRepository tables = Mockito.mock(TableRepository.class);
        when(tables.findByCatalogIdAndDatabaseIdAndName("cat-1", "db-1", TABLE))
                .thenReturn(Optional.of(table));

        PaimonPreviewTableFactory factory = new PaimonPreviewTableFactory(fileIo);
        TablePreviewService previews = new TablePreviewService(
                tables,
                databases,
                catalogs,
                List.of(factory),
                new CatalogEventPublisher(List.of(), null),
                new PreviewCursorCodec(new RestServerProperties()));
        return new SensitivitySampler(previews);
    }

    // ------------------------------------------------------------------ 造数

    /**
     * 500 条真身份证：前 6 位地址 + 出生日期 + 3 位顺序码 + 模 11 校验位。
     *
     * <p>校验位在这里算（而不是从固定表里抄），因为要造 500 个互不相同的值；
     * 但「模 11 的权重表对不对」不由这里证明——那由
     * {@code SensitivityValidatorsTests#idCardChecksMod11CheckDigit} 用硬编码的期望值钉住。
     * 两者的分工是：那边证明算法对，这边证明算法在 500 条真实规模上仍然对。
     */
    private static List<String> validIdCards(int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            values.add(idCard("11010119900307" + String.format("%03d", index)));
        }
        return values;
    }

    /** 同样 500 条，但校验位一律改错（其余部分与真号码完全同形）。 */
    private static List<String> dirtyIdCards(int count) {
        return spoil(validIdCards(count), count);
    }

    /**
     * 把前 {@code howMany} 条的校验位改错（其余保持合法）。
     *
     * <p>只动最后一位：正则仍然匹配、长度仍然对、日期仍然合法——唯一坏掉的是
     * 模 11 的结果。这样「分数掉了」只能归因于校验器，而不是「正则没命中」。
     */
    private static List<String> spoil(List<String> values, int howMany) {
        List<String> spoiled = new ArrayList<>(values);
        for (int index = 0; index < howMany; index++) {
            String valid = spoiled.get(index);
            char last = valid.charAt(17);
            spoiled.set(index, valid.substring(0, 17) + (last == '9' ? '8' : '9'));
        }
        return spoiled;
    }

    /** 500 个手机号（199 号段起，避开未分配段的排除表）。 */
    private static List<String> mobiles(int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            values.add("199" + String.format("%08d", index));
        }
        return values;
    }

    private static String idCard(String base17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String codes = "10X98765432";
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            sum += (base17.charAt(index) - '0') * weights[index];
        }
        return base17 + codes.charAt(sum % 11);
    }

    /**
     * 建表 + 写 500 行。
     *
     * <p>表路径必须先落 {@code schema-0}：{@link FileStoreTableFactory#create(FileIO,
     * org.apache.paimon.fs.Path)}（服务端工厂走的两参重载）按目录里的 schema 文件开表。
     * 与 {@code PolicyPreviewTests} 同一装置，多一列 {@code note}（用来放「像但不对」的值）。
     */
    private static void writeTable(Path dir, List<String> idCards, List<String> mobileValues,
                                   List<String> notes) throws Exception {
        Schema schema = Schema.newBuilder()
                .column("id", DataTypes.INT())
                .column("id_card_no", DataTypes.STRING())
                .column("mobile", DataTypes.STRING())
                .column("note", DataTypes.STRING())
                .primaryKey("id")
                .option("bucket", "1")
                .option("file.format", "parquet")
                .build();
        FileIO fileIO = LocalFileIO.create();
        org.apache.paimon.fs.Path tablePath =
                new org.apache.paimon.fs.Path(dir.resolve(TABLE).toUri().toString());
        TableSchema tableSchema = new FileSystemSchemaManager(fileIO, tablePath).createTable(schema);
        FileStoreTable table = FileStoreTableFactory.create(fileIO, tablePath, tableSchema);

        BatchWriteBuilder builder = table.newBatchWriteBuilder();
        try (BatchTableWrite write = builder.newWrite()) {
            for (int index = 0; index < idCards.size(); index++) {
                write.write(GenericRow.of(index + 1,
                        BinaryString.fromString(idCards.get(index)),
                        BinaryString.fromString(mobileValues.get(index)),
                        BinaryString.fromString(notes.get(index))));
            }
            List<CommitMessage> messages = write.prepareCommit();
            try (BatchTableCommit commit = builder.newCommit()) {
                commit.commit(messages);
            }
        }
    }
}
