<template>
  <div class="pc-sso-diag" :class="`is-${diagnosis.tone}`">
    <div class="pc-sso-diag-head">
      <el-icon class="pc-sso-diag-icon"><WarningFilled /></el-icon>
      <span class="pc-sso-diag-title">{{ diagnosis.title }}</span>
      <el-tag size="small" effect="plain" :type="diagnosis.tone === 'blocked' ? 'danger' : 'warning'">
        {{ diagnosis.tone === 'blocked' ? '需要改配置' : '可以重试' }}
      </el-tag>
    </div>
    <dl class="pc-kv pc-sso-diag-kv">
      <dt>为什么</dt>
      <dd>{{ diagnosis.why }}</dd>
      <dt>下一步</dt>
      <dd>{{ diagnosis.action }}</dd>
    </dl>
    <!-- 原文保留在折叠区：分类是为了给出下一步，不是为了让证据消失 -->
    <details v-if="diagnosis.raw" class="pc-sso-diag-raw">
      <summary>原始错误</summary>
      <pre class="pc-mono">{{ diagnosis.raw }}</pre>
    </details>
  </div>
</template>

<script setup>
import { WarningFilled } from '@element-plus/icons-vue'

/**
 * SSO 失败的诊断卡：一句结论 + 为什么 + 下一步 + 可展开的原文。
 *
 * <p>用自绘的块而不用 `el-alert`：`el-alert` 的正文是「一句说明」，
 * 而这里要同时给出**三条不同性质的信息**（结论、成因、动作），
 * 挤进一行会变成一段谁都不读的长句。也不套 `TipAlert`——错误不能收进气泡，
 * 那要求人多点一次才知道出了什么事（全站同一取舍，见 `TipAlert` 的注释）。
 *
 * <p>结构由 `utils/ssoDiagnostics.js` 决定（纯函数、可单测），本组件只负责排版，
 * 因此加一种失败原因不需要动这里。
 */
defineProps({
  /** `diagnoseSsoError()` 的返回值。 */
  diagnosis: { type: Object, required: true },
})
</script>

<style scoped>
.pc-sso-diag {
  border: 1px solid var(--pc-border);
  border-left-width: 3px;
  border-radius: var(--pc-radius);
  padding: 12px 14px;
  margin-bottom: 14px;
  background: var(--pc-surface);
}

/* 两种语气用左边框区分：需要改配置 vs 可以重试。颜色之外还有文案标签，
   因此不是「只靠颜色传达信息」 */
.pc-sso-diag.is-blocked {
  border-left-color: var(--el-color-danger);
}

.pc-sso-diag.is-retry {
  border-left-color: var(--el-color-warning);
}

.pc-sso-diag-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.pc-sso-diag-icon {
  color: var(--el-color-danger);
}

.pc-sso-diag.is-retry .pc-sso-diag-icon {
  color: var(--el-color-warning);
}

.pc-sso-diag-title {
  font-weight: 600;
  font-size: 14px;
}

.pc-sso-diag-kv {
  margin: 0;
  font-size: 13px;
}

.pc-sso-diag-kv dt {
  color: var(--pc-text-dim);
}

.pc-sso-diag-kv dd {
  margin: 2px 0 8px;
  line-height: 1.6;
}

.pc-sso-diag-kv dd:last-child {
  margin-bottom: 0;
}

.pc-sso-diag-raw {
  margin-top: 8px;
  font-size: 12px;
  color: var(--pc-text-dim);
}

.pc-sso-diag-raw summary {
  cursor: pointer;
  user-select: none;
}

.pc-sso-diag-raw pre {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: var(--pc-page-bg);
  border-radius: var(--pc-radius);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
