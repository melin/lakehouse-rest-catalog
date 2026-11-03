/**
 * `utils/ssoDiagnostics.js` 的单测。
 *
 * <p>这个文件测的是**分类本身**，不是排版：分类错了不会报错，只会把人引向错误的
 * 下一步——比如把「IdP 侧没登记回调地址」说成「网络不通」，于是所有人去查网络。
 * 因此每条规则都拿一句真实会出现的错误串钉住。
 *
 * <p>跑法：`npm run test:sso`（`npm test` 的通配 `tools/*-tests/*.test.mjs` 也会带上它）。
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { SSO_DIAGNOSIS, diagnoseSsoError, ssoErrorLabel } from '../../src/utils/ssoDiagnostics.js'

describe('diagnoseSsoError', () => {
  it('给了已知分类码就直接用，不再拿原文去猜', () => {
    // 原文里带 audience，但调用方明确说了是 state 失效——以调用方为准
    const result = diagnoseSsoError({ code: 'state', detail: 'audience mismatch' })
    assert.equal(result.code, 'state')
    // 原文必须原样留下：分类是为了给出下一步，不是为了让证据消失
    assert.equal(result.raw, 'audience mismatch')
  })

  it('给了不存在的分类码时退回规则匹配，而不是当成未知', () => {
    const result = diagnoseSsoError({ code: 'not_a_code', detail: 'redirect_uri mismatch' })
    assert.equal(result.code, 'redirect')
  })

  it('IdP 直接拒绝（access_denied）', () => {
    const result = diagnoseSsoError({ detail: 'access_denied：用户拒绝' })
    assert.equal(result.code, 'access_denied')
    assert.equal(result.tone, 'retry')
  })

  it('邮箱域不在白名单：具体规则先于通用 domain', () => {
    const result = diagnoseSsoError({ detail: 'email_domain_not_allowed: corp.example.org' })
    assert.equal(result.code, 'email_domain')
    assert.equal(result.tone, 'blocked')
  })

  it('同名本地主体冲突（JIT 被拒绝）', () => {
    assert.equal(
      diagnoseSsoError({ detail: 'principal_conflict: root already exists as a local principal' }).code,
      'principal_conflict',
    )
  })

  it('aud 不匹配', () => {
    assert.equal(diagnoseSsoError({ detail: 'JWT audience invalid' }).code, 'audience')
  })

  it('iss 不匹配', () => {
    assert.equal(
      diagnoseSsoError({ detail: 'issuer mismatch: expected https://idp/realms/x' }).code,
      'issuer',
    )
  })

  it('回调地址没登记', () => {
    assert.equal(
      diagnoseSsoError({ detail: 'invalid redirect_uri for client lakehouse-console' }).code,
      'redirect',
    )
  })

  it('client 认证失败：具体的 client_auth 先于 state', () => {
    // `invalid_client` 的官方说明是「Client authentication failed」，不含 "state"，
    // 但两条规则都要经得起同时命中时的顺序
    const result = diagnoseSsoError({ detail: 'invalid_client: Client authentication failed' })
    assert.equal(result.code, 'client_auth')
    assert.equal(result.tone, 'blocked')
  })

  it('state 失效', () => {
    assert.equal(diagnoseSsoError({ detail: 'state mismatch (csrf?)' }).code, 'state')
  })

  it('`statement` 这类词不算 state 失败', () => {
    // 裸 `state` 正则会把它吃掉，于是「SQL 语句被拒」会被说成「回调失效」
    const result = diagnoseSsoError({ detail: 'unsupported statement in request body' })
    assert.notEqual(result.code, 'state')
    assert.equal(result.code, 'unknown')
  })

  it('网络层失败（浏览器 fetch 的原文）', () => {
    assert.equal(diagnoseSsoError({ detail: 'TypeError: Failed to fetch' }).code, 'unreachable')
  })

  it('没命中任何规则时走 unknown，并保留原文', () => {
    const result = diagnoseSsoError({ detail: 'something odd happened' })
    assert.equal(result.code, 'unknown')
    assert.equal(result.raw, 'something odd happened')
    // 未知也要给出两个去处，否则卡片上只有一句「出了点问题」
    assert.ok(result.action.includes('身份接入'))
  })

  it('detail 与 message 一起进原文、一起参与匹配', () => {
    const result = diagnoseSsoError({ detail: 'token exchange failed', message: 'aud did not match' })
    assert.equal(result.code, 'audience')
    assert.equal(result.raw, 'token exchange failed aud did not match')
  })

  it('空输入不抛，返回 unknown 且原文为空', () => {
    for (const input of [undefined, {}, { detail: '' }, { detail: null }]) {
      const result = diagnoseSsoError(input)
      assert.equal(result.code, 'unknown')
      assert.equal(result.raw, '')
    }
  })

  it('每条分类都齐备四个字段，且 tone 只有两种取值', () => {
    for (const [code, entry] of Object.entries(SSO_DIAGNOSIS)) {
      for (const field of ['title', 'why', 'action']) {
        assert.equal(typeof entry[field], 'string', `${code}.${field} 必须是字符串`)
        assert.ok(entry[field].length > 0, `${code}.${field} 不能为空`)
      }
      // tone 决定左边框颜色与那个小徽标；第三种取值意味着组件里没有对应分支
      assert.ok(['blocked', 'retry'].includes(entry.tone), `${code}.tone = ${entry.tone}`)
    }
  })

  it('unknown 一定存在（未命中时的兜底）', () => {
    assert.ok(SSO_DIAGNOSIS.unknown)
  })
})

describe('ssoErrorLabel', () => {
  it('已知码回标题', () => {
    assert.equal(ssoErrorLabel('audience'), SSO_DIAGNOSIS.audience.title)
  })

  it('未知码回「未分类」而不是抛错或回空串', () => {
    assert.equal(ssoErrorLabel('nope'), '未分类')
    assert.equal(ssoErrorLabel(undefined), '未分类')
  })
})
