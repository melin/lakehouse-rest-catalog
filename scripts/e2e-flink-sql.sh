#!/bin/bash
# Flink 通过 Lakehouse REST catalog 读写 Paimon / Iceberg 的端到端验收。
#
# 为什么需要它：模块里的用例都用真实 Flink + 真实连接器，但「谁来起服务端、
# 谁来给令牌、两个版本怎么排」不适合写进每个测试类。本脚本起一个真实的
# LakeHouse Catalog Server，按版本各跑一遍 Paimon 与 Iceberg 的
# 「建表 → 写入 → 读回 → 主键更新」链路。
#
# 用法：
#   ./scripts/e2e-flink-sql.sh                 # 默认端口 18081，跑完自动停服务端
#   PORT=19090 ./scripts/e2e-flink-sql.sh      # 换端口
#   KEEP=1 ./scripts/e2e-flink-sql.sh          # 保留服务端不退出，便于手工继续调试
#   ONLY=1.20 ./scripts/e2e-flink-sql.sh       # 只跑某个版本模块（1.20 / 2.3）
#
# 前置：
#   - 已执行过 mvn -o install -DskipTests（需要 lakehouse-rest-server 的 jar）；
#   - Flink / 两个连接器的构件已在本机 ~/.m2（首次跑请先去掉 -o 联网拉一次，
#     或直接 mvn -pl :lakehouse-rest-flink-1.20,:lakehouse-rest-flink-2.3 test 一次）。
#
# 说明：
#   - 服务端用内存 H2（显式指定 h2 profile，默认数据源已是 MySQL），关闭即丢数据；
#   - 令牌 root 即服务管理员；catalog 由用例自己经管理 API 建（FILE 仓库，落在临时目录），
#     所以脚本不依赖任何已有 catalog，也不要求本机有对象存储。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${PORT:-18081}
BASE=http://127.0.0.1:$PORT
JAR=$ROOT/lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar
LOG=$(mktemp -t lakehouse-rest-flink-e2e.XXXXXX.log)
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}
MVN=${MVN:-mvn}

if [ ! -f "$JAR" ]; then
  echo "缺少服务端 jar：$JAR"
  echo "请先执行：mvn -o install -DskipTests"
  exit 1
fi

echo "== 启动服务端（端口 ${PORT}，日志 ${LOG}） =="
# 方括号在 zsh 里会被当成通配符，参数一律加引号。
"$JAVA_HOME/bin/java" -jar "$JAR" \
  --spring.profiles.active=h2 \
  --server.port="$PORT" \
  --lakehouse.rest.auto-create-catalog=true \
  --lakehouse.rest.auth.enabled=true \
  '--lakehouse.rest.auth.tokens[0]=root' \
  --lakehouse.rest.authorization.enabled=true \
  '--lakehouse.rest.authorization.service-admins[0]=root' \
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

# 两个版本模块各自独立跑：同一个 JVM 里无法同时装两套 Flink（1.20 与 2.3 的
# Table 工厂字节码不兼容），所以是「两次 mvn test」，不是一次跑两个模块的用例。
case "${ONLY:-}" in
  1.20) MODULES=(":lakehouse-rest-flink-1.20") ;;
  2.3)  MODULES=(":lakehouse-rest-flink-2.3") ;;
  *)    MODULES=(":lakehouse-rest-flink-1.20" ":lakehouse-rest-flink-2.3") ;;
esac

STATUS=0
for module in "${MODULES[@]}"; do
  echo "== 跑 ${module#:} 的 Flink SQL 端到端验收 =="
  #   PaimonFlinkSqlTests  : 建 Paimon 主键表 → 写两行 → 再写同一主键 → 行数不变、
  #                          值被后写覆盖（主键更新/upsert 语义），并核对服务端存下 primaryKeys
  #   IcebergFlinkSqlTests : 建 Iceberg 表（format-version=2）→ 写三行 → 再写一批 →
  #                          读回行数与合计，并核对服务端存下 metadata-location / format-version
  "$MVN" -o -pl "$module" test \
    -Dtest=PaimonFlinkSqlTests,IcebergFlinkSqlTests \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -De2e.management.url="$BASE/api/management/v1" \
    -De2e.management.token=root
  code=$?
  if [ "$code" != "0" ]; then
    echo "== ${module#:} 验收失败；服务端日志尾部 =="
    tail -30 "$LOG"
    STATUS=$code
  fi
done

exit "$STATUS"
