package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.GrantSource;
import io.github.melin.lakehouse.rest.domain.entity.GroupRoleMappingEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalSource;
import io.github.melin.lakehouse.rest.domain.repo.GroupRoleMappingRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.service.GroupRoleSyncService;
import io.github.melin.lakehouse.rest.service.OidcClaimSampleStore;
import io.github.melin.lakehouse.rest.service.OidcLoginService;
import io.github.melin.lakehouse.rest.service.OidcService;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * SSO 登录之后的供给与组同步（{@code docs/sso-catalog-identity-design.md} §9 的 1–10、13–15）。
 *
 * <p>这一组用例针对的是「认证通过之后什么都不会发生」这个缺口：识别得出令牌、
 * 拿得到主体名，但库里没有主体、没有角色，于是每个页面 403。因此断言的重点不是
 * 「登录成功」，而是<b>登录之后库里多出了什么、以及绝不能多出什么</b>。
 *
 * <p><b>不用真实 IdP。</b>要验证的是供给与同步，而它们的输入就是一个
 * {@link OidcService.OidcIdentity}——直接构造它比拉起一个 Keycloak 精确得多，
 * 也能构造出现实中很难复现的输入（{@code groups} 缺失、组名大小写不同、同名本地主体）。
 * 验签本身由 {@code OidcServiceTests} 覆盖，两件事不该混在一个用例里。
 *
 * <p>用独立的 H2 库：主体与授权是全局表，与其它测试类共享内存库会互相污染
 * （尤其是「root 已存在」这条前置条件）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-identity;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class SsoIdentityProvisioningTests {

    private static final String ISSUER = "https://idp.example.com/realms/lakehouse";

    @Autowired
    private PrincipalRepository principals;

    @Autowired
    private PrincipalRoleRepository roles;

    @Autowired
    private PrincipalRoleAssignmentRepository assignments;

    @Autowired
    private GroupRoleMappingRepository mappings;

    @Autowired
    private CatalogEventPublisher events;

    private RestServerProperties properties;

    private GroupRoleSyncService sync;

    private OidcLoginService login;

    /** 取样器本身的行为由 `IdentityProviderApiTests` 覆盖，这里只负责把它传进去。 */
    private final OidcClaimSampleStore claimSamples = new OidcClaimSampleStore();

    @BeforeEach
    void setUp() {
        properties = new RestServerProperties();
        sync = new GroupRoleSyncService(mappings, roles, assignments, principals, events, properties);
        login = new OidcLoginService(principals, sync, claimSamples, events, properties,
                Clock.systemUTC());

        // 每个用例从空表出发。注意引导主体（root）只在 authorization.enabled=true 时才建，
        // 而测试 profile 关着它——所以「同名本地主体」这条用例自己造一个，
        // 否则它会因为「根本没有这个主体」而空过
        assignments.deleteAll();
        mappings.deleteAll();
        principals.deleteAll();
        principals.save(principal("local-admin", PrincipalSource.LOCAL, null));
    }

    // ------------------------------------------------------------------ 1 首次登录

    /**
     * 首次登录：建主体、不建凭据、按组授角色。
     *
     * <p><b>「不建凭据」是这条用例的重点。</b>JIT 顺手生成 clientId + 密钥，会把
     * 一个可登录的人变成一个可被程序冒用的账号——而那个账号从没被任何人批准过。
     */
    @Test
    void firstLoginProvisionsPrincipalWithoutCredentials() {
        role("analyst");
        mapping("data-eng", "analyst");

        OidcLoginService.LoginResult result = login.onLogin(identity("u-1001", "data-eng"));

        assertTrue(result.created(), "首次登录应当建主体");
        PrincipalEntity principal = require("u-1001");
        assertEquals(PrincipalSource.OIDC, principal.getSource());
        assertEquals(ISSUER + "|u-1001", principal.getExternalId());
        assertEquals("u-1001@corp.example.com", principal.getDisplayName());
        assertNotNull(principal.getLastLoginAt(), "最后登录时间要落库，否则「从没登录过」与「很久没登录」分不开");
        assertNull(principal.getClientId(), "JIT 主体不能有 clientId");
        assertNull(principal.getSecretHash(), "JIT 主体不能有密钥");
        assertFalse(principal.isCredentialRotationRequired());

        assertEquals(Set.of("analyst"), idpRolesOf(principal.getId()));
    }

    /** 第二次登录不重复建主体、不重复授权：供给必须是幂等的。 */
    @Test
    void secondLoginIsIdempotent() {
        role("analyst");
        mapping("data-eng", "analyst");

        login.onLogin(identity("u-1002", "data-eng"));
        OidcLoginService.LoginResult second = login.onLogin(identity("u-1002", "data-eng"));

        assertFalse(second.created());
        assertEquals(1, principals.findAll().stream()
                .filter(p -> "u-1002".equals(p.getName())).count());
        assertEquals(Set.of("analyst"), idpRolesOf(require("u-1002").getId()));
        assertEquals(1, assignments.findAll().stream()
                .filter(a -> a.getPrincipalId().equals(require("u-1002").getId())).count());
    }

    /**
     * 并发首登只建一行。
     *
     * <p>「先查后插」在两个线程同时进来时都会查不到，唯一键是数据库已经付过钱的
     * 互斥原语。这里不断言哪个线程赢，只断言<b>两边都不报错、库里只有一行</b>——
     * 报错的那一方会让一个正常用户看到 500，那是比多一行更糟的结果。
     */
    @Test
    void concurrentFirstLoginCreatesASingleRow() throws Exception {
        role("analyst");
        mapping("data-eng", "analyst");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        login.onLogin(identity("u-1003", "data-eng"));
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "两个线程都应当在超时前完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get(), "并发首登不该让任何一方失败");
        assertEquals(1, principals.findAll().stream()
                .filter(p -> "u-1003".equals(p.getName())).count());
    }

    // ------------------------------------------------------------------ 2 同名冲突

    /** 同名本地主体：默认拒绝，绝不静默接管（接管就是提权）。 */
    @Test
    void refusesToAdoptLocalPrincipal() {
        assertNotNull(require("local-admin"), "前置：本地主体存在");
        assertNull(require("local-admin").getExternalId(), "本地主体本来没有外部标识");

        ApiException exception = assertThrows(ApiException.class,
                () -> login.onLogin(identity("local-admin", "data-eng")));

        assertEquals(403, exception.getStatus());
        assertTrue(exception.getMessage().contains("principal_conflict"));
        assertEquals(PrincipalSource.LOCAL, require("local-admin").getSource(), "被拒之后不许改来源");
        assertNull(require("local-admin").getExternalId());
    }

    /** 显式打开开关时才允许接管，并且来源要真的改过来。 */
    @Test
    void adoptsLocalPrincipalWhenExplicitlyAllowed() {
        properties.getAuth().getConsole().getOidc().setAllowAdoptLocal(true);

        login.onLogin(identity("local-admin"));

        PrincipalEntity adopted = require("local-admin");
        assertEquals(PrincipalSource.OIDC, adopted.getSource());
        assertEquals(ISSUER + "|local-admin", adopted.getExternalId());
    }

    /** 同名 OIDC 主体但外部标识不同：拒绝（同一个名字不该被另一个 IdP 的用户继承）。 */
    @Test
    void refusesOidcPrincipalWithDifferentExternalId() {
        PrincipalEntity existing = principal("u-1004", PrincipalSource.OIDC, "https://other|u-1004");
        principals.save(existing);

        ApiException exception = assertThrows(ApiException.class,
                () -> login.onLogin(identity("u-1004")));

        assertEquals(403, exception.getStatus());
        assertEquals("https://other|u-1004", require("u-1004").getExternalId());
    }

    // ------------------------------------------------------------------ 3 组同步

    /**
     * {@code groups} 缺失 + 默认的 {@code ON_LOGIN}：一个角色都不许删。
     *
     * <p>这是整组用例里最贵的一条：默认模式判错，表现是「一次 scope 配置变更后
     * 所有人同时失去权限」，而现场看起来像 IdP 挂了。
     */
    @Test
    void missingGroupsClaimKeepsRoles() {
        role("analyst");
        mapping("data-eng", "analyst");
        login.onLogin(identity("u-1005", "data-eng"));
        String principalId = require("u-1005").getId();

        login.onLogin(identityWithoutGroups("u-1005"));

        assertEquals(Set.of("analyst"), idpRolesOf(principalId), "claim 缺失时不能清空");
    }

    /** {@code STRICT}：把「没带组」当成「不在任何组」，清 IDP 角色、保留手工授权。 */
    @Test
    void strictModeClearsIdpRolesButKeepsManual() {
        properties.getAuth().getConsole().getOidc().setSyncMode(
                RestServerProperties.Auth.Oidc.SyncMode.STRICT);
        role("analyst");
        role("auditor");
        mapping("data-eng", "analyst");
        login.onLogin(identity("u-1006", "data-eng"));
        String principalId = require("u-1006").getId();
        grantManually(principalId, "auditor");

        login.onLogin(identityWithoutGroups("u-1006"));

        assertEquals(Set.of(), idpRolesOf(principalId), "STRICT 下 IDP 角色应当被清空");
        assertEquals(Set.of("auditor"), manualRolesOf(principalId), "手工授权永远不动");
    }

    /** 组被移出：对应的 IDP 角色删除，手工角色不动。 */
    @Test
    void removedGroupRevokesIdpRole() {
        role("analyst");
        role("auditor");
        role("keeper");
        mapping("data-eng", "analyst");
        mapping("audit", "auditor");
        login.onLogin(identity("u-1007", "data-eng", "audit"));
        String principalId = require("u-1007").getId();
        grantManually(principalId, "keeper");

        login.onLogin(identity("u-1007", "data-eng"));

        assertEquals(Set.of("analyst"), idpRolesOf(principalId));
        assertEquals(Set.of("keeper"), manualRolesOf(principalId));
    }

    /**
     * 映射指向不存在的角色：跳过并告警，登录照常成功。
     *
     * <p>这里的映射是直接写进库的（服务层在保存时会拦下不存在的角色），
     * 构造的正是现实里的那个场景：<b>映射建好之后角色被删了</b>。
     * 此时若让登录失败，一个角色的删除会把一批人挡在门外。
     */
    @Test
    void unknownRoleInMappingIsSkipped() {
        GroupRoleMappingEntity orphan = new GroupRoleMappingEntity();
        orphan.setId("m-orphan");
        orphan.setIdpGroup("data-eng");
        orphan.setRoleName("deleted-role");
        orphan.setEnabled(true);
        mappings.save(orphan);

        OidcLoginService.LoginResult result = login.onLogin(identity("u-1008", "data-eng"));

        assertEquals("success", result.outcome());
        assertEquals(Set.of(), idpRolesOf(require("u-1008").getId()));
    }

    /** 一个组映射两个角色：两个都要到位（映射是多对多，取并集）。 */
    @Test
    void oneGroupMapsToTwoRoles() {
        role("analyst");
        role("editor");
        mapping("data-eng", "analyst");
        mapping("data-eng", "editor");

        login.onLogin(identity("u-1009", "data-eng"));

        assertEquals(Set.of("analyst", "editor"), idpRolesOf(require("u-1009").getId()));
    }

    /** 组名精确匹配：大小写不同就是不同组，不 trim、不忽略大小写。 */
    @Test
    void groupMatchingIsCaseSensitive() {
        role("analyst");
        mapping("Data-Eng", "analyst");

        login.onLogin(identity("u-1010", "data-eng"));

        assertEquals(Set.of(), idpRolesOf(require("u-1010").getId()),
                "大小写不同不该命中——静默命中会让「组名配错了」永远查不出来");
    }

    /** 停用的映射不生效，但也不算「没配」（预览里两者的提示不同）。 */
    @Test
    void disabledMappingIsNotApplied() {
        role("analyst");
        GroupRoleMappingEntity disabled = mapping("data-eng", "analyst");
        disabled.setEnabled(false);
        mappings.save(disabled);

        login.onLogin(identity("u-1011", "data-eng"));

        assertEquals(Set.of(), idpRolesOf(require("u-1011").getId()));
        GroupRoleSyncService.Preview preview = sync.preview("default", List.of("data-eng"));
        assertTrue(preview.unmappedGroups().isEmpty(), "停用不等于没配");
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("disabled")));
    }

    // ------------------------------------------------------------------ 4 开关与准入

    /** 关掉 JIT：认证成功但不建主体，结果是「每个页面 403」——老行为，但要是可控的。 */
    @Test
    void autoProvisionDisabledLeavesNoPrincipal() {
        properties.getAuth().getConsole().getOidc().setAutoProvision(false);

        OidcLoginService.LoginResult result = login.onLogin(identity("u-1012"));

        assertEquals("not-provisioned", result.outcome());
        assertTrue(principals.findByName("u-1012").isEmpty());
    }

    /** 邮箱域白名单：域不符拒绝；令牌里没有邮箱也拒绝（fail closed）。 */
    @Test
    void emailDomainRestrictionIsEnforced() {
        properties.getAuth().getConsole().getOidc().setAllowedEmailDomains(List.of("corp.example.com"));

        login.onLogin(identity("u-1013"));  // u-1013@corp.example.com
        assertNotNull(require("u-1013"));

        OidcService.OidcIdentity outsider = new OidcService.OidcIdentity("u-1014", ISSUER, "u-1014",
                "someone@elsewhere.com", "someone@elsewhere.com", List.of(), true, null);
        assertEquals(403, assertThrows(ApiException.class, () -> login.onLogin(outsider)).getStatus());

        OidcService.OidcIdentity noEmail = new OidcService.OidcIdentity("u-1015", ISSUER, "u-1015",
                null, null, List.of(), true, null);
        assertEquals(403, assertThrows(ApiException.class, () -> login.onLogin(noEmail)).getStatus());
    }

    /**
     * 存量升级：新字段的默认值让行为与升级前一致。
     *
     * <p>两个默认值各自挡住一件事：{@code source=LOCAL} 让既有主体不会被
     * 「同名接管」，{@code granted_by=MANUAL} 让既有授权不会被同步删掉。
     */
    @Test
    void newEntitiesDefaultToLocalAndManual() {
        PrincipalEntity fresh = new PrincipalEntity();
        assertEquals(PrincipalSource.LOCAL, fresh.getSource());
        assertEquals(GrantSource.MANUAL, new PrincipalRoleAssignmentEntity().getGrantedBy());
        assertNull(fresh.getExternalId());
        assertNull(fresh.getLastLoginAt());
    }

    // ------------------------------------------------------------------ 工具

    private OidcService.OidcIdentity identity(String name, String... groups) {
        return new OidcService.OidcIdentity(name, ISSUER, name, name + "@corp.example.com",
                name + "@corp.example.com", List.of(groups), true, null);
    }

    private OidcService.OidcIdentity identityWithoutGroups(String name) {
        return new OidcService.OidcIdentity(name, ISSUER, name, name + "@corp.example.com",
                name + "@corp.example.com", List.of(), false, null);
    }

    private PrincipalEntity principal(String name, PrincipalSource source, String externalId) {
        PrincipalEntity entity = new PrincipalEntity();
        entity.setId("p-" + name);
        entity.setName(name);
        entity.setSource(source);
        entity.setExternalId(externalId);
        entity.markCreated("test", System.currentTimeMillis());
        return entity;
    }

    private PrincipalEntity require(String name) {
        return principals.findByName(name).orElseThrow();
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

    private GroupRoleMappingEntity mapping(String group, String roleName) {
        GroupRoleMappingEntity entity = new GroupRoleMappingEntity();
        entity.setId("m-" + group + "-" + roleName);
        entity.setIdpGroup(group);
        entity.setRoleName(roleName);
        entity.setEnabled(true);
        entity.markCreated("test", System.currentTimeMillis());
        return mappings.save(entity);
    }

    private void grantManually(String principalId, String roleName) {
        PrincipalRoleEntity role = role(roleName);
        PrincipalRoleAssignmentEntity assignment = new PrincipalRoleAssignmentEntity();
        assignment.setId("a-manual-" + principalId + "-" + role.getId());
        assignment.setPrincipalId(principalId);
        assignment.setPrincipalRoleId(role.getId());
        assignment.setGrantedBy(GrantSource.MANUAL);
        assignment.markCreated("test", System.currentTimeMillis());
        assignments.save(assignment);
    }

    private Set<String> idpRolesOf(String principalId) {
        return namesOf(assignments.findByPrincipalIdAndGrantedBy(principalId, GrantSource.IDP));
    }

    private Set<String> manualRolesOf(String principalId) {
        return namesOf(assignments.findByPrincipalIdAndGrantedBy(principalId, GrantSource.MANUAL));
    }

    private Set<String> namesOf(List<PrincipalRoleAssignmentEntity> rows) {
        return rows.stream()
                .map(row -> roles.findById(row.getPrincipalRoleId()).map(PrincipalRoleEntity::getName)
                        .orElse("?"))
                .collect(Collectors.toSet());
    }
}
