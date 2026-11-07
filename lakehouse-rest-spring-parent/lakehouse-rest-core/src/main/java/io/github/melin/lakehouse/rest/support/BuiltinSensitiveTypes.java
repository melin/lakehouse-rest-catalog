package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.DetectorConfigJson;
import io.github.melin.lakehouse.rest.scan.DetectorConfig;
import io.github.melin.lakehouse.rest.scan.DetectorConfig.LayerConfig;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.SensitiveCategory;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.SensitivityLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置敏感类型的定义（设计见 {@code docs/sensitive-data-design.md} §4.1 / §5.1 / §5.3）。
 *
 * <p><b>为什么是代码里的常量而不是 SQL 里的 insert。</b>这些定义要跟着版本一起演进
 * （新增一个类型、给某个类型的名称规则补一条同义词），而库里的行是**治理对象**——
 * 治理员可以改阈值、改权重、停用。两者是不同生命周期的东西：代码负责给「初始值」，
 * 库负责记「现在是什么」。写成 SQL 的话，升级时要么覆盖用户改动、要么改不动，
 * 两条路都不对。因此落在 {@code SensitiveTypeInitializer} 里做「存在就跳过、
 * 不存在才建」的 upsert（理由见那里的注释）。
 *
 * <p><b>纯静态、无 Spring 依赖</b>：与 {@link SensitivityDetectors} 同一取舍，
 * 这样 {@code SensitivityDetectionTests} 能直接断言「内置类型里没有 {@code CORE}」
 * 这类结构约束，不必起一个应用上下文。
 *
 * <p><b>P0 只有 D1（名称）与 D2（类型）两层真正生效</b>。{@code value} 层
 * （正则 + 校验器名）在这里已经写全，理由有两条：它是 §5.3 自动应用白名单的唯一依据，
 * 「这个类型凭什么被允许自动落标」应当能从配置里读出来；以及它让
 * {@code detectors_json} 的形状从第一天就稳定——P1 接上采样时不必改 seed，
 * 也不必给存量行做数据迁移。{@code dict} / {@code stat} 两层刻意留空：
 * 它们需要采样，且取值依赖具体业务字典，不是内置能定死的。
 */
public final class BuiltinSensitiveTypes {

    /** seed 用的主体名：与预置 catalog 同一口径，表示「系统随机初始化写入」。 */
    public static final String SYSTEM_PRINCIPAL = "system";

    private BuiltinSensitiveTypes() {
    }

    /**
     * 一个内置类型：seed 时落到 {@code lakehouse_sensitive_type} 的全部初始值。
     *
     * <p>字段与实体的对应关系写在 {@code SensitiveTypeInitializer} 里，这里保持
     * 「只是一份数据」——把映射写在数据类里，会让这个类同时依赖实体与 Spring。
     */
    public record Builtin(String typeCode, String typeName, SensitiveCategory category,
                          SensitivityLevel level, Map<DetectorKind, LayerConfig> detectors,
                          boolean autoApply, double minConfidence) {

        /** 序列化成 {@code detectors_json} 的文本。 */
        public String detectorsJson() {
            return DetectorConfigJson.toJson(DetectorConfig.of(detectors));
        }
    }

    /**
     * 内置类型清单（顺序即列表页的初始顺序）。
     *
     * <p><b>{@code autoApply} 不是手写的</b>，由 {@link SensitivityEnums#defaultAutoApply}
     * 按白名单推出（见 {@link #type}）：这样「白名单说可以、seed 说不行」与反向漂移
     * 都不可能发生——而反向漂移是危险的：一个本该人工确认的类型被自动落了标。
     */
    public static List<Builtin> all() {
        return List.of(
                // -------------------------------------------------- 强格式（允许自动应用）
                type("CN_ID_CARD", "中国大陆居民身份证号", SensitiveCategory.PII, SensitivityLevel.L4,
                        new Detectors()
                                .name("id_card", "idno", "id_no", "idcard", "identity", "身份证",
                                        "证件号", "公民身份号码")
                                .type(List.of("char", "varchar", "string"), List.of(List.of(15, 18)))
                                .value("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$",
                                        "CN_ID_CARD")),

                type("CN_MOBILE", "中国大陆手机号", SensitiveCategory.PII, SensitivityLevel.L3,
                        new Detectors()
                                .name("mobile", "phone", "tel", "cellphone", "手机号", "手机号码", "联系电话")
                                .type(List.of("char", "varchar", "string"), List.of(List.of(11, 11)))
                                .value("^1[3-9]\\d{9}$", "CN_MOBILE")),

                type("BANK_CARD", "银行卡号", SensitiveCategory.PCI, SensitivityLevel.L4,
                        new Detectors()
                                .name("bank_card", "bankcard", "card_no", "cardno", "银行卡", "卡号", "账号")
                                // 银行卡可以是指定长度的字符串，也可能是 bigint / decimal——
                                // 因此类型层放行数字型且**不设长度区间**（19 位的数字超 int，
                                // 建表时常落 decimal(19,0)，用长度判会把它们全漏掉）。
                                .type(List.of("char", "varchar", "string", "bigint", "decimal"), List.of())
                                .value("^\\d{13,19}$", "BANK_CARD")),

                type("CN_USCC", "统一社会信用代码", SensitiveCategory.PII, SensitivityLevel.L2,
                        new Detectors()
                                .name("uscc", "credit_code", "social_credit", "统一社会信用代码", "信用代码")
                                .type(List.of("char", "varchar", "string"), List.of(List.of(18, 18)))
                                .value("^[0-9A-HJ-NPQRTUWXY]{18}$", "CN_USCC")),

                // 邮箱刻意**不配 value 层**：它的「校验器」就是 @ 结构本身，
                // 写成正则再配一个空校验器只会让配置看起来比实际严谨。
                // 结构性证据由 D1（列名）承担，这已足够——邮箱误报代价低，
                // 且它不在「永不自动应用」那四类里。
                type("EMAIL", "电子邮箱", SensitiveCategory.PII, SensitivityLevel.L3,
                        new Detectors()
                                .name("email", "e_mail", "mail", "邮箱", "电子邮件")
                                .type(List.of("char", "varchar", "string"), List.of())),

                type("IP", "IP 地址", SensitiveCategory.PII, SensitivityLevel.L2,
                        new Detectors()
                                .name("ip", "ip_addr", "ipaddr", "client_ip", "remote_ip", "ip地址")
                                .type(List.of("char", "varchar", "string"), List.of(List.of(7, 45)))
                                .value("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9A-Fa-f:]{2,45}$", "IP")),

                // 凭据**只靠列名判**（§5.1 末行）：值层不判是刻意的——一旦去匹配
                // 「像不像一个令牌」，就会把一堆随机串判成凭据；
                // 而凭据列的列名几乎总是自解释的（password / secret / token）。
                type("CREDENTIAL", "口令 / 密钥 / 令牌", SensitiveCategory.SECRET, SensitivityLevel.L4,
                        new Detectors()
                                .name("password", "passwd", "pwd", "secret", "token", "api_key", "apikey",
                                        "access_key", "private_key", "credential", "口令", "密码", "密钥",
                                        "令牌")),

                // -------------------------------------------------- 语义弱格式（永不自动应用）
                type("PERSON_NAME", "姓名", SensitiveCategory.PII, SensitivityLevel.L3,
                        new Detectors()
                                .name("name", "user_name", "username", "real_name", "full_name", "cust_name",
                                        "contact", "姓名", "用户姓名", "客户名称", "联系人")),

                type("ADDRESS", "地址", SensitiveCategory.PII, SensitivityLevel.L3,
                        new Detectors()
                                .name("address", "addr", "street", "city", "province", "zip", "postcode",
                                        "地址", "住址", "收货地址", "邮编")),

                type("SALARY", "薪资 / 收入", SensitiveCategory.FINANCIAL, SensitivityLevel.L3,
                        new Detectors()
                                .name("salary", "wage", "income", "bonus", "pay", "薪资", "工资", "月薪",
                                        "年薪", "收入")),

                type("MEDICAL_DIAGNOSIS", "医疗诊断", SensitiveCategory.PHI, SensitivityLevel.L4,
                        new Detectors()
                                .name("diagnosis", "disease", "icd", "symptom", "diagnose", "诊断", "疾病",
                                        "病种", "症状")));
    }

    /** 按编码取一个内置类型；不存在返回 {@code null}（seed 用）。 */
    public static Builtin byTypeCode(String typeCode) {
        if (typeCode == null) {
            return null;
        }
        return all().stream()
                .filter(builtin -> builtin.typeCode().equals(typeCode))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ 构造辅助

    /**
     * 组装一个内置类型，并**由白名单推出 {@code autoApply}**。
     *
     * <p>规则见 {@link SensitivityEnums#defaultAutoApply(String)}：白名单内的允许自动
     * 应用，其余一律 {@code false}。因此 {@code PERSON_NAME} / {@code ADDRESS} /
     * {@code SALARY} / {@code MEDICAL_DIAGNOSIS} 四类的开关恒为 false——不是
     * 「先关着看看」，而是**永不**（§5.3：误报率天然高，一旦自动落标就是误脱敏）。
     *
     * <p>阈值统一 0.85：它是 §5.2 里那句「默认 0.85」的唯一落点。按 catalog 覆盖
     * 阈值是治理动作（P2），而**内置默认值只有一个**——四个类型各配一个数会让
     * 「为什么这个类型松」变成一句要翻四个常量才能答的问题。
     */
    private static Builtin type(String code, String name, SensitiveCategory category,
                                SensitivityLevel level, Detectors detectors) {
        return new Builtin(code, name, category, level, detectors.build(),
                SensitivityEnums.defaultAutoApply(code), 0.85d);
    }

    /**
     * 探测器配置的链式构造。
     *
     * <p><b>为什么用一个 builder 而不是让调用处直接 new {@link LayerConfig}（八个参数）。</b>
     * 那种写法里 {@code null} 与 {@code List.of()} 的区别全靠数位置：名称层要
     * {@code patterns} 非空、{@code types} 空；类型层反过来；值层只用最后两个。
     * 数错一位不会报错，只会让某一层永远不命中——而「某一层永远不命中」的表现是
     * 「识别结果偏少」，与「这张表本来就没敏感列」长得一样。
     *
     * <p>{@code weight} 一律 1.0（层内满值）：层系数的领域判断（名字 0.35、值 0.40）
     * 由 {@link SensitivityScorer} 固定，而内置类型之间没有「谁的名字规则更可信」
     * 的差别需要表达。治理员要调权重时改库里的那一行（那是治理动作，进审计），
     * 不由 seed 预置一个主观值。
     */
    private static final class Detectors {

        private final Map<DetectorKind, LayerConfig> layers = new LinkedHashMap<>();

        /** D1 名称层：任一 pattern 命中列名或列注释即算命中。 */
        Detectors name(String... patterns) {
            layers.put(DetectorKind.NAME, new LayerConfig(
                    List.of(patterns), List.of(), List.of(), null, null, List.of(), null, 1.0));
            return this;
        }

        /** D2 类型层：类型名在里面**且**长度落在某个闭区间内（空 {@code lengths} = 只看类型名）。 */
        Detectors type(List<String> types, List<List<Integer>> lengths) {
            layers.put(DetectorKind.TYPE, new LayerConfig(
                    List.of(), types, lengths, null, null, List.of(), null, 1.0));
            return this;
        }

        /** D3 值层：采样值正则 + 校验器名（校验器名先在配置里占位，P1 接上实现）。 */
        Detectors value(String regex, String validator) {
            layers.put(DetectorKind.VALUE, new LayerConfig(
                    List.of(), List.of(), List.of(), regex, validator, List.of(), null, 1.0));
            return this;
        }

        Map<DetectorKind, LayerConfig> build() {
            return layers;
        }
    }
}
