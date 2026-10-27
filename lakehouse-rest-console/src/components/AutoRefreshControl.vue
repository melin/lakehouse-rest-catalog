<template>
  <!--
    列表页的「定时刷新」开关 + 节拍。放在页头动作区、刷新按钮左边：
    它和「刷新」是同一件事的自动与手动两种模式，放在一起才不会
    被读成「另一项全局设置」。
  -->
  <div class="pc-auto-refresh">
    <el-switch
      v-model="on"
      size="small"
      :aria-label="'定时刷新'"
    />
    <el-tooltip placement="bottom" :content="hint" :show-after="300">
      <span class="pc-auto-refresh-label" :class="{ 'is-off': !on }">定时刷新</span>
    </el-tooltip>
    <el-select
      v-model="interval"
      size="small"
      class="pc-auto-refresh-interval"
      :disabled="!on"
      :aria-label="'刷新间隔'"
    >
      <el-option
        v-for="option in AUTO_REFRESH_INTERVALS"
        :key="option"
        :label="`${option} 秒`"
        :value="option"
      />
    </el-select>
    <!--
      静默刷新失败时的角标。**不抢眼、但必须在**：定时刷新失败时表格不会变空
      （那比不刷新更糟），于是页面看起来一切正常、数据其实停在几分钟前——
      这一颗图标是唯一的提示，悬浮才给原因。
    -->
    <el-tooltip
      v-if="staleError"
      placement="bottom"
      :content="`定时刷新失败：${staleError}。表格里是上一次成功取到的数据。`"
    >
      <el-icon class="pc-auto-refresh-warn"><WarningFilled /></el-icon>
    </el-tooltip>
  </div>
</template>

<script setup>
import { computed } from 'vue'

import { AUTO_REFRESH_INTERVALS, AUTO_REFRESH_DEFAULT } from '@/utils/autoRefresh.js'

/**
 * 定时刷新的控件（开关 + 节拍）。
 *
 * <p>**状态不放在这里**，`enabled` / `seconds` 由 `useAutoRefresh` 持有
 * （它同时负责定时器、落盘与后台暂停）。控件只管两件事：把值显示对、把用户的选择发出去。
 * 复制一份内部状态会立刻出现「开关在控件里、定时器在组合式里」两份真相，
 * 而它们不同步的表现是「关了还在刷」——正是这个功能最不该犯的错。
 */
const props = defineProps({
  enabled: { type: Boolean, default: AUTO_REFRESH_DEFAULT.enabled },
  seconds: { type: Number, default: AUTO_REFRESH_DEFAULT.seconds },
  /** 静默刷新最近一次失败的原因；空串表示一切正常。 */
  staleError: { type: String, default: '' },
  /** 最近一次自动刷新的时刻（毫秒）。0 表示还没跑过一拍。 */
  lastAt: { type: Number, default: 0 },
})

const emit = defineEmits(['update:enabled', 'update:seconds'])

const on = computed({
  get: () => props.enabled,
  set: (value) => emit('update:enabled', value),
})

const interval = computed({
  get: () => props.seconds,
  set: (value) => emit('update:seconds', value),
})

/** 悬浮说明：写清「多久一次、什么时候不刷、上次什么时候刷的」。 */
const hint = computed(() => {
  const head = on.value
    ? `每 ${props.seconds} 秒自动重取一次。切到别的标签页时暂停，切回来会立刻补一次。`
    : '定时刷新已关闭，用左边的「刷新」按钮手动更新。'
  return props.lastAt
    ? `${head}最近一次自动刷新：${new Date(props.lastAt).toLocaleTimeString('zh-CN', { hour12: false })}`
    : head
})
</script>

<style scoped>
/* 一行三件：开关、文字、节拍。用 inline-flex 而不是 flex：
   它会跟着页头动作区里的按钮排在同一行，占满宽度会把「新建」挤到下一行 */
.pc-auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pc-auto-refresh-label {
  font-size: 13px;
  color: var(--pc-text);
  cursor: default;
  white-space: nowrap;
}

/* 关掉时文字变灰：这里不加「已关闭」之类的字，状态由开关本身表达 */
.pc-auto-refresh-label.is-off {
  color: var(--pc-text-dim);
}

.pc-auto-refresh-interval {
  width: 82px;
}

.pc-auto-refresh-warn {
  color: var(--el-color-warning);
  font-size: 15px;
}
</style>
