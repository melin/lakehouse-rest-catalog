/**
 * `api/oidc.js#logoutRedirectUrl` 的用例。
 *
 * <p>这个函数决定「点退出登录之后浏览器去哪儿」，而它答错的两种方式都不会报错：
 * 该跳的时候不跳 —— 界面上一切正常，只是 IdP 那边的会话还留着，
 * 下一个人在同一台机器上点「登录」会直接进来；不该跳的时候跳 —— 用户被送到
 * 一个与他无关的 IdP 页面上，本机令牌却已经清掉了。
 *
 * <p>因此这里逐条钉住四个判据：有没有登出端点、这张票是不是 IdP 发的、
 * 令牌能不能当 id_token_hint、有没有配回跳地址。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import { logoutRedirectUrl } from '../../src/api/oidc.js'

const ENDPOINT = 'https://idp.example.com/realms/main/protocol/openid-connect/logout'

/** 形状正确的 JWT（内容无所谓：本函数不解析，IdP 自己会验）。 */
const ID_TOKEN = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl'

function oidc(overrides = {}) {
  return { clientId: 'lakehouse-console', endSessionEndpoint: ENDPOINT, ...overrides }
}

test('SSO 登录：跳到登出端点，带上 id_token_hint 与 client_id', () => {
  const url = new URL(logoutRedirectUrl(oidc(), { token: ID_TOKEN, source: 'oidc' }))
  assert.equal(`${url.origin}${url.pathname}`, ENDPOINT)
  assert.equal(url.searchParams.get('id_token_hint'), ID_TOKEN)
  assert.equal(url.searchParams.get('client_id'), 'lakehouse-console')
})

test('非 SSO 登录不跳：口令 / 静态令牌的用户在 IdP 那边没有会话', () => {
  for (const source of ['console-access-token', 'static-token', '']) {
    assert.equal(logoutRedirectUrl(oidc(), { token: ID_TOKEN, source }), '',
      `source=${source || '(空)'} 时不该去 IdP——把本服务的令牌当 id_token_hint 递过去，`
      + '会被 IdP 当成不属于自己的令牌')
  }
})

test('IdP 没给 end_session_endpoint 时不跳', () => {
  assert.equal(logoutRedirectUrl(oidc({ endSessionEndpoint: null }), { token: ID_TOKEN, source: 'oidc' }), '')
  assert.equal(logoutRedirectUrl(oidc({ endSessionEndpoint: '   ' }), { token: ID_TOKEN, source: 'oidc' }), '')
  assert.equal(logoutRedirectUrl(null, { token: ID_TOKEN, source: 'oidc' }), '')
})

test('不透明 access_token 不当 id_token_hint，但登出照旧', () => {
  const url = new URL(logoutRedirectUrl(oidc(), { token: 'opaque-token-value', source: 'oidc' }))
  assert.equal(url.searchParams.has('id_token_hint'), false,
    '不是 JWT 的串塞进 id_token_hint，部分 IdP 会拒绝整个登出请求')
  assert.equal(url.searchParams.get('client_id'), 'lakehouse-console',
    'client_id 仍然要带：多数 IdP 靠它定位客户端')
})

test('没拿到令牌时只带 client_id，不编造 hint', () => {
  const url = new URL(logoutRedirectUrl(oidc(), { source: 'oidc' }))
  assert.equal(url.searchParams.has('id_token_hint'), false)
  assert.equal(url.searchParams.get('client_id'), 'lakehouse-console')
})

test('配了 post_logout_redirect_uri 才带它（IdP 逐字比对登记值）', () => {
  const configured = oidc({ postLogoutRedirectUri: 'https://console.example.com/console/login' })
  assert.equal(
    new URL(logoutRedirectUrl(configured, { token: ID_TOKEN, source: 'oidc' }))
      .searchParams.get('post_logout_redirect_uri'),
    'https://console.example.com/console/login')
  assert.equal(
    new URL(logoutRedirectUrl(oidc(), { token: ID_TOKEN, source: 'oidc' }))
      .searchParams.has('post_logout_redirect_uri'),
    false, '没配时不能自己拼一个：未登记的地址会被 IdP 拒绝，用户停在错误页上')
})

test('登出端点本身带查询串时不被覆盖', () => {
  const url = new URL(logoutRedirectUrl(
    oidc({ endSessionEndpoint: `${ENDPOINT}?realm=main` }), { token: ID_TOKEN, source: 'oidc' }))
  assert.equal(url.searchParams.get('realm'), 'main')
  assert.equal(url.searchParams.get('client_id'), 'lakehouse-console')
})

test('登出端点不是合法 URL 时放弃跳转，而不是让「退出登录」失败', () => {
  assert.equal(logoutRedirectUrl(oidc({ endSessionEndpoint: 'not a url' }), { token: ID_TOKEN, source: 'oidc' }), '',
    '抛异常会让按钮先失败，比不结束 SSO 会话更糟')
})
