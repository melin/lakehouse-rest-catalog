package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 接口文档页（knife4j-next）在 HTTP 层的验收：**能看，但不能借它绕过门禁**。
 *
 * <p>为什么要在真容器里测（{@code RANDOM_PORT} 而不是 {@code MockMvc}）：
 * {@code /doc.html} 是 UI jar 里 {@code META-INF/resources} 下的静态文件，由容器的
 * 默认 servlet 提供，{@code MockMvc} 不走那一条链路——在 MockMvc 里它永远是 404，
 * 于是「测试通过、浏览器打不开」这种最该被发现的错会被测成绿色。
 *
 * <p>两条不变量，方向相反，缺一条都会让另一条失去意义：
 *
 * <ul>
 *   <li><b>文档页不需要登录</b>：{@code auth.enabled=true} 时它照样 200。
 *       这是用户明确要的效果——本地/内网看一眼接口清单不该先去换令牌。</li>
 *   <li><b>端点本身不会因此变松</b>：同一份配置下 {@code /api/management/v1/**}
 *       与 {@code /v1/**} 仍然 401。文档只负责把端点说清楚；页面上的「调试」
 *       发出去的请求照样要过鉴权。</li>
 * </ul>
 *
 * <p>分组也在这里钉住：三组的 {@code packages-to-scan} 一旦与控制器真实的包名
 * 脱节，表现是「UI 里那一组接口凭空消失」——不报错、日志也没有，只有打开页面
 * 才发现。逐个组取一次 {@code /v3/api-docs/{group}} 比打开页面便宜得多。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 文档页公开这条要求在「整体鉴权开着」时才算被验证过：
        // 鉴权关着时任何路径都 200，那种绿灯什么也说明不了
        "lakehouse.rest.auth.enabled=true",
        "lakehouse.rest.auth.access-token.signing-key=dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=",
        "spring.datasource.url=jdbc:h2:mem:paimon-api-doc;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class ApiDocEndpointTests {

    /** 三组都要能取到：组名与 {@code application.yml} 的 {@code springdoc.group-configs} 一一对应。 */
    private static final String[] GROUPS = {"paimon", "iceberg", "management"};

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void docPageIsServedWithoutAToken() throws Exception {
        HttpResponse<String> response = get("/doc.html");
        assertEquals(200, response.statusCode(),
                "文档页被挡住了；它不在 WebConfig 的受管前缀里，不该要求令牌");
        assertTrue(response.body().toLowerCase().contains("knife4j"),
                "200 但内容不是 knife4j 的页面——很可能是别的静态资源兜住了这个路径");
    }

    @Test
    void openApiDocumentIsServedWithoutAToken() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertEquals(200, response.statusCode(), "OpenAPI 原文需要能匿名取到，否则 UI 自己就是空白页");
        JsonNode document = JsonMapper.builder().build().readTree(response.body());
        assertTrue(document.hasNonNull("openapi"), "响应不是 OpenAPI 文档：" + response.body().substring(
                0, Math.min(200, response.body().length())));
        assertTrue(document.path("paths").size() > 0, "文档里一个路径都没有：springdoc 没扫到控制器");
    }

    @Test
    void everyGroupCarriesItsOwnEndpoints() throws Exception {
        for (String group : GROUPS) {
            HttpResponse<String> response = get("/v3/api-docs/" + group);
            assertEquals(200, response.statusCode(), "分组 " + group + " 取不到");
            JsonNode document = JsonMapper.builder().build().readTree(response.body());
            assertTrue(document.path("paths").size() > 0,
                    "分组 " + group + " 是空的：packages-to-scan 与控制器包名脱节了，"
                            + "UI 里这一组会凭空消失而没有任何报错");
        }
    }

    /**
     * 组名写错要 404，不能 500。
     *
     * <p>springdoc 抛的 {@code OpenApiResourceNotFoundException} 既不是 {@code ApiException}
     * 也不实现 MVC 的 {@code ErrorResponse}，没有专门处理时会落到兜底分支变成
     * 「500 Internal Server Error + 一条 ERROR 栈」——把调用方的拼写错误报成服务端故障。
     */
    @Test
    void unknownGroupIsNotFound() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs/no-such-group");
        assertEquals(404, response.statusCode(),
                "组名不认识应当 404；500 会让「我拼错了」看起来像「服务端挂了」");
        assertTrue(response.body().contains("no-such-group"), "错误信息里应带上写错的组名：" + response.body());
    }

    @Test
    void documentedButStillProtected() throws Exception {
        // 文档公开不等于端公开：同一份配置下两条受管路径必须仍然 401
        assertEquals(401, get("/api/management/v1/catalogs").statusCode(),
                "管理面被文档改动带松了");
        assertEquals(401, get("/v1/config").statusCode(),
                "数据面被文档改动带松了");

        // 而它们确实**在**文档里——「文档里没有」与「文档里有但打不动」是两回事，
        // 前者说明扫描范围错了（页面看起来正常，只是永远少一块）
        String document = get("/v3/api-docs").body();
        assertTrue(document.contains("/api/management/v1/catalogs"),
                "管理面端点没出现在文档里：packages-to-scan 漏了 rest.web");
        assertTrue(document.contains("/v1/{prefix}"), "数据面端点没出现在文档里");
    }
}
