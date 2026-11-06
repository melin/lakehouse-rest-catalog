<template>
  <div class="pc-page">
    <PageHeader
      title="敏感类型"
      subtitle="识别能力本身：类型定义与五层探测器配置。类型是全局资源（不属于任何 catalog），改一处、下一轮扫描全局生效。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建自定义类型</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 这一屏没有 catalog 选择器，是刻意的：`lakehouse_sensitive_type` 没有 catalog 列，
         类型是识别能力、不是某个目录的属性。因此四个端点判的都是服务管理员，
         而不是 CATALOG:ALTER——把「谁能改识别规则」收紧到服务级是安全取舍：
         改了权重等于改了所有 catalog 的判定口径。 -->
    <TipAlert type="info" class="pc-gap">
      <template #title>两个独立开关 · 改权重会改变判定结果</template>
      <code class="pc-mono">auto_apply</code> 决定「这类能不能自动落标」，
      <code class="pc-mono">min_confidence</code> 决定「多确定才自动落标」——两者同时满足才自动。
      而五层探测器里的 <strong>层内权重</strong> 直接乘在该层得分上，改完下一轮扫描的判定随之改变，
      <strong>变更进审计</strong>（谁把身份证的名称层权重调低了）。内置类型只能停用、不能删除，
      但<strong>每个字段都可改</strong>：删不掉的原因是删了下次启动 seed 又会把它建回来。
    </TipAlert>

    <div class="pc-sens-grid">
      <!-- ---------------------------------------------------------------- 左栏 -->
      <div>
        <PanelCard flush class="pc-gap">
          <div class="pc-list-head">
            <span class="pc-list-title">类型清单</span>
            <span class="pc-list-tools">
              <span class="pc-dim pc-sens-count">
                {{ builtinCount }} 内置 · {{ rows.length - builtinCount }} 自定义
              </span>
            </span>
          </div>
          <ResourceState
            :loading="loading"
            :error="error"
            :empty="!rows.length"
            empty-text="没有任何类型。检查 seed 开关 lakehouse.rest.sensitivity.seed-builtin-types 是否被关掉了。"
          >
            <div class="pc-sens-table-wrap">
              <el-table
                v-loading="loading"
                :data="rows"
                size="small"
                highlight-current-row
                :row-class-name="rowClass"
                @row-click="openEditor"
              >
                <el-table-column label="类型" min-width="190">
                  <template #default="{ row }">
                    <div class="pc-sens-name">{{ row.typeName }}</div>
                    <div class="pc-mono pc-dim pc-sens-code">{{ row.typeCode }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="类别" width="80">
                  <template #default="{ row }">
                    <el-tag size="small" type="info">{{ row.category }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="级别" width="62">
                  <template #default="{ row }">
                    <el-tag size="small" :type="levelTag(row.level)">{{ row.level }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="自动应用" width="96">
                  <template #default="{ row }">
                    <!-- 三种状态要分开表达：「规则不许」与「有人关了」是两件事。
                         只显示「开 / 关」会让后者看起来像前者，运维就会去翻白名单找为什么。 -->
                    <el-tag v-if="!row.autoApplyAllowed" size="small" type="danger">规则不许</el-tag>
                    <el-tag v-else-if="row.autoApply" size="small" type="success">开</el-tag>
                    <el-tag v-else size="small" type="warning">已关闭</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="阈值" width="72">
                  <template #default="{ row }">
                    <span class="pc-mono">{{ row.minConfidence.toFixed(2) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="探测器" min-width="140">
                  <template #default="{ row }">
                    <el-tag
                      v-for="layer in (row.configuredLayers || [])"
                      :key="layer"
                      size="small"
                      :type="layerTag(layer)"
                      class="pc-sens-chip"
                    >
                      {{ layerShort(layer) }}
                    </el-tag>
                    <span v-if="!(row.configuredLayers || []).length" class="pc-dim">
                      无可用判据
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="启用" width="70">
                  <template #default="{ row }">
                    <el-switch
                      :model-value="row.enabled"
                      size="small"
                      :loading="toggling === row.typeCode"
                      @click.stop
                      @change="(value) => toggleEnabled(row, value)"
                    />
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </ResourceState>
        </PanelCard>

        <PanelCard title="探测器权重（全局默认）" hint="代码固定 · 类型级层内权重可覆盖" class="pc-gap">
          <div class="pc-sens-stack">
            <i
              v-for="layer in LAYER_META"
              :key="layer.key"
              :style="{ width: (layer.coefficient * 100) + '%', background: layer.color }"
              :title="`${layer.label} ${layer.coefficient.toFixed(2)}`"
            />
          </div>
          <div class="pc-sens-legend">
            <span v-for="layer in LAYER_META" :key="layer.key" :style="{ color: layer.color }">
              {{ layer.label }} {{ layer.coefficient.toFixed(2) }}
            </span>
          </div>
          <!-- 这五个系数是 SensitivityScorer 里的常量，不是配置项——因此这里只读。
               把它做成可编辑的诱惑很大，但那等于允许「A 库的身份证判定比 B 库松」，
               而这件事应该由 min_confidence（阈值）表达：阈值是治理决定、系数是领域知识。
               一个类型要让某一层「不那么可信」，调的是**它自己的层内权重**（右侧编辑器）。 -->
          <p class="pc-sens-note">
            这五个系数由代码固定（<code class="pc-mono">SensitivityScorer</code>）：它对应
            「名字比类型可信」「值层接近确证」这类领域判断，不是一个类型一套的配置。
            某个类型要让某一层弱一些，改的是<strong>它自己的层内权重</strong>（右侧每一层右侧的输入框，默认 1.00 = 满值）。
            值模式权重最高，因为「列名叫 <code class="pc-mono">c1</code> 但内容是身份证」只有值层能发现。
          </p>
        </PanelCard>

        <PanelCard title="决策带" hint="全局默认 · 阈值可按 catalog 覆盖（见「敏感数据」屏）">
          <el-table :data="BANDS" size="small">
            <el-table-column label="置信度" width="170">
              <template #default="{ row }">
                <span class="pc-mono">{{ row.range }}</span>
              </template>
            </el-table-column>
            <el-table-column label="处置">
              <template #default="{ row }">
                <el-tag size="small" :type="row.tag">{{ row.action }}</el-tag>
                <div v-if="row.note" class="pc-dim pc-sens-band-note">{{ row.note }}</div>
              </template>
            </el-table-column>
          </el-table>
          <p class="pc-sens-note">
            <strong>D1/D2 任何一档都跑</strong>，两层都只读已落库的 schema 文档、零额外 I/O。
            这决定了元数据档的得分上限是
            <code class="pc-mono">0.35 + 0.10 = 0.45</code>，够不着默认阈值
            <code class="pc-mono">0.85</code>——因此<strong>元数据扫描只产命中、不产标签</strong>。
            要在人审队列里看到候选，靠的是元数据档的下界 <code class="pc-mono">0.35</code>
            （只有类型像的那批，如 <code class="pc-mono">char(18)</code>，不产候选——
            定长字符列满库都是）。<strong>D3 值层只在采样档跑</strong>，它把上限推到
            <code class="pc-mono">0.35 + 0.10 + 0.40 = 0.85</code>，强格式类型因此能自动落标；
            D4 字典与 D5 统计属 <b>P4</b>，现在配上去也不参与打分。
          </p>
        </PanelCard>
      </div>

      <!-- ---------------------------------------------------------------- 右栏 -->
      <div class="pc-sens-editor-col">
        <PanelCard :title="editorTitle" :hint="editorHint">
          <template #actions>
            <!-- 内置类型先只读、点「编辑」解锁：避免误改内置规则。自定义类型本就该能改，直接解锁。 -->
            <el-button
              v-if="draft && draft.builtin && locked"
              size="small"
              @click="locked = false"
            >
              <el-icon><Edit /></el-icon>
              <span class="pc-btn-text">编辑</span>
            </el-button>
            <el-tag v-if="draft && draft.builtin" size="small" type="primary">内置</el-tag>
          </template>

          <div v-if="!draft" class="pc-sens-placeholder">
            左侧点一个类型，这里编辑它的属性与五层探测器。
            <br />
            <span class="pc-dim">配置就在这一屏，不在别处。</span>
          </div>

          <template v-else>
            <el-form label-position="top" size="small" class="pc-form">
              <div class="pc-sens-inline">
                <el-form-item label="类型编码" class="pc-sens-half">
                  <el-input v-model="draft.typeCode" disabled />
                </el-form-item>
                <el-form-item label="类型名称" class="pc-sens-half">
                  <el-input v-model="draft.typeName" :disabled="locked" />
                </el-form-item>
              </div>
              <div class="pc-sens-inline">
                <el-form-item label="类别" class="pc-sens-half">
                  <el-select v-model="draft.category" :disabled="locked">
                    <el-option
                      v-for="item in CATEGORY_OPTIONS"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="默认分级" class="pc-sens-half">
                  <el-select v-model="draft.level" :disabled="locked">
                    <el-option
                      v-for="item in LEVEL_OPTIONS"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                  <div class="pc-hint">
                    <code class="pc-mono">CORE</code> / <code class="pc-mono">IMPORTANT</code>
                    不在此列：它们按国家与行业目录人工认定，探测器没有资格产出。
                  </div>
                </el-form-item>
              </div>

              <div class="pc-sens-inline">
                <el-form-item label="允许自动应用 · auto_apply" class="pc-sens-half">
                  <el-switch
                    v-model="draft.autoApply"
                    :disabled="locked || !draft.autoApplyAllowed"
                  />
                  <div class="pc-hint">
                    <template v-if="!draft.autoApplyAllowed">
                      <strong>规则不允许</strong>自动应用这类（误报率天然高，
                      一旦自动落标就是静默误脱敏）。
                    </template>
                    <template v-else-if="draft.autoApply">
                      规则允许，且当前已打开。
                    </template>
                    <template v-else>
                      规则允许，但<strong>被显式关闭</strong>——这是治理决定，会进审计。
                    </template>
                  </div>
                </el-form-item>
                <el-form-item label="自动应用阈值 · min_confidence" class="pc-sens-half">
                  <el-input-number
                    v-model="draft.minConfidence"
                    :min="0.1"
                    :max="1"
                    :step="0.01"
                    :precision="2"
                    :disabled="locked"
                    controls-position="right"
                    class="pc-sens-num"
                  />
                  <div class="pc-hint">
                    服务端接受 <code class="pc-mono">0.10</code>–<code class="pc-mono">1.00</code>。
                    0 会让任何命中都自动落标，1 则永远不会自动。
                  </div>
                </el-form-item>
              </div>
            </el-form>

            <div class="pc-sens-layers-head">
              <span>五层探测器</span>
              <span class="pc-dim">
                勾选 = 这一层配了判据；右侧数字是<strong>层内权重</strong>（0–1，默认 1.00 满值）
              </span>
            </div>

            <div
              v-for="layer in LAYER_META"
              :key="layer.key"
              class="pc-sens-layer"
              :class="{ 'is-off': !draft.layers[layer.key].active }"
            >
              <div class="pc-sens-layer-top">
                <el-checkbox
                  v-model="draft.layers[layer.key].active"
                  :disabled="locked"
                  class="pc-sens-layer-check"
                >
                  {{ layer.label }}
                </el-checkbox>
                <span class="pc-sens-layer-coef pc-mono pc-dim">
                  × {{ layer.coefficient.toFixed(2) }}
                </span>
                <span class="pc-sens-layer-weight">
                  <span class="pc-dim">权重</span>
                  <el-input-number
                    v-model="draft.layers[layer.key].weight"
                    :min="0"
                    :max="1"
                    :step="0.05"
                    :precision="2"
                    :disabled="locked || !draft.layers[layer.key].active"
                    controls-position="right"
                    size="small"
                    class="pc-sens-weight-num"
                  />
                </span>
              </div>

              <div class="pc-sens-layer-body">
                <template v-if="layer.key === 'name'">
                  <el-input
                    v-model="draft.layers.name.text"
                    type="textarea"
                    :rows="2"
                    :disabled="locked || !draft.layers.name.active"
                    placeholder="每行或逗号分隔一条，如 id_card / id_no / 身份证"
                  />
                  <div class="pc-hint">
                    命中列名或列注释即算命中（不区分大小写）。层内不做加权——
                    「一个类型配了 10 条同义正则」不该因为配得更啰嗦就更容易落标。
                  </div>
                </template>

                <template v-else-if="layer.key === 'type'">
                  <el-input
                    v-model="draft.layers.type.types"
                    :disabled="locked || !draft.layers.type.active"
                    placeholder="SQL 类型名，逗号分隔，如 char, varchar"
                  />
                  <div class="pc-hint">
                    长度区间（每行一段，如 <code class="pc-mono">15-18</code>；留空 = 只看类型名）
                  </div>
                  <el-input
                    v-model="draft.layers.type.lengths"
                    type="textarea"
                    :rows="2"
                    :disabled="locked || !draft.layers.type.active"
                    placeholder="15-18"
                  />
                  <div class="pc-hint">
                    区间的<strong>闭区间</strong>判据只在类型名也命中时才看：配了区间却没长度 → 不命中
                    （而不是「忽略长度」）。
                  </div>
                </template>

                <template v-else-if="layer.key === 'value'">
                  <el-input
                    v-model="draft.layers.value.regex"
                    :disabled="locked || !draft.layers.value.active"
                    placeholder="^[1-9]\d{5}(18|19|20)\d{2}...[\dXx]$"
                    class="pc-mono"
                  />
                  <div class="pc-hint">校验器名（正则只能排格式，校验位才能排「随便编的 18 位」）</div>
                  <el-input
                    v-model="draft.layers.value.validator"
                    :disabled="locked || !draft.layers.value.active"
                    placeholder="CN_ID_CARD"
                    class="pc-mono"
                  />
                  <div class="pc-hint pc-field-note-warn">
                    <strong>只在采样档跑</strong>：这一层要读明文样本，元数据档里配了也不生效。
                    打折口径值得记住——校验器<strong>通过率低于 99% 时整层乘一次通过率</strong>，
                    所以「500 行里 4 条脏数据」仍然自动落标（0.992），到 6 条才退到人审。
                  </div>
                </template>

                <template v-else-if="layer.key === 'dict'">
                  <el-input
                    v-model="draft.layers.dict.values"
                    type="textarea"
                    :rows="2"
                    :disabled="locked || !draft.layers.dict.active"
                    placeholder="每行一个枚举值，如 男 / 女"
                  />
                  <div class="pc-hint pc-field-note-warn">
                    <strong>这一层还没实现（属 P4）</strong>：字典要按列基数 &lt; 1000 采样取值集合，
                    现在配上去不参与打分。
                  </div>
                </template>

                <template v-else>
                  <el-input-number
                    v-model="draft.layers.stat.ratio"
                    :min="0"
                    :max="1"
                    :step="0.05"
                    :precision="2"
                    :disabled="locked || !draft.layers.stat.active"
                    controls-position="right"
                    class="pc-sens-num"
                  />
                  <div class="pc-hint">
                    基数比上限 <code class="pc-mono">maxCardinalityRatio</code>
                    （留空 = 不按基数判）。
                  </div>
                  <div class="pc-hint pc-field-note-warn">
                    <strong>这一层还没实现（属 P4）</strong>：统计特征要采样，现在配上去不参与打分。
                  </div>
                </template>
              </div>
            </div>

            <div class="pc-sens-actions">
              <el-button
                type="primary"
                size="small"
                :disabled="locked"
                :loading="saving"
                @click="save"
              >
                保存
              </el-button>
              <el-button size="small" :disabled="locked" @click="openEditor(selectedRow)">
                放弃改动
              </el-button>
              <el-button
                size="small"
                type="danger"
                :disabled="locked || draft.builtin"
                @click="confirmDelete(draft)"
              >
                删除
              </el-button>
              <span class="pc-dim pc-sens-actions-note">
                <template v-if="draft.builtin">
                  内置类型删除会返 <code class="pc-mono">409</code>——只能停用。
                </template>
                <template v-else>
                  自定义类型删了就是删了，没有「恢复默认」。
                </template>
              </span>
            </div>

            <!-- mockup 上还有一个「恢复内置默认」按钮，P0 刻意不做：
                 服务端没有这个端点（改回内置值需要一个「内置原始配置」的对照源，
                 而那等于把 seed 的那份配置在库里存第二遍）。放一个点了没反应的按钮，
                 比不放更糟——人会以为是自己点错了。 -->
            <p v-if="draft.builtin" class="pc-sens-note">
              想还原某个内置类型的改动：没有「恢复默认」按钮，因为它会需要在库里
              存第二份「内置原始配置」做对照源。改动都进了审计
              （<code class="pc-mono">updatedBy</code> / <code class="pc-mono">updatedAt</code>
              与事件），要回退请按审计里的旧值手工改回。
            </p>
            <p v-else class="pc-sens-note">
              类型编码会进标签键（<code class="pc-mono">类别.编码</code>）与事件属性，
              是跨系统契约的一部分，<strong>建好之后不能改名</strong>——改名会让历史命中
              与标签指向一个不存在的编码。
            </p>
          </template>
        </PanelCard>
      </div>
    </div>

    <el-dialog
      v-model="dialog.open"
      title="新建自定义类型"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <TipAlert type="info" class="pc-gap">
        <template #title>先建类型，再配探测器</template>
        先把类型建出来，探测器随后在右侧编辑器里配——全空的探测器配置是允许的，
        它只是「这个类型还没有可用判据」，不会报错。
      </TipAlert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="类型编码">
          <el-input
            v-model="dialog.typeCode"
            placeholder="如 CUSTOM_ACCOUNT_NO"
            class="pc-mono"
          />
          <div class="pc-hint">
            大写字母开头，只含大写字母、数字与下划线（<code class="pc-mono">[A-Z][A-Z0-9_]{1,63}</code>）。
            它会进标签键，因此不能带分隔符。
          </div>
        </el-form-item>
        <el-form-item label="类型名称">
          <el-input v-model="dialog.typeName" placeholder="如 自定义账号" />
        </el-form-item>
        <div class="pc-sens-inline">
          <el-form-item label="类别" class="pc-sens-half">
            <el-select v-model="dialog.category">
              <el-option
                v-for="item in CATEGORY_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="默认分级" class="pc-sens-half">
            <el-select v-model="dialog.level">
              <el-option
                v-for="item in LEVEL_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="自动应用阈值 · min_confidence">
          <el-input-number
            v-model="dialog.minConfidence"
            :min="0.1"
            :max="1"
            :step="0.01"
            :precision="2"
            controls-position="right"
            class="pc-sens-num"
          />
          <div class="pc-hint">
            新建的自定义类型 <code class="pc-mono">auto_apply</code> 恒为关：
            「允许自动落标」只对白名单里那几类强格式类型成立，
            而这个白名单在服务端代码里，不由调用方指定。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialog.open = false">取消</el-button>
        <el-button size="small" type="primary" :loading="dialog.saving" @click="create">
          新建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TipAlert from '@/components/TipAlert.vue'
import { describeError } from '@/api/client.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { confirmDanger, notifyError, notifySuccess } from '@/composables/index.js'

/**
 * 类型与探测器配置（mockup `#types`）。设计稿 §12 的 P0 范围把它与 `#overview`
 * `#tags` 并列为三屏之一，而它承担着一条**验收断言**：
 * 「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变且变更进审计」——
 * 这一屏是那条断言唯一的操作入口。
 *
 * <h3>为什么没有 catalog 选择器</h3>
 * `lakehouse_sensitive_type` 没有 catalog 列：类型是**识别能力**，不是某个目录的
 * 属性。一张表在 A 库叫 `id_card` 在 B 库也叫 `id_card`，判定规则没有理由不同。
 * 因此服务端四处判的都是 `requireServiceAdmin` 而不是 `CATALOG:ALTER`，
 * 这一屏也就不需要选 catalog。真要按 catalog 差异化，差异在**阈值与执行方式**上，
 * 那两样住在 `sensitivity-settings`（见「敏感数据」屏），不在类型表上。
 *
 * <h3>为什么两栏而不是「列表 + 弹窗」</h3>
 * 与既有列表页的对话框范式不同：配置一棵五层的结构（每层还有自己的字段）
 * 放进弹窗，等于让人在一个遮罩层里改分辨率的全貌。左栏点一行、右栏立刻编辑，
 * 是 mockup 的形态，也是「配置就在这一屏」那句话的落地——改一行探测器要能顺手看到
 * 相邻层配了什么。
 *
 * <h3>权重与系数是两件事，界面上必须分开摆</h3>
 * - **层系数**（0.35 / 0.10 / 0.40 / 0.10 / 0.05）在代码里固定，左下那张卡只读展示；
 * - **层内权重**（每层右侧，默认 1.00）住在 `detectors_json` 里，本页可编辑。
 *
 * 混成一句话会让人以为「把名称层系数改成 0.20」也能做，而服务端不接受这个字段——
 * 表现为「改了、保存了、没报错、也没变化」。
 */
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const rows = ref([])
const toggling = ref('')

/** 右侧编辑器当前编辑的类型（`null` = 还没选）。 */
const draft = ref(null)
/** 内置类型默认只读，点「编辑」解锁。自定义类型建出来就是为了改，直接解锁。 */
const locked = ref(false)
/** 用于「放弃改动」——不重新请求，直接用列表里那一行回填（列表就是服务端的最新读模型）。 */
const selectedRow = ref(null)

const dialog = reactive({
  open: false,
  saving: false,
  typeCode: '',
  typeName: '',
  category: 'PII',
  level: 'L3',
  minConfidence: 0.85,
})

/**
 * 五层的展示元数据。
 *
 * <p>`coefficient` 是**只读展示**的层系数（服务端 `SensitivityScorer` 的常量），
 * `color` 只用于左侧堆叠条与图例。
 *
 * <p>`active` 与 `weight` 才是可编辑的：它们进 `detectors_json`。
 * `VALUE` 只在采样档参与判定（P1 已实现，D1+D2+D3 恰好到 0.85）；`DICT` / `STAT`
 * 两层仍未实现（属 P4），编辑器里对它们写了「这一层还没实现」——不写的话，
 * 治理员调完权重发现判定没变，会以为功能坏了。
 */
const LAYER_META = [
  {
    key: 'name',
    label: 'D1 名称',
    coefficient: 0.35,
    color: '#409eff',
  },
  {
    key: 'type',
    label: 'D2 类型',
    coefficient: 0.10,
    color: '#79bbff',
  },
  {
    key: 'value',
    label: 'D3 值模式',
    coefficient: 0.40,
    color: '#67c23a',
  },
  {
    key: 'dict',
    label: 'D4 字典',
    coefficient: 0.10,
    color: '#e6a23c',
  },
  {
    key: 'stat',
    label: 'D5 统计',
    coefficient: 0.05,
    color: '#c0c4cc',
  },
]

/** 类别下拉：与服务端 `SensitiveEnums.SensitiveCategory` 逐字对齐。 */
const CATEGORY_OPTIONS = [
  { value: 'PII', label: 'PII · 个人信息' },
  { value: 'PCI', label: 'PCI · 支付卡行业' },
  { value: 'PHI', label: 'PHI · 医疗健康' },
  { value: 'SECRET', label: 'SECRET · 凭据密钥' },
  { value: 'FINANCIAL', label: 'FINANCIAL · 金融数据' },
]

/**
 * 分级下拉**只有 L1–L4**。
 *
 * <p>`CORE` / `IMPORTANT` 不在选项里不是漏写：它们是 GB/T 43697-2024 里
 * 按国家与行业目录人工认定的两档，服务端对它们直接 400（`SensitiveTypeService.apply`）。
 * 把它们放上下拉再让服务端拒绝，是把「规则不允许」表达成一个报错。
 */
const LEVEL_OPTIONS = [
  { value: 'L1', label: 'L1 公开' },
  { value: 'L2', label: 'L2 内部' },
  { value: 'L3', label: 'L3 敏感' },
  { value: 'L4', label: 'L4 机密' },
]

/**
 * 决策带（只读展示）。
 *
 * <p>下界有三档而不是两档，因为**下界随「这一列到底采到样没有」走，不随扫描模式走**：
 * 拿到样本的下界是 0.60，没拿到的下界是 0.35。若统一用 0.60，元数据档（上限 0.45）
 * 这条唯一能不采样就跑的路径会**恒不产出结论**。0.35 的含义是「名称层命中是
 * 没有样本时最低的证据要求」。
 */
const BANDS = [
  {
    range: '≥ 0.85 且允许自动',
    action: '直接落标',
    tag: 'success',
    note: '元数据档到不了；采样档 D1+D2+D3 恰好 0.85',
  },
  {
    range: '0.35 – 0.85（元数据档）',
    action: '待人审',
    tag: 'warning',
    note: '名字或名字+形状命中，进人审队列',
  },
  {
    range: '0.60 – 0.85（采样档）',
    action: '待人审',
    tag: 'warning',
    note: '拿到样本但校验器没全过：整层按通过率打折',
  },
  {
    range: '< 下界',
    action: '丢弃',
    tag: 'info',
    note: '只计入扫描统计。0.10（只有类型像）在这一档',
  },
]

const builtinCount = computed(() => rows.value.filter((row) => row.builtin).length)

const editorTitle = computed(() => (draft.value ? `编辑：${draft.value.typeName}` : '探测器配置'))
const editorHint = computed(() => {
  if (!draft.value) return '左侧点一个类型开始'
  if (locked.value && draft.value.builtin) return '内置类型只读 · 点「编辑」解锁'
  return '保存后下一轮扫描生效 · 改动进审计'
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const payload = await sensitivityApi.listTypes()
    rows.value = payload.items || []
  } catch (cause) {
    error.value = describeError(cause)
    notifyError(cause)
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 左栏

function rowClass({ row }) {
  return draft.value && draft.value.typeCode === row.typeCode ? 'pc-sens-row-on' : ''
}

/**
 * 点一行进入编辑态。
 *
 * <p>取数走 `GET /sensitive-types/{code}` 而不是直接用列表里那一行：列表已经带了
 * 完整 `detectors`，但**编辑器要的是「这一份配置的当前值」**，而列表是几百毫秒前
 * 拉的快照。这是一个两三人维护几十个类型的管理面，撞上并发编辑的概率很低，
 * 但「我明明刚保存过」这个疑问的代价比一次 GET 高。顺带也把 `getSensitiveType`
 * 这个端点用上，而不是让它成为一个没人调用的声明。
 */
async function openEditor(row) {
  if (!row || !row.typeCode) return
  selectedRow.value = row
  try {
    const detail = await sensitivityApi.getType(row.typeCode)
    applyToDraft(detail)
    locked.value = detail.builtin === true
  } catch (cause) {
    notifyError(cause)
  }
}

function applyToDraft(detail) {
  draft.value = {
    typeCode: detail.typeCode,
    typeName: detail.typeName || '',
    builtin: detail.builtin === true,
    category: detail.category || 'PII',
    level: detail.level || 'L3',
    autoApply: detail.autoApply === true,
    autoApplyAllowed: detail.autoApplyAllowed === true,
    minConfidence: typeof detail.minConfidence === 'number' ? detail.minConfidence : 0.85,
    enabled: detail.enabled !== false,
    layers: emptyLayers(),
  }
  const detectors = detail.detectors || {}
  const layers = draft.value.layers

  const name = detectors.name
  if (name) {
    layers.name.active = true
    layers.name.text = (name.patterns || []).join('\n')
    layers.name.weight = weightOf(name)
  }
  const type = detectors.type
  if (type) {
    layers.type.active = true
    layers.type.types = (type.types || []).join(', ')
    layers.type.lengths = (type.lengths || []).map((range) => range.join('-')).join('\n')
    layers.type.weight = weightOf(type)
  }
  const value = detectors.value
  if (value) {
    layers.value.active = true
    layers.value.regex = value.regex || ''
    layers.value.validator = value.validator || ''
    layers.value.weight = weightOf(value)
  }
  const dict = detectors.dict
  if (dict) {
    layers.dict.active = true
    layers.dict.values = (dict.values || []).join('\n')
    layers.dict.weight = weightOf(dict)
  }
  const stat = detectors.stat
  if (stat) {
    layers.stat.active = true
    layers.stat.ratio = typeof stat.maxCardinalityRatio === 'number'
      ? stat.maxCardinalityRatio
      : null
    layers.stat.weight = weightOf(stat)
  }
}

/** 服务端只在 `weight != 1.0` 时下发这个键——缺省即 1.00，不是 0。 */
function weightOf(layer) {
  return typeof layer.weight === 'number' ? layer.weight : 1
}

function emptyLayers() {
  return {
    name: { active: false, weight: 1, text: '' },
    type: { active: false, weight: 1, types: '', lengths: '' },
    value: { active: false, weight: 1, regex: '', validator: '' },
    dict: { active: false, weight: 1, values: '' },
    stat: { active: false, weight: 1, ratio: null },
  }
}

async function toggleEnabled(row, value) {
  toggling.value = row.typeCode
  try {
    // 只提交 `enabled`：服务端 PUT 是**逐字段**语义（没给的字段保持原值），
    // 因此「停用一个类型」不必把它的探测器整份回填一遍——
    // 回填就等于让「只想停用」的请求承担「把配置改错一遍」的风险。
    await sensitivityApi.updateType(row.typeCode, { enabled: value })
    notifySuccess(value ? `已启用 ${row.typeCode}` : `已停用 ${row.typeCode}`)
    await load()
    if (draft.value && draft.value.typeCode === row.typeCode) {
      draft.value.enabled = value
    }
  } catch (cause) {
    notifyError(cause)
    // 失败时把开关拨回去：`el-switch` 用的是 `:model-value`（单向绑定），
    // 界面上的位置由 `row.enabled` 决定，重新拉一次列表就是最准确的对账。
    await load()
  } finally {
    toggling.value = ''
  }
}

// ------------------------------------------------------------------ 右栏：保存 / 删除

/**
 * 把编辑态收成 `detectors` 对象。
 *
 * <p>形状必须与服务端 `DetectorConfig` 一致：**键是小写层名**，只放非空项，
 * `weight` 只在 `!= 1.0` 时写。服务端收下后还会走一次 `parse` → `toJson`
 * （认不出的层丢掉、空层丢掉），因此这里多写一点不会被罚；但少写一个必要键
 * 就是「这一层配了但不生效」，而它的表现是「识别结果偏少」——
 * 与「这张表本来就没敏感列」长得一样。
 */
function buildDetectors() {
  const layers = draft.value.layers
  const out = {}

  if (layers.name.active) {
    const patterns = splitList(layers.name.text)
    if (patterns.length) {
      out.name = withWeight({ patterns }, layers.name.weight)
    }
  }
  if (layers.type.active) {
    const types = splitList(layers.type.types)
    const lengths = parseRanges(layers.type.lengths)
    const body = {}
    if (types.length) body.types = types
    if (lengths.length) body.lengths = lengths
    if (Object.keys(body).length) {
      out.type = withWeight(body, layers.type.weight)
    }
  }
  if (layers.value.active) {
    const body = {}
    if (layers.value.regex.trim()) body.regex = layers.value.regex.trim()
    if (layers.value.validator.trim()) body.validator = layers.value.validator.trim()
    if (Object.keys(body).length) {
      out.value = withWeight(body, layers.value.weight)
    }
  }
  if (layers.dict.active) {
    const values = splitList(layers.dict.values)
    if (values.length) {
      out.dict = withWeight({ values }, layers.dict.weight)
    }
  }
  if (layers.stat.active) {
    // 富文本框里「没填」是 null，不是 0：0 是一个合法的上限值
    // （基数比 0 意味着「只认全常量列」），把 null 写 0 会静默改掉判定。
    if (typeof layers.stat.ratio === 'number') {
      out.stat = withWeight({ maxCardinalityRatio: layers.stat.ratio }, layers.stat.weight)
    }
  }
  return out
}

function withWeight(body, weight) {
  if (typeof weight === 'number' && weight !== 1) {
    body.weight = weight
  }
  return body
}

/** 每行或逗号分隔一条，去空、去重保序（重复的 pattern 不改变判定，只会让配置更难读）。 */
function splitList(text) {
  const seen = new Set()
  const out = []
  for (const raw of String(text || '').split(/[\n,]/)) {
    const item = raw.trim()
    if (item && !seen.has(item)) {
      seen.add(item)
      out.push(item)
    }
  }
  return out
}

/** `15-18` / `15,18` / `15~18` 都认；格式不对的段**整段丢掉**并留一条提示。 */
function parseRanges(text) {
  const out = []
  for (const raw of String(text || '').split('\n')) {
    const item = raw.trim()
    if (!item) continue
    const match = item.match(/^(\d+)\s*[-,~]\s*(\d+)$/)
    if (!match) continue
    const min = Number(match[1])
    const max = Number(match[2])
    if (Number.isFinite(min) && Number.isFinite(max) && min <= max) {
      out.push([min, max])
    }
  }
  return out
}

async function save() {
  if (!draft.value) return
  if (!draft.value.typeName.trim()) {
    notifyError(new Error('类型名称不能为空'))
    return
  }
  saving.value = true
  try {
    // 不带 `typeCode`：服务端只接受「与路径一致」，带上没有收益；
    // 而改名是被禁止的（历史命中与标签都指向旧编码），因此这里根本没有改名入口。
    await sensitivityApi.updateType(draft.value.typeCode, {
      typeName: draft.value.typeName.trim(),
      category: draft.value.category,
      level: draft.value.level,
      detectors: buildDetectors(),
      autoApply: draft.value.autoApply,
      minConfidence: draft.value.minConfidence,
      enabled: draft.value.enabled,
    })
    notifySuccess(`${draft.value.typeCode} 已保存，下一轮扫描生效`)
    await load()
    // 保存后从列表回填：`buildDetectors` 与服务端的归一化结果可能不同
    // （比如空层被丢掉、`weight` 被省掉），留在本地就是「我看到的不是库里的」。
    const fresh = rows.value.find((row) => row.typeCode === draft.value.typeCode)
    if (fresh) {
      selectedRow.value = fresh
      const detail = await sensitivityApi.getType(fresh.typeCode)
      applyToDraft(detail)
      locked.value = detail.builtin === true
    }
  } catch (cause) {
    notifyError(cause)
  } finally {
    saving.value = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除类型',
    target: `${row.typeName}（${row.typeCode}）`,
    detail: '历史命中与已落标签里存的是类型编码字符串，删掉类型后它们仍会显示，'
      + '但缺失展示名。自定义类型删了就没有「恢复默认」。',
  })
  if (!ok) return
  try {
    await sensitivityApi.deleteType(row.typeCode)
    notifySuccess(`${row.typeCode} 已删除`)
    if (draft.value && draft.value.typeCode === row.typeCode) {
      draft.value = null
    }
    await load()
  } catch (cause) {
    notifyError(cause)
  }
}

// ------------------------------------------------------------------ 新建

function openCreate() {
  dialog.typeCode = ''
  dialog.typeName = ''
  dialog.category = 'PII'
  dialog.level = 'L3'
  dialog.minConfidence = 0.85
  dialog.open = true
}

async function create() {
  const code = dialog.typeCode.trim().toUpperCase()
  if (!/^[A-Z][A-Z0-9_]{1,63}$/.test(code)) {
    notifyError(new Error('类型编码必须大写字母开头，只含大写字母、数字与下划线'))
    return
  }
  if (!dialog.typeName.trim()) {
    notifyError(new Error('类型名称不能为空'))
    return
  }
  dialog.saving = true
  try {
    const created = await sensitivityApi.createType({
      typeCode: code,
      typeName: dialog.typeName.trim(),
      category: dialog.category,
      level: dialog.level,
      minConfidence: dialog.minConfidence,
    })
    notifySuccess(`${code} 已创建，接着配它的探测器`)
    dialog.open = false
    await load()
    const row = rows.value.find((item) => item.typeCode === code)
    if (row) {
      await openEditor(row)
    } else if (created && created.typeCode) {
      applyToDraft(created)
      locked.value = false
    }
  } catch (cause) {
    notifyError(cause)
  } finally {
    dialog.saving = false
  }
}

// ------------------------------------------------------------------ 展示辅助

/**
 * `configuredLayers` 里是大写层名（`NAME` / `TYPE` / …），这里换成 D1–D5 短名。
 *
 * <p>认不出的值原样返回：层名来自服务端，若将来多一层，显示成原始名比显示成
 * 空白更有用（空白会让人以为「这个类型没配探测器」）。
 */
function layerShort(name) {
  const index = LAYER_META.findIndex((layer) => layer.key === String(name).toLowerCase())
  return index < 0 ? name : `D${index + 1}`
}

function layerTag(name) {
  const key = String(name).toLowerCase()
  if (key === 'name' || key === 'type') return 'success'
  return 'info'
}

function levelTag(level) {
  return { L4: 'danger', L3: 'warning', L2: 'primary', L1: 'info' }[level] || 'info'
}
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-sens-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.pc-sens-count {
  font-size: 12px;
}

.pc-sens-table-wrap {
  overflow-x: auto;
}

.pc-sens-name {
  font-weight: 500;
}

.pc-sens-code {
  font-size: 12px;
}

.pc-sens-chip {
  margin-right: 4px;
}

/* 选中行：右栏正在编辑哪一行必须一眼看得出，否则「我改的是哪个类型」会成为
   一个需要记忆的问题——而记忆在多标签页场景下不可靠。 */
:deep(.pc-sens-row-on) {
  background: var(--el-color-primary-light-9);
}

.pc-sens-stack {
  display: flex;
  height: 22px;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 8px;
}

.pc-sens-stack i {
  display: block;
  height: 100%;
}

.pc-sens-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  margin-bottom: 10px;
}

.pc-sens-note {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.pc-sens-band-note {
  font-size: 12px;
  margin-top: 2px;
}

.pc-sens-editor-col {
  position: sticky;
  top: 16px;
}

.pc-sens-placeholder {
  padding: 28px 8px;
  text-align: center;
  color: var(--el-text-color-secondary);
  line-height: 2;
}

.pc-sens-inline {
  display: flex;
  gap: 12px;
}

.pc-sens-half {
  flex: 1;
  min-width: 0;
}

.pc-sens-num {
  width: 100%;
}

.pc-sens-layers-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  padding-top: 6px;
  margin-bottom: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-weight: 500;
}

.pc-sens-layers-head .pc-dim {
  font-size: 12px;
  font-weight: 400;
}

.pc-sens-layer {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

/* 未勾选的层整块压暗：五层平铺时，不区分开会让「哪几层真的配了」需要逐个读复选框。 */
.pc-sens-layer.is-off {
  opacity: 0.55;
}

.pc-sens-layer-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.pc-sens-layer-check {
  min-width: 82px;
}

.pc-sens-layer-coef {
  font-size: 12px;
}

.pc-sens-layer-weight {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.pc-sens-weight-num {
  width: 96px;
}

.pc-sens-layer-body {
  margin-top: 6px;
}

.pc-sens-layer-body :deep(.el-input),
.pc-sens-layer-body :deep(.el-textarea) {
  margin-bottom: 6px;
}

.pc-sens-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.pc-sens-actions-note {
  font-size: 12px;
}

@media (max-width: 1100px) {
  .pc-sens-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .pc-sens-editor-col {
    position: static;
  }
}
</style>
