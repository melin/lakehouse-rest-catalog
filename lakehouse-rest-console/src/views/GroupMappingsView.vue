<template>
  <div class="pc-page">
    <PageHeader
      title="组映射（IdP 组 → 角色）"
      subtitle="SSO 登录时，服务端按这张表把「这个人属于 IdP 的哪些组」翻译成「他该有哪些角色」。映射只增删 granted_by=IDP 的成员关系——管理员手工授的角色永远不参与差集。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建映射</span>
        </el-button>
      </template>
    </PageHeader>

    <TipAlert type="info" class="pc-hint">
      <template #title>删除映射 ≠ 立刻撤权</template>
      删掉一条映射表达的是「以后别再按这条规则授权」，已经同步出去的成员关系<b>不会</b>被回收——
      要立刻生效，还需要在「主体」页对该主体点一次「同步角色」。两个动作的确认强度不一样，
      所以没有合成一个按钮。
    </TipAlert>

    <div class="pc-gm-grid">
      <PanelCard flush>
        <div class="pc-list-head">
          <span class="pc-list-title">映射（{{ total }}）</span>
          <el-input
            v-model="filter.group"
            size="small"
            class="pc-gm-filter"
            placeholder="组名包含…"
            clearable
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <span class="pc-spacer" />
        </div>

        <ResourceState
          :loading="loading"
          :error="error"
          :empty="!rows.length"
          empty-text="还没有任何组映射。建一条之后，属于该 IdP 组的人下次登录就会自动拿到对应角色。"
        >
          <template #actions>
            <el-button size="small" type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建映射</span>
            </el-button>
          </template>
          <el-table :data="rows" size="small" row-key="id">
            <el-table-column label="IdP 组" min-width="200">
              <template #default="{ row }">
                <span class="pc-mono pc-break">{{ row.group }}</span>
                <!-- 精确匹配是同步时的语义：大小写、空格、Keycloak 的 / 前缀都算差异 -->
                <el-tooltip
                  content="同步时按这个字符串精确匹配（区分大小写、不 trim）。Keycloak 开了 full group path 时组名带 / 前缀。"
                  placement="top"
                >
                  <el-icon class="pc-gm-info"><InfoFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="角色" min-width="150">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.role }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="170">
              <template #default="{ row }"><TimeText :millis="row.lastUpdateTimestamp" /></template>
            </el-table-column>
            <el-table-column label="操作" width="86" fixed="right">
              <template #default="{ row }">
                <IconAction icon="Edit" label="编辑" type="primary" @click="openEdit(row)" />
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

      <PanelCard>
        <SectionTitle title="同步预览" />
        <p class="pc-gm-lede">
          给一组 IdP 组名，算一遍「这个人最终会拿到哪些角色」。<b>不写库</b>，
          而且与登录时调用的是<b>同一段计算</b>——所以这里看到的就是登录后会发生的事。
        </p>
        <el-input
          v-model="previewInput"
          type="textarea"
          :rows="3"
          size="small"
          placeholder="一组组名，用逗号或换行分隔，例如：data-engineers, ops"
        />
        <div class="pc-gm-preview-actions">
          <el-button size="small" type="primary" :loading="previewing" @click="runPreview">
            算一下
          </el-button>
          <span class="pc-dim pc-gm-tip">区分大小写、不 trim，与同步一致</span>
        </div>

        <el-alert v-if="previewError" type="error" :closable="false" show-icon class="pc-hint">
          <template #title>预览失败</template>
          <div>{{ previewError }}</div>
        </el-alert>

        <div v-if="preview" class="pc-gm-preview">
          <div class="pc-gm-preview-row">
            <span class="pc-gm-preview-label">会得到的角色</span>
            <span v-if="!preview.desiredRoles?.length" class="pc-dim">无</span>
            <template v-else>
              <el-tag
                v-for="role in preview.desiredRoles"
                :key="role"
                size="small"
                effect="dark"
                type="success"
                class="pc-gm-chip"
              >{{ role }}</el-tag>
            </template>
          </div>
          <div v-if="preview.unmappedGroups?.length" class="pc-gm-preview-row">
            <span class="pc-gm-preview-label">没配映射的组</span>
            <el-tag
              v-for="group in preview.unmappedGroups"
              :key="group"
              size="small"
              effect="plain"
              type="warning"
              class="pc-gm-chip"
            >{{ group }}</el-tag>
          </div>
          <div v-if="preview.unknownRoles?.length" class="pc-gm-preview-row">
            <span class="pc-gm-preview-label">映射指向了不存在的角色</span>
            <el-tag
              v-for="role in preview.unknownRoles"
              :key="role"
              size="small"
              effect="plain"
              type="danger"
              class="pc-gm-chip"
            >{{ role }}</el-tag>
          </div>
          <div v-if="preview.warnings?.length" class="pc-gm-preview-row">
            <span class="pc-gm-preview-label">提示</span>
            <ul class="pc-gm-warnings">
              <li v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</li>
            </ul>
          </div>
          <p class="pc-gm-note">
            手工授的角色不在此列：差集只由 <code>granted_by=IDP</code> 的行构成，
            IdP 里少一个组不会把手工会员一起删掉。
          </p>
        </div>

        <TipAlert v-if="syncModeRisk" type="warning" class="pc-hint">
          <template #title>当前同步时机是 {{ config.syncMode }}</template>
          {{ syncModeRisk }}
        </TipAlert>
      </PanelCard>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.editing ? '编辑组映射' : '新建组映射'"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="IdP 组名" required>
          <el-input
            v-model="dialog.group"
            :disabled="dialog.editing"
            placeholder="例如 data-engineers"
          />
          <div class="pc-gm-field-note">
            同步时按这个字符串<b>精确匹配</b>。Keycloak 开了 full group path 时会带 <code>/</code> 前缀，
            Cognito 的组来自 <code>cognito:groups</code> 且区分大小写。
          </div>
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="dialog.role" filterable placeholder="选择 principal role" class="pc-full">
            <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
          </el-select>
          <div class="pc-gm-field-note">
            角色不存在时保存会被拦下（而不是留到同步时静默跳过）——那时候没人看着日志。
          </div>
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel
              label="启用"
              tip="停用只是不再生效，规则留在表里。配错时第一反应是删，但删掉之后「当时是怎么配的」就没了。"
            />
          </template>
          <el-switch v-model="dialog.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { InfoFilled, Plus, Refresh, Search } from '@element-plus/icons-vue'

import { describeError } from '@/api/client.js'
import { managementApi } from '@/api/management.js'
import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import {
  confirmDanger,
  notifyError,
  notifySuccess,
  onConsoleRefresh,
  useRemotePaging,
  useResource,
} from '@/composables/index.js'

/**
 * 组映射页（原型 `#groups`）。
 *
 * <p>这一页是整条身份链路上**唯一会改变别人权限**的地方，因此三处刻意做得比别处啰嗦：
 *
 * <ol>
 *   <li><b>保存前先预览</b>：映射写错的代价不对称——多给一个角色是「他看到不该看的数据」，
 *       少给一个是「他打不开该打开的表」，两者都不报错。预览与登录同步共用同一段计算，
 *       所以「预览说两个角色」就一定真的是两个。
 *   <li><b>危险映射要二次确认</b>：把一组人映射到「服务管理员手里的角色」，等于把
 *       「谁能进 IdP 的这个组」变成「谁能管理整个服务」。判据是<b>持有关系</b>而不是名字——
 *       名字像不像 admin 是习惯，谁真的持有才是事实。服务端回 400 附带确认要求，
 *       这里弹红色确认后带 `confirmAdminMapping=true` 重试。
 *   <li><b>删除不回收</b>：删规则与撤权是两件事，界面上说清楚，不去猜用户想的是哪件。
 */

const filter = reactive({ group: '' })

async function fetchPage({ page: index, pageSize: size }) {
  const payload = await managementApi.listGroupMappings({
    group: filter.group,
    page: index,
    pageSize: size,
  })
  return { items: payload?.mappings || [], total: payload?.page?.total ?? 0 }
}

const {
  loading, error, rows, total, page, pageSize, load, reload, onPageChange, onSizeChange,
} = useRemotePaging(fetchPage)

// 关键字变化回第一页重取：本地筛只筛得到当页，而「筛不到」会被读成「没有这条映射」
watch(() => filter.group, () => reload())

/** 角色候选：映射只能指向已存在的角色，因此下拉直接读管理面。 */
const { data: rolesData } = useResource(() => managementApi.listPrincipalRoles({ pageSize: 200 }))
const roleOptions = computed(() => (rolesData.value?.roles || []).map((role) => role.name))

/** 配置只为一件事：把当前的 sync-mode 风险说出来（它与映射是联动关系）。 */
const { data: identityConfig } = useResource(() => managementApi.getIdentityProvider())
const config = computed(() => identityConfig.value || {})

const syncModeRisk = computed(() => {
  if (config.value.syncMode === 'STRICT') {
    return '严格模式下，令牌里没有 groups 时会被当成「不在任何组」，清空该主体 IDP 来源的全部角色。'
  }
  if (config.value.syncMode === 'OFF') {
    return '同步已关闭：这张表当前不生效，角色只能手工授。'
  }
  return ''
})

const dialog = reactive({
  open: false,
  editing: false,
  id: '',
  group: '',
  role: '',
  enabled: true,
  version: null,
  saving: false,
})

function openCreate() {
  Object.assign(dialog, {
    open: true, editing: false, id: '', group: '', role: '', enabled: true, version: null,
  })
}

function openEdit(row) {
  Object.assign(dialog, {
    open: true,
    editing: true,
    id: row.id,
    group: row.group,
    role: row.role,
    enabled: row.enabled,
    version: row.entityVersion,
  })
}

/**
 * 保存。**危险映射的重试逻辑就在这里**：服务端 400 且要求确认时弹红色确认，
 * 确认后带 `confirmAdminMapping=true` 再发一次。
 *
 * <p>不在前端自己判断「这个角色是不是管理员角色」：那个判据要读
 * `authorization.service-admins` 与角色的持有关系，前端拿不到，猜一次就会漂移。
 */
async function save() {
  if (!dialog.group.trim()) {
    notifyError(new Error('组名不能为空'))
    return
  }
  if (!dialog.role) {
    notifyError(new Error('请选择角色'))
    return
  }
  dialog.saving = true
  try {
    if (dialog.editing) {
      await managementApi.updateGroupMapping(dialog.id, {
        currentEntityVersion: dialog.version,
        role: dialog.role,
        enabled: dialog.enabled,
      })
    } else {
      await managementApi.createGroupMapping({
        group: dialog.group.trim(),
        role: dialog.role,
        enabled: dialog.enabled,
      })
    }
    notifySuccess(dialog.editing ? '映射已更新' : '映射已创建')
    dialog.open = false
    load()
  } catch (cause) {
    if (await confirmAdminMapping(cause)) {
      await saveWithAdminConfirmation()
    } else if (cause?.status === 409) {
      notifyError(new Error('这条映射已经被别人改过或已存在，刷新后再试'))
    } else {
      notifyError(cause)
    }
  } finally {
    dialog.saving = false
  }
}

/** 服务端的「危险映射」提示：把一组人映射到管理员手里的角色。 */
async function confirmAdminMapping(cause) {
  const message = String(cause?.message || '')
  if (!message.includes('confirmAdminMapping')) {
    return false
  }
  try {
    await ElMessageBox.confirm(
      `这条映射会把「谁能进 IdP 的这个组」变成「谁能管理整个服务」。\n\n${message}`,
      '这是危险映射',
      {
        type: 'error',
        confirmButtonText: '我确认，继续保存',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
    return true
  } catch {
    return false
  }
}

async function saveWithAdminConfirmation() {
  if (dialog.editing) {
    await managementApi.updateGroupMapping(dialog.id, {
      currentEntityVersion: dialog.version,
      role: dialog.role,
      enabled: dialog.enabled,
      confirmAdminMapping: true,
    })
  } else {
    await managementApi.createGroupMapping({
      group: dialog.group.trim(),
      role: dialog.role,
      enabled: dialog.enabled,
      confirmAdminMapping: true,
    })
  }
  notifySuccess('映射已保存（已确认危险映射）')
  dialog.open = false
  load()
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除这条组映射？',
    target: `${row.group} → ${row.role}`,
    detail: '删除只影响以后的同步，已经授出去的成员关系不会被回收。',
  })
  if (!ok) {
    return
  }
  try {
    await managementApi.deleteGroupMapping(row.id)
    notifySuccess('映射已删除')
    load()
  } catch (cause) {
    notifyError(cause)
  }
}

// ------------------------------------------------------------------ 预览

const previewInput = ref('')
const previewing = ref(false)
const previewError = ref('')
const preview = ref(null)

/** 逗号 / 顿号 / 换行都当分隔符：管理员从别处抄过来时格式不统一。 */
function parseGroups(text) {
  return text
    .split(/[,，、\n\r]+/)
    .map((value) => value.trim())
    .filter(Boolean)
}

async function runPreview() {
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await managementApi.previewGroupMappings({
      groups: parseGroups(previewInput.value),
    })
  } catch (cause) {
    previewError.value = describeError(cause)
    preview.value = null
  } finally {
    previewing.value = false
  }
}

onConsoleRefresh(load)
</script>

<style scoped>
.pc-gm-grid {
  display: grid;
  grid-template-columns: minmax(420px, 1.4fr) minmax(320px, 1fr);
  gap: 14px;
  align-items: start;
}

@media (max-width: 1100px) {
  .pc-gm-grid {
    grid-template-columns: 1fr;
  }
}

.pc-gm-filter {
  width: 200px;
}

.pc-gm-info {
  margin-left: 6px;
  color: var(--pc-text-dim);
  cursor: help;
  vertical-align: middle;
}

.pc-gm-lede {
  margin: 0 0 10px;
  color: var(--pc-text-dim);
  font-size: 13px;
  line-height: 1.6;
}

.pc-gm-preview-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

.pc-gm-tip {
  font-size: 12px;
}

.pc-gm-preview {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pc-gm-preview-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
}

.pc-gm-preview-label {
  min-width: 132px;
  color: var(--pc-text-dim);
  font-size: 12px;
}

.pc-gm-chip {
  margin-right: 2px;
}

.pc-gm-warnings {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--pc-text-dim);
  line-height: 1.7;
}

.pc-gm-note {
  margin: 0;
  font-size: 12px;
  color: var(--pc-text-dim);
  line-height: 1.6;
}

.pc-gm-field-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--pc-text-dim);
  line-height: 1.6;
}
</style>
