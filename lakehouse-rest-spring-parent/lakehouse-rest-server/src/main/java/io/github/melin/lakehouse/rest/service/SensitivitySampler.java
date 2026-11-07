package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.ColumnValues;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.SamplingMode;
import io.github.melin.lakehouse.rest.scan.SensitivityMasking;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 采样读的编排：把「一次读若干行」拆成「每一列一组值 + 一份打码样本」
 * （设计见 {@code docs/sensitive-data-design.md} §5.4 / §6.2）。
 *
 * <p><b>本类不拥有读能力。</b>读走 {@link TablePreviewService#sample}——同一套
 * {@code PreviewTableFactory} 分派、同一份 {@link io.github.melin.lakehouse.rest.preview.PreviewLimits}
 * 预算、同一个并发池。这里只做两件与识别有关的事：
 * <ol>
 *   <li><b>按列转置</b>：读回来的是一行一行的值，而 D3 要按列算命中率
 *       （一列 2000 个值里有几个像身份证），转置是必须的一步；</li>
 *   <li><b>当场打码</b>：转置时顺手把每列前 5 个非空值的打码样本留下。
 *       这一步刻意放在**读之后、返回之前**——原值只要离开本方法就不再有任何
 *       保护，而打码是那条不变量的唯一执行点（§5.4）。</li>
 * </ol>
 *
 * <p><b>读失败不抛给调用方判断。</b>{@link #sample} 让异常往上抛，由扫描服务逐表捕获并
 * 计入 {@code tables_failed}：一张表读不出来（权限、存储抖动、格式不支持）不该让整轮
 * 扫描失败，但也不能算成「扫过、没命中」——那是把「漏扫」说成「干净」（§5.6.5）。
 *
 * <p><b>{@code samplingMode} 恒为 {@code HEAD}。</b>设计稿 §6.2 的优先级是
 * {@code TABLESAMPLE} 优先、不可用时降级 {@code HEAD} 并标出。当前读通道（预览）
 * 是按文件顺序读前 N 行，没有抽样算子，因此如实标 {@code HEAD}——
 * 样本偏斜的风险由 {@code SensitivityScorer} 的样本量打折与「值层只占 0.40」
 * 共同承担，而不是靠一个写不出来却看上去更聪明的模式名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivitySampler {

    private final TablePreviewService previews;

    /**
     * 采一张表的样。
     *
     * @param catalog   catalog 名
     * @param database  库名
     * @param table     表名
     * @param columns   要采的列（来自 {@code schema_doc}，**只读待判列**）
     * @param rows      最多读多少行
     */
    public TableSample sample(String catalog, String database, String table,
                              List<String> columns, int rows) {
        PreviewTarget target = previews.resolve(catalog, database, table);
        TablePreviewService.Sample sample = previews.sample(target, columns, rows);
        int width = sample.columns().size();
        Map<String, ColumnValues> values = new LinkedHashMap<>();
        Map<String, List<String>> masked = new LinkedHashMap<>();
        for (int index = 0; index < width; index++) {
            String name = sample.columns().get(index);
            List<String> column = new ArrayList<>(sample.rows().size());
            for (List<Object> row : sample.rows()) {
                Object cell = row != null && index < row.size() ? row.get(index) : null;
                // null 保留：值层的分母是「非空行」，在入口滤掉会让一列
                // 「90% 是 NULL、非空部分全是身份证」算出虚高的命中率
                column.add(cell == null ? null : String.valueOf(cell));
            }
            values.put(name, new ColumnValues(column));
            masked.put(name, SensitivityMasking.maskSamples(column));
        }
        log.debug("sampled {}.{}.{}: {} rows x {} columns", catalog, database, table,
                sample.rowsRead(), width);
        return new TableSample(values, masked, SamplingMode.HEAD.name(), sample.rowsRead());
    }

    /**
     * 一张表的采样结果。
     *
     * @param values        列名 → 该列的采样值（**原值，只在内存里活一次**）
     * @param maskedSamples 列名 → 该列的打码样本（至多 5 个，落库只用这一份）
     * @param samplingMode  采样方式（当前恒 {@code HEAD}）
     * @param rowsRead      真正读到的行数（样本量打折与「这次采了多少」都看它）
     */
    public record TableSample(Map<String, ColumnValues> values,
                              Map<String, List<String>> maskedSamples,
                              String samplingMode,
                              long rowsRead) {

        /** 没采到样（或这一轮不采样）：每一列都取不到值，探测器只跑 D1/D2。 */
        public static TableSample none() {
            return new TableSample(Map.of(), Map.of(), SamplingMode.HEAD.name(), 0);
        }

        /** 某一列的采样值；没采到返回 {@link ColumnValues#none()}（不是 null）。 */
        public ColumnValues values(String column) {
            return values.getOrDefault(column, ColumnValues.none());
        }

        /** 某一列的打码样本；没有就是空列表。 */
        public List<String> maskedSamples(String column) {
            return maskedSamples.getOrDefault(column, List.of());
        }
    }
}
