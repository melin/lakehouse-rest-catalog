#!/usr/bin/env python3
"""校验 Lance 面「54 个 operation 的台账」：规格副本 ↔ 控制器 ↔ 授权登记。

为什么需要它：这一面的取舍是**用一张清单表达能力边界**（设计文档 §3.1）——
13 个 P0 + 2 个 P1 + 2 个 P2 + 2 个标签只读做，其余一律 406 (code 0)。这份清单有三个地方
会各自漂移，而且都不是编译能抓到的：

  1. **规格换版本**。本文写作时按 48 个 operation 算；入库的副本
     （2026-10-28 抓自 lance-format/lance-namespace@main）是 54 个——
     多出来的是 branches 三个、materialized_view 两个、update_field_metadata 一个，
     全部落在「不做」那一栏。若没人盯着，设计文档里的 48 会一直错下去。
  2. **加了控制器忘了登记授权**（或反之）。Lance 面的授权是「未登记即 403」，
     而 403 与 406 的差别对客户端是「我没权限」与「这个能力不存在」——
     漏一条规则就把后者说成前者。
  3. **拦截器漏登记基址**。{@code /lance/**} 不在 {@code /v1/**} 之下，
     认证与授权两条路径清单各要写一行；少一行的后果是整个面不判权，
     而且**没有既有测试会发现**（这是设计文档点名的静默洞）。

校验项：
  1. 规格副本的 operation 总数           == 54
  2. 控制器解析出的端点集合              == 台账字面量（21 条，含两条规格外的兼容端点）
  3. 规格外的端点                        == 脚本登记的两条（各带理由）
  4. 台账端点（除规格外的两条）都在规格里
  5. 未实现的 operation 数              == 35，且 BatchCreateTableVersions 在其中
  6. 兜底控制器存在，且抛的是 LanceErrors.unsupported
  7. WebConfig 里 {@code /lance/v1/**} 出现 2 次（认证 + 授权两条拦截器）
  8. 每个控制器的 mapping 注解都被脚本认出来（否则上面的比对会静默少比几条）

用法：
    python3 scripts/verify-lance-endpoints.py

退出码非 0 表示存在不一致。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SPEC = os.path.join(ROOT, "spec/lance-namespace-rest-openapi.yaml")
LANCE_WEB = os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-protocol-lance/src/main/java"
    "/io/github/melin/lakehouse/rest/lance/web")
WEB_CONFIG = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java"
          "/io/github/melin/lakehouse/rest/config/WebConfig.java")

SPEC_OPERATIONS = 54
NOT_IMPLEMENTED_OPERATIONS = 35

# 台账：本服务端真有的端点（去掉 /lance 前缀后与规格同形）。
# 写成字面量而不是从控制器推出来——推导出来的集合永远自洽，
# 这里要的恰恰是「控制器改了但没人更新台账」能被发现。
IMPLEMENTED = {
    # ---- P0：13 个 operation
    "POST /v1/namespace/{id}/create",
    "GET /v1/namespace/{id}/list",
    "POST /v1/namespace/{id}/describe",
    "POST /v1/namespace/{id}/drop",
    "POST /v1/namespace/{id}/exists",
    "GET /v1/namespace/{id}/table/list",
    "GET /v1/table",
    "POST /v1/table/{id}/describe",
    "POST /v1/table/{id}/exists",
    "POST /v1/table/{id}/declare",
    "POST /v1/table/{id}/register",
    "POST /v1/table/{id}/deregister",
    "POST /v1/table/{id}/rename",
    # ---- P1：版本只读 2 个
    "POST /v1/table/{id}/version/list",
    "POST /v1/table/{id}/version/describe",
    # ---- P2：版本写 2 个
    "POST /v1/table/{id}/version/create",
    "POST /v1/table/{id}/version/delete",
    # ---- P2·L2-2：标签只读 2 个（索引仍 406：规范没定义 _indices/ 的盘上格式）
    "POST /v1/table/{id}/tags/list",
    "POST /v1/table/{id}/tags/version",
    # ---- 规格外的两条（理由见 EXTRA_ENDPOINTS）
    "GET /v1/table/{id}",
    "GET /v1/table/{id}/version/list",
}

# 规格里没有、但我们确实挂了映射的两条：
#  - 带 catalog 前缀的跨命名空间列举：规格把「列全部表」放在 GET /v1/table 上，
#    而它在本服务端被拒（必须带前缀），真正能用的是 /v1/table/{id}；
#  - ListTableVersions 的 GET 形态：参考实现（Gravitino 1.3.1）发的是 GET。
EXTRA_ENDPOINTS = {
    "GET /v1/table/{id}": "跨命名空间列举（规格只给了不带 id 的那一条，我们强制要求前缀）",
    "GET /v1/table/{id}/version/list": "ListTableVersions 的 GET 形态（兼容参考实现）",
}

# 刻意不做、且理由必须留在设计文档里的那一个：
# 它的路径里没有表标识，授权层无法定位资源（见 LanceVersionService 的类注释）。
DELIBERATELY_UNSUPPORTED = "POST /v1/table/version/batch-create"

problems = []
checks = 0


def check(label, ok, detail=""):
    global checks
    checks += 1
    if ok:
        print(f"  OK   {label}")
    else:
        print(f"  FAIL {label}")
        if detail:
            for line in str(detail).splitlines():
                print(f"         {line}")
        problems.append(label)


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def spec_endpoints():
    """从 OpenAPI 副本里取出全部 (路径 x 方法)。

    不引 PyYAML：这份文件结构规整，路径键缩进 2、方法键缩进 4，
    本脚本只要这两层。少一个依赖比省几行正则重要。
    """
    endpoints = set()
    path = None
    for line in read(SPEC).splitlines():
        match = re.match(r"^  (/v1[^:]*):\s*$", line)
        if match:
            path = match.group(1)
            continue
        method = re.match(r"^    (get|post|put|delete|head):", line)
        if method and path:
            endpoints.add(f"{method.group(1).upper()} {path}")
    return endpoints


ANNOTATION = re.compile(r"@(Get|Post|Put|Delete|Request)Mapping\b")
BASE_MAPPING = re.compile(r'@RequestMapping\(\s*"([^"]+)"\s*\)')
# 允许空串：不带 id 的那几条映射写成 @GetMapping("")（例如 GET /lance/v1/table）
SIMPLE_MAPPING = re.compile(r'@(Get|Post|Put|Delete)Mapping\(\s*"([^"]*)"\s*\)')


def without_comments(body):
    """剥掉注释：控制器注释里会出现 {@code @PostMapping} 这类说明文字。

    <b>块注释只从行首开始匹配</b>（{@code ^\s*/\*}）。Lance 面的兜底控制器注释里
    写着 {@code /lance/v1/**}——那个 {@code /**} 出现在**行中间**，
    若按「任意位置的 /*」去匹配，正则会从那里一直吃到下一个 {@code */}，
    把类注解本身一起吃掉，于是「兜底控制器存在」这条校验恒假。
    """
    body = re.sub(r"^\s*/\*.*?\*/", "", body, flags=re.S | re.M)
    return re.sub(r"^\s*//.*$", "", body, flags=re.M)


def controller_endpoints(path):
    """解析一个控制器：返回 (端点集合, 是否兜底控制器, 注解条数, 认出条数)。"""
    body = without_comments(read(path))
    base = BASE_MAPPING.search(body)
    prefix = base.group(1) if base else ""
    catch_all = prefix.endswith("/**")

    found = set()
    simple = SIMPLE_MAPPING.findall(body)
    for verb, suffix in simple:
        found.add(f"{verb.upper()} {prefix}{suffix}")
    if catch_all:
        return found, True, len(ANNOTATION.findall(body)), len(simple) + 1
    return found, False, len(ANNOTATION.findall(body)), len(simple) + (1 if base else 0)


spec = spec_endpoints()

print("== Lance 面端点台账：规格 / 控制器 / 拦截器 ==")
print(f"  规格 {len(spec)} 条，台账 {len(IMPLEMENTED)} 条（其中 {len(EXTRA_ENDPOINTS)} 条是规格外的兼容形态）")

check(f"规格副本的 operation 总数 == {SPEC_OPERATIONS}", len(spec) == SPEC_OPERATIONS,
      f"实际 {len(spec)} 条；规范升版时要同步这个常量、设计文档 §3.1 与 §6 的清单")

implemented = set()
catch_all = None
for name in sorted(os.listdir(LANCE_WEB)):
    if not name.endswith("Controller.java"):
        continue
    found, is_catch_all, annotations, parsed = controller_endpoints(os.path.join(LANCE_WEB, name))
    if is_catch_all:
        catch_all = name
        body = without_comments(read(os.path.join(LANCE_WEB, name)))
        check(f"{name}: 是兜底控制器且回 406",
              "LanceErrors.unsupported" in body,
              "未实现的 operation 必须由它接住并抛 LanceErrors.unsupported（code 0 → 406）")
        continue
    # 控制器上的基址是 /lance/v1/...，规格里是 /v1/...：去掉 /lance 才能比对
    implemented |= {endpoint.replace(" /lance/v1/", " /v1/") for endpoint in found}
    check(f"{name}: 注解解析完整", annotations == parsed,
          f"mapping 注解 {annotations} 条，脚本认出 {parsed} 条；有写法没被认出来")

check("台账与控制器一致", implemented == IMPLEMENTED,
      "控制器 - 台账 =\n" + "\n".join(sorted(implemented - IMPLEMENTED))
      + "\n\n台账 - 控制器 =\n" + "\n".join(sorted(IMPLEMENTED - implemented)))

extras = implemented - spec
check("规格外的端点与脚本登记一致", extras == set(EXTRA_ENDPOINTS),
      "实际规格外的端点 =\n" + "\n".join(sorted(extras))
      + "\n\n脚本登记 =\n" + "\n".join(sorted(EXTRA_ENDPOINTS))
      + "\n\n（新增一条规格外的端点要在这里写明理由：它多半是兼容性形态）")

unknown = sorted(implemented - spec - set(EXTRA_ENDPOINTS))
check("其余台账端点都在规格里", not unknown, "\n".join(unknown))

not_implemented = sorted(spec - implemented)
check(f"未实现的 operation 数 == {NOT_IMPLEMENTED_OPERATIONS}",
      len(not_implemented) == NOT_IMPLEMENTED_OPERATIONS,
      f"实际 {len(not_implemented)} 条：\n" + "\n".join(not_implemented))
check("BatchCreateTableVersions 仍在「不做」一栏",
      DELIBERATELY_UNSUPPORTED in not_implemented,
      "它出现在台账里了：它的路径不带表标识，授权层无法定位资源（LanceVersionService 注释）")

check("兜底控制器存在", catch_all is not None,
      f"{LANCE_WEB} 下没有 /lance/v1/** 的通配控制器；未实现的 operation 会落成 404")

config = read(WEB_CONFIG)
occurrences = config.count('"/lance/v1/**"')
check("WebConfig 里 /lance/v1/** 登记了两次（认证 + 授权）", occurrences == 2,
      f"实际 {occurrences} 次。Lance 面不在 /v1/** 之下，两条拦截器各要一行；"
      f"少一行的后果是这个面不判权，而且没有编译期或既有测试能发现")

print()
if problems:
    print(f"存在 {len(problems)} 处不一致，共 {checks} 项校验")
    sys.exit(1)
print(f"全部 {checks} 项校验通过")
