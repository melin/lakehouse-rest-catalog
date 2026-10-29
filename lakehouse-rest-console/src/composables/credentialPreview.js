import { onBeforeUnmount, ref, watch } from 'vue'

import { consoleApi } from '@/api/console.js'

/**
 * 「按现在这样填，保存后凭据会从哪来」——表单里的实时预览。
 *
 * <p><b>判定依然在服务端。</b>这个组合函数只做三件事：去抖、发请求、记结果。
 * 它不认识 `roleArn` / `stsUnavailable` / `providers` 中的任何一个，也不认识
 * `STS` / `RELAY` 这两个取值——把优先级抄进来就会与 `DefaultStorageCredentialManager`
 * 漂移，而漂移的表现是「表单说会签发、保存后列表说在转发」
 * （`scripts/verify-console.py` 第 24 组会拦住这件事）。
 *
 * <p><b>为什么值得一次往返。</b>`roleArn` 填了、但本部署没启用 `s3-token` 时，
 * 结果与「什么都没填」完全一样（都退回环境凭据链）。这一点在字段值上看不出来，
 * 在保存后要回列表页才看得见，而那时用户已经在另一步上了。
 *
 * <p><b>三个刻意的取舍：</b>
 *
 * <ol>
 *   <li><b>去抖而不是每次输入都发。</b>表单每敲一个字符就会 `push()` 一次新版配置，
 *       逐次请求等于把一次编辑变成几十次往返；{@code delay} 取 400ms，
 *       与「敲完一个 roleArn」的节奏大致对齐。</li>
 *   <li><b>失败就把预览藏起来，不保留上一次的结论。</b>旧结论配上新表单是一句假话，
 *       而一句假话比没有这一栏更坏——列表页显示「—」而不显示「不下发」是同一条理由。</li>
 *   <li><b>草稿没有「允许位置」时不问。</b>那时还谈不上保存（表单自己会拦），
 *       问出来的「不下发」只会是一句与用户下一步无关的噪音。</li>
 * </ol>
 *
 * @param {() => object} getDraft 返回当前提交体的 getter（表单的 `build()` 产物）
 * @param {{delay?: number}} [options]
 * @returns {{preview: import('vue').Ref<null|{mode: string, detail: string}>}}
 */
export function useCredentialPreview(getDraft, { delay = 400 } = {}) {
  /** 服务端的判定；`null` 表示「没有可靠的判定」（还没问、问失败、草稿还不完整）。 */
  const preview = ref(null)

  let timer = null
  // 递增序号丢弃过期响应：连续改动时先发的请求可能后到，
  // 不做这个保护就会出现「预览说的是上一版表单」——只在慢网络下复现，很难查
  // （与 `useResource` 同一个理由）。
  let sequence = 0
  /** 上一次**已经问过**的草稿，用来避免「内容没变也发一次」。 */
  let asked = ''

  function usable(draft) {
    return Boolean(draft?.storageType) && (draft?.allowedLocations || []).length > 0
  }

  async function ask() {
    const draft = getDraft?.() || {}
    if (!usable(draft)) {
      preview.value = null
      asked = ''
      // 草稿退回「不完整」时，在途的那次响应也必须作废：
      // 否则它晚一步到达，会把一条针对上一份草稿的结论贴回这块空表单上
      sequence += 1
      return
    }
    const key = JSON.stringify(draft)
    if (key === asked) {
      return
    }
    asked = key
    const current = ++sequence
    try {
      const answer = await consoleApi.previewCredentialSource(draft)
      if (current === sequence) {
        preview.value = answer?.mode ? answer : null
      }
    } catch {
      // 失败时把上一次的结论一起丢掉：留着它等于给新表单贴一个旧结论
      if (current === sequence) {
        preview.value = null
        // 允许下一次同样的草稿再问一次（失败不该被记成「已经问过」）
        asked = ''
      }
    }
  }

  function schedule() {
    if (timer) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      timer = null
      ask()
    }, delay)
  }

  watch(getDraft, schedule, { deep: true })
  // 初始也要问一次：编辑一个已存在的 catalog 时，表单一开始就是完整的，
  // 等用户改一个字才出现预览，会让人以为这一栏是「改坏了才提示」的地方。
  schedule()

  onBeforeUnmount(() => {
    if (timer) {
      clearTimeout(timer)
    }
    // 卸载后到达的响应不该再写 ref（组件已经没了），序号一变旧响应就作废
    sequence += 1
  })

  return { preview }
}
