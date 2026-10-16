<template>
  <div class="pc-repeat">
    <div v-for="(row, index) in rows" :key="index" class="pc-repeat-row">
      <el-input
        v-model="row.key"
        size="small"
        placeholder="键"
        class="pc-prop-key"
        @input="emitValue"
      />
      <el-input
        v-model="row.value"
        size="small"
        placeholder="值"
        class="pc-prop-val"
        @input="emitValue"
      />
      <el-button size="small" text type="danger" @click="removeRow(index)">
        <el-icon><Delete /></el-icon>
      </el-button>
    </div>
    <div class="pc-prop-foot">
      <el-button size="small" text type="primary" @click="addRow">
        <el-icon><Plus /></el-icon>
        <span class="pc-repeat-add">添加一项</span>
      </el-button>
      <span v-if="hint" class="pc-dim pc-prop-hint">{{ hint }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

import { mapToRows, rowsToMap } from '@/utils/format.js'

/**
 * 键值对编辑器。`v-model` 绑定一个 `Object<string,string>`。
 *
 * <p>内部维护「行数组」，对外只暴露对象。空键的行在收敛时被丢掉——
 * 用户点「添加一项」后直接提交是常见操作，不应该因此多出一条空属性。
 *
 * <p>回写判断用序列化比较而不是深比较：外部传入的新对象每次引用都不同
 * （服务端返回的是新解析的 JSON），按引用比较会让光标在输入过程中跳位。
 */
const props = defineProps({
  modelValue: { type: Object, default: () => ({}) },
  hint: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue'])

const rows = ref(mapToRows(props.modelValue))
let lastEmitted = JSON.stringify(rowsToMap(rows.value))

watch(
  () => props.modelValue,
  (next) => {
    const serialized = JSON.stringify(next || {})
    if (serialized === lastEmitted) return
    rows.value = mapToRows(next)
    lastEmitted = JSON.stringify(rowsToMap(rows.value))
  },
)

function emitValue() {
  const map = rowsToMap(rows.value)
  lastEmitted = JSON.stringify(map)
  emit('update:modelValue', map)
}

function addRow() {
  rows.value.push({ key: '', value: '' })
}

function removeRow(index) {
  rows.value.splice(index, 1)
  emitValue()
}
</script>

<style scoped>
/* 行的容器与行本身走全局基元（.pc-repeat / .pc-repeat-row）；
 * 本组件只决定两列各占多宽——键值对是两列，与表字段的三列不同。 */
.pc-prop-key {
  flex: 0 0 38%;
}

.pc-prop-val {
  flex: 1;
}

.pc-prop-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pc-prop-hint {
  font-size: 12.5px;
}
</style>
