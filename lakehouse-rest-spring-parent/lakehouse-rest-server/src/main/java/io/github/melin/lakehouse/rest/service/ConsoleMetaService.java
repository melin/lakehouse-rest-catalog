package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.CatalogType;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.CredentialManagerType;
import io.github.melin.lakehouse.rest.support.FileIoType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 控制台元数据的组装。
 *
 * <p>取值全部现取现用（枚举 {@code values()}、配置对象、{@link FileIoType#supportedStorageTypes()}），
 * 不缓存也不落库：这些值只有在服务端重启后才会变，而重启后本进程的缓存自然也没了。
 * 唯一的例外是版本号——它来自 jar 清单，进程生命周期内不变。
 */
@Service
@RequiredArgsConstructor
public class ConsoleMetaService {

    /** 从类目录（IDE、测试）运行时读不到清单，用这个占位而不是空串。 */
    private static final String DEVELOPMENT_VERSION = "development";

    private final RestServerProperties properties;

    private final CredentialCipher cipher;

    @Value("${spring.application.name:lakehouse-rest-server}")
    private String applicationName;

    public ConsoleDtos.ConsoleMeta meta() {
        return new ConsoleDtos.ConsoleMeta(service(), enums());
    }

    private ConsoleDtos.Service service() {
        FileIoType fileIoType = FileIoType.require(properties.getFileIo().getType());
        CredentialManagerType credentialManagerType =
                CredentialManagerType.require(properties.getCredentialManager().getType());
        return new ConsoleDtos.Service(
                applicationName,
                version(),
                properties.getAuth().isEnabled(),
                properties.getAuthorization().isEnabled(),
                credentialManagerType.wireName(),
                fileIoType.wireName(),
                properties.getDefaultPrefix(),
                properties.getDefaultWarehouse(),
                CatalogFormat.of(properties.getDefaultCatalogFormat()).wireName(),
                properties.isAutoCreateCatalog(),
                properties.getDefaultPageSize(),
                properties.getMaxPageSize(),
                properties.getPathTemplate(),
                properties.getStorageCredentialCache().getMaxEntries(),
                properties.getCredential().getTtlSeconds());
    }

    /**
     * 构建版本。
     *
     * <p>读 jar 清单里的 {@code Implementation-Version}——由 maven-jar-plugin 的
     * {@code addDefaultImplementationEntries} 写入（见 lakehouse-rest-spring-parent/lakehouse-rest-server/pom.xml）。
     * 从 {@code target/classes} 直接运行时该值为空，此时返回 {@code development}：
     * 报告一个「看起来像版本号」的默认值会让人误以为在跑发布版。
     */
    private String version() {
        String implementationVersion = ConsoleMetaService.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? DEVELOPMENT_VERSION
                : implementationVersion;
    }

    private ConsoleDtos.Enums enums() {
        FileIoType fileIoType = FileIoType.require(properties.getFileIo().getType());
        // 资源类型 → 该层级允许授予的 access，取自 Paimon 官方 Access/Resource 对照表。
        // 前端不再自带一份词表：它按这里的键渲染「资源类型」下拉，按值渲染「access」下拉。
        Map<String, List<String>> accessesByResourceType = new LinkedHashMap<>();
        for (ResourceType resourceType : ResourceType.values()) {
            List<String> accesses = new ArrayList<>();
            for (Access access : Access.allowedFor(resourceType)) {
                accesses.add(access.name());
            }
            // 集合来自 LinkedHashSet，顺序稳定；排序是为了让下拉框有可预期的顺序
            accesses.sort(String::compareTo);
            accessesByResourceType.put(resourceType.name(), accesses);
        }

        List<String> supported = new ArrayList<>();
        for (StorageType type : StorageType.values()) {
            if (fileIoType.supports(type)) {
                supported.add(type.wireName());
            }
        }

        return new ConsoleDtos.Enums(
                names(CatalogType.values()),
                names(CatalogFormat.values()),
                names(StorageType.values()),
                supported,
                cipher.available(),
                Arrays.stream(CredentialManagerType.values()).map(CredentialManagerType::wireName).toList(),
                List.copyOf(FileIoType.wireNames()),
                Arrays.stream(ResourceType.values()).map(Enum::name).toList(),
                accessesByResourceType);
    }

    private static <E extends Enum<E>> List<String> names(E[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (E value : values) {
            names.add(value.name());
        }
        return names;
    }
}
