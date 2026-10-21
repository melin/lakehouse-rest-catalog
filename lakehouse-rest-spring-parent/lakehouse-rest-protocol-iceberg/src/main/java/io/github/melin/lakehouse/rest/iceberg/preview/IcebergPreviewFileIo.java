package io.github.melin.lakehouse.rest.iceberg.preview;

import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.support.SeekableBytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SeekableInputStream;

/**
 * 只读的 {@link FileIO}：把 Iceberg 的读取交给**存储层那唯一一份**文件访问实现。
 *
 * <p><b>为什么不 {@code CatalogUtil.loadFileIO(io-impl, props, conf)}。</b>那条路要
 * 一个具体的 IO 实现（{@code S3FileIO} / {@code HadoopFileIO}），两者都不合适：
 * {@code HadoopFileIO} 要求服务端带 Hadoop 客户端栈（本部署不带），
 * {@code S3FileIO} 要另引 AWS SDK（约 50 MB）并且**自成一套凭据解析**——
 * 而"引擎能不能读"与"控制台能不能读"必须是同一个答案，那个答案的落点已经
 * 存在了（{@code StorageFileAccess} + {@code StorageFileIoFactory} 的凭据管理器）。
 *
 * <p>因此这里只做一件事：把 Iceberg 的 {@link InputFile} 接到
 * {@link StorageFileAccess#open} 上。凭据、scheme 插件、重试、路径模板全都不在本类，
 * 于是"换了一级具名存储之后控制台读不了"这类漂移不可能发生。
 *
 * <p><b>只读是刻意的。</b>预览不写任何东西；{@link #newOutputFile} 与
 * {@link #deleteFile} 直接抛 {@link UnsupportedOperationException}——
 * 一个"能写"的 IO 出现在读路径上，迟早会被某段代码借去写临时文件，
 * 而那些文件的生命周期没人管。
 *
 * <p><b>不可序列化。</b>接口要求 {@code Serializable}（Iceberg 的 {@code Table}
 * 是可序列化的，写路径要把它发给执行器），但本部署只在服务端进程内读一页，
 * 不会序列化 {@code Table}。真要序列化会在 {@link StorageFileAccess} 上失败——
 * 那是明确的错误，而不是静默地拿到一个不能用的空壳。
 */
final class IcebergPreviewFileIo implements FileIO {

    private final StorageFileAccess access;

    private final String catalogId;

    IcebergPreviewFileIo(StorageFileAccess access, String catalogId) {
        this.access = access;
        this.catalogId = catalogId;
    }

    @Override
    public InputFile newInputFile(String path) {
        return new IcebergInputFile(access, catalogId, path, -1);
    }

    @Override
    public InputFile newInputFile(String path, long length) {
        // 长度由调用方给时直接用：DataFile 里就有，省掉一次 getFileStatus
        return new IcebergInputFile(access, catalogId, path, length);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        throw new UnsupportedOperationException("the console preview only reads data files;"
                + " writing " + path + " is not supported here");
    }

    @Override
    public void deleteFile(String path) {
        throw new UnsupportedOperationException("the console preview only reads data files;"
                + " deleting " + path + " is not supported here");
    }

    /** 一个数据文件：长度可能来自清单（{@code DataFile#fileSizeInBytes}），也可能要现问。 */
    private static final class IcebergInputFile implements InputFile {

        private final StorageFileAccess access;
        private final String catalogId;
        private final String location;

        /** {@code -1} 表示"还没问过"；清单里给了长度时直接用那个值。 */
        private volatile long length;

        private IcebergInputFile(StorageFileAccess access, String catalogId, String location,
                                 long length) {
            this.access = access;
            this.catalogId = catalogId;
            this.location = location;
            this.length = length;
        }

        @Override
        public long getLength() {
            if (length < 0) {
                // 打开一次只为问长度：对象存储的 HEAD 比 GET 便宜得多，而 parquet 读
                // footer 之前必须先知道长度
                try (SeekableBytes bytes = access.open(catalogId, location)) {
                    length = bytes.length();
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot tell the length of " + location, e);
                }
            }
            return length;
        }

        @Override
        public SeekableInputStream newStream() {
            return new Bridge(access.open(catalogId, location), location);
        }

        @Override
        public String location() {
            return location;
        }

        @Override
        public boolean exists() {
            return access.exists(catalogId, location);
        }
    }

    /**
     * {@link SeekableBytes} → Iceberg 的 {@link SeekableInputStream}。
     *
     * <p>{@code read()}（单字节）在这里只能是"读一个字节的数组"：底层接口按块读。
     * parquet 的读取器走的是块读路径，单字节读只在探测文件头时出现，代价可以忽略。
     */
    private static final class Bridge extends SeekableInputStream {

        private final SeekableBytes bytes;
        private final String location;

        private Bridge(SeekableBytes bytes, String location) {
            this.bytes = bytes;
            this.location = location;
        }

        @Override
        public long getPos() {
            return bytes.position();
        }

        @Override
        public void seek(long newPosition) throws IOException {
            bytes.seek(newPosition);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = bytes.read(one, 0, 1);
            return read <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            return bytes.read(target, offset, length);
        }

        @Override
        public void close() throws IOException {
            bytes.close();
        }

        @Override
        public String toString() {
            return "preview:" + location;
        }
    }
}
