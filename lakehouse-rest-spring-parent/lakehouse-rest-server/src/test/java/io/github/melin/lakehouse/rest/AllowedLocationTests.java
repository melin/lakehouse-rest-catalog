package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code allowedLocations} 白名单判定的纯单元测试，不起 Spring 上下文。
 *
 * <p>要钉住的不是「字符串包含」——那是 {@code startsWith} 的事——而是四条只在边界上
 * 才看得出来的约定：段边界（{@code s3://b/ab} 不属于 {@code s3://b/a}）、
 * scheme 与 host 大小写不敏感而 path 敏感、空白名单等于不限制、
 * 以及报错里必须同时出现被拒位置与整份白名单。
 *
 * <p>这些每一条错了都不会抛异常：白名单变松的唯一表现是「越界建表成功了」，
 * 而那一刻没有任何日志、没有用例变红——直到有人发现某个 catalog 里躺着一张
 * 位于别的桶里的表。改造前 Lance 那一份实现（裸 {@code startsWith}）正是这样。
 */
class AllowedLocationTests {

    private static final String WAREHOUSE = "s3://analytics/warehouse";

    @Test
    void acceptsThePrefixItselfAndAnythingUnderIt() {
        assertTrue(Paths.isWithin(WAREHOUSE, List.of(WAREHOUSE)));
        assertTrue(Paths.isWithin(WAREHOUSE + "/db.db/t", List.of(WAREHOUSE)));
        assertTrue(Paths.isWithin(WAREHOUSE + "/db.db/t.lance", List.of(WAREHOUSE)));
    }

    @Test
    void rejectsASiblingThatOnlySharesACharacterPrefix() {
        // 这两条是 Lance 改造前的真实漏判：裸 startsWith 会把它们判成「在范围内」，
        // 于是「允许写哪几个前缀」这句话就变成了一句空话。
        assertFalse(Paths.isWithin("s3://analytics/warehouse2/t", List.of(WAREHOUSE)));
        assertFalse(Paths.isWithin("s3://analytics/warehouse-old/t", List.of(WAREHOUSE)));
        // 连分隔符都没到的那一段更不该命中
        assertFalse(Paths.isWithin("s3://analytics/warehous", List.of(WAREHOUSE)));
    }

    @Test
    void ignoresTrailingSlashesOnEitherSide() {
        // 表单里很常见的写法：白名单带尾斜杠，位置不带（或反过来）
        assertTrue(Paths.isWithin(WAREHOUSE + "/db.db/t", List.of(WAREHOUSE + "/")));
        assertTrue(Paths.isWithin(WAREHOUSE + "/", List.of(WAREHOUSE)));
        assertTrue(Paths.isWithin(WAREHOUSE + "//", List.of(WAREHOUSE + "/")));
    }

    @Test
    void lowercasesSchemeAndAuthorityButNotThePath() {
        assertTrue(Paths.isWithin("S3://Analytics/warehouse/db.db/t", List.of(WAREHOUSE)));
        // path 不能跟着小写：对象存储的 key 区分大小写，把 Warehouse 与 warehouse
        // 混为一谈会让一条真正越界的位置被判成合法。
        assertFalse(Paths.isWithin("s3://analytics/Warehouse/db.db/t", List.of(WAREHOUSE)));
    }

    @Test
    void keepsTheContainerNameInAnAzureAuthority() {
        String allowed = "abfss://container@account.dfs.core.windows.net/wh";
        assertTrue(Paths.isWithin(
                "abfss://container@Account.dfs.core.windows.net/wh/t", List.of(allowed)));
        // @ 之前是容器，不是 host，整段小写会把它也改掉
        assertFalse(Paths.isWithin(
                "abfss://Container@account.dfs.core.windows.net/wh/t", List.of(allowed)));
    }

    @Test
    void treatsAnEmptyWhitelistAsNoRestriction() {
        // 与 catalog 创建时的语义一致：FILE / HDFS 允许没有 allowedLocations。
        // 这条若改成「空即全拒」，存量 catalog 的建表会全部失败。
        assertTrue(Paths.isWithin("s3://anywhere/x", List.of()));
        assertTrue(Paths.isWithin("s3://anywhere/x", null));
    }

    @Test
    void ignoresAMissingLocation() {
        // 调用方没给位置时由目录按模板分配，校验留给分配之后那一步
        assertTrue(Paths.isWithin(null, List.of(WAREHOUSE)));
        assertTrue(Paths.isWithin("   ", List.of(WAREHOUSE)));
    }

    @Test
    void matchesAnyEntryOfTheList() {
        List<String> allowed = List.of("s3://staging/wh", WAREHOUSE, "s3://cold/wh");
        assertTrue(Paths.isWithin(WAREHOUSE + "/t", allowed));
        assertTrue(Paths.isWithin("s3://cold/wh/t", allowed));
        assertFalse(Paths.isWithin("s3://warm/wh/t", allowed));
    }

    @Test
    void handlesFileUrisWithAnEmptyAuthority() {
        assertTrue(Paths.isWithin("file:///tmp/wh/db.db/t", List.of("file:///tmp/wh")));
        assertFalse(Paths.isWithin("file:///tmp/wh2/t", List.of("file:///tmp/wh")));
    }

    @Test
    void doesNotAliasHadoopSchemes() {
        // s3a / s3n 与 s3 在上游是同一种存储，但 CredentialScope 只认字面的
        // scheme://authority/path。放行 s3a:// 会得到「校验通过、取票后 403」，
        // 那比一眼拒绝难查得多。理由见 Paths 的类注释。
        assertFalse(Paths.isWithin("s3a://analytics/warehouse/t", List.of(WAREHOUSE)));
    }

    @Test
    void aBucketRootPrefixCoversEveryKeyInTheBucket() {
        assertTrue(Paths.isWithin("s3://analytics/a/b", List.of("s3://analytics")));
        // 桶名只差一个字符时仍然不能命中——段边界对 authority 同样生效
        assertFalse(Paths.isWithin("s3://analytics2/a/b", List.of("s3://analytics")));
    }

    @Test
    void aSchemeOnlyWhitelistEntryDoesNotMatchEverything() {
        // 归一化只削 path 的尾斜杠，不削整串。削整串会把 "s3://" 削成 "s3:"，
        // 那条畸形白名单项随后会以 "s3://" 的形式命中**一切** s3 位置——
        // 一条明显的配置笔误因此变成一次静默放行。
        assertFalse(Paths.isWithin(WAREHOUSE, List.of("s3://")));
    }

    @Test
    void requireWithinAnswers400AndNamesBothSides() {
        ApiException error = assertThrows(ApiException.class,
                () -> Paths.requireWithin("s3://other/x", List.of(WAREHOUSE)));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("s3://other/x"), error.getMessage());
        assertTrue(error.getMessage().contains(WAREHOUSE), error.getMessage());
    }

    @Test
    void requireWithinIsSilentWhenTheLocationIsAllowed() {
        Paths.requireWithin(WAREHOUSE + "/t", List.of(WAREHOUSE));
        Paths.requireWithin(null, List.of(WAREHOUSE));
        Paths.requireWithin("s3://anywhere/x", List.of());
    }
}
