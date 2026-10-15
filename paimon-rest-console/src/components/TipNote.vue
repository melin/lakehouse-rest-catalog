<script setup>
/**
 * 区块级的补充说明，收进一个问号图标，鼠标悬停才展开。
 *
 * <p>原先这些说明是直接铺开的 `.pc-tip` 段落（两三行起步），一页里有好几处时，
 * 页面被说明文字占满，真正要看的数据反而被挤下去。说明本身不该消失——它解释的是
 * 「为什么是这样」和「边界在哪」，删掉会让人误判——所以改成图标常驻、文字悬停可见。
 *
 * <p>与 {@link FieldLabel} 的分工：那个管**表单字段**的备注（图标跟在字段名后，
 * 用 `#label` 插槽）；这个管**区块**的说明（卡片底部、表格下方、工具行里），
 * 两者共用同一套图标与气泡样式，视觉上是同一种东西。
 *
 * <p>用法：
 *
 * <pre>
 * &lt;TipNote text="各分区之和可能小于仓库实际占用" /&gt;
 * &lt;TipNote&gt;需要 &lt;code&gt;code&lt;/code&gt; 或分段时用默认插槽&lt;/TipNote&gt;
 * </pre>
 *
 * <p>不带内容时不渲染（`v-if` 交给调用方判断更清楚，这里只兜住空 `text`）。
 */
defineProps({
  /** 纯文本说明；富文本用默认插槽。 */
  text: { type: String, default: '' },
  /** 气泡位置，默认在图标上方。 */
  placement: { type: String, default: 'top' },
})
</script>

<template>
  <el-tooltip
    v-if="text || $slots.default"
    :placement="placement"
    effect="dark"
    :show-after="100"
    :hide-after="0"
    popper-class="pc-field-tip-popper"
  >
    <template #content>
      <div class="pc-field-tip"><slot>{{ text }}</slot></div>
    </template>
    <el-icon class="pc-field-label-icon pc-tip-icon" :size="14"><QuestionFilled /></el-icon>
  </el-tooltip>
</template>
