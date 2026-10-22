package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.support.AuditPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 审计列不被超长主体名撑破。
 *
 * <p>这一组只钉**两条写入路径**的边界，业务逻辑不在范围内：
 * <ol>
 *   <li>实体写入（{@code save}）——任何调用方、包括直接用 Lombok setter 的，
 *       都要在 {@code @PrePersist} / {@code @PreUpdate} 上被拦下来；</li>
 *   <li>批量更新（JPQL {@code update ...}）——不触发实体回调，只能靠调用方传
 *       {@link RequestContext#auditPrincipal()}。</li>
 * </ol>
 *
 * <p>为什么单独一个测试类而不是并进 {@code AuditPrincipalTests}：那一个测的是
 * {@link AuditPrincipal} 自己的归一化规则（摘要、幂等），这里测的是
 * 「有没有被用上」——规则再对，漏掉一个调用点照样是
 * {@code Data too long for column 'updated_by'}。两者会各自独立地坏。
 */
class AuditColumnGuardTests {

    /** 比审计列宽（255）长的一段「主体名」：令牌被当成主体名时的量级。 */
    private static final String LONG_PRINCIPAL = "t".repeat(272);

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void auditPrincipalFitsTheAuditColumnAndKeepsTheRawPrincipal() {
        RequestContext.setPrincipal(LONG_PRINCIPAL);
        String value = RequestContext.auditPrincipal();
        assertTrue(value.length() <= AuditPrincipal.MAX_LENGTH,
                "批量更新拿到的主体名必须放得进 varchar(255)，实际 " + value.length());
        assertTrue(value.startsWith(AuditPrincipal.DIGEST_PREFIX),
                "超长的应记摘要而不是截断，实际：" + value);

        // 原始主体名不受影响：授权判定读的仍是它
        assertEquals(LONG_PRINCIPAL, RequestContext.principal());
    }

    @Test
    void auditPrincipalFallsBackToAnonymous() {
        RequestContext.clear();
        assertEquals(AuditPrincipal.ANONYMOUS, RequestContext.auditPrincipal());
    }

    @Test
    void directSetterIsStillNormalizedByTheLifecycleCallback() {
        TableEntity table = new TableEntity();
        table.setId("t-1");
        table.setUpdatedBy(LONG_PRINCIPAL);
        table.setCreatedBy(LONG_PRINCIPAL);
        table.setOwner(LONG_PRINCIPAL);

        // Hibernate 在 persist / update 前调用的就是它
        table.normalizeAuditColumns();

        for (String value : new String[] {table.getUpdatedBy(), table.getCreatedBy(), table.getOwner()}) {
            assertTrue(value.length() <= AuditPrincipal.MAX_LENGTH,
                    "审计列写入前必须已归一化，实际 " + value.length());
            assertTrue(value.startsWith(AuditPrincipal.DIGEST_PREFIX), "超长主体名应记摘要，实际：" + value);
        }
    }

    @Test
    void normalizationLeavesNullAlone() {
        TableEntity table = new TableEntity();
        table.normalizeAuditColumns();
        assertNull(table.getOwner(), "「没有记录」不能被说成「anonymous 干的」");
        assertNull(table.getCreatedBy());
        assertNull(table.getUpdatedBy());
    }

    @Test
    void normalizationIsIdempotent() {
        TableEntity table = new TableEntity();
        table.setUpdatedBy(LONG_PRINCIPAL);
        table.normalizeAuditColumns();
        String once = table.getUpdatedBy();
        table.normalizeAuditColumns();
        assertEquals(once, table.getUpdatedBy());
    }
}
