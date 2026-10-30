<template>
  <div class="pc-storage">
    <el-form label-position="top" size="small" class="pc-form">
      <el-form-item required>
        <template #label>
          <FieldLabel label="存储类型（storageType）">
            仅可选择本部署已接入的存储实现（{{ supportedTypes.join(' / ') || '未知' }}），
            由服务端 <code>lakehouse.rest.file-io.type</code> 决定。
            <template v-if="unknownType">
              当前值 <code>{{ unknownType }}</code> 不在本控制台的类型表里，
              保存时会原样带回，不会被改写。
            </template>
          </FieldLabel>
        </template>
        <!--
          按钮组而不是下拉：七种存储之间的差别只在上方那句说明里，下拉把它们藏进
          一个需要点开的浮层，选完就再也看不到「另外六种是什么」。
          未接入的类型**仍然列出来**（灰掉），因为「这里能选 S3、不能选 OSS」
          会让人去查 lakehouse.rest.file-io.type；直接不显示只会像界面漏了一项。
        -->
        <div class="pc-seg">
          <span
            v-if="unknownType"
            class="pc-seg-item"
            :class="{ 'is-on': form.storageType === unknownType }"
          >{{ unknownType }}（未识别）</span>
          <span
            v-for="type in orderedTypes"
            :key="type"
            class="pc-seg-item"
            :class="{ 'is-on': form.storageType === type, 'is-disabled': isUnsupported(type) }"
            @click="selectType(type)"
          >{{ storageTypeLabel(type) }}</span>
        </div>
      </el-form-item>


      <el-form-item required>
        <template #label>
          <FieldLabel
            label="允许位置（allowedLocations）"
            tip="首项同时作为仓库根（warehouse）；白名单之外的位置服务端会拒绝写入。"
          />
        </template>
        <div class="pc-repeat">
          <div v-for="(_, index) in form.allowedLocations" :key="index" class="pc-repeat-row">
            <el-input
              v-model="form.allowedLocations[index]"
              size="small"
              :placeholder="locationPlaceholder"
            />
            <el-button size="small" text type="danger" @click="removeLocation(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button size="small" text type="primary" @click="form.allowedLocations.push('')">
            <el-icon><Plus /></el-icon>
            <span class="pc-repeat-add">添加位置</span>
          </el-button>
        </div>
      </el-form-item>

      <!--
        「保存后凭据从哪来」的预览。位置刻意在「允许位置」之后：它要等填了位置才出现，
        放在上面就会在用户正在输入时把输入框顶下去。而它又必须靠前——
        它描述的是**整份配置**的后果，roleArn 藏在下面的折叠区里，改完那一项
        要立刻看见结论，不该再往下翻。

        三档里后两档（转发长期密钥 / 不下发）在客户端配置上完全一样，差别只在服务端发哪把
        钥匙；而「填了 roleArn 但没部署签发」这一种，结果与「什么都没填」也一样。
        这句话只有服务端算得准，因此这里显示的是它的判定原文，前端只负责配色与措辞。
      -->
      <div
        v-if="credentialPreview"
        class="pc-cred-preview"
        :class="`is-${credentialPreview.mode.toLowerCase()}`"
      >
        <span class="pc-cred-label">保存后凭据来源：{{ credentialPreview.label }}</span>
        <span class="pc-cred-detail">{{ credentialPreview.detail }}</span>
      </div>

      <!-- COS 的三面差异必须在这里说清楚：把三个面当成一样，等于让两个面静默失败。
           Paimon 用 COSN 原生（凭据能下发、长作业能续）；
           Iceberg 的 HadoopFileIO 不消费 storage-credentials 里的 config，收不到下发凭据；
           Lance 是 Rust connector，根本没有 COSN 实现，只能另建 S3 类型 catalog。 -->
      <TipAlert v-if="form.storageType === 'COS'" type="info" class="pc-storage-carry">
        <template #title>COS 走 COSN 原生，但只有 Paimon 面能收到下发的凭据</template>
        <div class="pc-field-note">
          <b>Paimon</b>：<code>cosn://</code> + <code>fs.cosn.*</code>，服务端下发的密钥生效，
          长作业按 <code>data-token.enabled=true</code> 自行续期。<br />
          <b>Iceberg</b>：没有 COS FileIO，只能 <code>io-impl=HadoopFileIO</code> 并自行配
          <code>spark.hadoop.fs.cosn.*</code>——它不读服务端下发的
          <code>storage-credentials</code>。<br />
          <b>Lance</b>：connector 是 Rust，没有 COSN 实现，请另建一个 S3 类型的 catalog
          走 <code>s3://</code> + COS 的 S3 兼容端点。
        </div>
      </TipAlert>

      <el-form-item>
        <template #label>
          <FieldLabel label="具名存储（storageName）">
            填服务端 <code>lakehouse.rest.storage.&lt;厂商&gt;.storages</code> 下的键名。
            这种做法让密钥只留在服务端配置里，不出现在 catalog 元数据中。
          </FieldLabel>
        </template>
        <el-input v-model="form.storageName" placeholder="留空则使用服务端默认凭据" />
      </el-form-item>

      <template v-for="field in basicFields" :key="field.key">
        <el-form-item>
          <template #label>
            <FieldLabel :label="field.label" :tip="field.hint" />
          </template>
          <el-switch v-if="field.type === 'bool'" v-model="form.extras[field.key]" />
          <div v-else-if="field.type === 'list'" class="pc-repeat">
            <div v-for="(_, index) in form.extras[field.key]" :key="index" class="pc-repeat-row">
              <el-input v-model="form.extras[field.key][index]" size="small" :placeholder="field.placeholder || ''" />
              <el-button size="small" text type="danger" @click="form.extras[field.key].splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="form.extras[field.key].push('')">
              <el-icon><Plus /></el-icon>
              <span class="pc-repeat-add">添加一项</span>
            </el-button>
          </div>
          <el-input v-else v-model="form.extras[field.key]" :placeholder="field.placeholder || ''" />
        </el-form-item>
      </template>

      <template v-if="supportsStaticCredentials">
        <SectionTitle title="静态凭据（可选）" />

        <el-form-item>
          <template #label>
            <FieldLabel
              label="accessKeyId"
              tip="该 catalog 访问对象存储用的长期密钥 ID。与具名存储（storageName）互斥：两者都在回答「用哪把钥匙」。已保存时服务端只返回 ******，留空即沿用那把钥匙。"
            />
          </template>
          <el-input
            v-model="form.accessKeyId"
            :disabled="!staticCredentialsEnabled"
            :placeholder="accessKeyPlaceholder"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            <FieldLabel
              label="secretAccessKey"
              tip="只写不读：服务端从不回显该值（库内加密保存）。留空表示保持已保存的密钥不变。"
            />
          </template>
          <el-input
            v-model="form.secretAccessKey"
            type="password"
            show-password
            :disabled="!staticCredentialsEnabled"
            :placeholder="secretPlaceholder"
          />
        </el-form-item>

        <!-- roleArn 与静态凭据是两条并存的取密钥路径。服务端今天都接受
             （`StorageConfigs` 只拒绝「静态凭据 × storageName」），所以这里
             只提示、不拦截：前端比服务端更严，会让一份服务端认可的配置在控制台里存不回去。
             之所以仍要说一句，是因为 roleArn 收进折叠区后，用户更难自己发现。 -->
        <p v-if="credentialConflict" class="pc-field-note pc-field-note-warn">
          已同时配置 <code>accessKeyId</code>（含已保存的静态凭据）与「存储高级参数」里的 <code>roleArn</code>。
          两者是两条并存的取密钥路径（直接用它 / 由服务端 assume 角色），建议只保留一种。
        </p>
        <!-- 能力关着时给出可操作的出路：字段被禁用只是一半，
             不告诉用户「去开哪个配置项、密钥怎么生成」就会停在死路上 -->
        <p v-if="!staticCredentialsEnabled" class="pc-static-disabled-hint">
          本部署未开启静态凭据：服务端需要配置
          <code>lakehouse.rest.storage.credential-secret-key</code>（用于加密落库），
          生成命令：<code>openssl rand -base64 32</code>。
        </p>

        <el-form-item v-if="storedAccessKeyId && !form.removeCredentials">
          <el-button size="small" text type="danger" @click="form.removeCredentials = true">
            <el-icon><Delete /></el-icon>
            <span class="pc-repeat-add">移除已保存的静态凭据</span>
          </el-button>
        </el-form-item>

        <el-alert v-if="form.removeCredentials" type="info" :closable="false" show-icon class="pc-storage-carry">
          <template #title>保存后将移除这个 catalog 的静态凭据</template>
          <div>
            之后该 catalog 会回到「服务端配置的密钥」这条路径（默认凭据 → 环境凭据链）。
            <el-button size="small" text type="primary" @click="form.removeCredentials = false">取消移除</el-button>
          </div>
        </el-alert>
      </template>

      <!-- 存储高级参数：默认收起。
           收起态必须同时给出三件事，否则折叠就退化成「把字段藏起来」：
           ① 归属（标题）；② 计数（徽标）；③ 为什么可以不管它（右侧一行说明）。
           缺 ③，用户会以为这里有必填项而被迫展开——那才是折叠真正的成本。

           为什么自绘而不用 el-collapse：它的类名不是稳定的公开契约（见
           `styles/index.css` 开头），为了 2px 圆角与这套字号去覆盖内部结构，
           比这十几行更脆。键盘可达与语义由 button + aria-* 自己保证。

           为什么用 v-show 而不是 v-if：收起时保留 DOM，展开/收起不重建子控件，
           也就不会打断正在输入的焦点。 -->
      <div v-if="advancedFields.length" class="pc-collapse" :class="{ 'is-open': advancedOpen }">
        <button
          type="button"
          class="pc-collapse-head"
          :aria-expanded="advancedOpen ? 'true' : 'false'"
          @click="advancedOpen = !advancedOpen"
        >
          <span class="pc-caret" aria-hidden="true"></span>
          <span class="pc-collapse-title">存储高级参数</span>
          <span class="pc-collapse-badge" :class="{ 'is-filled': filledAdvancedKeys.length }">
            {{ advancedBadge }}
          </span>
          <span class="pc-collapse-hint">全部留空即用服务端默认值</span>
        </button>

        <div v-show="advancedOpen" class="pc-collapse-body">
          <p class="pc-collapse-note">
            本区字段随存储类型变化，当前 <code>{{ form.storageType }}</code> 有
            {{ advancedFields.length }} 项。它们会原样写入 catalog 元数据（服务端接受并回显）；
            是否影响引擎行为，取决于服务端的凭据实现。
          </p>

          <template v-for="(group, index) in advancedGroups" :key="group.name || index">
            <div v-if="group.name" class="pc-grp">
              <span class="pc-grp-title">{{ group.name }}</span>
              <span class="pc-grp-line"></span>
            </div>
            <el-form-item v-for="field in group.fields" :key="field.key">
              <template #label>
                <FieldLabel :label="field.label" :tip="field.hint" />
              </template>
              <el-switch v-if="field.type === 'bool'" v-model="form.extras[field.key]" />
              <div v-else-if="field.type === 'list'" class="pc-repeat">
                <div v-for="(_, itemIndex) in form.extras[field.key]" :key="itemIndex" class="pc-repeat-row">
                  <el-input v-model="form.extras[field.key][itemIndex]" size="small" :placeholder="field.placeholder || ''" />
                  <el-button size="small" text type="danger" @click="form.extras[field.key].splice(itemIndex, 1)">
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </div>
                <el-button size="small" text type="primary" @click="form.extras[field.key].push('')">
                  <el-icon><Plus /></el-icon>
                  <span class="pc-repeat-add">添加一项</span>
                </el-button>
              </div>
              <el-input v-else v-model="form.extras[field.key]" :placeholder="field.placeholder || ''" />
            </el-form-item>
          </template>
        </div>
      </div>

      <!-- 收起时把「已填了什么」露出来。编辑一个别人建的 catalog 时，
           没有这一行就会以为这些配置不存在，而它们会被原样提交回服务端。 -->
      <p
        v-if="advancedFields.length && !advancedOpen && filledAdvancedKeys.length"
        class="pc-collapse-summary"
      >
        已填：<code>{{ filledAdvancedKeys.join('、') }}</code>
        （展开可查看；留空的项不会出现在提交体里）
      </p>

      <TipAlert
        v-if="carriedKeys.length"
        type="info"
        class="pc-storage-carry"
      >
        <template #title>以下字段没有对应控件，保存时原样带回服务端</template>
        <div class="pc-mono pc-break">{{ carriedKeys.join('、') }}</div>
      </TipAlert>
    </el-form>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import TipAlert from '@/components/TipAlert.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import { useCredentialPreview } from '@/composables/credentialPreview.js'
import { credentialSourceInfo } from '@/utils/credentialSource.js'
import { isUnsupportedStorageType, storageTypeLabel, sortStorageTypes } from '@/utils/storageType.js'

/**
 * `StorageConfigInfo` 的表单。
 *
 * <p>按 `storageType` 动态出字段，而不是把六种存储的字段全列出来：
 * 服务端把 `StorageConfigInfo` 建模成判别联合，每种类型只接受自己的字段
 * （见 `StorageDtos`），表单跟着这个结构走，才能保证提交上去的组合是合法的。
 * 切换类型时构造新对象而不是就地删字段，避免残留上一个类型的属性。
 *
 * <p><b>两条不能违反的约定</b>（违反的后果是静默改坏配置，而不是报错）：
 *
 * <ol>
 *   <li><b>外部传入的 `modelValue` 必须能进入表单。</b>组件内部维护一份可编辑副本，
 *       如果只在创建时读一次 `props`，那么第二次打开对话框（编辑）时表单停留
 *       在上一次的值上，保存就会把 A 类型改写成 B 类型。这里用
 *       {@link watch} 同步，并用序列化快照区分「外部改了」与「自己刚发出去的」
 *       ——与 `PropertiesEditor` 用同一套办法。
 *   <li><b>不认识的字段必须原样带回。</b>服务端将来给某个存储类型加字段时，
 *       控制台的表单没有对应控件；如果 `build()` 只输出自己的已知字段，
 *       一次「只改了个名字」的保存会把这些字段抹掉。因此这里记住原始对象里
 *       表单覆盖不到的部分并一起提交。
 * </ol>
 */

/** 所有子类型共有的字段。 */
const COMMON_KEYS = ['storageType', 'allowedLocations', 'storageName']

/**
 * 静态凭据的字段名，以及哪些存储类型有这对字段。
 *
 * <p>它们是服务端 `StorageConfigInfo` 的扩展字段（规格里没有「用户填 AK/SK」的位置），
 * 语义与普通字段不同：`secretAccessKey` 只写不读，服务端从不回显，
 * 因此这里单独处理，不走下面 `BASIC_FIELDS` / `ADVANCED_FIELDS` 那套「空值即删除」
 * 的通用规则——对它来说「空」的含义是「保持不变」，见 `build()` 的说明。
 */
const STATIC_CREDENTIAL_KEYS = ['accessKeyId', 'secretAccessKey']

/**
 * 与服务端 {@code StorageConfigs.MASKED} 必须逐字相同的哨兵值。
 *
 * <p>两边各写一份字面量而不是共用常量：这个仓库没有跨 Java/JS 的构建期常量通道，
 * 而它出现在**协议**上（对外响应里就是这个字符串，客户端照抄即对），
 * 所以它更像一个 wire 值，不是内部枚举。改的时候两边一起改，用例会盯着服务端那一侧。
 */
const MASKED_ACCESS_KEY_ID = '******'

const STATIC_CREDENTIAL_TYPES = ['S3', 'OBS', 'OSS', 'COS']

/**
 * 各存储类型的字段，按「要不要在创建时就决定」分成两张表。
 *
 * <p><b>基本字段</b>：每次建 catalog 都要做的决定，不填服务端就落不了地
 * （仓库定位靠它们）。留在主表单里平铺。
 *
 * <p><b>高级字段</b>：只有特定场景才需要动、多数人留空也正确的。收进
 * 「存储高级参数」折叠区，默认收起。
 *
 * <p><b>为什么拆成两张表，而不是给字段加一个 `advanced: true` 标记</b>：
 * 拆表让「哪些进折叠区」这件事在数据里就看得见，而不是散在模板的 `v-if` 里；
 * 更重要的是，`build()` 读的是下面 `fields` 这个**合并后**的视图，
 * 因此它完全不知道自己被分了层——「折叠只改变可见性、绝不改变提交内容」
 * 这条约束于是由结构保证，不靠自觉。
 *
 * <p>分层的判据只有一条：<b>不填就跑不起来的留在外面，其余收进去</b>。
 * 收进去的不等于不重要——`roleArn` 是另一条取密钥的路径，它被收起是因为
 * 跨账户访问在本部署里是少数场景。
 *
 * <p>`group` 只是折叠区里的分组归属（`null` 表示不分组）。S3 有 10 项、
 * 其中 6 项同属「STS 与角色」，分组能把这层语义显出来；其余类型只有 1–3 项，
 * 不为了整齐硬造只有一个成员的分组。
 */
const BASIC_FIELDS = {
  S3: [
    { key: 'region', label: 'region', placeholder: 'us-east-1' },
    { key: 'endpoint', label: 'endpoint', placeholder: 'https://s3.us-east-1.amazonaws.com' },
  ],
  GCS: [],
  AZURE: [
    { key: 'tenantId', label: 'tenantId', placeholder: 'Azure 租户 ID（必填）' },
  ],
  OBS: [
    { key: 'endpoint', label: 'endpoint', placeholder: 'obs.cn-north-4.myhuaweicloud.com' },
  ],
  OSS: [
    { key: 'endpoint', label: 'endpoint', placeholder: 'oss-cn-hangzhou.aliyuncs.com' },
  ],
  COS: [
    { key: 'endpoint', label: 'endpoint', placeholder: 'cos.ap-guangzhou.myqcloud.com' },
    { key: 'bucketRegion', label: 'bucketRegion', placeholder: 'ap-guangzhou' },
  ],
  FILE: [],
}

/**
 * 折叠区里的字段（见 `BASIC_FIELDS` 的说明）。
 *
 * <p>OBS / OSS 各只有 `stsUnavailable`：这是本工程的扩展类型，字段刻意保持最小
 * （服务端 `StorageDtos` 的说明里写了为什么不给它们 `region`——两家云的
 * FileIO 配置面都没有这一项，区域含在 endpoint 里）。
 *
 * <p>`FILE` 是空数组：本地文件系统没有凭据概念，折叠区整个不出现。
 * 判据是「当前类型有没有高级字段」，不是「类型是不是 S3」——服务端以后
 * 给某个类型加字段，这里加上就会自动出现。
 */
const ADVANCED_FIELDS = {
  S3: [
    { key: 'roleArn', label: 'roleArn', placeholder: '跨账户访问时填写', group: 'STS 与角色' },
    { key: 'externalId', label: 'externalId', group: 'STS 与角色' },
    { key: 'userArn', label: 'userArn', group: 'STS 与角色' },
    { key: 'stsEndpoint', label: 'stsEndpoint', placeholder: '可选，STS 端点', group: 'STS 与角色' },
    { key: 'endpointInternal', label: 'endpointInternal', placeholder: '可选，内网端点', group: 'STS 与角色' },
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool', group: 'STS 与角色' },
    { key: 'pathStyleAccess', label: 'pathStyleAccess', type: 'bool', group: '访问方式' },
    { key: 'kmsUnavailable', label: 'kmsUnavailable', type: 'bool', group: 'KMS 与加密' },
    { key: 'encryptionKeys', label: 'encryptionKeys', type: 'list', placeholder: 'KMS 密钥 ARN', group: 'KMS 与加密' },
    { key: 'decryptionKeys', label: 'decryptionKeys', type: 'list', placeholder: 'KMS 密钥 ARN', group: 'KMS 与加密' },
  ],
  GCS: [
    { key: 'gcsServiceAccount', label: 'gcsServiceAccount', placeholder: '服务账号邮箱' },
  ],
  AZURE: [
    { key: 'multiTenantAppName', label: 'multiTenantAppName' },
    { key: 'consentUrl', label: 'consentUrl' },
    { key: 'hierarchical', label: 'hierarchical', type: 'bool' },
  ],
  OBS: [
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
  ],
  OSS: [
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
  ],
  COS: [
    {
      key: 'appId',
      label: 'appId',
      placeholder: '1250000000（留空则不校验桶名）',
      hint: '不是下发键：COS 的 appId 是桶名的一部分（cosn://<bucket>-<appid>/）'
        + '。填了会在保存时校验位置里的桶名确实以它结尾。',
    },
    {
      key: 'useHttps',
      label: 'useHttps',
      type: 'bool',
      default: true,
      hint: 'COSN 自己的缺省是不走 HTTPS（fs.cosn.useHttps=false）。'
        + '这里默认打开；只有私有化部署的 COS 兼容实现只能走 HTTP 时才关掉。',
    },
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
  ],
  FILE: [],
}

/** 本控制台认识的存储类型（基本表与高级表覆盖到的并集）。 */
const KNOWN_TYPES = new Set([...Object.keys(BASIC_FIELDS), ...Object.keys(ADVANCED_FIELDS)])

/**
 * 某个类型的全部字段，按「基本在前、高级在后」。
 *
 * <p>顺序有意义：`build()` 顺着它写 `config`，报文里的键序因此与界面一致，
 * 排查时对着表单和请求体可以一一对应。
 */
function fieldsOf(type) {
  return [...(BASIC_FIELDS[type] || []), ...(ADVANCED_FIELDS[type] || [])]
}

/**
 * 各类型允许的位置前缀。
 *
 * <p>用于拦「类型选 S3、位置填 file:///...」这类组合：两者各自都合法，
 * 拼在一起服务端只有到真正读写时才会失败，而那时错误已经离操作现场很远了。
 */
const LOCATION_SCHEMES = {
  S3: ['s3://', 's3a://'],
  GCS: ['gs://'],
  AZURE: ['abfss://', 'abfs://', 'wasbs://'],
  OBS: ['obs://'],
  OSS: ['oss://'],
  COS: ['cosn://'],
  FILE: ['file://', 'file:/', '/'],
}

const props = defineProps({
  modelValue: { type: Object, default: () => ({}) },
  /** 全部存储类型取值（服务端元数据） */
  storageTypes: { type: Array, default: () => ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'COS', 'FILE'] },
  /** 本部署实际支持的存储类型 */
  supportedTypes: { type: Array, default: () => [] },
  /**
   * 本部署能否保存静态凭据（服务端是否配了加密密钥）。
   *
   * <p>默认 false 而不是 true：读不到元数据时（服务端旧版本、或连接未就绪）
   * 乐观地放开输入，只会让用户填完再吃一个服务端 400。
   */
  staticCredentialsEnabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

/** 表单不认识、但要原样带回服务端的字段。 */
const carried = ref({})

/**
 * 服务端返回的 accessKeyId：**已保存时它是打码值 {@code ******}**，不是真值。
 *
 * <p>因此它只用来回答两个问题——「有没有已保存的凭据」与「保存时要不要发哨兵」，
 * 绝不能当作用户可以编辑的值（灌进输入框那一步已经在做区分）。
 */
const storedAccessKeyId = ref('')

const form = reactive({
  storageType: '',
  allowedLocations: [],
  storageName: '',
  extras: {},
  accessKeyId: '',
  secretAccessKey: '',
  /** 显式移除已保存的静态凭据（勾上则提交一对空串，见 build()）。 */
  removeCredentials: false,
})

/** 当前类型是否承载静态凭据。 */
const supportsStaticCredentials = computed(() => STATIC_CREDENTIAL_TYPES.includes(form.storageType))

/**
 * 「按现在这样填，保存后凭据从哪来」——问服务端，前端不算。
 *
 * <p>输入用 `props.modelValue`（也就是 `build()` 已经发出去的那一份），而不是再调一次
 * `build()`：两者应当相同，而一旦不同，说明「预览的输入」与「真正提交的东西」
 * 是两条路——那正是这类预览最容易出的错。
 */
const { preview: credentialDraft } = useCredentialPreview(() => props.modelValue)

/**
 * 把服务端的判定翻成标签与一句说明。`null` 表示「没有可靠的判定」——那时整条不显示，
 * 而不是显示一个「不下发」：把「不知道」写成某一档，是在替服务端下一个它没下的结论。
 */
const credentialPreview = computed(() => {
  if (!credentialDraft.value?.mode) return null
  return {
    ...credentialSourceInfo(credentialDraft.value.mode),
    mode: credentialDraft.value.mode,
    detail: credentialDraft.value.detail || '',
  }
})

const secretPlaceholder = computed(() => (storedAccessKeyId.value
  ? '已保存密钥，留空即保持不变'
  : '仅填写时不回显，请确认无误'))

/**
 * accessKeyId 的占位提示。
 *
 * <p>已保存时服务端只回 {@code ******}，因此输入框里**不放星号**：放了用户会以为那就是真值，
 * 编辑时很容易把星号改成「星号加一个字符」，服务端不认、报错又说不清。
 * 留空 + 明确说「沿用已保存的」才是同一件事的正确表达。
 */
const accessKeyPlaceholder = computed(() => (storedAccessKeyId.value
  ? '已保存（******），留空即保持不变'
  : '例如 minioadmin'))

/** 表单覆盖的键集合（按类型不同）。两张字段表都要算进来，否则分层会让字段被当成「不认识」。 */
function knownKeys(type) {
  const keys = new Set([...COMMON_KEYS, ...fieldsOf(type).map((field) => field.key)])
  if (STATIC_CREDENTIAL_TYPES.includes(type)) {
    STATIC_CREDENTIAL_KEYS.forEach((key) => keys.add(key))
  }
  return keys
}

/** 服务端返回的类型不在本控制台的类型表里时，保留原值并提示。 */
const unknownType = computed(() => {
  const type = form.storageType
  if (!type) return ''
  return KNOWN_TYPES.has(type) ? '' : type
})

function collectExtras(source, type) {
  const extras = {}
  for (const field of fieldsOf(type)) {
    const value = source[field.key]
    if (field.type === 'bool') {
      // 布尔字段可以带 `default`（见 COS 的 useHttps）。字段缺省时灌入的是那个默认值，
      // 而不是 false——否则「服务端默认 true」会在打开表单后被改写成 false。
      extras[field.key] = value === undefined || value === null
        ? Boolean(field.default)
        : Boolean(value)
    } else if (field.type === 'list') {
      extras[field.key] = Array.isArray(value) ? value.map((item) => String(item ?? '')) : []
    } else {
      extras[field.key] = value ?? ''
    }
  }
  return extras
}

function splitCarried(source, type) {
  const known = knownKeys(type)
  const rest = {}
  for (const [key, value] of Object.entries(source || {})) {
    if (!known.has(key)) {
      rest[key] = value
    }
  }
  return rest
}

function defaultType() {
  return props.supportedTypes[0] || 'FILE'
}

/**
 * 把外部对象灌进表单。
 *
 * <p>类型只认「外部给了什么」；没给才退回本部署支持的第一个类型。
 * 这里不做「不在 supportedTypes 里就纠正」的处理：那会把一份服务端认可、
 * 只是本控制台不支持编辑的配置改坏。
 */
function adopt(source) {
  const next = source || {}
  const type = next.storageType || defaultType()
  form.storageType = type
  form.allowedLocations = [...(next.allowedLocations || [])]
  form.storageName = next.storageName || ''
  form.extras = collectExtras(next, type)
  // 两个字段都不回显真值：AK ID 回来的是打码值 ******，密钥则根本不出现。
  // 已保存的那一份记在 storedAccessKeyId（只用来回答「有/无」并给 build() 提供哨兵），
  // 输入框一律留空——把星号灌进去会被当成真值继续编辑，而服务端只认「原样回传的星号」，
  // 改一个字符就变成一把不存在的钥匙。
  const credentialTypes = STATIC_CREDENTIAL_TYPES.includes(type)
  storedAccessKeyId.value = credentialTypes ? (next.accessKeyId || '') : ''
  form.accessKeyId = ''
  form.secretAccessKey = ''
  form.removeCredentials = false
  carried.value = splitCarried(next, type)
  // 换了一份配置（打开对话框、切到另一个 catalog）就回到收起态：
  // 这是「默认收起」在组件不重建时也能成立的地方。它不监听 form 自身的变化，
  // 因此用户手动展开后不会被打断——那条路径上的变化都被 lastEmitted 挡在 watch 外面。
  advancedOpen.value = false
}

/**
 * 基本字段（主表单平铺）与高级字段（折叠区）。
 *
 * <p>`fields` 是两者的合并视图，<b>只给 `build()` 用</b>，刻意不暴露给模板：
 * 模板要的是「分区的两半」，给它合并后的整体就无从判断哪一半该进折叠区了。
 */
const basicFields = computed(() => BASIC_FIELDS[form.storageType] || [])
const advancedFields = computed(() => ADVANCED_FIELDS[form.storageType] || [])
const fields = computed(() => [...basicFields.value, ...advancedFields.value])

/**
 * 折叠区是否展开。
 *
 * <p>初始恒为 `false`，且<b>不由「是否有值」反推</b>：一旦写成「有值就展开」，
 * 编辑一个别人建的 catalog 时它每次都会弹开，用户刚收起它这件事被忽略。
 * 「我收起来了它自己又开」比「我得多点一下」糟得多。
 */
const advancedOpen = ref(false)

/** 分组的下限：少于 6 项不分组（理由见 `ADVANCED_FIELDS` 的说明）。 */
const GROUP_THRESHOLD = 6

/** 折叠区内的分组；不分组时返回一个无名组，模板因此只有一套渲染路径。 */
const advancedGroups = computed(() => {
  const list = advancedFields.value
  if (list.length < GROUP_THRESHOLD) {
    return [{ name: '', fields: list }]
  }
  const groups = []
  for (const field of list) {
    const name = field.group || ''
    const last = groups[groups.length - 1]
    if (last && last.name === name) {
      last.fields.push(field)
    } else {
      groups.push({ name, fields: [field] })
    }
  }
  return groups
})

/**
 * 已填的高级项（键名）。
 *
 * <p>判定与 `build()` 的落盘规则一一对应：文本与列表按「有非空项」计，
 * 布尔<b>只在打开时</b>计。`false` 是默认值，把它算成「已填」会让每一份 S3
 * 配置都显示「已填 3 项」，计数随即失去意义。
 */
const filledAdvancedKeys = computed(() => advancedFields.value
  .filter((field) => {
    const value = form.extras[field.key]
    if (field.type === 'bool') {
      // 带默认值的布尔项：与默认值相同就是「没动过」，不算已填；
      // 否则 useHttps 会在每一份 COS 配置上都显示「已填 1 项」，计数随即失去意义
      return field.default !== undefined ? Boolean(value) !== field.default : value === true
    }
    if (field.type === 'list') return (value || []).some((item) => String(item ?? '').trim() !== '')
    return String(value ?? '').trim() !== ''
  })
  .map((field) => field.key))

/** 折叠头上的计数徽标。收起时它是「这里有东西」的唯一线索。 */
const advancedBadge = computed(() => (filledAdvancedKeys.value.length
  ? `已填 ${filledAdvancedKeys.value.length} 项`
  : '未填写'))

/**
 * `roleArn` 与静态凭据同时填写时的提示。
 *
 * <p><b>这是提示，不是校验</b>：服务端今天接受这种组合
 * （`StorageConfigs` 只拒绝「静态凭据 × storageName」，`roleArn` 那一对没被检查），
 * 前端不该比服务端更严——拦下来会让一份服务端认可的配置在控制台里存不回去。
 * 之所以仍然要说一句，是因为 `roleArn` 现在收进了折叠区，用户更不容易
 * 发现自己同时填了两条路。
 */
const credentialConflict = computed(() => {
  if (!supportsStaticCredentials.value || form.removeCredentials) return false
  // 打码之后「已配了凭据」不再等于「输入框里有字」：编辑一份已保存的配置时输入框是空的，
  // 只看 form.accessKeyId 会让这条提示永远不出现，而它要提醒的正是这组合并存
  const hasCredential = Boolean(String(form.accessKeyId || '').trim() || storedAccessKeyId.value)
  return Boolean(hasCredential && String(form.extras.roleArn || '').trim())
})

const carriedKeys = computed(() => Object.keys(carried.value))

const locationPlaceholder = computed(() => ({
  S3: 's3://bucket/prefix',
  GCS: 'gs://bucket/prefix',
  AZURE: 'abfss://container@account.dfs.core.windows.net/path',
  OBS: 'obs://bucket/prefix',
  OSS: 'oss://bucket/prefix',
  COS: 'cosn://bucket-1250000000/prefix',
  FILE: 'file:///data/warehouse',
}[form.storageType] || '存储位置 URI'))

/** 切换类型：只保留该类型的字段，避免把上一个类型的属性带到新配置里。 */
/** 按钮的排列顺序（原型的排法），与 `storageTypes` 里枚举的定义顺序不是一回事。 */
const orderedTypes = computed(() => sortStorageTypes(props.storageTypes))

/**
 * 这个类型在本部署里能不能用。
 *
 * <p>换成自绘按钮之后，这条判据必须自己写：原先它是 `el-option` 上的 `disabled`，
 * Element Plus 替页面挡着；`<span>` 没有这个属性，不判就会让「本部署未接入」的类型
 * 看起来和能选的一样，直到提交时被服务端以「本部署未接入该存储实现」拒绝。
 *
 * <p>判据本身在 `utils/storageType.js`（纯函数，有 utils 用例）：这个分支在界面上
 * 依赖部署配置——本仓库默认支持全部七种，永远看不到灰态，只靠截图验不到。
 */
function isUnsupported(type) {
  return isUnsupportedStorageType(type, props.supportedTypes)
}

/**
 * 点选存储类型。
 *
 * <p>先判能不能选，再判是不是同一个：换类型会清空 extras 与静态凭据（见
 * `onTypeChange`），重复点当前项时清一遍等于把刚填的 AK/SK 悄悄丢掉。
 */
function selectType(type) {
  if (isUnsupported(type) || form.storageType === type) {
    return
  }
  form.storageType = type
  onTypeChange(type)
}

function onTypeChange(next) {
  form.extras = collectExtras({}, next)
  // 静态凭据同样不能跨类型沿用：AK/SK 是签发到具体云厂商的，
  // 换成另一家的存储类型后旧密钥不但没用，还会让「这个 catalog 用哪把钥匙」变得难判断
  form.accessKeyId = ''
  form.secretAccessKey = ''
  form.removeCredentials = false
  storedAccessKeyId.value = ''
  carried.value = splitCarried(carried.value, next)
  // 换类型会清空 extras，此时还张着一个空区块会让人以为刚才填的丢了
  advancedOpen.value = false
  push()
}

function removeLocation(index) {
  form.allowedLocations.splice(index, 1)
  push()
}

/**
 * 收敛成服务端要的形状：丢掉空串与空数组，布尔值原样带上。
 *
 * <p><b>静态凭据的三个分支对应服务端的三种语义</b>（见服务端 `StorageConfigs`
 * 的 `mergeStaticCredentials`）：都填 = 设置、都为空串 = 清除、都不出现 = 保持不变。
 * 因此这里空值不是「删除字段」那么简单——对密钥来说删掉字段意味着「保持」，
 * 想清除必须显式提交一对空串，这也是「移除」要单独一个按钮而不是「清空输入框」的原因：
 * 一个空的输入框同时可能是「我没动它」和「我要删掉它」，UI 上必须分得开。
 */
function build() {
  // 先铺开表单覆盖不到的字段，再覆盖已知键——顺序不能反，否则已知键会被旧值盖回
  const config = { ...carried.value }
  config.storageType = form.storageType
  config.allowedLocations = form.allowedLocations.map((item) => (item || '').trim()).filter(Boolean)
  const storageName = (form.storageName || '').trim()
  if (storageName) {
    config.storageName = storageName
  } else {
    delete config.storageName
  }
  for (const field of fields.value) {
    const value = form.extras[field.key]
    if (field.type === 'bool') {
      // 等于字段默认值时不写：`useHttps` 的服务端默认是 true，写一个 `true` 进去
      // 会把「服务端以后改默认值」这件事钉死在这一份 catalog 上。
      // 不等于默认值时写——那才是用户真正表达的意思（例如显式关掉 HTTPS）。
      if (field.default !== undefined && Boolean(value) === field.default) {
        delete config[field.key]
      } else {
        config[field.key] = Boolean(value)
      }
    } else if (field.type === 'list') {
      const items = (value || []).map((item) => String(item ?? '').trim()).filter(Boolean)
      if (items.length) {
        config[field.key] = items
      } else {
        delete config[field.key]
      }
    } else if (value !== undefined && value !== null && String(value).trim() !== '') {
      config[field.key] = String(value).trim()
    } else {
      delete config[field.key]
    }
  }

  if (supportsStaticCredentials.value) {
    const typedId = (form.accessKeyId || '').trim()
    const secretAccessKey = (form.secretAccessKey || '').trim()
    // 输入框留空但库里有凭据时回传哨兵，而不是把 accessKeyId 整个省掉。
    // 省略只能表达「两个字段都不改」；用户只想换密钥（ID 不动）时，省略会变成
    // 「有密钥没 ID」，服务端只能拒。哨兵是唯一能表达「ID 沿用库里的、密钥换新的」的值。
    const accessKeyId = form.removeCredentials
      ? ''
      : (typedId || (storedAccessKeyId.value ? MASKED_ACCESS_KEY_ID : ''))
    if (form.removeCredentials) {
      config.accessKeyId = ''
      config.secretAccessKey = ''
    } else {
      if (accessKeyId) {
        config.accessKeyId = accessKeyId
      } else {
        delete config.accessKeyId
      }
      // 密钥留空即「不提交该字段」，服务端会保持库中已保存的那一份
      if (secretAccessKey) {
        config.secretAccessKey = secretAccessKey
      } else {
        delete config.secretAccessKey
      }
    }
  } else {
    STATIC_CREDENTIAL_KEYS.forEach((key) => delete config[key])
  }
  return config
}

function snapshot(value) {
  return JSON.stringify(value ?? {})
}

/** 最近一次由本组件发出的值，用来区分「外部改了」与「回声」。 */
let lastEmitted = ''

function push() {
  const built = build()
  const serialized = snapshot(built)
  if (serialized === lastEmitted) {
    return
  }
  lastEmitted = serialized
  emit('update:modelValue', built)
}

adopt(props.modelValue)
lastEmitted = snapshot(build())

watch(
  () => props.modelValue,
  (next) => {
    // 值与我们刚发出去的一致，说明这次变化是自己的回声，灌回去只会让光标跳位
    if (snapshot(next) === lastEmitted) {
      return
    }
    adopt(next)
    lastEmitted = snapshot(build())
  },
)

watch(form, push, { deep: true })

/** 由父组件在提交前调用；返回第一条不合规的提示，全部通过时返回空串。 */
function validate() {
  if (!form.storageType) return '请选择存储类型'
  const locations = build().allowedLocations
  if (!locations.length) return '至少填写一个允许位置（allowedLocations）'
  const schemes = LOCATION_SCHEMES[form.storageType]
  if (schemes) {
    const bad = locations.find((item) => !schemes.some((scheme) => item.startsWith(scheme)))
    if (bad) {
      return `位置「${bad}」不符合 ${form.storageType} 的写法（应以 ${schemes.join(' 或 ')} 开头）`
    }
  }
  if (form.storageType === 'AZURE' && !String(form.extras.tenantId || '').trim()) {
    return 'Azure 存储必须填写 tenantId'
  }
  const credentialIssue = validateCredentials()
  if (credentialIssue) return credentialIssue
  return ''
}

/**
 * 静态凭据的前置校验。
 *
 * <p>与服务端规则一致，提前在这里拦一次是因为它们的可发现性太差：
 * 两个字段都不回显真值（AK ID 是 {@code ******}、密钥根本不出现），
 * 用户看不到「服务端手上是什么」；而 storageName 与静态凭据在表单里相隔很远，
 * 「保存失败」时很难把两者联系到一起。
 *
 * <p>判断「有没有凭据」要同时看输入框与 {@link storedAccessKeyId}：
 * 打码之后，编辑一个已配好的 catalog 时输入框本来就是空的，
 * 只看输入框会把「已存在的凭据」判成「没有」。
 */
function validateCredentials() {
  if (!supportsStaticCredentials.value || form.removeCredentials) return ''
  const accessKeyId = (form.accessKeyId || '').trim()
  const secretAccessKey = (form.secretAccessKey || '').trim()
  const hasCredential = Boolean(accessKeyId || storedAccessKeyId.value)
  // 已保存的那一份能补上 ID，因此「只填密钥」是合法的（换密钥、ID 不动）
  if (secretAccessKey && !hasCredential) {
    return '填写了 secretAccessKey，但 accessKeyId 为空'
  }
  // 打了新 ID 就必须同时给密钥：服务端拿不到旧密钥，无法凑成一对
  if (accessKeyId && !secretAccessKey) {
    return '更换 accessKeyId 时必须同时填写 secretAccessKey（服务端不回显密钥，无法沿用）'
  }
  if (hasCredential && String(form.storageName || '').trim()) {
    return '静态凭据与具名存储（storageName）互斥：两者都在指定用哪一组密钥，请只保留一种；'
      + '要改用具名存储请先「移除已保存的静态凭据」'
  }
  if (accessKeyId && !props.staticCredentialsEnabled) {
    return '本部署未开启静态凭据（服务端未配置 lakehouse.rest.storage.credential-secret-key），'
      + '请留空以使用服务端配置的密钥'
  }
  return ''
}

defineExpose({ validate, build })
</script>

<style scoped>
/* 可重复行的容器、行、按钮文字都走全局基元（.pc-repeat / .pc-repeat-row /
 * .pc-repeat-add），表单栅格走全局 .pc-form。这里只剩本组件自己的东西。 */

.pc-storage-carry {
  margin-top: 4px;
}

/* 「保存后凭据来源」预览。故意做得比 `.pc-field-note` 重一点、比 `el-alert` 轻得多：
 * 它回答的是「我这样填会怎样」，是这一页最该被看见的一句话，但不是一个需要处理的告警。
 * 左侧竖线按四档上色，与列表页那一列的颜色同源（都取自 `credentialSource.js` 的 tag）。 */
.pc-cred-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  margin: -4px 0 14px;
  padding: 6px 10px;
  font-size: 12.5px;
  line-height: 1.65;
  background: var(--pc-surface-2);
  border-left: 2px solid var(--pc-text-dim);
  border-radius: var(--pc-radius);
}

.pc-cred-label {
  font-weight: 600;
  color: var(--pc-text);
}

.pc-cred-detail {
  flex: 1 1 260px;
  color: var(--pc-text-dim);
}

.pc-cred-preview.is-sts {
  border-left-color: var(--el-color-success);
}

.pc-cred-preview.is-relay {
  border-left-color: var(--el-color-warning);
}

.pc-cred-preview.is-invalid {
  border-left-color: var(--el-color-danger);
}

.pc-cred-preview.is-invalid .pc-cred-label {
  color: var(--el-color-danger);
}

.pc-static-disabled-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--pc-text-dim);
}

.pc-static-disabled-hint code {
  padding: 1px 4px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
}
</style>
