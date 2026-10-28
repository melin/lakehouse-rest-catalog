#!/bin/bash
# Lance 协议面对真实服务端的端到端验收（Spark → /lance/v1/**）。
#
# 为什么要一个单独脚本而不是并进 e2e-spark-sql.sh：
#   1. Lance 的 parser 扩展会把「解析失败」的报文换成它自己语法的那一份，而
#      e2e-spark-sql.sh 里那批判例守的正是报文本身（管理语句 / 策略语句）——同批跑
#      等于让两条互不相干的约束互相污染。因此 Lance 只在 `-De2e.lance.enabled=true`
#      时才装进会话，开关由本脚本打开。
#   2. 这一面要跑**两个 Spark 版本**（3.5 与 4.1），两批用例守的是同一份契约，
#      但类路径上的 connector 是两个不同构件；混进 e2e-spark-sql.sh 会让它变成
#      「按版本循环」的脚本，而那个脚本的一个类只该有一个含义。
#
# 用法：
#   ./scripts/e2e-lance-spark.sh              # 默认端口 18081，3.5 与 4.1 各跑一遍
#   SPARK_VERSIONS=4.1 ./scripts/e2e-lance-spark.sh   # 只跑 4.1
#   SPARK_VERSIONS="4.1 3.5" ./scripts/e2e-lance-spark.sh   # 调换顺序
#   PORT=19091 ./scripts/e2e-lance-spark.sh   # 换端口
#   KEEP=1 ./scripts/e2e-lance-spark.sh       # 保留服务端不退出，便于手工继续调试
#
# 前置：
#   - 已执行过 mvn -o install -DskipTests（需要 lakehouse-rest-server 的 jar）
#   - 两个版本的 bundle 已在本地仓库（0.8.0 的 3.5 与 4.1 各约 300 MB，
#     首次联网构建会自己下；离线跑之前先 mvn dependency:get 一次）
#
# 说明：
#   - 服务端只起一次，两批用例共用（LANCE catalog 由第一批建，第二批按 409 幂等复用）；
#   - 服务端用内存 H2（显式指定 h2 profile，因为默认数据源已是 MySQL），关闭即丢数据，
#     因此脚本不依赖已有状态，也不要求本机有 MySQL；
#   - 令牌 root 是服务管理员；limited 映射到没有任何授权的 e2e_limited，
#     用来验证 Lance 面确实被鉴权挡住（`LanceCatalogLiveTests.limitedTokenIsDenied`）；
#   - LANCE catalog 由用例经管理 API 建（这一面没有 `/v1/config`，客户端撞不出来），
#     warehouse 是 JVM 临时目录，落在它自己的 allowedLocations 里。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${PORT:-18081}
# 跑哪些 Spark 版本。用例在 shared/ 下共用，两个模块编的是同一份源码；
# 分开跑是因为各自的 connector 构件不同（3.5 → -3.5_2.12，4.1 → -4.1_2.13）。
SPARK_VERSIONS=${SPARK_VERSIONS:-3.5 4.1}
BASE=http://127.0.0.1:$PORT
JAR=$ROOT/lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar
LOG=$(mktemp -t lakehouse-rest-lance-e2e.XXXXXX.log)
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
  --lakehouse.rest.auth.enabled=true \
  --lakehouse.rest.auth.tokens[0]=root \
  --lakehouse.rest.auth.tokens[1]=limited \
  --lakehouse.rest.auth.token-principals.limited=e2e_limited \
  --lakehouse.rest.authorization.enabled=true \
  --lakehouse.rest.authorization.service-admins[0]=root \
  --lakehouse.rest.authorization.bootstrap-principal=root \
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

# 等健康：管理 API 要能应答（带令牌——这个实例开了鉴权，匿名会 401）
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

echo "== 校验 Lance 面的端点台账与文档一致 =="
# 静态检查，不需要服务端；放在这里是为了让一个命令覆盖这一面的全部验收。
# 只读规范副本、控制器映射与幂等常量，没有第三方依赖（不需要 PyYAML）。
# 依次尝试 $PYTHON、python3。
DOC_VERIFIER=$ROOT/scripts/verify-lance-endpoints.py
for candidate in "${PYTHON:-}" python3; do
  [ -n "$candidate" ] || continue
  if command -v "$candidate" >/dev/null 2>&1; then
    "$candidate" "$DOC_VERIFIER" || exit 1
    DOC_VERIFIED=1
    break
  fi
done
if [ -z "${DOC_VERIFIED:-}" ]; then
  echo "跳过台账校验：没有找到可用的 python3"
  echo "  或指定：PYTHON=/path/to/python3 scripts/e2e-lance-spark.sh"
fi

echo "== 跑 Lance 端到端验收 =="
# 一个类、一个会话：`LanceCatalogLiveTests` 的 7 个用例覆盖建命名空间、建表 + 写入读回、
# SHOW TABLES 的完整 id、OPTIMIZE / VACUUM 两条维护语句、schema 不存在时报错、
# declare 之后「只有登记」那一态、以及只读令牌被拒。
# 每个版本的 4 个 `-De2e.*` 里前两个是地址与身份，后两个打开 Lance：
#   e2e.lance.enabled        装 Lance catalog + parser 扩展（默认不装，见脚本头注释）
#   e2e.lance.limited-token  无授权令牌，用来验 403
#
# 一个版本跑失败就继续跑另一个：两批要的是同一份契约，把两边的失败一起报出来
# 才知道「是这一版特有的，还是两版都错」——只跑第一个会把这个信息丢掉。
FAILED=""
for version in $SPARK_VERSIONS; do
  case "$version" in
    3.5|4.1) ;;
    *)
      echo "不认识的 Spark 版本：${version}（只支持 3.5 / 4.1）"
      exit 2
      ;;
  esac
  echo "-- Spark $version --"
  "$MVN" -o -pl ":lakehouse-rest-spark-$version" test \
    -Dtest=LanceCatalogLiveTests \
    -DfailIfNoTests=false \
    -De2e.management.url="$BASE/api/management/v1" \
    -De2e.management.token=root \
    -De2e.lance.enabled=true \
    -De2e.lance.limited-token=limited
  status=$?
  if [ "$status" != "0" ]; then
    FAILED="$FAILED $version"
  fi
done

if [ -n "$FAILED" ]; then
  echo "== 验收失败：Spark${FAILED}；服务端日志尾部 =="
  tail -30 "$LOG"
  exit 1
fi
# 变量名后面紧跟中文标点时一定要写 ${...}：bash 在 UTF-8 locale 下会把全角字符
# 当成变量名的一部分，于是 `${SPARK_VERSIONS}）` 会被读成「未定义变量」。
echo "== 两个版本都通过（Spark: ${SPARK_VERSIONS}） =="
exit 0
