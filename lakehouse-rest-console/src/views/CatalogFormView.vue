<template>
  <div class="pc-page">
    <PageHeader :title="title" :subtitle="subtitle">
      <template #actions>
        <el-button size="small" @click="cancel">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">
          {{ isCreate ? '创建' : '保存' }}
        </el-button>
      </template>
    </PageHeader>

    <!--
      读失败（编辑态才可能）单独一屏：这时候表单里的值全是空的，把它渲染出来
      会让人以为「这个 catalog 没有配置」，而真相是「没读到」。
    -->
    <ResourceState
      v-if="loadError"
      :loading="false"
      :error="loadError"
      :empty="false"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'catalogs' })">返回列表</el-button>
      </template>
    </ResourceState>

    <div v-else v-loading="loading" class="pc-form-cols">
      <div class="pc-form-main">
        <!-- ============================ 1 基本信息 ============================ -->
        <PanelCard title="基本信息" hint="只影响元数据，与凭据无关">
          <template #icon><span class="pc-step">1</span></template>

          <el-form label-position="top" size="small" class="pc-form">
            <el-form-item v-if="isCreate" required>
              <template #label>
                <FieldLabel
                  label="名称（name / prefix）"
                  tip="创建后不可改名，改名等同于删除重建。它同时是客户端连接时用的 warehouse。"
                />
              </template>
              <el-input v-model="form.name" placeholder="例如 paimon，之后所有 /v1/{prefix}/... 都用它" />
            </el-form-item>

            <el-form-item v-else>
              <template #label>
                <FieldLabel label="名称（name / prefix）" tip="创建后不可改名。要换名字只能删除重建。" />
              </template>
              <div class="pc-readonly-row">
                <span class="pc-mono">{{ form.name }}</span>
                <span class="pc-dim">创建后不可更改</span>
              </div>
            </el-form-item>

            <!--
              表格式**只在创建时可选**：服务端把它当不可变列（改格式 = 删除重建），
              编辑态因此是一行只读展示——一个灰着的选择框会让人以为「再点点看能不能改」，
              而这件事在服务端根本不存在。
            -->
            <el-form-item v-if="isCreate" required>
              <template #label>
                <FieldLabel
                  label="表格式（catalogFormat）"
                  tip="决定这个 prefix 服务哪一套 REST 协议。创建后不可更改，要换格式请删除重建。"
                />
              </template>
              <div class="pc-pick-grid pc-pick-grid-three">
                <div
                  v-for="option in formatOptions"
                  :key="option.value"
                  class="pc-pick"
                  :class="{ 'is-on': form.catalogFormat === option.value }"
                  @click="form.catalogFormat = option.value"
                >
                  <div class="pc-pick-name">
                    <span class="pc-pick-dot" />
                    {{ option.label }}
                  </div>
                  <div class="pc-pick-desc">{{ option.desc }}</div>
                  <div class="pc-pick-meta pc-mono">{{ option.path }}</div>
                </div>
              </div>
              <div class="pc-field-note">{{ catalogFormatNote(form.catalogFormat) }}</div>
            </el-form-item>

            <el-form-item v-else>
              <template #label>
                <FieldLabel
                  label="表格式（catalogFormat）"
                  tip="创建后不可更改：同一个 prefix 下两种格式的元数据代数与客户端可见的端点都不同。"
                />
              </template>
              <div class="pc-readonly-row">
                <el-tag size="small" effect="plain" :type="catalogFormatTagType(form.catalogFormat)">
                  {{ catalogFormatLabel(form.catalogFormat) }}
                </el-tag>
                <span class="pc-dim">创建后不可更改</span>
              </div>
            </el-form-item>

            <el-form-item v-if="isCreate">
              <template #label>
                <FieldLabel
                  label="类型（type）"
                  tip="INTERNAL 由本服务管理元数据；EXTERNAL 只作为已有仓库的入口。与表格式正交，可组合。"
                />
              </template>
              <el-select v-model="form.type" class="pc-full">
                <el-option v-for="type in catalogTypes" :key="type" :label="type" :value="type" />
              </el-select>
            </el-form-item>

            <el-form-item v-else>
              <template #label>
                <FieldLabel
                  label="当前版本（currentEntityVersion）"
                  tip="并发保护：版本与服务端不一致时更新会被拒绝（409），此时请刷新后重试。"
                />
              </template>
              <el-input :model-value="String(form.entityVersion)" disabled />
            </el-form-item>
          </el-form>

          <!-- 格式的「控制台这一期跟到哪」与「服务端本来就不做什么」分开说：
               合并之后，用户会把「界面没做」读成「不支持」。 -->
          <TipAlert
            v-if="isCreate && isIcebergCatalog(form.catalogFormat)"
            class="pc-form-alert"
            type="warning"
            title="控制台一期不提供 Iceberg catalog 的表管理与数据浏览"
          >
            创建后可以在 catalog 详情查看协议与能力，表数据请通过 Spark 等 Iceberg REST 客户端访问。
          </TipAlert>
          <TipAlert
            v-if="isCreate && isLanceCatalog(form.catalogFormat)"
            class="pc-form-alert"
            type="warning"
            title="控制台不提供 Lance catalog 的库表浏览"
          >
            Lance 数据集由客户端写进仓库，服务端只做登记（declare / register）。创建后可以在
            catalog 详情看协议与能力、在「文件列表」里看 <code class="pc-mono">_versions/</code> 与
            <code class="pc-mono">data/</code>；库与表请用 Lance 客户端访问。
          </TipAlert>
        </PanelCard>

        <!-- ============================ 2 存储 ============================ -->
        <PanelCard
          title="存储"
          hint="同时决定凭据发给哪一家云、用哪一族键名"
        >
          <template #icon><span class="pc-step">2</span></template>

          <TipAlert v-if="!supportedTypes.length" type="warning" class="pc-form-alert">
            <template #title>未能读取服务端支持的存储类型</template>
            <div>
              存储类型的可选范围来自 <code class="pc-mono">GET /api/console/v1/meta</code>。
              当前读不到，表单只能给出保守的默认值（FILE），提交时可能被服务端以
              「本部署未接入该存储实现」拒绝。可先到「连接设置」确认地址与令牌。
            </div>
          </TipAlert>

          <!--
            key 让每次进入页面都拿到一个新实例：这个组件的表单是内部副本（靠 props 同步、
            靠 update:modelValue 回传），重建实例比依赖 watch 更不容错。
          -->
          <div ref="storageCard" class="pc-storage-anchor" :class="{ 'is-flash': flashStorage }">
            <StorageConfigFields
              :key="isCreate ? 'create' : `edit:${form.name}`"
              ref="storageForm"
              v-model="form.storageConfigInfo"
              :storage-types="storageTypes"
              :supported-types="supportedTypes"
              :static-credentials-enabled="staticCredentialsEnabled"
            />
          </div>
        </PanelCard>

        <!-- ============================ 3 凭据来源 ============================ -->
        <PanelCard
          title="凭据来源"
          hint="三档之一；只有服务端算得准"
        >
          <template #icon><span class="pc-step">3</span></template>
          <template #actions>
            <el-tag v-if="verdict" size="small" :type="verdict.tag" effect="plain">
              {{ verdict.label }}
            </el-tag>
          </template>

          <!--
            三档是**状态**，不是三个可以点的开关。
            这一点与原型不同，理由是实的：真正决定档位的字段（静态凭据 / roleArn /
            stsUnavailable）在第 2 节里，而「点一下就能落到这一档」在缺字段时落不下去——
            点了「签发」却没有 roleArn，判定仍然是「不下发」，卡片当场又灭了，
            看起来像坏了。因此这里把「你现在在哪一档」和「要落到这一档需要什么」分开写，
            缺的那一项给一个跳回第 2 节的入口。
          -->
          <div class="pc-source-list">
            <div
              v-for="option in SOURCE_OPTIONS"
              :key="option.mode"
              class="pc-source"
              :class="{ 'is-on': verdictMode === option.mode }"
            >
              <div class="pc-source-head">
                <span class="pc-source-dot" />
                <span class="pc-source-name">{{ option.label }}</span>
                <el-tag v-if="verdictMode === option.mode" size="small" type="success" effect="plain">
                  当前
                </el-tag>
              </div>
              <div class="pc-source-desc">{{ option.desc }}</div>
              <div class="pc-source-need">{{ option.need }}</div>
            </div>
          </div>

          <!-- 判定原文：它是服务端算的，页面只负责显示。缺字段时这句话就是「为什么不是你以为的那一档」 -->
          <div v-if="verdict" class="pc-field-note">
            <strong>判定依据：</strong>{{ verdict.detail || verdict.hint }}
          </div>
          <div v-else class="pc-field-note">
            还没有拿到服务端判定（草稿还没填完，或判定接口暂时不可用）。保存后可以在
            catalog 列表的「凭据来源」列看到结果。
          </div>

          <div v-if="missingHint" class="pc-source-jump">
            <span>
              要落到「签发 / 转发」，第 2 节还需要 <code class="pc-mono">{{ missingHint }}</code>；
              保持「不下发」则不必填。
            </span>
            <el-button size="small" text type="primary" @click="jumpToStorage">去第 2 节</el-button>
          </div>

          <!-- 作用域：描述的是「下发时会被收窄到哪」，不是整桶 -->
          <div class="pc-scope">
            <div class="pc-scope-head">
              <span>允许位置</span>
              <span class="pc-scope-hint">凭据按表路径收窄，不是整个桶；跨桶时多个位置合成一条语句</span>
            </div>
            <ul class="pc-scope-body">
              <li v-for="(location, index) in allowedLocations" :key="index" class="pc-mono">
                {{ location }}
                <span v-if="index === 0" class="pc-scope-root">仓库根</span>
              </li>
              <li v-if="!allowedLocations.length" class="pc-scope-empty">还没有填写允许位置</li>
            </ul>
          </div>

          <div class="pc-field-note">
            取票要 <code class="pc-mono">USE_CREDENTIAL</code>，票里含库里的长期密钥时还要
            <code class="pc-mono">USE_SECRET</code>；两者并列，只授后者取不到票。
          </div>
        </PanelCard>

        <!-- ============================ 4 引擎接入配置 ============================ -->
        <PanelCard
          title="引擎接入配置"
          hint="按上面的选择生成，可直接复制"
        >
          <template #icon><span class="pc-step">4</span></template>

          <div class="pc-snippet-tabs">
            <span
              v-for="face in faces"
              :key="face.id"
              class="pc-snippet-tab"
              :class="{ 'is-on': face.id === activeFace }"
              @click="activeFace = face.id"
            >{{ face.label }}</span>
          </div>

          <div class="pc-snippet-wrap">
            <el-button class="pc-snippet-copy" size="small" text @click="copySnippet">复制</el-button>
            <pre class="pc-snippet"><code><span
              v-for="(line, index) in snippet.lines"
              :key="index"
              class="pc-snippet-line"
              :class="`is-${line.kind}`"
            ><template v-if="line.kind === 'pair'"><span class="pc-snip-key">{{ line.key }}</span><span class="pc-snip-eq">=</span><span class="pc-snip-val">{{ line.value }}</span></template><template v-else>{{ line.text }}</template></span></code></pre>
          </div>

          <div v-if="snippet.note" class="pc-field-note">{{ snippet.note }}</div>
          <div v-if="faces.length === 1 && isLanceCatalog(form.catalogFormat)" class="pc-field-note">
            Lance 只有 Spark 一个页签：connector（<code class="pc-mono">org.lance.spark</code>）没有 Flink 版，
            摆一个出来只会让人去找它。
          </div>
        </PanelCard>
      </div>

      <!-- ============================ 右栏 ============================ -->
      <aside class="pc-form-side">
        <PanelCard title="生效摘要" hint="保存后会怎样">
          <dl class="pc-kv">
            <dt>协议面</dt>
            <dd>{{ catalogFormatLabel(form.catalogFormat) }}</dd>
            <dt>存储</dt>
            <dd>{{ form.storageConfigInfo?.storageType || '—' }}</dd>
            <dt>凭据来源</dt>
            <dd>
              <el-tag v-if="verdict" size="small" :type="verdict.tag" effect="plain">
                {{ verdict.label }}
              </el-tag>
              <span v-else class="pc-dim">判定未就绪</span>
            </dd>
            <dt>作用域</dt>
            <dd>{{ allowedLocations[0] || '—' }}</dd>
            <dt>风险</dt>
            <dd>
              <el-tag size="small" :type="risk.tag" effect="plain">{{ risk.label }}</el-tag>
              <span class="pc-dim pc-small">{{ risk.hint }}</span>
            </dd>
            <dt>长作业</dt>
            <dd>
              <template v-if="vendored">{{ renewal.label }}</template>
              <span v-else class="pc-dim">不适用（引擎自管凭据）</span>
            </dd>
          </dl>
          <div v-if="vendored" class="pc-field-note">{{ renewal.hint }}</div>
        </PanelCard>

        <PanelCard title="将提交的报文" hint="与下面表单同源">
          <pre class="pc-payload">{{ payloadText }}</pre>
          <div class="pc-field-note">
            <code class="pc-mono">******</code> 表示「沿用已保存的那一份」，它不是空串——
            空串才是「清除」。
          </div>
        </PanelCard>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import StorageConfigFields from '@/components/StorageConfigFields.vue'
import TipAlert from '@/components/TipAlert.vue'
import { managementApi } from '@/api/management.js'
import { useCredentialPreview } from '@/composables/credentialPreview.js'
import { notifyError, notifySuccess } from '@/composables/index.js'
import { session } from '@/store/session.js'
import {
  CATALOG_FORMATS,
  catalogFormatLabel,
  catalogFormatNote,
  catalogFormatOption,
  catalogFormatTagType,
  isIcebergCatalog,
  isLanceCatalog,
  PAIMON,
} from '@/utils/catalogFormat.js'
import { credentialSourceInfo } from '@/utils/credentialSource.js'
import {
  buildEngineSnippet,
  defaultSnippetFace,
  RENEWAL,
  snippetFaces,
} from '@/utils/engineSnippet.js'

/**
 * 新建 / 编辑 Catalog 的独立页面。
 *
 * <p><b>为什么从弹窗改成页面。</b>弹窗能装下一张表单，装不下一页的「后果」：
 * 这个对象上真正危险的字段只有一处（凭据从哪来），而它的三档在客户端配置上
 * 有两档长得完全一样——说不清这件事，表单填完也等于没填。页面能给的是旁边一栏
 * 常驻摘要（判定、作用域、风险、续期）和一段可以直接复制给业务方的接入片段，
 * 这两样都要在填的过程中一直看得见，弹窗里做不到（一滚就没了）。
 *
 * <p><b>状态只有一个来源：`form.storageConfigInfo`。</b>存储表单是子组件，它的
 * 内部副本靠 props 同步、靠 `update:modelValue` 回传编译后的提交体，因此右栏的
 * 报文与摘要都直接读它——不再另存一份「摘要用的状态」，两份迟早对不上。
 *
 * <p><b>判定的东西一律问服务端。</b>「保不保存得住」「保存后凭据从哪来」都由
 * 服务端算（`POST /api/console/v1/catalog-credential-source`）。这个页面只做两件事：
 * 把三档解释清楚、把缺的那一项指出来。它不认识 provider 优先级，也不推理会不会签发。
 */

const route = useRoute()
const router = useRouter()

const isCreate = computed(() => route.name === 'catalogCreate')

const title = computed(() => (isCreate.value ? '新建 Catalog' : `编辑 Catalog：${form.name}`))
const subtitle = computed(() => (isCreate.value
  ? '名称与表格式创建后不可更改。这一页唯一会影响「引擎要不要自带 AK/SK」的是第 3 节的凭据来源——'
    + '它有三档，而其中两档在客户端配置上完全一样，只有服务端知道区别。'
  : '这里能改的是存储配置与目录属性；名称与表格式是创建时定下的，改它们等于删除重建。'))

const loading = ref(false)
const loadError = ref('')
const saving = ref(false)

const meta = computed(() => session.state.meta)
const catalogTypes = computed(() => meta.value?.enums?.catalogTypes || ['INTERNAL', 'EXTERNAL'])
const storageTypes = computed(() => meta.value?.enums?.storageTypes || ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'FILE'])
const supportedTypes = computed(() => meta.value?.enums?.supportedStorageTypes || [])
/**
 * 本部署能否保存静态凭据（服务端配了落库加密密钥）。
 *
 * <p>取不到元数据时按 false 处理：乐观放开的代价是用户填完 AK/SK 再吃一个 400，
 * 而保守禁用的代价只是「暂时不能填」，且表单会说明原因。
 */
const staticCredentialsEnabled = computed(() => meta.value?.enums?.staticCredentialsEnabled === true)
const catalogFormats = computed(() => meta.value?.enums?.catalogFormats || CATALOG_FORMATS)
const formatOptions = computed(() => catalogFormats.value.map((value) => ({
  value,
  ...catalogFormatOption(value),
})))
/** 未指定格式时服务端用的那一种：用它预选，而不是恒选第一项（否则部署改过默认就会错位）。 */
const defaultCatalogFormat = computed(() => meta.value?.service?.defaultCatalogFormat || PAIMON)

const form = reactive({
  name: '',
  type: 'INTERNAL',
  catalogFormat: PAIMON,
  entityVersion: 0,
  properties: {},
  storageConfigInfo: { storageType: 'FILE', allowedLocations: [] },
})

const storageForm = ref(null)
const storageCard = ref(null)
const flashStorage = ref(false)

const allowedLocations = computed(() => form.storageConfigInfo?.allowedLocations || [])

/**
 * 草稿的实时判定。**与存储表单里那一行是同一个接口**：那边贴着字段（改完立刻看见），
 * 这边贴在摘要（回答「保存后会怎样」）。两次请求都是配置计算，不查库、不调云，
 * 但确实是两次——值得的代价是「摘要不再自己推理档位」。
 */
const { preview } = useCredentialPreview(() => form.storageConfigInfo)
const verdict = computed(() => (preview.value ? credentialSourceInfo(preview.value.mode) : null))
const verdictMode = computed(() => preview.value?.mode || '')
const vendored = computed(() => (preview.value ? preview.value.mode !== 'NONE' : true))

/**
 * 三档的说明。`need` 那一行写的是「要落到这一档，第 2 节里必须有哪个字段」——
 * 这是这一页唯一能替用户做判断的地方（字段值本身在前端可见），
 * 而「会不会真的签发」仍然只有服务端说了算。
 */
const SOURCE_OPTIONS = [
  {
    mode: 'STS',
    label: '签发短期凭据',
    desc: '服务端拿自己的身份去云上现签，按表路径收窄、到期自然失效。泄漏的影响面是「这一张表 + 这一段时间」。',
    need: '需要：第 2 节「存储高级参数」里的 roleArn，且本部署启用了对应的签发 provider。',
  },
  {
    mode: 'RELAY',
    label: '转发长期密钥',
    desc: '把 catalog 里存着的长期 AK/SK 原样发给引擎。能兼容没有 STS 的存储（自建 MinIO / Ceph），但这把钥匙不会过期。',
    need: '需要：第 2 节填了静态凭据（accessKeyId / secretAccessKey），或引用了具名存储。',
  },
  {
    mode: 'NONE',
    label: '不下发',
    desc: '只发定位信息（endpoint / region / path-style），密钥由引擎自己的身份提供（实例角色、环境变量、服务账号）。',
    need: '需要：勾上 stsUnavailable，或干脆什么凭据都不填——两者结果一样，但前者是明确的意图。',
  },
]

/**
 * 「还没落到签发或转发」时，第 2 节缺的是哪一项。
 *
 * <p>只在**什么凭据都没填**时提示，措辞是中立的（「要落到…还需要」而不是「你缺」）：
 * 选「不下发」是合法且常见的一档（引擎有自己的云身份），把它写成待办会逼用户
 * 随便填一份密钥来消掉提示。
 */
const missingHint = computed(() => {
  const payload = form.storageConfigInfo || {}
  if (payload.roleArn || payload.accessKeyId || payload.storageName) {
    return ''
  }
  if (preview.value && verdictMode.value !== 'NONE') {
    return ''
  }
  return 'roleArn（签发）或静态凭据（转发）'
})

const risk = computed(() => {
  if (!preview.value) {
    return { label: '未知', tag: 'info', hint: '判定未就绪' }
  }
  if (preview.value.mode === 'STS') {
    return { label: '低', tag: 'success', hint: '到期自然失效' }
  }
  if (preview.value.mode === 'RELAY') {
    return { label: '高', tag: 'danger', hint: '永久密钥，泄漏只能轮换' }
  }
  if (preview.value.mode === 'INVALID') {
    return { label: '配置有误', tag: 'danger', hint: '取票时会失败' }
  }
  return { label: '中', tag: 'warning', hint: '身份在引擎侧' }
})

const renewal = computed(() => RENEWAL[form.catalogFormat] || { label: '未知', hint: '' })

// ------------------------------------------------------------------ 接入片段
const faces = computed(() => snippetFaces(form.catalogFormat))
const activeFace = ref(defaultSnippetFace(PAIMON))

/**
 * 换格式就把页签落到新格式的第一个。
 *
 * <p>不这么做的话，从「Paimon + Spark」切到 Iceberg，`activeFace` 还停在
 * `spark-paimon`，匹配不上于是回落到 `faces[0]`——片段内容是对的，但页签高亮
 * 一个都不亮，读起来像「这一面没有配置」。切换只发生在创建态（编辑态格式不可变）。
 */
watch(() => form.catalogFormat, (format) => {
  activeFace.value = defaultSnippetFace(format)
})

// 换格式（只在创建时有这个动作）之后，页签要落到新格式的第一个
const snippet = computed(() => {
  const face = faces.value.find((item) => item.id === activeFace.value) || faces.value[0]
  if (!face) {
    return { lines: [], note: '', copyText: '' }
  }
  return buildEngineSnippet({
    format: form.catalogFormat,
    face: face.id,
    catalog: form.name,
    location: allowedLocations.value[0] || '',
    storageType: form.storageConfigInfo?.storageType || '',
    vendored: vendored.value,
  })
})

async function copySnippet() {
  const text = snippet.value.copyText
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    notifySuccess('已复制接入配置')
  } catch {
    // 剪贴板在非 https / 无权限时不可用。这时**不假装成功**：
    // 一句「已复制」而实际没复制，人会直接粘贴到别处，然后贴出一段旧内容
    notifyError(new Error('浏览器拒绝了剪贴板访问，请手动选中复制'))
  }
}

/** 跳到第 2 节并把卡片闪一下：缺的那一项在折叠区里，滚过去之后要能看见它在哪。 */
function jumpToStorage() {
  storageCard.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flashStorage.value = true
  window.setTimeout(() => { flashStorage.value = false }, 1600)
}

// ------------------------------------------------------------------ 报文
/**
 * 右栏的「将提交的报文」。
 *
 * <p>编辑态与创建态**不是同一个报文**：更新不带 `catalogFormat`（服务端把它当不可变列，
 * 改就 400），而创建必须带（不带的话服务端用部署默认值，会出现「表单里选 Paimon、
 * 建出来是 Iceberg」）。这里照实分叉，避免有人照着右栏拼一个更新请求却多发了字段。
 */
const payloadText = computed(() => JSON.stringify(buildPayload(), null, 2))

function buildPayload() {
  const storageConfigInfo = storageForm.value?.build?.() ?? form.storageConfigInfo
  if (isCreate.value) {
    return {
      type: form.type,
      name: form.name.trim(),
      catalogFormat: form.catalogFormat,
      properties: form.properties,
      storageConfigInfo,
    }
  }
  return {
    currentEntityVersion: form.entityVersion,
    properties: form.properties,
    storageConfigInfo,
  }
}

// ------------------------------------------------------------------ 载入 / 保存
onMounted(async () => {
  // 页面的候选值（存储类型、格式、能否存静态凭据）全部来自 meta。它通常已由顶栏
  // bootstrap() 取回；直接深链接进这一页时可能还没到，补一次（与概览页同一做法）。
  if (!meta.value) {
    session.loadMeta()
  }

  // 格式要先定下来，页签才有得选：编辑态下面会用服务端返回的格式覆盖
  form.catalogFormat = defaultCatalogFormat.value
  activeFace.value = defaultSnippetFace(form.catalogFormat)

  if (isCreate.value) {
    form.storageConfigInfo = {
      storageType: supportedTypes.value[0] || 'FILE',
      allowedLocations: [],
    }
    return
  }

  const catalogName = route.params.catalogName
  loading.value = true
  try {
    const catalog = await managementApi.getCatalog(catalogName)
    form.name = catalog.name
    form.type = catalog.type || 'INTERNAL'
    form.catalogFormat = catalog.catalogFormat || defaultCatalogFormat.value
    activeFace.value = defaultSnippetFace(form.catalogFormat)
    form.entityVersion = catalog.entityVersion ?? 0
    form.properties = { ...(catalog.properties || {}) }
    form.storageConfigInfo = catalog.storageConfigInfo
      ? JSON.parse(JSON.stringify(catalog.storageConfigInfo))
      : fallbackStorage(catalog)
  } catch (error) {
    loadError.value = error?.message || '读取 catalog 失败'
  } finally {
    loading.value = false
  }
})

/**
 * 服务端没有返回 `storageConfigInfo` 时的兜底（早期版本登记的 catalog）。
 *
 * <p>不能给空对象：表单会在「允许位置」为空上卡住，用户被迫手填一个本来就已知的位置。
 * 仓库位置通常就在 `properties.warehouse` 里——**回填而不是改写**，用户看到值并确认，
 * 比表单替他决定要好。
 */
function fallbackStorage(catalog) {
  const warehouse = catalog.properties?.warehouse
  return {
    storageType: supportedTypes.value[0] || 'FILE',
    allowedLocations: warehouse ? [warehouse] : [],
  }
}

async function save() {
  if (isCreate.value && !form.name.trim()) {
    notifyError(new Error('请填写 catalog 名称'))
    return
  }
  const invalid = storageForm.value?.validate?.()
  if (invalid) {
    notifyError(new Error(invalid))
    return
  }
  saving.value = true
  try {
    const catalogName = isCreate.value ? form.name.trim() : route.params.catalogName
    if (isCreate.value) {
      await managementApi.createCatalog({
        type: form.type,
        name: catalogName,
        // 格式创建后不可变，因此必须建表这一步就带上
        catalogFormat: form.catalogFormat,
        properties: form.properties,
        storageConfigInfo: buildPayload().storageConfigInfo,
      })
      notifySuccess(`已创建 catalog ${catalogName}（${catalogFormatLabel(form.catalogFormat)} 协议面）`)
    } else {
      // 编辑**不带** catalogFormat：服务端把改格式当 400 拒绝
      await managementApi.updateCatalog(catalogName, buildPayload())
      notifySuccess(`已更新 catalog ${catalogName}`)
    }
    await session.loadCatalogs()
    session.selectCatalog(catalogName)
    // 建完直接进详情：下一页要看的就是「判定对不对、能力有哪些」，
    // 回列表还要再点一次同一个名字
    router.replace({ name: 'catalogDetail', params: { catalogName } })
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}

function cancel() {
  if (!isCreate.value) {
    router.push({ name: 'catalogDetail', params: { catalogName: route.params.catalogName } })
    return
  }
  router.push({ name: 'catalogs' })
}
</script>

<style scoped>
/* 两栏：左表单宽、右摘要 340。与原型一致，也保证「判定」与「你正在填的那一项」
   在同一屏里——摘要掉到下面去之后，它就不再是摘要了 */
.pc-form-cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 14px;
  align-items: start;
}

.pc-form-main {
  min-width: 0;
}

/* 表格式是「三选一」：三张卡必须排在同一行。
 * 全局的 .pc-pick-grid 是 auto-fit（按最小列宽自动折行），窄一点时第三张会被挤到
 * 下一行，看起来像「先二选一、再看情况」——而三种格式在服务端是同一级的。
 * 这里钉死三列，把「一行」变成明确意图；minmax(0, 1fr) 让长路径
 * （/lance/v1/namespace/{id}）在卡片内折行而不是撑破栅格。 */
.pc-pick-grid-three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

@media (max-width: 860px) {
  .pc-pick-grid-three {
    grid-template-columns: 1fr;
  }
}

.pc-form-side {
  position: sticky;
  top: 12px;
}

@media (max-width: 1180px) {
  .pc-form-cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .pc-form-side {
    position: static;
  }
}

/* 卡片头的序号。用圆形徽标而不是把「1」写进标题文字：
   标题要能单独被复制/搜索，带序号的字符串会让「基本信息」变成「1 基本信息」 */
.pc-step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 19px;
  height: 19px;
  margin-right: 7px;
  border-radius: 50%;
  background: rgba(29, 78, 216, 0.1);
  color: var(--pc-accent);
  font-family: var(--pc-mono-font, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11.5px;
  font-weight: 600;
}

.pc-small {
  font-size: 11.5px;
}

/* ---------------- 三档来源 ---------------- */
.pc-source-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 900px) {
  .pc-source-list {
    grid-template-columns: 1fr;
  }
}

.pc-source {
  padding: 10px 12px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface);
}

/* 当前那一档：描边 + 极浅底。只靠底色在截图里看不出来，
   只靠描边在浅色主题下也不够显眼（与 .pc-pick 同一套判断） */
.pc-source.is-on {
  border-color: var(--el-color-primary);
  background: rgba(29, 78, 216, 0.05);
}

.pc-source-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}

.pc-source-dot {
  width: 9px;
  height: 9px;
  border: 1.5px solid #c2cbd9;
  border-radius: 50%;
}

.pc-source.is-on .pc-source-dot {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
}

.pc-source-name {
  color: var(--pc-text);
}

.pc-source-desc {
  margin-top: 5px;
  color: var(--pc-text-dim);
  font-size: 11.5px;
  line-height: 1.55;
}

.pc-source-need {
  margin-top: 6px;
  color: var(--pc-text-dim);
  font-size: 11px;
  line-height: 1.5;
}

.pc-source-jump {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 12.5px;
}

/* ---------------- 作用域 ---------------- */
.pc-scope {
  margin-top: 12px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  overflow: hidden;
}

.pc-scope-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--pc-surface-2);
  border-bottom: 1px solid var(--pc-border);
  font-size: 12px;
  color: var(--pc-text-dim);
}

.pc-scope-hint {
  margin-left: auto;
  font-size: 11px;
}

.pc-scope-body {
  margin: 0;
  padding: 8px 10px;
  list-style: none;
  font-size: 11.5px;
  line-height: 1.9;
}

.pc-scope-root {
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 2px;
  background: var(--pc-surface-2);
  color: var(--pc-text-dim);
  font-size: 10.5px;
}

.pc-scope-empty {
  color: var(--pc-text-dim);
}

/* 跳转后闪一下：目标在折叠区里，滚过去之后没有提示就等于没滚 */
.pc-storage-anchor {
  transition: box-shadow 0.2s;
  border-radius: var(--pc-radius);
}

.pc-storage-anchor.is-flash {
  box-shadow: 0 0 0 2px rgba(29, 78, 216, 0.35);
}

/* ---------------- 片段 ---------------- */
.pc-snippet-tabs {
  display: flex;
  gap: 2px;
  border-bottom: 1px solid var(--pc-border);
  margin: -4px 0 0;
}

.pc-snippet-tab {
  padding: 7px 10px 6px;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  color: var(--pc-text-dim);
  font-size: 12.5px;
  cursor: pointer;
}

.pc-snippet-tab.is-on {
  border-bottom-color: var(--el-color-primary);
  color: var(--el-color-primary);
  font-weight: 600;
}

.pc-snippet-wrap {
  position: relative;
}

.pc-snippet {
  margin: 0;
  /* 右侧留出复制按钮的宽度：按钮是绝对定位的，长键名会从它底下穿过去，
     首行看起来像被截断了。滚动条出现时这段内边距也正好让内容不被压在按钮下 */
  padding: 10px 68px 10px 12px;
  max-height: 320px;
  overflow: auto;
  border-radius: var(--pc-radius);
  background: #0f1729;
  color: #d6e2f5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.75;
}

.pc-snippet-line {
  display: block;
  white-space: pre;
}

.pc-snippet-line.is-comment {
  color: #6b8099;
}

/* 键与值分色：一眼能扫出「这一行改的是哪个键」，复制起来也不至于把
   `s3.access-key` 这种带点的键看错 */
.pc-snip-key {
  color: #93c5fd;
}

.pc-snip-val {
  color: #fcd34d;
}

.pc-snip-eq {
  color: #6b8099;
}

.pc-snippet-copy {
  position: absolute;
  top: 4px;
  right: 6px;
  color: #cbd5e1;
  /* 深色底上的按钮需要自己的底色：滚动时下面的文本会从它旁边掠过，
     没有底就会和代码叠在一起 */
  background: rgba(15, 23, 41, 0.92);
  border-radius: 4px;
}

.pc-snippet-copy:hover {
  color: #fff;
}

/* ---------------- 摘要 ---------------- */
.pc-kv {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 7px 10px;
  margin: 0;
  font-size: 12.5px;
}

.pc-kv dt {
  color: var(--pc-text-dim);
}

.pc-kv dd {
  margin: 0;
  word-break: break-all;
}

.pc-payload {
  margin: 0;
  padding: 9px 11px;
  max-height: 300px;
  overflow: auto;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface-2);
  color: var(--pc-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
