#!/usr/bin/env python3
"""从 sql/schema-{mysql,postgres}.sql 派生出 Flyway 迁移基线（**一次性工具**）。

为什么要这个脚本
----------------
库结构的**唯一真源是 JPA 实体**，它经由 `scripts/gen-{mysql,postgres}-ddl.sh`
产出 `sql/schema-*.sql`（全量初始化脚本，给人手工执行）。Flyway 要的是**同一份结构**
的另一种形态：能让它在启动时按版本顺序执行。两份文本如果各写一遍，迟早漂移，
而漂移的表现是「新库用 schema-mysql.sql 建出来的表，与 Flyway 建出来的表不一样」——
两种部署路径产出不同的 schema，排查时最难查。

因此这里做的是**纯派生**：输入 `sql/schema-*.sql`，输出 `V1__baseline.sql`。
不引入任何第二处结构描述，改结构照旧只改实体。

本脚本的适用窗口：**只到「V1 尚未被任何库应用」为止**
------------------------------------------------------
这条边界不是本脚本的限制，是 Flyway 的语义：`validate-on-migrate: true` 会让
Flyway 逐个比对「已应用版本的 checksum」与「当前文件内容」，**V1 的文件内容一旦变过，
任何已经应用过 V1 的库都会启动失败**：

    Validate failed: Migration checksum mismatch for migration version 1

于是本脚本有一个**自毁开关**：一旦 `db/migration/<vendor>/` 下出现 V1 以外的版本
（V2、V3…），说明已经进入「结构演进」阶段，V1 已被某些库应用过、不能再由快照重派生。
此时本脚本**拒绝运行**（退出码 2），而不是把 V1 悄悄改掉。

演进阶段的正确改法：

  1. 改实体 -> `./scripts/gen-{mysql,postgres}-ddl.sh` 重生成 `sql/schema-*.sql`
     （这只是「给人看的当前结构快照」，不再与 V1 比对）；
  2. **手写** `V2__<做了什么>.sql`（增量 `alter table ...`）放进同一目录；
  3. 启动时 Flyway 自动把 V2 应用到所有库（新库是 V1 + V2，存量库是 baseline + V2）。

第 2 步为什么要手写、不能从快照算出来：全量快照里没有「上一版长什么样」的信息，
增量语句无法从它推导（没有 SQL 引擎，也不该为了生成脚本引入一个）。这也是 Flyway
的常规用法——版本化迁移本来就是人手写的。

派生时剥掉什么、为什么
----------------------
只剥 `CREATE DATABASE` / `USE`（MySQL）与纯注释行，其余一字不改：

  - `CREATE DATABASE` / `USE \`lakehouse_catalog\``：Flyway 连的是**某个已存在的库**
    （连接串里写死了库名），自己建库、切库会把迁移作用到错误的库上，
    而且 `USE` 在 PostgreSQL 上根本不存在。建库与授权是 DBA 的职责，
    容器化部署里通常由 initContainer 或平台侧完成——不是应用启动该做的事。
  - 纯注释行：`sql/schema-*.sql` 的文件头讲的是「怎么手工执行这份文件」，
    对 Flyway 是噪音。Flyway 自己会记录 checksum，注释改了会改 checksum，
    让「只加了一句说明」也变成一次迁移变更。

不剥什么（有意保留）：
  - `ENGINE=InnoDB` / `mediumtext` / `comment '...'` 等 MySQL 专有语法——迁移在
    MySQL 上执行，它们是必要的。
  - 语句内的缩进与换行（Hibernate 的 format_sql 产物）——保留原样，
    让 `git diff` 能直接看出结构变化，而不是整段重排。

用法
----
    /opt/homebrew/bin/python3.11 scripts/gen-flyway-baseline.py          # 写入
    /opt/homebrew/bin/python3.11 scripts/gen-flyway-baseline.py --check  # 只校验是否漂移

`--check` 是为了在 CI / 提交前发现「改了实体、重生成 schema、却忘了重生成迁移」。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# (源文件, 输出文件, 是否剥建库/切库语句, 人类可读的方言名, 该方言的 Flyway 目录名)
TARGETS = [
    ("sql/schema-mysql.sql", "mysql/V1__baseline.sql", True, "MySQL 8.0", "mysql"),
    ("sql/schema-postgres.sql", "postgresql/V1__baseline.sql", False, "PostgreSQL 16", "postgresql"),
]

MIGRATION_ROOT = Path(
    "lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/db/migration"
)

# Flyway 的版本化迁移文件名：V<版本>__<描述>.sql
MIGRATION_FILE = re.compile(r"^V(\d+(?:\.\d+)*)__.+\.sql$")

# 文件头由本脚本生成，写在每条语句的注释里没有意义：
# Flyway 的 checksum 覆盖整份文件，因此文件头一旦变动就会让「已应用过的库」
# 被判为 checksum 不一致而启动失败。把「这份文件怎么来的」写在这里，
# 内容固定，不随生成次数变化。
HEADER = """-- LakeHouse Catalog Server：Flyway 基线（{dialect}）
--
-- 本文件由 scripts/gen-flyway-baseline.py 从 sql/schema-{vendor}.sql **派生**，请勿手工修改。
-- 结构定义的真源是 JPA 实体；改动路径是：改实体 -> ./scripts/gen-{vendor}-ddl.sh
-- -> python3.11 scripts/gen-flyway-baseline.py。
--
-- 为什么是 V1 而不是「按历史切分成多个版本」：本工程此前用 sql/schema-{vendor}.sql
-- 作为全量初始化脚本，结构演进没有留下逐版本的迁移历史，因此这里把当前结构
-- 作为 V1 基线。存量库（已用全量脚本建好的）不需要重跑本文件——
-- 用 flyway 的 baseline 机制标记为「已在 V1」即可，见 README「数据库迁移」一节。
--
-- 派生时剥掉了 CREATE DATABASE / USE 与纯注释行（理由见脚本 docstring）。
-- 其余语句与 sql/schema-{vendor}.sql 逐字相同。
--
"""


def statements_of(sql: str, strip_database_statements: bool) -> list[str]:
    """把整份 SQL 切成单条语句，并剥掉建库/切库语句与纯注释行。

    按 `;` + 换行切分而不是按 `;`：Hibernate 生成的文本里，每条语句之间恰好是
    `;\\n`，而值或注释里出现的 `;` 不会跟换行（本工程的 DDL 里没有这类字面量）。
    保守切分的好处是**不改变语句内部的任何字符**——用 sqlparse 之类做完整解析会
    把语句重排，diff 就再也没有可读性了。
    """
    out: list[str] = []
    for chunk in re.split(r";\s*\n", sql):
        body = "\n".join(
            line for line in chunk.split("\n") if not line.strip().startswith("--")
        ).strip()
        if not body:
            continue
        lowered = body.lower()
        if strip_database_statements and (
            lowered.startswith("create database") or lowered.startswith("use ")
        ):
            continue
        out.append(body)
    return out


def render(source: Path, strip_database_statements: bool, dialect: str, vendor: str) -> str:
    sql = source.read_text(encoding="utf-8")
    statements = statements_of(sql, strip_database_statements)
    if not statements:
        raise SystemExit(f"{source} 里没有解析到任何语句，切分规则可能已失效")
    return HEADER.format(dialect=dialect, vendor=vendor) + ";\n\n".join(statements) + ";\n"


def summary(rendered: str) -> str:
    """给出一句「生成了什么」的统计。

    先把注释行剥掉再切分：文件头与紧随其后的第一条 `create table` 之间只隔一个换行，
    不剥的话它们会被算成同一条语句，统计结果里就会冒出一个 `-- lakehouse 1 条`，
    看着像文件坏了。
    """
    body = "\n".join(
        line for line in rendered.split("\n") if not line.strip().startswith("--")
    )
    kinds = Counter(
        " ".join(chunk.split()[:2]).lower()
        for chunk in re.split(r";\s*\n", body)
        if chunk.strip()
    )
    return "，".join(f"{name} {count} 条" for name, count in sorted(kinds.items()))


def apply_version(vendor: str) -> str | None:
    """该方言目录下已存在的最大迁移版本号；目录为空则返回 None。

    任何非 V1 的版本出现，都意味着 V1 已经（或即将）被应用过——见模块 docstring
    里「只到 V1 尚未被任何库应用为止」那一节。
    """
    directory = ROOT / MIGRATION_ROOT / vendor
    if not directory.is_dir():
        return None
    versions = [
        match.group(1)
        for entry in directory.iterdir()
        if (match := MIGRATION_FILE.match(entry.name))
    ]
    if not versions:
        return None
    # 按数值比而不是字典序：V10 要排在 V9 之后
    return max(versions, key=lambda v: tuple(int(part) for part in v.split(".")))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="只检查是否有漂移，不写文件；有漂移时以非零状态退出",
    )
    args = parser.parse_args()

    drift: list[str] = []
    for source_rel, target_rel, strip_db, dialect, vendor in TARGETS:
        # 自毁开关：出现 V1 以外的版本就拒绝运行（--check 也要拒：
        # 那时「基线是否与快照一致」已经是个没有意义的比较，放行只会让人以为还能重派生）。
        version = apply_version(vendor)
        if version is not None and version != "1":
            print(
                f"拒绝运行：{vendor} 的迁移目录里已有 {version} 版，V1 不能再重派生。\n"
                f"  原因：V1 的文件内容一旦变过，所有应用过 V1 的库都会因 checksum 不一致\n"
                f"        拒绝启动（validate-on-migrate: true）。\n"
                f"  正确做法：不再动 V1，改实体 -> gen-{vendor}-ddl.sh 重生成快照 ->\n"
                f"        手写 V{int(version) + 1}__<描述>.sql 增量语句。\n"
                f"  详见本脚本 docstring「只到 V1 尚未被任何库应用为止」一节。",
                file=sys.stderr,
            )
            return 2

        source = ROOT / source_rel
        if not source.exists():
            print(f"跳过：{source_rel} 不存在", file=sys.stderr)
            continue
        target = ROOT / MIGRATION_ROOT / target_rel
        expected = render(source, strip_db, dialect, vendor)

        if args.check:
            if not target.exists():
                drift.append(f"{target_rel}：缺失")
                continue
            if target.read_text(encoding="utf-8") != expected:
                drift.append(f"{target_rel}：与 {source_rel} 不一致")
            else:
                print(f"OK  {target_rel}")
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.read_text(encoding="utf-8") == expected:
            print(f"无变化：{target.relative_to(ROOT)}")
            continue
        target.write_text(expected, encoding="utf-8")
        print(f"已写入：{target.relative_to(ROOT)}（{summary(expected)}）")

    if drift:
        print("发现漂移：", file=sys.stderr)
        for item in drift:
            print(f"  - {item}", file=sys.stderr)
        print(
            "请先跑 ./scripts/gen-{mysql,postgres}-ddl.sh 重生成 schema，"
            "再跑本脚本（不带 --check）重生成迁移基线。",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
