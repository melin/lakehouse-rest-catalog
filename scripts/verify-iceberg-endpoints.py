#!/usr/bin/env python3
"""校验 Iceberg 端点的三份真相彼此一致：规格、声明、实现。

为什么需要它：{@code GET /v1/config} 的 {@code endpoints} 字段是服务端对客户端的
**能力承诺**，它有两类错，靠人工读代码都看不出来：

  1. **声明了但没实现**——客户端在连接阶段看到这个端点存在，就会在某次操作里用它，
     而那时候才 404。典型例子是 {@code POST .../plan}：
     规格里有、本服务端不做，一旦声明出去，故障现场会离原因非常远
     （客户端一直正常，直到用户真的发起一次扫描规划）。
  2. **实现了但没声明**——功能其实能用，但客户端认为服务端不支持，
     于是在连接阶段就拒绝本服务端，或者回落到一条更差的路。

两边的字符串还必须与 Iceberg 客户端的 {@code Endpoint} 逐字一致（客户端拿它做
等值比较，多一个空格就静默失效），因此这里连"拼写"也要比：声明里的每一条都必须
能在规格的 paths 里找到，反之亦然。

校验项：
  1. 规格端点总数          规格里 (路径 x 方法) 的组合数 == 35
  2. 声明条数              IcebergEndpoints.SUPPORTED 的条数 == 30
  3. 声明 ⊆ 规格           声明里没有规格里不存在的端点（拼错路径/动词会在这里露出来）
  4. 未实现的端点          「规格 - 声明」== 设计文档 §6 列出的那 5 个（逐条列出）
  5. 声明 → 实现           每一条声明都能在控制器上找到对应的映射
  6. 实现 → 声明           Iceberg 侧的每个映射都在声明里（不声明就是一个隐藏端点）
  7. 注解解析完整性        每个控制器里 mapping 注解的条数 == 脚本解析出的端点条数
                           （防止某一种写法没被正则覆盖，让校验静默地少比几条）

用法：
    python3 scripts/verify-iceberg-endpoints.py

退出码非 0 表示存在不一致。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SPEC = os.path.join(ROOT, "spec/rest-catalog-open-api-iceberg.yaml")
ENDPOINTS = os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-protocol-iceberg/src/main/java"
    "/io/github/melin/lakehouse/rest/iceberg/support/IcebergEndpoints.java")
CORE_WEB = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java"
          "/io/github/melin/lakehouse/rest/web")
ICEBERG_WEB = os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-protocol-iceberg/src/main/java"
    "/io/github/melin/lakehouse/rest/iceberg/web")

# 走 Iceberg 协议面、但由 core 承担的端点（同路径冲突与不带 prefix 的那几条，
# 见 docs/iceberg-rest-catalog-design.md §2.1）。core 的控制器同时服务两套协议，
# 因此这里显式列出"其中属于 Iceberg 的那几条"，以及"同一个文件里不属于 Iceberg 的"——
# 后者也要列，否则往这些文件里加一个新映射不会让本脚本报错。
CORE_SOURCES = [
    ("CatalogRenameController.java",
     ["POST /v1/{prefix}/tables/rename", "POST /v1/{prefix}/views/rename"],
     []),
    ("ConfigController.java", ["GET /v1/config"], []),
    # OAuth 令牌端点在 Iceberg 规格里不带 prefix；同一个控制器上还挂着 Polaris 对齐的
    # 扩展路径，那条不属于 Iceberg 面（因此进第二组）
    ("OAuthTokenController.java",
     ["POST /v1/oauth/tokens"],
     ["POST /api/catalog/v1/oauth/tokens"]),
]

# 设计文档 §6 里写明"不做"的那 5 个端点。写成字面量而不是从"规格 - 声明"推出来：
# 推导出来的集合永远是自洽的，这里要的恰恰是"实现变了但文档没改"能被发现。
# 二期补齐了 functions（只读两个）、register-view 与 transactions/commit，
# 剩下的都在 plan / tasks / sign 一族（见各分组注释）。
NOT_IMPLEMENTED = [
    # plan / tasks：扫描规划要一个常驻执行引擎，是另一个产品的份量
    "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan",
    "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}",
    "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}",
    "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/tasks",
    # sign：远程签名 KMS
    "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign",
]

SPEC_ENDPOINT_TOTAL = 35
SUPPORTED_TOTAL = 30

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
    """从 OpenAPI 规格里取出全部 (路径 x 方法)。

    不引入 YAML 依赖：这份规格的结构很规整，路径键在缩进 2、方法键在缩进 4，
    本脚本只需要这两层。用 PyYAML 会多一个部署前提，而它带来的收益只是省下几行正则。
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


def declared_endpoints():
    """IcebergEndpoints.SUPPORTED 里的字符串字面量。"""
    body = read(ENDPOINTS)
    block = re.search(r"SUPPORTED\s*=\s*List\.of\((.*?)\);", body, re.S)
    if not block:
        raise SystemExit(f"找不到 SUPPORTED 清单：{ENDPOINTS}")
    return re.findall(r'"([^"]+)"', block.group(1))


ANNOTATION = re.compile(r"@(Get|Post|Put|Delete|Request)Mapping\b")
BASE_MAPPING = re.compile(r'@RequestMapping\(\s*"([^"]+)"\s*\)')
SIMPLE_MAPPING = re.compile(r'@(Get|Post|Put|Delete)Mapping\(\s*"([^"]+)"\s*\)')
VALUE_MAPPING = re.compile(r'@(Get|Post|Put|Delete)Mapping\(value\s*=\s*\{([^}]*)\}')
METHOD_MAPPING = re.compile(
    r'@RequestMapping\(value\s*=\s*"([^"]+)"\s*,\s*method\s*=\s*RequestMethod\.(\w+)\s*\)')


def without_comments(body):
    """去掉注释与字符串之外的 javadoc/行注释。

    必须做这一步：控制器的类注释里会出现 `{@code @GetMapping}` 这样的**说明文字**
    （本目录就有——为了一条 HEAD 映射的取舍专门解释了一段），
    不剥掉注释就会把它当成一个真映射，于是"注解条数 == 解析出的端点数"这条恒假。
    """
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    return re.sub(r"^\s*//.*$", "", body, flags=re.M)


def controller_endpoints(path):
    """解析一个控制器里的映射，返回 (端点集合, 注解总条数, 脚本认出的注解条数, 端点数)。

    能认的写法就是本仓库实际用到的四种：类级 @RequestMapping("...")、
    @PostMapping("...")、@PostMapping(value = {"a","b"})、以及 HEAD 用的
    @RequestMapping(value = "...", method = RequestMethod.HEAD)。类级映射可以没有
    （路径写在方法上，例如 OAuthTokenController），此时前缀为空串。

    解析完整性比的是**注解条数**而不是端点数：一条 `@PostMapping(value = {"a","b"})`
    是注解，它声明两个端点。注解总条数与脚本认出的条数不等，就说明文件里有某种写法
    没被覆盖——那种情况下"声明 ↔ 实现"的比对会静默地少比几条，比报错更危险。
    """
    body = without_comments(read(path))
    base = BASE_MAPPING.search(body)
    prefix = base.group(1) if base else ""

    endpoint_count = 0
    found = set()

    simple = SIMPLE_MAPPING.findall(body)
    multi = VALUE_MAPPING.findall(body)
    head = METHOD_MAPPING.findall(body)

    for verb, suffix in simple:
        found.add(f"{verb.upper()} {prefix}{suffix}")
        endpoint_count += 1
    for verb, paths in multi:
        for suffix in re.findall(r'"([^"]+)"', paths):
            found.add(f"{verb.upper()} {prefix}{suffix}")
            endpoint_count += 1
    for suffix, verb in head:
        found.add(f"{verb.upper()} {prefix}{suffix}")
        endpoint_count += 1

    annotations = len(ANNOTATION.findall(body))
    parsed = len(simple) + len(multi) + len(head) + (1 if base else 0)
    return found, annotations, parsed, endpoint_count


spec = spec_endpoints()
declared = set(declared_endpoints())

print("== Iceberg 端点：规格 / 声明 / 实现 三方比对 ==")
print(f"  规格 {len(spec)} 条，声明 {len(declared)} 条")

check(f"规格端点总数 == {SPEC_ENDPOINT_TOTAL}", len(spec) == SPEC_ENDPOINT_TOTAL,
      f"实际 {len(spec)} 条；规格换版本时同步这个常量与 design 文档 §6")
check(f"声明条数 == {SUPPORTED_TOTAL}", len(declared) == SUPPORTED_TOTAL,
      f"实际 {len(declared)} 条")

unknown = sorted(declared - spec)
check("声明里的端点都在规格里", not unknown,
      "规格里没有这些端点（多半是路径或动词拼错了）:\n" + "\n".join(unknown))

missing = sorted(spec - declared)
check("未实现的端点与设计文档 §6 一致", missing == sorted(NOT_IMPLEMENTED),
      "规格 - 声明 =\n" + "\n".join(missing)
      + "\n\ndesign 文档 §6 写的是 =\n" + "\n".join(sorted(NOT_IMPLEMENTED)))

# ---- 实现侧：逐个控制器解析，并与声明双向比对
implemented = set()
for name in sorted(os.listdir(ICEBERG_WEB)):
    if not name.endswith("Controller.java"):
        continue
    found, annotations, parsed, endpoint_count = controller_endpoints(
        os.path.join(ICEBERG_WEB, name))
    implemented |= found
    check(f"{name}: 注解解析完整", annotations == parsed,
          f"mapping 注解 {annotations} 条，脚本认出 {parsed} 条，展开成 {endpoint_count} 个端点；"
          f"有写法没被脚本认出来")

for name, expected, not_iceberg in CORE_SOURCES:
    found, annotations, parsed, endpoint_count = controller_endpoints(os.path.join(CORE_WEB, name))
    implemented |= set(expected)
    check(f"{name}: 注解解析完整", annotations == parsed,
          f"mapping 注解 {annotations} 条，脚本认出 {parsed} 条，展开成 {endpoint_count} 个端点")
    check(f"{name}: Iceberg 侧端点与脚本登记一致",
          found == set(expected) | set(not_iceberg),
          "实际映射 =\n" + "\n".join(sorted(found))
          + "\n\n脚本登记 =\n" + "\n".join(sorted(set(expected) | set(not_iceberg)))
          + "\n\n（新增映射要同时更新本脚本的 CORE_SOURCES，并想清楚它属不属于 Iceberg 面）")

undeclared = sorted(implemented - declared)
check("实现的端点都已声明", not undeclared,
      "实现了但没进 endpoints 清单（客户端会以为服务端不支持）:\n" + "\n".join(undeclared))

unimplemented = sorted(declared - implemented)
check("声明的端点都已实现", not unimplemented,
      "声明了但没有控制器映射（客户端会用到一个 404 的端点）:\n" + "\n".join(unimplemented))

print()
if problems:
    print(f"存在 {len(problems)} 处不一致，共 {checks} 项校验")
    sys.exit(1)
print(f"全部 {checks} 项校验通过")
