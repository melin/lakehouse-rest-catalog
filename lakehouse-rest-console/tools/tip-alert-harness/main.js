/**
 * 渲染检查页：把 TipAlert 与它替换掉的 el-alert 摆在一起看。
 *
 * <p>要看的三件事：收起后是不是真的只有一行（`el-alert__description` 不再出现）、
 * 问号是否与标题居中、以及气泡里的富文本（`<code>` / `<strong>`）是否还在。
 *
 * <p>用法：`?theme=dark` 切暗色；`?hover=1` 会在页面加载后对第一个问号派发
 * mouseenter，把气泡展开截进图里（无头截图没法真的移鼠标）。
 */
import { createApp, h } from 'vue'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.css'

import TipAlert from '@/components/TipAlert.vue'

const params = new URLSearchParams(location.search)
if (params.get('theme') === 'dark') {
  document.documentElement.classList.add('dark')
}

function block(title, ...children) {
  return h('div', { style: 'padding: 14px 24px 26px; border-bottom: 1px dashed var(--pc-border)' }, [
    h('div', { style: 'font-size: 12px; color: var(--pc-text-dim); margin-bottom: 10px' }, title),
    ...children,
  ])
}

const App = {
  setup() {
    return () => h('div', { class: 'pc-page', style: 'padding: 16px 0' }, [
      block('TipAlert（info，正文收进问号）',
        h(TipAlert, { type: 'info', class: 'pc-gap' }, {
          title: () => '默认语义 · 没有策略 = 不保护',
          default: () => [
            '没有绑定策略的表对所有主体明文。「取不到策略就拒绝」只发生在策略服务不可达时，',
            h('strong', '两者在语义上必须分开表达'),
            '，否则运维会以为「建了策略就全拦住了」。调参见 ',
            h('code', { class: 'pc-mono' }, 'lakehouse.rest.policy.enabled'),
            '。',
          ],
        })),
      block('TipAlert（warning，title 属性而非插槽）',
        h(TipAlert, {
          type: 'warning',
          title: '控制台一期不提供 Iceberg catalog 的表管理与数据浏览',
        }, {
          default: () => '创建后可以在 catalog 详情查看协议与能力，表数据请通过 Spark 等 Iceberg REST 客户端访问。',
        })),
      block('TipAlert（只有标题、没有正文：不出现空问号）',
        h(TipAlert, { type: 'info', title: '这条提示没有补充说明' })),
      block('对照：仍保留正文的 el-alert（错误类，必须一眼看到）',
        h('div', { class: 'el-alert el-alert--error is-light' }, [
          h('div', { class: 'el-alert__content' }, [
            h('span', { class: 'el-alert__title' }, '读取失败：HTTP 500'),
            h('p', { class: 'el-alert__description' }, '错误正文仍然直显——藏进气泡等于让人多点一次才知道出了什么事。'),
          ]),
        ])),
    ])
  },
}

const app = createApp(App)
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.mount('#app')

if (params.get('hover') === '1') {
  // el-tooltip 的触发器监听 mouseenter（不冒泡），因此必须直接派发到图标上
  setTimeout(() => {
    const icon = document.querySelector('.pc-tip-icon')
    icon?.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false }))
    icon?.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }))
  }, 400)
}
