<template>
  <div class="ll-bar">
    <div class="ll-item"><span class="ll-swatch ll-paimon" />Paimon</div>
    <div class="ll-item"><span class="ll-swatch ll-iceberg" />Iceberg</div>
    <div class="ll-item"><span class="ll-swatch ll-external" />外部节点</div>
    <div class="ll-item"><span class="ll-line" />线宽 ∝ 列映射数</div>
    <div class="ll-item"><span class="ll-line ll-faded" />淡化 = 30 天未更新</div>
    <div class="ll-budget">
      上游 {{ budget.upstream }} 张 · 下游 {{ budget.downstream }} 张 · 已画 {{ counts }} 个节点
      <span v-if="budget.truncated" class="ll-truncated">（已到节点预算，图被截断）</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 图例 + 预算条（§8.6 的 `budget` 在这里显形）。
 *
 * <p>「上游 812 张、已画 4」这句话不能省：没有它，折叠节点上的 `+42` 就是个编出来的数字；
 * 有了它，用户知道眼前是整张图的多少，也就知道「再点几下能不能看全」。
 */
defineProps({
  budget: { type: Object, default: () => ({ upstream: 0, downstream: 0 }) },
  counts: { type: Number, default: 0 },
})
</script>

<style scoped>
.ll-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-top: 8px;
  color: var(--pc-text-dim);
  font-size: 11.5px;
}

.ll-item {
  display: flex;
  align-items: center;
  gap: 5px;
}

.ll-swatch {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}

/* 图例色只读变量，与画布、目录页徽标同源（§9.3） */
.ll-paimon {
  background: var(--fmt-paimon);
}

.ll-iceberg {
  background: var(--fmt-iceberg);
}

.ll-external {
  background: var(--fmt-external);
}

.ll-line {
  width: 18px;
  height: 0;
  border-top: 1.5px solid var(--pc-text-dim);
  display: inline-block;
}

.ll-faded {
  opacity: 0.35;
}

.ll-budget {
  margin-left: auto;
}

.ll-truncated {
  color: #b45309;
}
</style>
