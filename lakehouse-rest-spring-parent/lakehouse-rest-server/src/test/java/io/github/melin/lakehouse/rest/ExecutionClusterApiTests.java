package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogExecutionClusterRepository;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.ExecutionClusterStatus;
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
 * catalog 级执行集群的 HTTP 层验收（设计见 {@code docs/catalog-execution-cluster-design.md}）。
 *
 * <p>这一层要证明的是四条只有走完整链路才看得见的约定：
 *
 * <ol>
 *   <li><b>凭据只进不出。</b>写入的 kubeconfig / 秘密在任何一处响应里都不出现明文，
 *       读回来的是 {@code has*} 与可公开标识；库里存的是能解开的密文。
 *   <li><b>留空即保持。</b>{@code PUT} 是整份替换，但四个密文字段「留空 = 不改」——
 *       否则「改个 namespace、顺手保存」会把凭据清成空串，而失败会推迟到下一次派发。
 *   <li><b>状态是派生的。</b>没验证过是「待验证」、停用是「已停用」、验证失败是「异常」，
 *       三种状态都只由库里那两个 hash 与 {@code enabled} 算出来，不由写入方声明。
 *   <li><b>没配就是 404。</b>「这个 catalog 还没配执行集群」与「catalog 不存在」是两件
 *       要分别引导的事，错误体里的资源类型必须能区分开。
 * </ol>
 *
 * <p>加密密钥通过 {@code properties} 注入，与其它测试上下文互不影响，也不给生产配置
 * 留测试密钥。探活不在本类覆盖：它会真连集群，测不到也不能桩掉（桩掉就测不到它想证的事）。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExecutionClusterApiTests {

    private static final String M = "/api/management/v1";

    private static final String CATALOG = "exec-cluster-test";

    private static final String OTHER = "exec-cluster-copy-src";

    /** 一份服务端真能用的 kubeconfig：静态客户端证书 + 内联 CA。 */
    private static final String KUBECONFIG = """
            apiVersion: v1
            kind: Config
            current-context: dev
            clusters:
            - name: dev
              cluster:
                server: https://10.20.30.41:6443
                certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t
            users:
            - name: dev-admin
              user:
                client-certificate-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t
                client-key-data: LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQ==
            contexts:
            - name: dev
              context:
                cluster: dev
                user: dev-admin
            - name: eks-prod
              context:
                cluster: dev
                user: eks-user
            """;

    /** 带 {@code exec:} 的 kubeconfig：解析要给出「不支持」而不是「可用」。 */
    private static final String KUBECONFIG_EXEC = """
            apiVersion: v1
            kind: Config
            current-context: eks
            clusters:
            - name: eks
              cluster:
                server: https://ABC123.gr7.ap-northeast-1.eks.amazonaws.com
            users:
            - name: eks-user
              user:
                exec:
                  apiVersion: client.authentication.k8s.io/v1beta1
                  command: aws
                  args: ["eks", "get-token", "--cluster-name", "prod"]
            contexts:
            - name: eks
              context:
                cluster: eks
                user: eks-user
            """;

    private static final String SECRET = "wJalrXUtnFEMI/EXAMPLEKEY";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private CatalogExecutionClusterRepository clusterRepository;

    @Autowired
    private CredentialCipher cipher;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (String name : created) {
            // 先删执行集群再删 catalog：H2 上外键级联不一定生效，而残留的那一行会在
            // 同一个 JVM 的其它测试里冒出来（「没配执行集群」的用例会因此失败）
            json(204, "DELETE", M + "/catalogs/" + name + "/execution-cluster", null,
                    true);
            json(204, "DELETE", M + "/catalogs/" + name, null, true);
        }
        created.clear();
    }

    // ------------------------------------------------------------------ 未配置 / 404

    @Test
    void readingBeforeConfiguredIs404WithItsOwnResourceType() throws Exception {
        createCatalog(CATALOG);

        JsonNode error = json(404, "GET", path(), null);
        assertEquals("EXECUTION_CLUSTER", error.get("resourceType").asString(),
                "「还没配执行集群」要能与「catalog 不存在」区分开：" + error);
        assertEquals(CATALOG, error.get("resourceName").asString());

        // 删除一次没配过的同样是 404（不是 204：它什么都没删掉）
        json(404, "DELETE", path(), null);
    }

    // ------------------------------------------------------------------ 自建集群

    @Test
    void createsSelfHostedClusterWithoutEchoingTheKubeconfig() throws Exception {
        createCatalog(CATALOG);

        JsonNode created = json(200, "PUT", path(), """
                {"clusterType":"SELF_HOSTED",
                 "kubeconfig":%s,
                 "namespace":"paimon-opt",
                 "image":"registry.example.com/lakehouse-rest-optimizer:1.0.0",
                 "imagePullPolicy":"IfNotPresent",
                 "callbackBaseUrl":"http://paimon-rest.paimon.svc.cluster.local:8080/",
                 "maxTablesPerJob":20,
                 "maxConcurrentJobs":2,
                 "jobTimeoutSeconds":7200}
                """.formatted(quote(KUBECONFIG)));

        // 1) apiServer 与 context 由解析结果投影出来，不接受手填
        assertEquals("SELF_HOSTED", created.get("clusterType").asString());
        assertEquals("dev", created.get("contextName").asString());
        assertEquals("https://10.20.30.41:6443", created.get("apiServer").asString());
        // 末尾斜杠被吞掉，否则会拼出 //api/...
        assertEquals("http://paimon-rest.paimon.svc.cluster.local:8080", created.get("callbackBaseUrl").asString());

        // 2) kubeconfig 只回摘要：两个 context，其中 dev 可用、eks-prod 因为指向不存在的 user 不可用
        JsonNode kubeconfig = created.get("kubeconfig");
        assertEquals("CONFIGURED", kubeconfig.get("state").asString());
        assertEquals(2, kubeconfig.get("contexts").size());
        assertFalse(created.toString().contains("client-key-data"),
                "响应体任何位置都不应出现 kubeconfig 原文：" + created);
        assertFalse(created.toString().contains("LS0tLS1CRUdJTiBSU0EgUFJJVkFURSBLRVktLS0tLQ"),
                "客户端私钥不能回到浏览器");

        // 3) 新配置是「待验证」，而它可派发（刚配好还没验是正常中间状态）
        assertEquals("UNVERIFIED", created.get("status").asString());
        assertTrue(created.get("enabled").asBoolean());
        assertTrue(created.get("credentialsReadable").asBoolean());
        assertTrue(created.get("probedAt") == null || created.get("probedAt").isNull(),
                "还没验证过，不该有探测时间戳");

        // 4) 库里是密文（能解开才算数）
        CatalogExecutionClusterEntity stored = stored();
        assertTrue(CredentialCipher.sealed(stored.getKubeconfigSealed()), "kubeconfig 必须密封落库");
        assertEquals(KUBECONFIG.trim(), cipher.reveal(stored.getKubeconfigSealed()).trim());
        // hash 参与状态判定：写入时就应该算好，而不是等第一次探活
        assertNotNull(stored.getConfigHash());
        assertNull(stored.getProbeConfigHash(), "没验证过时 probeConfigHash 必须为空，否则会误判成「正常」");
    }

    @Test
    void omittedKubeconfigKeepsTheStoredOneOnUpdate() throws Exception {
        createCatalog(CATALOG);
        JsonNode created = json(200, "PUT", path(), selfHostedBody("paimon-opt"));
        int version = created.get("entityVersion").asInt();
        String sealedBefore = stored().getKubeconfigSealed();

        // 只改 namespace 与镜像，kubeconfig 一个字都不带
        JsonNode updated = json(200, "PUT", path(), """
                {"clusterType":"SELF_HOSTED","entityVersion":%d,
                 "contextName":"dev",
                 "namespace":"paimon-opt-2",
                 "image":"registry.example.com/lakehouse-rest-optimizer:1.0.1",
                 "callbackBaseUrl":"http://paimon-rest.paimon.svc.cluster.local:8080"}
                """.formatted(version));

        assertEquals("paimon-opt-2", updated.get("namespace").asString());
        assertEquals("CONFIGURED", updated.get("kubeconfig").get("state").asString(),
                "留空 = 保持原样：把凭据清成空串是这类表单最典型的事故");
        assertEquals(sealedBefore, stored().getKubeconfigSealed(),
                "明文没变就必须沿用旧密文——重新加密会让 IV 变、configHash 变，状态无故掉回「待验证」");
        assertEquals(version + 1, updated.get("entityVersion").asInt());
    }

    @Test
    void staleEntityVersionIsRejected() throws Exception {
        createCatalog(CATALOG);
        JsonNode created = json(200, "PUT", path(), selfHostedBody("paimon-opt"));
        int version = created.get("entityVersion").asInt();

        JsonNode error = json(409, "PUT", path(), """
                {"clusterType":"SELF_HOSTED","entityVersion":%d,"namespace":"other",
                 "image":"registry.example.com/x:1","callbackBaseUrl":"http://a.b"}
                """.formatted(version - 1));
        assertTrue(error.has("message") || error.has("resourceType"), "409 要能说清是版本冲突：" + error);
        assertEquals("paimon-opt", json(200, "GET", path(), null).get("namespace").asString(),
                "版本冲突时不能落库任何字段");
    }

    @Test
    void disabledClusterIsDerivedAsDisabled() throws Exception {
        createCatalog(CATALOG);
        created.add(CATALOG);
        JsonNode created = json(200, "PUT", path(), selfHostedBody("paimon-opt"));
        int version = created.get("entityVersion").asInt();

        JsonNode disabled = json(200, "PUT", path(), """
                {"clusterType":"SELF_HOSTED","entityVersion":%d,"enabled":false,
                 "contextName":"dev","namespace":"paimon-opt",
                 "image":"registry.example.com/lakehouse-rest-optimizer:1.0.0",
                 "callbackBaseUrl":"http://paimon-rest.paimon.svc.cluster.local:8080"}
                """.formatted(version));
        assertFalse(disabled.get("enabled").asBoolean());
        assertEquals("DISABLED", disabled.get("status").asString(), "停用优先于「待验证」");
        assertEquals(ExecutionClusterStatus.DISABLED, clusterRepository.findByCatalogId(catalogId())
                .map(ExecutionClusterStatus::of).orElseThrow());

        // 不传 enabled 时保持原样：PUT 是整份替换，但「没提这一项」不该把停用悄悄打开
        JsonNode again = json(200, "PUT", path(), """
                {"clusterType":"SELF_HOSTED","entityVersion":%d,"contextName":"dev",
                 "namespace":"paimon-opt",
                 "image":"registry.example.com/lakehouse-rest-optimizer:1.0.1",
                 "callbackBaseUrl":"http://paimon-rest.paimon.svc.cluster.local:8080"}
                """.formatted(disabled.get("entityVersion").asInt()));
        assertFalse(again.get("enabled").asBoolean(), "没传 enabled 就该保持停用");
    }

    // ------------------------------------------------------------------ 云类型

    @Test
    void eksAndAksAreValidatedAndTheirSecretsStayWriteOnly() throws Exception {
        createCatalog(CATALOG);

        // EKS 缺 region → 400，且文案点名是哪一项
        JsonNode missing = json(400, "PUT", path(), """
                {"clusterType":"EKS","clusterName":"prod","apiServer":"https://a.eks.amazonaws.com",
                 "accessKeyId":"AKIAEXAMPLE","secretAccessKey":"%s",
                 "namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b"}
                """.formatted(SECRET));
        assertTrue(missing.get("message").asString().contains("region"), "文案要点名缺的是 region：" + missing);

        // 补齐后成功；accessKeyId 回显（它不是秘密），secret 只回 has*
        JsonNode eks = json(200, "PUT", path(), """
                {"clusterType":"EKS","region":"ap-northeast-1","clusterName":"prod",
                 "apiServer":"https://a.gr7.ap-northeast-1.eks.amazonaws.com",
                 "accessKeyId":"AKIAEXAMPLE","secretAccessKey":"%s",
                 "namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b"}
                """.formatted(SECRET));
        assertEquals("AKIAEXAMPLE", eks.get("eks").get("accessKeyId").asString());
        assertTrue(eks.get("eks").get("hasSecretAccessKey").asBoolean());
        assertFalse(eks.toString().contains(SECRET), "秘密不能回显：" + eks);

        // 换成 AKS：另一类凭据不会被沿用，因此必须重填（这里故意不带 clientSecret → 400）
        JsonNode switched = json(400, "PUT", path(), """
                {"clusterType":"AKS","authority":"PUBLIC","tenantId":"t","clientId":"c",
                 "apiServer":"https://aks.example.com:443",
                 "scope":"6dae42f8-4368-4678-94ff-3960e28e3630/.default",
                 "namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b","entityVersion":%d}
                """.formatted(eks.get("entityVersion").asInt()));
        assertTrue(switched.get("message").asString().contains("clientSecret"),
                "换类型要显式重填秘密，错误文案要点名：" + switched);

        // scope 必须以 /.default 结尾：这条 400 把一部分「401 Invalid audience」提前到保存时。
        // 注意它**只能**拦住后缀（下面的合法性检查是纯语法的）：
        // https://management.core.windows.net/.default 这种「后缀对、audience 错」的写法会存下来，
        // 症状是探活时 401 Invalid audience——那正是探活第 1 步存在的理由。
        JsonNode badScope = json(400, "PUT", path(), """
                {"clusterType":"AKS","authority":"PUBLIC","tenantId":"t","clientId":"c",
                 "clientSecret":"s3cret","apiServer":"https://aks.example.com:443",
                 "scope":"https://management.core.windows.net",
                 "namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b","entityVersion":%d}
                """.formatted(eks.get("entityVersion").asInt()));
        assertTrue(badScope.get("message").asString().contains("/.default"), "要说清 scope 的规则：" + badScope);

        // 后缀对但 audience 错：服务端会收下（它无从判断），这类错只能靠探活暴露
        JsonNode wrongAudience = json(200, "PUT", path(), """
                {"clusterType":"AKS","authority":"PUBLIC","tenantId":"t","clientId":"c",
                 "clientSecret":"s3cret","apiServer":"https://aks.example.com:443",
                 "scope":"https://management.core.windows.net/.default",
                 "namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b","entityVersion":%d}
                """.formatted(eks.get("entityVersion").asInt()));
        assertEquals("https://management.core.windows.net/.default",
                wrongAudience.get("aks").get("scope").asString(),
                "语法合法的 scope 会被存下来；audience 对不对由探活回答，保存时判不了");
    }

    @Test
    void unknownClusterTypeIsRejected() throws Exception {
        createCatalog(CATALOG);
        JsonNode error = json(400, "PUT", path(), """
                {"clusterType":"GKE","namespace":"paimon-opt","image":"registry.example.com/x:1",
                 "callbackBaseUrl":"http://a.b"}
                """);
        assertTrue(error.get("message").asString().contains("SELF_HOSTED"),
                "错误要列出合法取值：" + error);
    }

    // ------------------------------------------------------------------ kubeconfig 解析

    @Test
    void parsesKubeconfigForEveryContextWithoutPersistingIt() throws Exception {
        createCatalog(CATALOG);

        JsonNode parsed = json(200, "POST", path() + "/kubeconfig/parse",
                "{\"kubeconfig\":" + quote(KUBECONFIG) + "}");
        assertEquals("dev", parsed.get("currentContext").asString());
        assertEquals(2, parsed.get("contexts").size());
        assertEquals("OK", parsed.get("contexts").get(0).get("verdict").asString());
        assertEquals("https://10.20.30.41:6443", parsed.get("contexts").get(0).get("server").asString());
        assertEquals("client-certificate-data", parsed.get("contexts").get(0).get("auth").asString());
        // 第二个 context 指向一个不存在的 user：判「不可用」并说明原因，而不是静默变成匿名
        assertEquals("UNAVAILABLE", parsed.get("contexts").get(1).get("verdict").asString());
        assertTrue(parsed.get("contexts").get(1).get("note").asString().contains("不存在"));

        // 只解析、不落库
        json(404, "GET", path(), null);

        // exec: 是硬拒（服务端执行本地命令 = RCE 边界），结论必须是「不支持」而不是「可用」
        JsonNode exec = json(200, "POST", path() + "/kubeconfig/parse",
                "{\"kubeconfig\":" + quote(KUBECONFIG_EXEC) + "}");
        assertEquals("UNSUPPORTED", exec.get("contexts").get(0).get("verdict").asString());
        assertTrue(exec.get("contexts").get(0).get("note").asString().contains("AWS EKS"),
                "硬拒必须给出替代路径（切到 EKS 令牌方式）：" + exec);

        // 用它保存必须 400：不能把一份「服务端永远连不上」的配置存进去
        JsonNode rejected = json(400, "PUT", path(), """
                {"clusterType":"SELF_HOSTED","kubeconfig":%s,"namespace":"paimon-opt",
                 "image":"registry.example.com/x:1","callbackBaseUrl":"http://a.b"}
                """.formatted(quote(KUBECONFIG_EXEC)));
        assertTrue(rejected.get("message").asString().contains("不能用"), "保存要拒绝不可用的 context：" + rejected);
    }

    // ------------------------------------------------------------------ 列表摘要

    @Test
    void catalogListCarriesTheExecutionClusterSummary() throws Exception {
        createCatalog(CATALOG);
        json(200, "PUT", path(), selfHostedBody("paimon-opt"));

        JsonNode row = catalogRow(CATALOG);
        JsonNode summary = row.get("executionCluster");
        assertNotNull(summary, "catalog 列表每行要带执行集群摘要（控制台那一列就靠它）");
        assertTrue(summary.get("configured").asBoolean());
        assertEquals("UNVERIFIED", summary.get("status").asString());
        assertEquals("SELF_HOSTED", summary.get("clusterType").asString());
        assertEquals("paimon-opt", summary.get("namespace").asString());
        assertFalse(summary.toString().contains("client-key-data"), "摘要里更不能有凭据");

        // 没配的 catalog 也要有这一块（configured=false），前端据此写「优化不会运行」
        createCatalog(OTHER);
        JsonNode notConfigured = catalogRow(OTHER).get("executionCluster");
        assertNotNull(notConfigured);
        assertFalse(notConfigured.get("configured").asBoolean());
        assertEquals("NOT_CONFIGURED", notConfigured.get("status").asString());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void deleteRemovesTheWholeConfiguration() throws Exception {
        createCatalog(CATALOG);
        json(200, "PUT", path(), selfHostedBody("paimon-opt"));

        json(204, "DELETE", path(), null);
        json(404, "GET", path(), null);
        assertTrue(clusterRepository.findByCatalogId(catalogId()).isEmpty(), "行必须真的删掉（含凭据）");
        // catalog 本身还在
        assertEquals(CATALOG, json(200, "GET", M + "/catalogs/" + CATALOG, null).get("name").asString());
    }

    // ------------------------------------------------------------------ 工具

    private String path() {
        return M + "/catalogs/" + CATALOG + "/execution-cluster";
    }

    private String selfHostedBody(String namespace) {
        return """
                {"clusterType":"SELF_HOSTED","kubeconfig":%s,"contextName":"dev",
                 "namespace":"%s",
                 "image":"registry.example.com/lakehouse-rest-optimizer:1.0.0",
                 "imagePullPolicy":"IfNotPresent",
                 "callbackBaseUrl":"http://paimon-rest.paimon.svc.cluster.local:8080",
                 "maxTablesPerJob":20,"maxConcurrentJobs":2,"jobTimeoutSeconds":7200}
                """.formatted(quote(KUBECONFIG), namespace);
    }

    private void createCatalog(String name) throws Exception {
        if (catalogRepository.findByPrefix(name).isEmpty()) {
            json(201, "POST", M + "/catalogs", """
                    {"catalog":{"type":"INTERNAL","name":"%s","properties":{},
                     "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/%s"]}}}
                    """.formatted(name, name));
        }
        created.add(name);
    }

    private String catalogId() {
        return catalogRepository.findByPrefix(CATALOG).orElseThrow().getId();
    }

    private CatalogExecutionClusterEntity stored() {
        return clusterRepository.findByCatalogId(catalogId()).orElseThrow();
    }

    private JsonNode catalogRow(String name) throws Exception {
        for (JsonNode row : json(200, "GET", M + "/catalogs", null).get("catalogs")) {
            if (name.equals(row.get("name").asString())) {
                return row;
            }
        }
        throw new AssertionError("catalog 列表里没有 " + name);
    }

    /** 把一段文本包成 JSON 字符串字面量（kubeconfig 里有换行，必须转义）。 */
    private String quote(String text) {
        try {
            return mapper.writeValueAsString(text);
        } catch (RuntimeException impossible) {
            throw new IllegalStateException("cannot serialize a test fixture", impossible);
        }
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        return json(expected, method, path, body, false);
    }

    /**
     * @param tolerate404 收尾清理时用：删一个本来就不存在的东西（404）不算失败，
     *                    而「删成功了」是 204
     */
    private JsonNode json(int expected, String method, String path, String body, boolean tolerate404)
            throws Exception {
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
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String text = result.getResponse().getContentAsString();
        if (tolerate404 && (status == 404 || status == 204)) {
            return text == null || text.isBlank()
                    ? JsonNodeFactory.instance.objectNode()
                    : mapper.readTree(text);
        }
        assertEquals(expected, status, () -> "unexpected status, body=" + text);
        return text == null || text.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(text);
    }
}
