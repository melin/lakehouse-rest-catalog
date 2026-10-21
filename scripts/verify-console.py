#!/usr/bin/env python3
"""校验控制台与接口规格、服务端静态资源三者一致。

为什么需要它：控制台是用字符串拼 URL 的，而服务端的路径属于契约
（catalog API 见 static/rest-catalog-open-api.yaml，管理 API 见
static/polaris-management-service.yml，Iceberg 协议面见
spec/rest-catalog-open-api-iceberg.yaml）。路径写错时前端只表现为「空列表」或 404，
看不出是拼错了还是服务端改了；而这批路径分散在几十个端点里，靠人工比对不现实。

本脚本不构建前端、不启动服务端，只做静态比对——它读的是磁盘上的产物与源码，
因此可以放进提交前的检查里。

校验项：
  1. 端点存在     src/api/endpoints.js 里的每条路径都能在规格中找到（扩展端点走显式白名单）
  2. 白名单有效   声明为规格外扩展的端点确实被使用，且没有过期条目
  3. 端点引用     src/ 里按名字调用的端点都在 endpoints.js 中声明
  4. 无死条目     endpoints.js 里声明的端点都在 src/ 中被调用过
  5. 基址一致     vite 的 base、路由的 base、构建输出目录、服务端静态资源目录四处指向同一处；
                  以及控制台契约表里的令牌端点路径与服务端下发的 tokenEndpoint 是否一致
  6. 产物完整     已提交的入口页存在，且它引用的每个 assets 文件都存在
  7. 产物新鲜     构建产物不比控制台源码旧（改了源码没重新构建会在这里暴露）
  8. 对话框       每个 el-dialog 都声明 destroy-on-close（否则编辑时会残留上一次的状态）
  9. 下拉选项     每个 el-select 都声明了候选来源（漏写 el-option 时点开只有「无数据」，
                  而这类漏写编译、类型检查与单元测试都不会报）
 10. 静态凭据     catalog 自带 AK/SK 这条链路跨「服务端 DTO / 支持的类型 / 能力开关的
                  暴露 / 前端表单」四处，任何一处漏改都表现为「填了没反应」或「填完才 400」
 11. 优化参数键   存储优化的表单字段名必须与服务端 OptimizationOptions.KNOWN_KEYS（Paimon）
                  与 IcebergOptimizationOptions.KNOWN_KEYS（Iceberg）各逐字一致
                  （键名写错的表现是保存时吃一个 400，只有点进去才会发现；
                  两套键混用的表现更糟——被收下、然后静默不生效）
 12. 表格式       catalog 的 PAIMON / ICEBERG 跨「服务端枚举 / meta 下发 / 前端兜底 /
                  新建表单 / 列表徽标与筛选 / 三个页面按格式伸缩」六处，且格式**不可变**；
                  清单与判据都不能由前端另抄一份
 13. Iceberg 浏览   Iceberg catalog 的目录浏览走 namespaces/** 而不是 databases/**。
                  这一组钉「契约形状」：endpoints 逐条对 Iceberg 规格、参数名是 pageSize
                  而不是 maxResults、namespace 段用 0x1F、建表体是 Iceberg 的 schema/spec；
                  以及 Iceberg 表详情页（IcebergTableView）的契约：文件列表走 catalog 级
                  文件端点、快照处理 -1 哨兵、存储优化页签复用共用面板并传 ICEBERG。
                  错法的共同点是**不报错**——列表少一页、层级被切错、填了保存不进去
 14. 存储字段分层  catalog 表单的字段被拆成「基本 / 高级」两张表，高级那张进折叠区。
                  两条静默风险：拆表时漏一个字段（只是保存不进去，不报错）、
                  以及顺手让折叠状态参与提交内容（展开看一眼再收起，报文就变了）
 15. 事件页       事件流的增量契约要前后端钉在一起：端点路径、since/nextCursor 的
                  消费、事件类型清单对 CatalogEventType 的 wire 值、目标类型显示名
                  对 CatalogEvent 的 TARGET_* 常量、独立路由。前端另抄一份名单
                  或丢了 since，页面都不报错——只是契约悄悄失效
 16. 提示图标     问号图标必须「悬停即显示」：trigger 显式写成 hover、show-after 为 0，
                  且命中区比 14px 的图标本身大一圈。加延迟或要求点击时，用户看到的
                  现象是「移到图标上没反应」，而页面不会报任何错
 17. 文档计数     文档里写的「N 组 M 项」与脚本实际一致——加校验组的人要顺手改文档，
                  而这条约定此前没人执行（真漂过一次：docs/console.md 37、实际 38）

用法：
    python3 scripts/verify-console.py

本机若有多个 Python，确保用的是装了 PyYAML 的那个；build-console.sh 会透传
PYTHON=... 给本脚本。

退出码：0 全部通过，1 存在不一致，2 环境不满足（缺依赖）。
"""
import os
import re
import sys

try:
    import yaml
except ImportError:  # 只在环境缺依赖时触发；脚本要读规格，yaml 是硬依赖
    sys.stderr.write(
        "缺少 PyYAML：本脚本需要解析 OpenAPI 规格。\n"
        "安装方式（任选其一）：\n"
        "  %s -m pip install --user PyYAML\n"
        "  PYTHON=/path/to/python3 scripts/build-console.sh   # 换一个已装 PyYAML 的解释器\n"
        % os.path.basename(sys.executable))
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CONSOLE_DIR = os.path.join(ROOT, "lakehouse-rest-console")
ENDPOINTS_JS = os.path.join(CONSOLE_DIR, "src/api/endpoints.js")
VITE_CONFIG = os.path.join(CONSOLE_DIR, "vite.config.js")
ROUTER = os.path.join(CONSOLE_DIR, "src/router/index.js")
CLIENT_JS = os.path.join(CONSOLE_DIR, "src/api/client.js")

CATALOG_SPEC = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/rest-catalog-open-api.yaml")
# Iceberg 协议面另有一份规格副本。控制台在 Iceberg catalog 下走的是这一套路径
# （namespaces / identifiers / kebab-case 报文），因此端点比对必须把两份规格
# 并起来——只比 Paimon 那份的话，新加的 Iceberg 端点会被判成「规格里没有」。
ICEBERG_SPEC = os.path.join(ROOT, "spec/rest-catalog-open-api-iceberg.yaml")
# 管理面的规格副本随服务端分发（运行时可从 /polaris-management-service.yml 下载）。
# spec/ 下的同名副本已删除——官方 Paimon 管理 API 只剩权限 3 个 operation（见
# docs/management-api-overview.md），那份副本是另一回事，不能拿来比对管理面端点。
MANAGEMENT_SPEC = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/polaris-management-service.yml")

CONSOLE_ASSETS = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console")

# 服务端拆成四个模块后，被校验的源码分处两个模块目录。包名没变，变的只是
# 「这个包在哪个 jar 里」——因此这里按包的归属分别定位，而不是拼一个总前缀。
# 归属见 docs/maven-module-design.md §3.3：dto/support 里与格式无关的公共类型在 core，
# 控制台自己的服务与控制器在 server。
CORE_JAVA = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java/io/github/melin/lakehouse/rest")

SERVER_JAVA = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/java/io/github/melin/lakehouse/rest")

STORAGE_DTOS = os.path.join(CORE_JAVA, "dto/StorageDtos.java")
STORAGE_CONFIGS = os.path.join(CORE_JAVA, "support/StorageConfigs.java")
CONSOLE_DTOS = os.path.join(CORE_JAVA, "dto/ConsoleDtos.java")
CONSOLE_META_SERVICE = os.path.join(SERVER_JAVA, "service/ConsoleMetaService.java")
MANAGEMENT_CATALOG_SERVICE = os.path.join(
    SERVER_JAVA, "service/ManagementCatalogService.java")

STORAGE_CONFIG_FIELDS = os.path.join(
    CONSOLE_DIR, "src/components/StorageConfigFields.vue")
CATALOGS_VIEW = os.path.join(CONSOLE_DIR, "src/views/CatalogsView.vue")

# 表格式（PAIMON / ICEBERG）这条链路的六个落点。与静态凭据那一组同一个理由：
# 一个字段横跨服务端枚举、meta 下发、前端兜底与四个页面，任何一处漏改都表现为
# 「界面看起来正常，点下去才发现不对」——正是静态校验该管的事。
CATALOG_FORMAT_UTIL = os.path.join(CONSOLE_DIR, "src/utils/catalogFormat.js")
CATALOG_FORMAT_ENUM = os.path.join(CORE_JAVA, "support/CatalogFormat.java")
CATALOG_DETAIL_VIEW = os.path.join(CONSOLE_DIR, "src/views/CatalogDetailView.vue")
BROWSE_VIEW = os.path.join(CONSOLE_DIR, "src/views/BrowseView.vue")
TABLE_VIEW = os.path.join(CONSOLE_DIR, "src/views/TableView.vue")
PERMISSIONS_VIEW = os.path.join(CONSOLE_DIR, "src/views/PermissionsView.vue")

OPTIMIZATION_OPTIONS = os.path.join(SERVER_JAVA, "support/OptimizationOptions.java")
ICEBERG_OPTIMIZATION_OPTIONS = os.path.join(SERVER_JAVA, "support/IcebergOptimizationOptions.java")
OPTIMIZATION_UTIL = os.path.join(CONSOLE_DIR, "src/utils/optimization.js")

# 管理规格的 servers[0].url 是 {scheme}://{host}/api/management/v1，路径要去掉基址才可比
MANAGEMENT_BASE = "/api/management/v1"

CONSOLE_BASE = "/console/"

# 规格之外的扩展端点。每一条都要有理由，且必须在控制台里真的被调用——否则就是过期条目。
EXTENSION_ENDPOINTS = {
    ("GET", "/api/console/v1/meta"): "控制台元数据（枚举取值与服务配置摘要），非 Polaris 规格",
    # 仓库文件浏览：列 catalog 仓库里某个目录的一层内容（`?path=` 相对仓库根）。
    # 与 meta 一样是控制台扩展端点，服务端实现见 ConsoleFileController；
    # 它读的是物理目录（表目录下有哪些文件、有没有残留），两份规格都不涉及文件系统，
    # 因此按扩展端点登记。只读，且可以用 file-browser.enabled=false 关掉。
    ("GET", "/api/console/v1/catalogs/{catalogName}/files"): "控制台仓库文件浏览（列一层目录，只读，可配置关闭）",
    # 同一套浏览能力、根换成表目录：表详情页的「文件列表」。
    # 以表元数据的 path 为根，path 参数相对表目录，`..` 与绝对路径被服务端拒掉，
    # 因此这个端点列不出表外的东西。只读，同样受 file-browser.enabled 管辖。
    ("GET", "/api/console/v1/tables/{tableId}/files"): "控制台表目录文件浏览（表详情页，根为表目录，只读）",
    # 单文件下载：与列举同一开关、同一路径防护（必须指到文件，目录 400），仍是只读——
    # 把已有的字节流出去，不产生任何写入。没有它，「看一眼盘上的 Parquet 头」要登机器。
    ("GET", "/api/console/v1/catalogs/{catalogName}/files/download"): "控制台仓库单文件下载（流式，只读，可配置关闭）",
    ("GET", "/api/console/v1/tables/{tableId}/files/download"): "控制台表目录单文件下载（流式，根为表目录，只读）",
    # 登录引导。在服务端鉴权范围之外（WebConfig 用精确路径排除），
    # 因为登录页要先知道服务端支持哪些认证方式，才谈得上发起登录。
    ("GET", "/api/console/v1/auth"): "控制台登录引导（可用认证方式、令牌端点、当前令牌状态）",
    # 用户名 + 密码换令牌：控制台的默认登录方式（账号在服务端配置里，默认 admin/admin）。
    # 与下面那条令牌端点一样必须在鉴权之外——它就是用来换令牌的。
    # 用 JSON 而不是表单：调用方只有控制台，不背 OAuth 的 form-urlencoded 约定。
    ("POST", "/api/console/v1/login"): "控制台用户名密码登录（默认方式，响应是驼峰字段而非 OAuth 形状）",
    # OAuth 2.0 客户端凭据令牌端点。路径与 Polaris 一致（其官方 console 的默认
    # VITE_OAUTH_TOKEN_URL 就是这个地址），但它不是 catalog API 规格里的一节，
    # 因此在这里登记。同样必须在鉴权之外——它就是用来换令牌的。
    ("POST", "/api/catalog/v1/oauth/tokens"): "OAuth 2.0 客户端凭据令牌端点（RFC 6749 第 4.4 节）",
    # Paimon 官方 REST Management API 的权限端点（principal 直授，user:/role:）。
    # 官方 wire 契约在其 rest-management-open-api.yaml，不在本仓库的两份规格副本里，
    # 因此按扩展端点登记；服务端实现见 PermissionController。
    ("GET", "/v1/{prefix}/permissions"): "Paimon 官方权限端点：列出 principal 直授",
    ("POST", "/v1/{prefix}/permissions/grant"): "Paimon 官方权限端点：授予 principal 直授",
    ("POST", "/v1/{prefix}/permissions/revoke"): "Paimon 官方权限端点：撤销 principal 直授（幂等）",
    # 授权清单的「一行」：同主体同范围的多条赋权先折叠再分页。官方权限端点只给平铺的
    # 逐条赋权，而折叠必须发生在切页之前——否则同一组被页边界切成两行，界面上就是
    # 同一个主体出现两次。因此由控制台自己提供这层聚合视图（只读）。
    ("GET", "/v1/{prefix}/permissions/scopes"): "控制台授权清单（服务端折叠 + 分页，只读聚合）",
    # 表存储优化（本工程扩展，挂管理 API 基址）。三份规格里都没有它：策略与批次是
    # 服务端自己的资源，不是 Paimon 元数据的一部分。服务端实现见 OptimizationController。
    #
    # 两个回调端点（`/jobs/{id}/submission`、`/jobs/{id}/items/{itemId}`）刻意不在这里：
    # 它们的调用方是 k8s 里的任务，控制台没有那条调用路径。
    ("GET", "/api/management/v1/optimization/catalogs/{catalogName}/settings"): "优化：catalog 级默认设置（读）",
    ("PUT", "/api/management/v1/optimization/catalogs/{catalogName}/settings"): "优化：catalog 级默认设置（逐键写）",
    ("GET", "/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/policy"): "优化：表策略（含生效值与差异）",
    ("PUT", "/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/policy"): "优化：表策略（逐字段写）",
    ("POST", "/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/run"): "优化：立即优化一张表（返回新建的批次）",
    ("GET", "/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/items"): "优化：单表的执行历史",
    ("GET", "/api/management/v1/optimization/jobs"): "优化：批次列表",
    ("GET", "/api/management/v1/optimization/jobs/{jobId}"): "优化：批次详情（含表级明细）",
    ("POST", "/api/management/v1/optimization/jobs/{jobId}/retry"): "优化：重试失败的表（新建批次）",
    ("POST", "/api/management/v1/optimization/jobs/{jobId}/cancel"): "优化：取消批次",
    # 存储页的聚合端点（本工程扩展，挂管理 API 基址）：一次查询取 catalog 下全部表的
    # 当前快照统计，替代「一张表一个请求」的逐表扫描。服务端实现见
    # ManagementCatalogController#tableStats。
    ("GET", "/api/management/v1/catalogs/{catalogName}/table-stats"): "存储页：catalog 存储汇总（SQL 聚合，只读）",
    ("GET", "/api/management/v1/catalogs/{catalogName}/table-stats/tables"): "存储页：逐表明细的一页（远程分页，只读）",
    # 目录事件流：游标增量拉取（`?since=` 只回比它新的事件），契约见
    # docs/event-framework.md §4。两份规格都不涉及事件——这是本工程自己的
    # 审计面，服务端实现见 ConsoleEventController。只读。
    ("GET", "/api/console/v1/events"): "控制台目录事件流（游标增量拉取，只读）",
}

problems = []
checks = 0


def check(description, ok, detail=""):
    global checks
    checks += 1
    print(("  [OK]   " if ok else "  [FAIL] ") + description)
    if not ok:
        problems.append(description)
        if detail:
            for line in str(detail).splitlines():
                print("         " + line)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ------------------------------------------------------------------ 0. 解析输入

endpoints_source = read(ENDPOINTS_JS)
# 形如 `  listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },`
entry_pattern = re.compile(
    r"^\s*(\w+):\s*\{\s*method:\s*'([A-Z]+)',\s*path:\s*'([^']+)'\s*\},?\s*$", re.M)
endpoints = {}
duplicate_names = []
for match in entry_pattern.finditer(endpoints_source):
    name, method, path = match.group(1), match.group(2), match.group(3)
    if name in endpoints:
        duplicate_names.append(name)
    endpoints[name] = (method, path)

catalog_paths = yaml.safe_load(read(CATALOG_SPEC))["paths"]
iceberg_paths = yaml.safe_load(read(ICEBERG_SPEC))["paths"]
management_paths = yaml.safe_load(read(MANAGEMENT_SPEC))["paths"]

spec_routes = set()
for path, operations in catalog_paths.items():
    for method in operations:
        spec_routes.add((method.upper(), path))
for path, operations in iceberg_paths.items():
    for method in operations:
        spec_routes.add((method.upper(), path))
for path, operations in management_paths.items():
    for method in operations:
        spec_routes.add((method.upper(), MANAGEMENT_BASE + path))

console_routes = {(method, path) for method, path in endpoints.values()}
all_problems_before = len(problems)

# ------------------------------------------------------------------ 1. 端点存在

print("1. 端点存在性")
missing = sorted(console_routes - spec_routes - set(EXTENSION_ENDPOINTS))
check(f"endpoints.js 的 {len(console_routes)} 条路径都能在规格或扩展白名单中找到",
      not missing,
      "\n".join(f"{method} {path}" for method, path in missing))
check("endpoints.js 中没有重名的端点",
      not duplicate_names,
      ", ".join(duplicate_names))

# ------------------------------------------------------------------ 2. 白名单

print("2. 扩展端点白名单")
stale = sorted(set(EXTENSION_ENDPOINTS) - console_routes)
check("白名单里的扩展端点都在控制台中被使用（没有过期条目）",
      not stale,
      "\n".join(f"{method} {path}" for method, path in stale))
unjustified = sorted(route for route in EXTENSION_ENDPOINTS if not EXTENSION_ENDPOINTS[route])
check("每个扩展端点都写明了理由", not unjustified)

# ------------------------------------------------------------------ 3. 端点引用

print("3. 端点引用")
source_files = []
for base, _, files in os.walk(os.path.join(CONSOLE_DIR, "src")):
    for name in files:
        if name.endswith((".js", ".vue")):
            source_files.append(os.path.join(base, name))
all_source = "\n".join(read(path) for path in source_files)

# `download` 也算引用：它是下载端点的调用入口（响应按二进制处理，见 client.js），
# 与 get/post 等动词一样按端点名调用，不认就会把下载端点误判成死条目
called = set(re.findall(r"\b(?:get|post|put|del|request|download)\(\s*'(\w+)'", all_source))
unknown = sorted(called - set(endpoints))
check(f"src/ 中按名字调用的 {len(called)} 个端点都已声明",
      not unknown,
      ", ".join(unknown))

# ------------------------------------------------------------------ 4. 死条目

print("4. 无死条目")
unused = sorted(name for name in endpoints if name not in called)
check(f"endpoints.js 声明的 {len(endpoints)} 个端点都在 src/ 中被调用过",
      not unused,
      ", ".join(unused))

# ------------------------------------------------------------------ 5. 基址一致

print("5. 基址一致")
vite_source = read(VITE_CONFIG)
router_source = read(ROUTER)
expected_out = os.path.relpath(CONSOLE_ASSETS, CONSOLE_DIR)

check(f"vite.config.js 的 base 为 {CONSOLE_BASE}",
      f"base: '{CONSOLE_BASE}'" in vite_source,
      "未找到 base 配置")
check(f"vite.config.js 的 outDir 指向 {expected_out}",
      f"outDir: '{expected_out}'" in vite_source,
      "未找到 outDir 配置")
check(f"路由的 history base 为 {CONSOLE_BASE}",
      f"createWebHistory('{CONSOLE_BASE}')" in router_source,
      "未找到 createWebHistory 配置")
# 服务端侧：静态资源处理器与转发目标
web_config = read(os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/java/io/github/melin/lakehouse/rest/config/ConsoleWebConfig.java"))
check("服务端静态资源位置与构建输出目录一致",
      f'CONSOLE_LOCATION = "classpath:/static{CONSOLE_BASE}"' in web_config,
      "ConsoleWebConfig 中的资源位置不是 classpath:/static/console/")
check("client.js 的默认 API 基址是同源（空串）",
      "return localStorage.getItem(BASE_URL_KEY) || ''" in read(CLIENT_JS),
      "默认基址不是同源，会让生产构建后的相对路径请求打到别处")

# 令牌端点的路径在两处出现：控制台的契约表，以及服务端下发给前端的 tokenEndpoint
# （外部 OAuth 客户端按它对接）。控制台实际上用的是自己的契约表，两者不一致时
# 外部客户端打的是对的、浏览器登录打的是错的——「只有登录按钮坏」这种故障
# 很难从现象反推回原因，因此在这里钉死。
auth_controller = read(os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/java/io/github/melin/lakehouse/rest/web/ConsoleAuthController.java"))
advertised = re.search(r'TOKEN_ENDPOINT\s*=\s*"([^"]+)"', auth_controller)
console_token_path = endpoints.get("token", (None, None))[1]
check("控制台的令牌端点与服务端下发的 tokenEndpoint 一致",
      advertised is not None and console_token_path == advertised.group(1),
      f"endpoints.js={console_token_path}，ConsoleAuthController="
      f"{advertised.group(1) if advertised else '未找到 TOKEN_ENDPOINT 常量'}")

# ------------------------------------------------------------------ 6. 产物完整

print("6. 构建产物完整")
index_path = os.path.join(CONSOLE_ASSETS, "index.html")
exists = os.path.isfile(index_path)
check("入口页 index.html 已提交", exists, index_path)
if exists:
    html = read(index_path)
    referenced = re.findall(r'(?:src|href)="(/console/[^"]+)"', html)
    check("入口页引用了构建产物", bool(referenced))
    missing_assets = [
        ref for ref in referenced
        if not os.path.isfile(os.path.join(CONSOLE_ASSETS, ref[len(CONSOLE_BASE):]))
    ]
    check(f"入口页引用的 {len(referenced)} 个资源都存在",
          not missing_assets,
          "\n".join(missing_assets))

# ------------------------------------------------------------------ 7. 产物新鲜度

print("7. 构建产物新鲜度")
if not exists:
    print("  [SKIP] 入口页不存在，先构建再谈新鲜度")
else:
    built_at = os.path.getmtime(index_path)
    stale = []
    watched = [VITE_CONFIG, os.path.join(CONSOLE_DIR, "package.json")]
    for base, _, files in os.walk(os.path.join(CONSOLE_DIR, "src")):
        watched.extend(os.path.join(base, name) for name in files)
    for path in watched:
        if os.path.isfile(path) and os.path.getmtime(path) > built_at:
            stale.append(os.path.relpath(path, ROOT))
    check("构建产物不比控制台源码旧",
          not stale,
          "以下文件晚于构建产物，需要重新执行 npm run build：\n" + "\n".join(stale))

# ------------------------------------------------------------------ 8. 对话框状态

print("8. 对话框（打开/关闭时的状态残留）")

# 对话框默认常驻 DOM，内部维护副本的组件只在创建时读一次 props，
# 于是第二次打开（编辑）时表单还停在上一次的值上——保存就会把 A 类型改写成 B 类型。
# `destroy-on-close` 让每次打开都拿到新实例，是这条链路最便宜的保险。
dialog_status = []
vue_files = sorted(
    os.path.join(base, name)
    for base, _, names in os.walk(os.path.join(CONSOLE_DIR, "src"))
    for name in names if name.endswith(".vue"))

for path in vue_files:
    text = read(path)
    for match in re.finditer(r"<el-dialog\b[^>]*>", text, re.S):
        tag = match.group(0)
        if "destroy-on-close" not in tag:
            line = text[:match.start()].count("\n") + 1
            dialog_status.append(
                f"{os.path.relpath(path, ROOT)}:{line} 缺少 destroy-on-close")

dialogs = sum(len(re.findall(r"<el-dialog\b", read(p), re.S)) for p in vue_files)
check(f"全部 {dialogs} 个对话框都声明了 destroy-on-close",
      not dialog_status,
      "\n".join(dialog_status))

# ------------------------------------------------------------------ 9. 下拉选项

print("9. 下拉选项（el-select 的候选来源）")

# `el-select` 漏写 `el-option` 时，它不是一个「空列表」，而是一个点开只写「无数据」的面板：
# 带着 `allow-create` 还能让用户手工敲进去，看着像是能用；没有就成了一个点了没反应的下拉。
# 关键在于这类漏写**编译、类型检查、单元测试全都不会报**——只有在浏览器里点一下才暴露，
# 于是最容易被当成「某个下拉坏了」的偶发问题。所以在这里逐个数出来，让它变成静态可查。
empty_selects = []
select_count = 0
FREE_INPUT_MARK = "el-select-free-input"


def blank_comments(source):
    """把 HTML 注释换成等长空白，但原样保留 `el-select-free-input` 标记。

    换成等长空白而不是删掉，是为了让后面报出的行号仍然是文件里的真实行号。
    保留标记，则是为了让「有意的自由输入」能豁免下面这条检查——豁免写在源码里，
    评审时看得见；藏一张名单在脚本里就没人知道它为什么被放过了。
    """
    def repl(match):
        text = match.group(0)
        parts = []
        rest = text
        while True:
            idx = rest.find(FREE_INPUT_MARK)
            if idx < 0:
                parts.append(re.sub(r"[^\n]", " ", rest))
                break
            parts.append(re.sub(r"[^\n]", " ", rest[:idx]))
            parts.append(FREE_INPUT_MARK)  # 原样保留，长度不变
            rest = rest[idx + len(FREE_INPUT_MARK):]
        return "".join(parts)
    return re.sub(r"<!--.*?-->", repl, source, flags=re.S)


# 标记在 blank_comments 之后仍然长这样
MARK_PLACEHOLDER = FREE_INPUT_MARK

for path in vue_files:
    text = blank_comments(read(path))
    for match in re.finditer(r"<el-select\b", text):
        select_count += 1
        line = text[:match.start()].count("\n") + 1
        location = f"{os.path.relpath(path, ROOT)}:{line}"
        # 标记写在 el-select 紧邻的上方（注释换行也算在内，故留一段余量）
        if MARK_PLACEHOLDER in text[max(0, match.start() - 400):match.start()]:
            continue
        rest = text[match.start():]
        # 只看 select 自己的开始标签：内部 <el-option ... /> 也是自闭合的，
        # 直接找「第一个 />」会把它错当成 select 自闭合，从而把所有正常写法误判成漏写。
        open_tag = re.match(r"<el-select\b[^>]*>", rest, re.S)
        if open_tag is None:
            empty_selects.append(f"{location} 的开始标签没有正常闭合")
            continue
        if open_tag.group(0).endswith("/>"):
            empty_selects.append(
                f"{location} 写成自闭合标签，无法携带 el-option；"
                f"若确是有意的自由输入，在其上方加 <!-- {FREE_INPUT_MARK} --> 说明")
            continue
        body = rest[open_tag.end():]
        close_tag = body.find("</el-select>")
        if close_tag == -1:
            empty_selects.append(f"{location} 找不到配对的 </el-select>")
            continue
        if not re.search(r"<el-option\b|<el-option-group\b|:options\s*=", body[:close_tag]):
            empty_selects.append(
                f"{location} 没有 el-option / el-option-group / :options；"
                f"若确是有意的自由输入，在其上方加 <!-- {FREE_INPUT_MARK} --> 说明")

check(f"全部 {select_count} 个下拉都声明了候选来源（或显式豁免为自由输入）",
      not empty_selects,
      "\n".join(empty_selects))

# ------------------------------------------------------------------ 10. 静态凭据

print("10. 静态凭据（表单 ↔ 服务端字段与能力开关）")

# catalog 自带的 AK/SK 是超出规格的扩展，它这条链路横跨四个文件：服务端 DTO 声明字段、
# StorageConfigs 决定哪些类型有这对字段、ConsoleDtos / ConsoleMetaService 把「本部署能不能
# 加密落库」报给前端、前端据此决定渲染与校验。任何一处漏改的表现都是「填了没反应」
# 或「填完才吃一个 400」——都是要真点进去才发现的那类问题，因此在这里静态钉住。
storage_dtos = read(STORAGE_DTOS)
storage_configs = read(STORAGE_CONFIGS)
fields_vue = read(STORAGE_CONFIG_FIELDS)


def record_shapes(source):
    """从 Java record 声明里取「参数列表」与「storageType() 返回的枚举值」。

    不另抄一份「哪个 record 对应哪个 storageType」的名单：抄了就会漂移，
    而漂移的表现是校验静默放过一个已经改了名字的类型。
    """
    shapes = {}
    for match in re.finditer(r"public record (\w+)\(", source):
        name = match.group(1)
        tail = source[match.end():]
        next_record = re.search(r"\n    public record ", tail)
        block = tail[:next_record.start()] if next_record else tail
        header = block[:block.index(")")] if ")" in block else block
        wire = re.search(r"ManagementEnums\.StorageType\.(\w+)", block)
        shapes[name] = (header, wire.group(1) if wire else None)
    return shapes


def parameter_names(header):
    """record 头里的参数名。先去掉泛型（`List<String>` 里的逗号不参与分隔）。"""
    names = []
    for part in re.sub(r"<[^>]*>", "", header).split(","):
        part = part.strip()
        if part:
            names.append(re.split(r"\s+", part)[-1])
    return names


shapes = record_shapes(storage_dtos)
by_type = {wire: name for name, (_, wire) in shapes.items() if wire}

server_types = []
method = re.search(
    r"supportsStaticCredentials\(StorageConfigInfo info\)\s*\{(.*?)\n    \}",
    storage_configs, re.S)
for cls in re.findall(r"instanceof (\w+)", method.group(1) if method else ""):
    if shapes.get(cls, (None, None))[1]:
        server_types.append(shapes[cls][1])
server_types = sorted(server_types)

vue_types_source = re.search(
    r"STATIC_CREDENTIAL_TYPES\s*=\s*\[([^\]]*)\]", fields_vue)
vue_types = sorted(re.findall(r"'([^']+)'", vue_types_source.group(1))) \
    if vue_types_source else []

check("表单承载静态凭据的类型与服务端 supportsStaticCredentials 一致",
      server_types and vue_types == server_types,
      f"StorageConfigs.java={server_types or '未解析出 instanceof 分支'}，"
      f"StorageConfigFields.vue={vue_types or '未找到 STATIC_CREDENTIAL_TYPES'}")

vue_keys_source = re.search(
    r"STATIC_CREDENTIAL_KEYS\s*=\s*\[([^\]]*)\]", fields_vue)
vue_keys = sorted(re.findall(r"'([^']+)'", vue_keys_source.group(1))) \
    if vue_keys_source else []
# 「像凭据的」参数名。新加第三个凭据字段却忘了在表单里处理时，这条会报出来
CREDENTIAL_HINT = re.compile(r"(?i)accesskey|secret|credential")

mismatched = []
for wire in vue_types:
    shape = shapes.get(by_type.get(wire, ""), None)
    if shape is None:
        mismatched.append(f"{wire} 在 StorageDtos.java 里找不到对应的 record")
        continue
    declared = sorted(name for name in parameter_names(shape[0])
                      if CREDENTIAL_HINT.search(name))
    if declared != vue_keys:
        mismatched.append(f"{wire}: 服务端 {declared}，前端 {vue_keys}")
check(f"三种类型的凭据字段名与服务端 DTO 逐字一致（{vue_keys or '未知'}）",
      vue_keys and not mismatched,
      "\n".join(mismatched))

# 这两个字段是本工程加的：万一上游规格哪天也加了同名但语义不同的字段，
# 这条会失败，提醒先去对齐语义再决定是否还叫「扩展」。
management_schemas = (yaml.safe_load(read(MANAGEMENT_SPEC)).get("components")
                      or {}).get("schemas") or {}


def schema_properties(schema):
    names = set(schema.get("properties") or {}) if isinstance(schema, dict) else set()
    for part in (schema or {}).get("allOf") or []:
        if isinstance(part, dict):
            names |= set(part.get("properties") or {})
    return names


spec_properties = (schema_properties(management_schemas.get("StorageConfigInfo"))
                   | schema_properties(management_schemas.get("AwsStorageConfigInfo")))
overlapping = sorted(set(vue_keys) & spec_properties)
check("规格里没有这两个字段（它们确实是本工程的扩展）",
      bool(vue_keys) and not overlapping,
      f"管理规格的 StorageConfigInfo / AwsStorageConfigInfo 已含 {overlapping}，"
      f"需要重新确认语义后再决定是否沿用同一组字段名")

console_dtos = read(CONSOLE_DTOS)
meta_service = read(CONSOLE_META_SERVICE)
check("服务端在 meta 里声明并如实赋值 staticCredentialsEnabled",
      "boolean staticCredentialsEnabled" in console_dtos and "cipher.available()" in meta_service,
      "ConsoleDtos.Enums 需要声明该字段，ConsoleMetaService.enums() 需要传 cipher.available()")

catalogs_view = read(CATALOGS_VIEW)
wiring = {
    "CatalogsView 从 meta 取值": "enums?.staticCredentialsEnabled" in catalogs_view,
    "CatalogsView 传给表单": ':static-credentials-enabled="staticCredentialsEnabled"' in catalogs_view,
    "表单声明该 prop": "staticCredentialsEnabled: { type: Boolean, default: false }" in fields_vue,
}
check("能力开关从 meta 一路传到表单的 prop",
      all(wiring.values()),
      "\n".join(name for name, ok in wiring.items() if not ok))

check("能力关着时表单给出可操作的提示（而不是把字段藏起来）",
      "lakehouse.rest.storage.credential-secret-key" in fields_vue
      and "openssl rand -base64 32" in fields_vue,
      "StorageConfigFields.vue 应说明该开哪个配置项以及生成密钥的命令")

# 读路径的脱敏：`toDto` 只要漏掉这一层，密文就会原样出现在每个 catalog 的响应里
# ——而客户端拿到一串 base64 并不会报错，它只会安静地把密文当成配置再写回去。
check("管理 API 读路径对存储配置脱敏（withoutSecrets）",
      "withoutSecrets" in read(MANAGEMENT_CATALOG_SERVICE),
      "ManagementCatalogService 的 toDto 需要在返回前抹掉 secretAccessKey 的密文")

# ------------------------------------------------------------------ 11. 优化参数键

print("11. 优化参数键（表单字段 ↔ 服务端 KNOWN_KEYS）")

# 存储优化的每一项动作允许出现哪些参数键，由服务端的 OptimizationOptions.KNOWN_KEYS
# （Paimon）与 IcebergOptimizationOptions.KNOWN_KEYS（Iceberg）定义；键名写错的表现是
# 保存时吃一个 400「Unknown compact option」——编译器、类型检查与单元测试都不会报，
# 只有点进去保存一次才发现。因此在这里把两处逐字比一遍。
#
# **两种格式各一套键**：`retain-min`（Paimon，最少保留几个快照）与 `retain-last`
# （Iceberg，保留最后几个）是语义相反的一对，键名共用一套的话，保存时会 400，
# 或者更糟——互不认识却都被收下，然后静默不生效。因此下面按格式各比一次。
#
# 比的是「前端那份字段定义」而不是前端再抄一份名单：字段定义是渲染表单的依据，
# 也是提交时唯一会出现在请求体里的键，它跟服务端对不上就是真的对不上。
opt_util = read(OPTIMIZATION_UTIL)


def server_option_keys(source, wire_of):
    """服务端某个参数类里的 {动作 wire 名: 排序后的键名}。

    解析的是两件东西：`public static final String STRATEGY = "strategy";` 这样的键常量，
    与 `Action.XXX, Set.of(...)` 这样的「动作 → 键集合」映射，两者用动作常量名对上。
    动作常量本身（`COMPACT("compact", "Compaction")`）只在 `OptimizationOptions` 里声明一次，
    Iceberg 那份用的是同一个枚举，所以 `wire_of` 由调用方传进来、不从本类里找。
    不写死对照表，抄了就会漂移。
    """
    literals = dict(re.findall(r'public static final String (\w+)\s*=\s*"([^"]+)";', source))
    keys = {}
    for constant, names in re.findall(r"Action\.(\w+),\s*Set\.of\(([^)]*)\)", source):
        wire = wire_of.get(constant)
        if wire is None:
            continue
        resolved = []
        for name in names.split(","):
            name = name.strip()
            if not name:
                continue
            # 解析不出的常量名原样带上：与其静默漏掉一个键，不如让它在这里显形
            resolved.append(literals.get(name, "<未解析:" + name + ">"))
        keys[wire] = sorted(resolved)
    return keys


def console_field_keys(source, marker):
    """前端某个字段表里的 {动作 wire 名: 排序后的 `key:` 值}。

    `marker` 是那张表的声明（`const PAIMON_FIELDS = {` / `const ICEBERG_FIELDS = {`）：
    从它起、到下一个顶层声明（顶格 `const` / `export` / `function`）前为止。
    段内的动作块以两格缩进的 `compact: [` 起头——两格是这张表的层级，
    因此不会误吃动作元数据里那些顶格与四格缩进的东西。
    """
    start = source.index(marker) + len(marker)
    tail = source[start:]
    end = re.search(r"^(?:const|export|function)\s", tail, re.M)
    block = tail[:end.start()] if end else tail
    marks = [(match.group(1), match.start()) for match in re.finditer(r"^  ([a-z]+): \[", block, re.M)]
    keys = {}
    for index, (wire, offset) in enumerate(marks):
        stop = marks[index + 1][1] if index + 1 < len(marks) else len(block)
        keys[wire] = sorted(re.findall(r"key: '([^']+)'", block[offset:stop]))
    return keys


def compare_option_keys(label, server_keys, frontend_keys):
    """逐动作比一次，返回不一致的描述（空列表表示一致）。"""
    differences = []
    for wire in sorted(set(server_keys) | set(frontend_keys)):
        server = server_keys.get(wire, [])
        front = frontend_keys.get(wire, [])
        if server != front:
            differences.append(f"{label} {wire}: 服务端 {server}，前端 {front}")
            continue
        if any(name.startswith("<未解析:") for name in server):
            differences.append(f"{label} {wire}: 服务端有一处常量没解析出来 {server}")
    return differences


optimization_source = read(OPTIMIZATION_OPTIONS)
# 动作常量名 → wire 名（`COMPACT("compact", "Compaction")`）。两种格式共用这一个枚举，
# 因此只解析一次，两个参数类都用它。
action_wire = dict(re.findall(r'(\w+)\("([a-z]+)",\s*"', optimization_source))
paimon_server_keys = server_option_keys(optimization_source, action_wire)
iceberg_server_keys = server_option_keys(read(ICEBERG_OPTIMIZATION_OPTIONS), action_wire)
paimon_frontend_keys = console_field_keys(opt_util, "const PAIMON_FIELDS = {")
iceberg_frontend_keys = console_field_keys(opt_util, "const ICEBERG_FIELDS = {")

paimon_differences = compare_option_keys("Paimon", paimon_server_keys, paimon_frontend_keys)
check(f"{len(paimon_server_keys)} 项动作的 Paimon 参数键与服务端 KNOWN_KEYS 逐字一致",
      bool(paimon_server_keys) and not paimon_differences,
      "\n".join(paimon_differences))

# Iceberg 那一份必须**真的不同**：两套键若能对上同一批键名，说明有人把 Iceberg 的
# 字段直接复制了 Paimon 那份（`retain-min` 之类会一起跟过来），这条就是那道闸。
iceberg_differences = compare_option_keys("Iceberg", iceberg_server_keys, iceberg_frontend_keys)
check(f"{len(iceberg_server_keys)} 项动作的 Iceberg 参数键与服务端 KNOWN_KEYS 逐字一致",
      bool(iceberg_server_keys) and not iceberg_differences
      and iceberg_frontend_keys != paimon_frontend_keys,
      "\n".join(iceberg_differences) if iceberg_differences
      else "Iceberg 与 Paimon 的参数字段完全相同——Iceberg 那份大概率是复制过来的")

# ------------------------------------------------------------------ 12. 表格式

print("12. catalog 表格式（创建时区分 Paimon / Iceberg）")

# 这一组的理由：catalog 的表格式是**创建时选定、之后不可改**的一条属性，
# 它同时决定「客户端能用哪些端点」与「控制台能显示哪些页签」。前端如果在任何一处
# 自己猜（猜候选、猜默认值、猜哪些页面该收敛），错法都很安静：候选少一个 → 建不出
# Iceberg catalog；默认值猜错 → 表单里选 Paimon、建出来是 Iceberg；漏收敛一个页面 →
# 对着 Iceberg catalog 打 Paimon 路径，页面上是一串 404，看着像故障而不是「一期不支持」。
# 这四种都不会被编译、类型检查或单元测试拦住。
format_util = read(CATALOG_FORMAT_UTIL)
format_enum = read(CATALOG_FORMAT_ENUM)
detail_view = read(CATALOG_DETAIL_VIEW)
browse_view = read(BROWSE_VIEW)
table_view = read(TABLE_VIEW)

# --- 12.1 取值只有两个，且服务端是唯一权威 ---------------------------------
# 取枚举常量：`public enum CatalogFormat {` 之后到枚举段结束的 `;` 之前。
# 先去掉注释，否则枚举上的 Javadoc 里的全大写词会被当成取值。
enum_body = format_enum.split("public enum CatalogFormat {", 1)[-1].split(";", 1)[0]
enum_body = re.sub(r"/\*.*?\*/", "", enum_body, flags=re.S)
enum_body = re.sub(r"//[^\n]*", "", enum_body)
server_formats = sorted(re.findall(r"\b([A-Z][A-Z_]+)\b", enum_body))

fallback = re.search(r"CATALOG_FORMATS\s*=\s*\[([^\]]*)\]", format_util)
frontend_formats = sorted(re.findall(r"'([A-Z]+)'", fallback.group(1))) if fallback else []

check(f"前端兜底的取值与服务端 CatalogFormat 枚举逐字一致（{server_formats or '未解析出枚举'}）",
      bool(server_formats) and frontend_formats == server_formats,
      f"CatalogFormat.java={server_formats or '未解析出枚举常量'}，"
      f"catalogFormat.js={frontend_formats or '未找到 CATALOG_FORMATS'}")

# 下发的必须是枚举本身，而不是 meta 服务里另写的一份列表——另写一份就会漂移，
# 而漂移的表现是「界面上能选一个服务端不认的格式」，提交时才 400。
check("服务端 meta 下发的 catalogFormats 来自枚举本身",
      "names(CatalogFormat.values())" in meta_service,
      "ConsoleMetaService 的 enums() 需要传 names(CatalogFormat.values())，不能另写一份字面量列表")

check("服务端 meta 下发的 defaultCatalogFormat 也走同一个枚举",
      "CatalogFormat.of(properties.getDefaultCatalogFormat())" in meta_service,
      "默认值要过 CatalogFormat.of() 归一化，前端拿它预选时才不会与服务端的兜底方向不同")

# --- 12.2 新建表单：候选、默认值、提交体 -----------------------------------
start = catalogs_view.find("managementApi.updateCatalog(")
update_call = catalogs_view[start:start + 320] if start >= 0 else ""
create_wiring = {
    "候选来自 meta 的 enums.catalogFormats": "enums?.catalogFormats" in catalogs_view,
    "默认值来自 meta 的 service.defaultCatalogFormat": "service?.defaultCatalogFormat" in catalogs_view,
    # 绑定形式有两种都算过：下拉（`v-model`）与选项卡片（点击赋值）。
    # 效果图用的是卡片，改成卡片时这条断言跟着换，而不是把卡片改回下拉去迁就断言。
    "表单绑定 dialog.catalogFormat": 'v-model="dialog.catalogFormat"' in catalogs_view
        or '@click="dialog.catalogFormat = option.value"' in catalogs_view,
    "提交体带 catalogFormat": "catalogFormat: dialog.catalogFormat" in catalogs_view,
    "编辑不回传它（格式不可变）": start >= 0 and "catalogFormat" not in update_call,
}
check("新建表单从 meta 取候选与默认值、提交时带上 catalogFormat；编辑不回传它",
      all(create_wiring.values()),
      "\n".join(name for name, ok in create_wiring.items() if not ok))

# 选项下方要有一行影响面说明：选 Iceberg 之后客户端只能走 Iceberg 路径、
# 控制台一期不浏览它的表——这些要**在提交前**看到，而不是建完再发现。
check("新建表单在选项下方给出影响面说明（选 Iceberg 之前就能看见）",
      "catalogFormatNote(dialog.catalogFormat)" in catalogs_view
      and "isIcebergCatalog(dialog.catalogFormat)" in catalogs_view,
      "CatalogsView 应在格式选择下方渲染 catalogFormatNote，并在选中 Iceberg 时给出额外提示")

# --- 12.3 列表：徽标 + 筛选 ------------------------------------------------
check("列表有格式徽标与格式筛选",
      "catalogFormatTagType(row)" in catalogs_view
      and "catalogFormatLabel(row)" in catalogs_view
      and "FORMAT_FILTERS" in catalogs_view
      and 'v-model="formatFilter"' in catalogs_view,
      "CatalogsView 的清单需要「表格式」列（徽标）与一个格式筛选器")

# 筛选下推到服务端之后，「同一个归属」变成两处各一次回落：徽标走 catalogFormatOf()
# （缺列按 Paimon），服务端 formatFiltered() 也必须按同一个方向回落。两边不一致时
# 的现象是「筛 Paimon 出来一行，徽标却写着 Iceberg」——或者更常见的：那一行根本不出现。
check("格式筛选与徽标走同一个归属（缺列按 Paimon 的回落两边一致）",
      "catalogFormatTagType(row)" in catalogs_view
      and "format: formatFilter.value" in catalogs_view
      and "formatFiltered" in read(os.path.join(SERVER_JAVA, "web/ManagementCatalogController.java")),
      "列表徽标用 catalogFormatOf、筛选参数随请求下推、服务端按同一回落过滤，三者缺一")

# --- 12.4 三个页面按格式伸缩 ----------------------------------------------
# 判据不是「模板里藏了内容」，而是**loader 里挡了请求**：只在模板上做 v-if
# 仍然会把注定被拒的请求发出去，页面上留下 404 日志，看起来像故障。
#
# 目录浏览这一条在 Iceberg 支持落地后变了形状：不再是「整页说明」，而是
# **换一个走 Iceberg 契约的组件**。判据因此要盯两件事——换页这一下真的在，
# 以及 Paimon 的那几个 loader 仍保留守卫（watch / 刷新会绕过模板直接调 load）。
page_wiring = {
    "表详情：loader 里挡掉 Paimon 表端点": "if (icebergCatalog.value)" in table_view,
    "表详情：模板给出说明而不是空页": "!icebergCatalog" in table_view,
    "目录浏览：Iceberg catalog 换成 Iceberg 协议面的浏览页":
        '<IcebergBrowseView v-if="icebergCatalog" />' in browse_view,
    "目录浏览：Paimon 的库列表 loader 在 Iceberg 下仍挡请求": "if (icebergCatalog.value) return []" in browse_view,
    "目录浏览：Paimon 的四个对象列表 loader 一并挡掉": "|| icebergCatalog.value) {" in browse_view,
    "catalog 详情：「配置」页签两种格式都在，table-default 编辑器对两者都开放":
        'name="config"' in detail_view and 'v-if="!isIceberg"' not in detail_view,
}
check("三个页面都按 catalog 格式伸缩（且在 loader 里挡请求，不只是藏界面）",
      all(page_wiring.values()),
      "\n".join(name for name, ok in page_wiring.items() if not ok))

# --- 12.4.1 「配置」页签的内容（两种格式同一套） ----------------------------
# 这个页签曾经只在 Paimon 侧出现，后来改成「两边都有但内容不同」：Paimon 编辑
# `table-default.*`，Iceberg 只读展示 `/v1/config`。现在两边统一成同一套设计——
# 都能编辑 `table-default.*`（服务端在建表时合并，表级同名优先），Iceberg 再**另附**
# 一份只读的连接配置（defaults / overrides），因为那是连接参数，与表属性不是一回事。
check("两种格式的配置页都编辑 table-default.*（同一套设计）",
      "表默认参数（table-default.*）" in detail_view
      and "isIceberg ? 'Iceberg' : 'Paimon'" in detail_view,
      "配置页应在两种格式下给出同一个默认参数编辑器，标题按格式换名")

check("Iceberg catalog 的配置页另附 /v1/config 的 defaults 与 overrides（只读）",
      "客户端连接配置" in detail_view
      and "configDefaults" in detail_view
      and "configOverrides" in detail_view,
      "Iceberg 侧应保留 defaults / overrides 的只读展示：那是连接参数，不是表属性")

# 「其它属性」曾经在 Paimon 配置页重复列一遍（全量替换时要回填的那些键）：
# 概览页的「属性」与 JSON 块已经完整展示同一份数据，这一列既占地方又容易过期。
check("Paimon 配置页不再重复展示「其它属性」（概览页已有完整 properties）",
      "其它属性" not in detail_view and "otherProperties" not in detail_view,
      "其余属性请在概览页展示；配置页只留 table-default.* 的编辑")

# 深链接进来时 catalog 列表可能还没到，格式就无从判断——那时会默认走 Paimon 路径。
# 两页都要先等一次列表（`ensureCatalogs`），否则「直接打开链接」这条路径上是猜的。
check("两页都先确认格式再发请求（深链接进来时不猜）",
      "session.state.catalogsLoaded" in table_view
      and "session.state.catalogsLoaded" in browse_view,
      "TableView 与 BrowseView 都应在第一个请求之前确保 catalog 列表已加载")

# --- 12.5 能力清单以服务端下发的为准 --------------------------------------
# Iceberg 侧的 endpoints 是服务端**下发**给客户端的原文，客户端拿它做等值比较。
# 前端另抄一份会出现「界面显示的能力与实际生效的不一致」——而这一块的全部价值
# 就是排障时不用去读文档。因此详情页必须是「读回来再显示」。
check("catalog 详情页的能力清单读服务端的 /v1/config，而不是前端抄一份",
      "catalogApi.config(catalogName.value)" in detail_view
      and "endpoints" in detail_view
      and "GET /v1/{prefix}/namespaces" not in detail_view,
      "CatalogDetailView 应读 catalogApi.config() 的 endpoints；端点的字面量清单不能写在前端")

check("详情页给出客户端基址与「创建后不可更改」这两条可核对的说明",
      "clientBase" in detail_view and "创建后不可更改" in detail_view,
      "CatalogDetailView 的概览应显示表格式（含不可变说明）与 /v1/{prefix} 基址")

# --- 12.6 「不声明支持」的条数与唯一的来源对齐 ------------------------------
# 这一栏只能写在前端：服务端不会下发「我不支持什么」，没有就是没有。
# 于是界面上那个数字成了唯一会随实现漂移的地方——每多实现一个端点，它就该少一条。
# 因此把它与 `verify-iceberg-endpoints.py` 里那份**字面量**清单的条数比一遍：
# 那份清单本身已经被「规格 − 声明」双向校验过（见该脚本）。
not_declared_total = re.search(r"NOT_DECLARED_TOTAL\s*=\s*(\d+)", detail_view)
iceberg_script = read(os.path.join(ROOT, "scripts/verify-iceberg-endpoints.py"))
not_implemented_block = iceberg_script.split("NOT_IMPLEMENTED = [", 1)[-1].split("]", 1)[0]
not_implemented = re.findall(r'"([A-Z]+ /v1[^"]+)"', not_implemented_block)
check("详情页「不声明支持」的条数与 verify-iceberg-endpoints.py 的 NOT_IMPLEMENTED 一致"
      f"（{len(not_implemented)} 条）",
      not_declared_total is not None and int(not_declared_total.group(1)) == len(not_implemented),
      f"CatalogDetailView 写的是 {not_declared_total.group(1) if not_declared_total else '未找到 NOT_DECLARED_TOTAL'}，"
      f"verify-iceberg-endpoints.py 登记了 {len(not_implemented)} 条")

# --- 12.7 权限页：候选来源按格式分派 --------------------------------------
# 「权限管理」页的中间那一层在两种格式里名字与来源都不同：Paimon 是扁平库名
# （`databases/**`），Iceberg 是多级 namespace（`namespaces/**`，且端点只回直接子级，
# 要按层递归）。选错来源的表现是**空下拉**或一串 404——而授权本身照旧提交得进去
# （服务端不校验库名是否存在），于是落库的是一条永远匹配不上的权限。
# 这类错编译、类型检查都不会报：`catalogApi.listDatabases` 与 `icebergApi.listNamespaces`
# 在类型上都是「返回一个列表」。
permission_view = read(PERMISSIONS_VIEW)
permission_wiring = {
    "格式判断走统一的归属函数":
        "isIcebergCatalog(session.catalogByName(catalogName.value))" in permission_view,
    "Iceberg：namespace 候选按层递归取（listAllNamespaces）":
        "listAllNamespaces(" in permission_view,
    "Iceberg：表 / 视图候选走 icebergApi，并按 identifiers 取":
        "icebergApi.listTables(" in permission_view
        and "icebergApi.listViews(" in permission_view
        and "identifiers" in permission_view,
    "Paimon：仍走 databases 与 tables / views":
        "catalogApi.listDatabases(" in permission_view,
    "文案随格式切换（Namespace / Database）":
        "'Namespace'" in permission_view and "scopeTerm" in permission_view,
    "先确认格式再取候选（深链接进来时不猜）":
        "ensureCatalogs" in permission_view,
}
check("权限页按 catalog 格式取候选（Iceberg 走 namespaces/**，Paimon 走 databases/**）",
      all(permission_wiring.values()),
      "\n".join(name for name, ok in permission_wiring.items() if not ok))

# ------------------------------------------------------------------ 13. Iceberg 目录浏览

print()
print("13. Iceberg 目录浏览（namespace / table / view 走 Iceberg 契约）")

# 起因：控制台原先对 Iceberg catalog 是「整页说明、不发请求」。现在这一页真的
# 能读了，于是多出一类新的静默错误——**用 Paimon 的形状去读 Iceberg**：
#
#   · 路径对上但参数名错（`maxResults` 而不是 `pageSize`）→ 服务端不报错，
#     只是按默认页大小回一页，列表看起来「就是只有 50 条」；
#   · namespace 段用点号拼 → 层级名含点时被切错，也是不报错；
#   · 表列表想显示 location / 时间 → 只能逐表 load，N 次请求换一屏数据，
#     而 `identifiers` 里本来就没有这些字段。
#
# 这一组只钉「契约形状」，不钉像素。
iceberg_api = read(os.path.join(CONSOLE_DIR, "src/api/iceberg.js"))
iceberg_browse = read(os.path.join(CONSOLE_DIR, "src/views/IcebergBrowseView.vue"))

# 第 1 组比的是「并集」，一条 Iceberg 端点若碰巧与 Paimon 同路径也可能过关
# （rename 就是同路径的那一条）。这里只取 iceberg* 端点，逐条对 Iceberg 规格。
iceberg_entry = re.compile(
    r"^\s*(iceberg\w+):\s*\{\s*method:\s*'([A-Z]+)',\s*path:\s*'([^']+)'\s*\},?\s*$", re.M)
iceberg_endpoints = {m.group(1): (m.group(2), m.group(3)) for m in iceberg_entry.finditer(endpoints_source)}
iceberg_spec_routes = {
    (method.upper(), path)
    for path, operations in iceberg_paths.items()
    for method in operations
}
not_in_iceberg_spec = sorted(
    name for name, route in iceberg_endpoints.items() if route not in iceberg_spec_routes)

check(f"endpoints.js 里 {len(iceberg_endpoints)} 条 iceberg* 端点都能在 Iceberg 规格中找到",
      iceberg_endpoints and not not_in_iceberg_spec,
      "\n".join(f"{name} → {iceberg_endpoints[name][0]} {iceberg_endpoints[name][1]}"
                for name in not_in_iceberg_spec))

check("Iceberg 浏览页只调 icebergApi（不混用 Paimon 的 catalogApi）",
      "icebergApi" in iceberg_browse and "catalogApi" not in iceberg_browse,
      "IcebergBrowseView 里出现了 catalogApi 的引用，两套契约会串")

# 一条断言钉住「路径分隔符」：0x1F 是服务端的存储口径，点号只是显示口径。
check("namespace 路径段用 0x1F 拼，不是点号",
      "NAMESPACE_SEPARATOR = '\\u001F'" in iceberg_api
      and "join(NAMESPACE_SEPARATOR)" in iceberg_api
      and "namespaceLabel" in iceberg_api,
      "api/iceberg.js 应导出 NAMESPACE_SEPARATOR（\\u001F）与 namespacePath/namespaceLabel 两个方向的转换")

# 分页参数名是这一组最容易错、又最不会报错的一条。
# 判据看的是**查询键**（`maxResults:`），不是这个词出现过没有：注释里提一句
# 「Paimon 面叫 maxResults」是在解释差异，那正是该留下的东西。
check("列表请求用 Iceberg 的 pageSize（不是 Paimon 的 maxResults）",
      "pageSize" in iceberg_api and "maxResults:" not in iceberg_api
      and "next-page-token" in iceberg_api,
      "api/iceberg.js 里出现了 maxResults 这个查询键，那是 Paimon 面的参数名")

# 表列表只拿得到 identifiers：控制台不能假装列表里有 location / 时间。
# loadTable 属于表详情页（IcebergTableView），浏览页里出现它就是又在逐表拼列。
iceberg_table_view = read(os.path.join(CONSOLE_DIR, "src/views/IcebergTableView.vue"))
check("表、视图、函数列表都按 identifiers 取，逐表 load 只发生在表详情页",
      "payload?.identifiers" in iceberg_browse
      and all(f"icebergApi.list{name}(" in iceberg_browse
              for name in ("Tables", "Views", "Functions"))
      and "icebergApi.loadTable" not in iceberg_browse
      and "icebergApi.loadTable" in iceberg_table_view,
      "三个列表都应以 identifiers 为字段；loadTable 应只在 IcebergTableView 里按需调用")

# 建表报文是 Iceberg 形状：schema-id / required / source-id / field-id。
# 这四项任何一项写成 Paimon 的样子，服务端会用 SchemaParser 报一句难懂的错。
check("Iceberg 建表体是 Iceberg 形状（schema-id / required / partition-spec / source-id）",
      all(token in iceberg_browse for token in ("'schema-id'", "required:", "'partition-spec'", "'source-id'", "'field-id'")),
      "IcebergBrowseView 的建表表单应拼出 Iceberg 的 schema 与 partition-spec，而不是 Paimon 的 fields/partitionKeys")

check("Iceberg 浏览页不出现 Paimon 的路径与参数（/databases）",
      "/databases" not in iceberg_browse and "/databases" not in iceberg_api,
      "Iceberg 侧的路径是 /namespaces/**")

# 删命名空间的入口已按需求移除（2026-10-20）：Iceberg 的 drop namespace 不是级联
# 删除（非空回 409），误触成本高；管理动作收敛到「新建命名空间」。API 层的
# dropNamespace 保留——服务端端点还在，API 层照旧镜像它。
check("Iceberg 浏览页不再提供删除命名空间的入口",
      "删除命名空间" not in iceberg_browse and "confirmDropNamespace" not in iceberg_browse,
      "按钮已按需求移除，别把它加回来；确要恢复时先改这条断言与文档")

# 两个浏览页共用一套骨架样式：搬回某个组件的 scoped 里，另一页会塌。
index_css = read(os.path.join(CONSOLE_DIR, "src/styles/index.css"))
check("左列表 + 右面板的骨架样式在全局，两个浏览页共用一份",
      ".pc-browse-body" in index_css and ".pc-db-item" in index_css
      and ".pc-browse-body" not in read(BROWSE_VIEW).split("<style")[1],
      "styles/index.css 应有 .pc-browse-body；BrowseView 的 scoped 里不该再有一份")

# --- 13.2 Iceberg 表详情页 ---------------------------------------------------
# 起因：表详情从「整页说明、不发请求」升级为真实可读的独立页（IcebergTableView）。
# 它与 Paimon 版 TableView 平行，最容易犯的错还是「用 Paimon 的形状去读 Iceberg」，
# 外加两条它特有的：
#
#   · 文件列表去调 Paimon 的 tableId 端点 —— LoadTableResult 里没有内部 id，
#     那个端点也只认 Paimon 表元数据的 path，正确做法是 catalog 级端点
#     （根 = warehouse）+ metadata.location 减去 warehouse 前缀；
#   · 存储优化页签自己调优化端点、或忘了传 format —— 面板按 format 取参数集
#     （Iceberg 的 retain-last vs Paimon 的 retain-min），漏传的表现是表单显示
#     Paimon 的参数、保存时 400，而不是编译错误。
router_source = read(ROUTER)

check("Iceberg 表详情页有独立路由（icebergTable），与 Paimon 版平行",
      "'icebergTable'" in router_source and "IcebergTableView" in router_source,
      "router/index.js 应声明 /iceberg-tables/... 路由并指向 IcebergTableView")

check("Iceberg 表详情页只调 icebergApi（不混用 Paimon 的 catalogApi）",
      "icebergApi" in iceberg_table_view and "catalogApi" not in iceberg_table_view,
      "IcebergTableView 里出现了 catalogApi 的引用，两套契约会串")

check("Iceberg 表详情页不出现 Paimon 的路径（/databases）",
      "/databases" not in iceberg_table_view,
      "Iceberg 侧的路径是 /namespaces/**")

check("表详情的文件列表走 catalog 级文件端点（根 = warehouse），不用 Paimon 的 tableId 端点",
      "listCatalogFiles" in iceberg_table_view and "listTableFiles" not in iceberg_table_view,
      "LoadTableResult 里没有内部 id；文件浏览应由 metadata.location 减去 warehouse 前缀后调 listCatalogFiles")

check("快照读 metadata.snapshots，并处理 current-snapshot-id=-1 的无快照哨兵",
      "'current-snapshot-id'" in iceberg_table_view and "> 0" in iceberg_table_view
      and "无快照" in iceberg_table_view,
      "建表后还没提交时 current-snapshot-id 是 -1，照原样显示会让人去找编号 -1 的快照")

check("存储优化页签挂的是共用面板，并显式传入 ICEBERG（取 Iceberg 那套参数集）",
      "TableOptimizationPanel" in iceberg_table_view
      and 'format="ICEBERG"' in iceberg_table_view
      # 面板按 props 自己拼请求，页签不该自己再调一次策略/执行端点——那会变成
      # 两份「什么时候加载、加载哪个」的逻辑，且第二份迟早漏掉 format
      and "tableOptimizationPolicy" not in iceberg_table_view
      and "runTableOptimization" not in iceberg_table_view,
      "Iceberg 表详情的「存储优化」应复用 TableOptimizationPanel 并传 format=\"ICEBERG\"；"
      "参数键与执行语句由 format 分派，页签自己调端点会绕过这条分派")

check("建表表单的 format-version 提供 1 / 2 / 3 三个选项",
      all(f'value="{version}"' in iceberg_browse for version in ("1", "2", "3")),
      "iceberg-core 1.11 支持 v3（实测 properties['format-version']='3' 落出 format-version: 3），"
      "表单只给 1 / 2 是过时的")

# ------------------------------------------------------------------ 14. 存储字段分层

print()
print("14. 存储字段分层（基本 / 高级两张表，折叠不改提交内容）")

# 起因：S3 有 12 个字段，其中 10 个是「绝大多数人不用填」的（跨账户角色、STS、KMS）。
# 把它们收进「存储高级参数」折叠区，看起来只是模板上的事，实际有两个静默风险：
#
#   ① 拆表时漏掉一个字段 —— 不报错，只是那个字段从此保存不进去。用户会以为
#      「服务端不认 roleArn 了」而去查 IAM，而真实原因是在前端被丢了。
#   ② 顺手让折叠状态参与提交 —— 展开看一眼再收起来，报文里就多了几个 false。
#      这种差异只有 diff 报文才看得见，所以这里把「build() 不读折叠状态」变成断言。
#
# 判据尽量对着**服务端 record**，而不是在前端再抄一份字段名单：
# 抄了就会漂移，而漂移的表现是「两边都错得一致」，校验静默通过。
field_source = read(STORAGE_CONFIG_FIELDS)


def vue_string_list(const_name):
    """取 `const NAME = ['a', 'b']` 这类字面量的字符串项。"""
    found = re.search(rf"{const_name} = \[([^\]]*)\]", field_source)
    return re.findall(r"'([^']+)'", found.group(1)) if found else []


def vue_field_table(const_name):
    """取「存储类型 -> 字段 key 列表」的表。

    不引入 JS 解析器：这两张表是纯字面量、形状固定（`TYPE: [ { key: 'x' }, ... ]`），
    正则足够；万一它被改成别的形状（例如按类型拼装），这里会解析成空表，
    校验随即失败——那正是该人工看一遍的时候。
    """
    block = re.search(rf"const {const_name} = \{{(.*?)\n\}}\n", field_source, re.S)
    if not block:
        return None
    table = {}
    for entry in re.finditer(r"\n  (\w+): \[(.*?)\]", block.group(1), re.S):
        table[entry.group(1)] = re.findall(r"key: '([^']+)'", entry.group(2))
    return table


basic_table = vue_field_table("BASIC_FIELDS")
advanced_table = vue_field_table("ADVANCED_FIELDS")

# 共有字段与静态凭据不走这两张表（它们由模板单独渲染、由 build() 单独处理），
# 因此比对时要从服务端 record 里减掉——这两个名单同样从前端读，不另抄。
shared_keys = set(vue_string_list("COMMON_KEYS"))
credential_keys = set(vue_string_list("STATIC_CREDENTIAL_KEYS"))


def dto_form_fields(wire):
    """服务端 record 里「该由这两张表渲染」的字段；找不到对应 record 时返回 None。"""
    shape = shapes.get(by_type.get(wire, ""))
    if shape is None:
        return None
    return set(parameter_names(shape[0])) - shared_keys - credential_keys


# --- 13.1 拆分不丢字段、也不多出服务端没有的字段 -----------------------------
union_issues = []
for wire in sorted(set(basic_table or {}) | set(advanced_table or {})):
    expected = dto_form_fields(wire)
    if expected is None:
        union_issues.append(f"{wire}: StorageDtos.java 里找不到对应的 record")
        continue
    actual = (set((basic_table or {}).get(wire, []))
              | set((advanced_table or {}).get(wire, [])))
    if actual != expected:
        union_issues.append(
            f"{wire}: 少了 {sorted(expected - actual) or '无'}，"
            f"多了 {sorted(actual - expected) or '无'}")

check("两张字段表合并后覆盖服务端 record 的全部专属字段（不丢、不多）",
      basic_table is not None and advanced_table is not None and not union_issues,
      "\n".join(union_issues) or "未解析出 BASIC_FIELDS / ADVANCED_FIELDS")

# --- 13.2 两张表之间不重叠 ---------------------------------------------------
# 重叠的表现是同一个键渲染两次：两个控件的 v-model 指向同一处，
# 改一个另一个跟着变，看起来像「输入框卡住了」。
overlaps = []
for wire in (basic_table or {}):
    both = set(basic_table.get(wire, [])) & set((advanced_table or {}).get(wire, []))
    if both:
        overlaps.append(f"{wire}: {sorted(both)}")

check("同一类型下基本表与高级表不重叠（重叠会让同一个键渲染两次）",
      basic_table is not None and advanced_table is not None and not overlaps,
      "\n".join(overlaps) or "未解析出 BASIC_FIELDS / ADVANCED_FIELDS")

# --- 13.3 build() 不读折叠状态 -----------------------------------------------
# 这是本组最该存在的一条：折叠是纯视觉改动，最常见的事故是顺手在提交逻辑里
# 也加一层「收起就不提交」。一旦加了，表现是**静默改坏配置**而不是报错。
# 同时要求它仍读合并后的 `fields.value`——若改成硬编码列表，13.1 也就形同虚设了。
build_match = re.search(r"function build\(\) \{(.*?)\n\}", field_source, re.S)
build_body = build_match.group(1) if build_match else ""
leaked = [token for token in ("advancedOpen", "advancedFields", "filledAdvanced")
          if token in build_body]
if not build_body:
    build_detail = "未找到 build() 函数体（改名了？本组的所有判据都依赖它）"
elif leaked:
    build_detail = (f"build() 里出现 {leaked}：折叠状态不得参与提交内容，"
                    "否则「展开看一眼再收起」会改变报文")
elif "fields.value" not in build_body:
    build_detail = "build() 不再读合并后的 fields.value，可能改成了硬编码字段列表"
else:
    build_detail = ""

check("build() 不读取折叠状态、仍读合并后的字段视图",
      bool(build_body) and not leaked and "fields.value" in build_body,
      build_detail)

# --- 13.4 FILE 没有高级字段、折叠区按「有没有」决定是否出现 -------------------
# 判据写成 advancedFields.length 而不是「类型 === 'S3'」：后者在服务端给
# 某个类型加字段时会静默漏掉，而那正是最不容易被发现的一类遗漏。
check("FILE 没有高级字段（折叠区整块不出现）",
      advanced_table is not None and advanced_table.get("FILE") == [],
      f"ADVANCED_FIELDS.FILE = {advanced_table.get('FILE') if advanced_table else '未解析'}")

check("折叠区按「当前类型有没有高级字段」出现，而不是硬编码成某个类型",
      'v-if="advancedFields.length"' in field_source,
      '模板里应写 v-if="advancedFields.length"')

# --- 13.5 默认收起，且收起态说得出「可以不管它」 ------------------------------
# 「默认收起」是需求本身；而「全部留空即用服务端默认值」这句话是折叠能被信任的
# 前提——没有它，用户会以为收起区里有必填项而被迫展开，折叠就白做了。
check("折叠区默认收起（初始值 false，不由是否有值反推）",
      bool(re.search(r"advancedOpen = ref\(false\)", field_source)),
      "StorageConfigFields 里应写 advancedOpen = ref(false)")

check("收起态给出计数徽标与「可以不管它」的说明",
      "advancedBadge" in field_source and "全部留空即用服务端默认值" in field_source,
      "折叠头需要 计数（advancedBadge）+ 一句「全部留空即用服务端默认值」")

# ------------------------------------------------------------------ 15. 事件页契约

print()
print("15. 事件页（远程分页 + 清单覆盖服务端枚举）")

# 起因：事件页从「游标增量」改成「远程分页」之后，最容易退化的两件事是
# 「页码没下推」（每次翻页都回同一批，界面表现为点了下一页内容没变）和
# 「类型筛选回到本地」（本地只筛得到当页，筛出空页并不等于窗口里没有）。
# 事件类型清单与目标类型显示名同样对着**服务端枚举/常量**比对：前端另抄一份
# 名单，服务端加类型时的表现是新事件显示成 wire 原文——静默，且只有真发生
# 那种事件才看得见。

events_view = read(os.path.join(CONSOLE_DIR, "src/views/EventsView.vue"))
event_type_source = read(os.path.join(CORE_JAVA, "event/CatalogEventType.java"))
event_category_source = read(os.path.join(CORE_JAVA, "event/CatalogEventCategory.java"))
catalog_event_source = read(os.path.join(CORE_JAVA, "event/CatalogEvent.java"))

check("endpoints.js 登记了 listEvents 且路径与端点一致",
      endpoints.get("listEvents") == ("GET", "/api/console/v1/events"),
      f"实际是 {endpoints.get('listEvents')}")

check("EventsView 走远程分页（页码下推、total 取自响应）",
      "listEvents" in events_view and "useRemotePaging" in events_view
      and "payload.page?.total" in events_view,
      "增量游标只能往前看；页码模式才能往回翻历史，总数也才有出处")

check("事件类型筛选也下推服务端（换类型回到第一页重取）",
      "type: filter.type" in events_view and "reload()" in events_view,
      "类型只在前端筛时只筛得到当页；筛出空页会被读成「窗口里没有这种事件」")

# 枚举里的 wire 值写在构造参数第二位上：`X(CatalogEventCategory.TABLE, "table.created")`，
# 个别常量换行书写，所以匹配要允许空白跨越换行
wire_types = re.findall(r'CatalogEventCategory\.[A-Z_]+,\s*"([a-z._]+)"', event_type_source)
check("事件类型清单覆盖 CatalogEventType 的全部 wire 值",
      bool(wire_types) and all(wire in events_view for wire in wire_types),
      "前端 TYPES 少了服务端新增的类型时，那种事件会显示成 wire 原文")

# 类别是监听器订阅的粒度，也是事件页的筛选项：前端少一个类别 = 那个筛选项永远筛不出东西
categories = re.findall(r"^ {4}([A-Z][A-Z_]{2,})\s*,?\s*$", event_category_source, re.M)
check("事件类别清单覆盖 CatalogEventCategory 的全部取值",
      bool(categories) and all(f"'{category}'" in events_view for category in categories),
      "前端 CATEGORIES 少了服务端新增的类别时，该类事件只能靠类型筛")

check("类别筛选走服务端参数（category 随页码一起发出）",
      "category: filter.category" in events_view,
      "类别是服务端过滤条件；只在前端筛会让「换类别」仍沿用旧游标的整页数据")

check("目标类型显示名覆盖 CatalogEvent 的全部 TARGET_* 常量",
      all(f"'{value}'" in events_view
          for value in re.findall(r'TARGET_\w+ = "(\w+)"', catalog_event_source)),
      "前端 TARGET_LABELS 漏了常量时，那一类目标会显示成原文")

check("事件页有独立路由（/events，进侧边菜单）",
      "'/events'" in router_source and "EventsView" in router_source,
      "router/index.js 应声明 /events 路由并指向 EventsView")

# ------------------------------------------------------------------ 16. 提示图标

print()
print("16. 提示图标（鼠标移上去就显示，不延迟、不用点）")

# 问号图标是 14px 的小东西，用户能给它的注意力就是「移过去扫一眼」。
# 两个坑都真实发生过：
#   1. `show-after=100` 的延迟——快速划过时什么都没出现，观感就是「移到图标上没反应」；
#   2. 图标本身只有 14px，鼠标偏一两像素就落在容器上，而点击时整块区域都算命中，
#      于是表现成「移上去没动静、点一下才弹」。
# 前者在模板里断言（trigger 显式 hover + show-after 为 0），后者在样式里断言
# （图标带 padding，把可悬停范围撑到 20px）。
tip_note_source = read(os.path.join(CONSOLE_DIR, "src/components/TipNote.vue"))
field_label_source = read(os.path.join(CONSOLE_DIR, "src/components/FieldLabel.vue"))
console_styles = read(os.path.join(CONSOLE_DIR, "src/styles/index.css"))

hover_broken = [
    name for name, source in (("TipNote", tip_note_source), ("FieldLabel", field_label_source))
    if 'trigger="hover"' not in source or ':show-after="0"' not in source
]
check(f"两个提示组件都是悬停即显示（trigger=\"hover\" + show-after 0）",
      not hover_broken,
      "以下组件仍带显示延迟、或不是悬停触发: " + "、".join(hover_broken))

check("问号图标的命中区比图标本身大（鼠标差一两个像素也要能触发）",
      re.search(r"\.pc-field-label-icon\s*\{[^}]*?padding:", console_styles) is not None,
      "styles/index.css 里 .pc-tip-icon / .pc-field-label-icon 应带 padding，"
      "把 14px 图标的可悬停范围撑大一圈")

# ------------------------------------------------------------------ 17. 目录浏览的入口

print()
print("17. 目录浏览的入口（工具条常驻、页头不重复、Iceberg 视图可建）")

# 起因：`ResourceState` 命中空态时只渲染 `el-empty`，**默认插槽整个不渲染**。
# 三类对象的工具条原先都写在插槽里，于是「这个库还是空的」这一刻，
# 「新建视图 / 新建函数」按钮跟着表格一起消失了——而那正是最需要它的时刻。
# 表页签当时没暴露，是因为页头另有一颗「新建表」；代价是同一个动作两个入口，
# 而 Iceberg 页连这点运气都没有：页头的按钮与工具条里的重复，空态时仍然只剩页头那颗。
# 三条断言都是「字符串位置」级别的，能拦住把工具条挪回插槽、或把按钮塞回页头。
browse_src = read(BROWSE_VIEW)
iceberg_browse_src = read(os.path.join(CONSOLE_DIR, "src/views/IcebergBrowseView.vue"))
endpoints_source = read(os.path.join(CONSOLE_DIR, "src/api/endpoints.js"))
iceberg_view_controller = read(os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-iceberg",
    "src/main/java/io/github/melin/lakehouse/rest/iceberg/web/IcebergViewController.java"))
tools_rows_broken = []
for label, source in (("BrowseView", browse_src), ("IcebergBrowseView", iceberg_browse_src)):
    positions = [m.start() for m in re.finditer(r'class="pc-tools-row"', source)]
    state_at = source.index("<ResourceState")
    if len(positions) != 3 or any(pos > state_at for pos in positions):
        tools_rows_broken.append(f"{label}: {len(positions)} 处工具条，"
                                 f"其中 {sum(pos > state_at for pos in positions)} 处在 ResourceState 之后")
check("两个浏览页的三条工具条都在 ResourceState 之外（空态下新建入口仍在）",
      not tools_rows_broken,
      "\n".join(tools_rows_broken))


def page_header_block(source):
    start = source.index("<PageHeader")
    return source[start:source.index("</PageHeader>", start)]


semantic_left = "语义视图" in browse_src
header_extra = [label for label, source in (("BrowseView", browse_src),
                                            ("IcebergBrowseView", iceberg_browse_src))
                if "openCreateTable" in page_header_block(source)]
check("目录浏览没有「语义视图」页签，页头也不再重复放「新建表」",
      not semantic_left and not header_extra,
      ("BrowseView 里仍有「语义视图」文案" if semantic_left else "")
      + ("；页头仍带新建表: " + "、".join(header_extra) if header_extra else ""))

# Iceberg 建视图：前端有入口、端点表登记了它、服务端控制器真有那条 POST。
# 三处任何一处缺失，按钮都会变成 404 或静默不发请求——静态检查能提前抓住。
create_view_path = re.search(r'icebergCreateView: \{ method: .POST., path: .([^\']+). \}',
                             endpoints_source)
# 控制器的路径写在两处：类上的 `@RequestMapping("/v1/{prefix}")` 与方法上的
# `@PostMapping("/namespaces/{namespace}/views")`。端点表里的是拼起来的全路径，
# 因此比的是「去掉类前缀之后的那一段」——直接比全路径永远不相等。
class_prefix = re.search(r'@RequestMapping\("([^"]+)"\)', iceberg_view_controller)
create_view_suffix = (
    create_view_path.group(1)[len(class_prefix.group(1)):]
    if create_view_path and class_prefix and create_view_path.group(1).startswith(class_prefix.group(1))
    else None
)
create_view_mapped = bool(create_view_suffix) and (
    f'@PostMapping("{create_view_suffix}")' in iceberg_view_controller)
check("Iceberg 视图页签有创建入口，且端点路径与控制器一致",
      "openCreateView" in iceberg_browse_src and create_view_mapped,
      "缺: " + "、".join([
          item for item, ok in (
              ("IcebergBrowseView 里的新建视图按钮", "openCreateView" in iceberg_browse_src),
              ("endpoints.js 的 icebergCreateView", create_view_path is not None),
              ("IcebergViewController 的 POST 映射", create_view_mapped),
          ) if not ok
      ]))

# ------------------------------------------------------------------ 18. Catalog 详情·存储

# 这一页两种格式读法不同，而「不同」只允许出现在一个文件里（`api/storageHealth.js`）。
# 页面上混进 Paimon 的 `databases/**` 时，Iceberg catalog 下的症状是一串 404——不白屏，
# 很容易漏看。断言分别钉：页签存在且两格式共用组件、数据来源只有那几个端点、
# 平均是加权的、面板本身不碰写接口，以及三个交互的钉子（Paimon 表开关、明细分页、两卡对齐）。
#
# 第 2 条断言比对的是**调用集合**而不是文本包含：源码注释里会出现 `t.files`、`const files`
# 这类词，裸字符串比对既会漏也会误伤（写这一组时实测过）。

print()
print("18. Catalog 详情·存储（两种格式同一套读法，来源与口径钉死）")

CATALOG_DETAIL = os.path.join(CONSOLE_DIR, "src/views/CatalogDetailView.vue")
STORAGE_PANEL = os.path.join(CONSOLE_DIR, "src/components/CatalogStoragePanel.vue")
STORAGE_API = os.path.join(CONSOLE_DIR, "src/api/storageHealth.js")
STORAGE_UTIL = os.path.join(CONSOLE_DIR, "src/utils/storageHealth.js")
SERVICE_JAVA = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/java",
    "io/github/melin/lakehouse/rest/service/ManagementCatalogService.java")
REPO_JAVA = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java",
    "io/github/melin/lakehouse/rest/domain/repo/TableSnapshotRepository.java")

catalog_detail_src = read(CATALOG_DETAIL)
storage_panel_src = read(STORAGE_PANEL)
storage_api_src = read(STORAGE_API)
storage_util_src = read(STORAGE_UTIL)

check("详情页有「存储」页签，两种格式传同一个组件（格式只作参数）",
      'name="storage"' in catalog_detail_src
      and "CatalogStoragePanel" in catalog_detail_src
      and "isIceberg ? 'ICEBERG' : 'PAIMON'" in catalog_detail_src,
      "缺: 页签 / 组件引用 / 格式参数之一")

# 数据源是管理面两个聚合端点：汇总（table-stats，SQL 聚合）+ 逐表明细（table-stats/tables，
# 远程分页）。采集文件里不允许再出现协议面的逐表调用——非快照管理的表
# （Format / Object / Lance）尤其不该被问到快照端点。
mgmt_calls = set(re.findall(r'managementApi\.(\w+)', storage_api_src))
check("存储页读数走管理面聚合端点（汇总 + 远程分页明细），不再逐表调 snapshot / loadTable",
      mgmt_calls == {"getCatalogTableStats", "listCatalogTableRows"}
      and "catalogApi" not in storage_api_src and "icebergApi" not in storage_api_src,
      f"managementApi 调用: {sorted(mgmt_calls)}（应为 getCatalogTableStats + listCatalogTableRows）；"
      "采集文件里不得残留 catalogApi / icebergApi 的逐表调用")

# 汇总口径已下沉到服务端 SQL：加权平均在 ManagementCatalogService，
# 小文件判据（表级平均 < 阈值）在聚合 SQL 里——前端只剩行级派生值。
service_src = read(SERVICE_JAVA)
repo_src = read(REPO_JAVA)
check("平均文件大小是加权的、小文件是表级平均判据（口径在服务端聚合里）",
      "(double) size / filesWithSize" in service_src
      and "s.fileSizeInBytes / s.fileCount < :smallThreshold" in repo_src,
      "加权口径被改成了「各表平均值的平均」——1 张 10 万文件的大表会与 1000 张单文件表等权")

check("存储页只读：面板不直接调协议面或管理面（写动作留在表页的「存储优化」）",
      "catalogApi" not in storage_panel_src and "icebergApi" not in storage_panel_src,
      "面板里出现了直接的 API 调用；取数应当只经 api/storageHealth.js")

# 「只统计 Paimon 表」由服务端按 `tableType` 过滤（onlyPaimon 参数）：
# Format / Object / Lance 表没有快照，混进来只会把「有数据的表」的分母撑大。
check("Paimon 侧有「只统计 Paimon 表」开关，onlyPaimon 传给汇总与明细两个端点",
      "只统计 Paimon 表" in storage_panel_src
      and "onlyPaimon: props.format === 'PAIMON' && onlyPaimon.value" in storage_panel_src
      and "onlyPaimon" in storage_api_src,
      "缺: 面板开关 / onlyPaimon 参数传递之一")

# 逐表明细是**远程分页**：打开页面不拉任何明细，选中了库才按页取数；
# 分页 / 关键字 / 排序全部下推 SQL。排序仍用 sortable="custom"——排序键要传给服务端，
# el-table 自带排序在分页下只排当前页。
check("逐表明细远程分页：选库才请求、TablePager 用服务端 total、排序 custom 下推",
      "选择一个库，按需加载" in storage_panel_src
      and '<TablePager' in storage_panel_src and ':total="detail.total"' in storage_panel_src
      and "fetchDetail" in storage_panel_src and "onPageChange" in storage_panel_src
      and 'sortable="custom"' in storage_panel_src
      and "database" in storage_api_src and "pageSize" in storage_api_src,
      "缺: 按需加载提示 / 远程 total / fetchDetail / 自定义排序 / 服务端页码参数之一")

check("按库/Top10 两卡在栅格并排且不吃堆叠边距（等高、顶部对齐）",
      ".pc-sto-grid > :deep(.pc-card + .pc-card)" in storage_panel_src
      and "margin-top: 0" in storage_panel_src,
      "缺: 栅格内边距回收规则——右卡会被全局堆叠边距推下 16px，顶部错位")

# ------------------------------------------------------------------ 19. 表格远程分页

print()
print("19. 表格远程分页（页码与过滤下推，页面只持有一页）")

# 起因：这批表格原本都是「服务端一次给一批、浏览器自己切片」。它不抛异常，只是把
# 三句话悄悄说错：界面上的「共 N 条」说的是这一批而不是库里；过滤只筛得到当页；
# 翻到第 N 页时上一步的过滤条件还在、结果却已经换了一批。三种错法都只有拿真实
# 数据核对才看得出来，所以在这里用静态校验把「谁在翻页、往哪里翻」钉住。
PAGING_COMPOSABLES = os.path.join(CONSOLE_DIR, "src/composables/index.js")
TABLE_PAGER = os.path.join(CONSOLE_DIR, "src/components/TablePager.vue")
composables_source = read(PAGING_COMPOSABLES)

check("composables 只导出远程分页，不再导出浏览器切片的通用分页器",
      "export function useRemotePaging" in composables_source
      and "export function useLocalPaging" not in composables_source,
      "useLocalPaging 的产物是「共 N 条 = 这一批的条数」，留着就会被再一次用起来")

# 名单是**显式**的：新加一个列表页却忘了接分页时，什么都不会报错，
# 只是它的总数永远等于这一页的条数。要加列表页就得往这里加一行。
REMOTE_PAGED_VIEWS = {
    "CatalogsView.vue": "catalog 列表",
    "PrincipalRolesView.vue": "主体角色列表",
    "PrincipalsView.vue": "主体列表",
    "PermissionsView.vue": "授权清单（服务端折叠后再切页）",
    "OptimizationJobsView.vue": "优化批次列表",
    "EventsView.vue": "事件流",
    "CatalogDetailView.vue": "catalog 文件列表",
    "TableView.vue": "表文件 / 分区列表",
    "IcebergTableView.vue": "Iceberg 表文件列表",
    "BrowseView.vue": "库 / 表 / 视图 / 函数",
    "IcebergBrowseView.vue": "namespace / 表 / 视图 / 函数",
}
missing_paging = [name for name in REMOTE_PAGED_VIEWS
                  if "useRemotePaging" not in read(os.path.join(CONSOLE_DIR, "src/views", name))]
check(f"{len(REMOTE_PAGED_VIEWS)} 个列表视图都接了 useRemotePaging", not missing_paging,
      f"这些视图还没接：{missing_paging}")

# 分页器只有 TablePager 一个。各写一份 el-pagination 会漂出「这页能选 100、那页
# 最大 50」和「这页显示总数、那页不显示」的差异，而它们本该是同一条口径。
raw_pager_files = []
pager_uses = []
for folder in (os.path.join(CONSOLE_DIR, "src/views"), os.path.join(CONSOLE_DIR, "src/components")):
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".vue") or name == "TablePager.vue":
            continue
        source = read(os.path.join(folder, name))
        if "<el-pagination" in source:
            raw_pager_files.append(name)
        for block in re.findall(r"<TablePager\b([^>]*?)/>", source, re.S):
            pager_uses.append((name, block))
check("分页器统一走 TablePager（没有页面自己拼 el-pagination）", not raw_pager_files,
      f"这些文件里还有裸 el-pagination：{raw_pager_files}")

# 存储页的明细曾经在浏览器里把页号编成 pageToken（`base64url("offset:N")`），
# 那是服务端 `Paging` 的私有编解码。抄一份到前端，等于把 token 格式变成前端契约：
# 服务端换编码，这一页会安静地永远停在第一页（它不报错，只是第 2 页等于第 1 页）。
STORAGE_HEALTH_API = os.path.join(CONSOLE_DIR, "src/api/storageHealth.js")
storage_api_source = read(STORAGE_HEALTH_API)
check("存储明细按页码取数（客户端不再自己编码 pageToken）",
      "offsetToken" not in storage_api_source
      and "pageToken" not in storage_api_source
      and "pageSize" in storage_api_source
      and "payload?.page?.total" in storage_api_source,
      "客户端编码 token 意味着 token 格式成了前端契约；页码应当原样下推")

check(f"{len(pager_uses)} 处 TablePager 用法都带了 total / page / page-size",
      bool(pager_uses) and all(
          prop in block for _, block in pager_uses
          for prop in (":total=", ":page=", ":page-size=")),
      "少一个 prop 就退化成「只能看第一页」或「总数显示 0」，而它不会报错")

# 类名撞车：全局样式里的 `.pc-opt`（catalog 表格式的选择卡）曾经和
# `TableOptimizationPanel` 的根节点同名，于是整整一屏「存储优化」被套上了
# 选择卡的边框、内边距与 pointer 光标，hover 时边框还会变蓝；同一族里的
# `.pc-opt-dot` 更隐蔽——面板拿它当分隔符 `·`，全局却把它画成 9px 圆点。
# 现在选择卡改叫 `pc-pick*`，`pc-opt-*` 前缀归组件私有：全局样式里不该再出现它。
GLOBAL_CSS = os.path.join(CONSOLE_DIR, "src/styles/index.css")
global_css_source = read(GLOBAL_CSS)
leaked = sorted(set(re.findall(r"\.pc-opt[\w-]*", global_css_source)))
check("全局样式不占用组件私有的 pc-opt-* 前缀（缩写撞车会让整块面板变形）",
      not leaked,
      f"index.css 里仍有这些选择器：{leaked}；组件私有的类名请换前缀（选择卡用 pc-pick）")

# 操作列钉在右侧。窄屏下列多必然横向滚，操作列若跟着内容滚，「滚到右边才找得到
# 按钮、滚回来又不见了」；这一条只在真机上滚一遍才看得出来，所以静态钉住。
FIXED_MISSING = []
for folder in (os.path.join(CONSOLE_DIR, "src/views"), os.path.join(CONSOLE_DIR, "src/components")):
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".vue"):
            continue
        source = read(os.path.join(folder, name))
        for block in re.findall(r"<el-table-column[^>]*label=\"操作\"[^>]*>", source):
            if 'fixed="right"' not in block:
                FIXED_MISSING.append(f"{name}: {' '.join(block.split())[:70]}")
check("带行内动作的表格把「操作」列钉在右侧（不随横向滚动跑掉）", not FIXED_MISSING,
      f"这些操作列缺 fixed=\"right\"：{FIXED_MISSING}")

# 端点在：每个分页列表端点都要收 page。少了它，前端的翻页请求会被当成「不分页」，
# 结果是每次翻页都回同一批——界面上表现为「点了下一页，内容没变」。
PAGED_CONTROLLERS = [
    (os.path.join(SERVER_JAVA, "web/ManagementCatalogController.java"), "catalog 列表"),
    (os.path.join(SERVER_JAVA, "web/PrincipalController.java"), "主体列表"),
    (os.path.join(SERVER_JAVA, "web/PrincipalRoleController.java"), "主体角色列表"),
    (os.path.join(SERVER_JAVA, "web/PermissionController.java"), "授权清单"),
    (os.path.join(SERVER_JAVA, "web/OptimizationController.java"), "优化批次列表"),
    (os.path.join(SERVER_JAVA, "web/ConsoleEventController.java"), "事件流"),
    (os.path.join(SERVER_JAVA, "web/ConsoleFileController.java"), "文件列表"),
    (os.path.join(ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-paimon/src/main/java"
                        "/io/github/melin/lakehouse/rest/paimon/web/DatabaseController.java"), "库列表"),
    (os.path.join(ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-paimon/src/main/java"
                        "/io/github/melin/lakehouse/rest/paimon/web/TableController.java"), "表列表"),
    (os.path.join(ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-paimon/src/main/java"
                        "/io/github/melin/lakehouse/rest/paimon/web/PartitionController.java"), "分区列表"),
    (os.path.join(ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-paimon/src/main/java"
                        "/io/github/melin/lakehouse/rest/paimon/web/ViewController.java"), "视图列表"),
    (os.path.join(ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-protocol-paimon/src/main/java"
                        "/io/github/melin/lakehouse/rest/paimon/web/FunctionController.java"), "函数列表"),
]
no_page_param = [label for path, label in PAGED_CONTROLLERS
                 if not re.search(r"Integer\s+page\b", read(path))]
check(f"{len(PAGED_CONTROLLERS)} 个分页端点都接受 page 参数", not no_page_param,
      f"这些端点没接 page：{no_page_param}（翻页请求会被当成不分页）")

check("TablePager 在 total 为 0 时不渲染（空态已经有说明文字）",
      "total > 0" in read(TABLE_PAGER),
      "空页面再挂一条「共 0 条」只会更吵")

# ------------------------------------------------------------------ 20. 文档计数

print()
print("20. 文档计数（文档写的「N 组 M 项」必须与脚本实际一致）")

# 这一组是**元校验**，起因是一次真实的漂移：docs/console.md 写「12 组 37 项」、
# README 写「12 组 38 项」，而脚本实际是 38——两个数字都没人核对，因为脚本此前
# 只打印计数、从不断言它。`AGENTS.md` §6 写着「加校验组要顺手把总数写进文档」，
# 但那是靠人记的约定，而人记不住（这次就没记住）。
#
# 期望值由脚本自身推出，不写死常量：组数＝源码里 `print("<n>. ...")` 的条数，
# 项数＝本次 check() 的总次数（含本组这两条）。于是新增/删除校验项时这里不用改，
# 但**文档必须改**——它正是本组要逼出来的那个动作。
#
# 本组的盲区：拦不住「连本组一起删掉」。这一点没有低成本解法，只能靠 review 时
# 注意到文档里的组数少了一组，所以在这里写明，免得下一个人以为它是全覆盖的。
this_source = read(os.path.abspath(__file__))
actual_groups = len(re.findall(r'^print\("\d+\. ', this_source, re.M))
actual_items = checks + 2  # 本组这两条还没跑，先计入总数


def documented_counts(path):
    """取文档里的「N 组 M 项」；没有这种写法时返回 None。"""
    found = re.search(r"(\d+)\s*组\s*(\d+)\s*项", read(path))
    return (int(found.group(1)), int(found.group(2))) if found else None


for label, doc_path in (("docs/console.md", os.path.join(ROOT, "docs/console.md")),
                        ("README.md", os.path.join(ROOT, "README.md"))):
    documented = documented_counts(doc_path)
    check(f"{label} 的组数与项数（应为 {actual_groups} 组 {actual_items} 项）与实际一致",
          documented == (actual_groups, actual_items),
          f"文档里写的是 {documented[0]} 组 {documented[1]} 项" if documented
          else "文档里找不到「N 组 M 项」这样的写法")

# ------------------------------------------------------------------ 汇总

print()
if problems:
    print(f"校验未通过：{len(problems)} / {checks} 项不一致")
    sys.exit(1)
print(f"全部校验通过：{checks} 项")
