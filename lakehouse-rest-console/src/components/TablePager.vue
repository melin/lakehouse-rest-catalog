<template>
  <!--
    远程分页的统一页脚。

    为什么是一个组件而不是每个页面抄一遍 `<el-pagination>`：**页大小候选、布局、
    总数文案**这三样一旦各写各的，就会出现「这页能选 100、那页最大 50」和
    「这页显示总数、那页不显示」的差异，而它们本该是同一条口径。

    `total` 为 0 时整块不渲染：空态已经有 `ResourceState` 的说明文字，
    再挂一条「共 0 条」的分页器只会让空页面更吵。
  -->
  <div v-if="total > 0" class="pc-pager">
    <el-pagination
      :current-page="page"
      :page-size="pageSize"
      :total="total"
      :page-sizes="PAGE_SIZES"
      layout="total, sizes, prev, pager, next, jumper"
      size="small"
      background
      @current-change="$emit('page-change', $event)"
      @size-change="$emit('size-change', $event)"
    />
  </div>
</template>

<script setup>
/**
 * @prop total    过滤后的总条数（服务端 {@code page.total}）
 * @prop page     当前页，1-based
 * @prop pageSize 本页大小
 * 事件：`page-change` / `size-change`，直接转发给 `useRemotePaging` 的同名方法。
 */
defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
})

defineEmits(['page-change', 'size-change'])

/**
 * 可选的页大小。与服务端 `Paging.MAX_PAGE_SIZE`（200）留出余量：
 * 100 是「一屏能看到的最大量」的经验值，再往上翻页就不如用过滤条件了。
 */
const PAGE_SIZES = [20, 50, 100]
</script>

<style scoped>
.pc-pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 4px 2px;
}

/* 窄屏时右对齐会把分页器挤出容器：改成居中并允许换行 */
@media (max-width: 720px) {
  .pc-pager {
    justify-content: center;
  }

  .pc-pager :deep(.el-pagination) {
    flex-wrap: wrap;
    row-gap: 6px;
  }
}
</style>
