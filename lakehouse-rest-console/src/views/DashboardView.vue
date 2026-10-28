<template>
  <div class="pc-page">
    <PageHeader
      title="控制台概览"
      subtitle="服务端当前的能力与目录清单。数据都实时取自服务端，未在本机缓存。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="pc-stats">
      <div v-for="item in stats" :key="item.label" class="pc-stat">
        <div class="pc-stat-label">{{ item.label }}</div>
        <div class="pc-stat-value">{{ item.value }}</div>
        <div class="pc-stat-foot">{{ item.foot }}</div>
      </div>
    </div>

    <ResourceState :loading="loading" :error="error" />

    <div class="pc-grid">
      <PanelCard title="服务端配置" hint="来自 lakehouse.rest.* 的生效值">
        <dl class="pc-kv">
          <template v-for="row in serviceRows" :key="row.label">
            <dt>{{ row.label }}</dt>
            <dd>
              <el-tag v-if="row.tag" size="small" :type="row.tagType" effect="plain">{{ row.tag }}</el-tag>
              <span v-else class="pc-mono">{{ row.value }}</span>
            </dd>
          </template>
        </dl>
      </PanelCard>

      <PanelCard title="连接自检" hint="逐个探测控制台依赖的端点">
        <div class="pc-stack">
          <div v-for="check in checks" :key="check.label" class="pc-check">
            <el-tag size="small" :type="check.state.type" effect="plain" class="pc-check-tag">
              {{ check.state.text }}
            </el-tag>
            <div class="pc-check-body">
              <div class="pc-check-label">{{ check.label }}</div>
              <div class="pc-mono pc-dim">{{ check.path }}</div>
              <div v-if="check.detail" class="pc-dim pc-check-detail">{{ check.detail }}</div>
            </div>
          </div>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="目录清单" :hint="`共 ${session.state.catalogs.length} 个，详情见「Catalogs」`" flush>
      <template #actions>
        <el-button size="small" text type="primary" @click="$router.push({ name: 'catalogs' })">
          管理
        </el-button>
      </template>
      <ResourceState
        :loading="session.state.loadingCatalogs"
        :error="session.state.catalogsError"
        :empty="!session.state.catalogs.length"
        :empty-text="catalogEmptyText"
      >
        <template #actions>
          <!-- 与「Catalogs」页同一条说明：空列表可能来自可见性过滤，不是没登记 -->
          <p class="pc-empty-hint">
            若服务端登记过 catalog，当前登录主体
            <span class="pc-mono">{{ auth.state.principal || '（未认证）' }}</span>
            可能没有任何 catalog 的赋权——开启授权后列表会按可见性过滤，
            于是「库里有数据、这里为空」是正常结论。把它加入
            <span class="pc-mono">lakehouse.rest.authorization.service-admins</span>，
            或在某个 catalog 上授权后即可看到。
          </p>
          <el-button size="small" type="primary" @click="$router.push({ name: 'catalogs' })">
            去创建
          </el-button>
        </template>
        <el-table :data="session.state.catalogs" size="small">
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="{ name: 'browse', query: { catalog: row.name } }">
                {{ row.name }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="110" />
          <!-- 摘要页只回答「有什么、数据在哪」；存储类型与更新时间属于
               「Catalogs」页的详情，那个页面就在右边的「管理」 -->
          <el-table-column label="仓库位置" min-width="260">
            <template #default="{ row }">
              <span class="pc-mono pc-break">
                {{ row.storageConfigInfo?.allowedLocations?.[0] || row.properties?.warehouse || '—' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <PanelCard
      v-if="configData"
      title="目录发现结果"
      hint="服务端下发给客户端连接的默认参数与覆盖项：GET /v1/config"
      flush
    >
      <div class="pc-config">
        <div>
          <div class="pc-config-label">defaults</div>
          <JsonBlock :value="configData.defaults" max-height="220px" />
        </div>
        <div>
          <div class="pc-config-label">overrides</div>
          <JsonBlock :value="configData.overrides" max-height="220px" />
        </div>
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import { getBaseUrl } from '@/api/client.js'
import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import { onConsoleRefresh, useResource } from '@/composables/index.js'
import { auth } from '@/store/auth.js'
import { session } from '@/store/session.js'
import { credentials } from '@/store/credentials.js'
import { formatNumber } from '@/utils/format.js'

/**
 * 概览页。
 *
 * <p>「连接自检」不是装饰：控制台最常见的故障是地址或令牌配错，
 * 而症状在各页面上是「空列表」，看不出是没数据还是没连上。
 * 这里把三个依赖端点的探测结果直接摆出来，省掉一轮猜。
 */
const directory = useResource(async () => {
  const [principals, principalRoles] = await Promise.all([
    managementApi.listPrincipals(),
    managementApi.listPrincipalRoles(),
  ])
  return {
    principals: principals.principals || [],
    principalRoles: principalRoles.roles || [],
  }
})

const configResource = useResource(async () => {
  const prefix = session.state.selectedCatalog
  return catalogApi.config(prefix || undefined)
})

const loading = computed(() => directory.loading.value || session.state.loadingCatalogs)
const error = computed(() => directory.error.value)
const configData = computed(() => configResource.data.value)

/**
 * 「目录清单」的空态主文案。
 *
 * <p>以前这里写的是「还没有登记任何 catalog」——在开着授权、而调用者没有任何赋权时
 * 那句话是假的：库里明明有行。服务端也不会告诉我们「过滤掉了 N 条」（不可见的
 * catalog 会因此通过条数泄露），所以主文案只断言「可见」范围，成因交给空态里的说明。
 */
const catalogEmptyText = computed(() => '没有可见的 catalog。')

const stats = computed(() => {
  const meta = session.state.meta
  return [
    {
      label: '目录（Catalog）',
      value: formatNumber(session.state.catalogs.length),
      foot: `默认 prefix：${meta?.service?.defaultPrefix || '—'}`,
    },
    {
      label: '主体（Principal）',
      value: directory.data.value ? formatNumber(directory.data.value.principals.length) : '—',
      foot: '持有凭据、连接本服务的身份',
    },
    {
      label: '角色（Principal Role）',
      value: directory.data.value ? formatNumber(directory.data.value.principalRoles.length) : '—',
      foot: '主体与 catalog 角色之间的中间层',
    },
  ]
})

const serviceRows = computed(() => {
  const meta = session.state.meta
  if (!meta) return []
  const service = meta.service || {}
  return [
    {
      label: '鉴权（lakehouse.rest.auth.enabled）',
      tag: service.authEnabled ? '要求令牌' : '关闭',
      tagType: service.authEnabled ? 'success' : 'info',
    },
    {
      // 控制台门禁与数据面鉴权是两件事，分开列：只开着门禁时数据接口仍然匿名可调，
      // 把两者合成一行会让人以为「要求令牌」那一行覆盖了控制台
      label: '控制台门禁（console.required）',
      tag: auth.state.consoleRequired
        ? (auth.gateOnly() ? '要求登录（只挡界面）' : '要求登录')
        : '关闭',
      tagType: auth.state.consoleRequired ? 'success' : 'info',
    },
    {
      label: '授权（lakehouse.rest.authorization.enabled）',
      tag: service.authorizationEnabled ? '启用 RBAC' : '关闭',
      tagType: service.authorizationEnabled ? 'success' : 'info',
    },
    { label: '凭据下发（credential-manager.type）', value: service.credentialManagerType || '—' },
    { label: '存储实现（file-io.type）', value: service.fileIoType || '—' },
    // 能选哪几种存储属于「服务端能力」，是列表项而不是计数——原先它被做成了
    // 一个统计块，与三个数量块并排，让那一排失去了可比性
    {
      label: '可用存储（supported-storage-types）',
      value: (meta.enums?.supportedStorageTypes || []).join(' / ') || '—',
    },
    { label: '自动登记 catalog（auto-create-catalog）', value: String(service.autoCreateCatalog ?? '—') },
    { label: '默认仓库（default-warehouse）', value: service.defaultWarehouse || '—' },
    { label: '路径模板（path-template）', value: service.pathTemplate || '—' },
    {
      label: '分页上限（max-page-size）',
      value: service.maxPageSize === null || service.maxPageSize === undefined
        ? '—'
        : String(service.maxPageSize),
    },
    { label: '服务端版本', value: service.version || '—' },
  ]
})

const checks = computed(() => {
  const meta = session.state.meta
  return [
    {
      label: '控制台元数据',
      path: 'GET /api/console/v1/meta',
      state: session.state.metaError
        ? { type: 'danger', text: '失败' }
        : (meta ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: session.state.metaError || '',
    },
    {
      label: '管理 API',
      path: 'GET /api/management/v1/catalogs',
      state: session.state.catalogsError
        ? { type: 'danger', text: '失败' }
        : (session.state.catalogsLoaded ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: session.state.catalogsError || '',
    },
    {
      label: 'catalog API',
      path: 'GET /v1/config',
      state: configResource.error.value
        ? { type: 'danger', text: '失败' }
        : (configData.value ? { type: 'success', text: '正常' } : { type: 'info', text: '检测中' }),
      detail: configResource.error.value || '',
    },
    {
      label: '访问令牌',
      path: getBaseUrl() || '同源',
      state: credentials.configured
        ? { type: 'success', text: '已配置' }
        : { type: 'info', text: '未配置' },
      detail: credentials.configured ? '' : '服务端开启鉴权时，所有请求都会返回 401',
    },
  ]
})

function reloadAll() {
  session.loadCatalogs().catch(() => {})
  session.loadMeta()
  directory.load()
  configResource.load()
}

onConsoleRefresh(reloadAll)
</script>

<style scoped>

/* 空态里的成因说明（与 Catalogs 页同一段文字，样式各自保留在各自的 scoped 块里） */
.pc-empty-hint {
  max-width: 560px;
  margin: 0 auto 12px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--pc-text-dim);
  text-align: left;
}

.pc-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 14px;
  margin-top: 14px;
}

.pc-check {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.pc-check-tag {
  flex-shrink: 0;
  margin-top: 1px;
}

.pc-check-label {
  font-size: 12.5px;
  font-weight: 600;
}

.pc-check-detail {
  font-size: 12.5px;
  line-height: 1.6;
  margin-top: 2px;
}

.pc-config {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  padding: 14px 16px;
}

.pc-config-label {
  font-size: 12.5px;
  color: var(--pc-text-dim);
  margin-bottom: 6px;
}

@media (max-width: 1080px) {
  .pc-grid,
  .pc-config {
    grid-template-columns: 1fr;
  }
}
</style>
