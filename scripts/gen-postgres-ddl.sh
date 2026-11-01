#!/bin/bash
# 生成 PostgreSQL 建表语句并安装到 sql/schema-postgres.sql。
#
# 为什么要有这个脚本：DDL 必须与实体一致，手工维护 28 张表迟早会漂移。
# 建表语句由 Hibernate 依据实体元数据生成（见 PostgresDdlGeneratorTests），
# 本脚本只负责补文件头、落到交付路径，并把「是否发生变化」告诉调用者。
#
# 用法：
#   ./scripts/gen-postgres-ddl.sh              # 生成并覆盖 sql/schema-postgres.sql
#   OUT=/tmp/x.sql ./scripts/gen-postgres-ddl.sh
#
# 前置：JDK 21（与其它构建脚本一致）；系统 mvn（3.9+）。
#   本仓库**没有** Maven Wrapper（见 AGENTS.md），因此这里用 mvn。
#   需要附加 Maven 参数（例如离线环境里覆盖 paimon.version）时用 MVN_ARGS：
#     MVN_ARGS=-Dpaimon.version=2.0.0 ./scripts/gen-postgres-ddl.sh
#
# 与 MySQL 脚本的差异：PostgreSQL 没有 CREATE DATABASE / USE 这一套，
# 脚本只产出表结构本身，使用前需先连到目标数据库（库必须已存在）：
#   createdb lakehouse_catalog            # 若库尚不存在
#   psql -d lakehouse_catalog -f sql/schema-postgres.sql
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/sql/schema-postgres.sql}
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}
MVN=${MVN:-mvn}
MVN_ARGS=${MVN_ARGS:-}

GENERATED=$(mktemp -t paimon-postgres-schema.XXXXXX.sql)
TARGET=$(mktemp -t paimon-postgres-schema-final.XXXXXX.sql)
trap 'rm -f "$GENERATED" "$TARGET"' EXIT

echo "== 由实体元数据生成 PostgreSQL DDL =="
cd "$ROOT" || exit 1
"$MVN" -o -pl :lakehouse-rest-server test \
  -Dtest=PostgresDdlGeneratorTests \
  -DfailIfNoTests=false \
  "-Dpostgres.ddl.output=$GENERATED" \
  $MVN_ARGS \
  -q

if [ ! -s "$GENERATED" ]; then
  echo "未生成 DDL：$GENERATED"
  exit 1
fi

# 文件头固定，便于读者知道这是生成物以及如何重新生成
cat >"$TARGET" <<'HEADER'
-- LakeHouse Catalog Server 元数据表结构（PostgreSQL 16）
--
-- 本文件由 scripts/gen-postgres-ddl.sh 从 JPA 实体元数据生成，请勿手工修改：
-- 手工改动会在下次生成时丢失，需要改结构请改实体后重新生成。
--
-- 应用方式（数据库需预先建好，库名可改 application-postgresql.yml 与下面的连接命令）：
--   createdb lakehouse_catalog
--   psql -d lakehouse_catalog -f sql/schema-postgres.sql
--
-- 几点说明：
--   - 本文件用于初始化空库，重复执行会因表已存在而失败；要重建请先 drop schema public cascade;
--   - 应用启动时的 ddl-auto 是 validate：本文件与实体不一致时会直接启动失败，
--     而不是悄悄改表，因此升级代码后若实体有变，需要重新生成并迁移；
--   - 默认 schema 是 public；如需其它 schema，请在应用侧用 search_path 指定，
--     并重命名下面所有表为 <schema>.<table>（生成物默认不带 schema 前缀）；
--   - 自增列用 GENERATED ... AS IDENTITY（PostgreSQL 的序列等价物），不是 MySQL 的 AUTO_INCREMENT；
--   - 只有 pg 特有、MySQL 没有的语法差异（IDENTITY / 独立的 comment on 语句）才需要单独留意；
--   - 表与列的注释来自实体的 @Table(comment = ...) / @Column(comment = ...)，
--     要改注释请改实体：改这个文件会在下次生成时丢失。
--
HEADER

# Hibernate 的脚本输出以空行开头，去掉它，让文件头与首个建表语句之间不留空档
sed '/./,$!d' "$GENERATED" >>"$TARGET"

mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ] && cmp -s "$OUT" "$TARGET"; then
  echo "无变化：$OUT"
else
  cp "$TARGET" "$OUT"
  # mktemp 建的是 0600，首次落盘（$OUT 不存在时）会把这份权限带过去，
  # 结果是交付文件在仓库里不可读。显式改回常规权限。
  chmod 644 "$OUT"
  echo "已写入：$OUT"
fi
# Hibernate 格式化后的语句带缩进，因此匹配前导空白。
# 与 MySQL 不同，PostgreSQL 的唯一约束内联在 create table 里（constraint uk_... unique (...)），
# 没有独立的 alter table ... add constraint 语句，所以这里按 unique ( 计数。
echo "建表语句 $(grep -cE '^[[:space:]]*create table' "$OUT") 条，唯一约束（内联）$(grep -cE 'unique *\(' "$OUT") 条，索引 $(grep -cE '^[[:space:]]*create index' "$OUT") 条，注释语句 $(grep -cE '^[[:space:]]*comment on' "$OUT") 条"
