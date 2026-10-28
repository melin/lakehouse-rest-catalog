package io.github.melin.lakehouse.rest.support;

/**
 * 列举仓库目录得到的一个条目。
 *
 * <p><b>为什么需要它，而不是把 Paimon 的 {@code FileStatus} 传出去。</b>
 * {@code org.apache.paimon.fs.FileStatus} 与 {@code FileIO} 是同一条禁令的两端：
 * 协议面模块（Iceberg / Lance）不许 import 任何 Paimon 类型（由各自 POM 的
 * {@code bannedDependencies} 守着）。storage 模块是唯一能与它们共处的地方，
 * 因此它列举完要把结果**翻译成不带格式类型的结构**再交出去——与
 * {@link SeekableBytes} 是同一个理由、同一道边界。
 *
 * <p><b>{@code modificationTime} 可能为 0</b>：对象存储的 LIST 未必返回时间，
 * 本地文件系统才有。调用方要把它当「可能没有」，而不是当成 epoch 0 这个真实时刻。
 *
 * @param name             条目名（不是全路径）
 * @param length           字节数；目录为 0
 * @param modificationTime 修改时刻（epoch 毫秒），不可得时为 0
 * @param directory        是否是目录
 */
public record FileEntry(String name, long length, long modificationTime, boolean directory) {
}
