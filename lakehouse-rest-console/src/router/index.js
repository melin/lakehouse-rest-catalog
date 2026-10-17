import { createRouter, createWebHistory } from 'vue-router'

import { auth } from '@/store/auth.js'

/**
 * 路由表。用 history 模式（路径不带 `#`），基址为 `/console/`。
 *
 * <p>history 模式需要服务端把 `/console/**` 的未知路径回退到 `index.html`，
 * 由服务端的 `ConsoleRouteController` 承担——控制台自己无法处理深链接刷新。
 *
 * <p>导航菜单直接由这张表生成（见 {@link navGroups}），不另外维护一份菜单配置：
 * 加一个页面只需在这里加一条路由，侧边栏与面包屑跟着就有了。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    // 不进侧边菜单，也不套管理布局：登录时还不知道自己是谁，
    // 让「当前目录」「退出登录」这些控件出现在旁边只会误导
    meta: { title: '登录', public: true },
  },
  {
    // SSO 的回跳地址。必须与服务端 `paimon.rest.auth.console.oidc.redirect-uri`
    // 以及 IdP 侧登记的地址逐字一致（`api/oidc.js` 的 CALLBACK_PATH 同此）。
    // 它也是 public：此时手上还没有令牌，套上管理布局会先发出一批注定 401 的请求
    path: '/auth/callback',
    name: 'authCallback',
    component: () => import('@/views/AuthCallbackView.vue'),
    meta: { title: '正在登录', public: true },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '控制台概览', icon: 'Odometer', group: '概览', nav: true },
  },
  {
    path: '/browse',
    name: 'browse',
    component: () => import('@/views/BrowseView.vue'),
    meta: { title: '目录浏览', icon: 'Files', group: '目录（Catalog API）', nav: true },
  },
  {
    path: '/tables/:prefix/:database/:table',
    name: 'table',
    component: () => import('@/views/TableView.vue'),
    meta: { title: '表详情', group: '目录（Catalog API）', parent: 'browse' },
  },
  {
    // Iceberg 表详情。与 `/tables/...` 平行而不是同一个页面里二选一：
    // Paimon 版的七个页签挂在 `/databases/**` 上，这一页的数据几乎全部来自
    // 一次 `loadTable`（LoadTableResult 的 metadata 是整份 TableMetadata），
    // 数据形状与按需加载的单位都不同——塞进一个组件是每屏两遍 v-if。
    // `database` 段是点号拼接的 namespace 显示名（与 /browse 的深链接同一口径）。
    path: '/iceberg-tables/:prefix/:database/:table',
    name: 'icebergTable',
    component: () => import('@/views/IcebergTableView.vue'),
    meta: { title: '表详情（Iceberg）', group: '目录（Catalog API）', parent: 'browse' },
  },
  {
    path: '/catalogs',
    name: 'catalogs',
    component: () => import('@/views/CatalogsView.vue'),
    meta: { title: 'Catalogs', icon: 'Coin', group: '治理（Management API）', nav: true },
  },
  {
    // catalog 详情。`catalogName` 就是 catalog 的 prefix（创建后不可改名），
    // 因此它既在路径里也在查询参数里被别的页面引用，是同一件事的两种写法。
    //
    // 不进侧边菜单（没有 `nav`）：它是一个列表的下钻目标，和 `/tables/...` 一样，
    // 单独列在菜单里会变成一个「打开就是空态」的入口。
    path: '/catalogs/:catalogName',
    name: 'catalogDetail',
    component: () => import('@/views/CatalogDetailView.vue'),
    meta: { title: 'Catalog 详情', group: '治理（Management API）', parent: 'catalogs' },
  },
  {
    path: '/principals',
    name: 'principals',
    component: () => import('@/views/PrincipalsView.vue'),
    meta: { title: '主体', icon: 'User', group: '治理（Management API）', nav: true },
  },
  {
    path: '/principal-roles',
    name: 'principalRoles',
    component: () => import('@/views/PrincipalRolesView.vue'),
    meta: { title: '角色', icon: 'UserFilled', group: '治理（Management API）', nav: true },
  },
  {
    // 控制台唯一的授权入口：Paimon 官方权限模型，授权直接面向 principal
    // （user:/group:/role:），覆盖 catalog / database / table / view 四级资源。
    //
    // <p>Polaris 的 RBAC（主体 → 角色 → catalog 角色 → 资源授权）没有控制台页面：
    // 它的管理面留在管理 API 与 Spark SQL 上（`lakehouse-rest-spark` 的 CREATE ROLE /
    // GRANT 等语句），控制台只暴露「谁对哪个对象有什么权限」这一件事。
    path: '/permissions',
    name: 'permissions',
    component: () => import('@/views/PermissionsView.vue'),
    meta: { title: '权限管理', icon: 'Lock', group: '治理（Management API）', nav: true },
  },
  {
    // 优化批次是跨表跨库的运维入口：策略配在表上（表详情的「存储优化」页签），
    // 而「哪些批次在跑、跑成什么样、失败的表怎么重试」只有一个地方看——这里。
    //
    // 排在治理分组而不是「目录浏览」旁边：它挂在管理 API
    // （`/api/management/v1/optimization/**`）下，与 catalog API 的浏览是两套接口；
    // 分组标签写的就是「这一页用的是哪套 API」。
    //
    // `?job=` 只用来「打开并展开某一个批次」——从表详情页的历史点批次号跳过来。
    // 它不设成路由参数：这一页没有「当前批次」的概念，URL 也不必随展开状态变化。
    path: '/optimization-jobs',
    name: 'optimizationJobs',
    component: () => import('@/views/OptimizationJobsView.vue'),
    meta: { title: '优化任务', icon: 'Histogram', group: '治理（Management API）', nav: true },
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '连接设置', icon: 'Setting', group: '系统', nav: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/NotFoundView.vue'),
  },
]

export const router = createRouter({
  history: createWebHistory('/console/'),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/** 侧边栏用的分组菜单，按 `meta.group` 聚合，保留路由表里的声明顺序。 */
export function navGroups() {
  const groups = new Map()
  for (const route of router.options.routes) {
    if (!route.meta?.nav) continue
    const group = route.meta.group || '其他'
    if (!groups.has(group)) {
      groups.set(group, [])
    }
    groups.get(group).push({
      name: route.name,
      path: route.path,
      title: route.meta.title,
      icon: route.meta.icon,
    })
  }
  return [...groups.entries()].map(([group, items]) => ({ group, items }))
}

/**
 * 登录守卫。
 *
 * <p>需不需要登录由服务端的 {@code GET /api/console/v1/auth} 决定，前端只负责照做：
 * 服务端要求令牌（`authEnabled`）**或**控制台自己要求登录（`consoleRequired`，
 * 默认 true）而当前没有一个被认可的令牌时，跳到登录页。两个判据都收在
 * `auth.loginRequired()` 里——服务端那边是同一个式子
 * （`RestServerProperties.Auth.Console#loginRequired`），两处分开写迟早会漂移。
 *
 * <p>`consoleRequired` 单独存在是因为它**不是安全边界**：它开着而 `authEnabled`
 * 关着时，数据接口仍然匿名可调，这层只挡住界面。这句话在登录页与「连接设置」页都要出现。
 *
 * <p>问不到服务端时**放行**，不把用户困在登录页：连不上时该看到的是各页面自己的
 * 错误提示（它会带上 HTTP 状态与失败原因），而不是一句「请先登录」——
 * 后者会把「服务端没起来」误导成「凭据不对」。
 */
router.beforeEach(async (to) => {
  if (to.meta?.public) {
    return true
  }
  if (!(await auth.ensureChecked())) {
    return true
  }
  // 令牌过期了先清掉再判断，别带着一个必然 401 的令牌继续走：
  // 那样每个页面都会显示「未通过鉴权」，看起来像权限不足而不是需要重新登录
  auth.dropExpiredToken()
  if (!auth.loginRequired() || auth.state.authenticated) {
    return true
  }
  return {
    name: 'login',
    // 只带站内路径：`redirect` 会原样交给 router.replace，
    // 放行一个绝对 URL 等于开放重定向
    query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
  }
})

router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title ? `${title} · LakeHouse Catalog 控制台` : 'LakeHouse Catalog 控制台'
})

export default router
