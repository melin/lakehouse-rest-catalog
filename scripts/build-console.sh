#!/bin/bash
# 构建 Web 管理控制台前端，并把产物落到服务端的 classpath 里。
#
# 为什么要有这个脚本：控制台的构建产物**不入库**，但它必须出现在服务端 jar 里
# （lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console 是
# classpath 资源的落点）。也就是说「改了前端」与「打了个能用的 jar」之间隔着一道
# 手工步骤，而漏掉它的表现是安静的——jar 里根本没有 /console，服务端不会抱怨。
# 本脚本把「装依赖 → 构建 → 校验产物与源码一致」串成一条命令，
# 最后一步由 verify-console.py 机械完成。任何要出 jar 的路径（本地打包、build-image.sh）
# 都应当先跑它。
#
# 用法：
#   ./scripts/build-console.sh              # 依赖已就绪时直接构建
#   FRESH=1 ./scripts/build-console.sh      # 强制重新 npm ci 后再构建
#   ./scripts/build-console.sh --no-verify  # 只构建，跳过一致性校验
#   PYTHON=/path/to/python3 ./scripts/build-console.sh   # 指定装了 PyYAML 的解释器
#
# 前置：Node 20+（工程用 Vite 8 / Vue 3，node_modules 已 gitignore）。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CONSOLE="$ROOT/lakehouse-rest-console"
OUT="$ROOT/lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console"
# 不经 PATH 里的 python3：宿主机常同时存在多个解释器，装 PyYAML 的未必是默认那个
PYTHON=${PYTHON:-python3}
VERIFY=1
for arg in "$@"; do
  case "$arg" in
    --no-verify) VERIFY=0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

if ! command -v npm >/dev/null 2>&1; then
  cat >&2 <<'MSG'
找不到 npm。控制台前端需要 Node 20+：
  - 宿主机已装 Node 时，确认 npm 在 PATH 里；
  - 没有 Node 就拿不到控制台产物——它不入库（见仓库根 .gitignore），
    没有它 `mvn package` 打出的 jar 里不会有 /console，服务端不会为此报错，
    只是浏览器打开 /console/ 会 404。要一个现成可用的服务端，请用发布好的
    jar 或镜像（Dockerfile 只装现成的 jar，不涉及 Node）。
MSG
  exit 1
fi

cd "$CONSOLE" || exit 1
echo "== 构建控制台前端（$(node -v) / npm $(npm -v)）=="

# npm ci 会比 npm install 慢，但严格按 package-lock.json 落依赖，
# 保证「谁构建都得到同一份产物」。只在缺依赖或显式 FRESH=1 时才装，
# 因为绝大多数改动只是改一两个 .vue 文件。
if [ "${FRESH:-0}" = "1" ] || [ ! -d node_modules ]; then
  if [ -f package-lock.json ]; then
    echo "-- 安装依赖（npm ci，按 lock 文件精确还原）"
    npm ci
  else
    echo "-- 安装依赖（npm install，未找到 package-lock.json）"
    npm install
  fi
else
  echo "-- 复用已有 node_modules（要强制重装用 FRESH=1）"
fi

npm run build

if [ ! -f "$OUT/index.html" ]; then
  echo "构建结束但没有产物：$OUT/index.html 不存在" >&2
  exit 1
fi

# 产物大小是判断「有没有把不该打进来的东西打进来」最快的信号。
# 主 chunk 里含 Element Plus 全量组件与图标，1MB 上下是预期的；
# 突然翻倍通常意味着某个依赖被误引入，而不是正常的代码增长。
ENTRY=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "$OUT/index.html" | head -n1)
if [ -n "$ENTRY" ]; then
  echo "-- 入口 chunk $ENTRY $(du -h "$OUT/$ENTRY" | cut -f1)"
fi
echo "-- 产物共 $(find "$OUT" -type f | wc -l | tr -d ' ') 个文件，$(du -sh "$OUT" | cut -f1)"

if [ "$VERIFY" = "1" ]; then
  if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "跳过校验：找不到 $PYTHON" >&2
  else
    echo
    # 校验脚本自己会打印逐项结果；退出码非 0 时 set -e 会让本脚本同样失败，
    # 这样「改了源码忘记重新构建」就不会悄悄溜过去
    "$PYTHON" "$ROOT/scripts/verify-console.py"
  fi
fi

echo
echo "完成。产物已就位：$OUT"
echo "注意：产物不入库（.gitignore 排除，见 verify-console.py 第 6 组），"
echo "      所以每次要出 jar 之前都要先跑本脚本——mvn package 不会替你构建前端，"
echo "      而漏掉这一步打出的 jar 里没有 /console，构建过程不会报任何错。"
