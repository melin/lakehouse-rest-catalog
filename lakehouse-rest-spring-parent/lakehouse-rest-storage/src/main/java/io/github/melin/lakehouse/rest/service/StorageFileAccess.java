package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 以 catalog 的身份读写仓库里的**单个文件**，接口上不出现任何格式类型。
 *
 * <p><b>为什么需要它。</b>服务端有两处要自己往仓库里写元数据文件：Paimon 面的
 * {@code schema-<n>} / {@code snapshot-<n>}，以及 Iceberg 面的
 * {@code metadata/v<N>-<uuid>.metadata.json}。两者要的都是同一件事——按 catalog 的存储配置
 * 打开 FileIO、原子写一个文件——但**Iceberg 面不该 import
 * {@code org.apache.paimon.fs.FileIO}**：那个类的存在本身就是「两套依赖世界不共处
 * 一个编译单元」这条设计要隔开的东西。本类把这层封装在 storage 模块里
 * （它是唯一允许 import Paimon FileIO 的地方之一），对外只暴露
 * 「路径 + 文本」。
 *
 * <p>这不只是洁癖：{@code paimon-core} 与 {@code iceberg-core} 各自带一份 Jackson /
 * Avro / Guava，两个协议面互相看不到对方的依赖，才有可能在将来把其中一个换掉或去掉。
 *
 * <p><b>每次调用都重新打开 FileIO</b>，与 {@link StorageFileIoFactory} 的理由相同：
 * 缓存凭据的代价是「管理 API 换过 catalog 凭据之后，服务端还在用旧钥匙写」，
 * 而那种问题要到下次写文件时才炸，报错只说认证失败。写路径是低频操作，不值得为此冒险。
 *
 * <p><b>写入是原子的</b>（{@code tryToWriteAtomic}）。这一点对 Iceberg 尤其要紧：
 * 元数据文件一旦被读到一半，客户端会解析失败并把整张表判成损坏。对象存储侧的
 * 「原子写」是「先写临时对象再 rename」，实现由 FileIO 负责。
 */
@Service
@RequiredArgsConstructor
public class StorageFileAccess {

    private static final Logger log = LoggerFactory.getLogger(StorageFileAccess.class);

    private final CatalogRepository catalogRepository;

    private final StorageFileIoFactory fileIoFactory;

    /** 文件是否存在。 */
    public boolean exists(String catalogId, String location) {
        try {
            return fileIO(catalogId, location).exists(new Path(location));
        } catch (IOException e) {
            throw failure("check", location, e);
        }
    }

    /**
     * 读一个 UTF-8 文本文件。
     *
     * @throws ApiException 404 文件不存在时（对调用方而言就是「这个位置没有元数据」）
     */
    public String read(String catalogId, String location) {
        try {
            FileIO fileIO = fileIO(catalogId, location);
            Path path = new Path(location);
            if (!fileIO.exists(path)) {
                throw new ApiException(404, null, location, "No such file: " + location);
            }
            return fileIO.readFileUtf8(path);
        } catch (IOException e) {
            throw failure("read", location, e);
        }
    }

    /**
     * 原子写一个 UTF-8 文本文件。
     *
     * <p><b>已存在且内容不同时报错，而不是覆盖。</b>元数据文件的文件名里带 uuid，
     * 正常情况下不可能撞名；撞上只有两种可能：别人同时提交了同一个版本，或者
     * 这个位置原本就属于另一张表。两种情况下覆盖都比报错危险——后者至少把
     * 「换个位置」这个选择留给调用方。（与 Paimon 侧 {@code materializeSnapshot}
     * 的处置一致。）
     *
     * <p>已存在、内容一致时静默成功：那是一次重试，比如客户端在超时后重发同一个提交。
     */
    public void write(String catalogId, String location, String content) {
        try {
            FileIO fileIO = fileIO(catalogId, location);
            Path path = new Path(location);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (fileIO.tryToWriteAtomic(path, content)) {
                log.debug("wrote {} ({} bytes)", location, bytes.length);
                return;
            }
            if (!fileIO.exists(path)) {
                throw new IOException("the write to " + location + " did not take effect");
            }
            String existing = fileIO.readFileUtf8(path);
            if (!existing.equals(content)) {
                throw new IOException(location + " already exists with a different content");
            }
        } catch (IOException e) {
            throw failure("write", location, e);
        }
    }

    /** 删一个文件；文件不存在时返回 {@code false} 而不是报错。 */
    public boolean delete(String catalogId, String location) {
        try {
            FileIO fileIO = fileIO(catalogId, location);
            Path path = new Path(location);
            if (!fileIO.exists(path)) {
                return false;
            }
            fileIO.delete(path, false);
            return true;
        } catch (IOException e) {
            throw failure("delete", location, e);
        }
    }

    /** 递归删一个目录；不存在时返回 {@code false}。 */
    public boolean deleteDirectory(String catalogId, String location) {
        try {
            FileIO fileIO = fileIO(catalogId, location);
            Path path = new Path(location);
            if (!fileIO.exists(path)) {
                return false;
            }
            fileIO.delete(path, true);
            return true;
        } catch (IOException e) {
            throw failure("delete", location, e);
        }
    }

    /** 按 catalog 当前的存储配置打开 FileIO。 */
    private FileIO fileIO(String catalogId, String location) throws IOException {
        CatalogEntity catalog = catalogRepository.findById(catalogId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogId));
        return fileIoFactory.open(new Path(location),
                fileIoFactory.optionsFor(catalog, catalogId, location));
    }

    /**
     * 仓库访问失败一律是 500。
     *
     * <p>不接住 {@link ApiException}（那是「算不出凭据」，例如
     * {@code credential-manager.type=noop}），它在 {@code optionsFor} 里抛出，
     * 语义是「这份部署访问不了这个仓库」，与 IO 失败一样是服务端的问题。
     * 用 {@link UncheckedIOException} 之外的形态报出去，是为了让错误信息里
     * 带上到底是哪一步、哪个路径——对象存储的异常里常常只有一个没有上下文的 key。
     */
    private static ApiException failure(String action, String location, IOException cause) {
        log.error("failed to {} {}", action, location, cause);
        return new ApiException(500, null, location,
                "failed to " + action + " " + location + ": " + cause.getMessage());
    }
}
