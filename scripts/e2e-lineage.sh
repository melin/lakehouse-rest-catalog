#!/bin/bash
# 血缘的端到端验收：真实服务端 + 真实 SparkSession + 真实上报（docs/lineage-design.md §11.4）。
#
# 为什么需要它：抽取器有 24 条表驱动单测、上报器有队列单测、服务端有端点用例，
# 但三者各自绿着而拼起来错位完全可能——字段名对不上、ref 的归一化两端不一致、
# SQL 文本旁路没带过去。只有真跑一条 INSERT 才看得见。
#
# 用法：
#   ./scripts/e2e-lineage.sh                 # 默认端口 18081，跑 Spark 3.5，跑完自动停服务端
#   PORT=19091 ./scripts/e2e-lineage.sh      # 换端口
#   SPARK="3.5 4.1" ./scripts/e2e-lineage.sh # 多版本（4.1 目前会在建会话时失败，见文末）
#   KEEP=1 ./scripts/e2e-lineage.sh          # 保留服务端不退出，便于手工继续调试
#
# 前置：
#   - 已执行过 mvn -o install -DskipTests（需要 lakehouse-rest-server 的 jar）
#
# 与 e2e-spark-sql.sh 的关系：同一个服务端形态，两处差别—
#   - 这里显式写 lakehouse.rest.lineage.enabled=true。服务端缺省已经是 true，
#     写出来只为让「本脚本依赖血缘可用」这件事在启动命令里看得见；
#     那个脚本不写、走缺省；
#   - 这里的 SparkSession 用 -De2e.lineage.enabled=true 打开客户端的采集开关，
#     LiveSparkSession 据此设置 spark.lakehouse.rest.lineage.enabled。
#
# 注意 lineage.enabled 必须出现在 SparkConf 里（.config(...) / --conf），
# 不能靠 spark.conf.set：监听器的注册发生在会话状态构建期，那时只有 SparkConf 读得到。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${PORT:-18081}
BASE=http://127.0.0.1:$PORT
JAR=$ROOT/lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar
LOG=$(mktemp -t paimon-rest-lineage-e2e.XXXXXX.log)
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}
# 本仓库没有 Maven Wrapper（见 AGENTS.md §1），一律用系统 mvn（3.9+）
MVN=${MVN:-mvn}

if [ ! -f "$JAR" ]; then
  echo "缺少服务端 jar：$JAR"
  echo "请先执行：mvn -o install -DskipTests"
  exit 1
fi

echo "== 启动服务端（端口 ${PORT}，日志 ${LOG}） =="
"$JAVA_HOME/bin/java" -jar "$JAR" \
  --spring.profiles.active=h2 \
  --server.port="$PORT" \
  --lakehouse.rest.auto-create-catalog=true \
  --lakehouse.rest.auth.enabled=true \
  --lakehouse.rest.auth.tokens[0]=root \
  --lakehouse.rest.lineage.enabled=true \
  >"$LOG" 2>&1 &
SERVER_PID=$!

cleanup() {
  if [ -n "${KEEP:-}" ]; then
    echo "== KEEP 已设置：服务端仍在运行（pid ${SERVER_PID}），日志 ${LOG} =="
  else
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

echo "== 等待服务端就绪 =="
ready=0
for _ in $(seq 1 60); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H 'Authorization: Bearer root' "$BASE/api/management/v1/catalogs" 2>/dev/null)
  if [ "$code" = "200" ]; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "服务端未在 60 秒内就绪（最后一次状态码 ${code:-无}）"
  echo "--- 日志尾部 ---"
  tail -40 "$LOG"
  exit 1
fi
echo "就绪。"

cd "$ROOT" || exit 1

# 先把 -common 装进本地仓库，再跑测试。
#
# 为什么必须：`shared/` 的 Scala 主源码同时被 -3.5 / -4.1 两个模块编译，而它们引用
# `lakehouse-rest-spark-common` 里的 `LineageRun` / `LineageReporter` 是靠**本地仓库里的
# jar**，不是靠 reactor（这里只跑单模块，没有 -am）。jar 旧了的表现是
# `not found: type LineageRun` —— 看上去像源码写错了，其实是编译期依赖没更新。
echo "== 安装 lakehouse-rest-spark-common（shared 源码编译要用它） =="
"$MVN" -o -q install -pl :lakehouse-rest-spark-common -DskipTests || exit 1

echo "== 校验 docs/lineage-design.md 与实现一致 =="
# 静态检查，不需要服务端：端点双向一致 + §10.1 的配置键与默认值。
DOC_VERIFIER=$ROOT/scripts/verify-lineage-doc.py
for candidate in "${PYTHON:-}" /opt/homebrew/bin/python3.11 python3; do
  [ -n "$candidate" ] || continue
  if command -v "$candidate" >/dev/null 2>&1; then
    "$candidate" "$DOC_VERIFIER" || exit 1
    DOC_VERIFIED=1
    break
  fi
done
if [ -z "${DOC_VERIFIED:-}" ]; then
  echo "跳过文档校验：没有找到可用的 python3"
  echo "  或指定：PYTHON=/path/to/python3 scripts/e2e-lineage.sh"
fi

# 一次一个版本：一个 JVM 只能有一个 SparkContext，两个版本各自起 JVM 才干净。
#
# 两版都跑（Paimon 2.1.0 起）。此前 4.1 这一层跑不起来，但与血缘无关：Paimon 2.0.0 没有
# Spark 4.1 的 bundle，4.1 模块用的是 `paimon-spark-4.0_2.13`，它的 Spark 扩展在建会话时加载
# `Spark4Shim` 直接失败（`Unable to get public no-arg constructor`），表现为
# `Error while instantiating 'SessionStateBuilder'`，一条语句都还没发出去。既有的
# `ManagementSqlLiveServerTests` 在 4.1 上一模一样地红，可以拿它做对照。
#
# 2.1.0 发布了原生 `paimon-spark-4.1_2.13`，`-4.1/pom.xml` 已切过去，默认值因此回到两版
# （见 docs/maven-module-design.md §13.6）。要只跑一版仍可用 SPARK=3.5 覆盖。
VERSIONS=${SPARK:-"3.5 4.1"}
STATUS=0
for V in $VERSIONS; do
  echo "== 血缘端到端（Spark ${V}） =="
  "$MVN" -o -pl ":lakehouse-rest-spark-${V}" test \
    -Dtest=LineageLiveServerTests \
    -DfailIfNoTests=false \
    -De2e.management.url="$BASE/api/management/v1" \
    -De2e.management.token=root \
    -De2e.lineage.enabled=true
  rc=$?
  if [ "$rc" != "0" ]; then
    STATUS=$rc
    echo "== Spark ${V} 失败；服务端日志尾部 =="
    tail -30 "$LOG"
  fi
done

exit "$STATUS"
