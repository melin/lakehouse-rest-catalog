package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
 * 按存储类型下发凭据的 HTTP 层验收。
 *
 * <p>为什么需要这一层：{@code StorageCredentialResolutionTests} 证明了解析规则本身对，
 * 但证明不了「读表接口真的读了 catalog 的存储配置」。这两件事之间隔着一段容易断的链路——
 * catalog 的配置是从 JSON 列反序列化出来的，不是内存里的对象；凭据经过缓存再返回。
 * 只有走完整链路才能发现「解析写对了但拿到的是一份空配置」这类问题。
 *
 * <p><b>所有密钥都写在 {@code storageConfigInfo} 里</b>（这是「配置跟着 catalog 走」的
 * 落点），所以每个用例都是一份 catalog 配置 + 一次取票。服务端配置只剩加密密钥，
 * 它通过 {@code properties} 注入而不是改 application.yml：这样这个 Spring 上下文
 * 与其它测试的上下文互不影响，也不会给生产配置留下测试用的密钥。
 *
 * <p>{@code test} profile 把数据源换成内存 H2，本类不依赖外部服务，也不真的访问任何云存储——
 * 凭据解析不发出网请求，这一点是被测行为的一部分。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StorageCredentialApiTests {

    private static final String M = "/api/management/v1";

    private static final String TABLE = "orders";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void deleteCreatedCatalogs() throws Exception {
        for (String name : created) {
            call(204, delete(M + "/catalogs/" + name));
        }
        created.clear();
    }

    // ------------------------------------------------------------------ S3

    /** catalog 上写什么就发什么；服务端专用的两个地址一个字节都不出去。 */
    @Test
    void s3CatalogVendsItsOwnCredentials() throws Exception {
        String prefix = "s3-cred";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"ap-east-1",
                 "endpoint":"https://s3.example.com:1234",
                 "endpointInternal":"https://s3.internal.example.com:1234",
                 "pathStyleAccess":true,
                 "accessKeyId":"AKIA-CATALOG",
                 "secretAccessKey":"SECRET-CATALOG"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("AKIA-CATALOG", token.get("s3.access-key-id").asString());
        assertEquals("SECRET-CATALOG", token.get("s3.secret-access-key").asString());
        assertEquals("ap-east-1", token.get("s3.region").asString());
        assertEquals("https://s3.example.com:1234", token.get("s3.endpoint").asString());
        assertEquals("true", token.get("s3.path-style-access").asString());

        // 服务端专用地址绝不能下发
        assertFalse(token.has("s3.endpoint-internal"));
        assertFalse(token.has("s3.sts-endpoint"));
        assertFalse(token.toString().contains("s3.internal.example.com"));
    }

    /**
     * 没填密钥的 catalog 只有定位信息可发。
     *
     * <p>这条同时守住了一个反向性质：**服务端没有「默认凭据」可以顶上**。
     * 早先这一档会回落到 {@code lakehouse.rest.storage.aws.access-key}，
     * 那份配置已经删了——现在不下发密钥就是不下发，引擎走自己的凭据链。
     */
    @Test
    void s3CatalogWithoutCredentialsVendsNoSecrets() throws Exception {
        String prefix = "s3-no-creds";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"us-west-2"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertFalse(token.has("s3.access-key-id"));
        assertFalse(token.has("s3.secret-access-key"));
        assertEquals("us-west-2", token.get("s3.region").asString(), "定位信息照发");
    }

    /** {@code storageName} 是纯标签：填了它不会改变任何下发结果。 */
    @Test
    void storageNameDoesNotChangeVending() throws Exception {
        String prefix = "s3-labeled";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://named-bucket/warehouse/"],
                 "storageName":"named-a"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertFalse(token.has("s3.access-key-id"),
                "标签不指向任何密钥，所以这里什么都不该出现: " + token);
    }

    /** {@code stsUnavailable} 表示服务端不代发凭据，但仍应告诉引擎连哪里。 */
    @Test
    void s3CatalogWithStsUnavailableVendsNoSecrets() throws Exception {
        String prefix = "s3-no-sts";
        createCatalog(prefix, """
                {"storageType":"S3",
                 "allowedLocations":["s3://analytics-bucket/warehouse/"],
                 "region":"eu-west-1",
                 "stsUnavailable":true}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertFalse(token.has("s3.access-key-id"));
        assertFalse(token.has("s3.secret-access-key"));
        assertEquals("eu-west-1", token.get("s3.region").asString());
    }

    // ------------------------------------------------------------------ Azure

    @Test
    void azureCatalogVendsTenantAndAccountWithoutSecrets() throws Exception {
        String prefix = "azure-cred";
        createCatalog(prefix, """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@myaccount.dfs.core.windows.net/warehouse/"],
                 "tenantId":"72f988bf-86f1-41af-91ab-2d7cd011db47",
                 "hierarchical":true}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("72f988bf-86f1-41af-91ab-2d7cd011db47", token.get("azure.tenant-id").asString());
        assertEquals("myaccount", token.get("azure.account").asString());
        assertEquals("true", token.get("azure.hierarchical").asString());
        // 没有账户密钥就没有可下发的凭据，这一点必须是显式的，不能靠「大概没有」
        assertFalse(token.has("adls.sas-token"));
        assertFalse(token.has("azure.account-key"));
    }

    /**
     * 服务主体写在 catalog 上，但**不会**被当成数据面凭据下发。
     *
     * <p>它是「服务端去换委托密钥」的身份；真正该出现在 token 里的是 SAS，而 SAS 只在
     * 启用 {@code azure-sas} 时才有。把 {@code clientSecret} 发出去不仅没用，
     * 还会让来源字段写成「静态凭据」，看上去一切正常。
     */
    @Test
    void azureServicePrincipalIsNeverVended() throws Exception {
        String prefix = "azure-principal";
        createCatalog(prefix, """
                {"storageType":"AZURE",
                 "allowedLocations":["abfss://container@myaccount.dfs.core.windows.net/warehouse/"],
                 "tenantId":"tenant-1",
                 "clientId":"app-client-id",
                 "clientSecret":"app-client-secret"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertFalse(token.has("adls.sas-token"));
        assertFalse(token.toString().contains("app-client-id"), token.toString());
        assertFalse(token.toString().contains("app-client-secret"), token.toString());
        assertEquals("tenant-1", token.get("azure.tenant-id").asString());
    }

    // ------------------------------------------------------------------ GCS

    /**
     * GCS 只发定位信息；服务账号私钥一个字节都不出去。
     *
     * <p>没启用 {@code gcs-downscope} 时不存在可下发的令牌，而 {@code serviceAccountJson}
     * 是能签发令牌的长期私钥——它比一对 AK/SK 更敏感，绝不能出现在响应里。
     */
    @Test
    void gcsCatalogVendsLocatorOnly() throws Exception {
        String prefix = "gcs-cred";
        createCatalog(prefix, """
                {"storageType":"GCS",
                 "allowedLocations":["gs://analytics-bucket/warehouse/"],
                 "gcsServiceAccount":"catalog@project.iam.gserviceaccount.com",
                 "serviceAccountJson":"{\\"client_email\\":\\"catalog@project.iam.gserviceaccount.com\\",\\"private_key\\":\\"-----BEGIN PRIVATE KEY-----\\\\nMIIB\\\\n-----END PRIVATE KEY-----\\\\n\\"}"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("catalog@project.iam.gserviceaccount.com",
                token.get("gcs.service-account").asString());
        assertFalse(token.has("gcs.oauth2.token"), "没启用签发就不该有令牌: " + token);
        assertFalse(token.toString().contains("PRIVATE KEY"), "私钥不得下发: " + token);
    }

    /** 服务账号 JSON 落到响应里必须是打码值：它没有 id 那栏可打，星号就打在它自己身上。 */
    @Test
    void gcsServiceAccountJsonIsMaskedInResponses() throws Exception {
        String prefix = "gcs-masked";
        JsonNode createdCatalog = createCatalog(prefix, """
                {"storageType":"GCS",
                 "allowedLocations":["gs://analytics-bucket/warehouse/"],
                 "serviceAccountJson":"{\\"client_email\\":\\"svc@project.iam.gserviceaccount.com\\",\\"private_key\\":\\"k\\"}"}
                """);

        JsonNode storage = createdCatalog.get("storageConfigInfo");
        assertEquals("******", storage.get("serviceAccountJson").asString());
        assertFalse(createdCatalog.toString().contains("svc@project.iam.gserviceaccount.com"),
                "打码后响应里不该残留私钥内容: " + createdCatalog);
    }

    // ------------------------------------------------------------------ OBS / OSS

    /**
     * 扩展类型走的是同一条下发链路，要证明的是「读表接口真的按 catalog 的类型选了对应键族」。
     *
     * <p>这是端到端最容易漏的一环：本地单测里 {@code manager.vend(obs, ...)} 的入参是直接
     * 构造出来的，而真实路径上的存储类型来自 JSON 列的反序列化结果。两者不一致时，
     * 下发的会是另一套键名，而引擎只是静默地拿不到凭据。
     */
    @Test
    void obsCatalogVendsObsKeyFamily() throws Exception {
        String prefix = "obs-cred";
        createCatalog(prefix, """
                {"storageType":"OBS",
                 "allowedLocations":["obs://analytics-bucket/warehouse/"],
                 "endpoint":"obs.cn-north-4.myhuaweicloud.com",
                 "accessKeyId":"OBS-AK","secretAccessKey":"OBS-SK"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("OBS-AK", token.get("fs.obs.access.key").asString());
        assertEquals("OBS-SK", token.get("fs.obs.secret.key").asString());
        assertEquals("obs.cn-north-4.myhuaweicloud.com", token.get("fs.obs.endpoint").asString());
        // catalog 上只有 AK/SK，没有会话令牌可发
        assertFalse(token.has("fs.obs.session.token"), "长期凭据不该带令牌: " + token);
        // 另两套键名族一个都不能出现
        assertFalse(token.has("s3.access-key-id"));
        assertFalse(token.has("fs.oss.accessKeyId"));
    }

    /** 阿里云侧的键名是驼峰，且没配临时凭据时不该出现空的 securityToken。 */
    @Test
    void ossCatalogVendsOssKeyFamily() throws Exception {
        String prefix = "oss-cred";
        createCatalog(prefix, """
                {"storageType":"OSS",
                 "allowedLocations":["oss://analytics-bucket/warehouse/"],
                 "endpoint":"oss-cn-hangzhou.aliyuncs.com",
                 "accessKeyId":"OSS-AK","secretAccessKey":"OSS-SK"}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertEquals("OSS-AK", token.get("fs.oss.accessKeyId").asString());
        assertEquals("OSS-SK", token.get("fs.oss.accessKeySecret").asString());
        assertEquals("oss-cn-hangzhou.aliyuncs.com", token.get("fs.oss.endpoint").asString());
        assertFalse(token.has("fs.oss.securityToken"));
        assertFalse(token.has("fs.obs.access.key"));
        assertFalse(token.has("s3.access-key-id"));
    }

    // ------------------------------------------------------------------ FILE 与缓存

    /** 本地仓库没有凭据，保留既有的自包含令牌与表路径。 */
    @Test
    void fileCatalogKeepsSelfContainedToken() throws Exception {
        String prefix = "file-cred";
        createCatalog(prefix, """
                {"storageType":"FILE","allowedLocations":["file:///tmp/file-cred-warehouse/"]}
                """);
        seedTable(prefix);

        JsonNode token = token(prefix).get("token");
        assertTrue(token.get("accessKeyId").asString().startsWith("PAIMON-"));
        assertTrue(token.has("securityToken"));
        assertTrue(token.get("tablePath").asString().contains("file-cred-warehouse"));
    }

    /**
     * 同一张表在凭据有效期内反复读，拿到的是同一份凭据。
     *
     * <p>断言过期时刻相同，而不是只断言密钥相同：FILE 的密钥本来就是随机的，
     * 若每次都重新签发，过期时刻会往后漂——那说明缓存没生效，
     * 而密钥相同这种断言在配置驱动的云存储上根本看不出区别。
     */
    @Test
    void repeatedRequestsReuseCachedCredential() throws Exception {
        String prefix = "s3-cache";
        createCatalog(prefix, """
                {"storageType":"S3","allowedLocations":["s3://analytics-bucket/warehouse/"]}
                """);
        seedTable(prefix);

        JsonNode first = token(prefix);
        JsonNode second = token(prefix);

        assertEquals(first.get("expiresAt").asLong(), second.get("expiresAt").asLong());
        assertEquals(first.get("token").toString(), second.get("token").toString());
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode createCatalog(String name, String storageConfigInfo) throws Exception {
        created.add(name);
        return json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":%s}}
                """.formatted(name, storageConfigInfo));
    }

    /** 建库并建表，使凭据下发接口有对象可查。 */
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

    private JsonNode token(String prefix) throws Exception {
        return json(200, "GET", tokenPath(prefix), null);
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
