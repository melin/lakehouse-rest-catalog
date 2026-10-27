/**
 * 渲染检查用的最小页面：把 AutoRefreshControl 放进真实的页头动作区里画一遍。
 *
 * <p>为什么不靠「看一眼源码」确认：这个控件是**三个控件拼在一行**（开关、文字、下拉），
 * 宽度与对齐出问题时不报错，只是把「新建 Catalog」挤到下一行；
 * 关掉时下拉禁用、失败时多出一颗角标，也都是只有画出来才看得见的态。
 *
 * <p>用法：`vite build` 之后起一个静态服务器打开本页；`?theme=dark` 切暗色，
 * `?stale=1` 让第三行带上失败角标。
 */
import { createApp, h, ref } from 'vue'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@/styles/index.css'

import AutoRefreshControl from '@/components/AutoRefreshControl.vue'

const params = new URLSearchParams(location.search)
if (params.get('theme') === 'dark') {
  document.documentElement.classList.add('dark')
}

/** 一行页头：标题 + 控件 + 两个按钮，与 Catalogs 页的真实排布一致。 */
function headerRow({ title, enabled, seconds, staleError }) {
  return h('div', { class: 'pc-page-head' }, [
    h('div', [
      h('h1', { class: 'pc-page-title' }, title),
      h('p', { class: 'pc-page-sub' }, '定时刷新控件：开关 + 节拍 + 失败角标'),
    ]),
    h('div', { class: 'pc-page-actions' }, [
      h(AutoRefreshControl, {
        enabled: enabled.value,
        seconds: seconds.value,
        staleError: staleError.value,
        lastAt: Date.now(),
        'onUpdate:enabled': (value) => { enabled.value = value },
        'onUpdate:seconds': (value) => { seconds.value = value },
      }),
      h('button', { class: 'el-button el-button--small' }, '刷新'),
      h('button', { class: 'el-button el-button--small el-button--primary' }, '新建 Catalog'),
    ]),
  ])
}

const App = {
  setup() {
    const rows = [
      {
        title: '默认：开启、5 秒',
        enabled: ref(true),
        seconds: ref(5),
        staleError: ref(''),
      },
      {
        title: '关闭时（节拍禁用、文字变灰）',
        enabled: ref(false),
        seconds: ref(30),
        staleError: ref(''),
      },
      {
        title: '静默刷新失败（角标 + 悬浮给原因）',
        enabled: ref(true),
        seconds: ref(10),
        staleError: ref('HTTP 500：OptimizationJobService 暂时不可用'),
      },
    ]
    return () => h('div', { class: 'pc-page', style: 'padding: 20px 24px' }, rows.map((row) => h('div', {
      style: 'padding: 10px 0 26px; border-bottom: 1px dashed var(--pc-border)',
    }, [headerRow(row)])))
  },
}

const app = createApp(App)
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.mount('#app')
