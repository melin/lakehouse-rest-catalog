#!/usr/bin/env python3
"""校验 docs/spark-sql-reference.md 与实现三者一致。

为什么需要它：SQL 参考文档里最贵的一类错误是「写得很像但不成立」——
漏掉一条语句、access 名少一个字母、结果列多一列。这些靠人工读稿看不出来，
但可以机械比对。本脚本不生成文档，只做校验，与生成器分开实现
（共用代码时推导错误会互相掩盖）。

校验项：
  1. 语句覆盖    文档第 2 节的编号从 1 起连续，且与其声明的「共 N 条语句」一致
  2. 语句清单    第 2 节总览与第 10 节示例块覆盖全部语法备选
  3. access 词表 第 7.4 节的 14 项 access（官方 12 + 两个凭据扩展词）与 8 种资源类型的可用集合 == PermissionDtos
  4. 资源写法    第 7.3 节的八种资源写法在语法与 PermissionDtos.ResourceType 中都能对上
  5. 结果列      第 8 节与实现声明的列名一致
  6. 配置项      第 1.3 节的配置键 == 实现里的配置常量
  7. 端点映射    第 2 节给出的 REST 路径在客户端代码里存在
  8. 保留字      第 3.3 节列出的关键字 == 语法文件的词法关键字，且声明的数量正确

用法：
    python3 scripts/verify-spark-sql-doc.py

退出码非 0 表示存在不一致。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "docs/spark-sql-reference.md")

# 三个源码位置都在 -spark 的共享目录里（两个 Spark 版本模块编译同一份源码），
# 客户端在 -common 模块。路径改过一次（从单模块拆成 common + 两个版本模块），
# 这里写死新路径：脚本是离线校验，读不到文件时会直接报错，不会静默放过。
SPARK = os.path.join(ROOT, "lakehouse-rest-spark")
GRAMMAR = os.path.join(SPARK, "shared/src/main/antlr4/io/github/melin/lakehouse/rest/spark/parser/ManagementSql.g4")
COMMANDS = os.path.join(SPARK, "shared/src/main/scala/io/github/melin/lakehouse/rest/spark/ManagementCommands.scala")
EXTENSIONS = os.path.join(SPARK, "shared/src/main/scala/io/github/melin/lakehouse/rest/spark/ManagementSparkExtensions.scala")
CLIENT = os.path.join(SPARK, "lakehouse-rest-spark-common/src/main/java/io/github/melin/lakehouse/rest/spark/client/ManagementApiClient.java")
# 数据策略的七条命令在独立文件里（output 列与 SQL 侧配置键都出自这里）
POLICY_COMMANDS = os.path.join(
    SPARK, "shared/src/main/scala/io/github/melin/lakehouse/rest/spark/PolicySqlCommands.scala")
# 参考文档里的资源定位器与 access 词表来自服务端的官方权限模型，实现就是这份 DTO
# 拆模块后 PermissionDtos 落在 core（它与格式无关，两套协议面都要用权限词表）。
PERMISSIONS = os.path.join(
    ROOT, "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java/io/github/melin/lakehouse/rest/dto/PermissionDtos.java")

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


def section(doc, heading, end):
    """截取 `### heading` 到下一个 `end` 之间的正文；找不到时返回空串并抛出可读错误。"""
    match = re.search(re.escape(heading) + r"(.*?)" + re.escape(end), doc, re.S)
    if not match:
        raise SystemExit(f"文档结构不符：找不到从「{heading}」到「{end}」的章节")
    return match.group(1)


doc = read(DOC)
grammar = read(GRAMMAR)

# ------------------------------------------------------------------ 1. 语句覆盖

statement_rule = re.search(r"^statement\s*\n\s*:\s*(.*?)\s*;", grammar, re.S | re.M)
branches = [b.strip() for b in statement_rule.group(1).split("|")] if statement_rule else []
print(f"语法 statement 规则的备选数：{len(branches)}")

# ------------------------------------------------------------------ 2. 语句清单

# 每条语句 → 能唯一识别它的正则。顺序与 §2 总览表一致。
SIGNATURES = [
    ("createPrincipal",      r"CREATE\s+PRINCIPAL\s+(?!ROLE)"),
    ("dropPrincipal",        r"DROP\s+PRINCIPAL\s+(?!ROLE)"),
    ("alterPrincipal",       r"ALTER\s+PRINCIPAL\s+(?!ROLE)"),
    ("resetPrincipal",       r"(?:RESET|ROTATE)\s+PRINCIPAL\b"),
    ("createPrincipalRole",  r"CREATE\s+PRINCIPAL\s+ROLE\b"),
    ("dropPrincipalRole",    r"DROP\s+PRINCIPAL\s+ROLE\b"),
    ("alterPrincipalRole",   r"ALTER\s+PRINCIPAL\s+ROLE\b"),
    ("grantPrincipalRole",   r"GRANT\s+PRINCIPAL\s+ROLE\b"),
    ("revokePrincipalRole",  r"REVOKE\s+PRINCIPAL\s+ROLE\b"),
    # 资源授权：access 是普通标识符或 GRANT/ALTER/DROP 三个关键字之一，
    # 后半段用 `ON <资源> ... TO|FROM <授权对象>` 定形状，避免和角色装配语句混淆
    ("grantPermissions",     r"GRANT\s+[A-Z_`]+\s*(?:,\s*[A-Z_`]+\s*)*ON\s+[A-Z_]+.*?\bTO\b"),
    ("revokePermissions",    r"REVOKE\s+[A-Z_`]+\s*(?:,\s*[A-Z_`]+\s*)*ON\s+[A-Z_]+.*?\bFROM\b"),
    ("showPrincipals",       r"SHOW\s+PRINCIPALS\b"),
    ("showPrincipalRoles",   r"SHOW\s+PRINCIPAL\s+ROLES\b"),
    ("showPermissions",      r"SHOW\s+PERMISSIONS\b"),
    ("showCatalogs",         r"SHOW\s+CATALOGS\b"),
    # 数据策略（设计稿 §13）：六条修改语句都是 ALTER TABLE 形状，靠
    # ROW FILTER / MASK / POLICY 关键字链与原生 ALTER TABLE 区分
    ("setRowFilter",         r"SET\s+ROW\s+FILTER\b"),
    ("dropRowFilter",        r"DROP\s+ROW\s+FILTER\b"),
    ("setColumnMask",        r"SET\s+MASK\b"),
    ("dropColumnMask",       r"DROP\s+MASK\b"),
    ("setPolicySubjects",    r"SET\s+POLICY\b"),
    ("dropPolicy",           r"DROP\s+POLICY\b"),
    ("showPolicy",           r"SHOW\s+POLICY\b"),
]
check("语句标识表覆盖全部语法备选",
      sorted(b for b, _ in SIGNATURES) == sorted(branches),
      "语法侧独有：" + ", ".join(sorted(set(branches) - {b for b, _ in SIGNATURES})) + "\n"
      "文档侧独有：" + ", ".join(sorted({b for b, _ in SIGNATURES} - set(branches))))

# §2 总览表是「给人看的语句清单」，与语法规则不是一一对应——`ALTER ... SET|UNSET`
# 与 `RESET|ROTATE` 各占两行（一条规则两种写法），`SHOW` 那一行反过来并了三种形态。
# 因此这里不比行数与备选数（那只是历史巧合），只钉两件会真出问题的事：
# 编号连续（漏行后编号会跳），以及行数与正文声明的条数一致（删了语句忘改声明）。
overview = re.search(r"## 2\. 语句总览(.*?)\n### 结果集形态", doc, re.S)
overview = overview.group(1) if overview else ""
overview_rows = [int(number) for number in re.findall(r"^\|\s*(\d+)\s*\|", overview, re.M)]
check("第 2 节的编号从 1 起连续",
      overview_rows == list(range(1, len(overview_rows) + 1)),
      f"实际编号：{overview_rows}")
declared = re.search(r"共\s*(\d+)\s*条语句", overview)
check(f"第 2 节声明的条数与表格行数一致（{len(overview_rows)} 行）",
      declared is not None and int(declared.group(1)) == len(overview_rows),
      f"声明 {declared.group(1) if declared else '未找到'} 条 / 表格 {len(overview_rows)} 行")

example = re.search(r"<!-- doc-example:begin -->(.*?)<!-- doc-example:end -->", doc, re.S)
if not example:
    check("存在第 10 节示例块（doc-example 标记）", False, "未找到 doc-example:begin / end 标记")
    example_body = ""
else:
    example_body = example.group(1)
missing = [name for name, pattern in SIGNATURES
           if not re.search(pattern, example_body, re.I)]
check(f"示例块覆盖全部 {len(SIGNATURES)} 条语句", not missing,
      "示例中缺少：" + ", ".join(missing))

# ------------------------------------------------------------------ 3. access 词表

permissions_source = read(PERMISSIONS)


def enum_constants(source, name):
    """取 `public enum X {` 与它自己的闭合大括号之间的常量名（每行一个）。"""
    body = re.search(r"public enum " + name + r" \{(.*?)\n    \}", source, re.S)
    if not body:
        raise SystemExit(f"PermissionDtos 里找不到 enum {name}")
    return re.findall(r"^ {8}([A-Z][A-Z0-9_]*)[,;]", body.group(1), re.M)


def allowed_accesses(source):
    """`allowedFor` 的 switch：资源类型 → 允许的 access 集合。"""
    body = re.search(r"public static Set<Access> allowedFor\(ResourceType type\) \{(.*?)\n        \}",
                     source, re.S)
    if not body:
        raise SystemExit("PermissionDtos 里找不到 allowedFor")
    result = {}
    for type_name, items in re.findall(r"case (\w+) -> set\(([^;]*?)\);", body.group(1), re.S):
        result[type_name] = [item.strip() for item in items.replace("\n", " ").split(",")
                             if item.strip()]
    return result


access_enum = enum_constants(permissions_source, "Access")
resource_enum = enum_constants(permissions_source, "ResourceType")
allowed = allowed_accesses(permissions_source)
print(f"PermissionDtos：{len(access_enum)} 项 access / {len(resource_enum)} 种资源类型")

body_84 = section(doc, "### 7.4 access 词表", "\n### 7.5")
rows_84 = re.findall(r"^\|\s*`([A-Z_]+)`\s*\|\s*([^|]+)\|", body_84, re.M)
doc_allowed = {row_type: re.findall(r"`([A-Z][A-Z0-9_]*)`", cells) for row_type, cells in rows_84}

check(f"第 7.4 节列出的资源类型与 PermissionDtos 一致（{len(resource_enum)} 种）",
      sorted(doc_allowed) == sorted(resource_enum),
      "实现独有：" + ", ".join(sorted(set(resource_enum) - set(doc_allowed))) + "\n"
      "文档独有：" + ", ".join(sorted(set(doc_allowed) - set(resource_enum))))

for type_name in sorted(allowed):
    expected, listed = allowed[type_name], doc_allowed.get(type_name, [])
    check(f"「{type_name}」允许的 access 与实现一致（{len(expected)} 项）",
          sorted(listed) == sorted(expected),
          f"实现 {sorted(expected)}\n文档 {sorted(listed)}")

declared = re.search(r"### 7\.4 access 词表（(\d+) 项）", doc)
check(f"第 7.4 节声明的 access 数量正确（{len(access_enum)} 项）",
      declared is not None and int(declared.group(1)) == len(access_enum),
      f"声明 {declared.group(1) if declared else '未找到'} 项 / 实现 {len(access_enum)} 项")

# ------------------------------------------------------------------ 4. 资源写法

# permissionResource 规则体里每行一个备选（首行是 `: x`，其余是 `| y`）
resource_rule = re.search(r"^permissionResource\s*\n(.*?)\s*;", grammar, re.S | re.M).group(1)
grammar_rules = re.findall(r"\b(\w+Resource)\b", resource_rule)


def expected_rule(type_value):
    """CATALOG_ALL → catalogAllResource：枚举值到下划线驼峰规则的命名。"""
    head, _, tail = type_value.partition("_")
    return head.lower() + (tail.capitalize() if tail else "") + "Resource"


expected_rules = [expected_rule(value) for value in resource_enum]
check("八种资源定位器在语法中一一对应",
      sorted(grammar_rules) == sorted(expected_rules),
      "语法 " + ", ".join(sorted(grammar_rules)) + "\n"
      "期望 " + ", ".join(sorted(expected_rules)))

body_83 = section(doc, "### 7.3 八种资源写法", "\n### 7.4")
resource_rows = re.findall(r"^\|\s*`([^`]+)`\s*\|\s*`([A-Z_]+)`\s*\|", body_83, re.M)
doc_types = {row[1] for row in resource_rows}
check("第 7.3 节的 ResourceType 取值与 PermissionDtos 一致",
      doc_types == set(resource_enum),
      f"实现 {sorted(resource_enum)}\n文档 {sorted(doc_types)}\n"
      f"文档少写：{sorted(set(resource_enum) - doc_types)}；文档多写：{sorted(doc_types - set(resource_enum))}")
check("第 7.3 节列出了八种资源写法",
      len(resource_rows) == len(grammar_rules) == 8,
      f"文档列出 {len(resource_rows)} 种：{[row[0] for row in resource_rows]}")

# ------------------------------------------------------------------ 5. 结果列

commands = read(COMMANDS) + read(POLICY_COMMANDS)
# 列名有两种写法：直呼 attr("name" ...)（ManagementCommands）与
# ("name", nullable) 元组循环建列（ShowPolicyCommand）
impl_columns = sorted(set(re.findall(r'attr\("([a-z_]+)"', commands))
                      | set(re.findall(r'\("([a-z_]+)", (?:true|false)\)', commands)))
unused = [name for name in impl_columns if f"`{name}`" not in doc]
check(f"实现声明的全部列名都在文档中出现（{len(impl_columns)} 个）",
      not unused,
      "文档未提及：" + ", ".join(unused))

SHOW_COLUMNS = {
    "SHOW CATALOGS": ["name", "type"],
    "SHOW PRINCIPALS": ["name", "client_id"],
    "SHOW PRINCIPAL ROLES": ["name", "federated"],
    "SHOW PERMISSIONS": ["catalog", "principal", "type", "resource", "database",
                         "object_name", "columns", "access", "expire_time"],
    "SHOW POLICY": ["version", "subjects", "row_filter", "masked_columns",
                    "allow_null_confirmed", "updated_by", "updated_at"],
}
section_show = re.search(r"## 8\. SHOW(.*?)\n## 9\.", doc, re.S).group(1)
for label, columns in SHOW_COLUMNS.items():
    block = re.search(r"### 8\.\d+ `" + re.escape(label) + r"`(.*?)(?=\n### |\Z)", section_show, re.S)
    body = block.group(1) if block else ""
    absent = [c for c in columns if f"`{c}`" not in body]
    check(f"{label} 的结果列在文档中写全", not absent,
          "缺少：" + ", ".join(absent))

# ------------------------------------------------------------------ 6. 配置项

extensions = read(EXTENSIONS) + read(POLICY_COMMANDS)
# 键名里可以有连字符（`refresh-window`、`confirm-destructive`），字符类必须带上 `-`：
# 少了它，这些键根本不在检查范围内——「全部键都在文档里」于是变成一条空跑的校验
config_keys = sorted(set(re.findall(r'val\s+[A-Z_]+\s*=\s*"(spark\.[a-zA-Z.\-]+)"', extensions)))
docs_keys = set(re.findall(r"`(spark\.lakehouse\.rest\.[a-zA-Z.\-]+)`", doc))
missing_keys = [key for key in config_keys if key not in docs_keys]
check(f"实现的配置键都在文档中列出（{len(config_keys)} 个）",
      not missing_keys,
      "文档未提及：" + ", ".join(missing_keys))

# ------------------------------------------------------------------ 7. 端点映射

client = read(CLIENT)
# 第 2 节表格里的路径模板，转成客户端代码里的字符串拼接形态
table = re.search(r"## 2\. 语句总览(.*?)\n### 结果集形态", doc, re.S).group(1)
paths = re.findall(r"`(?:POST|PUT|GET|DELETE) (`?[A-Z]+`?[^`|]*)`", table)
missing_paths = []
for path in paths:
    core = re.sub(r"\{(\w+)\}", "X", path).strip()
    # 客户端用常量拼接，取路径最后一段做存在性检查即可
    tail = core.split("/")[-1]
    if tail and tail.upper() not in client.upper() and f'"{tail}"' not in client:
        missing_paths.append(core)
check("第 2 节给出的端点路径都能在客户端代码中找到出处",
      not missing_paths,
      "客户端未出现：" + "; ".join(missing_paths))

# ------------------------------------------------------------------ 8. 保留字

# 词法里的关键字定义形如 `ALTER : A L T E R ;`——用字母片段写成，因此大小写不敏感。
# 右侧只允许「单个大写字母」或 `UNDERSCORE` 片段：这样既能覆盖 CATALOG_ALL 这类带下划线的
# 关键字，又不会把 `SEMICOLON : ';' ;` 之类当成关键字。
lexer_keywords = set()
for match in re.finditer(r"^([A-Z][A-Z_]*)\s*:\s*([^;]+);", grammar, re.M):
    tokens = match.group(2).split()
    if tokens and all(t == "UNDERSCORE" or (len(t) == 1 and t.isupper()) for t in tokens):
        lexer_keywords.add(match.group(1))

body_33 = section(doc, "### 3.3 关键字是保留字", "\n### 3.4")
# 只取「清单句」——它止于「这 N 个词出现在名称位置时必须加反引号」。
# 后面的正文会用反引号引用 access 取值与已删除的旧关键字，那些不是保留字。
listing = re.search(r"^(.*?)这\s*(\d+)\s*个词", body_33, re.S)
check("第 3.3 节有保留字清单与数量声明", listing is not None,
      "找不到「这 N 个词」句式")
listing, declared_count = (listing.group(1), int(listing.group(2))) if listing else ("", None)
doc_keywords = set(re.findall(r"`([A-Z][A-Z0-9_]*)`", listing))
check(f"第 3.3 节的保留字清单与语法文件一致（{len(lexer_keywords)} 个）",
      doc_keywords == lexer_keywords,
      "文档多出：" + ", ".join(sorted(doc_keywords - lexer_keywords)) + "\n"
      "文档缺少：" + ", ".join(sorted(lexer_keywords - doc_keywords)))
check("第 3.3 节声明的保留字数量正确",
      declared_count == len(lexer_keywords),
      f"声明 {declared_count} 个 / 实际 {len(lexer_keywords)} 个")

# ------------------------------------------------------------------ 汇总

print()
if problems:
    print(f"校验未通过：{len(problems)} / {checks} 项不一致")
    sys.exit(1)
print(f"全部校验通过：{checks} 项")
