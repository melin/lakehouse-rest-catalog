package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * 设计稿 §3.4.3 / §8 P1-7 的验收：**「能跑查询」与「能拿走长期密钥」不是一个权限点**。
 *
 * <p>这一层要证明的是三个方向，缺一个这套拆分就不成立：
 *
 * <ol>
 *   <li>只有 {@code USE_CREDENTIAL}：能调 {@code /token}，但票里要含目录里存的那对
 *       长期密钥时被拒（403 且消息点名缺的是 {@code USE_SECRET}）；</li>
 *   <li>两个都有：照常下发那对密钥——这是「加了一道门」而不是「把功能关掉」的证据；</li>
 *   <li>只有 {@code USE_SECRET}：**取不到票**。两个词是并列的，不是包含关系；
 *       若这条反过来，省掉一半授权就会因为「反正是更宽的那个」而蒙对。</li>
 * </ol>
 *
 * <p>为什么要走 HTTP 而不是只测判定：拆分有两处落点，一处是
 * {@code CatalogAccessRules} 的路径映射（端点级，{@code USE_CREDENTIAL}），
 * 一处是凭据服务拿到凭据之后的判定（载荷级，{@code USE_SECRET}）。前者用
 * {@link CatalogEndpointAuthorizationTests} 的映射断言就能覆盖，后者只有在
 * **真的拿到一份含长期密钥的凭据**时才走到——那需要完整的 catalog + 表 + 存储配置，
 * 也就是这个类在做的事。
 *
 * <p>最后一条用例是纯单元断言：判据落在 {@link VendedStorageCredential#carriesLongTermKeyPair()}
 * 上，与「哪种存储」无关，只与「这次真的发了密钥、而且它有时限」有关。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        // 静态凭据要能解密才谈得上下发；密钥只注入这个上下文，不进 application.yml
        "lakehouse.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "spring.datasource.url=jdbc:h2:mem:paimon-cred-split;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CredentialAccessSplitTests {

    private static final String M = "/api/management/v1";
    private static final String ROOT = "root";
    private static final String READER = "cred-reader";
    private static final String DATABASE = "cred_db";
    private static final String TABLE = "orders";
    private static final String ACCESS_KEY_ID = "AKIA-SPLIT-TEST";
    private static final String SECRET = "SPLIT-TEST-SECRET-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private TableRepository tableRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            mockMvc.perform(delete(M + "/catalogs/" + name)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + ROOT));
        }
        created.clear();
    }

    // ------------------------------------------------------------------ 三个方向

    /**
     * 只有「能取票」：取票通过，含长期密钥则被拒，且错误信息点名 {@code USE_SECRET}。
     *
     * <p>这条是整条拆分的核心断言。旧行为下 {@code TABLE:SELECT} 一个授权就能把这把钥匙
     * 拷走——而 SELECT 是「读这张表」的日常授权，等于每个分析师都握着一份可以永久使用、
     * 不受任何 policy 约束的云上凭据。现在它需要一条显式的、名字里带 SECRET 的授权。
     */
    @Test
    void onlyTheCredentialGrantIsNotEnoughForStoredKeys() throws Exception {
        String prefix = seedCatalog();
        createPrincipal(READER);
        grant(prefix, "TABLE", TABLE, "USE_CREDENTIAL");

        MvcResult result = perform(READER, get(tokenPath(prefix)));
        String body = result.getResponse().getContentAsString();

        assertEquals(403, result.getResponse().getStatus(), () -> "body=" + body);
        assertTrue(body.contains("USE_SECRET"),
                "错误信息必须点名缺的那一项，否则调用方只能靠猜: " + body);
        assertFalse(body.contains(SECRET), "错误信息里不能出现密钥本身: " + body);
        assertFalse(body.contains(ACCESS_KEY_ID), "错误信息里也不该出现 AK ID: " + body);
    }

    /** 两个都授：那对密钥照常下发。拆分不能把功能关掉。 */
    @Test
    void bothGrantsVendTheStoredKeyPair() throws Exception {
        String prefix = seedCatalog();
        createPrincipal(READER);
        grant(prefix, "TABLE", TABLE, "USE_CREDENTIAL");
        grant(prefix, "TABLE", TABLE, "USE_SECRET");

        JsonNode token = json(READER, 200, get(tokenPath(prefix))).get("token");

        assertEquals(ACCESS_KEY_ID, token.get("s3.access-key-id").asString());
        assertEquals(SECRET, token.get("s3.secret-access-key").asString());
    }

    /**
     * 只有「能拿走密钥」：取不到票。
     *
     * <p>官方对这两个点的描述本就是互斥的（{@code USE_SECRET} 不能调
     * {@code getCredentials()}），本工程照抄这个方向。反过来做成「USE_SECRET 含
     * USE_CREDENTIAL」会让「授权写少一条」变成一个看起来正常、实则可用的状态。
     */
    @Test
    void theSecretGrantAloneCannotFetchACredential() throws Exception {
        String prefix = seedCatalog();
        createPrincipal(READER);
        grant(prefix, "TABLE", TABLE, "USE_SECRET");

        MvcResult result = perform(READER, get(tokenPath(prefix)));
        String body = result.getResponse().getContentAsString();

        assertEquals(403, result.getResponse().getStatus(), () -> "body=" + body);
        assertTrue(body.contains("USE_CREDENTIAL"),
                "拒绝理由应是「缺取票权」，而不是缺 USE_SECRET: " + body);
        assertFalse(body.contains(SECRET), body);
    }

    /** 一个凭据点都没授：同样是 403，缺的是端点那一道 {@code USE_CREDENTIAL}。 */
    @Test
    void noCredentialGrantAtAllIsRejectedAtTheEndpoint() throws Exception {
        String prefix = seedCatalog();
        createPrincipal(READER);
        // 只给「能读这张表」——这是旧实现里足以拿到密钥的那条授权
        grant(prefix, "TABLE", TABLE, "SELECT");

        MvcResult result = perform(READER, get(tokenPath(prefix)));
        String body = result.getResponse().getContentAsString();

        assertEquals(403, result.getResponse().getStatus(), () -> "body=" + body);
        assertTrue(body.contains("USE_CREDENTIAL"), "SELECT 不蕴含取票权: " + body);
        assertFalse(body.contains(SECRET), body);
    }

    // ------------------------------------------------------------------ 判据本身

    /**
     * 判据只看「这次发了什么」，不看「哪种存储」。
     *
     * <p>三种形态的差别正好覆盖了判据的两个条件：{@code hasSecrets()} 决定「有没有真的
     * 发密钥」（Azure 只发定位元数据、{@code sts-unavailable} 什么都不发），
     * {@code shortLived()} 决定「有没有时限」。缺任一条件都不该要 {@code USE_SECRET}——
     * 否则「只发了个 endpoint」也要用户先授一把钥匙权，授权就变成了噪声。
     */
    @Test
    void theRuleIsAboutThePayloadNotTheStorageType() {
        assertTrue(credential(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, 0L)
                        .carriesLongTermKeyPair(),
                "catalog 自己存的那对密钥：最典型的「长期密钥」");

        assertFalse(credential(VendedStorageCredential.SOURCE_STS_SESSION, 1_000L).carriesLongTermKeyPair(),
                "STS 现签的票有 TTL、policy 收窄到表：发给能读表的人是设计意图");
        assertFalse(credential(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, 0L)
                        .carriesLongTermKeyPair(),
                "只发定位元数据时没有任何密钥，不该要求 USE_SECRET");
        assertFalse(credential(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, 0L)
                        .carriesLongTermKeyPair(),
                "sts-unavailable 什么都不发，同样不该要求");
        assertFalse(credential(VendedStorageCredential.SOURCE_ENVIRONMENT, 0L).carriesLongTermKeyPair(),
                "交给引擎自己的环境凭据链时，服务端没发任何东西");
    }

    // ------------------------------------------------------------------ 辅助

    private static VendedStorageCredential credential(String source, long expireTimeInMs) {
        Map<String, String> token = new LinkedHashMap<>();
        token.put("s3.access-key-id", ACCESS_KEY_ID);
        return new VendedStorageCredential(token, 3600L, StorageType.S3, source,
                VendedStorageCredential.PROVIDER_RELAY, expireTimeInMs);
    }

    /**
     * 建一个带静态凭据的 S3 catalog，并放一张表；返回 prefix。
     *
     * <p><b>库与表直接落库，不走 API。</b>这一层要验的只是判权，而走 API 建表会把
     * {@code s3://minio.internal/...} 当成真地址去写元数据：每条用例因此要多等
     * 两分半的 DNS/连接超时（实测三次尝试各 ~50 秒），五个用例就是十几分钟的纯等待。
     * 直接写实体拿到的是同样可用的 {@code (catalog, database, table)} 三件套，
     * 而「建表能不能成功」是 {@code StorageCredentialApiTests} 那一类的事。
     */
    private String seedCatalog() throws Exception {
        String prefix = "cred-split";
        created.add(prefix);
        json(ROOT, 201, post(M + "/catalogs").contentType(MediaType.APPLICATION_JSON).content("""
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":{"storageType":"S3",
                                      "allowedLocations":["s3://analytics-bucket/warehouse/"],
                                      "region":"us-east-1",
                                      "endpoint":"http://minio.internal:9000",
                                      "pathStyleAccess":true,
                                      "stsUnavailable":true,
                                      "accessKeyId":"%s",
                                      "secretAccessKey":"%s"}}}
                """.formatted(prefix, ACCESS_KEY_ID, SECRET)));

        CatalogEntity catalog = catalogRepository.findByPrefix(prefix).orElseThrow();
        DatabaseEntity database = new DatabaseEntity();
        database.setId(UUID.randomUUID().toString());
        database.setCatalogId(catalog.getId());
        database.setName(DATABASE);
        database.setLocation("s3://analytics-bucket/warehouse/" + DATABASE + ".db");
        databaseRepository.save(database);

        TableEntity table = new TableEntity();
        table.setId(UUID.randomUUID().toString());
        table.setCatalogId(catalog.getId());
        table.setDatabaseId(database.getId());
        table.setName(TABLE);
        table.setPath("s3://analytics-bucket/warehouse/" + DATABASE + ".db/" + TABLE);
        table.setSchemaDoc("{\"fields\":[{\"id\":0,\"name\":\"id\",\"type\":\"BIGINT NOT NULL\"}]}");
        tableRepository.save(table);
        return prefix;
    }

    /**
     * 登记主体。同一个类里的多个用例共用这个 H2 库，第二个用例再去建就会拿到 409，
     * 因此这里把「已存在」当作成功——幂等是这些用例之间唯一需要的约定。
     */
    private void createPrincipal(String name) throws Exception {
        MvcResult result = perform(ROOT, post(M + "/principals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":{\"name\":\"" + name + "\"}}"));
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertTrue(status == 201 || status == 409,
                () -> "unexpected status creating principal: " + status + ", body=" + body);
    }

    private void grant(String prefix, String resourceType, String object, String access) throws Exception {
        json(ROOT, 201, post("/v1/" + prefix + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resource\":{\"type\":\"" + resourceType + "\",\"database\":\""
                        + DATABASE + "\",\"table\":\"" + object + "\"},\"access\":\"" + access
                        + "\",\"principal\":\"" + READER + "\"}"));
    }

    private String tokenPath(String prefix) {
        return "/v1/" + prefix + "/databases/" + DATABASE + "/tables/" + TABLE + "/token";
    }

    private MvcResult perform(String principal, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal))
                .andReturn();
    }

    private JsonNode json(String principal, int expected, MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = perform(principal, request);
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
        return body == null || body.isBlank() ? null : mapper.readTree(body);
    }
}
