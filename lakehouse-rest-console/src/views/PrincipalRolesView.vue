<template>
  <div class="pc-page">
    <PageHeader
      title="角色（Principal Role）"
      subtitle="角色只是主体分组，不携带任何权限：主体持有角色，授权按 `role:&lt;角色名&gt;` 命中。因此改一个角色，会影响所有持有它的主体。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建角色</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">角色（{{ total }}）</span>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        empty-text="还没有角色。新建一个，再把它授予主体。"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span class="pc-btn-text">新建角色</span>
          </el-button>
        </template>
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column label="属性" min-width="170">
            <template #default="{ row }">
              <span class="pc-mono pc-dim pc-break">{{ inlineMap(row.properties) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }"><TimeText :millis="row.createTimestamp" /></template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <IconAction icon="User" label="成员主体" type="primary" @click="openDetail(row)" />
              <IconAction icon="Edit" label="编辑" @click="openEdit(row)" />
              <IconAction icon="Delete" label="删除" type="danger" @click="confirmDelete(row)" />
            </template>
          </el-table-column>
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

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '新建角色' : `编辑角色：${dialog.name}`"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item v-if="dialog.mode === 'create'" label="名称" required>
          <el-input v-model="dialog.name" placeholder="例如 service_admin、data_reader" />
        </el-form-item>
        <el-form-item v-if="dialog.mode === 'edit'" label="当前版本（currentEntityVersion）">
          <el-input :model-value="String(dialog.entityVersion)" disabled />
        </el-form-item>
      </el-form>
      <SectionTitle title="属性（properties）" />
      <PropertiesEditor v-model="dialog.properties" hint="自由扩展字段，留空即可" />
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          {{ dialog.mode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 成员主体 -->
    <el-drawer v-model="detail.open" :title="`角色：${detail.name}`" size="620px">
      <ResourceState
        :loading="detail.loadingPrincipals"
        :error="detail.errorPrincipals"
        :empty="!detail.principals.length"
        empty-text="还没有主体持有这个角色。授予要按主体操作，去「主体」页面。"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="$router.push({ name: 'principals' })">
            去「主体」页面授予
          </el-button>
        </template>
        <el-table :data="detail.principals" size="small">
          <el-table-column prop="name" label="主体" min-width="160" />
          <el-table-column label="Client ID" min-width="180">
            <template #default="{ row }"><CopyText :text="row.clientId" /></template>
          </el-table-column>
        </el-table>
        <TablePager
          :total="total"
          :page="page"
          :page-size="pageSize"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </ResourceState>
      <p v-if="detail.principals.length" class="pc-drawer-note">
        授予主体要在「主体」页面操作：那条接口按主体定位，这里只做展示。
      </p>
    </el-drawer>
  </div>
</template>

<script setup>
import { reactive } from 'vue'

import CopyText from '@/components/CopyText.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useRemotePaging } from '@/composables/index.js'
import { inlineMap } from '@/utils/format.js'

// 角色表走远程分页：一页一条请求，`total` 是服务端数出来的。
const {
  rows,
  total,
  page,
  pageSize,
  loading,
  error,
  load,
  reload,
  onPageChange,
  onSizeChange,
} = useRemotePaging(({ page: current, pageSize: size }) => managementApi
  .listPrincipalRoles({ page: current, pageSize: size })
  .then((payload) => ({ items: payload.roles || [], total: payload.page?.total ?? 0 })))

const dialog = reactive({
  open: false,
  mode: 'create',
  name: '',
  entityVersion: 0,
  properties: {},
  saving: false,
})

function openCreate() {
  Object.assign(dialog, { open: true, mode: 'create', name: '', properties: {} })
}

function openEdit(row) {
  Object.assign(dialog, {
    open: true,
    mode: 'edit',
    name: row.name,
    entityVersion: row.entityVersion ?? 0,
    properties: { ...(row.properties || {}) },
  })
}

async function save() {
  if (dialog.mode === 'create' && !dialog.name.trim()) {
    notifyError(new Error('请填写角色名称'))
    return
  }
  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await managementApi.createPrincipalRole({
        name: dialog.name.trim(),
        properties: dialog.properties,
      })
      notifySuccess(`已创建角色 ${dialog.name.trim()}`)
    } else {
      await managementApi.updatePrincipalRole(dialog.name, {
        currentEntityVersion: dialog.entityVersion,
        properties: dialog.properties,
      })
      notifySuccess(`已更新角色 ${dialog.name}`)
    }
    dialog.open = false
    reload()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除角色',
    target: `确认删除角色「${row.name}」？`,
    detail: '与该角色相关的绑定会一并移除：所有持有它的主体都会失去这个角色带来的授权。',
  })
  if (!ok) return
  try {
    await managementApi.deletePrincipalRole(row.name)
    notifySuccess(`已删除角色 ${row.name}`)
    // 删完可能正好少一页：回到第一页重取，页码永远落在存在的页上
    reload()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 详情

const detail = reactive({
  open: false,
  name: '',
  principals: [],
  loadingPrincipals: false,
  errorPrincipals: '',
})

async function openDetail(row) {
  detail.name = row.name
  detail.principals = []
  detail.open = true

  detail.loadingPrincipals = true
  detail.errorPrincipals = ''
  try {
    const payload = await managementApi.listPrincipalsOfPrincipalRole(row.name)
    detail.principals = payload.principals || []
  } catch (error) {
    detail.errorPrincipals = describeError(error)
  } finally {
    detail.loadingPrincipals = false
  }
}

onConsoleRefresh(() => load())
</script>
