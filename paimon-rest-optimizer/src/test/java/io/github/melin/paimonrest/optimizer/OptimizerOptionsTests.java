package io.github.melin.paimonrest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 启动参数的解析与 catalog 地址的推导。
 *
 * <p>参数由服务端生成的 k8s Job 定义决定，两边一旦不一致，症状是「容器起来就退出」
 * 而错误信息出现在集群里——最不划算的排查路径。因此这里把三个必需参数、三种写法
 * （{@code --k v} / {@code --k=v} / 位置参数）以及地址推导都钉住。
 */
class OptimizerOptionsTests {

    @Test
    void parsesSpaceSeparatedForm() {
        OptimizerOptions options = OptimizerOptions.parse(new String[]{
                "--job-id", "abc", "--management-url", "http://rest:8080/api/management/v1", "--token", "tk"});

        assertEquals("abc", options.jobId());
        assertEquals("http://rest:8080/api/management/v1", options.managementUrl());
        assertEquals("tk", options.callbackToken());
        // catalog 地址由管理地址去掉固定后缀推出：两者挂在同一服务端上，只传一个地址
        assertEquals("http://rest:8080", options.catalogUrl());
        assertNull(options.catalogToken());
    }

    @Test
    void parsesEqualsFormAndTrailingSlash() {
        OptimizerOptions options = OptimizerOptions.parse(new String[]{
                "--job-id=abc",
                "--management-url=http://rest:8080/api/management/v1/",
                "--token=tk",
                "--catalog-token=ct"});

        assertEquals("abc", options.jobId());
        assertEquals("http://rest:8080/api/management/v1", options.managementUrl());
        assertEquals("http://rest:8080", options.catalogUrl());
        assertEquals("ct", options.catalogToken());
    }

    @Test
    void catalogUrlCanBeOverridden() {
        // 网关改写过管理 API 前缀时推导会失败，此时用显式地址
        OptimizerOptions options = OptimizerOptions.parse(new String[]{
                "--job-id", "abc", "--management-url", "http://gw/rest/api/management/v1",
                "--token", "tk", "--catalog-url", "http://rest:8080/"});
        assertEquals("http://rest:8080", options.catalogUrl());
    }

    @Test
    void catalogUrlFallsBackToManagementUrlWhenSuffixMissing() {
        OptimizerOptions options = OptimizerOptions.parse(new String[]{
                "--job-id", "abc", "--management-url", "http://rest:8080", "--token", "tk"});
        assertEquals("http://rest:8080", options.catalogUrl());
    }

    @Test
    void missingRequiredArgumentExplainsWhoPassesIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptimizerOptions.parse(new String[]{"--job-id", "abc", "--token", "tk"}));
        assertTrue(error.getMessage().contains("--management-url"), error.getMessage());
        // 看到这条错误的人通常是在改 k8s Job 定义，提示要指向那里
        assertTrue(error.getMessage().contains("KubernetesJobSubmitter"), error.getMessage());
    }

    @Test
    void danglingFlagIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OptimizerOptions.parse(new String[]{"--job-id", "abc", "--token"}));
    }

    @Test
    void blankCatalogTokenIsTreatedAsAbsent() {
        // 服务端在没配令牌时干脆不传这个参数；这里也认空串，两种部署形态行为一致
        assertNull(OptimizerOptions.parse(new String[]{"--job-id", "a", "--management-url", "http://x",
                "--token", "t", "--catalog-token", "  "}).catalogToken());
    }
}
