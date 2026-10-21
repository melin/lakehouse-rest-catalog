package io.github.melin.lakehouse.rest.support;

import java.io.Closeable;
import java.io.IOException;

/**
 * 一个**可按位置读**的字节流——服务端读仓库里的数据文件时用的最小契约。
 *
 * <p><b>为什么需要它。</b>预览要读 parquet 数据文件，而 parquet 的读取方式不是
 * "从头读到尾"：先读文件尾部的 footer、再按 row group 跳到指定位置读列块。
 * 这要求流支持 {@link #seek(long)}。但两个协议面**都不该 import 对方的
 * IO 类型**（见 {@code docs/maven-module-design.md} §3.1）：Paimon 的
 * {@code org.apache.paimon.fs.SeekableInputStream} 与 Iceberg 的
 * {@code org.apache.iceberg.io.SeekableInputStream} 是两个不相干的类型，
 * 而真正打开文件的实现（凭据、scheme 插件、原子写）必须只有一份——那是
 * {@code StorageFileAccess} 的责任。
 *
 * <p>于是本接口只描述"能干什么"，不带任何格式的类型：storage 用 Paimon 的 FileIO
 * 实现它，Iceberg 侧再把它包成自己的 {@code InputFile}（见
 * {@code rest.iceberg.preview.IcebergPreviewFileIo}）。这与
 * {@code CatalogProtocol} 只暴露三个方法、{@code PreviewTable} 只描述读取能力
 * 是同一个手法：**跨层传递的应该是能力，不是实现类型**。
 *
 * <p><b>为什么不是 {@code InputStream}。</b>普通流没有位置，跳过 N 字节只能真读
 * 并丢弃。对对象存储来说，"读 100 MB 再丢掉"和"直接跳到偏移"是两个数量级的差别。
 *
 * <p><b>线程模型</b>：一个实例对应一次打开的文件句柄，**不是线程安全的**；每次读取
 * 用一个新实例（与 {@code FileIO} 每次新建的理由一致：缓存句柄会把"凭据换过了、
 * 服务端还在用旧钥匙"变成只在长连接上才发作的问题）。
 */
public interface SeekableBytes extends Closeable {

    /** 文件总字节数。 */
    long length();

    /** 当前位置（从 0 开始）。 */
    long position();

    /**
     * 移到指定位置。
     *
     * @param position 目标偏移；允许超过末尾（之后 {@link #read} 返回 -1），
     *                 与 {@code RandomAccessFile#seek} 的语义一致
     */
    void seek(long position) throws IOException;

    /**
     * 读一段字节。
     *
     * @return 实际读到的字节数；到末尾返回 -1
     */
    int read(byte[] target, int offset, int length) throws IOException;
}
