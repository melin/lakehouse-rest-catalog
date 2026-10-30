package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.ThrottledException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.options.CatalogOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 保存前自检：**用调用方给的凭据、对调用方给的地址，真的敲一次存储**（设计稿 §5）。
 *
 * <p>它回答的是三条准入线里的前两条：服务端能不能读、服务端能不能写。第三条（引擎读写）
 * 在这里验不了——那是另一个进程，用的可能是另一种身份，{@code vend} 只能证明「票发出去了」。
 *
 * <p><b>这也是本工程唯一一个「会按用户指令往外发请求」的端点，因此是 SSRF 的形状。</b>
 * 五条边界写在 {@code docs/catalog-storage-auth-design.md} §5.5，落到代码里是：
 * <ul>
 *   <li>{@code file://} 一律不探测（对 {@code file:///etc/passwd} 做 HEAD 就是
 *       「本机文件存在性」预言机），硬编码白名单，不是配置项；</li>
 *   <li>只探 {@code allowedLocations} 里的位置，请求里另给的一律忽略；</li>
 *   <li>读档只 {@code getFileStatus}（HEAD），不 {@code listStatus}、不读内容；</li>
 *   <li>写档只写 **0 字节**、只写 {@code _lakehouse_probe/} 前缀、写完立刻删；</li>
 *   <li>写档必须显式勾选；只回五档分类，**不回云侧错误原文**（原文常带 endpoint、
 *       账号 ID、内网域名）。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code StorageFileIoFactory#optionsFor}。</b>那个方法的入参是
 * {@code CatalogEntity}，而自检的对象是**还没保存的草稿**：它可能连 catalog 都还没有。
 * 这里直接调凭据管理器取票，再按同一套规则补上 {@code warehouse}——
 * 与 {@code optionsFor} 走的是同一个 {@code vend}，因此「自检能过、保存后读不了」
 * 不会是两份实现漂移出来的。
 */
@Service
@RequiredArgsConstructor
public class ConsoleStorageProbeService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleStorageProbeService.class);

    /** 写档的固定前缀：桶策略只要放行它就能做「最小写权限」试跑，运维也容易清。 */
    static final String PROBE_PREFIX = "_lakehouse_probe";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private static final String OK = "OK";
    private static final String DENIED = "DENIED";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String UNREACHABLE = "UNREACHABLE";
    private static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * 「我们这边没准备好」：凭据装配失败、配置自相矛盾、加解密失败。
     *
     * <p><b>必须与 {@link #UNSUPPORTED} 分开。</b>两者的现象都是「没探成」，但指向的方向
     * 完全相反：`UNSUPPORTED` 是「这个部署没有这种 scheme 的 FileIO」（去查
     * {@code lakehouse.rest.file-io.type} 与插件），`CONFIG_ERROR` 是「凭据没装配起来」
     * （去查配置本身）。合并成一档时，最常发生的那一种——编辑态提交体里带着打码的
     * {@code ******}——会被报成「这个存储不支持探测」，而它与 scheme 毫无关系。
     */
    private static final String CONFIG_ERROR = "CONFIG_ERROR";

    /** 判定取最坏的那个，顺序即严重程度（值越大越坏）。 */
    private static final List<String> SEVERITY =
            List.of(OK, UNSUPPORTED, CONFIG_ERROR, UNREACHABLE, NOT_FOUND, DENIED);

    private final ManagementCatalogService catalogs;
    private final StorageRuntimePolicy storagePolicy;
    private final StorageFileIoFactory fileIoFactory;
    private final CredentialCipher cipher;
    private final RestServerProperties properties;
    private final AuthorizationService authorization;

    /** 每主体一分钟的探测窗口。失败也计数——否则它就是一个免费的凭据试错器。 */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    /**
     * 探一次。
     *
     * @throws ApiException 400 草稿缺失 / 位置为空 / 出现 {@code file:}；403 不是服务管理员；
     *                      429 触发限频
     */
    public ConsoleDtos.StorageProbeResult probe(ConsoleDtos.StorageProbeRequest request) {
        // 自检能让服务端拿着调用方给的钥匙发请求，因此要求与「建 catalog」同一道门：
        // 服务管理员。细粒度判定（CATALOG:CREATE / ALTER）在控制台这一层没有模型可用。
        authorization.requireServiceAdmin("probe whether the server can reach a storage location");
        if (request == null || request.storageConfigInfo() == null) {
            throw ApiException.badRequest("storageConfigInfo is required: the probe runs against the"
                    + " exact configuration the form would save");
        }
        StorageConfigInfo draft = request.storageConfigInfo();
        requireMergeableDraft(draft, request.catalog());
        if (draft.storageType() == StorageType.FILE) {
            // 本地仓库没有「认证」这回事，两档都不探（设计稿 §5.5 第 1 条）
            return new ConsoleDtos.StorageProbeResult(UNSUPPORTED, false, null, List.of(),
                    null, null, "本地文件系统不需要认证，因此不探测：对 file:// 做 HEAD 等于"
                    + "把服务端变成「本机文件存在性」的预言机");
        }
        checkRate();

        StorageConfigInfo merged = mergeWithStored(draft, request.catalog());
        StorageConfigInfo normalized = StorageConfigs.normalize(merged, request.warehouse());
        List<String> targets = normalized.allowedLocations();
        if (targets == null || targets.isEmpty()) {
            throw ApiException.badRequest("storageConfigInfo.allowedLocations is empty: nothing to probe."
                    + " Fill a location (or the warehouse) first");
        }
        requireNoLocalPaths(targets);

        List<ConsoleDtos.StorageProbeCheck> checks = new ArrayList<>();
        boolean readable = true;
        for (String location : targets) {
            ConsoleDtos.StorageProbeCheck check = readCheck(normalized, location, request.warehouse());
            checks.add(check);
            readable &= OK.equals(check.verdict());
        }

        String probeKey = null;
        Boolean cleanup = null;
        Boolean writable = null;
        if (request.checkWrite() && readable) {
            WriteResult write = writeCheck(normalized, targets.get(0), request.warehouse());
            probeKey = write.key();
            cleanup = write.cleanup();
            writable = OK.equals(write.verdict());
            checks.add(new ConsoleDtos.StorageProbeCheck(targets.get(0), write.verdict(), write.detail()));
        } else if (request.checkWrite()) {
            writable = Boolean.FALSE;
        }

        String verdict = worst(checks);
        return new ConsoleDtos.StorageProbeResult(verdict, readable, writable, checks,
                probeKey, cleanup, hint(verdict, readable, writable, request.checkWrite()));
    }

    /**
     * 打码值必须能追溯到库中那份配置，否则这次自检注定是「拿星号去签名」。
     *
     * <p>编辑态的表单提交体里，服务端打码回来的 {@code accessKeyId} 是 {@code ******}：
     * 那是**对外那份**，不是内部那份。要让它变成可用的凭据只有一条路——按 catalog 名
     * 把库中那份合并进来（{@link #mergeWithStored}）。少了这个名字，合并无从发生，
     * 后面 {@code secrets()} 会以 500 拒掉，而这个 500 在界面上被归成
     * 「这个存储不支持探测」。
     *
     * <p>所以在最前面就把它拦成 400，并**说清缺的是什么**：调用方少传了一个字段，
     * 不是存储有问题。
     */
    private static void requireMergeableDraft(StorageConfigInfo draft, String catalogName) {
        if (catalogName != null && !catalogName.isBlank()) {
            return;
        }
        if (!StorageConfigs.carriesMaskedSecret(draft)) {
            return;
        }
        throw ApiException.badRequest("storageConfigInfo carries the masking value \""
                + StorageConfigs.MASKED + "\" (that is what the API returns for a stored secret,"
                + " never the secret itself); send \"catalog\" with the name of the catalog being"
                + " edited so the server can merge in the stored configuration, or supply the real"
                + " credential");
    }

    // ------------------------------------------------------------------ 读档

    /**
     * 读档：HEAD 一次，失败时再用「目录标记」补一次。
     *
     * <p>第二次 HEAD 是为了对齐对象存储的真实语义：{@code s3://bucket/wh} 这种前缀下面
     * 可能只有子目录、没有同名对象，于是第一次 HEAD 会 404，而它完全可用。
     * 仍然只做 HEAD（{@code wh/} 这个 key），不列目录——列表能力等于把服务端
     * 变成对象存储浏览器（§5.5 第 2 条）。
     */
    private ConsoleDtos.StorageProbeCheck readCheck(StorageConfigInfo storage, String location,
                                                    String warehouse) {
        try {
            FileIO fileIO = fileIoFactory.open(new Path(location), options(storage, location, warehouse, false));
            if (headSucceeds(fileIO, location)) {
                return new ConsoleDtos.StorageProbeCheck(location, OK, "HEAD 200");
            }
            return new ConsoleDtos.StorageProbeCheck(location, NOT_FOUND,
                    "HEAD 404（对象与目录标记都不存在）");
        } catch (IOException failure) {
            return classify(location, failure);
        } catch (RuntimeException failure) {
            // 不是「存储连不上」，而是「我们自己没准备好」：取票、解密、合并这一层出的错
            log.warn("storage probe for {} failed before reaching the storage", location, failure);
            return new ConsoleDtos.StorageProbeCheck(location, CONFIG_ERROR,
                    "服务端没能为这个位置装配凭据（配置问题，不是存储问题；详见服务端日志）");
        }
    }

    private static boolean headSucceeds(FileIO fileIO, String location) {
        if (statSucceeds(fileIO, location)) {
            return true;
        }
        return statSucceeds(fileIO, location.endsWith("/") ? location : location + "/");
    }

    private static boolean statSucceeds(FileIO fileIO, String location) {
        try {
            fileIO.getFileStatus(new Path(location));
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 写档

    private record WriteResult(String verdict, String detail, String key, Boolean cleanup) {
    }

    /**
     * 写档：PUT 一个 0 字节对象到 {@code <位置>/_lakehouse_probe/}，确认它在，然后删掉。
     *
     * <p>三个选择都有理由（§5.3）：0 字节不产生存储成本、也不会被当成数据；固定前缀让
     * 「最小写权限」可以只放行那一个前缀；写完就删是不指望桶的生命周期规则——
     * 但删失败必须把 key 回给用户，静默残留不可接受。
     */
    private WriteResult writeCheck(StorageConfigInfo storage, String location, String warehouse) {
        String key = location.endsWith("/") ? location + PROBE_PREFIX : location + "/" + PROBE_PREFIX;
        key = key + "/" + ZonedDateTime.now(ZoneOffset.UTC).format(STAMP) + "-"
                + randomSuffix() + ".txt";
        try {
            FileIO fileIO = fileIoFactory.open(new Path(key), options(storage, location, warehouse, true));
            try (OutputStream out = fileIO.newOutputStream(new Path(key), false)) {
                // 故意什么都不写：0 字节
                out.flush();
            }
            if (!statSucceeds(fileIO, key)) {
                // 写成功但 HEAD 不到：多数是最终一致性或「写到了别的账户」，两种情况都该说「没验过」
                return new WriteResult(NOT_FOUND, "PUT 成功但 HEAD 不到这个对象", key, null);
            }
            boolean deleted = delete(fileIO, key);
            return new WriteResult(OK, "PUT 0 字节 + HEAD 200" + (deleted ? " + DELETE" : "，DELETE 失败"),
                    key, deleted);
        } catch (IOException failure) {
            ConsoleDtos.StorageProbeCheck classified = classify(location, failure);
            return new WriteResult(classified.verdict(), "PUT " + classified.detail(), key, null);
        } catch (RuntimeException failure) {
            log.warn("storage write probe for {} failed before reaching the storage", key, failure);
            return new WriteResult(CONFIG_ERROR,
                    "服务端没能为这个位置装配写凭据（配置问题，不是存储问题；详见服务端日志）", key, null);
        }
    }

    private static boolean delete(FileIO fileIO, String key) {
        try {
            return fileIO.delete(new Path(key), false);
        } catch (IOException failure) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 判定与文案

    /**
     * 把云侧的异常归到五档。
     *
     * <p><b>只回分类，不回原文。</b>云侧的错误消息常带 endpoint、账号 ID、内网域名，
     * 而这个响应会进浏览器、进截图、进工单。原文只写服务端日志。
     *
     * <p><b>403 与 404 在 S3 上不可区分</b>：没有 {@code s3:ListBucket} 时 HEAD 一个不存在的
     * key 会回 403。所以文案不写「对象不存在」，写「这个位置读不到」——两个可能都指向
     * 用户下一步要查的东西。
     */
    private ConsoleDtos.StorageProbeCheck classify(String location, IOException failure) {
        String verdict = classifyVerdict(failure);
        log.info("storage probe of {} classified as {}: {}", location, verdict, failure.toString());
        return new ConsoleDtos.StorageProbeCheck(location, verdict, detailOf(verdict));
    }

    private static String classifyVerdict(Throwable failure) {
        String text = flatten(failure).toLowerCase(Locale.ROOT);
        if (text.contains("no fileio implementation for scheme")) {
            return UNSUPPORTED;
        }
        if (text.contains("403") || text.contains("accessdenied") || text.contains("forbidden")
                || text.contains("permission denied") || text.contains("denied")) {
            return DENIED;
        }
        if (text.contains("404") || text.contains("nosuchbucket") || text.contains("not found")
                || text.contains("no such file") || text.contains("does not exist")) {
            return NOT_FOUND;
        }
        return UNREACHABLE;
    }

    /** 整条因果链拼起来匹配：真正的关键词常在 {@code Caused by} 那一层。 */
    private static String flatten(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            text.append(current.getClass().getSimpleName()).append(':')
                    .append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return text.toString();
    }

    private static String detailOf(String verdict) {
        return switch (verdict) {
            case DENIED -> "401/403：这份身份没有权限，或根本没用上这份身份";
            case NOT_FOUND -> "404：这个位置读不到——可能地址不对，也可能这份身份没有权限";
            case UNSUPPORTED -> "本部署没有这个 scheme 的 FileIO 实现";
            case CONFIG_ERROR -> "服务端没装配起这份凭据（取票 / 解密 / 配置校验失败），与存储是否可达无关";
            default -> "连不上：DNS、端口或出网策略（详见服务端日志）";
        };
    }

    private static String worst(List<ConsoleDtos.StorageProbeCheck> checks) {
        String worst = OK;
        for (ConsoleDtos.StorageProbeCheck check : checks) {
            if (SEVERITY.indexOf(check.verdict()) > SEVERITY.indexOf(worst)) {
                worst = check.verdict();
            }
        }
        return worst;
    }

    /**
     * 一句话说清下一步。
     *
     * <p>「能读、不能写」必须单独成句：它对应的现象是「预览正常、建表 500」，
     * 而用户看到「自检通过」时会以为整份配置都对。
     */
    private static String hint(String verdict, boolean readable, Boolean writable, boolean checkedWrite) {
        if (DENIED.equals(verdict)) {
            return "权限被拒：先确认这把钥匙对应的身份在桶策略 / 角色信任关系里放行了这个前缀，"
                    + "再确认服务端确实用上了它（而不是退回了自己的身份）";
        }
        if (NOT_FOUND.equals(verdict)) {
            return "位置读不到：地址（桶名 / 前缀 / region）可能写错，也可能这份身份没有权限——"
                    + "S3 上这两种情况的回码可能一样";
        }
        if (UNREACHABLE.equals(verdict)) {
            return "服务端连不上这个地址：检查 endpoint 是不是内网地址、出网与 DNS";
        }
        if (UNSUPPORTED.equals(verdict)) {
            return "本部署没有这个 scheme 的实现：见 lakehouse.rest.file-io.type 与该存储的插件";
        }
        if (CONFIG_ERROR.equals(verdict)) {
            return "服务端自己没能装配起这份凭据（与存储是否可达无关）：先看每一项后面的明细与服务端日志。"
                    + "最常见的是编辑态提交了打码后的配置，或落库加密密钥换过、旧密文解不开";
        }
        if (!checkedWrite) {
            return "只验了读。建表要写 schema-<n>，而写权限与读权限在四家云上都不是同一份策略——"
                    + "勾上「连写入一起验」才能确认建得出表";
        }
        if (Boolean.TRUE.equals(writable)) {
            return "读写都通了：服务端可以预览，也可以建表";
        }
        return "能读，不能写：预览会正常，但建表 / 改表 / 删目录会失败——"
                + "桶策略里要单独放行 PutObject（更省事的做法是只放行 _lakehouse_probe/ 先试）";
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 编辑态：草稿里的 {@code ******} 要换成库里那份，否则 {@code secrets()} 会拒（500）。
     *
     * <p>复用的正是保存路径的 {@link StorageConfigs#mergeCredentials}——自检要看的是
     * 「保存之后会怎样」，用另一套合并规则就会得出另一个答案。
     */
    private StorageConfigInfo mergeWithStored(StorageConfigInfo draft, String catalogName) {
        if (catalogName == null || catalogName.isBlank()) {
            return draft;
        }
        CatalogEntity catalog = catalogs.require(catalogName);
        return StorageConfigs.mergeCredentials(draft, StorageConfigs.of(catalog), cipher);
    }

    /**
     * 给 FileIO 的选项：凭据（与引擎同一套来源）+ 仓库位置。
     *
     * <p>仓库位置也要给：对象存储的 FileIO 缺少定位信息时会退回默认凭据链与默认区域，
     * 报出来的错与真正的原因隔着一层。
     */
    private Map<String, String> options(StorageConfigInfo storage, String location, String warehouse,
                                        boolean allowWrite) {
        Map<String, String> options = new LinkedHashMap<>();
        String scopeId = "probe:" + (RequestContext.principal() == null ? "anonymous"
                : RequestContext.principal());
        options.putAll(storagePolicy.credentialManager()
                .vend(storage, scopeId, location, allowWrite).token());
        if (warehouse != null && !warehouse.isBlank()) {
            options.put(CatalogOptions.WAREHOUSE.key(), warehouse);
        }
        return options;
    }

    /**
     * 只探对象存储：{@code file:} 与无 scheme 的路径一律拒。
     *
     * <p>写成硬编码而不是配置项：允许探测本地文件等于开了一个「本机文件存在性」查询接口，
     * 而它在任何部署形态下都不是需求（§5.5 第 1 条）。400 而不是静默跳过——
     * 静默跳过会让用户以为「验过了」。
     */
    private static void requireNoLocalPaths(List<String> targets) {
        for (String location : targets) {
            String scheme = schemeOf(location);
            if (scheme == null || "file".equals(scheme)) {
                throw ApiException.badRequest("cannot probe \"" + location + "\": local paths are"
                        + " never probed (a HEAD on file:// is a \"does this file exist on the"
                        + " server\" oracle); point the catalog at an object store to use the probe");
            }
        }
    }

    private static String schemeOf(String location) {
        try {
            String scheme = URI.create(location.trim()).getScheme();
            return scheme == null || scheme.isBlank() ? null : scheme.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * 限频：滑动一分钟窗口，按主体计。
     *
     * <p>失败也计数（先记再探）：一个只在成功时计数的限流器，对「拿错钥匙反复试」
     * 这件事完全无效，而那正是它要防的用法。
     */
    private void checkRate() {
        String principal = RequestContext.principal() == null ? "anonymous"
                : RequestContext.principal();
        int limit = Math.max(1, properties.getStorageProbe().getPerMinute());
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(principal, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                throw new ThrottledException(
                        "storage probe rate limit exceeded for " + principal + ": at most " + limit
                                + " probes per minute (lakehouse.rest.console.storage-probe-per-minute)");
            }
            window.addLast(now);
        }
    }

    private String randomSuffix() {
        return String.format(Locale.ROOT, "%06x", random.nextInt(0x1000000));
    }
}
