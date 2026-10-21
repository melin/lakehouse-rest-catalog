package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SeekableBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.SeekableInputStream;
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

    /**
     * 以 catalog 的身份**按位置读**一个数据文件。
     *
     * <p><b>为什么需要"按位置"。</b>预览读的是 parquet：先读文件尾部的 footer，
     * 再按 row group 跳到各处读列块。普通 {@code InputStream} 只能从头读到尾，
     * 跳过 N 字节要真读再丢——对对象存储来说这是两个数量级的差别。
     *
     * <p><b>为什么参数与返回都不带格式类型。</b>调用方是 Iceberg 协议面的预览读取器，
     * 它不能 import {@code org.apache.paimon.fs.SeekableInputStream}（那条禁令由
     * 模块 POM 的 {@code bannedDependencies} 守着）。本类把它包成 core 里的
     * {@link SeekableBytes}，于是"打开文件"这件事的实现仍然只有一处——
     * 与写元数据共用同一套凭据解析，避免"引擎能读、控制台读不了"。
     *
     * <p><b>为什么调用方必须关。</b>句柄不随请求结束自动释放；对象存储上它对应一个
     * HTTP 连接池里的连接，泄漏的后果是连接池耗尽，现象与网络故障无法区分。
     * 因此返回值是 {@code Closeable}，读取器在 finally / try-with-resources 里关。
     *
     * @throws ApiException 404 文件不存在（清单与文件不一致时会发生：并发 compaction
     *                      删掉了刚被规划进来的文件）；500 仓库访问失败
     */
    public SeekableBytes open(String catalogId, String location) {
        try {
            FileIO fileIO = fileIO(catalogId, location);
            Path path = new Path(location);
            if (!fileIO.exists(path)) {
                throw new ApiException(404, null, location, "No such file: " + location);
            }
            // 长度先问一次：parquet 的 footer 读取要从 length - 8 开始寻址，
            // 而 InputFile 的契约要求长度可答且廉价
            return new PaimonSeekableBytes(fileIO.newInputStream(path),
                    fileIO.getFileSize(path), location);
        } catch (IOException e) {
            throw failure("open", location, e);
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

    /**
     * 把 Paimon 的流包成 {@link SeekableBytes}——**这个类就是那道类型边界**。
     *
     * <p>包装而不是暴露：调用方拿到的是"能 seek 的字节流"这个能力，而不是
     * {@code org.apache.paimon.fs.SeekableInputStream} 这个类型。于是 Iceberg 协议面
     * 不必为了读一个文件而依赖 Paimon，而凭据与 scheme 插件的解析仍然只有一份。
     *
     * <p>长度在构造时定死，之后不再问存储：一次预览里文件的长度是稳定的
     * （快照托管的数据文件写完就不再改），而重复问一次可能多一次 RTT。
     */
    private static final class PaimonSeekableBytes implements SeekableBytes {

        private final SeekableInputStream stream;
        private final long length;
        private final String location;

        private PaimonSeekableBytes(SeekableInputStream stream, long length, String location) {
            this.stream = stream;
            this.length = length;
            this.location = location;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public long position() {
            try {
                return stream.getPos();
            } catch (IOException e) {
                // position() 不在接口上声明受检异常：调用方（parquet 读取器）此时
                // 只会把它当成"流坏了"，包成未检异常让它走同一条失败路径
                throw new UncheckedIOException("cannot tell the position in " + location, e);
            }
        }

        @Override
        public void seek(long position) throws IOException {
            stream.seek(position);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            return stream.read(target, offset, length);
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
