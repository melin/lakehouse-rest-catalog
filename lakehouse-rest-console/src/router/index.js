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
    // SSO 的回跳地址。必须与服务端 `lakehouse.rest.auth.console.oidc.redirect-uri`
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
    // Lance 表详情。与 `/tables/...`、`/iceberg-tables/...` 三页平行，理由与 Iceberg 那一页
    // 同源：三个协议面的数据来源、可读字段、按需加载的单位都不同（Lance 连 `/v1/config`
    // 握手都没有，id 是 `catalog$schema$table` 的拼接串）。
    //
    // `namespace` 段是**叶子名**（不是 `catalog$schema` 整串）：存储优化接口要的是
    // `lakehouse_database.name`，Lance 的命名空间只有一级，叶子名就是它。
    path: '/lance-tables/:prefix/:namespace/:table',
    name: 'lanceTable',
    component: () => import('@/views/LanceTableView.vue'),
    meta: { title: '表详情（Lance）', group: '目录（Catalog API）', parent: 'browse' },
  },
  {
    path: '/catalogs',
    name: 'catalogs',
    component: () => import('@/views/CatalogsView.vue'),
    meta: { title: 'Catalogs', icon: 'Coin', group: '治理（Management API）', nav: true },
  },
  {
    // 新建 catalog 是一页而不是弹窗：这一页要给的不只是表单，还有右栏常驻的
    // 「保存后会怎样」（凭据来源判定、作用域、风险）与一段可复制的接入配置——
    // 这三样都要在填的过程中一直看得见，弹窗一滚就没了。
    //
    // 必须排在 `/catalogs/:catalogName` 之前：否则 `new` 会被当成一个 catalog 名，
    // 于是「新建」打开的是「叫 new 的那个 catalog 的详情页」，而它不存在。
    path: '/catalogs/new',
    name: 'catalogCreate',
    component: () => import('@/views/CatalogFormView.vue'),
    meta: { title: '新建 Catalog', group: '治理（Management API）', parent: 'catalogs' },
  },
  {
    // 编辑与新建共用一页，差别只在「名字与格式是只读的、保存要带 entityVersion」。
    // 分成两个页面会让「同一个对象的同一份配置」有两处实现，改一处忘一处。
    path: '/catalogs/:catalogName/edit',
    name: 'catalogEdit',
    component: () => import('@/views/CatalogFormView.vue'),
    meta: { title: '编辑 Catalog', group: '治理（Management API）', parent: 'catalogs' },
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
    // 数据保护总览（mockup 画面 1）：跨表的行过滤 / 列脱敏策略列表。
    // 与「权限管理」并列在治理分组：权限管「谁能读」，数据保护管「读到什么」——
    // 两者是同一层治理决策，不该藏进表详情（表详情的「数据策略」页签管单表编辑）。
    path: '/policies',
    name: 'policies',
    component: () => import('@/views/PoliciesView.vue'),
    meta: { title: '数据保护', icon: 'Key', group: '治理（Management API）', nav: true },
  },
  {
    // 新建策略向导（mockup 画面 2）：跨表入口——目标表三段选择 + 与表详情页签
    // 同一份结构化编辑器（逻辑共用 utils/policyAst.js）。表上已有策略时不给建
    // （一张表一条，整份替换会顶掉现有策略），引导去表详情编辑。
    // 列表的下钻目标不进侧边菜单，与 /catalogs/:catalogName 同一取舍。
    path: '/policies/new',
    name: 'policyWizard',
    component: () => import('@/views/PolicyWizardView.vue'),
    meta: { title: '新建数据策略', group: '治理（Management API）', parent: 'policies' },
  },
  {
    // 主体视角试算（mockup 画面 4）：以指定主体（不是当前登录者）判定策略四态
    // 并用预览求值链读一页数据。挂在策略上下文（三段名在路径里），
    // 从总览页的「试算」或表详情页签跳进来；同样是下钻目标，不进菜单。
    path: '/policies/:catalog/:database/:table/dryrun',
    name: 'policyDryRun',
    component: () => import('@/views/PolicyDryRunView.vue'),
    meta: { title: '主体试算', group: '治理（Management API）', parent: 'policies' },
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
    // 敏感数据总览（mockup `#overview`）。与「数据保护」并列在治理分组：
    // 数据保护管「怎么保护」（行过滤 / 列脱敏），这一屏管「哪些列需要保护」——
    // 分类分级在前、保护策略在后，两屏是同一层治理决策的两半。
    //
    // <p>挂在管理 API（`/api/management/v1/sensitivity-*`）下，分组标签写的就是
    // 「这一页用的是哪套 API」。
    path: '/sensitivity',
    name: 'sensitivityOverview',
    component: () => import('@/views/SensitivityOverviewView.vue'),
    meta: { title: '敏感数据', icon: 'Aim', group: '治理（Management API）', nav: true },
  },
  {
    // 列级标签清单（mockup `#tags`）：识别结论落成的治理事实。
    //
    // <p>与总览分成两屏而不是一页两个页签：总览回答「治理到什么程度」（跨页统计、
    // 一眼扫完），标签清单回答「具体哪一列是什么」（检索、逐条改删）。
    // 两者的操作频次与检索方式都不同，混在一页会让「我今天要做什么」变得不清楚。
    path: '/sensitivity/tags',
    name: 'sensitivityTags',
    component: () => import('@/views/SensitivityTagsView.vue'),
    meta: { title: '敏感标签', icon: 'CollectionTag', group: '治理（Management API）', nav: true },
  },
  {
    // 类型与探测器配置（mockup `#types`）：识别能力本身。
    //
    // <p>**没有 catalog 选择器**，与上面两屏刻意不同：`lakehouse_sensitive_type`
    // 没有 catalog 列，类型是识别能力、不是某个目录的属性。服务端四处判的也是
    // `requireServiceAdmin` 而不是 `CATALOG:ALTER`——改一个类型的权重等于改所有
    // catalog 的判定口径，把入口收紧到服务级是安全取舍。
    //
    // <p>这一屏还是设计稿 §12 P0 那条验收断言「改一个内置类型的探测器权重后，
    // 下一轮扫描的判定随之改变」唯一的操作入口。
    path: '/sensitivity/types',
    name: 'sensitivityTypes',
    component: () => import('@/views/SensitivityTypesView.vue'),
    meta: { title: '敏感类型', icon: 'SetUp', group: '治理（Management API）', nav: true },
  },
  {
    // 发起扫描（mockup `#scan`，设计稿 §11）。
    //
    // <p>与总览分成两屏而不是把表单塞进总览的弹窗：这一屏要给的不只是参数，还有
    // 「这一发会读什么」——范围级联、执行位置对比、成本与权限提示，全都必须在按下
    // 按钮**之前**看得见（采样是 §5.4 明确承认的「合法高权限读」）。总览页只留一个
    // 一跳就到的入口按钮，那是「看治理到什么程度」的页面，不该被表单撑开。
    //
    // <p>不进侧边菜单（没有 `nav`）：它是总览的下钻目标，单独列在菜单里会变成一个
    // 「打开就是空表单」的入口——与 `/catalogs/:catalogName` 同一取舍。
    path: '/sensitivity/scan',
    name: 'sensitivityScan',
    component: () => import('@/views/SensitivityScanView.vue'),
    meta: { title: '发起扫描', group: '治理（Management API）', parent: 'sensitivityOverview' },
  },
  {
    // 识别结果审核（mockup `#findings`，人审闭环：读 + 写）。
    //
    // <p>P1 起写侧在这一屏：确认落 `source=MANUAL` 的标签、拒绝落永久抑制项
    // （`POST /sensitivity-findings:decide`），页尾「已否决」卡片是解禁入口。
    // 这一屏的价值在于把「为什么这么判」摊开——命中了哪一层、各层贡献多少，
    // 这些在标签清单里看不到（那里只有结论）。
    // 不进菜单，从总览的「待人审」卡与「最近扫描」的行跳进来。
    path: '/sensitivity/findings',
    name: 'sensitivityFindings',
    component: () => import('@/views/SensitivityFindingsView.vue'),
    meta: { title: '识别结果审核', group: '治理（Management API）', parent: 'sensitivityOverview' },
  },
  {
    // 扫描调度（mockup `#schedules`，设计稿 §5.6.3 / §12 P4）。
    //
    // <p>**现在是过渡态**：调度已拍板改为 catalog 自建（与表优化调度同形），属 P4；
    // 在第 6 张表 `lakehouse_sensitivity_schedule` 与 `/sensitivity-schedules` 的
    // 5 条端点落地之前，这一屏给的是那行可复制的 curl（cron 由外部系统自己管）。
    // 所以「没有新建调度按钮」的成因是「CRUD 还没到」，不是「设计里没有这一屏」。
    // 它不进侧边菜单：一个只读的建议页摆在菜单里会被读成「调度功能在这」，
    // 而运维真正要的是那行 curl——入口开在发起扫描屏的页尾。
    path: '/sensitivity/schedules',
    name: 'sensitivitySchedules',
    component: () => import('@/views/SensitivitySchedulesView.vue'),
    meta: { title: '扫描调度', group: '治理（Management API）', parent: 'sensitivityOverview' },
  },
  {
    // OIDC 配置：SSO 的**生效配置** + 连通性测试 + 令牌样例。
    //
    // <p>整页只读，没有编辑框——这些字段来自部署配置（yml / K8s ConfigMap）。
    // 允许在线改等于「已经登录进来的人可以改自己的准入规则」，而配错一次就是
    // 全员登不上；服务端因此只提供读与探活两个端点，前端也就不假装能改。
    // 页面上的「怎么改」写成操作指引，指向具体配置键。
    path: '/identity',
    name: 'identityProvider',
    component: () => import('@/views/IdentityProviderView.vue'),
    meta: { title: 'OIDC 配置', icon: 'Connection', group: '身份', nav: true },
  },
  {
    // 组映射：IdP 组 → 角色。
    //
    // <p>与「OIDC 配置」分成两页而不是一页两组表单：前者改一次要重新部署，
    // 后者是日常运维（人来了、组改了），两者的频次差一个量级，混在一页会让
    // 「我今天要做什么」变得不清楚。
    path: '/group-mappings',
    name: 'groupMappings',
    component: () => import('@/views/GroupMappingsView.vue'),
    meta: { title: '组映射', icon: 'Share', group: '身份', nav: true },
  },
  {
    // 目录事件流：两种表格式的事件在同一个流里（一张 audit_event 表、一个端点），
    // 页面只做「看」——刷新是带游标的增量拉取（`?since=`，契约见
    // docs/event-framework.md §4），不做往回翻页；要回看历史就「重置游标」
    // 重新取最新一页。挂在控制台扩展命名空间（/api/console/v1/events）而不是
    // 管理 API 下，所以分组单列为「审计」，与「这一页用的是哪套 API」的约定一致。
    path: '/events',
    name: 'events',
    component: () => import('@/views/EventsView.vue'),
    meta: { title: '事件', icon: 'Bell', group: '审计', nav: true },
  },
  {
    // 身份审计：登录 / 供给 / 组同步三类事件。
    //
    // <p>与「事件」同一分组、同一张 `audit_event` 表、同一个端点
    // （`/api/console/v1/events`）——这一页只是把游标范围收窄到身份相关的
    // 事件类型，并把 `attributes` 里的结果/角色差集翻译成人话。
    // 分成两页是因为「查一次登录为什么失败」和「翻整体变更」的检索方式不同：
    // 前者按主体名找，后者按时间扫。
    path: '/identity-audit',
    name: 'identityAudit',
    component: () => import('@/views/IdentityAuditView.vue'),
    meta: { title: '身份审计', icon: 'Tickets', group: '审计', nav: true },
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
