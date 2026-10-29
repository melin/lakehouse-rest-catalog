package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CredentialManagerType;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 不下发凭据的实现，对应 {@code lakehouse.rest.credential-manager.type=noop}。
 *
 * <p>适用场景是引擎自带云凭据的部署：实例角色、工作负载标识、挂载的配置文件。
 * 这类部署里服务端再下发一份凭据不仅多余，还会让引擎的凭据来源变得不确定——
 * 到底用了服务端给的那份还是自己的那份，取决于引擎的优先级规则，
 * 排查权限问题时这是个很难绕开的坑。关掉它，凭据来源就只剩一个。
 *
 * <p>返回 501 而不是 403：这是「本部署没有提供这项能力」，
 * 不是「你这个身份不够格」。用 403 会让调用方去申请权限，
 * 而真正该做的是换一个部署或改用引擎自带凭据。
 */
@Component
public class NoopStorageCredentialManager implements StorageCredentialManager {

    @Override
    public CredentialManagerType type() {
        return CredentialManagerType.NOOP;
    }

    @Override
    public VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath,
                                        boolean allowWrite) {
        throw new ApiException(501, null, null,
                "storage credential vending is disabled: lakehouse.rest.credential-manager.type=noop");
    }

    /**
     * 服务端自己的票同样不提供。
     *
     * <p>这一档的语义是「引擎自带云凭据」，而服务端自己**没有**引擎那份凭据可借——
     * 它若也要访问仓库，只能靠部署环境（实例角色 / 挂载的配置文件）而不是这里。
     * 所以这里保持一致地报 501，而不是悄悄回一份空凭据：空凭据会让 FileIO
     * 退回环境链，表现是「预览能看、写元数据失败」，比直接报错难查得多。
     */
    @Override
    public VendedStorageCredential vendForCatalog(StorageConfigInfo storage, String catalogId,
                                                  List<String> allowedLocations, boolean allowWrite) {
        throw new ApiException(501, null, null,
                "storage credential vending is disabled: lakehouse.rest.credential-manager.type=noop");
    }
}
