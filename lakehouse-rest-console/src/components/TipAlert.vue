<script setup>
import { computed, useSlots } from 'vue'

import TipNote from '@/components/TipNote.vue'

/**
 * 一行高的提示条：标题常驻，**说明文字收进标题旁的问号**，鼠标悬停才展开。
 *
 * <p>为什么要有它：控制台里 `el-alert` 的典型形态是「一句结论 + 一段解释」，
 * 解释往往三五行（为什么这样、边界在哪、出问题怎么办）。一页里有两三条时，
 * 页面被这些段落占满，真正要看的数据被挤到折叠线以下——而删掉解释又会让人误判。
 * 于是按 `TipNote` 的同一套做法处理：**图标常驻、文字悬停可见**。
 *
 * <p><b>只用于提示性告警（info / warning）。</b>错误（error）不在这里收——
 * 「保存失败」「凭据解不开」这类信息必须一眼看到，藏进气泡等于让人多点一次
 * 才知道出了什么事；`verify-console` 有一条守卫盯着这件事。
 *
 * <p>用法与 `el-alert` 完全一致（`#title` 插槽或 `title` 属性 + 正文），
 * 因此替换只是一个标签名：
 *
 * <pre>
 * &lt;TipAlert type="info" class="pc-hint"&gt;
 *   &lt;template #title&gt;保存即整份替换&lt;/template&gt;
 *   这里提交的是&lt;strong&gt;完整策略&lt;/strong&gt;……
 * &lt;/TipAlert&gt;
 * </pre>
 *
 * <p>两种正文**不要**用它，仍写 `el-alert`：
 * ① 带按钮/链接/复制控件的（气泡里点不到，等于把动作弄丢）；
 * ② 本身是被阅读对象的结构化块（例如一段 SQL 文本、一组表格）。
 * 这两种情况下正文不是「解释」，而是内容本身。
 *
 * <p>无障碍上有一个已知取舍：气泡对键盘与读屏都不可达。这与 `TipNote` / `FieldLabel`
 * 一致（全站同一套交互），换来的是页面可用性；要读全部说明的场景请走文档。
 */
const props = defineProps({
  /** 与 `el-alert` 同名的语义类型。只应传 info / warning。 */
  type: { type: String, default: 'info' },
  /** 标题（也可用 `#title` 插槽，支持插值）。 */
  title: { type: String, default: '' },
})

const slots = useSlots()

/**
 * 有没有正文。判断「有没有传默认插槽」而不是判断文本是否为空：
 * 正文里可能是插值或组件（例如一段动态拼接的说明），编译期看不出来。
 * 没有正文时只渲染标题，不出现一个点了没反应的空问号。
 */
const hasBody = computed(() => Boolean(slots.default))
</script>

<template>
  <el-alert v-bind="$attrs" :type="props.type" :closable="false" show-icon class="pc-tip-alert">
    <template #title>
      <span class="pc-tip-alert-head">
        <span class="pc-tip-alert-title">{{ props.title }}<slot name="title" /></span>
        <TipNote v-if="hasBody"><slot /></TipNote>
      </span>
    </template>
  </el-alert>
</template>
