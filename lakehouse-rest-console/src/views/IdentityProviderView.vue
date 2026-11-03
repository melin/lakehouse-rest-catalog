<template>
  <div class="pc-page">
    <PageHeader
      title="OIDC 配置（SSO）"
      subtitle="平台 SSO 登录之后要发生什么，取决于这几项配置：谁来签令牌、主体名取哪个 claim、组名从哪儿读、登录时建不建主体。这一页把它们摊开，并回答「现在能不能连上」与「最近登录的人实际带回来了什么 claim」。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <TipAlert type="info" class="pc-hint">
      <template #title>这一页只读</template>
      这里的每一项都来自部署配置（<code>application.yml</code> / ConfigMap / 环境变量）。
      做成可在线修改意味着「一个登录进来的人可以改掉自己的准入规则」，而配错的表现是
      <b>所有人都登不上</b>——那需要重启、回滚与审计流程。改配置请改 <code>lakehouse.rest.auth.console.oidc.*</code>
      后重启服务端，本页用来确认改完的效果。
    </TipAlert>

    <div class="pc-idp-grid">
      <PanelCard>
        <SectionTitle title="生效配置" />
        <ResourceState :loading="loading" :error="error" :empty="false">
          <dl class="pc-kv pc-idp-kv">
            <dt>OIDC 登录</dt>
            <dd>
              <el-tag size="small" effect="plain" :type="configView.enabled ? 'success' : 'info'">
                {{ configView.enabled ? '已启用' : '未启用' }}
              </el-tag>
              <span v-if="!configView.enabled" class="pc-idp-note">
                登录页不会出现 SSO 页签。需要 <code>enabled=true</code> 且填好 issuer 与 clientId。
              </span>
            </dd>

            <dt>Issuer</dt>
            <dd><span class="pc-mono pc-break">{{ configView.issuer || '—' }}</span></dd>

            <dt>Client ID</dt>
            <dd><span class="pc-mono pc-break">{{ configView.clientId || '—' }}</span></dd>

            <dt>Audience</dt>
            <dd>
              <span class="pc-mono pc-break">{{ configView.audience || '（未配置，不校验受众）' }}</span>
            </dd>

            <dt>回调地址</dt>
            <dd><span class="pc-mono pc-break">{{ configView.redirectUri || '—' }}</span></dd>

            <dt>Scope</dt>
            <dd><span class="pc-mono pc-break">{{ configView.scope || '—' }}</span></dd>

            <dt>主体名 claim</dt>
            <dd>
              <span class="pc-mono">{{ configView.principalClaim || 'sub' }}</span>
              <span v-if="configView.principalClaim && configView.principalClaim !== 'sub'" class="pc-idp-note">
                取的不是 <code>sub</code>：这个字段一变（改名 / 换邮箱），同一个人就成了另一个主体。
              </span>
            </dd>

            <dt>组名 claim</dt>
            <dd>
              <span class="pc-mono">{{ configView.groupClaim || 'groups' }}</span>
              <span class="pc-idp-note">
                Keycloak 开了 full group path 会给 <code>/组名</code>；Cognito 是 <code>cognito:groups</code>。
                填错不报错，只表现为「这个人一个角色都没有」。
              </span>
            </dd>

            <dt>显示名 claim</dt>
            <dd><span class="pc-mono">{{ configView.displayNameClaim || 'email' }}</span></dd>

            <dt>组同步时机</dt>
            <dd>
              <el-tag size="small" effect="plain" :type="syncModeTag">{{ syncModeLabel }}</el-tag>
              <span class="pc-idp-note">{{ syncModeNote }}</span>
            </dd>

            <dt>首次登录建主体</dt>
            <dd>
              <span>{{ configView.autoProvision ? '开启（JIT）' : '关闭' }}</span>
              <span v-if="!configView.autoProvision" class="pc-idp-note pc-idp-warn">
                关着时，没有预注册过的人登录后<b>每页 403</b>——认证是成功的，缺的是准入。
              </span>
            </dd>

            <dt>接管同名本地主体</dt>
            <dd>
              <span>{{ configView.allowAdoptLocal ? '允许（危险）' : '不允许（默认）' }}</span>
              <span v-if="configView.allowAdoptLocal" class="pc-idp-note pc-idp-warn">
                打开后，能让 IdP 签发同名 <code>sub</code> 的人会继承本地主体的全部授权。
              </span>
            </dd>

            <dt>邮箱域白名单</dt>
            <dd>
              <span v-if="!configView.allowedEmailDomains?.length" class="pc-dim">不限</span>
              <template v-else>
                <el-tag
                  v-for="domain in configView.allowedEmailDomains"
                  :key="domain"
                  size="small"
                  effect="plain"
                  class="pc-idp-chip"
                >{{ domain }}</el-tag>
              </template>
            </dd>

            <dt>基线角色</dt>
            <dd>
              <span v-if="!configView.defaultRoles?.length" class="pc-dim">无</span>
              <template v-else>
                <el-tag
                  v-for="role in configView.defaultRoles"
                  :key="role"
                  size="small"
                  effect="plain"
                  class="pc-idp-chip"
                >{{ role }}</el-tag>
              </template>
            </dd>

            <dt>控制台门禁</dt>
            <dd>
              {{ configView.consoleRequired ? '要求先登录' : '不要求登录' }}
              <span class="pc-idp-note">
                与 OIDC 是否启用是两件事：门禁关着时登录页根本不会出现。
              </span>
            </dd>
          </dl>
        </ResourceState>
      </PanelCard>

      <div class="pc-idp-side">
        <PanelCard>
          <SectionTitle title="连通性测试" />
          <p class="pc-idp-lede">
            真的向 IdP 发一次请求：拉发现文档、比对 issuer、取 JWKS。五步各自给结论——
            连不上时也要能看见「配置里还缺什么」。
          </p>
          <el-button
            size="small"
            type="primary"
            :loading="probing"
            @click="runProbe"
          >
            <el-icon><Connection /></el-icon>
            <span class="pc-btn-text">运行连通性测试</span>
          </el-button>

          <el-alert
            v-if="probeError"
            type="error"
            :closable="false"
            show-icon
            class="pc-hint"
          >
            <template #title>测试没跑起来</template>
            <div>{{ probeError }}</div>
          </el-alert>

          <div v-if="probe" class="pc-idp-probe">
            <div class="pc-idp-probe-head">
              <span>总判</span>
              <el-tag size="small" :type="verdictTag(probe.verdict)" effect="dark">
                {{ verdictLabel(probe.verdict) }}
              </el-tag>
            </div>
            <div v-for="step in probe.steps" :key="step.step" class="pc-idp-step">
              <el-tag size="small" :type="verdictTag(step.status)" effect="plain" class="pc-idp-step-tag">
                {{ verdictLabel(step.status) }}
              </el-tag>
              <div class="pc-idp-step-body">
                <div class="pc-idp-step-name">{{ stepLabel(step.step) }}</div>
                <div class="pc-idp-step-detail">{{ step.detail }}</div>
              </div>
            </div>
          </div>
        </PanelCard>

        <PanelCard>
          <SectionTitle title="令牌样例（最近登录）" />
          <p class="pc-idp-lede">
            服务端<b>不保存令牌</b>，只留 claim 的形状：有哪些 claim、<code>groups</code> 有几个值。
            值已打码（组名留前三个字符、邮箱只留域名）——这是判断「<code>group-claim</code> 填对了没有」
            的唯一直接证据。
          </p>
          <ResourceState
            :loading="samplesLoading"
            :error="samplesError"
            :empty="!samples.length"
            empty-text="还没有人用 SSO 登录过。让一个人打开登录页走一遍，这里就会出现他的 claim 形状。"
          >
            <el-table :data="samples" size="small">
              <el-table-column prop="principal" label="主体" min-width="120" />
              <el-table-column label="结果" width="110">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :type="outcomeTag(row.outcome)">
                    {{ outcomeLabel(row.outcome) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="组" width="150">
                <template #default="{ row }">
                  <span v-if="!row.groupsClaimPresent" class="pc-dim">令牌里没有该 claim</span>
                  <span v-else-if="row.groupCount === 0" class="pc-dim">0 个（空数组）</span>
                  <template v-else>
                    <span class="pc-mono">{{ row.groups.join(', ') }}</span>
                    <span class="pc-dim">（共 {{ row.groupCount }}）</span>
                  </template>
                </template>
              </el-table-column>
              <el-table-column label="邮箱" min-width="170">
                <template #default="{ row }">
                  <span class="pc-mono">{{ row.email || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="170">
                <template #default="{ row }"><TimeText :millis="row.at" /></template>
              </el-table-column>
            </el-table>
            <p v-if="samplesProcessLocal" class="pc-idp-note pc-idp-footnote">
              样本存在<b>本进程内存</b>里（最多 20 条、同一主体只留最近一次）。
              多副本部署时，每个副本只记得自己处理过的那几次登录——看到的人少不等于别人没登录过。
            </p>
          </ResourceState>
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Connection, Refresh } from '@element-plus/icons-vue'

import { describeError } from '@/api/client.js'
import { managementApi } from '@/api/management.js'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import { onConsoleRefresh, useResource } from '@/composables/index.js'

/**
 * OIDC 配置页（原型 `#idp`）。
 *
 * <p><b>为什么是只读页。</b>这一页的每一项都是部署配置。让它可写意味着「一个登录进来的人
 * 能改掉自己的准入规则」，而配错的表现是所有人都登不上——那需要重启与回滚，
 * 不是浏览器能承担的操作。所以这里做的是「把黑盒打开」：配置是什么、
 * 现在连不连得上、最近登录的人带回了什么 claim。
 *
 * <p><b>为什么三块必须放在一起。</b>「登录成功但没有权限」这一个现象至少有两个根因：
 * 配置写错了（`group-claim` / `sync-mode`），或者 IdP 根本没给那个 claim。
 * 配置能看出前一半，claim 样例才能看出后一半；缺任何一块，排查都要回到翻日志。
 */

const { loading, error, data: config, load } = useResource(() => managementApi.getIdentityProvider())

/** 未取到时给一份空壳：模板里到处是可选链，但 `config` 本身不能是 null。 */
const configView = computed(() => config.value || {})

const probing = ref(false)
const probe = ref(null)
const probeError = ref('')

const samplesLoading = ref(false)
const samplesError = ref('')
const samples = ref([])
const samplesProcessLocal = ref(false)

/**
 * 同步时机的标签与注解。
 *
 * <p>这一项最容易被当成「三个差不多的选项」随便选，而它们的差别只在一种情况下显现：
 * 令牌里**没有** `groups` 时。`STRICT` 会把「没带」解释成「不在任何组」并清空权限，
 * 一次 scope 配置错误就能削掉所有人。
 */
const syncModeLabel = computed(() => {
  const mode = configView.value.syncMode
  if (mode === 'STRICT') return 'STRICT（严格）'
  if (mode === 'OFF') return 'OFF（不同步）'
  return 'ON_LOGIN（默认）'
})

const syncModeTag = computed(() => {
  const mode = configView.value.syncMode
  if (mode === 'STRICT') return 'danger'
  if (mode === 'OFF') return 'info'
  return 'success'
})

const syncModeNote = computed(() => {
  const mode = configView.value.syncMode
  if (mode === 'STRICT') return '令牌没带 groups 时按「不在任何组」处理，清空 IDP 来源的角色。'
  if (mode === 'OFF') return '组映射不生效，角色只能手工授（灰度或排障时用）。'
  return '令牌没带 groups 时不动现有角色，只补基线角色——避免一次 scope 配错清空全员权限。'
})

const STEP_LABELS = {
  discovery: '发现文档',
  endpoints: '端点与回调地址',
  jwks: 'JWKS 公钥',
  mapping: 'claim 映射',
  provisioning: '供给开关',
}

const VERDICT_LABELS = { OK: '通过', WARN: '注意', FAIL: '失败', SKIPPED: '跳过' }
const VERDICT_TAGS = { OK: 'success', WARN: 'warning', FAIL: 'danger', SKIPPED: 'info' }

function stepLabel(step) {
  return STEP_LABELS[step] || step
}

function verdictLabel(verdict) {
  return VERDICT_LABELS[verdict] || verdict
}

function verdictTag(verdict) {
  return VERDICT_TAGS[verdict] || 'info'
}

const OUTCOME_LABELS = {
  success: '登录成功',
  partial: '同步失败',
  'not-provisioned': '未建主体',
  refused: '被拒绝',
}

function outcomeLabel(outcome) {
  return OUTCOME_LABELS[outcome] || outcome
}

function outcomeTag(outcome) {
  if (outcome === 'success') return 'success'
  if (outcome === 'refused') return 'danger'
  return 'warning'
}

async function runProbe() {
  probing.value = true
  probeError.value = ''
  try {
    probe.value = await managementApi.probeIdentityProvider()
  } catch (cause) {
    probeError.value = describeError(cause)
  } finally {
    probing.value = false
  }
}

async function loadSamples() {
  samplesLoading.value = true
  samplesError.value = ''
  try {
    const payload = await managementApi.identityClaimSamples()
    samples.value = payload?.samples || []
    samplesProcessLocal.value = Boolean(payload?.processLocal)
  } catch (cause) {
    samplesError.value = describeError(cause)
    samples.value = []
  } finally {
    samplesLoading.value = false
  }
}

function reloadAll() {
  load()
  loadSamples()
}

onMounted(loadSamples)
onConsoleRefresh(reloadAll)
</script>

<style scoped>
/* 两列：左边配置（长列表，给宽），右边测试与样例（信息密度高，给窄） */
.pc-idp-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(340px, 1fr);
  gap: 14px;
  align-items: start;
}

@media (max-width: 1100px) {
  .pc-idp-grid {
    grid-template-columns: 1fr;
  }
}

.pc-idp-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.pc-idp-kv dt {
  white-space: nowrap;
}

.pc-idp-kv dd {
  margin-bottom: 10px;
  line-height: 1.6;
}

.pc-idp-note {
  display: block;
  margin-top: 4px;
  color: var(--pc-text-dim);
  font-size: 12px;
  line-height: 1.5;
}

.pc-idp-warn {
  color: var(--el-color-warning);
}

.pc-idp-chip {
  margin-right: 4px;
}

.pc-idp-lede {
  margin: 0 0 10px;
  color: var(--pc-text-dim);
  font-size: 13px;
  line-height: 1.6;
}

.pc-idp-probe {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pc-idp-probe-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--pc-text-dim);
}

.pc-idp-step {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.pc-idp-step-tag {
  margin-top: 1px;
  flex-shrink: 0;
}

.pc-idp-step-name {
  font-size: 13px;
  font-weight: 600;
}

.pc-idp-step-detail {
  margin-top: 2px;
  color: var(--pc-text-dim);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.pc-idp-footnote {
  margin-top: 8px;
}
</style>
