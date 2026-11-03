package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.GrantSource;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalSource;
import io.github.melin.lakehouse.rest.domain.repo.GroupRoleMappingRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 组映射的管理端点（{@code /api/management/v1/group-mappings}）与手动同步。
 *
 * <p>钉住的是四件在别处测不到的事：
 *
 * <ul>
 *   <li><b>重复映射回 409 而不是 500</b>——唯一键冲突穿过到客户端时若变成 500，
 *       管理员会以为服务端坏了，然后重试，然后继续 500；
 *   <li><b>危险映射需要二次确认</b>——把一组人映射到管理员手里的角色，
 *       等价于把「谁能进 IdP 的这个组」变成「谁能管理整个服务」；
 *   <li><b>预览与登录同步用同一份计算</b>——这里断言的预览结果，与
 *       {@code SsoIdentityProvisioningTests} 里实际授到的角色必须一致；
 *   <li><b>手动同步缺参数时拒绝</b>——「按空组重算」是撤权，方向只有一个且不可逆。
 * </ul>
 *
 * <p>测试 profile 关着授权，因此这里不验「非管理员」的 403（那由
 * {@code AuthorizationTests} 一族覆盖），只验端点自身的行为。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-mappings;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GroupMappingApiTests {

    private static final String BASE = "/api/management/v1/group-mappings";
    private static final String PRINCIPALS = "/api/management/v1/principals";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupRoleMappingRepository mappings;

    @Autowired
    private PrincipalRoleRepository roles;

    @Autowired
    private PrincipalRepository principals;

    @Autowired
    private PrincipalRoleAssignmentRepository assignments;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mappings.deleteAll();
        assignments.deleteAll();
        principals.deleteAll();
        roles.deleteAll();

        // 服务管理员的持有关系：默认配置里 service-admins = ["root"]，
        // 于是「root 手里的角色」就是「危险角色」
        PrincipalEntity admin = principals.save(principal("root", PrincipalSource.LOCAL));
        PrincipalRoleEntity serviceAdmin = role("service_admin");
        assign(admin, serviceAdmin, GrantSource.MANUAL);

        role("analyst");
        role("editor");
    }

    /** 建一条映射并读回来：形状与字段都要对得上。 */
    @Test
    void createAndList() throws Exception {
        JsonNode created = call(201, post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"analyst\"}"));

        assertEquals("data-eng", created.get("group").asString());
        assertEquals("analyst", created.get("role").asString());
        assertEquals("default", created.get("provider").asString(), "单 IdP 下 provider 恒为 default");
        assertTrue(created.get("enabled").asBoolean());

        JsonNode list = call(200, get(BASE));
        assertEquals(1, list.get("mappings").size());
        assertEquals("data-eng", list.get("mappings").get(0).get("group").asString());
    }

    /** 重复映射回 409：唯一键是 (provider, group, role)，同组同角色只能有一条。 */
    @Test
    void duplicateMappingIsAConflict() throws Exception {
        String body = "{\"group\":\"data-eng\",\"role\":\"analyst\"}";
        call(201, post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
        call(409, post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 角色不存在时保存就被拦住，而不是留到同步时「跳过并告警」。 */
    @Test
    void unknownRoleIsRejectedOnSave() throws Exception {
        call(404, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"no-such-role\"}"));
    }

    /**
     * 映射到管理员手里的角色：不带确认 400，带了才 201。
     *
     * <p>不直接禁止是刻意的：「全员管理员」在内部小团队里是合理配置，
     * 禁止只会把人逼去手工授权，反而更不可审计。
     */
    @Test
    void adminRoleNeedsExplicitConfirmation() throws Exception {
        call(400, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"platform\",\"role\":\"service_admin\"}"));

        call(201, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"platform\",\"role\":\"service_admin\","
                        + "\"confirmAdminMapping\":true}"));
    }

    /** 预览不写库，且给出「会得到哪些角色 / 哪些组没配」。 */
    @Test
    void previewDoesNotWrite() throws Exception {
        call(201, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"analyst\"}"));

        JsonNode preview = call(200, post(BASE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groups\":[\"data-eng\",\"contractors\"]}"));

        assertEquals(1, preview.get("desiredRoles").size());
        assertEquals("analyst", preview.get("desiredRoles").get(0).asString());
        assertEquals(1, preview.get("unmappedGroups").size());
        assertEquals("contractors", preview.get("unmappedGroups").get(0).asString());
        assertEquals(1, mappings.count(), "预览不能写库");
    }

    /** 改角色：带乐观锁版本号，版本不符回 409。 */
    @Test
    void updateChecksEntityVersion() throws Exception {
        JsonNode created = call(201, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"analyst\"}"));
        String id = created.get("id").asString();
        int version = created.get("entityVersion").asInt();

        JsonNode updated = call(200, put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentEntityVersion\":" + version + ",\"role\":\"editor\","
                        + "\"enabled\":false}"));
        assertEquals("editor", updated.get("role").asString());
        assertFalse(updated.get("enabled").asBoolean());

        call(409, put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentEntityVersion\":" + version + ",\"enabled\":true}"));
    }

    /** 删除只删规则，不动已经同步出去的成员关系（撤权要显式再走一次同步）。 */
    @Test
    void deleteKeepsSyncedMemberships() throws Exception {
        String id = call(201, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"analyst\"}")).get("id").asString();
        PrincipalEntity user = principals.save(principal("u-2001", PrincipalSource.OIDC));
        assign(user, roles.findByName("analyst").orElseThrow(), GrantSource.IDP);

        call(204, delete(BASE + "/" + id));

        assertEquals(0, mappings.count());
        assertEquals(1, assignments.findByPrincipalIdAndGrantedBy(
                user.getId(), GrantSource.IDP).size(), "已授出的角色不因删规则而消失");
    }

    /**
     * 手动同步：给定组名重算角色；缺组名回 400（那会变成撤权）；主体不存在回 404。
     */
    @Test
    void manualSyncNeedsGroups() throws Exception {
        call(201, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"group\":\"data-eng\",\"role\":\"analyst\"}"));
        principals.save(principal("u-2002", PrincipalSource.OIDC));

        call(400, post(PRINCIPALS + "/u-2002/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        JsonNode result = call(200, post(PRINCIPALS + "/u-2002/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groups\":[\"data-eng\"]}"));
        assertEquals(1, result.get("added").size());
        assertEquals("analyst", result.get("added").get(0).asString());

        // 幂等：同样的输入再来一次，不增不删
        JsonNode again = call(200, post(PRINCIPALS + "/u-2002/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groups\":[\"data-eng\"]}"));
        assertEquals(0, again.get("added").size());
        assertEquals(0, again.get("removed").size());

        call(404, post(PRINCIPALS + "/nobody/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groups\":[]}"));
    }

    /** 主体列表按来源过滤：OIDC 与 LOCAL 能分开看。 */
    @Test
    void principalsCanBeFilteredBySource() throws Exception {
        principals.save(principal("u-2003", PrincipalSource.OIDC));

        JsonNode oidc = call(200, get(PRINCIPALS).param("source", "OIDC"));
        assertEquals(1, oidc.get("principals").size());
        assertEquals("u-2003", oidc.get("principals").get(0).get("name").asString());

        JsonNode local = call(200, get(PRINCIPALS).param("source", "LOCAL"));
        assertEquals(1, local.get("principals").size());
        assertEquals("root", local.get("principals").get(0).get("name").asString());

        // 不传即全部：既有调用方不受这次改动影响
        assertEquals(2, call(200, get(PRINCIPALS)).get("principals").size());
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        var result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private PrincipalEntity principal(String name, PrincipalSource source) {
        PrincipalEntity entity = new PrincipalEntity();
        entity.setId("p-" + name);
        entity.setName(name);
        entity.setSource(source);
        entity.markCreated("test", System.currentTimeMillis());
        return entity;
    }

    private PrincipalRoleEntity role(String name) {
        return roles.findByName(name).orElseGet(() -> {
            PrincipalRoleEntity entity = new PrincipalRoleEntity();
            entity.setId("r-" + name);
            entity.setName(name);
            entity.markCreated("test", System.currentTimeMillis());
            return roles.save(entity);
        });
    }

    private void assign(PrincipalEntity principal, PrincipalRoleEntity role, GrantSource source) {
        PrincipalRoleAssignmentEntity assignment = new PrincipalRoleAssignmentEntity();
        assignment.setId("a-" + principal.getId() + "-" + role.getId());
        assignment.setPrincipalId(principal.getId());
        assignment.setPrincipalRoleId(role.getId());
        assignment.setGrantedBy(source);
        assignment.markCreated("test", System.currentTimeMillis());
        assignments.save(assignment);
    }
}
