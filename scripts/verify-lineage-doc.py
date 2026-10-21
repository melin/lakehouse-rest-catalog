#!/usr/bin/env python3
"""校验血缘文档与实现的一致性：端点、配置键。

为什么需要它：血缘是「一份设计文档 + 一次落地」同时存在的形态，而文档里的端点表
与配置表是最容易腐化的两处——它们不参与编译、不被任何测试引用，写错了只是
把读文档的人带偏：

  1. **文档写了但没实现**——按 §6.1 去 curl 一个不存在的路径，故障现场离原因很远。
  2. **实现了但没写**——`GET /runs/{runId}` 这类「顺手加的」端点在文档里找不到，
     下一个人就会以为运行历史只有列表、自己去写一条重复的查询。
  3. **配置键改名了**——`runRetentionDays` 变成 `retentionDays` 时，文档里的
     `run-retention-days` 就成了一句假话，而 YAML 里写它会被静默忽略。

校验项（与 `docs/lineage-design.md` §11.5 对应）：

  1. 端点数              `LineageController` 上解析出 5 个映射（加端点要同步这里与文档）
  2. 实现 → 文档         每个 `@*Mapping` 都能在文档里找到 `方法 路径`
  3. 文档 → 实现         文档里出现的每个血缘端点都在控制器上
  4. 注解解析完整性       控制器里 `@*Mapping` 的注解行数 == 解析出的端点数
                        （防止某种写法没被正则覆盖，让校验静默地少比几条）
  5. 配置键与默认值       §10.1 的表逐行对照 `RestServerProperties.Lineage` 的字段：
                        驼峰转 kebab 后名字一致、默认值渲染后一致、字段数一致

用法：
    python3 scripts/verify-lineage-doc.py
    python3 scripts/verify-lineage-doc.py --controller /tmp/x.java   # 变异测试用

退出码非 0 表示存在不一致。
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DOC = os.path.join(ROOT, "docs/lineage-design.md")
CONTROLLER = os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/java"
    "/io/github/melin/lakehouse/rest/web/LineageController.java")
PROPERTIES = os.path.join(
    ROOT,
    "lakehouse-rest-spring-parent/lakehouse-rest-core/src/main/java"
    "/io/github/melin/lakehouse/rest/config/RestServerProperties.java")

BASE = "/api/management/v1/lineage"

MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
CLASS_MAPPING = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')

ok = 0
bad = 0


def check(desc, passed, detail=""):
    global ok, bad
    if passed:
        ok += 1
        print("  [OK]   %s" % desc)
    else:
        bad += 1
        print("  [FAIL] %s" % desc)
        if detail:
            for line in str(detail).split("\n"):
                print("           %s" % line)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def controller_endpoints(path):
    """@RequestMapping 的基址 + 方法级 @*Mapping，拼成 (METHOD, 绝对路径)。"""
    src = read(path)
    bases = CLASS_MAPPING.findall(src)
    base = bases[0].rstrip("/") if bases else ""
    found = set()
    for method, sub in MAPPING.findall(src):
        path = (base + "/" + sub.lstrip("/")).replace("//", "/").rstrip("/") or "/"
        found.add((method.upper(), path))
    # 方法级 @RequestMapping(method = ...) 这一期没有，出现就说明解析漏了
    method_level_rm = len(re.findall(r'@RequestMapping\s*\(', src))
    return found, len(MAPPING.findall(src)) + max(0, method_level_rm - len(bases))


def doc_endpoints(path):
    src = read(path)
    found = set()
    for method, raw in re.findall(r'\b(GET|POST|PUT|DELETE|PATCH)\s+(/api/management/v1/lineage\S*)', src):
        clean = raw.split("?")[0].split("#")[0]
        clean = clean.rstrip("，。、）；;:：`")
        path_only = clean.rstrip("/") or "/"
        found.add((method, path_only))
    return found


def kebab(name):
    return re.sub(r'(?<!^)(?=[A-Z])', '-', name).lower()


def java_default(raw):
    raw = raw.strip()
    if raw.startswith('"') and raw.endswith('"'):
        return raw[1:-1]
    return raw


def lineage_props(path):
    """RestServerProperties.Lineage 里的字段 → {kebab 键: 默认值渲染}。"""
    src = read(path)
    start = src.index("public static class Lineage {")
    block = src[start:]
    # 到下一个顶层 class 或文件末尾
    nxt = re.search(r'\n    public static class |\n}\s*$', block[1:])
    if nxt:
        block = block[: nxt.start() + 1]
    props = {}
    for typ, name, default in re.findall(
            r'private\s+(?:final\s+)?(\w+)\s+(\w+)\s*=\s*([^;]+);', block):
        props[kebab(name)] = java_default(default)
    return props


def doc_props(path):
    """§10.1 表格 → {键: 默认值}。"""
    src = read(path)
    start = src.index("### 10.1 ")
    end = src.index("### 10.2 ")
    rows = {}
    for key, default in re.findall(r'^\|\s*`([a-z0-9-]+)`\s*\|\s*`([^`]*)`', src[start:end], re.M):
        rows[key] = default
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--controller", default=CONTROLLER)
    parser.add_argument("--doc", default=DOC)
    args = parser.parse_args()

    print("== 血缘文档与实现的一致性 ==")
    impl, annotated = controller_endpoints(args.controller)
    documented = doc_endpoints(args.doc)

    print("\n1. 端点数")
    check("控制器上解析出 5 个血缘端点（加端点要同步本脚本与文档 §6.1 / §8）",
          len(impl) == 5, "实际 %d 个：%s" % (len(impl), sorted(impl)))

    print("\n2. 实现 → 文档")
    missing = sorted(impl - documented)
    check("控制器上的每个端点都在 docs/lineage-design.md 里出现", not missing,
          "文档里找不到：\n" + "\n".join("%s %s" % m for m in missing) if missing else "")

    print("\n3. 文档 → 实现")
    extra = sorted(documented - impl)
    check("文档里写的每个血缘端点都在控制器上", not extra,
          "控制器上没有：\n" + "\n".join("%s %s" % m for m in extra) if extra else "")

    print("\n4. 注解解析完整性")
    check("控制器里 @*Mapping 的条数 == 解析出的端点数（防止漏认某一种写法）",
          annotated == len(impl), "注解 %d 条，解析出 %d 个" % (annotated, len(impl)))

    print("\n5. 配置键与默认值")
    want = lineage_props(PROPERTIES)
    got = doc_props(args.doc)
    check("§10.1 的配置键集合与 RestServerProperties.Lineage 的字段一一对应",
          set(want) == set(got),
          "文档多写：%s\n代码多出：%s" % (sorted(set(got) - set(want)), sorted(set(want) - set(got))))
    diff = {k: (want[k], got[k]) for k in set(want) & set(got) if want[k] != got[k]}
    check("§10.1 的默认值与代码里的默认值一致", not diff,
          "\n".join("%s: 代码 %s / 文档 %s" % (k, v[0], v[1]) for k, v in sorted(diff.items())))

    print()
    if bad:
        print("发现 %d 处不一致（共 %d 项校验）。" % (bad, ok + bad))
        return 1
    print("全部校验通过：%d 项。" % ok)
    return 0


if __name__ == "__main__":
    sys.exit(main())
