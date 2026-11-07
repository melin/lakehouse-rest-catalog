#!/bin/bash
# 跑服务端（连带 core / storage / 三个协议面）的测试。
#
# 为什么不直接 `mvn test`：
#   server 模块在 generate-resources 绑了 frontend-maven-plugin（npm ci + vite build），
#   于是 `mvn test` 也会先把控制台整套构建一遍。改后端时这是几十秒的纯浪费，
#   而在**受限沙箱**里它会直接失败而不是变慢——`npm ci` 的做法是把 node_modules
#   整棵删掉再重装，沙箱的 safe-delete 钩子拒绝「一次删超过 100 个文件」：
#
#     npm error [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED]
#       {"count":960,"threshold":100,"scope":"turn",
#        "targets":["…/lakehouse-rest-console/node_modules/.puppeteer-core-hSo10uMg"]}
#     → Failed to execute goal …frontend-maven-plugin…:npm (npm-ci): Process exited with an error: 1
#
#   这条错误长得像「代码坏了」，实际是环境限制：钩子不在仓库里，在宿主 CLI 里
#   （WorkBuddy.app 的 cli/vendor/shim/node-safe-delete-shim.cjs）。普通终端没有它，
#   所以在 CI 与人工机器上 `mvn test` 一直是好的——这也是它容易被误诊的原因。
#   本脚本传 `-Dfrontend.skip=true` 跳过整组前端任务（开关语义见父 POM 的 frontend.skip），
#   于是测试只依赖 Java，与环境里删不删得动 node_modules 无关。
#
#   **打包不要沿用这个开关**：`mvn package` 默认构建前端是刻意的设计，
#   跳过之后 jar 里没有 /console 且不报错（AGENTS.md 第 8 节陷阱 1）。
#
# 用法：
#   ./scripts/test-server.sh                                   # 服务端全量（约 20 分钟）
#   ./scripts/test-server.sh -Dtest=ScanCallbackAuthTests      # 单跑一个类
#   ./scripts/test-server.sh -Dtest='Sensitivity*Test'         # 通配
#   ONLINE=1 ./scripts/test-server.sh                          # 不走 -o（本地仓库缺件时）
#   FRONTEND=1 ./scripts/test-server.sh                        # 连前端一起构建（要能删 node_modules）
#   MVN_ARGS=-DskipITs ./scripts/test-server.sh                # 追加任意 Maven 参数
#
# 两个兜底环境变量的来历：
#   AWS_ENDPOINT_URL=http://127.0.0.1:1  让 AWS SDK v2 的连接**立刻**失败而不是去连真 AWS。
#     注意它挡不住 Hadoop S3A（那是另一套凭据链），所以 StaticCredentialApiTests
#     仍然固定花 ~12 分钟——既有现象，不是卡死。
#   JAVA_HOME                            未设置时优先用 jdk-21（仓库基准），找不到就交给 mvn 自己解析。
#
# 退出码：mvn 的退出码（0 = 全绿）。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# 与其它脚本一致：用系统的 mvn（本仓库没有 Maven Wrapper，见 AGENTS.md）。
MVN=${MVN:-mvn}
MVN_ARGS=${MVN_ARGS:-}
ONLINE=${ONLINE:-0}
FRONTEND=${FRONTEND:-0}
LOG=${LOG:-$ROOT/lakehouse-rest-spring-parent/lakehouse-rest-server/target/test-run.log}

if [ -z "${JAVA_HOME:-}" ] && [ -d /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
fi
export AWS_ENDPOINT_URL=${AWS_ENDPOINT_URL:-http://127.0.0.1:1}

# -am 不是可选项：不带它，server 拿的是本地仓库里那份**上次 install 的 core**，
# 改了 core 之后的表现是 NoSuchMethodError 或者「测试跑了旧逻辑」（见 AGENTS.md 第 8 节）。
FLAGS="-pl :lakehouse-rest-server -am"
[ "$ONLINE" = "1" ] || FLAGS="-o $FLAGS"
[ "$FRONTEND" = "1" ] || FLAGS="$FLAGS -Dfrontend.skip=true"

echo "== 服务端测试 =="
echo "   JAVA_HOME=$JAVA_HOME"
echo "   前端构建：$([ "$FRONTEND" = "1" ] && echo 是 || echo 跳过（-Dfrontend.skip=true）)"
echo "   日志：$LOG"
mkdir -p "$(dirname "$LOG")"

cd "$ROOT" || exit 1
START=$(date +%s)
# 退出码写文件，不读 PIPESTATUS：`set -u` 下读一个没被填充的 PIPESTATUS 会让脚本自己
# 在赋值那行炸掉（现象是「构建失败、日志里却没有任何结论」，与「测试失败」不可区分），
# 而 `pipefail` 又会把 tee 的状态混进来。e2e 脚本里同一个坑也是这么绕开的。
STATUS_FILE=$(mktemp -t test-server.XXXXXX)
trap 'rm -f "$STATUS_FILE"' EXIT
set +e
# shellcheck disable=SC2086 # FLAGS 与 MVN_ARGS 就是拆成多个参数的
{ "$MVN" $FLAGS test $MVN_ARGS "$@"; echo $? >"$STATUS_FILE"; } 2>&1 | tee "$LOG"
CODE=$(cat "$STATUS_FILE")
set -e
ELAPSED=$(( $(date +%s) - START ))

# Surefire 的汇总行分散在多个模块里，逐个模块报一遍总量，最后给一行总账。
echo
# 变量名后面紧跟全角括号时必须写 ${VAR}：bash 3.2 会把 UTF-8 字节当成名字的一部分，
# 于是去找一个叫 `CODE）` 的变量（报 `CODE?: unbound variable`，看着像自己写错了名字）。
echo "== 结果（耗时 $((ELAPSED / 60)) 分 $((ELAPSED % 60)) 秒，退出码 ${CODE}）=="
# grep 用的模式要能容忍 surefire 的两种格式：
# 单模块 `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` 与
# 汇总 `[INFO] Tests run: 938, Failures: 0, ...`——两者都在行尾出现同样的字段，取最后一处即可。
TOTALS=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOG" || true)
if [ -n "$TOTALS" ]; then
  printf '%s\n' "$TOTALS" | tail -3
else
  echo "  没解析到汇总行，直接看 ${LOG}（失败时先找 'BUILD FAILURE' 与 '<<< FAILURE!' ）"
fi
exit "$CODE"
