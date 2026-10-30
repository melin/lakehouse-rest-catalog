package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 用户填写的静态凭据在 HTTP 层的验收。
 *
 * <p>这一层要证明的不是「字段能解析」——那是 {@code StorageConfigDtosTests} 的事——
 * 而是三条只有走完整链路才看得见的约定：
 *
 * <ol>
 *   <li><b>两个字段都不回显真值。</b>创建、读取、列表三处响应里都不出现明文密钥，
 *       也不出现真实的 {@code accessKeyId}（那个位置固定是 {@link StorageConfigs#MASKED}）；
 *       库里存的也不是明文（能解开的密文才算数）；
 *   <li><b>密钥省略即保持，打码值回传也即保持。</b>{@code PUT} 对其余字段是整体替换，
 *       唯独只写不读的凭据不能被替换掉——否则「改个 endpoint」会顺手清掉密钥，
 *       而「把回显的 {@code ******} 当真值存回去」会把密钥对弄错位（G14）；
 *   <li><b>它真的被下发。</b>读表凭据接口回来的必须是 catalog 里那把钥匙。
 *       服务端没有「默认凭据」可回落：{@code lakehouse.rest.storage} 里不再有任何
 *       对象存储密钥（见 {@code RestServerProperties.Storage}），所以这里下发的一定是
 *       catalog 那一份，或者一份都没有。
 * </ol>
 *
 * <p>加密密钥通过 {@code properties} 注入而非改 application.yml：这个上下文与其它测试
 * 互不影响，也不会给生产配置留下测试密钥。未配置密钥时的行为由
 * {@code StaticCredentialDisabledApiTests} 单独覆盖。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        // 控制台端点默认要求先登录（console.required=true），元数据也不例外；
        // 登记一个静态令牌，省得在一条「顺便验一下元数据」的用例里走一遍登录
        "lakehouse.rest.auth.tokens[0]=static-cred-token"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StaticCredentialApiTests {

    private static final String M = "/api/management/v1";

    private static final String TABLE = "orders";

    private static final String CONSOLE_TOKEN = "static-cred-token";

    private static final String ACCESS_KEY_ID = "AKIA-USER-SUPPLIED";

    private static final String SECRET = "USER-SUPPLIED-SECRET-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private CredentialCipher cipher;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            call(204, delete(M + "/catalogs/" + name));
        }
        created.clear();
    }

    // ------------------------------------------------------------------ 回显与落库

    @Test
    void createsCatalogWithStaticCredentialsWithoutEchoingTheSecret() throws Exception {
        String prefix = "static-s3";
        JsonNode created = createCatalog(prefix, staticS3Config());

        JsonNode storage = created.get("storageConfigInfo");
        assertEquals("S3", storage.get("storageType").asString());
        assertEquals(StorageConfigs.MASKED, storage.get("accessKeyId").asString(),
                "accessKeyId 也要打码：它一旦能被读出来，就成了「这把钥匙是谁的」的旁路标识");
        assertFalse(created.toString().contains(ACCESS_KEY_ID), "响应体任何位置都不应出现真实 AK ID");
        assertFalse(storage.has("secretAccessKey"), "响应里不应出现 secretAccessKey: " + storage);
        assertFalse(created.toString().contains(SECRET), "响应体任何位置都不应出现明文密钥");

        // 重新读取与列表：同样不回显
        JsonNode fetched = json(200, "GET", M + "/catalogs/" + prefix, null);
        assertFalse(fetched.toString().contains(SECRET), "单查也不应回显明文密钥");
        assertFalse(fetched.toString().contains(ACCESS_KEY_ID), "单查也不应回显 AK ID");
        assertFalse(fetched.get("storageConfigInfo").has("secretAccessKey"));
        assertFalse(json(200, "GET", M + "/catalogs", null).toString().contains(SECRET),
                "列表更不应该回显明文密钥");
        assertFalse(json(200, "GET", M + "/catalogs", null).toString().contains(ACCESS_KEY_ID),
                "列表同样打码——两处走的是同一个出口，不该一边星号一边真值");

        // 库里存的是密文，且能解开
        StorageConfigInfo stored = storedConfig(prefix);
        AwsStorageConfigInfo s3 = (AwsStorageConfigInfo) stored;
        assertTrue(CredentialCipher.sealed(s3.secretAccessKey()),
                "落库必须是密文，实际: " + s3.secretAccessKey());
        assertFalse(s3.secretAccessKey().contains(SECRET), "密文里不应出现明文");
        assertEquals(SECRET, cipher.reveal(s3.secretAccessKey()));
    }

    /** 端到端：读表凭据接口下发的必须是 catalog 里那把钥匙。 */
    @Test
    void vendsCatalogStaticCredentials() throws Exception {
        String prefix = "static-s3-vend";
        createCatalog(prefix, staticS3Config());
        seedTable(prefix);

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");

        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
        // 定位配置照旧下发，静态凭据不改变这一点
        assertEquals("us-east-1", token.get("s3.region").asString());
        assertEquals("http://minio.internal:9000", token.get("s3.endpoint").asString());
        assertEquals("true", token.get("s3.path-style-access").asString());
    }

    // ------------------------------------------------------------------ 更新语义

    /**
     * 打码值的正常回程：{@code ******} 原样带回 + 省略密钥 = 两样都不动。
     *
     * <p>用「再下发一次凭据」验证，而不是只读回响应：响应里本来就没有明文，
     * 只断言响应等于没断言。
     */
    @Test
    void maskedAccessKeyIdWithOmittedSecretKeepsBoth() throws Exception {
        String prefix = "static-s3-keep";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        // 只改端点，把详情里那个打码值原样送回来——控制台与 SDK 读-改-写的默认形态
        JsonNode updated = json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"eu-west-1",
                                      "endpoint":"http://minio2.internal:9000",
                                      "pathStyleAccess":true,
                                      "accessKeyId":"%s"}}
                """.formatted(version, StorageConfigs.MASKED));

        assertEquals("eu-west-1", updated.get("storageConfigInfo").get("region").asString());
        assertFalse(updated.toString().contains(SECRET), "更新响应也不应回显明文密钥");
        assertEquals(StorageConfigs.MASKED, updated.get("storageConfigInfo").get("accessKeyId").asString(),
                "响应照旧打码，不该因为这次写就把真值放出来");

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString(),
                "省略 secretAccessKey 应保持库中原值");
        assertEquals("http://minio2.internal:9000", token.get("s3.endpoint").asString());
    }

    /**
     * 设计稿 §8 P1-8 的验收：打码之后「只改个名字」的保存不改变库里的 AK ID。
     *
     * <p>这是 G14 那个坑的正面形态。没有哨兵分支时，一次「只改属性」的保存会把
     * {@code ******} 当成新 AK ID 落库，而密钥沿用旧值——**密钥对错位**，
     * 失败现场在存储侧（{@code InvalidAccessKeyId}），与「我刚改了什么」完全联系不上。
     *
     * <p>与上一条的区别：这条模拟控制台的完整提交（属性 + 整份 storageConfigInfo），
     * 上一条模拟「客户端只改一个字段」。两者都必须让库里的 AK ID 保持原样。
     */
    @Test
    void maskedAccessKeyIdSurvivesARenameOnlyUpdate() throws Exception {
        String prefix = "static-s3-rename";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        JsonNode updated = json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "properties":{"lakehouse.display-name":"订单仓库"},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "pathStyleAccess":true,
                                      "accessKeyId":"%s"}}
                """.formatted(version, StorageConfigs.MASKED));

        assertEquals("订单仓库", updated.get("properties").get("lakehouse.display-name").asString());

        // 库里那把钥匙一个字符都没变，密钥也还是旧的（能解开、且是同一份明文）
        StorageConfigs.StorageSecrets stored =
                StorageConfigs.secrets(storedConfig(prefix), cipher);
        assertEquals(ACCESS_KEY_ID, stored.id(), "「只改名字」不该动 AK ID");
        assertEquals(SECRET, stored.secret());

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
    }

    /**
     * 只换密钥、AK ID 不动：{@code ******} + 新密钥，是唯一能表达这件事的组合。
     *
     * <p>省略 AK ID 会变成「有密钥没 ID」而被拒（下一条），所以哨兵不是可选的便利，
     * 而是这条轮换路径成立的前提。
     */
    @Test
    void maskedAccessKeyIdWithANewSecretRotatesOnlyTheSecret() throws Exception {
        String prefix = "static-s3-rotate-secret";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "pathStyleAccess":true,
                                      "accessKeyId":"%s",
                                      "secretAccessKey":"ROTATED-SECRET"}}
                """.formatted(version, StorageConfigs.MASKED));

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString(), "AK ID 应沿用库里的");
        assertEquals("ROTATED-SECRET", token.get("s3.secret-access-key").asString());
    }

    /**
     * 库里没有可指向的钥匙时，{@code ******} 无意义，必须拒绝。
     *
     * <p>这个值的意思是「沿用你库里那份」。新建 catalog 时没有那份，静默存下去就等于
     * 存了一把叫六个星号的钥匙；而它永远签不了名，问题要到第一次读写数据才暴露。
     */
    @Test
    void maskedAccessKeyIdOnANewCatalogIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-masked-new","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "accessKeyId":"%s","secretAccessKey":"some-secret"}}}
                """.formatted(StorageConfigs.MASKED));

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("accessKeyId"),
                "错误信息应点出是哪个字段: " + error);
    }

    /** 六个星号出现在密钥位上同样拒绝：它不是密钥，存进去只会在签名时失败。 */
    @Test
    void maskedValueAsSecretIsRejected() throws Exception {
        String prefix = "static-s3-bad-secret";
        JsonNode created = createCatalog(prefix, staticS3Config());
        int version = created.get("entityVersion").asInt();

        JsonNode error = json(400, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "endpoint":"http://minio.internal:9000",
                                      "accessKeyId":"%s","secretAccessKey":"%s"}}
                """.formatted(version, StorageConfigs.MASKED, StorageConfigs.MASKED));

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("secretAccessKey"), error.toString());
    }

    /** 换端点、换区域都不该动密钥；换 accessKeyId 则必须连密钥一起给（见下一条）。 */
    @Test
    void updatingWithoutAnyCredentialFieldKeepsWhatIsStored() throws Exception {
        String prefix = "static-s3-untouched";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "pathStyleAccess":true}}
                """.formatted(version));

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
    }

    /**
     * 一对空串是显式清除：之后不再下发任何密钥，引擎走自己的凭据链。
     *
     * <p><b>不是「退回服务端配置的那一份」。</b>那一路已经删了——全局配置里没有
     * 对象存储凭据（见 {@code RestServerProperties.Storage}）。所以清除的语义是
     * 「这个 catalog 不再由服务端代持钥匙」，对应下发来源 {@code environment}。
     */
    @Test
    void emptyCredentialPairClearsStaticCredentials() throws Exception {
        String prefix = "static-s3-clear";
        JsonNode created = createCatalog(prefix, staticS3Config());
        seedTable(prefix);
        int version = created.get("entityVersion").asInt();

        JsonNode updated = json(200, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "accessKeyId":"",
                                      "secretAccessKey":""}}
                """.formatted(version));

        assertFalse(updated.get("storageConfigInfo").has("accessKeyId"), "清除后不应再给出 accessKeyId");
        assertFalse(StorageConfigs.hasCredentials(storedConfig(prefix)), "库里不应再留有凭据");

        JsonNode token = json(200, "GET", tokenPath(prefix), null).get("token");
        assertFalse(token.has("s3.access-key-id"),
                "没有任何来源可发：不该凭空冒出密钥 " + token);
        assertFalse(token.has("s3.secret-access-key"), token.toString());
        assertEquals("us-east-1", token.get("s3.region").asString(), "定位键仍然照发");
    }

    // ------------------------------------------------------------------ 校验

    /** 只给密钥不给 ID：没有确定语义，拒绝。 */
    @Test
    void secretWithoutAccessKeyIdIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-bad-1","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "secretAccessKey":"only-secret"}}}
                """);

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("accessKeyId"),
                "错误信息应指出该字段: " + error);
    }

    /**
     * 换了 accessKeyId 却没给新密钥：拒绝。
     *
     * <p>这类请求多半来自「想轮换密钥但不知道服务端不回显」，静默接受会得到一个
     * 「新 ID 配旧密钥」的组合，认证失败现场在引擎侧，排查成本很高。
     */
    @Test
    void changingAccessKeyIdWithoutSecretIsRejected() throws Exception {
        String prefix = "static-s3-rotate";
        JsonNode created = createCatalog(prefix, staticS3Config());
        int version = created.get("entityVersion").asInt();

        JsonNode error = json(400, "PUT", M + "/catalogs/" + prefix, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "endpoint":"http://minio.internal:9000",
                                      "accessKeyId":"AKIA-ROTATED"}}
                """.formatted(version));

        assertEquals(400, error.get("code").asInt());
        assertTrue(error.get("message").asString().contains("secretAccessKey"),
                "错误信息应指出缺的是密钥: " + error);
    }

    @Test
    void emptyAccessKeyIdWithSecretIsRejected() throws Exception {
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"static-bad-2","properties":{},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://bucket/warehouse/"],
                                      "accessKeyId":"","secretAccessKey":"half-a-pair"}}}
                """);
    }

    /**
     * {@code storageName} 与凭据**可以同时出现**——它现在只是一个标签。
     *
     * <p>这两者原先被判为互斥，因为 {@code storageName} 当时指向服务端配置里的
     * 一组具名密钥，「用哪一组」只能有一个答案。配置面删掉之后它不再参与凭据解析，
     * 互斥也就失去了依据；保留那条规则会让「我想给这个 catalog 起个名字」
     * 变成一个 400。
     */
    @Test
    void storageNameIsJustALabelNextToCredentials() throws Exception {
        JsonNode created = createCatalog("labeled-s3", """
                {"storageType":"S3",
                 "allowedLocations":["s3://bucket/warehouse/"],
                 "storageName":"named-a",
                 "accessKeyId":"AKIA-X","secretAccessKey":"SECRET-X"}
                """);

        assertEquals("named-a",
                created.get("storageConfigInfo").get("storageName").asString());
        assertEquals(StorageConfigs.MASKED,
                created.get("storageConfigInfo").get("accessKeyId").asString());
    }

    /** OBS / OSS / COS 复用同一套字段与语义，别只给 S3 接上。 */
    @Test
    void obsOssAndCosAlsoAcceptStaticCredentials() throws Exception {
        createCatalog("static-obs", """
                {"storageType":"OBS",
                 "allowedLocations":["obs://analytics-bucket/warehouse/"],
                 "endpoint":"obs.cn-north-4.myhuaweicloud.com",
                 "accessKeyId":"OBS-AK","secretAccessKey":"OBS-SK"}
                """);
        createCatalog("static-oss", """
                {"storageType":"OSS",
                 "allowedLocations":["oss://analytics-bucket/warehouse/"],
                 "endpoint":"oss-cn-hangzhou.aliyuncs.com",
                 "accessKeyId":"OSS-AK","secretAccessKey":"OSS-SK"}
                """);
        createCatalog("static-cos", """
                {"storageType":"COS",
                 "allowedLocations":["cosn://analytics-1250000000/warehouse/"],
                 "bucketRegion":"ap-guangzhou",
                 "appId":"1250000000",
                 "accessKeyId":"COS-AK","secretAccessKey":"COS-SK"}
                """);

        JsonNode obs = json(200, "GET", M + "/catalogs/static-obs", null).get("storageConfigInfo");
        assertEquals(StorageConfigs.MASKED, obs.get("accessKeyId").asString(), "打码对所有带凭据的类型生效");
        assertFalse(obs.has("secretAccessKey"));
        HuaweiObsStorageConfigInfo storedObs = (HuaweiObsStorageConfigInfo) storedConfig("static-obs");
        assertEquals("OBS-AK", storedObs.accessKeyId(), "库里仍是真值，打码只发生在出口");
        assertTrue(CredentialCipher.sealed(storedObs.secretAccessKey()));

        JsonNode oss = json(200, "GET", M + "/catalogs/static-oss", null).get("storageConfigInfo");
        assertEquals(StorageConfigs.MASKED, oss.get("accessKeyId").asString());
        assertFalse(oss.has("secretAccessKey"));

        JsonNode cos = json(200, "GET", M + "/catalogs/static-cos", null).get("storageConfigInfo");
        assertEquals(StorageConfigs.MASKED, cos.get("accessKeyId").asString());
        assertFalse(cos.has("secretAccessKey"), "COS 的密钥同样只写不读: " + cos);
        TencentCosStorageConfigInfo storedCos = (TencentCosStorageConfigInfo) storedConfig("static-cos");
        assertEquals("COS-AK", storedCos.accessKeyId());
        assertTrue(CredentialCipher.sealed(storedCos.secretAccessKey()),
                "COS 的密钥也必须以密文落库，而不是明文直存");
    }

    /** 控制台元数据要如实报告「本部署能保存静态凭据」，否则表单一律禁用输入。 */
    @Test
    void metaReportsStaticCredentialsAsEnabled() throws Exception {
        JsonNode enums = metaEnums();

        assertTrue(enums.get("staticCredentialsEnabled").asBoolean(),
                "配了加密密钥就应报告为可用: " + enums);
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode metaEnums() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/console/v1/meta")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CONSOLE_TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body).get("enums");
    }

    /** 一段带静态凭据的 S3 配置：S3 兼容对象存储的典型形态（自定义端点 + 路径风格 + 无 STS）。 */
    private static String staticS3Config() {
        return """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"us-east-1",
                 "endpoint":"http://minio.internal:9000",
                 "pathStyleAccess":true,
                 "stsUnavailable":true,
                 "accessKeyId":"%s",
                 "secretAccessKey":"%s"}
                """.formatted(ACCESS_KEY_ID, SECRET);
    }

    private JsonNode createCatalog(String name, String storageConfigInfo) throws Exception {
        created.add(name);
        return json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":%s}}
                """.formatted(name, storageConfigInfo));
    }

    /** 直接读库，验「落库的形态」而不是「接口回显了什么」。 */
    private StorageConfigInfo storedConfig(String prefix) {
        CatalogEntity entity = catalogRepository.findByPrefix(prefix).orElseThrow();
        StorageConfigInfo config = entity.getStorageConfig();
        assertNotNull(config, "存储配置应随 catalog 一起落库");
        return config;
    }

    private void seedTable(String prefix) throws Exception {
        json(200, "POST", "/v1/" + prefix + "/databases", "{\"name\":\"cred_db\",\"options\":{}}");
        json(200, "POST", "/v1/" + prefix + "/databases/cred_db/tables", """
                {"identifier":{"database":"cred_db","object":"%s"},
                 "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"}],
                           "primaryKeys":["id"],"options":{"bucket":"1"}}}
                """.formatted(TABLE));
    }

    private String tokenPath(String prefix) {
        return "/v1/" + prefix + "/databases/cred_db/tables/" + TABLE + "/token";
    }

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }
}
