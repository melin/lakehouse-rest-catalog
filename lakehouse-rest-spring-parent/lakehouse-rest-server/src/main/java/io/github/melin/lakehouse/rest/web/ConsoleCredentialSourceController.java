package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.service.ConsoleCredentialSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的「这个 catalog 的凭据从哪来」端点。
 *
 * <p>与 {@link ConsoleMetaController} 同属控制台命名空间（不是管理 API 的一部分）：
 * 它回答的是界面上的一个问题——「这一行是现签短期票、还是在转发长期密钥、还是根本不下发」。
 * 三档在后两档上客户端配置完全一样，只能靠界面上这一处标记区分
 * （{@code docs/credential-vending-design.md} §10.1）。
 *
 * <p>放在服务端算而不是在浏览器里算：判据要读 {@code lakehouse.rest.storage.*} 与
 * {@code lakehouse.rest.credential.providers}，前端拿不到；而把优先级抄进 JS
 * 一定会与凭据管理器漂移，漂移的表现是「界面说在签发、实际在转发」。
 */
@RestController
@RequestMapping("/api/console/v1")
@RequiredArgsConstructor
public class ConsoleCredentialSourceController {

    private final ConsoleCredentialSourceService service;

    /** {@code GET /api/console/v1/catalog-credential-sources}：当前可见 catalog 的来源判定。 */
    @GetMapping("/catalog-credential-sources")
    public ConsoleDtos.CatalogCredentialSources list() {
        return service.list();
    }

    /**
     * {@code POST /api/console/v1/catalog-credential-source}：判定表单**还没保存**的那份配置。
     *
     * <p>用 POST 而不是 GET：输入是一整份 {@code storageConfigInfo}，而且这份配置
     * 可以带打码的 {@code accessKeyId}——把它塞进查询串会进访问日志，而日志是
     * 比请求体更容易被顺手分享出去的地方（密钥本身不在里面，但这个习惯不该养）。
     *
     * <p>请求体就是管理 API 里那一份 {@code StorageConfigInfo}（同一套多态反序列化），
     * 因此控制台不需要为预览另拼一种形状；反序列化不了（{@code storageType} 缺失或
     * 不认得）时是 400，控制台把预览藏起来——表单本身会先拦住这种情况。
     */
    @PostMapping("/catalog-credential-source")
    public ConsoleDtos.CredentialSourcePreview preview(@RequestBody StorageConfigInfo draft) {
        return service.preview(draft);
    }
}
