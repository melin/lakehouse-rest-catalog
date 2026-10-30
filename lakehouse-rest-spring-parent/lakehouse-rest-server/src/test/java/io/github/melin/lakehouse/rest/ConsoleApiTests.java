package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 控制台的服务端支撑测试。
 *
 * <p>控制台本身是前端代码，不在这里测；本类只覆盖服务端为它承担的三件事：
 *
 * <ol>
 *   <li><b>元数据端点</b>（{@code /api/console/v1/meta}）。前端表单的取值都来自它，
 *       一旦枚举改名或权限分组漂移，表现为「页面能打开但提交后报错」，
 *       所以这里断言的是内容而不是状态码。
 *   <li><b>单页应用回退</b>。history 路由要求「非文件路径返回入口页」，
 *       而「带扩展名的缺失文件仍然 404」是这条规则的另一半——
 *       少了它，构建产物不同步会变成白屏但状态码 200。
 *   <li><b>构建产物自洽</b>。产物由 {@code scripts/build-console.sh} 生成、**不入库**
 *       （见仓库根 {@code .gitignore}），因此要有一条断言挡住「构建被打断 / 只搬了一半
 *       assets」这类半套产物——它在页面上的表现是白屏，而没有任何东西报错。
 * </ol>
 */
@SpringBootTest(properties = {
        // 默认配置下控制台要求先登录（console.required=true），因此 /api/console/v1/meta
        // 也要求令牌。本类测的是元数据与托管，不想在每条用例里走一遍登录：
        // 登记一个静态令牌，由 getJson 统一带上——顺带把「门禁开启时静态令牌可用」也覆盖了
        "lakehouse.rest.auth.tokens[0]=console-api-test-token"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConsoleApiTests {

    private static final String META = "/api/console/v1/meta";

    private static final String AUTH = "/api/console/v1/auth";

    /** 每个可见 catalog 的凭据来源（三档 + 配置有误）。 */
    private static final String CREDENTIAL_SOURCES = "/api/console/v1/catalog-credential-sources";

    /** 表单里**还没保存**的那份配置的凭据来源（单个，POST 一份 storageConfigInfo）。 */
    private static final String CREDENTIAL_SOURCE = "/api/console/v1/catalog-credential-source";

    /** 可见性必须与这条列表一致：来源端点照抄它的规则，条数也要对得上。 */
    private static final String MANAGEMENT_CATALOGS = "/api/management/v1/catalogs";

    private static final String TOKEN = "console-api-test-token";

    private static final String CONSOLE_ENTRY = "static/console/index.html";

    /** 入口页里引用的、以控制台基址开头的静态资源。 */
    private static final Pattern CONSOLE_ASSET = Pattern.compile("(?:src|href)=\"(/console/[^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode getJson(String path, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    // ------------------------------------------------------------------ 元数据

    @Test
    void metaExposesEnumsUsedByConsoleForms() throws Exception {
        JsonNode body = getJson(META, 200);
        JsonNode service = body.get("service");
        JsonNode enums = body.get("enums");
        assertNotNull(service, "service block is missing");
        assertNotNull(enums, "enums block is missing");

        assertEquals("lakehouse-rest-server", service.get("name").asString());
        assertEquals("paimon", service.get("defaultPrefix").asString());
        // 新建 catalog 表单的预选格式由服务端下发，而不是前端恒选第一项：
        // 部署改过默认格式时两者会不一致
        assertEquals("PAIMON", service.get("defaultCatalogFormat").asString());
        assertFalse(service.get("authEnabled").asBoolean(), "auth is off in the test profile");
        assertEquals("default", service.get("credentialManagerType").asString());
        assertEquals("default", service.get("fileIoType").asString());
        assertTrue(service.get("maxPageSize").asInt() > 0);

        assertEquals(List.of("INTERNAL", "EXTERNAL"), strings(enums.get("catalogTypes")));
        // 表格式与托管模式正交，两个列表都要下发：控制台的新建表单用它渲染格式选择，
        // 顺序即建议的展示顺序（默认格式在前），因此这里断言顺序而不只是集合
        // LANCE 是第三个协议面（/lance/v1/**），catalog 一建就定型，建的入口与其它两种一致
        assertEquals(List.of("PAIMON", "ICEBERG", "LANCE"), strings(enums.get("catalogFormats")));
        // 三个扩展存储类型必须在取值集合里：控制台的存储类型下拉框据此渲染
        assertEquals(Set.of("S3", "GCS", "AZURE", "OBS", "OSS", "COS", "FILE"),
                new LinkedHashSet<>(strings(enums.get("storageTypes"))));

        // file-io.type=default 时全部存储可用，FILE 恒在其中
        assertEquals(Set.of("S3", "GCS", "AZURE", "OBS", "OSS", "COS", "FILE"),
                new LinkedHashSet<>(strings(enums.get("supportedStorageTypes"))));

        assertTrue(strings(enums.get("credentialManagerTypes")).containsAll(List.of("default", "noop")));
        assertTrue(strings(enums.get("fileIoTypes")).containsAll(List.of("default", "local", "obs", "oss", "cosn")));

        // 资源类型 = 官方 PermissionResource 的 8 个 discriminator 取值。
        // 字段名 `grantTypes` 是历史命名（原先指 Polaris 的授权类型小写值），
        // 控制台仍按这个名字读，因此这里只改内容不改键名。
        assertEquals(
                List.of("CATALOG", "CATALOG_ALL", "DATABASE", "DATABASE_ALL",
                        "TABLE", "COLUMN", "VIEW", "FUNCTION"),
                strings(enums.get("grantTypes")));
    }

    /**
     * 「资源类型 → 允许授予的 access」必须与服务端判定用的 {@code Access.allowedFor}
     * 出自同一张表。若控制台拿到的分组与判定用的分组不同源，用户会看到能选、
     * 但提交必被拒的 access。
     *
     * <p>抽查四组边界，覆盖官方对照表里最容易写错的四处：
     * {@code CATALOG} 没有 {@code DESCRIBE}、{@code DATABASE_ALL} 没有
     * {@code CREATETABLE}、{@code COLUMN} 只有 {@code SELECT}、{@code VIEW} 没有
     * 命名空间级的 {@code LIST}。
     */
    @Test
    void metaAccessesFollowOfficialMatrix() throws Exception {
        JsonNode accesses = getJson(META, 200).get("enums").get("privilegesByGrantType");
        assertNotNull(accesses);

        List<String> catalog = strings(accesses.get("CATALOG"));
        List<String> catalogAll = strings(accesses.get("CATALOG_ALL"));
        List<String> databaseAll = strings(accesses.get("DATABASE_ALL"));
        List<String> column = strings(accesses.get("COLUMN"));
        List<String> view = strings(accesses.get("VIEW"));

        assertTrue(catalog.contains("CREATEDATABASE"));
        assertFalse(catalog.contains("DESCRIBE"),
                "官方对照表里 CATALOG 没有任何读权限");
        assertTrue(catalogAll.contains("LIST"));
        assertFalse(catalogAll.contains("CREATEDATABASE"),
                "建库只在 CATALOG 上允许");
        assertFalse(databaseAll.contains("CREATETABLE"),
                "DATABASE_ALL 的词表里没有 CREATETABLE");
        assertEquals(List.of("SELECT"), column);
        assertTrue(view.contains("DESCRIBE"));
        assertFalse(view.contains("LIST"),
                "VIEW 层没有 LIST，列举是 DATABASE 上集合操作");
    }

    // ------------------------------------------------------------------ 登录引导

    /**
     * 默认配置下的登录引导：控制台要求登录，但数据面仍然开放。
     *
     * <p>这两个字段必须分开报告，而且必须同时如实：{@code authEnabled=false} 说明
     * {@code /v1/**} 与管理 API 仍然是匿名可调的，{@code consoleRequired=true} 说明
     * 控制台自己会拦在登录页上。控制台据此在登录页写一句「这层门禁只挡住界面」——
     * 少了任何一个字段，那句话要么写不出来，要么会写成「本服务端已受保护」。
     *
     * <p>{@code methods} 必须非空：门禁开着时要能告诉用户怎么进来。
     */
    @Test
    void authAsksForALoginEvenWhenTheDataPlaneIsOpen() throws Exception {
        // 这条必须匿名请求：getJson 会带上令牌，而「未登录时 session 是什么样」
        // 正是本用例要断言的一半内容
        MvcResult result = mockMvc.perform(get(AUTH)).andReturn();
        JsonNode body = mapper.readTree(body(result));
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);

        assertFalse(body.get("authEnabled").asBoolean(), "测试 profile 下 lakehouse.rest.auth.enabled=false");
        assertTrue(body.get("consoleRequired").asBoolean(), "控制台默认要求登录");
        assertEquals(List.of("password", "client-credentials", "static-token"), strings(body.get("methods")),
                "用户名密码排在第一个：它不需要先建主体");
        assertFalse(body.get("session").get("authenticated").asBoolean());
        assertNull(body.get("session").get("principal"));
        assertNull(body.get("oidc"), "未启用 OIDC 时不该下发客户端参数");
    }

    /**
     * 门禁模式下带着令牌访问登录引导，要能认出「你是谁」。
     *
     * <p>与上一条互为对照：同一个端点，带没带令牌的差别只体现在 {@code session} 上，
     * 而不是整个请求被 401 挡掉——登录页需要能读到「支持哪些登录方式」，
     * 即使手里拿着的是个过期令牌。
     */
    @Test
    void authReportsTheSessionWhenATokenIsPresent() throws Exception {
        JsonNode session = getJson(AUTH, 200).get("session");

        assertTrue(session.get("authenticated").asBoolean());
        // 本类的令牌没有配 token-principals 映射，因此主体名就是令牌本身
        assertEquals(TOKEN, session.get("principal").asString());
        assertEquals("static-token", session.get("source").asString());
    }

    /**
     * 门禁开着时，控制台自己的端点要求令牌。
     *
     * <p>这是「只让控制台要求登录」这层门禁在服务端的落点：控制台界面被拦在登录页，
     * 它的数据端点也不接受匿名请求。**注意范围**——只有 {@code /api/console/v1/**}
     * 这一段，catalog API 与管理 API 仍按 {@code lakehouse.rest.auth.enabled} 走。
     */
    @Test
    void consoleEndpointsRejectAnonymousRequestsWhileTheGateIsOn() throws Exception {
        assertEquals(401, mockMvc.perform(get(META)).andReturn().getResponse().getStatus(),
                "门禁开着时匿名请求不该读到控制台元数据");
        assertNotNull(getJson(META, 200), "带令牌仍应可读");

        // 数据面不受门禁影响：这是默认配置下刻意保留的行为，写成断言免得被无意改掉
        assertEquals(200, mockMvc.perform(get("/v1/config?warehouse=paimon")).andReturn()
                .getResponse().getStatus(), "console.required 不该让 /v1/** 也要求令牌");
    }

    /**
     * 令牌端点的地址由服务端下发，且与 Polaris 一致。
     *
     * <p>控制台是构建期打包的静态资源，读不到服务端配置，因此这个地址只能运行时下发。
     * 断言它的取值是为了守住与 Polaris 的对齐：按 OAuth 2.0 写的客户端与
     * Polaris 官方 console 的默认 {@code VITE_OAUTH_TOKEN_URL} 都是这个路径。
     */
    @Test
    void authAdvertisesThePolarisCompatibleTokenEndpoint() throws Exception {
        assertEquals("/api/catalog/v1/oauth/tokens", getJson(AUTH, 200).get("tokenEndpoint").asString());
    }

    // ------------------------------------------------------------------ 凭据来源

    /**
     * 「凭据来源」必须覆盖**当前可见的每个** catalog，且取值落在四档之内。
     *
     * <p>为什么钉「条数一致」而不是只钉 200：控制台拿这一份去标列表的每一行，
     * 少一条的表现是那一行显示「—」（`credentialSourceCell` 的兜底），
     * 而人多半会把它读成「这个 catalog 不下发凭据」——一个错误的结论，
     * 却没有任何报错。条数与管理 API 的可见列表相等，是这条契约最省事的判据。
     *
     * <p>测试 profile 的仓库是本地文件系统，因此至少有一条 `NONE`，且 detail 里
     * 应当说的是「没有密钥可发」而不是别的原因。
     */
    @Test
    void credentialSourcesCoverEveryVisibleCatalog() throws Exception {
        JsonNode body = getJson(CREDENTIAL_SOURCES, 200);
        JsonNode sources = body.get("sources");
        assertNotNull(sources, "sources is missing");
        assertTrue(sources.isArray(), "sources should be an array");

        // credentialProviders 一起回：它决定「签发」这一档在本部署是否可用，
        // 详情页要据此解释「填了 roleArn 为什么仍显示不下发」
        assertNotNull(body.get("credentialProviders"), "credentialProviders is missing");

        Set<String> modes = Set.of("STS", "RELAY", "NONE", "INVALID");
        for (JsonNode entry : sources) {
            assertFalse(entry.get("catalog").asString().isBlank(), "catalog is blank");
            assertTrue(modes.contains(entry.get("mode").asString()),
                    () -> "unexpected mode: " + entry.get("mode").asString());
            assertFalse(entry.get("detail").asString().isBlank(),
                    "detail must say which branch was taken, otherwise the badge is unexplained");
        }

        JsonNode catalogs = getJson(MANAGEMENT_CATALOGS, 200).get("catalogs");
        assertEquals(catalogs.size(), sources.size(),
                "凭据来源必须覆盖可见的每个 catalog：少一条时那一行显示「—」，会被读成「不下发」");

        assertTrue(sources.size() > 0, "测试 profile 至少有一个预置 catalog");
        // 预置 catalog 的仓库是 file://（见 application-test.yml），文件系统没有密钥可发
        assertEquals("NONE", sources.get(0).get("mode").asString(),
                "file:// 仓库的凭据来源应当是「不下发」");
    }

    /**
     * 表单草稿的判定：这一条是「保存前就要看得见」的那部分。
     *
     * <p>逐条钉住的是**同一个判定在不同输入下的结论**，而不是 HTTP 行为：
     * 这个端点的价值全在 detail 那句话上——它要说清走的是哪条分支，
     * 否则界面上仍是一个需要用户自己猜的标签。
     */
    @Test
    void credentialSourcePreviewJudgesTheDraftBeforeItIsSaved() throws Exception {
        // ① 填了 roleArn、但本部署没启用 s3-token（测试 profile 的 providers 是空的）。
        //    这是整件事的由头：它与「什么都没填」的结果一模一样，字段上看不出来，
        //    保存后要回列表页才看得见——而那时用户已经在另一步上了
        JsonNode orphanRole = previewDraft(Map.of(
                "storageType", "S3",
                "allowedLocations", List.of("s3://bucket/warehouse"),
                "roleArn", "arn:aws:iam::123456789012:role/lakehouse"));
        assertEquals("NONE", orphanRole.get("mode").asString());
        assertTrue(orphanRole.get("detail").asString().contains("s3-token"),
                () -> "detail 必须点名要开的配置项，否则用户不知道去改什么："
                        + orphanRole.get("detail").asString());

        // ② catalog 自带静态凭据 → 转发档。它同时是「填了 roleArn 也不会签发」的另一种情形：
        //    静态凭据是调用方的明确决定，STS 只是服务端的默认策略，策略不覆盖决定
        JsonNode relay = previewDraft(Map.of(
                "storageType", "S3",
                "allowedLocations", List.of("s3://bucket/warehouse"),
                "accessKeyId", "AKIAEXAMPLE",
                "secretAccessKey", "s3cr3t"));
        assertEquals("RELAY", relay.get("mode").asString());

        // ③ 打码值不影响结论：编辑一份已保存的配置时，表单拿到的 accessKeyId 是 ******，
        //    它与真值得判出同一档——否则「打开编辑框」这个动作本身就会改变界面上显示的档位
        JsonNode masked = previewDraft(Map.of(
                "storageType", "S3",
                "allowedLocations", List.of("s3://bucket/warehouse"),
                "accessKeyId", "******"));
        assertEquals("RELAY", masked.get("mode").asString(),
                "对外那份（已打码）与库里那份必须判出同一档");

        // ④ 引用了不存在的具名存储 → 配置有误。保存时它不报错，取票时才 500，
        //    因此这是唯一能在保存前说出来的一档
        JsonNode invalid = previewDraft(Map.of(
                "storageType", "S3",
                "allowedLocations", List.of("s3://bucket/warehouse"),
                "storageName", "no-such-storage"));
        assertEquals("INVALID", invalid.get("mode").asString());

        // ⑤ 本地文件系统：没有密钥可发
        JsonNode file = previewDraft(Map.of(
                "storageType", "FILE",
                "allowedLocations", List.of("file:///data/warehouse")));
        assertEquals("NONE", file.get("mode").asString());

        // ⑥ 控制台不认识的存储类型：400，前端据此把预览藏起来（表单自己会先拦住这种情况）。
        //    这里断言的是「解析失败不要变成 500」——500 会让控制台弹出全局错误提示，
        //    而用户只是还没选完类型
        MvcResult bad = mockMvc.perform(post(CREDENTIAL_SOURCE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storageType\":\"NOPE\",\"allowedLocations\":[\"nope://x/y\"]}"))
                .andReturn();
        String badBody = bad.getResponse().getContentAsString();
        assertEquals(400, bad.getResponse().getStatus(), () -> "body=" + badBody);
    }

    private JsonNode previewDraft(Map<String, Object> draft) throws Exception {
        MvcResult result = mockMvc.perform(post(CREDENTIAL_SOURCE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(draft)))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    // ------------------------------------------------------------------ 单页应用回退

    @Test
    void consoleRootRedirectsToTrailingSlash() throws Exception {
        MvcResult result = mockMvc.perform(get("/console")).andReturn();
        assertEquals(302, result.getResponse().getStatus());
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.endsWith("/console/"), () -> "unexpected Location: " + location);
    }

    /**
     * {@code /console/} 转发到入口页。
     *
     * <p>断言的是转发目标而不是响应体：目录式路径到不了资源解析器
     * （见 {@code ConsoleWebConfig} 的说明），这里要守住的就是「这条转发还在」。
     * 入口页本身能否取到，由 {@link #consoleEntryIsServedAtItsRealUrl} 覆盖。
     */
    @Test
    void consoleDirectoryUrlForwardsToEntryPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/console/")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("/console/index.html", result.getResponse().getForwardedUrl());
    }

    @Test
    void consoleEntryIsServedAtItsRealUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/console/index.html")).andReturn();
        String body = body(result);
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "响应没有 Content-Type");
        assertTrue(contentType.startsWith("text/html"),
                () -> "入口页应当是 HTML，实际为 " + contentType);
        assertTrue(body.contains("id=\"app\""), "入口页应当是控制台的挂载点");
    }

    /**
     * 前端路由的深链接与多级路径都要回退到入口页。
     *
     * <p>五条路径分别覆盖：一级路由（{@code /catalogs}）、带查询串的路由、
     * 表详情这种三段式路由（{@code /tables/{catalog}/{database}/{table}}）、
     * OIDC 回调（{@code /auth/callback}）——最后这条是外部 IdP 重定向回来的落点，
     * 它回退失败意味着 SSO 登录在这台服务端上永远走不完；
     * 以及登录页本身。
     */
    @Test
    void clientSideRoutesFallBackToEntryPage() throws Exception {
        String entry = readClasspathText(CONSOLE_ENTRY);
        for (String path : List.of(
                "/console/catalogs",
                "/console/browse?catalog=paimon",
                "/console/tables/paimon/default/orders",
                "/console/principal-roles",
                "/console/catalog-roles",
                "/console/login",
                "/console/auth/callback")) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            String body = body(result);
            assertEquals(200, result.getResponse().getStatus(), () -> path + " body=" + body);
            assertEquals(entry, body, () -> path + " 应当返回入口页原文");
        }
    }

    /**
     * 缺失的静态文件仍然 404。
     *
     * <p>这是回退规则的另一半：构建产物更新后浏览器仍持有旧入口页的情况很常见，
     * 若把缺失的 JS 也回退成 HTML，症状会是「白屏但一切 200」，只能靠翻控制台发现。
     */
    @Test
    void missingAssetStillReturns404() throws Exception {
        MvcResult result = mockMvc
                .perform(get("/console/assets/this-file-does-not-exist-000000.js"))
                .andReturn();
        assertEquals(404, result.getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 构建产物

    /**
     * 当前构建出的入口页里引用的每个资源都真实存在。
     *
     * <p>构建产物的文件名带内容哈希，重新构建会同时换掉入口页与 assets，两者是一个整体。
     * 产物**不入库**（见仓库根 {@code .gitignore}），因此这里要挡的不是「只提交了一半」，
     * 而是三种会留下半套产物的情形：构建被 Ctrl-C 打断、手工清理了 assets 却留下入口页、
     * 从别处 rsync 了一份不完整的目录。三种情形下 {@code /console/} 都是白屏，
     * 而服务端、构建日志、{@code git status} 全都无话可说。
     */
    @Test
    void consoleHtmlReferencesExistingAssets() throws Exception {
        String html = readClasspathText(CONSOLE_ENTRY);
        Set<String> assets = new LinkedHashSet<>();
        Matcher matcher = CONSOLE_ASSET.matcher(html);
        while (matcher.find()) {
            assets.add(matcher.group(1));
        }
        assertFalse(assets.isEmpty(), "入口页没有引用任何 /console/ 下的资源，构建产物不完整");

        List<String> missing = new ArrayList<>();
        for (String asset : assets) {
            String relativePath = asset.substring("/console/".length());
            if (!new ClassPathResource("static/console/" + relativePath).exists()) {
                missing.add(asset);
            }
        }
        assertTrue(missing.isEmpty(), () -> "入口页引用了不存在的资源：" + missing);
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> strings(JsonNode array) {
        assertNotNull(array, "expected a JSON array");
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    /**
     * 按 UTF-8 读响应体。
     *
     * <p>静态资源的响应头只有 {@code text/html} 而没有 charset，
     * {@code getContentAsString()} 会退回 ISO-8859-1，中文注释变成乱码，
     * 与期望内容逐字比较时必然失败——而这只说明读取方式不对，不是页面有问题。
     */
    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 读 classpath 上的文本；不存在时给一条「该跑哪条命令」的说明。
     *
     * <p>控制台产物不入库，所以干净检出直接跑测试必然读不到它。默认的
     * {@code NoSuchResource} 只会说「资源打不开」，读者要自己推断是路径写错还是
     * 根本没构建——这里把下一步动作写进消息里。
     */
    private static String readClasspathText(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "classpath 里没有 " + path + "：控制台产物不入库（见仓库根 .gitignore），"
                    + "构建服务端之前要先跑 ./scripts/build-console.sh（需要 Node 20+）");
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
