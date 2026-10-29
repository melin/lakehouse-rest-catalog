package io.github.melin.lakehouse.rest.dto;

import java.util.Arrays;
import java.util.Optional;

/**
 * 管理 API 中需要校验取值的字符串枚举。
 *
 * <p>规格把这些字段声明为自由字符串加 {@code enum} 约束而非引用具名 schema，
 * 这里为它们建 Java 枚举以获得编译期校验，同时保留 {@link #wireName()}
 * 作为对外取值，保证序列化与规格逐字一致。
 */
public final class ManagementEnums {

    private ManagementEnums() {
    }

    /** 管理规格 {@code Catalog.type}。 */
    public enum CatalogType {
        INTERNAL,
        EXTERNAL;

        /** 按规格取值解析，未知取值返回空。 */
        public static Optional<CatalogType> parse(String value) {
            // 显式限定外层方法：签名同名的内部方法会遮蔽它
            return ManagementEnums.parse(CatalogType.class, value);
        }

        public String wireName() {
            return name();
        }
    }

    /**
     * 管理规格 {@code StorageConfigInfo.storageType}。
     *
     * <p><b>{@code OBS} 与 {@code OSS} 是本工程的扩展，不在 Polaris 规格里。</b>
     * 规格的这一处 {@code enum} 只有 {@code S3 / GCS / AZURE / FILE} 四个取值，
     * 因为 Polaris 把华为云与阿里云都归入「兼容 S3 协议的对象存储」，
     * 靠自定义 {@code endpoint} 接入。这里单列两个取值，是为了让凭据下发能产出
     * 厂商原生的键族（{@code fs.obs.*} / {@code fs.oss.*}）——这两套键与
     * {@code s3.*} 并不通用，混用会静默失效。
     *
     * <p>代价是 {@code storageType} 的取值集合超出规格。影响面仅限管理 API 的
     * 「按类型分派」逻辑：客户端（引擎）只是把 {@code storageConfigInfo} 原样透传，
     * 不解析这个字段，因此不破坏 REST Catalog 协议。取舍记在
     * {@code docs/management-api-contract.md}。
     *
     * <p><b>{@code COS} 同理，但它与 {@code OBS} / {@code OSS} 有一处本质差别</b>：
     * 后两者是「兼容 S3 协议」的等价物，理论上可以用 {@code S3} 类型加自定义
     * {@code endpoint} 接进来；腾讯云 COS 走的是 COSN（{@code cosn://} +
     * {@code fs.cosn.*} 键族，见 {@code DefaultStorageCredentialManager}），
     * 与 S3 兼容层是两条不同的实现路径，混用会静默失效。因此必须单列一个取值——
     * 否则「用 S3 类型接 COS」这种看起来能跑的配置，会在引擎侧报一个与凭据无关的错。
     */
    public enum StorageType {
        S3,
        GCS,
        AZURE,
        OBS,
        OSS,
        COS,
        FILE;

        public static Optional<StorageType> parse(String value) {
            return ManagementEnums.parse(StorageType.class, value);
        }

        public String wireName() {
            return name();
        }
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
