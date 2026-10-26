package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Context;
import io.github.melin.lakehouse.rest.service.exec.KubeconfigParser;
import io.github.melin.lakehouse.rest.service.exec.KubeconfigParser.Parsed;
import io.github.melin.lakehouse.rest.support.ApiException;
import org.junit.jupiter.api.Test;

/**
 * kubeconfig 解析的判定表：**每个 context 给一个结论**，而不是「解析成功 / 失败」。
 *
 * <p>这些用例钉的是判定边界的顺序与文案里那个「接下来该做什么」，因为解析结果直接渲染成
 * 界面上的一行结论：
 *
 * <ul>
 *   <li>引用不存在的 cluster / user <b>先于</b>认证方式判定——反过来会把「拼错了名字」
 *       报成「匿名访问」，而那两句处置完全不同；</li>
 *   <li>{@code exec:} 与旧式 {@code auth-provider:} 是**硬拒**：服务端照做等于执行任意命令。
 *       文案必须给出替代路径（切到对应的云类型），只说「不支持」用户没有下一步；</li>
 *   <li>指向文件的证书 / 令牌（{@code client-certificate}、{@code tokenFile}）不可用，
 *       因为那是**调用方机器上**的路径；</li>
 *   <li>{@code insecure-skip-tls-verify} 可用但降级为警告——它关掉的是中间人防护。</li>
 * </ul>
 *
 * <p>纯函数、不连网、不读文件：因此本类是普通单元测试，不需要 Spring 上下文。
 */
class KubeconfigParserTests {

    private static final String STATIC_CERT = """
            apiVersion: v1
            kind: Config
            current-context: dev
            clusters:
            - name: dev
              cluster:
                server: https://10.20.30.41:6443
                certificate-authority-data: LS0tCg==
            users:
            - name: dev-admin
              user:
                client-certificate-data: LS0tCg==
                client-key-data: LS0tCg==
            contexts:
            - name: dev
              context:
                cluster: dev
                user: dev-admin
            """;

    @Test
    void staticClientCertificateIsUsable() {
        Parsed parsed = KubeconfigParser.parse(STATIC_CERT);

        assertEquals("dev", parsed.currentContext());
        assertEquals(1, parsed.contexts().size());
        Context context = parsed.contexts().get(0);
        assertEquals("OK", context.verdict());
        assertEquals("https://10.20.30.41:6443", context.server());
        assertEquals("client-certificate-data", context.auth());
        assertEquals(java.util.List.of("dev"), parsed.usableNames());
        assertTrue(KubeconfigParser.isUsable(context.verdict()));
    }

    @Test
    void execIsRejectedWithAnAlternativePath() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: eks
                  cluster:
                    server: https://abc.eks.amazonaws.com
                users:
                - name: eks-user
                  user:
                    exec:
                      command: aws
                      args: ["eks", "get-token"]
                contexts:
                - name: eks
                  context:
                    cluster: eks
                    user: eks-user
                """);

        Context context = parsed.contexts().get(0);
        assertEquals("UNSUPPORTED", context.verdict(), "exec: 是 RCE 边界，必须硬拒");
        assertEquals("exec: aws eks", context.auth());
        // 只说「不支持」用户没有下一步：文案里必须有替代路径
        assertTrue(context.note().contains("AWS EKS"), "处置要指向「AWS EKS · 令牌」：" + context.note());
        assertTrue(parsed.usableNames().isEmpty());
    }

    @Test
    void legacyAuthProviderIsRejected() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: aks
                  cluster:
                    server: https://aks.example.com:443
                users:
                - name: aks-user
                  user:
                    auth-provider:
                      name: azure
                contexts:
                - name: aks
                  context:
                    cluster: aks
                    user: aks-user
                """);

        Context context = parsed.contexts().get(0);
        assertEquals("UNSUPPORTED", context.verdict());
        assertEquals("auth-provider: azure", context.auth());
        assertTrue(context.note().contains("1.26"), "要说清它为什么不能用（版本移除）：" + context.note());
    }

    /** 引用了不存在的 user：先报「文件自身不完整」，而不是把它当成匿名访问。 */
    @Test
    void danglingUserIsReportedBeforeTheAuthMode() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: dev
                  cluster:
                    server: https://10.0.0.1:6443
                users: []
                contexts:
                - name: dev
                  context:
                    cluster: dev
                    user: typo-admin
                """);

        Context context = parsed.contexts().get(0);
        assertEquals("UNAVAILABLE", context.verdict());
        assertTrue(context.note().contains("typo-admin"), "要点名那个名字：" + context.note());
    }

    @Test
    void missingClusterIsUnavailable() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                contexts:
                - name: dev
                  context:
                    cluster: nope
                """);

        assertEquals("UNAVAILABLE", parsed.contexts().get(0).verdict());
        assertNull(parsed.contexts().get(0).server(), "cluster 都找不到，就不该有 server");
    }

    /** 文件路径形式的证书不可用：那是**调用方机器**上的文件，服务端读不到。 */
    @Test
    void certificatePathsAreUnsupported() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: dev
                  cluster:
                    server: https://10.0.0.1:6443
                users:
                - name: admin
                  user:
                    client-certificate: /home/me/.kube/dev.crt
                    client-key: /home/me/.kube/dev.key
                contexts:
                - name: dev
                  context:
                    cluster: dev
                    user: admin
                """);

        assertEquals("UNSUPPORTED", parsed.contexts().get(0).verdict());
    }

    /** CA 指向路径只记警告（不打开它），context 本身仍可用。 */
    @Test
    void certificateAuthorityPathOnlyWarns() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: dev
                  cluster:
                    server: https://10.0.0.1:6443
                    certificate-authority: /etc/kubernetes/ca.crt
                users:
                - name: admin
                  user:
                    token: abc123
                contexts:
                - name: dev
                  context:
                    cluster: dev
                    user: admin
                """);

        assertEquals("OK", parsed.contexts().get(0).verdict(), "CA 路径不影响「能不能连」的判定");
        assertEquals("token", parsed.contexts().get(0).auth());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("certificate-authority")),
                "要提示那是调用方机器上的路径：" + parsed.warnings());
    }

    @Test
    void insecureSkipTlsVerifyDowngradesToWarning() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: dev
                  cluster:
                    server: https://10.0.0.1:6443
                    insecure-skip-tls-verify: true
                users:
                - name: admin
                  user:
                    token: abc123
                contexts:
                - name: dev
                  context:
                    cluster: dev
                    user: admin
                """);

        assertEquals("WARN", parsed.contexts().get(0).verdict());
        assertTrue(KubeconfigParser.isUsable("WARN"), "警告讲的是风险，不是「用不了」");
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("insecure-skip-tls-verify")));
    }

    @Test
    void userWithoutAnyCredentialIsUnavailable() {
        Parsed parsed = KubeconfigParser.parse("""
                apiVersion: v1
                kind: Config
                clusters:
                - name: dev
                  cluster:
                    server: https://10.0.0.1:6443
                users:
                - name: admin
                  user:
                    irrelevant: true
                contexts:
                - name: dev
                  context:
                    cluster: dev
                    user: admin
                """);

        assertEquals("UNAVAILABLE", parsed.contexts().get(0).verdict());
        assertTrue(parsed.contexts().get(0).note().contains("没有任何认证"));
    }

    @Test
    void currentContextPointingNowhereIsAWarningNotAFailure() {
        Parsed parsed = KubeconfigParser.parse(STATIC_CERT.replace("current-context: dev", "current-context: gone"));

        assertEquals("gone", parsed.currentContext());
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("current-context")),
                "文件里写着指向一个不存在的 context，要说出来（否则界面上「默认选中」会静默落空）");
        assertFalse(parsed.usableNames().isEmpty(), "这不影响其它 context 的可用性");
    }

    // ------------------------------------------------------------------ 拒绝整份输入

    @Test
    void rejectsEmptyAndOversizedInput() {
        assertEquals("kubeconfig 内容为空",
                assertThrows(ApiException.class, () -> KubeconfigParser.parse("   ")).getMessage());

        String huge = "a: " + "x".repeat(KubeconfigParser.MAX_BYTES);
        ApiException tooLarge = assertThrows(ApiException.class, () -> KubeconfigParser.parse(huge));
        assertTrue(tooLarge.getMessage().contains("64 KiB"), tooLarge.getMessage());
    }

    @Test
    void rejectsMalformedYamlAndNonKubeconfig() {
        assertThrows(ApiException.class, () -> KubeconfigParser.parse("clusters: [unclosed"));

        ApiException noContexts = assertThrows(ApiException.class,
                () -> KubeconfigParser.parse("apiVersion: v1\nkind: Config\n"));
        assertTrue(noContexts.getMessage().contains("contexts"), noContexts.getMessage());

        // 顶层不是映射（例如一份列表）也要拒：否则后面每一处都读不到东西，却像是「解析成功」
        assertThrows(ApiException.class, () -> KubeconfigParser.parse("- a\n- b\n"));
    }
}
