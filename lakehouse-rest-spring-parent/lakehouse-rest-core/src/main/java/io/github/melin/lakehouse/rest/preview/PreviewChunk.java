package io.github.melin.lakehouse.rest.preview;

import java.util.List;

/**
 * 一次读取的产物：行 + 这一页的真实代价 + 下一页从哪儿接着读。
 *
 * <p>四个计数分开而不是只回 {@code rows.size()}，是因为它们回答不同的问题：
 * {@code rowsRead} 与 {@code rows.size()} 的差是"过滤器真的生效了"的唯一证据；
 * {@code filesRead} 说明采样有多激进；{@code bytesRead} 说明对象存储上真的读了多少；
 * {@code exhausted} 说明"没有更多了"还是"这一页满了"——前者决定要不要给
 * 「加载更多」按钮，后者只是本次的限流。
 *
 * <p><b>{@code nextSplitOffset} / {@code nextSplitRows} 是游标的原料。</b>预览是流式读，
 * 没有便宜的"跳到第 N 行"，因此下一页必须从"上一个 split 的第几行"接着来。
 * 这两个值是**读完之后**的位置（含被过滤掉、被 skip 掉的行），不是返回行数——
 * 用返回行数续读会在有过滤条件时跳错位置。
 *
 * @param rows           本次返回的行（已按投影裁过列，已按过滤条件筛过）
 * @param rowsRead       本次真正从文件里读出来的行数（含被过滤掉的）
 * @param bytesRead      本次读过文件的字节数（格式给不出时为 0）
 * @param filesRead      本次打开的数据文件数
 * @param exhausted      是否已经读到作用域末尾（没有更多行了）
 * @param nextSplitOffset 下一页从这个 split 开始；{@code exhausted} 时无意义
 * @param nextSplitRows  在该 split 内已经消费掉的行数（下一页要跳过这么多）
 */
public record PreviewChunk(
        List<List<Object>> rows,
        long rowsRead,
        long bytesRead,
        int filesRead,
        boolean exhausted,
        int nextSplitOffset,
        long nextSplitRows) {
}
