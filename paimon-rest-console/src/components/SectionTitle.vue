<script setup>
/**
 * 表单 / 卡片里的小节标题，替代 `el-divider content-position="left"`。
 *
 * <p>el-divider 的分组标题观感单薄：一条通栏细线在文字两侧断开，文字本身
 * 又淡又小，看上去不像「分组」倒像「掉了一半的字」。这里改成统一的
 * 小节头——竖向强调条锚定视线、标题用正文色加粗、余下的空间用一条
 * 渐隐细线收尾——分组边界一眼可见，也和页面里其它强调元素（主按钮、
 * 活跃页签）共用同一种主色。
 *
 * <p>用法（标题后的问号图标走 {@link TipNote}，与其它说明同一套视觉）：
 *
 * <pre>
 * &lt;SectionTitle title="字段信息（fields）"&gt;
 *   &lt;TipNote&gt;Object 表不需要填写字段…&lt;/TipNote&gt;
 * &lt;/SectionTitle&gt;
 * </pre>
 *
 * <p>不带插槽时只渲染标题。间距由全局 `.pc-section` 的 margin 提供，
 * 调用方不需要再包一层。
 */
import TipNote from '@/components/TipNote.vue'

defineProps({
  /** 小节标题，如「字段信息（fields）」。 */
  title: { type: String, required: true },
  /** 纯文本说明，收进标题后的问号图标；富文本用 `tip` 插槽。 */
  tip: { type: String, default: '' },
})
</script>

<template>
  <div class="pc-section">
    <span class="pc-section-bar" aria-hidden="true" />
    <span class="pc-section-title">{{ title }}</span>
    <TipNote v-if="tip || $slots.tip" placement="top">
      <template v-if="$slots.tip"><slot name="tip" /></template>
      <template v-else>{{ tip }}</template>
    </TipNote>
    <span class="pc-section-line" aria-hidden="true" />
  </div>
</template>
