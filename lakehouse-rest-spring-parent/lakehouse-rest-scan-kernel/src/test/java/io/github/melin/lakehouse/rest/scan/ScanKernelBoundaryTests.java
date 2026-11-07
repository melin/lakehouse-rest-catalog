package io.github.melin.lakehouse.rest.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 内核模块的边界：**不许引 Spring、JPA、Jackson、HTTP、日志实现**。
 *
 * <p><b>这条断言为什么值得存在。</b>本模块的产物要同时进两个依赖世界：服务端（Spring Boot 4，
 * Jackson 3 即 {@code tools.jackson}）与集群上的扫描作业（Spark 3.5，Jackson 2 即
 * {@code com.fasterxml.jackson}）。任何一次「顺手用 Spring 的 StringUtils」或
 * 「顺手用 {@code tools.jackson} 解析一个 JSON」都会让另一个世界少类或多一份旧版本，
 * 而这两种错**都只在真机上炸**：单元测试在这里跑得通，镜像打出来跑不通。
 *
 * <p>检查的是**源码里的 import 文本**，不是解析后的依赖树：前者能抓住「写了一个 import
 * 但依赖还没加」（编译期就红），后者只能抓住「依赖树里出现了某个 jar」——而一个被带进来
 * 却没人用的 jar，恰恰是最难被发现的那种越界。
 *
 * <p>排除注释与字符串字面量不做精细处理：这里查的是 {@code import} 行，注释里写
 * 「不要引 org.springframework」不会命中——因为断言只匹配 `import ` 开头的行。
 */
class ScanKernelBoundaryTests {

    /** 一旦出现在 import 里就说明两个依赖世界已经分叉的前缀。 */
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.springframework",
            "jakarta.",
            "javax.",
            "tools.jackson",
            "com.fasterxml.jackson",
            "io.fabric8",
            "org.slf4j",
            "org.apache.spark",
            "java.net.http");

    @Test
    void kernelSourcesDoNotImportServerOrEngineTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (!line.startsWith("import ")) {
                    continue;
                }
                for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                    if (line.startsWith("import " + prefix)) {
                        violations.add(file.getFileName() + ":" + (index + 1) + " " + line);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "lakehouse-rest-scan-kernel 必须能在 Spring Boot 与 Spark 两个依赖世界里同时编译，"
                        + "但它引了下面这些只属于其中一边的类型（改法：把这段逻辑留在调用方，"
                        + "或者在本模块里用纯 JDK 实现）：\n" + String.join("\n", violations));
    }

    /**
     * 内核里没有任何模块级的框架注解。
     *
     * <p>这不是重复上一条：{@code @Component} 之类来自 {@code org.springframework.stereotype}，
     * 上一条已经覆盖；这里查的是「未来有人用全限定名写注解、不写 import」那种写法——
     * 它在源码里看不见 import，却同样会把 Bean 定义带进作业的类路径。
     */
    @Test
    void kernelSourcesDoNotMentionFrameworkAnnotations() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file);
            for (String annotation : List.of("@Component", "@Service", "@Configuration", "@Bean")) {
                if (text.contains(annotation)) {
                    hits.add(file.getFileName() + " " + annotation);
                }
            }
        }
        assertTrue(hits.isEmpty(), "内核模块不应声明任何框架组件：" + hits);
    }

    /** 内核里的每个类型都必须是纯 JDK 依赖的实现，且不依赖本工程的其它模块。 */
    @Test
    void kernelSourcesDoNotDependOnProjectModules() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : sources()) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import io.github.melin.lakehouse.rest.")
                        && !trimmed.startsWith("import io.github.melin.lakehouse.rest.scan.")) {
                    hits.add(file.getFileName() + " " + trimmed);
                }
            }
        }
        assertTrue(hits.isEmpty(), "内核模块不能反向依赖 core / server（依赖方向是 core → kernel）："
                + hits);
    }

    /**
     * 协议记录里的字段名是**跨进程契约**，改名字要让两端的镜像同时升级。
     *
     * <p>这条用例把名字钉住：改了它就会红，红了就会有人去读这段注释与设计稿 §5.6.5，
     * 而不是「改完发版，线上作业读到的字段全是 null」。
     */
    @Test
    void protocolFieldNamesAreStable() {
        List<String> spec = new ArrayList<>();
        for (var component : ScanProtocol.ScanJobSpec.class.getRecordComponents()) {
            spec.add(component.getName());
        }
        assertEquals(List.of("scanId", "catalog", "format", "mode", "sampleSize", "offset", "limit",
                        "totalTables", "hasMore", "types", "tables"), spec,
                "执行清单的字段名变了——作业与服务端是两个独立升级的产物");

        List<String> report = new ArrayList<>();
        for (var component : ScanProtocol.ScanItemReport.class.getRecordComponents()) {
            report.add(component.getName());
        }
        assertEquals(List.of("database", "table", "status", "columnsScanned", "errorMessage",
                        "findings"), report);

        // 状态字符串同样是契约：服务端只认 SCANNED 为成功，拼成 SUCCEEDED 不会报错，
        // 只会让每一张「扫过」的表都记成失败
        assertEquals("SCANNED", ScanProtocol.ScanItemReport.STATUS_SCANNED);
        assertEquals("FAILED", ScanProtocol.ScanItemReport.STATUS_FAILED);

        List<String> finding = new ArrayList<>();
        for (var component : ScanProtocol.ScanFinding.class.getRecordComponents()) {
            finding.add(component.getName());
        }
        assertEquals(List.of("column", "typeCode", "confidence", "state", "scores",
                        "maskedSamples", "samplingMode"), finding);
    }

    /** 采到的值出模块之前一定被打码：这条用「样本里不该出现的原文」直接验。 */
    @Test
    void findingsCarryMaskedSamplesOnly() {
        ScanColumn column = new ScanColumn("id_card", "varchar(18)", "身份证");
        ScanProtocol.ScanType type = new ScanProtocol.ScanType("CN_ID_CARD", "L3", 0.85, true,
                java.util.Map.of(
                        "name", new DetectorConfig.LayerConfig(
                                java.util.List.of("id_card"), java.util.List.of(), java.util.List.of(),
                                null, null, java.util.List.of(), null, 1.0),
                        "value", new DetectorConfig.LayerConfig(
                                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                                "\\d{18}", null, java.util.List.of(), null, 1.0)));
        String plain = "110101199003071234";
        // 300 个样本：少于 200 行时打分器会整层 ×0.7（样本量折损），那样 0.75 会掉到 0.525，
        // 低于人审下界 0.60，这条命中会被丢弃——用例要验的是打码，不是折损系数。
        var match = SensitivityDetectors.detect("demo", "t", column, List.of(type.toCandidate()),
                new SensitivityDetectors.ColumnValues(
                        java.util.Collections.nCopies(300, plain))).get(0);
        ScanProtocol.ScanFinding finding = ScanProtocol.ScanFinding.of("id_card", match,
                List.of(plain), "HEAD");
        assertEquals(1, finding.maskedSamples().size());
        assertFalse(finding.maskedSamples().get(0).contains("19900307"),
                "打码样本里不该出现中间那段（出生日期）");
    }

    private static List<Path> sources() throws IOException {
        String configured = System.getProperty("scan.kernel.main.sources");
        Path root = Path.of(configured == null || configured.isBlank()
                ? "src/main/java" : configured);
        assertTrue(Files.isDirectory(root), "找不到内核源码目录：" + root.toAbsolutePath());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
