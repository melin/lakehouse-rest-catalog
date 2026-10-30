<template>
  <div class="pc-page">
    <PageHeader :title="title">
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

          <!--
            标签在左、控件在右（`pc-form-label-left`）：这一节的字段名都短（名称 / 表格式 /
            类型），而第 2 节与第 3 节要对照着看，省下来的行高比省下来的列宽值钱。
          -->
          <el-form
            label-position="right"
            label-width="var(--pc-form-label-w)"
            size="small"
            class="pc-form pc-form-label-left"
          >
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
              <!--
                按钮组而不是三张卡：卡片占的高度会让下面「存储类型」被推到首屏之外。
                选完**不再在下面补一句格式说明**——三种格式各自要提醒的事（控制台这一期
                跟到哪、服务端本来就不做什么）已经由本卡片底部的两条 TipAlert 承担，
                而「Paimon 走 /v1/{prefix}/databases/**」这类协议细节属于引擎接入那一节，
                放在这里只是把同一件事说第二遍。
              -->
              <div class="pc-seg">
                <span
                  v-for="option in catalogFormats"
                  :key="option"
                  class="pc-seg-item"
                  :class="{ 'is-on': form.catalogFormat === option }"
                  @click="form.catalogFormat = option"
                >{{ option }}</span>
              </div>
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

            <!--
              编辑态不再显示 `currentEntityVersion`。它是并发保护的版本号，只对
              「两次保存互相踩」有意义，而用户既不能改它、也无法据此做任何决定；
              它仍在 `buildPayload()` 里随更新请求发出（见那个函数），
              409 的处置写在保存失败的分支里。
            -->
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
            认证方式：先选「用哪条路」，再填那条路需要的字段。
            顺序不能反——反过来的话，S3 的 roleArn 与静态密钥会同时出现在一屏里，
            用户既不知道哪个该填，也不知道两个都填会怎样（v3 之前正是这样）。
          -->
          <div v-if="authMethods.length > 1" class="pc-method-grid">
            <button
              v-for="item in authMethods"
              :key="item.id"
              type="button"
              class="pc-method"
              :class="{ 'is-on': method === item.id, 'is-dim': item.state !== 'ok' }"
              :disabled="item.state !== 'ok'"
              @click="selectMethod(item.id)"
            >
              <div class="pc-method-head">
                <span class="pc-method-name">{{ item.title }}</span>
                <el-tag v-if="item.state === 'deployDisabled'" size="small" type="info" effect="plain">
                  本部署未启用
                </el-tag>
                <el-tag v-else-if="item.state === 'notImplemented'" size="small" type="info" effect="plain">
                  本工程暂未接入
                </el-tag>
              </div>
              <div class="pc-method-desc">{{ item.desc }}</div>
              <div v-if="item.state === 'ok'" class="pc-method-get">
                引擎拿到 {{ item.engine }}；轮换：{{ item.rotate }}
              </div>
              <div v-else class="pc-method-get">
                不可用，缺{{ item.state === 'notImplemented' ? '字段与接口' : '部署开关' }}：{{ item.reason }}
              </div>
            </button>
          </div>
          <div v-else-if="authMethods.length === 1" class="pc-method-grid is-single">
            <div class="pc-method is-on">
              <div class="pc-method-head">
                <span class="pc-method-name">{{ authMethods[0].title }}</span>
                <el-tag size="small" type="info" effect="plain">唯一方式</el-tag>
              </div>
              <div class="pc-method-desc">{{ authMethods[0].desc }}</div>
              <div class="pc-method-get">
                引擎拿到 {{ authMethods[0].engine }}；轮换：{{ authMethods[0].rotate }}
              </div>
            </div>
          </div>

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
              :method="method"
            />
          </div>
        </PanelCard>

        <!-- ============================ 3 凭据来源 ============================ -->
        <PanelCard
          title="凭据来源"
          hint="五档之一；只有服务端算得准"
        >
          <template #icon><span class="pc-step">3</span></template>
          <template #actions>
            <el-tag v-if="verdict" size="small" :type="verdict.tag" effect="plain">
              {{ verdict.label }}
            </el-tag>
          </template>

          <!--
            五档是**状态**，不是五个可以点的开关。这一点与原型不同，理由是实的：
            真正决定档位的字段在第 2 节里，而「点一下就能落到这一档」在缺字段时落不下去——
            点了「签发」却没有 roleArn，判定仍然是「无可用凭据」，卡片当场又灭了，
            看起来像坏了。因此这里只写「你现在在哪一档、这一档意味着什么」，
            缺的那一项给一个跳回第 2 节的入口。
          -->
          <!--
            存量横幅（E10）：v3 之前的 catalog 一个密钥都没配，它们不会被自动迁移。
            这档不是错误，但也绝不能安静地留在那里——服务端读它会失败，而用户
            只会在第一次预览时看到一个指向存储的 500。
          -->
          <TipAlert
            v-if="verdictMode === 'LEGACY_ENV'"
            class="pc-form-alert"
            type="warning"
            title="这个 catalog 没有可用凭据（v3 之前的存量）"
          >
            服务端读这个位置用的是进程自己的身份，预览 / 浏览 / 下载可能失败；引擎用它自己的。
            不会被自动迁移：在第 2 节选一种方式、补上字段，再跑一次自检即可。
          </TipAlert>

          <div class="pc-source-list">
            <div class="pc-source" :class="{ 'is-on': Boolean(verdict) }">
              <div class="pc-source-head">
                <span class="pc-source-dot" />
                <span class="pc-source-name">{{ verdict ? verdict.label : '还没取到判定' }}</span>
                <el-tag v-if="currentMethod" size="small" type="info" effect="plain">
                  {{ currentMethod.title }}
                </el-tag>
              </div>
              <div class="pc-source-desc">
                {{ verdict ? verdict.hint : '表单还没填到能判定的程度，或判定接口暂时不可用。' }}
              </div>
            </div>
          </div>

          <!--
            只保留「还没判出来」这一种说明。判定成功时**不再另起一行复述「判定依据」**：
            档位名与它意味着什么已经在上面的卡片里，而那句判定原文说的是同一件事的第二遍
            （「转发 catalog 自带的静态凭据」），多看一行不会多知道一点。
          -->
          <div v-if="!verdict" class="pc-field-note">
            还没有拿到服务端判定（草稿还没填完，或判定接口暂时不可用）。保存后可以在
            catalog 列表的「凭据来源」列看到结果。
          </div>

          <!--
            保存前自检：整个控制台唯一「按用户给的地址真的发一次请求」的地方。
            与上面的标签回答的不是同一个问题——标签说「凭据从哪来」，
            自检说「这份凭据现在能不能用」。一个 VENDED 的 catalog 同样可能因为
            角色信任关系写错而读不了。
          -->
          <div class="pc-probe">
            <div class="pc-probe-head">
              <span>保存前自检</span>
              <span class="pc-probe-hint">
                用表单里这份凭据真的去存储上探一次（不保存任何改动）
              </span>
            </div>
            <div v-if="probeUnavailable" class="pc-field-note">{{ probeUnavailable }}</div>
            <template v-else>
              <div class="pc-probe-actions">
                <el-checkbox v-model="checkWrite" :disabled="probing">
                  连写权限一起探（在仓库里写一个 0 字节对象再删掉）
                </el-checkbox>
                <el-button size="small" type="primary" plain :loading="probing" @click="runProbe">
                  运行自检
                </el-button>
              </div>
              <div v-if="probeVerdict" class="pc-probe-result">
                <el-tag size="small" :type="probeVerdict.tag" effect="plain">
                  {{ probeVerdict.label }}
                </el-tag>
                <span v-if="probeResult.hint" class="pc-probe-detail">{{ probeResult.hint }}</span>
              </div>
              <ul v-if="probeResult && probeResult.checks && probeResult.checks.length" class="pc-probe-checks">
                <li v-for="(check, index) in probeResult.checks" :key="index" class="pc-mono">
                  {{ check.location }} —— {{ check.detail }}
                </li>
              </ul>
            </template>
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

          <!--
            续期开关：只影响 Paimon 两面的 token.provider（bear ↔ oauth2）。
            **默认关**是有意的：老 classpath 上没有 provider jar，片段里直接写 oauth2
            会让 getOrCreate() 当场失败，报的是一句「找不到 factory」——比「不会续期」难查得多。
            打开之后片段会带上需要什么 jar 的注释，代价只是一行可见的说明。
          -->
          <label v-if="renewSwitchable" class="pc-snippet-switch">
            <el-checkbox v-model="renewToken" size="small" />
            <span>主体令牌自动续期（token.provider=oauth2）</span>
            <span class="pc-snippet-switch-hint">需要 lakehouse-rest-engine-auth 在与 paimon bundle 同一个 ClassLoader 里</span>
          </label>

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
          <!--
            续期这件事贴着片段说，不放进「摘要」：它回答的是「这份配置能不能撑住一个
            跑几小时的任务」，而读这句话的人此时正对着配置本身。
            **控制面与数据面分两行**：以前只有一句「能」，而那个「能」说的是数据票——
            真正让长作业挂掉的恰恰是另一条（主体令牌）。合成一句等于把唯一的故障点藏在「能」后面。
          -->
          <div v-if="vendored" class="pc-field-note">
            控制面（主体令牌）：<strong>{{ renewal.control.label }}</strong>——{{ renewal.control.hint }}
          </div>
          <div v-if="vendored" class="pc-field-note">
            数据面（存储凭据）：<strong>{{ renewal.data.label }}</strong>——{{ renewal.data.hint }}
          </div>
          <div v-else class="pc-field-note">
            长作业：服务端不下发凭据，续期由引擎自己的身份提供，与这一页无关。
          </div>
          <div v-if="faces.length === 1 && isLanceCatalog(form.catalogFormat)" class="pc-field-note">
            Lance 只有 Spark 一个页签：connector（<code class="pc-mono">org.lance.spark</code>）没有 Flink 版，
            摆一个出来只会让人去找它。
          </div>
        </PanelCard>

      </div>
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
import { consoleApi } from '@/api/console.js'
import { managementApi } from '@/api/management.js'
import { useCredentialPreview } from '@/composables/credentialPreview.js'
import { notifyError, notifySuccess } from '@/composables/index.js'
import { session } from '@/store/session.js'
import {
  CATALOG_FORMATS,
  catalogFormatLabel,
  catalogFormatTagType,
  isIcebergCatalog,
  isLanceCatalog,
  PAIMON,
} from '@/utils/catalogFormat.js'
import { credentialSourceInfo } from '@/utils/credentialSource.js'
import { availability, inferMethod, methodsFor } from '@/utils/storageAuthMethods.js'
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
 * 有两档长得完全一样——说不清这件事，表单填完也等于没填。页面能给的是
 * 一整节把它讲开（第 3 节）+ 一段可以直接复制给业务方的接入片段（第 4 节），
 * 弹窗里两者都会被滚走。
 *
 * <p><b>曾经有一栏「生效摘要」，已去掉。</b>它回答的四个问题现在各自回到更近的地方：
 * 判定回到第 3 节（那里本来就写着「你现在在哪一档」）、续期回到第 4 节片段下方
 * （读它的人正对着配置）。留在右栏的代价是整页被挤成两栏，而这一页的字段都不长，
 * 右栏一半是空白。
 *
 * <p><b>页尾那张「将提交的报文」也去掉了。</b>它先前是「我填的东西会被翻译成什么」的
 * 唯一自证处，但那份 JSON 与表单同源（同一个 `buildPayload()`），真要核对时读表单本身
 * 更快，而它常驻在页尾占掉一整块。提交体因此**只保留一个来源**：`buildPayload()`，
 * 保存走它，页面上不再有第二处拼给人看的地方。
 *
 * <p><b>字段名与控件同一行</b>（`pc-form-label-left` + 左列 `--pc-form-label-w`）：这一页要
 * 「对照」而不是「依次填」——第 2 节选了什么，第 3 节就落在哪一档，两节隔得太远就
 * 读不出因果。标签在上时每个字段多占一行，两个区块会被拉出视野。
 *
 * <p><b>状态只有一个来源：`form.storageConfigInfo`。</b>存储表单是子组件，它的
 * 内部副本靠 props 同步、靠 `update:modelValue` 回传编译后的提交体，因此保存与
 * 第 3 节的判定都直接读它——不再另存一份「摘要用的状态」，两份迟早对不上。
 *
 * <p><b>判定的东西一律问服务端。</b>「保不保存得住」「保存后凭据从哪来」都由
 * 服务端算（`POST /api/console/v1/catalog-credential-source`）。这个页面只做两件事：
 * 把三档解释清楚、把缺的那一项指出来。它不认识 provider 优先级，也不推理会不会签发。
 */

const route = useRoute()
const router = useRouter()

const isCreate = computed(() => route.name === 'catalogCreate')

const title = computed(() => (isCreate.value ? '新建 Catalog' : `编辑 Catalog：${form.name}`))

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
 * 这边贴在第 3 节的三档卡片上（回答「我现在落在哪一档」）。两次请求都是配置计算，
 * 不查库、不调云，但确实是两次——值得的代价是页面不再自己推理档位。
 */
const { preview } = useCredentialPreview(() => form.storageConfigInfo)
const verdict = computed(() => (preview.value ? credentialSourceInfo(preview.value.mode) : null))
const verdictMode = computed(() => preview.value?.mode || '')
/**
 * 引擎会不会拿到凭据（接入片段要不要写 AK/SK）。
 *
 * <p>只认「签发」与「转发」两档：其余三档（本地 / 无可用凭据 / 配置有误）都没有
 * 凭据可下发。判定没回来时按「有」处理——宁可多写两行配置，也不要让片段少一段
 * 而让引擎在运行时才报认证失败。
 */
const vendored = computed(() => {
  const mode = preview.value?.mode
  if (!mode) return true
  return mode === 'VENDED' || mode === 'STATIC'
})

/**
 * 认证方式：这一页的「选什么」。
 *
 * <p>方式表在 `utils/storageAuthMethods.js`（纯数据，不含档位判定），
 * 这里只补两件服务端才知道的事：本部署启用了哪些签发器（卡片可不可选），
 * 以及当前选中的是哪一条。
 *
 * <p><b>不可用的卡片照样渲染，只是不可选。</b>删掉它们会让「S3 只有静态密钥一种方式」
 * 看起来像设计，而不是缺口；但也不能让它们可选——v3 的底线是「列出来的都能同时
 * 供服务端预览和引擎使用」，一个选得中但用不了的方式比不显示更糟。
 */
const credentialProviders = ref(null)

const authMethods = computed(() => {
  const type = form.storageConfigInfo?.storageType
  return methodsFor(type).map((item) => ({
    ...item,
    state: availability(item, credentialProviders.value),
    reason: item.notImplemented
      ? item.missing
      : `本部署没启用 ${item.provider}（lakehouse.rest.credential.providers）`,
  }))
})

/** 用户点过的卡片；空表示「按已填字段反推」（编辑态第一次进来就是这种）。 */
const method = ref('')

const currentMethod = computed(() => authMethods.value.find((item) => item.id === method.value) || null)

/**
 * 换方式只改这一个 ref。
 *
 * <p>真正的「清掉上一种方式的字段」发生在存储表单里：它按 `method` 过滤字段表，
 * 隐藏的键在下一次 `build()` 里就不写进提交体了。在这里删字段是第二份逻辑，
 * 两份迟早不一致。
 */
function selectMethod(id) {
  method.value = id
}

/** 按当前配置反推方式：编辑态打开页面、以及切了存储类型之后各调一次。 */
function syncMethod() {
  method.value = inferMethod(form.storageConfigInfo?.storageType, form.storageConfigInfo)
}

watch(() => form.storageConfigInfo?.storageType, syncMethod)

// ------------------------------------------------------------------ 保存前自检
const checkWrite = ref(false)
const probing = ref(false)
const probeResult = ref(null)

/**
 * 自检在这个状态下没有意义的两处（E8）。
 *
 * <p>`FILE` 不是「跳过」而是「不适用」：本地仓库没有密钥可校验，两档都点不了，
 * 文案要说清原因，否则用户会以为功能坏了。
 */
const probeUnavailable = computed(() => {
  const type = form.storageConfigInfo?.storageType
  if (type === 'FILE') {
    return '本地仓库不需要认证：没有密钥可校验，读档与写档都不适用。'
  }
  if (!(form.storageConfigInfo?.allowedLocations || []).length) {
    return '先在第 2 节填写允许位置——自检要探的就是这些位置。'
  }
  return ''
})

/** 自检结论的文案。分类由服务端给，这里只负责把它说成人话。 */
const PROBE_VERDICTS = {
  OK: { label: '通过', tag: 'success' },
  DENIED: { label: '被拒绝（权限不足）', tag: 'danger' },
  NOT_FOUND: { label: '路径不可见', tag: 'warning' },
  UNREACHABLE: { label: '连不上', tag: 'danger' },
  UNSUPPORTED: { label: '这个存储不支持探测', tag: 'info' },
  // 与 UNSUPPORTED 分开：这一档是「服务端自己没装配起来」，去查配置；
  // 那一档是「本部署没有这种 scheme 的实现」，去查 file-io.type 与插件。
  // 合成一个标签时，最常见的「拿打码配置去探测」会被指向一个不相干的方向。
  CONFIG_ERROR: { label: '服务端没装配起凭据', tag: 'warning' },
}

const probeVerdict = computed(() => (probeResult.value
  ? PROBE_VERDICTS[probeResult.value.verdict] || { label: probeResult.value.verdict, tag: 'info' }
  : null))

async function runProbe() {
  if (probing.value) return
  probing.value = true
  probeResult.value = null
  try {
    // 编辑态必须带上 catalog 名：提交体里的 accessKeyId 是打码值，服务端要靠这个名字
    // 把库中那份真凭据合并进来。不带它的症状是「探测提示这个存储不支持」，
    // 而真实原因是它拿着一份只有星号的配置去取票了。
    probeResult.value = await consoleApi.probeStorage(
      storageForm.value?.build?.() ?? form.storageConfigInfo,
      checkWrite.value,
      isCreate.value ? '' : form.name,
    )
  } catch (error) {
    // 400 / 429 都是「这份草稿现在不能探」（本地路径、超出限频），
    // 提示已经由 http 层统一给出，这里只把上一次的结论清掉——
    // 留着上一次的「通过」比不显示更危险
    notifyError(error)
  } finally {
    probing.value = false
  }
}

// 改了配置，上一次的结论就不再适用：它验的是另一份草稿
watch(() => form.storageConfigInfo, () => {
  probeResult.value = null
})

/**
 * 「还没落到签发或转发」时，第 2 节缺的是哪一项。
 *
 * <p>只在**什么凭据都没填**时提示，措辞是中立的（「要落到…还需要」而不是「你缺」）：
 * 选「不下发」是合法且常见的一档（引擎有自己的云身份），把它写成待办会逼用户
 * 随便填一份密钥来消掉提示。
 */
const missingHint = computed(() => {
  const payload = form.storageConfigInfo || {}
  // 「具名存储」那个字段已经随具名存储配置组一起删了，因此这里只看真正的两种路：
  // 签发的 roleArn（或 Azure / GCS 的身份字段）与转发的静态密钥
  if (payload.roleArn || payload.accessKeyId || payload.clientId || payload.serviceAccountJson) {
    return ''
  }
  if (preview.value && vendored.value) {
    return ''
  }
  return 'roleArn（签发）或静态凭据（转发）'
})

/**
 * 「这个格式撑不撑得住一个长作业」。**控制面与数据面分开答**（见 `RENEWAL`），
 * 因此不折算成一个「能/不能」的布尔——Iceberg 那一档控制面是「能（服务端支持 exchange 时）」、
 * 数据面是「要看客户端」，折掉之后就只剩一句会误导人的「能」。
 *
 * <p>默认值兜住「认不出的格式」：控制台宁可在这一行写「未知」，也不假装能续。
 */
const UNKNOWN_RENEWAL = { control: { label: '未知', hint: '' }, data: { label: '未知', hint: '' } }
const renewal = computed(() => RENEWAL[form.catalogFormat] || UNKNOWN_RENEWAL)

// ------------------------------------------------------------------ 接入片段
const faces = computed(() => snippetFaces(form.catalogFormat))
const activeFace = ref(defaultSnippetFace(PAIMON))

/**
 * 「主体令牌自动续期」开关，**默认关**。
 *
 * <p>默认关不是保守，是必须：片段里写 `token.provider=oauth2` 而 classpath 上没有
 * 本工程的 provider jar 时，Paimon 在建 catalog 那一刻就失败，报的是一句
 * `Could not find any factory for identifier 'oauth2'`——排查方向会跑到「版本对不对」
 * 而不是「少了一个 jar」。默认关时片段与今天逐字一致，想续期的人会看到开关并读到
 * 它需要什么。
 */
const renewToken = ref(false)

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
    // 只对 Paimon 两面生效：Iceberg 原生的 oauth2 本来就会续期，Lance 那一面没有 provider 概念。
    // 开关放在 `renewable` 上而不是直接改片段文本，是为了让「切 face 之后取值仍然一致」
    // ——按面各写一份状态的话，切页签会看到两个答案
    renewable: renewSwitchable.value && renewToken.value,
  })
})

/**
 * 续期开关要不要显示。
 *
 * <p>只在 Paimon 面显示：Iceberg 那一面本来就在续（`rest.auth.type=oauth2` 是它原生的），
 * 摆一个开关会让人以为不打开就不续；Lance 面没有 provider 机制，摆出来是假的。
 */
const renewSwitchable = computed(() => String(activeFace.value).includes('paimon'))

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

// ------------------------------------------------------------------ 提交体
/**
 * 送出去的那一份。创建态与编辑态**不是同一个报文**：更新不带 `catalogFormat`
 * （服务端把它当不可变列，改就 400），而创建必须带（不带的话服务端用部署默认值，
 * 会出现「表单里选 Paimon、建出来是 Iceberg」）。这里照实分叉。
 *
 * <p><b>页面上不再显示这份 JSON</b>（页尾那张「将提交的报文」卡片已去掉）。但它仍是
 * 唯一的提交体来源：保存走它，`StorageConfigFields.build()` 是它里面那块存储配置的
 * 来源。去掉卡片不改变这条约定，反而更紧——没有第二处「再拼一份给人看」的地方，
 * 也就没有「存进去的和看到的不是同一份」。
 */
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

  // 本部署启用了哪些签发器：方式卡片的「可选 / 本部署未启用」全靠它。
  // 失败时保持 null —— 卡片按「未知」处理（不置灰），而不是统统判成未启用：
  // 把取不到数据说成「你的部署没开这个功能」，是这一格最容易犯的错。
  consoleApi.credentialSources()
    .then((payload) => {
      credentialProviders.value = payload?.credentialProviders || []
    })
    .catch(() => {})

  if (isCreate.value) {
    form.storageConfigInfo = {
      storageType: supportedTypes.value[0] || 'FILE',
      allowedLocations: [],
    }
    syncMethod()
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
    // 方式由已保存的字段反推：编辑一个「配了 roleArn」的 catalog 时，
    // 卡片要亮在「IAM 角色」上，否则第 2 节会把 roleArn 藏在折叠区里
    syncMethod()
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
/* 单列，铺满可用宽度。原先这里是「左表单 + 右 340 摘要」的两栏，右栏的「生效摘要」与页尾的
   「将提交的报文」都去掉之后，页面就是一条直线往下读。

   页面不再收在 1080：收窄之后**卡片只占左边一半，而页头的「保存」贴着窗口右边缘**——
   两者不在一个容器里，看起来就像按钮飘在空白里（这正是改这一处的原因）。
   现在页头与卡片同宽，按钮落在卡片右边缘的正上方。 */
.pc-form-cols {
  width: 100%;
}

.pc-form-main {
  min-width: 0;
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

/* 续期开关：夹在页签与代码块之间，读片段之前先看到「这份配置会不会续」 */
.pc-snippet-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 2px;
  color: var(--pc-text-dim);
  font-size: 12px;
  cursor: pointer;
}

.pc-snippet-switch-hint {
  color: var(--pc-text-faint, var(--pc-text-dim));
  font-size: 11.5px;
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

/* ---------------------------------------------------------------- 认证方式卡片 */

/* 一行几张：S3 三张、OSS 三张、OBS 两张、COS/Azure/GCS 一张（走 `.is-single`）。
   不用 grid-auto-fit：三张卡片的文字长度差很大，等宽会让「静态访问密钥」那张
   空出半行，而它恰是多数人要点的那一张。 */
.pc-method-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.pc-method-grid.is-single {
  grid-template-columns: 1fr;
}

.pc-method {
  display: block;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  font: inherit;
  color: var(--pc-text);
  background: var(--pc-bg-subtle);
  border: 1px solid var(--pc-border);
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.pc-method.is-on {
  border-color: var(--el-color-primary);
  background: var(--pc-bg-active);
}

.pc-method.is-dim {
  cursor: not-allowed;
  border-style: dashed;
  opacity: 0.68;
}

.pc-method-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.pc-method-name {
  font-weight: 600;
}

.pc-method-desc {
  font-size: 12px;
  line-height: 1.55;
  color: var(--pc-text-secondary);
}

/* 「引擎拿到什么」单独一行：它才是几种方式之间真正的差别——
   能不能用是三线全过的共同前提，拿到什么才需要人选。 */
.pc-method-get {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--pc-text-tertiary);
}

/* ---------------------------------------------------------------- 保存前自检 */

.pc-probe {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--pc-border);
}

.pc-probe-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  font-weight: 600;
}

.pc-probe-hint {
  font-weight: 400;
  font-size: 12px;
  color: var(--pc-text-tertiary);
}

.pc-probe-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.pc-probe-result {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 8px;
}

.pc-probe-detail {
  font-size: 12px;
  color: var(--pc-text-secondary);
}

.pc-probe-checks {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--pc-text-secondary);
}
</style>
