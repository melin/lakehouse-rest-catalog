package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalSource;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Paging;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * SSO 登录之后的「供给」：把外部身份落成 catalog 里的主体，并按组授角色。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>认证本身早就通了——{@code TokenAuthenticationService} 认三种令牌，OIDC 是其中一种。
 * 但认出来之后什么都不会发生：授权判定按主体名查库，查不到就是零权限，
 * 于是「登录成功、每个页面 403」。本类补的就是这一跳。
 *
 * <h2>三条安全默认</h2>
 *
 * <ol>
 *   <li><b>JIT 建出来的主体没有凭据</b>：{@code clientId} / 密钥留空。
 *       顺手生成密钥会把「一个可登录的人」变成「一个可被程序冒用的账号」，
 *       而那个账号的存在没有任何人批准过。
 *   <li><b>同名本地主体默认不接管</b>：否则 OIDC 用户的 {@code sub} 只要等于
 *       {@code root}，就继承本地 root 的全部授权——这是提权。
 *   <li><b>同名 OIDC 主体但 {@code external_id} 不同一律拒绝</b>：同名的两个人
 *       （换了 IdP、或另一个 issuer 下的同名用户）不该共用一份授权。
 * </ol>
 *
 * <h2>为什么不在事务里</h2>
 *
 * <p>并发首登靠「唯一键冲突后回读」收敛，而<b>捕获冲突要求那次失败的 insert
 * 是自己的事务</b>：在同一个事务里捕获约束冲突之后，持久化上下文已经不可用，
 * 后续查询会以一个与业务无关的异常失败。因此本类不加 {@code @Transactional}，
 * 每次仓储调用各自原子；代价是最坏出现「主体建好了、角色没同步」的中间态，
 * 而它下一次登录就收敛（同步是幂等的）。
 *
 * <p>{@link TokenExchangeService} <b>不调用本类</b>，理由写在那个类的注释里：
 * 交换不是登录，没有组 claim 可用，授权本来就从库里读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OidcLoginService {

    private final PrincipalRepository principals;

    private final GroupRoleSyncService sync;

    private final CatalogEventPublisher events;

    private final RestServerProperties properties;

    private final Clock clock;

    /**
     * 一次 SSO 登录的供给结果。
     *
     * @param principal 主体名（等于令牌里的 principal claim）
     * @param created   这次是否新建了主体
     * @param synced    是否真的执行了组同步
     * @param outcome   {@code provisioned} / {@code existing} / {@code not-provisioned} /
     *                  {@code partial}（同步失败，登录仍然成立）
     */
    public record LoginResult(String principal, boolean created, boolean synced, String outcome) {
    }

    /**
     * 登录时调用：确保主体存在、刷新最后登录时间、按组收敛角色。
     *
     * @throws ApiException 403：同名本地主体不可接管、{@code external_id} 不一致、
     *                      或邮箱域不在白名单内。<b>这三种都拒绝登录</b>——
     *                      放行一个「认证过了但身份不属于这里」的人，后果是他的每次请求
     *                      都零权限，而用户会以为这是权限配置问题
     */
    public LoginResult onLogin(OidcService.OidcIdentity identity) {
        RestServerProperties.Auth.Oidc oidc = properties.getAuth().getConsole().getOidc();
        String name = identity.principal();

        // 这条链路上没有别的认证主体（登录引导端点本身在鉴权之外），
        // 因此把上下文主体设成登录者本人：审计列与事件里的「谁」才是对的
        RequestContext.setPrincipal(name);

        requireAllowedDomain(identity, oidc);

        String externalId = externalId(identity);
        PrincipalEntity principal = principals.findByName(name).orElse(null);
        boolean created = false;
        if (principal == null) {
            if (!oidc.isAutoProvision()) {
                // 关掉 JIT 就回到「装了 OIDC 但没人能进」。这里不抛异常：
                // 认证是成功的，缺的是准入——由调用方决定怎么表达
                publishLogin(identity, "not_provisioned", "auto-provision-off", false);
                return new LoginResult(name, false, false, "not-provisioned");
            }
            principal = provision(name, identity, externalId);
            created = principal.getSource() == PrincipalSource.OIDC;
            if (created) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("issuer", identity.issuer());
                attributes.put("externalId", externalId);
                events.publish(CatalogEventType.PRINCIPAL_PROVISIONED, "",
                        CatalogEvent.TARGET_PRINCIPAL, name, attributes);
            }
        } else {
            admit(principal, identity, externalId, oidc);
        }

        principal.setLastLoginAt(clock.millis());
        principals.save(principal);

        GroupRoleSyncService.SyncResult syncResult;
        try {
            syncResult = sync.sync(principal, identity.groups(), identity.groupsClaimPresent());
        } catch (RuntimeException e) {
            // 同步失败不清空（详见 GroupRoleSyncService），最坏是「旧权限多留一会儿」。
            // 登录照常成功：把一次映射表查询失败升级成「所有人都登不上」是更糟的取舍
            log.warn("failed to sync IdP roles for principal {}: {}", name, e.getMessage(), e);
            publishLogin(identity, "partial", "role-sync-failed", created);
            return new LoginResult(name, created, false, "partial");
        }

        publishLogin(identity, "success", null, created);
        return new LoginResult(name, created, !syncResult.skipped(), "success");
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 建主体。并发首登时靠唯一键收敛，而不是预锁——行还不存在，锁不住。
     */
    private PrincipalEntity provision(String name, OidcService.OidcIdentity identity,
            String externalId) {
        long now = clock.millis();
        PrincipalEntity entity = new PrincipalEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setSource(PrincipalSource.OIDC);
        entity.setExternalId(externalId);
        entity.setDisplayName(identity.displayName());
        entity.setLastLoginAt(now);
        // 人不是机器：JIT 主体没有凭据，也不要求轮换
        entity.setCredentialRotationRequired(false);
        // owner / created_by 记主体自己：这条行是它首次登录时自己产生的，
        // 而此刻的请求上下文里没有别的身份
        entity.markCreated(name, now);
        try {
            return principals.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // 另一个线程先建了同一个名字：回读，然后走与「已存在」完全相同的判定。
            // 不能因为「是并发」就放行——并发撞上的可能是一个本地主体
            PrincipalEntity existing = principals.findByName(name)
                    .orElseThrow(() -> e);
            log.debug("principal {} was created concurrently; reusing the existing row", name);
            admit(existing, identity, externalId, properties.getAuth().getConsole().getOidc());
            return existing;
        }
    }

    /** 已存在的主体能否承载这个外部身份。不能则拒绝登录。 */
    private void admit(PrincipalEntity principal, OidcService.OidcIdentity identity,
            String externalId, RestServerProperties.Auth.Oidc oidc) {
        if (principal.getSource() == PrincipalSource.LOCAL) {
            if (!oidc.isAllowAdoptLocal()) {
                log.warn("refused OIDC login: principal {} already exists as a local principal",
                        principal.getName());
                throw conflict();
            }
            // 显式打开开关才允许接管；把它做成一次可见的操作（日志 + 改 source），
            // 而不是每次登录都重新判断一遍
            log.warn("local principal {} is now bound to OIDC identity {} (allow-adopt-local=true)",
                    principal.getName(), externalId);
            principal.setSource(PrincipalSource.OIDC);
            principal.setExternalId(externalId);
            principal.setDisplayName(identity.displayName());
            return;
        }
        if (!Objects.equals(principal.getExternalId(), externalId)) {
            log.warn("refused OIDC login: principal {} is bound to a different external identity",
                    principal.getName());
            throw conflict();
        }
    }

    /**
     * 邮箱域白名单。
     *
     * <p>没配就不判。<b>配了而令牌里没有邮箱，则拒绝</b>——白名单的意义就是「只让这些人进」，
     * 缺字段时放行等于白名单形同虚设。
     */
    private void requireAllowedDomain(OidcService.OidcIdentity identity,
            RestServerProperties.Auth.Oidc oidc) {
        List<String> allowed = oidc.getAllowedEmailDomains();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        String email = identity.email();
        String domain = domainOf(email);
        boolean matched = domain != null && allowed.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(domain::equals);
        if (!matched) {
            log.warn("refused OIDC login for {}: email domain is not in the allowed list",
                    identity.principal());
            // 不回显邮箱域：那等于把白名单内容告诉任何一个能登录 IdP 的人
            throw new ApiException(403, null, null,
                    "the identity's email domain is not allowed (email_domain_not_allowed)");
        }
    }

    private void publishLogin(OidcService.OidcIdentity identity, String result, String reason,
            boolean jitCreated) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("source", "oidc");
        attributes.put("issuer", identity.issuer());
        attributes.put("result", result);
        attributes.put("jitCreated", jitCreated);
        attributes.put("groupsCount", identity.groups() == null ? 0 : identity.groups().size());
        if (reason != null) {
            attributes.put("reason", reason);
        }
        events.publish(CatalogEventType.PRINCIPAL_LOGIN, "",
                CatalogEvent.TARGET_PRINCIPAL, identity.principal(), attributes);
    }

    private static String externalId(OidcService.OidcIdentity identity) {
        String subject = identity.subject() == null ? identity.principal() : identity.subject();
        return identity.issuer() + "|" + subject;
    }

    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return null;
        }
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static ApiException conflict() {
        // 不透露「这个名字已经存在」以外的任何信息，也不区分「本地主体」与「另一个外部身份」：
        // 对调用方来说下一步动作是一样的（找管理员），而对攻击者来说少一条枚举线索
        return new ApiException(403, null, null,
                "this identity cannot be bound to a principal; contact an administrator"
                        + " (principal_conflict)");
    }
}
