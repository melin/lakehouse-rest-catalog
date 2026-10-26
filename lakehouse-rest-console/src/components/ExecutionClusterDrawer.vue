<script setup>
/**
 * 执行集群配置抽屉（优化任务提交到哪个 k8s、用什么身份）。
 *
 * <p>设计见 `docs/catalog-execution-cluster-design.md`，交互见
 * `docs/catalog-execution-cluster-mockup.html` 的画面 2–5。
 *
 * <p><b>为什么是抽屉而不是一个页面。</b>这一屏只服务一个 catalog，而「哪个 catalog」
 * 由列表那一行决定；做成页面就要在 URL 里带 catalog 名、还要自己处理「catalog 被删了」，
 * 而用户在列表上下文的心理模型里，这本来就是一个展开的动作。给一级菜单更糟：
 * 执行集群不是可枚举的资源（一个 catalog 最多一份），一份列表只会让人问
 * 「这里的行和上面的 catalog 是什么关系」。
 *
 * <p><b>三条贯穿全文的约定，每一条都对应一次真实会踩的坑：</b>
 *
 * 1. **密文字段留空 = 保持原样。** kubeconfig / secretAccessKey / clientSecret /
 *    catalogToken 的输入框在已配置时是**空**的，placeholder 写「已配置，留空保持不变」。
 *    不能回填：读接口本来就不回明文；也不能把空串当「清空」——那样「打开抽屉、
 *    什么都不改、保存」会把凭据清掉。要清空走底部的「清除凭据」。
 * 2. **类型即认证方式。** 自建 → kubeconfig、EKS → SigV4 预签名、AKS → AAD 客户端凭据，
 *    三者一一对应，因此只有一个「集群类型」选择器，没有第二个「认证方式」下拉。
 *    换类型会**丢**掉另一类的凭据（服务端语义），所以切换时立刻提示要重填。
 * 3. **保存 ≠ 验证。** 保存只落配置、状态变「待验证」；「验证连接」才会真的连一次。
 *    两个按钮分开是有意的：验证会触发真实的 STS 调用与 dry-run 建 Job，
 *    不该在每次保存时都发生。
 */
import { computed, reactive, watch } from 'vue'

import FieldLabel from '@/components/FieldLabel.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TipNote from '@/components/TipNote.vue'
import TimeText from '@/components/TimeText.vue'
import { describeError } from '@/api/client.js'
import { executionClusterOf, managementApi } from '@/api/management.js'
import { confirmDanger, notifyError, notifySuccess } from '@/composables/index.js'
import {
  CLUSTER_TYPES,
  clusterStatusInfo,
  clusterTypeLabel,
  contextSelectable,
  contextVerdict,
  probeReasonLabel,
  probeStepTag,
} from '@/utils/executionCluster.js'

const props = defineProps({
  /** 是否打开（`v-model`）。 */
  modelValue: { type: Boolean, default: false },
  /** 配置属于哪个 catalog。为空时不加载（抽屉还没被指定对象）。 */
  catalogName: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'saved'])

const IMAGE_PULL_POLICIES = ['IfNotPresent', 'Always', 'Never']

/** 表单的空白形状。新建与「读失败」都从这里开始，避免半份旧值残留。 */
function blankForm() {
  return {
    enabled: true,
    clusterType: 'SELF_HOSTED',
    // 自建
    kubeconfig: '',
    contextName: '',
    // EKS
    region: '',
    clusterName: '',
    accessKeyId: '',
    secretAccessKey: '',
    sessionToken: '',
    // AKS
    authority: 'PUBLIC',
    tenantId: '',
    clientId: '',
    clientSecret: '',
    scope: '',
    // 两者共用
    apiServer: '',
    caCertPem: '',
    // 运行位置
    namespace: '',
    image: '',
    imagePullPolicy: 'IfNotPresent',
    imagePullSecrets: '',
    serviceAccount: '',
    callbackBaseUrl: '',
    // 批次策略
    maxTablesPerJob: 20,
    maxConcurrentJobs: 2,
    jobTimeoutSeconds: 7200,
    catalogToken: '',
  }
}

const state = reactive({
  loading: false,
  loadError: '',
  /** 服务端那一份（新建时为 null）。`entityVersion`、`has*` 都从这里来。 */
  entity: null,
  form: blankForm(),
  /** 表单刚加载时的快照，用来判断「有没有未保存的改动」。 */
  snapshot: JSON.stringify(blankForm()),
  saving: false,
  /** `form` 或 `probe`：探活结果整屏替换表单，回来时表单还在（不重新加载）。 */
  view: 'form',
  probing: false,
  probe: null,
  parsing: false,
  parseResult: null,
  parseError: '',
  /**
   * 「清除已保存的数据面令牌」。
   *
   * <p>只有 catalogToken 需要这个开关：它是**可选**的（没开认证的部署本来就没有），
   * 所以「清掉」是一个有意义的状态。另外四个秘密没有开关也不需要——它们是建客户端的
   * 必需材料，清掉之后这份配置连探活都跑不了（服务端 400），
   * 那个语义等于「删掉整份配置」，走底部的「删除配置」。
   */
  clearCatalogToken: false,
})

const isOpen = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const configured = computed(() => state.entity != null)
const status = computed(() => (state.entity ? state.entity.status : 'NOT_CONFIGURED'))
const statusInfo = computed(() => clusterStatusInfo(status.value))
const dirty = computed(() => JSON.stringify(state.form) === state.snapshot ? false : true)
/** 换过类型（与库里存的那一类不同）——此时另一类凭据要不要重填，是用户最需要知道的事。 */
const typeChanged = computed(() => configured.value && state.form.clusterType !== state.entity.clusterType)
const typeOption = computed(() => CLUSTER_TYPES.find((item) => item.value === state.form.clusterType))

/** 已保存的 kubeconfig 的 context 列表（服务端解密后重新解析得到，不含密钥材料）。 */
const storedContexts = computed(() => state.entity?.kubeconfig?.contexts || [])
const storedKubeconfigState = computed(() => state.entity?.kubeconfig?.state || 'NONE')

/** 底部那句「保存后会怎样」——把状态机的结论提前说出来，免得保存完才发现变成了「待验证」。 */
const footerHint = computed(() => {
  if (!configured.value) {
    return '保存后状态为「待验证」，不会自动开始派发'
  }
  if (state.form.enabled === false) {
    return '当前为停用：保存后不提交任何优化任务，但配置与凭据保留'
  }
  if (dirty.value) {
    return '保存后状态回到「待验证」——连接参数变了，上一次的验证结论不再适用'
  }
  return status.value === 'HEALTHY'
    ? '连接参数自上次验证后未改动'
    : '连接参数与上次验证时一致；点「验证连接」更新结论'
})

// ---------------------------------------------------------------- 加载

watch(() => [props.modelValue, props.catalogName], ([open, name]) => {
  if (!open || !name) {
    return
  }
  load()
})

async function load() {
  state.loading = true
  state.loadError = ''
  state.view = 'form'
  state.probe = null
  state.parseResult = null
  state.parseError = ''
  state.clearCatalogToken = false
  try {
    const dto = await executionClusterOf(props.catalogName)
    state.entity = dto
    state.form = dto ? formOf(dto) : blankForm()
  } catch (error) {
    state.entity = null
    state.form = blankForm()
    state.loadError = describeError(error)
  } finally {
    state.snapshot = JSON.stringify(state.form)
    state.loading = false
  }
}

/**
 * 读模型 → 表单。
 *
 * <p>**四个密文字段一律留空**，只把「有没有」写进 placeholder（见 `has*` computed）。
 * 回填是做不到的（服务端不回明文），而把 `null` 当值填进去会让输入框显示 "null"。
 */
function formOf(dto) {
  return {
    enabled: dto.enabled !== false,
    clusterType: dto.clusterType || 'SELF_HOSTED',
    kubeconfig: '',
    contextName: dto.contextName || '',
    region: dto.eks?.region || '',
    clusterName: dto.eks?.clusterName || '',
    accessKeyId: dto.eks?.accessKeyId || '',
    secretAccessKey: '',
    sessionToken: '',
    authority: dto.aks?.authority || 'PUBLIC',
    tenantId: dto.aks?.tenantId || '',
    clientId: dto.aks?.clientId || '',
    clientSecret: '',
    scope: dto.aks?.scope || '',
    apiServer: dto.apiServer || '',
    caCertPem: dto.caCertPem || '',
    namespace: dto.namespace || '',
    image: dto.image || '',
    imagePullPolicy: dto.imagePullPolicy || 'IfNotPresent',
    imagePullSecrets: (dto.imagePullSecrets || []).join(', '),
    serviceAccount: dto.serviceAccount || '',
    callbackBaseUrl: dto.callbackBaseUrl || '',
    maxTablesPerJob: dto.maxTablesPerJob || 20,
    maxConcurrentJobs: dto.maxConcurrentJobs || 2,
    jobTimeoutSeconds: dto.jobTimeoutSeconds || 7200,
    catalogToken: '',
  }
}

// 「这一项已配置」的占位提示。空输入框 + 一句 placeholder 是这套表单的核心约定。
const hasEksSecret = computed(() => !!state.entity?.eks?.hasSecretAccessKey && !typeChanged.value)
const hasEksSession = computed(() => !!state.entity?.eks?.hasSessionToken && !typeChanged.value)
const hasAksSecret = computed(() => !!state.entity?.aks?.hasClientSecret && !typeChanged.value)
const hasCatalogToken = computed(() => !!state.entity?.hasCatalogToken)

function placeholderIfStored(stored, fallback) {
  return stored ? '已配置，留空保持不变' : fallback
}

// ---------------------------------------------------------------- 交互

function chooseType(value) {
  if (state.form.clusterType === value) {
    return
  }
  state.form.clusterType = value
  // 换类型＝换认证方式：上一类的 secret 输入框清空，别让人以为「填过的那份还在用」
  state.form.secretAccessKey = ''
  state.form.sessionToken = ''
  state.form.clientSecret = ''
  state.form.kubeconfig = ''
  state.parseResult = null
  state.parseError = ''
}

/** 解析 kubeconfig（不落库）。解析完自动选中服务端的 `current-context`。 */
async function parseKubeconfig() {
  if (!state.form.kubeconfig.trim()) {
    notifyError(new Error('先粘贴 kubeconfig'))
    return
  }
  state.parsing = true
  state.parseError = ''
  try {
    const result = await managementApi.parseExecutionClusterKubeconfig(props.catalogName, state.form.kubeconfig)
    state.parseResult = result
    const contexts = result.contexts || []
    const usable = contexts.filter((context) => contextSelectable(context.verdict))
    const preferred = contexts.find((context) => context.name === result.currentContext)
    if (preferred && contextSelectable(preferred.verdict)) {
      state.form.contextName = preferred.name
      state.form.apiServer = preferred.server
    } else if (usable.length === 1) {
      // 只有一个能用时直接选中：让人再点一次没有信息量
      state.form.contextName = usable[0].name
      state.form.apiServer = usable[0].server
    } else if (!usable.some((context) => context.name === state.form.contextName)) {
      state.form.contextName = ''
      state.form.apiServer = ''
    }
  } catch (error) {
    state.parseResult = null
    state.parseError = describeError(error)
  } finally {
    state.parsing = false
  }
}

/**
 * 选中一个 context。
 *
 * <p>两个来源：刚解析出来的（`parseResult`）与已保存的（`entity.kubeconfig.contexts`）。
 * 两者形状一样，都是服务端 `KubeconfigParser` 的产出，所以这里不需要分支判断来源，
 * 只需要判断**是否可用**——不可用的行点了不响应，与它的置灰外观一致。
 */
function chooseContext(context) {
  if (!contextSelectable(context.verdict)) {
    return
  }
  state.form.contextName = context.name
  // apiServer 由 context 投影出来，不给手填：填错的表现是「连到了别的集群」，
  // 而这件事在界面上完全看不出来
  state.form.apiServer = context.server || ''
}

/**
 * 组装 PUT 报文。
 *
 * <p>两处「留空」的处理**刻意不同**，因为服务端对它们的语义不同：
 *
 * - 密文字段（kubeconfig / 三个 secret / catalogToken）空 → 传 `undefined`（不改）。
 *   `sessionToken` 特殊：服务端把 `''` 当「清空」、`null` 当「不改」，因此也按
 *   `undefined` 处理——要清空整个会话令牌请用「清除凭据」。
 * - `caCertPem` 不是秘密，空串是**明确的「清空」**（用户可能就是要删掉它）。
 *   自建类型下强制传空串：kubeconfig 自带 CA，残留一份 EKS 时期的 CA 会把它覆盖掉，
 *   症状是 TLS 校验失败——而配置页上根本看不到这个字段，无从排查。
 */
function buildPayload() {
  const form = state.form
  const selfHosted = form.clusterType === 'SELF_HOSTED'
  const payload = {
    enabled: form.enabled,
    clusterType: form.clusterType,
    namespace: form.namespace,
    image: form.image,
    imagePullPolicy: form.imagePullPolicy,
    imagePullSecrets: form.imagePullSecrets,
    serviceAccount: form.serviceAccount,
    callbackBaseUrl: form.callbackBaseUrl,
    maxTablesPerJob: form.maxTablesPerJob,
    maxConcurrentJobs: form.maxConcurrentJobs,
    jobTimeoutSeconds: form.jobTimeoutSeconds,
    caCertPem: selfHosted ? '' : form.caCertPem,
  }
  if (selfHosted) {
    payload.contextName = form.contextName
    payload.kubeconfig = form.kubeconfig.trim() ? form.kubeconfig : undefined
  } else if (form.clusterType === 'EKS') {
    payload.region = form.region
    payload.clusterName = form.clusterName
    payload.apiServer = form.apiServer
    payload.accessKeyId = form.accessKeyId
    payload.secretAccessKey = form.secretAccessKey || undefined
    payload.sessionToken = form.sessionToken || undefined
  } else {
    payload.authority = form.authority
    payload.tenantId = form.tenantId
    payload.clientId = form.clientId
    payload.apiServer = form.apiServer
    payload.clientSecret = form.clientSecret || undefined
    payload.scope = form.scope
  }
  if (state.clearCatalogToken) {
    payload.clearCatalogToken = true
  } else if (form.catalogToken) {
    payload.catalogToken = form.catalogToken
  }
  if (configured.value) {
    payload.entityVersion = state.entity.entityVersion
  }
  return payload
}

/** 保存。`silent` 用于「验证连接」前面的隐式保存（不弹两条成功提示）。 */
async function save({ silent = false } = {}) {
  if (state.saving) {
    return false
  }
  state.saving = true
  try {
    const saved = await managementApi.updateExecutionCluster(props.catalogName, buildPayload())
    state.entity = saved
    state.form = formOf(saved)
    state.clearCatalogToken = false
    state.snapshot = JSON.stringify(state.form)
    if (!silent) {
      notifySuccess(`已保存 ${props.catalogName} 的执行集群配置（${statusOfLabel(saved)}）`)
      emit('saved', saved)
    }
    return true
  } catch (error) {
    notifyError(error)
    return false
  } finally {
    state.saving = false
  }
}

function statusOfLabel(dto) {
  return clusterStatusInfo(dto.status).label
}

/**
 * 验证连接：先保存（有改动时），再跑全档探活。
 *
 * <p>为什么必须先保存：探活读的是**库里那一份**配置。未保存就探活，验的是旧配置，
 * 而界面显示的是新表单——「验证通过」与「保存后不通过」这个组合最难解释。
 */
async function verify() {
  if (state.probing) {
    return
  }
  if (dirty.value || !configured.value) {
    const ok = await save({ silent: true })
    if (!ok) {
      return
    }
  }
  state.probing = true
  state.view = 'probe'
  state.probe = null
  try {
    state.probe = await managementApi.probeExecutionCluster(props.catalogName, true)
    // 探活结论已落库：把 entity 换成最新的一份，抽屉顶部的状态 chip 才不会停在旧值上
    state.entity = await executionClusterOf(props.catalogName).catch(() => state.entity)
    emit('saved', state.entity)
  } catch (error) {
    notifyError(error)
    state.view = 'form'
  } finally {
    state.probing = false
  }
}

/** 删掉整份配置（含凭据）。 */
async function removeConfig() {
  const ok = await confirmDanger({
    title: '删除执行集群配置',
    target: `确认删除 catalog「${props.catalogName}」的执行集群配置？`,
    detail: '整份配置与凭据都会删除，这个 catalog 的优化派发立即停止；到期策略不会丢，'
      + '但游标停在原处（重新配置后会立刻补上，不补跑错过的调度）。',
  })
  if (!ok) {
    return
  }
  try {
    await managementApi.deleteExecutionCluster(props.catalogName)
    notifySuccess('已删除执行集群配置')
    state.entity = null
    state.form = blankForm()
    state.snapshot = JSON.stringify(state.form)
    emit('saved', null)
    isOpen.value = false
  } catch (error) {
    notifyError(error)
  }
}

function backToForm() {
  state.view = 'form'
}

function close() {
  isOpen.value = false
}

// 探活结果的计数（一句话摘要用）：三通过一警告一失败
const probeCounts = computed(() => {
  const steps = state.probe?.steps || []
  return {
    pass: steps.filter((step) => step.status === 'PASS').length,
    warn: steps.filter((step) => step.status === 'WARN').length,
    fail: steps.filter((step) => step.status === 'FAIL').length,
    skip: steps.filter((step) => step.status === 'SKIP').length,
    total: steps.length,
  }
})

const probeSummaryTone = computed(() => {
  if (probeCounts.value.fail) {
    return 'danger'
  }
  return probeCounts.value.warn ? 'warning' : 'success'
})

const probeSummaryTitle = computed(() => {
  if (!state.probe) {
    return ''
  }
  const parts = []
  if (probeCounts.value.pass) parts.push(`${probeCounts.value.pass} 项通过`)
  if (probeCounts.value.warn) parts.push(`${probeCounts.value.warn} 项警告`)
  if (probeCounts.value.fail) parts.push(`${probeCounts.value.fail} 项失败`)
  if (probeCounts.value.skip) parts.push(`${probeCounts.value.skip} 项跳过`)
  return `验证完成：${parts.join(' · ') || '没有可执行的项目'}`
})

/** 探活结果对派发的影响。写清「失败会怎样」，而不是只说「失败」。 */
const probeConsequence = computed(() => {
  const summary = state.probe
  if (!summary) {
    return ''
  }
  if (summary.status === 'FAIL') {
    return '状态记为「异常」：该 catalog 的批次派发会被暂停，排队中的批次保持原样、不判失败；'
      + '修好之后下一次轻探活通过即自动恢复（错过的调度不补）。'
  }
  if (summary.status === 'WARN') {
    return '状态记为「正常」（警告不拦派发），但隐患留在上面那条说明里——建议按处置修掉。'
  }
  return '状态记为「正常」。'
})
</script>

<template>
  <el-drawer
    v-model="isOpen"
    :size="'760px'"
    :close-on-click-modal="false"
    :destroy-on-close="false"
    class="exec-drawer"
  >
    <template #header>
      <div class="exec-head">
        <div class="exec-head-line">
          <h3 class="exec-head-title">执行集群配置</h3>
          <el-tag size="small" effect="plain" :type="statusInfo.tag">{{ statusInfo.label }}</el-tag>
          <span v-if="configured && !state.form.enabled" class="pc-dim">已停用</span>
        </div>
        <div class="exec-head-sub">
          <span v-if="state.form.clusterType" class="pc-mono">{{ clusterTypeLabel(state.form.clusterType) }}</span>
          <span v-if="state.form.namespace" class="pc-dim"> · ns {{ state.form.namespace }}</span>
          <span class="pc-dim"> · catalog </span>
          <span class="pc-mono">{{ catalogName }}</span>
        </div>
      </div>
    </template>

    <!-- 加载与读失败：读失败也要能继续填（比如服务端刚升级、密文解不开），
         因此错误只是一条提示，表单照常可用 -->
    <div v-if="state.loading" class="pc-dim exec-loading">正在读取配置…</div>

    <template v-else>
      <el-alert
        v-if="state.loadError"
        type="error"
        :closable="false"
        show-icon
        class="exec-alert"
        :title="state.loadError"
      >
        <template #default>
          读不到已有配置。可以继续在下面填写并保存——保存会覆盖原有配置（若确实存在）。
        </template>
      </el-alert>

      <!-- 「密文解不开」与「没配」是两件事：前者要运维恢复密钥，后者要用户去填 -->
      <el-alert
        v-else-if="configured && state.entity.credentialsReadable === false"
        type="error"
        :closable="false"
        show-icon
        class="exec-alert"
        title="已保存的凭据无法解密"
      >
        <template #default>
          这台服务端的 <code class="pc-mono">lakehouse.rest.storage.credential-secret-key</code>
          与保存时用的不一致，密文解不开。连接与派发都会失败，请重新填写凭据（或让运维恢复原来的密钥）。
        </template>
      </el-alert>

      <el-alert
        v-else-if="configured && status === 'FAILING'"
        type="error"
        :closable="false"
        show-icon
        class="exec-alert"
        :title="`最近一次验证失败${state.entity.probeReason ? '：' + probeReasonLabel(state.entity.probeReason) : ''}`"
      >
        <template #default>
          <div>{{ state.entity.probeMessage || '未记录具体原因' }}</div>
          <div class="pc-field-note">
            这个 catalog 的优化派发已暂停（排队中的批次不判失败、游标不推进）。修好后点「验证连接」。
          </div>
        </template>
      </el-alert>

      <!-- ============================ 探活结果视图 ============================ -->
      <div v-if="state.view === 'probe'" class="exec-probe">
        <div v-if="state.probing" class="pc-dim exec-loading">
          正在验证：依次检查凭据、身份、权限、准入与回调…
        </div>
        <template v-else-if="state.probe">
          <el-alert
            :type="probeSummaryTone"
            :closable="false"
            show-icon
            class="exec-alert"
            :title="probeSummaryTitle"
          >
            <template #default>
              <div>{{ probeConsequence }}</div>
              <div class="pc-field-note">
                耗时 {{ state.probe.elapsedMillis }} ms
                <template v-if="state.probe.probedAt">
                  · <TimeText :millis="state.probe.probedAt" />
                </template>
              </div>
            </template>
          </el-alert>

          <div v-for="step in state.probe.steps" :key="step.step" class="exec-step">
            <div class="exec-step-no">{{ step.step }}</div>
            <div class="exec-step-body">
              <div class="exec-step-head">
                <span class="exec-step-title">{{ step.title }}</span>
                <el-tag size="small" effect="plain" :type="probeStepTag(step.status)">
                  {{ step.status === 'PASS' ? '通过' : step.status === 'WARN' ? '警告'
                    : step.status === 'FAIL' ? '失败' : '跳过' }}
                </el-tag>
              </div>
              <div class="exec-step-summary">{{ step.summary }}</div>
              <div v-if="step.detail" class="exec-step-detail pc-mono">{{ step.detail }}</div>
              <div v-if="step.fix" class="exec-step-fix">
                <b>处置：</b>{{ step.fix }}
              </div>
            </div>
          </div>

          <div class="pc-field-note exec-probe-note">
            调度扫描每 30 分钟跑一次<b>轻探活</b>（只做第 1–3 步）：dry-run 虽然不落库，
            但它逐条走准入链路，在大集群的审计日志里是可见的动作，不该每半小时来一次。
          </div>
        </template>
      </div>

      <!-- ============================ 表单视图 ============================ -->
      <div v-else class="exec-form">
        <!-- 1 集群类型 -->
        <SectionTitle title="集群类型">
          <template #tip>
            <TipNote>
              类型决定用哪种方式证明身份，一一对应：自建 → kubeconfig、EKS → SigV4 预签名令牌、
              AKS → AAD 客户端凭据。不存在「EKS + kubeconfig」这种组合，所以只有一个选择器。
            </TipNote>
          </template>
        </SectionTitle>
        <div class="pc-pick-grid">
          <div
            v-for="option in CLUSTER_TYPES"
            :key="option.value"
            class="pc-pick"
            :class="{ 'is-on': state.form.clusterType === option.value }"
            @click="chooseType(option.value)"
          >
            <div class="pc-pick-name">
              <span class="pc-pick-dot" />
              {{ option.label }}
            </div>
            <div class="pc-pick-desc">{{ option.desc }}</div>
            <div class="pc-pick-meta pc-mono">{{ option.path }}</div>
          </div>
        </div>
        <div v-if="typeChanged" class="pc-field-note pc-field-note-warn exec-type-change">
          类型从 {{ clusterTypeLabel(state.entity.clusterType) }} 改成 {{ clusterTypeLabel(state.form.clusterType) }}：
          另一类的凭据不会被沿用，保存前必须重新填写{{ state.form.clusterType === 'SELF_HOSTED'
            ? ' kubeconfig' : state.form.clusterType === 'EKS' ? '区域、集群名与访问密钥' : '租户、客户端与密钥' }}。
        </div>

        <!-- 2 连接：自建 -->
        <template v-if="state.form.clusterType === 'SELF_HOSTED'">
          <SectionTitle title="连接（kubeconfig）">
            <template #tip>
              <TipNote>
                服务端不执行 kubeconfig 里的本地命令：<code class="pc-mono">exec:</code>（如
                <code class="pc-mono">aws eks get-token</code>）与旧式
                <code class="pc-mono">auth-provider:</code> 都会被拒绝——那等于让服务端执行任意命令。
                这两种情况请改选对应的云类型。
              </TipNote>
            </template>
          </SectionTitle>

          <el-form label-position="top" size="small" class="pc-form exec-grid2">
            <el-form-item class="span2">
              <template #label>
                <FieldLabel
                  label="kubeconfig"
                  :tip="configured ? '已保存过一份。留空 = 保持原样，只换 context 就不用重新粘贴。' : '≤ 64 KiB。保存后只保留摘要，读接口不回原文。'"
                />
              </template>
              <el-input
                v-model="state.form.kubeconfig"
                type="textarea"
                :rows="7"
                class="pc-mono"
                :placeholder="storedKubeconfigState === 'CONFIGURED'
                  ? '已配置（留空保持不变）。要整体替换就粘贴新的 kubeconfig'
                  : 'apiVersion: v1\nkind: Config\ncurrent-context: ...'"
              />
              <div class="exec-inline">
                <el-button size="small" :loading="state.parsing" @click="parseKubeconfig">
                  解析
                </el-button>
                <span v-if="state.parseResult" class="pc-field-note">
                  已解析：{{ state.parseResult.contexts?.length || 0 }} 个 context
                  <template v-if="state.parseResult.contexts?.length">
                    ，其中 {{ state.parseResult.contexts.filter((c) => contextSelectable(c.verdict)).length }} 个可用
                  </template>
                </span>
                <span v-else-if="state.parseError" class="exec-inline-error">{{ state.parseError }}</span>
                <span v-else-if="storedKubeconfigState === 'UNREADABLE'" class="exec-inline-error">
                  已保存的 kubeconfig 无法解密，请重新粘贴
                </span>
              </div>
              <div v-if="state.parseResult?.warnings?.length" class="pc-field-note pc-field-note-warn">
                <div v-for="warning in state.parseResult.warnings" :key="warning">{{ warning }}</div>
              </div>
            </el-form-item>
          </el-form>

          <!-- 2b 选 context：刚解析的优先，否则用已保存的那份 -->
          <SectionTitle title="选择 context">
            <template #tip>
              <TipNote>
                不可用的 context 禁用但不隐藏——「我的 context 去哪了」比一个红叉更让人困惑。
                选中的 context 决定 apiServer、CA 与身份，服务端不接受手填这些值。
              </TipNote>
            </template>
          </SectionTitle>
          <div v-if="(state.parseResult?.contexts || storedContexts).length" class="exec-ctx-list">
            <div
              v-for="context in (state.parseResult?.contexts || storedContexts)"
              :key="context.name"
              class="exec-ctx"
              :class="{
                'is-on': state.form.contextName === context.name,
                'is-off': !contextSelectable(context.verdict),
              }"
              @click="chooseContext(context)"
            >
              <div class="exec-ctx-main">
                <span class="exec-ctx-name pc-mono">{{ context.name }}</span>
                <el-tag size="small" effect="plain" :type="contextVerdict(context.verdict).tag">
                  {{ contextVerdict(context.verdict).label }}
                </el-tag>
              </div>
              <div class="exec-ctx-meta pc-mono">{{ context.server || '—' }}</div>
              <div class="exec-ctx-auth pc-dim">{{ context.auth || '—' }}</div>
              <div v-if="context.note" class="exec-ctx-note">{{ context.note }}</div>
            </div>
          </div>
          <div v-else class="pc-field-note">
            还没有 context 可选：粘贴 kubeconfig 后点「解析」。
          </div>
          <div v-if="state.form.contextName" class="pc-field-note">
            已选 <span class="pc-mono">{{ state.form.contextName }}</span>
            <template v-if="state.form.apiServer">
              · server <span class="pc-mono">{{ state.form.apiServer }}</span>
            </template>
          </div>
        </template>

        <!-- 2 连接：EKS -->
        <template v-else-if="state.form.clusterType === 'EKS'">
          <SectionTitle title="集群与凭据（EKS）">
            <template #tip>
              <TipNote>
                EKS 的 apiserver 认的不是 AK/SK，而是 <code class="pc-mono">aws eks get-token</code>
                产出的串——本质是用 SigV4 预签名的 STS <code class="pc-mono">GetCallerIdentity</code> URL，
                15 分钟有效。服务端每次请求现签一个，不必装 aws cli。
              </TipNote>
            </template>
          </SectionTitle>
          <el-form label-position="top" size="small" class="pc-form exec-grid2">
            <el-form-item>
              <template #label>
                <FieldLabel label="区域（region）" tip="决定 STS 与 EKS 的端点。中国区是 *.amazonaws.com.cn。" />
              </template>
              <el-input v-model="state.form.region" placeholder="例如 ap-northeast-1" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel
                  label="集群名（clusterName）"
                  tip="必须与 EKS 控制台里逐字一致：它被塞进 x-k8s-aws-id 头，写错的表现是 401 而不是「找不到集群」。"
                />
              </template>
              <el-input v-model="state.form.clusterName" placeholder="例如 prod" />
            </el-form-item>
            <el-form-item class="span2">
              <template #label>
                <FieldLabel label="API Server" tip="EKS 控制台 → 集群 → API server endpoint。" />
              </template>
              <el-input v-model="state.form.apiServer" placeholder="https://XXXXXX.gr7.ap-northeast-1.eks.amazonaws.com" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel
                  label="访问密钥 ID（accessKeyId）"
                  tip="不是秘密，读接口会回显它——用途是让人认出「配的是哪把钥匙」。"
                />
              </template>
              <el-input v-model="state.form.accessKeyId" placeholder="AKIA…" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel
                  label="秘密访问密钥（secretAccessKey）"
                  :tip="hasEksSecret ? '已配置。留空 = 保持原样。' : '与 accessKeyId 配对的那一半，只写不读。'"
                />
              </template>
              <el-input
                v-model="state.form.secretAccessKey"
                type="password"
                show-password
                :placeholder="placeholderIfStored(hasEksSecret, '必填')"
              />
            </el-form-item>
            <el-form-item class="span2">
              <template #label>
                <FieldLabel
                  label="会话令牌（sessionToken，可选）"
                  :tip="hasEksSession ? '已配置。留空 = 保持原样。' : '只有 STS 临时凭据才需要；长期 AK/SK 留空。'"
                />
              </template>
              <el-input
                v-model="state.form.sessionToken"
                type="password"
                show-password
                :placeholder="placeholderIfStored(hasEksSession, '长期 AK/SK 留空即可')"
              />
              <div class="pc-field-note pc-field-note-warn">
                长期 AK/SK 是这份设计的弱点：建议每个环境一把专用密钥、只授
                <code class="pc-mono">sts:GetCallerIdentity</code> 与
                <code class="pc-mono">eks:DescribeCluster</code> 两条权限、90 天轮转。
              </div>
            </el-form-item>
            <el-form-item class="span2">
              <template #label>
                <FieldLabel
                  label="集群 CA（caCertPem，可选）"
                  tip="取自 aws eks describe-cluster --query cluster.certificateAuthority.data（base64 解码后是 PEM）。留空 = 用 JVM 信任库。"
                />
              </template>
              <el-input v-model="state.form.caCertPem" type="textarea" :rows="3" class="pc-mono" placeholder="留空 = 用 JVM 信任库" />
            </el-form-item>
          </el-form>
        </template>

        <!-- 2 连接：AKS -->
        <template v-else>
          <SectionTitle title="云环境与客户端凭据（AKS）">
            <template #tip>
              <TipNote>
                AKS 用 AAD 客户端凭据换令牌。<b>scope 必须是 AKS 自己的 audience</b>
                （6dae42f8-4368-4678-94ff-3960e28e3630），不是 management.core.windows.net——
                用管理面 scope 会得到 <code class="pc-mono">401 Invalid audience</code>，
                看起来像「应用没授权」。不设 subscriptionId：它不参与取令牌。
              </TipNote>
            </template>
          </SectionTitle>
          <el-form label-position="top" size="small" class="pc-form exec-grid2">
            <el-form-item>
              <template #label>
                <FieldLabel label="云环境（authority）" tip="中国云用 login.chinacloudapi.cn。" />
              </template>
              <el-select v-model="state.form.authority" class="pc-full">
                <el-option label="公共云（login.microsoftonline.com）" value="PUBLIC" />
                <el-option label="中国云（login.chinacloudapi.cn）" value="CHINA" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel label="API Server" tip="形如 https://xxx.hcp.chinacloudapi.cn:443。" />
              </template>
              <el-input v-model="state.form.apiServer" placeholder="https://xxx.hcp.chinacloudapi.cn:443" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel label="租户 ID（tenantId）" tip="AAD 目录（租户）id。" />
              </template>
              <el-input v-model="state.form.tenantId" placeholder="00000000-0000-0000-0000-000000000000" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel label="客户端 ID（clientId）" tip="AAD 应用的 applicationId。" />
              </template>
              <el-input v-model="state.form.clientId" placeholder="AAD 应用的 applicationId" />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel
                  label="客户端密钥（clientSecret）"
                  :tip="hasAksSecret ? '已配置。留空 = 保持原样。' : '轮转时先保存新密钥，再验证一次。'"
                />
              </template>
              <el-input
                v-model="state.form.clientSecret"
                type="password"
                show-password
                :placeholder="placeholderIfStored(hasAksSecret, '必填')"
              />
            </el-form-item>
            <el-form-item>
              <template #label>
                <FieldLabel label="scope" tip="留空用默认值。必须以 /.default 结尾。" />
              </template>
              <el-input v-model="state.form.scope" placeholder="6dae42f8-4368-4678-94ff-3960e28e3630/.default" />
            </el-form-item>
            <el-form-item class="span2">
              <template #label>
                <FieldLabel label="集群 CA（caCertPem，可选）" tip="AKS 的 apiserver 证书多半由公共 CA 签发，通常可以留空。" />
              </template>
              <el-input v-model="state.form.caCertPem" type="textarea" :rows="3" class="pc-mono" placeholder="留空 = 用 JVM 信任库" />
            </el-form-item>
          </el-form>
        </template>

        <!-- 3 运行位置 -->
        <SectionTitle title="运行位置">
          <template #tip>
            <TipNote>
              这里的每一项都是<b>提交参数的唯一来源</b>：<code class="pc-mono">application.yml</code>
              里已经没有 namespace / image / callbackBaseUrl 这些键了。改它们不用重启服务端。
            </TipNote>
          </template>
        </SectionTitle>
        <el-form label-position="top" size="small" class="pc-form exec-grid2">
          <el-form-item>
            <template #label>
              <FieldLabel label="命名空间（namespace）" tip="Job 建在这个命名空间里，RFC 1123 名字。" />
            </template>
            <el-input v-model="state.form.namespace" placeholder="例如 paimon-opt" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <FieldLabel
                label="Service Account"
                tip="IRSA / Workload Identity 都挂在这个 SA 上。留空 = 命名空间默认账号。"
              />
            </template>
            <el-input v-model="state.form.serviceAccount" placeholder="留空 = 命名空间默认账号" />
          </el-form-item>
          <el-form-item class="span2">
            <template #label>
              <FieldLabel
                label="镜像（image）"
                tip="必须带 tag 或 digest：只写仓库名会解析成 latest，而 latest + IfNotPresent 是「改了不生效」。"
              />
            </template>
            <el-input v-model="state.form.image" placeholder="registry.example.com/lakehouse-rest-optimizer:1.0.0" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <FieldLabel label="拉取策略" tip="Always 每次重新拉；IfNotPresent 有缓存就用缓存。" />
            </template>
            <el-select v-model="state.form.imagePullPolicy" class="pc-full">
              <el-option v-for="policy in IMAGE_PULL_POLICIES" :key="policy" :label="policy" :value="policy" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <template #label>
              <FieldLabel label="imagePullSecrets" tip="逗号分隔的 Secret 名，最多 8 个；私有仓库才需要。" />
            </template>
            <el-input v-model="state.form.imagePullSecrets" placeholder="registry-cred（多个用逗号分隔）" />
          </el-form-item>
          <el-form-item class="span2">
            <template #label>
              <FieldLabel
                label="回调地址（callbackBaseUrl）"
                tip="必须是「集群里的 Pod 能 curl 通」的服务端地址：同集群用 Service 名（不是 pod 名），跨集群用内网 LB 或对外域名。"
              />
            </template>
            <el-input v-model="state.form.callbackBaseUrl" placeholder="http://lakehouse-rest-server.lakehouse-rest.svc.cluster.local:8080" />
            <div class="pc-field-note pc-field-note-warn">
              同一服务端在不同集群里这一项不同。填错的表现是：Job 起来了、优化跑完了、结果一条都没回来——
              而探活第 5 步（只验服务端 → 地址这个方向）照样通过。
            </div>
          </el-form-item>
        </el-form>

        <!-- 4 批次策略 -->
        <SectionTitle title="批次策略">
          <template #tip>
            <TipNote>
              配额是给<b>这台集群</b>留的余量，所以跟着集群走而不是跟着服务端走：
              同一台集群给两个 catalog 分配不同份额是正常用法。改这三个数不会让状态掉回「待验证」。
            </TipNote>
          </template>
        </SectionTitle>
        <el-form label-position="top" size="small" class="pc-form exec-grid3">
          <el-form-item>
            <template #label>
              <FieldLabel label="单批最多几张表" tip="1–500。批次是按 catalog 攒的，超过这个数就顺延到下一批。" />
            </template>
            <el-input-number v-model="state.form.maxTablesPerJob" :min="1" :max="500" controls-position="right" class="pc-full" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <FieldLabel label="同时几个批次" tip="1–50。超出的留在 PENDING，不判失败。" />
            </template>
            <el-input-number v-model="state.form.maxConcurrentJobs" :min="1" :max="50" controls-position="right" class="pc-full" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <FieldLabel label="批次超时（秒）" tip="300–86400。超过它由跟踪节拍判失败并清理执行侧资源。" />
            </template>
            <el-input-number v-model="state.form.jobTimeoutSeconds" :min="300" :max="86400" :step="600" controls-position="right" class="pc-full" />
          </el-form-item>
          <el-form-item class="span3">
            <template #label>
              <FieldLabel
                label="catalog 数据面令牌（catalogToken，可选）"
                :tip="hasCatalogToken ? '已配置。留空 = 保持原样。' : '下发给任务用于访问 /v1/{prefix}；跨云时通常必须有。'"
              />
            </template>
            <el-input
              v-model="state.form.catalogToken"
              type="password"
              show-password
              :disabled="state.clearCatalogToken"
              :placeholder="state.clearCatalogToken ? '保存后会被清除'
                : placeholderIfStored(hasCatalogToken, '不填则任务拿不到数据面凭据')"
            />
            <el-checkbox
              v-if="hasCatalogToken"
              v-model="state.clearCatalogToken"
              size="small"
              class="exec-clear-token"
            >清除已保存的数据面令牌</el-checkbox>
            <div class="pc-field-note">
              它会以命令行参数出现在 Pod spec 里——同一命名空间能读 pod 定义的人就能读到它；
              作用域应限于数据面读取。
            </div>
          </el-form-item>
        </el-form>

      </div>
    </template>

    <template #footer>
      <div class="exec-foot">
        <template v-if="state.view === 'probe'">
          <el-button size="small" @click="backToForm">返回配置</el-button>
          <span class="pc-dim">验证不会改动配置，只更新状态与结论</span>
          <span class="pc-spacer" />
          <el-button size="small" :loading="state.probing" @click="verify">再验一次</el-button>
          <el-button size="small" type="primary" @click="close">关闭</el-button>
        </template>
        <template v-else>
          <el-switch
            v-model="state.form.enabled"
            size="small"
            inline-prompt
            active-text="启用"
            inactive-text="停用"
          />
          <el-button v-if="configured" size="small" text type="danger" @click="removeConfig">
            删除配置
          </el-button>
          <span class="pc-spacer" />
          <span class="pc-dim exec-foot-hint">{{ footerHint }}</span>
          <el-button size="small" :loading="state.probing" @click="verify">验证连接</el-button>
          <el-button size="small" type="primary" :loading="state.saving" @click="save()">保存</el-button>
        </template>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.exec-head-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exec-head-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.exec-head-sub {
  margin-top: 4px;
  font-size: 12.5px;
}

.exec-loading {
  padding: 24px 0;
  font-size: 13px;
}

.exec-alert {
  margin-bottom: 14px;
}

/* 两列 / 三列栅格。
 * 为什么不用全局的 .pc-form-grid（auto-fit minmax(148px,1fr)）：抽屉里 760px 会被切成
 * 四列，而 EKS 的「区域 + 集群名」「密钥 + 会话令牌」这些字段宽度差得多（集群名 40 字符、
 * region 15 字符），四列会把短字段撑成一整格、长字段挤成一条。这里要的是确定的列数。 */
.exec-grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 14px;
  row-gap: 0;
}

.exec-grid3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  column-gap: 14px;
  row-gap: 0;
}

.exec-grid2 :deep(.el-form-item),
.exec-grid3 :deep(.el-form-item) {
  margin-bottom: 14px;
}

.exec-grid2 :deep(.span2),
.exec-grid3 :deep(.span2) {
  grid-column: 1 / -1;
}

.exec-grid3 :deep(.span3) {
  grid-column: 1 / -1;
}

.exec-clear-token {
  margin-top: 4px;
}

.exec-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.exec-inline-error {
  font-size: 12.5px;
  color: var(--el-color-danger);
}

.exec-type-change {
  margin: -4px 0 6px;
}

/* context 列表：一行一个，不可用的置灰但不隐藏（隐藏会造出「我的 context 去哪了」） */
.exec-ctx-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.exec-ctx {
  padding: 8px 12px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface);
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

.exec-ctx:hover {
  border-color: var(--el-color-primary);
}

.exec-ctx.is-on {
  border-color: var(--el-color-primary);
  background: rgba(29, 78, 216, 0.05);
}

.exec-ctx.is-off {
  cursor: not-allowed;
  opacity: 0.65;
}

.exec-ctx.is-off:hover {
  border-color: var(--pc-border);
}

.exec-ctx-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exec-ctx-name {
  font-size: 13.5px;
  font-weight: 600;
}

.exec-ctx-meta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--pc-text-dim);
  overflow-wrap: anywhere;
}

.exec-ctx-auth {
  margin-top: 2px;
  font-size: 12px;
}

.exec-ctx-note {
  margin-top: 5px;
  padding-left: 8px;
  border-left: 2px solid var(--el-color-warning);
  font-size: 12px;
  line-height: 1.7;
  color: var(--pc-text-dim);
}

/* 探活结果：每一步一行，序号在左，结论与处置在右 */
.exec-step {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--pc-border);
}

.exec-step:last-of-type {
  border-bottom: none;
}

.exec-step-no {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--pc-surface-2, rgba(0, 0, 0, 0.04));
  font-size: 12px;
  font-weight: 600;
  line-height: 22px;
  text-align: center;
  color: var(--pc-text-dim);
}

.exec-step-body {
  flex: 1;
  min-width: 0;
}

.exec-step-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exec-step-title {
  font-size: 13.5px;
  font-weight: 600;
}

.exec-step-summary {
  margin-top: 4px;
  font-size: 12.8px;
  line-height: 1.75;
}

.exec-step-detail {
  margin-top: 4px;
  font-size: 12px;
  color: var(--pc-text-dim);
  overflow-wrap: anywhere;
}

.exec-step-fix {
  margin-top: 6px;
  padding: 6px 10px;
  border-left: 2px solid var(--el-color-warning);
  background: rgba(230, 162, 60, 0.06);
  font-size: 12.5px;
  line-height: 1.75;
}

.exec-probe-note {
  margin-top: 10px;
}

.exec-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.exec-foot-hint {
  font-size: 12px;
}
</style>
