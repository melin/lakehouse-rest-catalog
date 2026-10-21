<template>
  <div class="lf-bar">
    <div class="lf-group">
      <span class="lf-label">视图</span>
      <el-radio-group :model-value="view" size="small" @update:model-value="$emit('update:view', $event)">
        <el-radio-button value="graph">图</el-radio-button>
        <el-radio-button value="table">邻接表</el-radio-button>
        <el-radio-button value="columns">列映射</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="engines.length > 1" class="lf-group">
      <span class="lf-label">引擎</span>
      <el-select
        :model-value="filters.engines"
        size="small"
        multiple
        collapse-tags
        placeholder="全部"
        class="lf-select"
        @update:model-value="patch({ engines: $event })"
      >
        <el-option v-for="engine in engines" :key="engine" :label="engine" :value="engine" />
      </el-select>
    </div>

    <div class="lf-group">
      <el-switch
        :model-value="filters.showExternal"
        size="small"
        @update:model-value="patch({ showExternal: $event })"
      />
      <span class="lf-label">显示外部节点</span>
    </div>

    <div v-if="hasColumns" class="lf-group">
      <el-switch
        :model-value="filters.onlyWithColumns"
        size="small"
        @update:model-value="patch({ onlyWithColumns: $event })"
      />
      <span class="lf-label">只看有列级映射</span>
    </div>

    <div class="lf-group lf-search">
      <el-input
        :model-value="search"
        size="small"
        clearable
        placeholder="在图里找表名"
        @update:model-value="$emit('update:search', $event)"
      />
    </div>
  </div>
</template>

<script setup>
/**
 * 过滤条：全部受控，**不发请求**（§9.4(4)）。
 *
 * <p>过滤是探索动作——一分钟要做几十次。每次过滤都回服务端既慢又不必要：数据已经在内存里，
 * 图也就几百个节点。因此这个组件只改 `filters`，「隐藏 / 淡化」的分界交给
 * `lineageGraph.applyFilters`（隐藏改布局，淡化不改）。
 *
 * <p>引擎选项从图里现算而不是写死 `SPARK`：将来接 Flink 时这里自动多一项，
 * 而写死的下拉会让新引擎的边永远显示不出来。
 */
defineProps({
  filters: { type: Object, required: true },
  view: { type: String, default: 'graph' },
  engines: { type: Array, default: () => [] },
  hasColumns: { type: Boolean, default: false },
  search: { type: String, default: '' },
})

const emit = defineEmits(['update:filters', 'update:view', 'update:search'])

/** 逐字段合并：父组件的 filters 是同一个 reactive 对象，这里不改它的引用。 */
function patch(changes) {
  emit('update:filters', { ...changes })
}
</script>

<style scoped>
.lf-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin-bottom: 10px;
}

.lf-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.lf-label {
  color: var(--pc-text-dim);
  font-size: 12px;
  white-space: nowrap;
}

.lf-select {
  width: 140px;
}

.lf-search {
  margin-left: auto;
  width: 200px;
}
</style>
