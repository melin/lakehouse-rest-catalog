<template>
  <div class="pc-page">
    <PageHeader
      title="身份审计"
      subtitle="登录、供给、组同步、映射变更四类事件。排查「这个人为什么进不来」时从这一页开始：这里只留身份相关的事件，比混在所有目录事件里翻要快。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">身份事件（{{ total }}）</span>
        <el-select v-model="scope" size="small" class="pc-ia-filter">
          <el-option v-for="item in SCOPES" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <TipNote>
          四个范围对应服务端的 <code>type</code> 参数（<b>下推</b>，不是本地筛）：
          选了「登录」就只取 <code>principal.login</code>。<br /><br />
          「结果」列取自事件的 attributes：登录事件是 <code>result</code> + <code>reason</code>，
          同步事件是增删了哪些角色。展开行能看到完整属性与请求 ID。<br /><br />
          服务端不按 attributes 过滤（那是 JSON 列，为它加派生列得不偿失），
          所以「只看失败」需要先按范围筛、再往下翻——或者去「事件」页按类别看全部。
        </TipNote>
        <span class="pc-spacer" />
      </div>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="loaded && !total"
        empty-text="还没有身份相关的事件。让一个人用 SSO 登录一次，或者建一条组映射。"
      >
        <el-table :data="rows" size="small" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="pc-ia-detail">
                <dl class="pc-kv">
                  <dt>事件 ID</dt>
                  <dd><CopyText :text="row.eventId" empty-text="—" /></dd>
                  <dt>请求 ID</dt>
                  <dd><CopyText :text="row.requestId" empty-text="—" /></dd>
                  <dt>类型</dt>
                  <dd><span class="pc-mono">{{ row.type }}</span></dd>
                </dl>
                <SectionTitle title="属性" />
                <JsonBlock :value="row.attributes" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }"><TimeText :millis="row.occurredAt" /></template>
          </el-table-column>
          <el-table-column label="事件" width="150">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="typeMeta(row.type).tag">
                {{ typeMeta(row.type).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="主体" min-width="150">
            <template #default="{ row }">
              <RouterLink
                v-if="row.principal"
                class="pc-link pc-mono"
                :to="{ name: 'principals', query: { name: row.principal } }"
              >{{ row.principal }}</RouterLink>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="resultMeta(row).tag">
                {{ resultMeta(row).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="详情" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="pc-mono pc-dim">{{ detailOf(row) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <span />
          </template>
        </el-table>

        <TablePager
          :total="total"
          :page="page"
          :page-size="pageSize"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </ResourceState>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import { consoleApi } from '@/api/console.js'
import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { onConsoleRefresh, useRemotePaging } from '@/composables/index.js'

/**
 * 身份审计页（原型 `#audit`）。
 *
 * <p><b>为什么不复用「事件」页。</b>那一页回答「这个系统发生了什么」，筛选项按
 * catalog / 类别 / 类型组织，适合全局巡检；而这一页回答的是一个具体问题：
 * <b>这个人为什么进不来</b>。问题不同，默认视图就该不同——把四类身份事件收成四个
 * 范围（登录 / 供给 / 组同步 / 映射变更），比让人在下拉里翻 9 个类别要快。
 *
 * <p><b>筛选全部下推服务端。</b>事件是 append-only 且按 id 倒序分页的，
 * 本地筛只筛得到当页——「登录失败」筛出空页并不等于最近没人登录失败。
 *
 * <p><b>「结果」列是翻译，不是重算。</b>登录事件的成败由服务端写进
 * `attributes.result`（`success` / `partial` / `not-provisioned`），这一列只做词表映射；
 * 前端自己判「有没有权限」一定会与 {@code OidcLoginService} 的判定漂移。
 */

/** 范围 → 服务端查询参数。`category` 与 `type` 互斥地用一个：给了 type 就够精确。 */
const SCOPES = [
  { value: 'all', label: '全部身份事件', query: { category: 'PRINCIPAL', type: '' } },
  { value: 'login', label: '登录', query: { category: 'PRINCIPAL', type: 'principal.login' } },
  { value: 'provisioned', label: '供给（JIT 建主体）', query: { category: 'PRINCIPAL', type: 'principal.provisioned' } },
  { value: 'roles', label: '组同步', query: { category: 'PRINCIPAL', type: 'principal.roles_synced' } },
  { value: 'mapping', label: '映射变更', query: { category: 'PRINCIPAL', type: 'group_mapping.changed' } },
]

const scope = ref('all')
const loaded = ref(false)

/** 事件类型的展示词表。只收这一类页面上会出现的四种（其余走事件页）。 */
const TYPES = {
  'principal.login': { label: '登录', tag: 'primary' },
  'principal.provisioned': { label: 'JIT 建主体', tag: 'success' },
  'principal.roles_synced': { label: '组同步', tag: 'info' },
  'group_mapping.changed': { label: '映射变更', tag: 'warning' },
}

const OUTCOMES = {
  success: { label: '成功', tag: 'success' },
  partial: { label: '同步失败', tag: 'warning' },
  'not-provisioned': { label: '未建主体', tag: 'warning' },
  refused: { label: '被拒绝', tag: 'danger' },
}

const scoped = computed(() => SCOPES.find((item) => item.value === scope.value) || SCOPES[0])

async function fetchPage({ page: index, pageSize: size }) {
  const payload = await consoleApi.listEvents({
    category: scoped.value.query.category,
    type: scoped.value.query.type,
    page: index,
    pageSize: size,
  })
  loaded.value = true
  return { items: payload?.events || [], total: payload?.page?.total ?? 0 }
}

const {
  loading, error, rows, total, page, pageSize, load, reload, onPageChange, onSizeChange,
} = useRemotePaging(fetchPage)

function typeMeta(type) {
  return TYPES[type] || { label: type, tag: 'info' }
}

/**
 * 「结果」列。四类事件各有各的「结论」字段：
 *
 * <ul>
 *   <li>登录 → {@code result}（成功 / 同步失败 / 未建主体），失败带 {@code reason}
 *   <li>供给 → 恒为「新建」
 *   <li>同步 → 增删了哪些角色，两个都空说明这次是幂等重跑
 *   <li>映射变更 → {@code action}（created / updated / deleted）
 * </ul>
 *
 * <p>拿不到就回「—」而不是猜：缺字段说明服务端换了形状，猜出来的结论比空白更误导。
 */
function resultMeta(event) {
  const attributes = event.attributes || {}
  if (event.type === 'principal.login') {
    const outcome = OUTCOMES[attributes.result] || { label: attributes.result || '—', tag: 'info' }
    return outcome
  }
  if (event.type === 'principal.roles_synced') {
    const added = Array.isArray(attributes.added) ? attributes.added.length : 0
    const removed = Array.isArray(attributes.removed) ? attributes.removed.length : 0
    if (!added && !removed) {
      return { label: '无变化', tag: 'info' }
    }
    return { label: `+${added} / −${removed}`, tag: 'primary' }
  }
  if (event.type === 'group_mapping.changed') {
    const action = attributes.action
    const labels = { created: '新建', updated: '修改', deleted: '删除' }
    return { label: labels[action] || action || '—', tag: action === 'deleted' ? 'danger' : 'warning' }
  }
  return { label: '新建', tag: 'success' }
}

/** 详情：优先说「为什么」，其次说「动了什么」。 */
function detailOf(event) {
  const attributes = event.attributes || {}
  if (event.type === 'principal.login') {
    const parts = []
    if (attributes.issuer) parts.push(`issuer=${attributes.issuer}`)
    if (typeof attributes.groupsCount === 'number') parts.push(`groups=${attributes.groupsCount}`)
    if (attributes.jitCreated) parts.push('本次新建主体')
    if (attributes.reason) parts.push(`reason=${attributes.reason}`)
    return parts.join('  ') || '—'
  }
  if (event.type === 'principal.roles_synced') {
    const parts = []
    if (attributes.mode) parts.push(`mode=${attributes.mode}`)
    if (attributes.added?.length) parts.push(`+${attributes.added.join(',')}`)
    if (attributes.removed?.length) parts.push(`−${attributes.removed.join(',')}`)
    return parts.join('  ') || '—'
  }
  if (event.type === 'group_mapping.changed') {
    const parts = []
    if (attributes.group) parts.push(`组=${attributes.group}`)
    if (attributes.role) parts.push(`角色=${attributes.role}`)
    if (attributes.enabled === false) parts.push('已停用')
    return parts.join('  ') || '—'
  }
  if (event.type === 'principal.provisioned') {
    return attributes.externalId ? `external_id=${attributes.externalId}` : '首次登录自动创建'
  }
  return ''
}

// 换范围回第一页重取
watch(scope, () => reload())

onConsoleRefresh(() => load())
</script>

<style scoped>
.pc-ia-filter {
  width: 180px;
}

.pc-ia-detail {
  padding: 8px 16px 14px;
}
</style>
