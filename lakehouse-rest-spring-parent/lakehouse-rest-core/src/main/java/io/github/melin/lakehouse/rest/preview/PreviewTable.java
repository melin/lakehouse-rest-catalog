package io.github.melin.lakehouse.rest.preview;

import java.util.List;
import java.util.Map;

/**
 * 一张可预览的表——**格式无关的那一半**，两个协议面各实现一次。
 *
 * <p><b>为什么是接口而不是在 core 里写一个通用实现。</b>预览的两段式执行（先裁文件、
 * 再筛行）里，"裁文件"这一步只有格式自己的扫描器做得到：Paimon 的
 * {@code DataSplit} 是 {@code dataFiles + deleteFiles} 的装配体，自己按文件路径过滤
 * 会把 delete 文件落下，主键表立刻显示已删除的行。所以接口留在这里（服务端按它编排），
 * 实现留在各自的协议模块（{@code rest.paimon.preview} / 将来的 {@code rest.iceberg.preview}），
 * core 因此不必 import 任何格式 API。
 *
 * <p><b>生命周期。</b>一次预览请求一个实例：Paimon 的 {@link #partitions()} 要在内部
 * 保留"分区键 → 原生分区对象"的映射（{@code BinaryRow}），缓存跨请求既占内存又可能
 * 与新快照错位。用完 {@link #close()}，实现里持有的 reader 也随之释放。
 */
public interface PreviewTable extends AutoCloseable {

    /** 展示用的格式名（{@code Paimon} / {@code Iceberg}），出现在错误信息与事件里。 */
    String formatLabel();

    /** 本次读取的快照 id；表还没有任何快照时为 {@code null}（响应里如实回 {@code null}）。 */
    Long snapshotId();

    /**
     * 表**当前**的最新快照 id；空表为 {@code null}。
     *
     * <p>与 {@link #snapshotId()} 不是一回事：时间旅行时前者是"读的那个"、后者是"现在最新的"。
     * 游标续读要靠这个差来判断"期间表又提交过"（→ 409 重取第一页），
     * 否则第 2 页会在一份已经过期的 manifest 上继续 {@code skip}，行数对不上还不报错。
     */
    Long latestSnapshotId();

    /** 全部列，顺序即表的字段顺序。 */
    List<PreviewColumn> columns();

    /** 分区字段名，顺序即表定义顺序；非分区表返回空列表。 */
    List<String> partitionFields();

    /**
     * 分区字段的变换（{@code identity} / {@code day} / {@code bucket(4, id)} …）。
     *
     * <p>它决定"最新分区"这句话能不能说：{@code bucket} 与 {@code truncate} 之间没有
     * 先后，只有时间族与 identity 才排得出大小。缺省返回空表，表示**全部是 identity**——
     * Paimon 正是如此，而 {@code Map.get} 拿不到时按 identity 处理，两处口径一致。
     *
     * <p>Iceberg 必须实现它（{@code PartitionSpec#fields()} 里有），否则 hidden 分区
     * 的桶号会被当成可排序的数字，默认视图会"锁定最新桶"——一个编出来的概念。
     */
    default Map<String, String> partitionTransforms() {
        return Map.of();
    }

    /**
     * 枚举分区（只读清单，不读数据文件）。
     *
     * <p>非分区表返回空列表；分区表的每一项都要带文件数、大小与最近写入时间——
     * 选择器要靠这三列回答"值不值得看这个分区"，自动锁定也要靠文件数做预算判断。
     */
    List<PartitionSlice> partitions();

    /** 整张表的数据文件数。 */
    int totalFiles();

    /**
     * 给定分区选择下的数据文件数；{@code selection} 为空表示不过滤。
     *
     * <p>供自动分区做预算级联用：它必须便宜（只读清单），否则每级一次全扫会把
     * 首次打开拖到秒级。
     */
    int countFiles(List<PartitionSlice> selection);

    /**
     * 读一页。
     *
     * @param spec 投影、过滤、分区选择、跳过行数与各种上限
     */
    PreviewChunk read(ReadSpec spec);

    @Override
    default void close() {
        // 大多数实现不需要释放资源；需要的那几个自己覆盖
    }

    /**
     * 一次读取的全部参数。
     *
     * <p><b>为什么要 {@code outputCount} 而不是让投影只包含要显示的列。</b>过滤条件可以
     * 引用没被勾选的列（用户只看 3 列，却按第 8 列筛）。这类列必须读出来才有得判，
     * 但不该出现在结果里——服务端因此把它们**追加在投影末尾**，读完之后按
     * {@code outputCount} 切掉。这样"读什么"与"给什么"就解耦了，而切掉这个动作
     * 落在唯一知道顺序的地方（本对象的构造方）。
     *
     * @param projection   要读的列下标（含为过滤而补读的列）
     * @param outputCount  返回给调用方的列数（{@code projection} 的前 N 个）
     * @param filters      已编译的过滤条件，位置是 {@code projection} 里的下标
     * @param partitions   分区选择；空表示不过滤
     * @param splitOffset  从第几个 split 开始（游标续读）
     * @param skipRows     在起始 split 内跳过多少行（该 split 内已消费的行数）
     * @param limit        本页最多返回多少行
     * @param maxFiles     本次最多打开多少个数据文件
     * @param maxBytes     本次最多读多少字节（实现可粗算，超了就以"已读"为准停下）
     * @param deadlineNanos {@link System#nanoTime()} 的截止时刻；超了要抛超时异常
     */
    record ReadSpec(
            int[] projection,
            int outputCount,
            List<PreviewFilters.Compiled> filters,
            List<PartitionSlice> partitions,
            int splitOffset,
            long skipRows,
            int limit,
            int maxFiles,
            long maxBytes,
            long deadlineNanos) {
    }
}
