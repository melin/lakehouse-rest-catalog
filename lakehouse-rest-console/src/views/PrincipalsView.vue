<template>
  <div class="pc-page">
    <PageHeader
      title="主体（Principal）"
      subtitle="主体是访问凭证的持有者，引擎客户端用它连接本服务。这一页的操作只有服务管理员能做，不受任何 catalog 的授权限制。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建主体</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">主体（{{ total }}）</span>
        <el-select v-model="sourceFilter" size="small" class="pc-principal-filter">
          <el-option label="全部来源" value="" />
          <el-option label="本地（LOCAL）" value="LOCAL" />
          <el-option label="IdP 登录（OIDC）" value="OIDC" />
        </el-select>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        empty-text="还没有主体。新建一个，再把角色授予它，它就能访问 catalog 了。"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span class="pc-btn-text">新建主体</span>
          </el-button>
        </template>
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column label="来源" width="100">
            <template #default="{ row }">
              <!-- 来源决定这一行能做什么：OIDC 主体没有 clientId / 密钥，
                   因此不给「轮换」「重置」两个动作——点了也只会得到一份没人用的凭据 -->
              <el-tooltip
                :content="row.source === 'OIDC'
                  ? '首次 SSO 登录时自动创建（JIT），没有 clientId 与密钥'
                  : '在控制台或管理 API 里创建，持有 clientId 与密钥'"
                placement="top"
              >
                <el-tag size="small" effect="plain" :type="row.source === 'OIDC' ? 'warning' : 'info'">
                  {{ row.source === 'OIDC' ? 'IdP 登录' : '本地' }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="Client ID" min-width="190">
            <template #default="{ row }">
              <CopyText v-if="row.clientId" :text="row.clientId" />
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="外部标识" min-width="200">
            <template #default="{ row }">
              <span v-if="row.externalId" class="pc-mono pc-dim pc-break">{{ row.externalId }}</span>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="属性" min-width="150">
            <template #default="{ row }">
              <span class="pc-mono pc-dim pc-break">{{ inlineMap(row.properties) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最后登录" width="170">
            <template #default="{ row }">
              <TimeText v-if="row.lastLoginAt" :millis="row.lastLoginAt" />
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }"><TimeText :millis="row.createTimestamp" /></template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Avatar" label="角色" type="primary" @click="openRoles(row)" />
              <!-- 同步只对 IdP 来的主体有意义：本地主体没有 IDP 来源的成员关系可收敛 -->
              <IconAction
                v-if="row.source === 'OIDC'"
                icon="RefreshRight"
                label="按组同步角色"
                @click="openSync(row)"
              />
              <template v-if="row.source !== 'OIDC'">
                <IconAction icon="Refresh" label="轮换密钥" @click="rotate(row)" />
                <IconAction icon="Key" label="重置 clientId 与密钥" @click="openReset(row)" />
              </template>
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

    <!-- 新建主体 -->
    <el-dialog v-model="createDialog.open" title="新建主体" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <TipAlert type="warning" class="pc-hint">
        <template #title>服务端只保存密钥摘要</template>
        <div>创建成功后，明文密钥只返回一次，请当场保存。</div>
      </TipAlert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="名称" required>
          <el-input v-model="createDialog.name" placeholder="例如 spark-job、flink-prod" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel
              label="要求轮换凭据（credentialRotationRequired）"
              tip="打开后，拿到凭据的客户端需要在首次使用前完成一次轮换。"
            />
          </template>
          <el-switch v-model="createDialog.rotationRequired" />
        </el-form-item>
      </el-form>
      <SectionTitle title="属性（properties）" />
      <PropertiesEditor v-model="createDialog.properties" hint="自由扩展字段，留空即可" />
      <template #footer>
        <el-button @click="createDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重置密钥 -->
    <el-dialog v-model="resetDialog.open" :title="`重置 clientId 与密钥：${resetDialog.name}`" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <p class="pc-dim">
        重置后旧凭据立即失效。两个字段都留空时，由服务端生成一套新的 clientId 与 clientSecret。
      </p>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="Client ID（可选）">
          <el-input v-model="resetDialog.clientId" />
        </el-form-item>
        <el-form-item label="Client Secret（可选）">
          <el-input v-model="resetDialog.clientSecret" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="resetDialog.saving" @click="saveReset">重置</el-button>
      </template>
    </el-dialog>

    <!-- 角色抽屉 -->
    <el-drawer v-model="rolesDrawer.open" :title="`角色：${rolesDrawer.principal}`" size="560px">
      <div class="pc-role-add">
        <el-select v-model="rolesDrawer.toGrant" size="small" placeholder="选择要授予的角色" filterable class="pc-role-select">
          <el-option
            v-for="role in grantableRoles"
            :key="role.name"
            :label="role.name"
            :value="role.name"
          />
        </el-select>
        <el-button size="small" type="primary" :loading="rolesDrawer.saving" :disabled="!rolesDrawer.toGrant" @click="grantRole">
          授予
        </el-button>
      </div>

      <ResourceState
        :loading="rolesDrawer.loading"
        :error="rolesDrawer.error"
        :empty="!rolesDrawer.roles.length"
        empty-text="这个主体还没有角色。用上面的选择器授予一个。"
      >
        <el-table :data="rolesDrawer.roles" size="small">
          <el-table-column prop="name" label="角色" min-width="160" />
          <el-table-column label="" width="70" align="right">
            <template #default="{ row }">
              <IconAction icon="Close" label="移除" type="danger" @click="revokeRole(row.name)" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </el-drawer>

    <!-- 按组同步角色（只对 IdP 来的主体） -->
    <el-dialog
      v-model="syncDialog.open"
      :title="`按组同步角色：${syncDialog.principal}`"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <TipAlert type="warning" class="pc-hint">
        <template #title>组名必须由你给出</template>
        服务端<b>不保存</b>「这个人上次登录时在哪些组」，它只会在下次登录时按令牌重新算，
        所以这里要你把组名填上。同样的组名跑两次是幂等的。<br />
        <b>留空的后果由服务端的 sync-mode 决定</b>：<code>ON_LOGIN</code>（默认）下是
        「只补默认角色、<b>一个都不删</b>」；<code>STRICT</code> 下才是按「不在任何组」重算，
        也就是撤掉 IDP 来源的全部角色。手工授的角色两种情况都不受影响。
        具体是哪一种由服务端判，这里不猜。
      </TipAlert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="IdP 组名">
          <el-input
            v-model="syncDialog.groups"
            type="textarea"
            :rows="3"
            placeholder="用逗号或换行分隔，例如 data-engineers, ops"
          />
        </el-form-item>
      </el-form>
      <p v-if="syncDialog.result" class="pc-dim pc-sync-result">
        新增 <b>{{ syncDialog.result.added.join('、') || '无' }}</b>；
        撤销 <b>{{ syncDialog.result.removed.join('、') || '无' }}</b>
        <!-- 这个分支必须单独说：它与「跑完了没有变化」在界面上长得一样，
             但原因完全不同（服务端刻意什么都没做），回一句「幂等」会让人以为规则生效了 -->
        <template v-if="syncDialog.result.skippedReason">
          <br />
          这次<b>刻意没有改动</b>（{{ syncDialog.result.skippedReason }}）：没带组成员时，
          <code>ON_LOGIN</code> 模式只补默认角色、不删任何角色。
        </template>
      </p>
      <template #footer>
        <el-button @click="syncDialog.open = false">关闭</el-button>
        <el-button type="primary" :loading="syncDialog.saving" @click="saveSync">同步</el-button>
      </template>
    </el-dialog>

    <CredentialDialog
      v-model="credentialDialog.open"
      :principal-name="credentialDialog.principal"
      :credentials="credentialDialog.credentials"
    />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import TipAlert from '@/components/TipAlert.vue'
import CopyText from '@/components/CopyText.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import IconAction from '@/components/IconAction.vue'
import CredentialDialog from '@/components/CredentialDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useRemotePaging, useResource } from '@/composables/index.js'
import { inlineMap } from '@/utils/format.js'

// 主体表走远程分页：`total` 由服务端数，页面不再自己数已经拿到的那一批。
//
// 来源过滤也下推服务端（`?source=LOCAL|OIDC`）：本地筛只筛得到当页，
// 「筛 OIDC 得到 0 条」会被读成「没有人用 SSO 登录过」，而实际上他们在第 3 页。
const sourceFilter = ref('')

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
  .listPrincipals({ source: sourceFilter.value, page: current, pageSize: size })
  .then((payload) => ({ items: payload.principals || [], total: payload.page?.total ?? 0 })))

// 换来源回第一页重取
watch(sourceFilter, () => reload())

// 授予角色用的候选**不分页**：那是一个下拉框，需要「所有能授予的角色」，
// 拿到一页会让另一半角色选不到。角色数量本身是个位数到几十的量级。
const rolesResource = useResource(() => managementApi.listPrincipalRoles(), { immediate: false })
const allRoles = computed(() => rolesResource.data.value?.roles || [])

// ------------------------------------------------------------------ 按组同步角色

/**
 * 手动重算一个 OIDC 主体的 IDP 来源角色。
 *
 * <p><b>为什么组名要人填</b>：服务端不保存「上次登录时这个人在哪些组」——那是一次快照，
 * 存下来就多一份会过期的状态，而它唯一的用途是省掉这一次参数。
 *
 * <p><b>留空不是「什么都不做」</b>，但也不是一律「撤权」——后果由服务端的 sync-mode 决定：
 * `ON_LOGIN`（默认）下服务端会**刻意一个都不删**、只补默认角色，并回一个 `skippedReason`；
 * `STRICT` 下才是按「不在任何组」重算，也就是撤掉 IdP 来源的全部角色。
 * 前端拿不到这个判据（它要读部署配置），所以这里既不静默提交、也不假装知道是哪一种：
 * 空输入走一次红色确认把两种后果都说清楚，回执里再按 `skippedReason` 说出实际发生了什么。
 */
const syncDialog = reactive({
  open: false,
  principal: '',
  groups: '',
  result: null,
  saving: false,
})

function openSync(row) {
  Object.assign(syncDialog, { open: true, principal: row.name, groups: '', result: null })
}

/** 逗号 / 顿号 / 换行都算分隔符：管理员从别处抄过来时格式不统一。 */
function parseGroups(text) {
  return text
    .split(/[,，、\n\r]+/)
    .map((value) => value.trim())
    .filter(Boolean)
}

async function saveSync() {
  const groups = parseGroups(syncDialog.groups)
  if (!groups.length) {
    const ok = await confirmDanger({
      title: '空组提交：后果由 sync-mode 决定',
      target: `把「${syncDialog.principal}」按「一个人都不在任何组」提交？`,
      detail: 'ON_LOGIN（默认）下服务端只补默认角色、一个都不删；STRICT 下会撤掉 IDP 来源的全部角色。'
        + '手工授的角色两种情况都不受影响。',
      confirmText: '提交',
    })
    if (!ok) {
      return
    }
  }
  syncDialog.saving = true
  try {
    syncDialog.result = await managementApi.syncPrincipal(syncDialog.principal, groups)
    const result = syncDialog.result
    if (result.skippedReason) {
      // 「刻意什么都没做」与「跑完了没变化」不是一回事，提示语不能合并
      notifySuccess('服务端按 sync-mode 跳过了这次同步，没有删除任何角色')
    } else if (!result.added?.length && !result.removed?.length) {
      notifySuccess('没有变化（同样的组名跑两次是幂等的）')
    }
    load()
  } catch (cause) {
    notifyError(cause)
  } finally {
    syncDialog.saving = false
  }
}

const credentialDialog = reactive({ open: false, principal: '', credentials: {} })

// ------------------------------------------------------------------ 新建

const createDialog = reactive({
  open: false,
  name: '',
  rotationRequired: false,
  properties: {},
  saving: false,
})

function openCreate() {
  Object.assign(createDialog, { open: true, name: '', rotationRequired: false, properties: {} })
  rolesResource.load()
}

async function saveCreate() {
  const name = createDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写主体名称'))
    return
  }
  createDialog.saving = true
  try {
    const payload = await managementApi.createPrincipal(
      { name, properties: createDialog.properties },
      createDialog.rotationRequired,
    )
    createDialog.open = false
    credentialDialog.principal = name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    reload()
  } catch (error) {
    notifyError(error)
  } finally {
    createDialog.saving = false
  }
}

// ------------------------------------------------------------------ 密钥

async function rotate(row) {
  const ok = await confirmDanger({
    title: '轮换密钥',
    target: `轮换主体「${row.name}」的凭据？`,
    detail: 'clientId 保持不变，clientSecret 会换成新的。用旧密钥的客户端会立即开始鉴权失败。',
    confirmText: '轮换',
  })
  if (!ok) return
  try {
    const payload = await managementApi.rotatePrincipal(row.name)
    credentialDialog.principal = row.name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    reload()
  } catch (error) {
    notifyError(error)
  }
}

const resetDialog = reactive({ open: false, name: '', clientId: '', clientSecret: '', saving: false })

function openReset(row) {
  Object.assign(resetDialog, { open: true, name: row.name, clientId: '', clientSecret: '' })
}

async function saveReset() {
  resetDialog.saving = true
  try {
    const body = {}
    if (resetDialog.clientId.trim()) body.clientId = resetDialog.clientId.trim()
    if (resetDialog.clientSecret.trim()) body.clientSecret = resetDialog.clientSecret.trim()
    const payload = await managementApi.resetPrincipal(resetDialog.name, body)
    resetDialog.open = false
    credentialDialog.principal = resetDialog.name
    credentialDialog.credentials = payload.credentials || {}
    credentialDialog.open = true
    reload()
  } catch (error) {
    notifyError(error)
  } finally {
    resetDialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除主体',
    target: `确认删除主体「${row.name}」？`,
    detail: '该主体持有的角色绑定会一并移除。已经用它的凭据签发的令牌在过期前仍然有效。',
  })
  if (!ok) return
  try {
    await managementApi.deletePrincipal(row.name)
    notifySuccess(`已删除主体 ${row.name}`)
    reload()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 角色绑定

const rolesDrawer = reactive({
  open: false,
  principal: '',
  roles: [],
  loading: false,
  error: '',
  toGrant: '',
  saving: false,
})

const grantableRoles = computed(() => {
  const owned = new Set(rolesDrawer.roles.map((role) => role.name))
  return allRoles.value.filter((role) => !owned.has(role.name))
})

async function loadRoles() {
  rolesDrawer.loading = true
  rolesDrawer.error = ''
  try {
    const payload = await managementApi.listPrincipalRolesOfPrincipal(rolesDrawer.principal)
    rolesDrawer.roles = payload.roles || []
  } catch (error) {
    rolesDrawer.error = describeError(error)
  } finally {
    rolesDrawer.loading = false
  }
}

async function openRoles(row) {
  rolesDrawer.principal = row.name
  rolesDrawer.toGrant = ''
  rolesDrawer.open = true
  rolesResource.load()
  await loadRoles()
}

async function grantRole() {
  rolesDrawer.saving = true
  try {
    await managementApi.grantPrincipalRole(rolesDrawer.principal, { name: rolesDrawer.toGrant })
    notifySuccess(`已授予 ${rolesDrawer.toGrant}`)
    rolesDrawer.toGrant = ''
    await loadRoles()
  } catch (error) {
    notifyError(error)
  } finally {
    rolesDrawer.saving = false
  }
}

async function revokeRole(name) {
  const ok = await confirmDanger({
    title: '移除角色',
    target: `从主体「${rolesDrawer.principal}」移除角色「${name}」？`,
    detail: '该角色所带的 catalog 权限会立即失效。',
    confirmText: '移除',
  })
  if (!ok) return
  try {
    await managementApi.revokePrincipalRole(rolesDrawer.principal, name)
    notifySuccess(`已移除 ${name}`)
    await loadRoles()
  } catch (error) {
    notifyError(error)
  }
}

onConsoleRefresh(() => load())
</script>

<style scoped>
/* 与「主体（N）」同处一行：标题自带 `margin-right: auto`，会把它推到右端，
 * 与其它列表页把工具放右侧的排布一致。定宽是为了切换选项时标题不被挤动。 */
.pc-principal-filter {
  width: 150px;
}

/* 同步结果贴在表单下方，字号小一号——它是「刚才那次操作的结果」，
 * 不属于表单本身的输入。 */
.pc-sync-result {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>
