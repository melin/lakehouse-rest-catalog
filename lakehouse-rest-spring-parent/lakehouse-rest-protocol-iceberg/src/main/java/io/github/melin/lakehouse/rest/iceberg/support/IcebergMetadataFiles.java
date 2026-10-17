package io.github.melin.lakehouse.rest.iceberg.support;

import java.util.Locale;
import java.util.UUID;
import org.apache.iceberg.TableMetadataParser;

/**
 * 推演下一个元数据文件的位置。
 *
 * <p><b>为什么这件事要服务端自己做。</b>Iceberg 的 {@code TableMetadata} 里有一个
 * {@code metadataFileLocation} 字段，但 {@code TableMetadata.newTableMetadata(...)} 与
 * {@code TableMetadata.buildFrom(...).build()} 都**不会**给它赋值（实测两者都是
 * {@code null}）——上游把它留给「拥有这张表」的那一层：{@code HadoopTables} /
 * {@code BaseMetastoreTableOperations} 各自用文件系统目录推，REST 服务端则用
 * 客户端传来的路径推。本服务端就是那个「拥有这张表」的角色，因此必须自己推。
 *
 * <p>形状与 Iceberg 自己的默认一致：{@code <表目录>/metadata/<5 位序号>-<uuid>.metadata.json}。
 *
 * <ul>
 *   <li><b>5 位补零的序号</b>是「这一代是第几份元数据」。它必须从上一份推出来，
 *       不能用随机数或时间戳——人肉排障时 {@code ls metadata/} 是第一步，
 *       而序号不连续会让「中间那一代去哪了」变成一个必须读代码才能回答的问题。</li>
 *   <li><b>uuid 而不是内容摘要</b>：同一份内容重复写两次（客户端超时重试）会得到两个
 *       不同的文件名，于是「先写仓库、后落库」的次序下最多留下一个孤儿文件，
 *       不会覆盖掉别人刚写的那一份。孤儿是无害的，{@code remove_orphan_files} 能清。</li>
 *   <li><b>目录取自上一份文件所在目录</b>（而不是硬拼 {@code <表目录>/metadata}）：
 *       {@code set-location} 改过表位置、或表被 register 自别处的目录时，
 *       元数据文件本来就该跟着上一份待在同一个地方。</li>
 * </ul>
 */
public final class IcebergMetadataFiles {

    /**
     * 表元数据文件的默认扩展名（未压缩）。
     *
     * <p>由上游推出来而不是写死：Iceberg 允许 {@code .gz.metadata.json}，
     * 扩展名的形状归上游定义，写死等于把它的约定抄一份并承诺它不变。
     */
    public static final String DEFAULT_EXTENSION =
            TableMetadataParser.getFileExtension(TableMetadataParser.Codec.NONE);

    private static final String DIRECTORY = "metadata";

    private IcebergMetadataFiles() {
    }

    /**
     * 推演出下一份表元数据文件的位置。
     *
     * @param tableLocation    表的根位置（{@code paimon_table.path_value}）
     * @param previousLocation 当前元数据文件的位置；建表时为 {@code null}
     */
    public static String nextTableMetadataLocation(String tableLocation, String previousLocation) {
        return nextLocation(tableLocation, previousLocation);
    }

    /** 推演出下一份视图元数据文件的位置。视图与表用同一套命名与目录约定。 */
    public static String nextViewMetadataLocation(String viewLocation, String previousLocation) {
        return nextLocation(viewLocation, previousLocation);
    }

    /**
     * 由上一份文件推演出下一份：序号 +1、换一个新 uuid、扩展名沿用。
     *
     * <p><b>扩展名必须走 {@code Codec.fromFileName}，不能把位置直接交给
     * {@code getFileExtension}。</b>上游那个方法是
     * {@code getFileExtension(String codecName)}——它收的是 **codec 名**
     * （{@code "none"} / {@code "gzip"}），不是文件位置。传一个位置进去能编译通过，
     * 却在运行时报 {@code IllegalArgumentException: Invalid codec name: <整个路径>}，
     * 而且**只在第二份元数据之后才炸**：建表那一步 {@code previousLocation} 是
     * {@code null}，压根不走这条分支，于是「建表成功、第一次写入失败」是它唯一的表现
     * （实测：被 {@code IcebergRestCatalogApiTests} 的 commit 用例抓到，
     * 静态校验与授权映射两条守卫都看不出来，因为这不是形状问题）。
     *
     * <p>{@code fromFileName} 顺带认得住历史的 {@code .metadata.json.gz} 写法，
     * 因此沿用扩展名这件事对压缩部署仍然成立——把一份压缩元数据换成未压缩会让
     * 「这一代之后为什么体积涨了十倍」变成一个没有答案的问题。
     */
    private static String nextLocation(String location, String previousLocation) {
        String base = location;
        int nextVersion = 1;
        String extension = DEFAULT_EXTENSION;
        if (previousLocation != null && !previousLocation.isBlank()) {
            int slash = previousLocation.lastIndexOf('/');
            if (slash > 0) {
                base = previousLocation.substring(0, slash);
            }
            String fileName = slash < 0 ? previousLocation : previousLocation.substring(slash + 1);
            nextVersion = versionOf(fileName) + 1;
            extension = TableMetadataParser.getFileExtension(TableMetadataParser.Codec.fromFileName(fileName));
        }
        return directoryOf(base) + "/" + DIRECTORY + "/"
                + String.format(Locale.ROOT, "%05d", nextVersion)
                + "-" + UUID.randomUUID() + extension;
    }

    /**
     * 从文件名里读序号：{@code 00003-....metadata.json} → {@code 3}。
     *
     * <p>读不出来时返回 0，于是下一份的序号是 1。这种情况只会发生在「当前位置的
     * 文件名不是本服务端写的」——例如 {@code register} 登记了一张别人的表，
     * 客户端的命名习惯与这里不同。此时从 1 重新开始是安全的：文件名里带 uuid，
     * 撞名不可能发生，而序号本身不参与任何判定。
     */
    private static int versionOf(String fileName) {
        int end = 0;
        while (end < fileName.length() && Character.isDigit(fileName.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(fileName.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 去掉位置末尾的斜杠，避免拼出 {@code //metadata/}。 */
    private static String directoryOf(String location) {
        String trimmed = location.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
