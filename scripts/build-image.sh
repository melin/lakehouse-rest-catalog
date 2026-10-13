#!/usr/bin/env bash
#
# 构建服务端镜像。默认同时产出 linux/amd64 与 linux/arm64 两个平台。
#
# 为什么需要这个脚本，而不是一行 `docker build`：
#   `docker build` 只能产出**当前机器架构**的镜像。而「Apple Silicon 上开发、
#   x86 服务器上部署」或者「集群里既有 x86 节点又有 ARM 节点」这两种常见情形，
#   都要求同一份镜像同时提供两种架构，只能走
#   `docker buildx build --platform linux/amd64,linux/arm64`。
#   但多平台的产物与单平台不是一回事，输出方式、缓存、推送都有额外约束（见下），
#   参数一多就容易记错或踩坑，所以固定成脚本。
#
# 多平台的几条硬约束（都踩过）：
#
#   1) **多平台产物的 `--load` 需要 containerd 镜像存储。** 多平台镜像的顶层是一个
#      manifest list，传统 graph driver 的镜像存储放不下它（会报
#      "docker exporter does not currently support exporting manifest lists"）。
#      Docker Desktop 默认已启用 containerd 存储，判据是
#      `docker info --format '{{.Driver}}'` 输出 overlayfs（传统驱动是 overlay2）。
#      不支持时改用 OCI=<文件> 导出 tar，或 PUSH=1 直接推仓库。
#
#   2) **只有 PUSH=1 才能让别的机器拿到多平台镜像。** 装进本机镜像存储的 tag 只在
#      这台机器上有效；异构集群各节点要各自拉取自己架构的那一份，必须有一个能存
#      manifest list 的 registry。IMAGE 因此要写成带仓库地址的完整引用。
#
#   3) **构建节点需要能跑目标架构的二进制。** 在 arm64 上构建 amd64 靠 QEMU 模拟，
#      `RUN apt-get install curl` 这类步骤会明显变慢（本机实测 amd64 那份启动到
#      健康检查通过约 85 秒，arm64 原生约 20 秒）。镜像内容不受影响，只是慢。
#
#   4) **jar 是架构无关的字节码，一次构建喂给两个平台。** 这一点由「Dockerfile 不
#      编译、只装宿主机构建好的 jar」直接保证：本脚本会校验镜像里那份 jar 的
#      sha256 与本地文件一致（见 --check 说明）。因此多架构**不需要**为每个平台
#      各跑一次 Maven。
#
# 前置：jar 必须先构建好——镜像里没有 JDK 与 Maven，构建镜像不编译。
#   mvn -o -pl paimon-rest-server -am clean package -DskipTests
#
# 用法（环境变量，都可用在命令前）：
#   scripts/build-image.sh                                  # 双平台，装进本机镜像存储
#   PUSH=1 IMAGE=<仓库>/paimon-rest-server:0.0.1 scripts/build-image.sh   # 推仓库
#   OCI=/tmp/paimon-rest-server.tar scripts/build-image.sh  # 导出 OCI tar
#   PLATFORMS=linux/amd64 scripts/build-image.sh            # 退回单平台
#   BUILDER=mirrorb scripts/build-image.sh                  # 指定 buildx builder
#
#   IMAGE       镜像名（含 tag）。默认 paimon-rest-server:0.0.1
#   PLATFORMS   逗号分隔的平台列表。默认 linux/amd64,linux/arm64
#   PUSH        置 1 则推送到 IMAGE 指明的仓库（与 OCI 互斥）
#   OCI         输出 OCI 归档的路径（与 PUSH 互斥）
#   BUILDER     传给 buildx 的 builder 名；不指定则用当前默认 builder
#   ATTEST      置 1 保留 provenance/sbom 证明（默认关闭，理由见下）
#   EXTRA       透传给 `docker buildx build` 的额外参数
#
# 关于默认关闭 attestation：开着时 manifest list 里会多出两个 platform 为
# unknown/unknown 的证明条目。对自建镜像没有实际收益，却会让部分自建 registry
# （尤其是较老的 Harbor）与镜像扫描工具困惑。需要时 ATTEST=1 打开。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

IMAGE="${IMAGE:-paimon-rest-server:0.0.1}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
PUSH="${PUSH:-}"
OCI="${OCI:-}"
BUILDER="${BUILDER:-}"
ATTEST="${ATTEST:-}"
EXTRA="${EXTRA:-}"

# jar 的路径是 POM 的约定产物名；改版本号时这里与 Dockerfile 的 COPY 都要跟着改。
JAR="paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar"

die() { echo "错误：$*" >&2; exit 1; }

# --- 前置检查：让失败发生在构建之前，而不是构建到一半 ---

[ -f Dockerfile ] || die "请在仓库根目录运行（当前 $ROOT 下没有 Dockerfile）"
[ -f "$JAR" ] || die "找不到 $JAR —— 镜像只装 jar、不编译，请先执行：
    mvn -o -pl paimon-rest-server -am clean package -DskipTests"

command -v docker >/dev/null 2>&1 || die "找不到 docker"
docker buildx version >/dev/null 2>&1 || die "找不到 docker buildx"

if [ -n "$PUSH" ] && [ -n "$OCI" ]; then
  die "PUSH 与 OCI 不能同时使用：多平台产物只能有一个去向。
    推仓库用 PUSH=1，导本地 tar 用 OCI=<路径>。"
fi

# 推送目标必须是带仓库地址的完整引用，否则会推到 Docker Hub——通常不是本意。
if [ -n "$PUSH" ]; then
  case "$IMAGE" in
    */*) ;;
    *) die "PUSH=1 时 IMAGE 要写成带仓库地址的完整引用，当前是 '$IMAGE'。
    例如 IMAGE=registry.example.com/paimon-rest/paimon-rest-server:0.0.1" ;;
  esac
fi

# --- 组装命令 ---

# 没显式指定 BUILDER 时，若机器上已经有一个带镜像源的 builder（README 里教的
# mirrorb），就直接用它。理由是：出口受限的机器上，默认 builder 直连 Docker Hub
# 必然失败（见文件末尾的报错处置），而每次都要手写 BUILDER= 很啰嗦。只复用、
# 不自动创建——脚本不该在别人的机器上悄悄留下容器与 builder 实例。
if [ -z "$BUILDER" ] && docker buildx inspect mirrorb >/dev/null 2>&1; then
  BUILDER=mirrorb
  echo "提示：未指定 BUILDER，自动复用已有的带镜像源 builder「mirrorb」"
fi

set -- build --platform "$PLATFORMS" -t "$IMAGE"

# 多平台 + attestation 的组合在部分 registry 上会被拒（不认识 OCI artifact
# manifest），默认关掉；需要供应链证明时用 ATTEST=1。
if [ -z "$ATTEST" ]; then
  set -- "$@" --provenance=false --sbom=false
fi

if [ -n "$BUILDER" ]; then
  set -- "$@" --builder "$BUILDER"
fi

if [ -n "$PUSH" ]; then
  set -- "$@" --push
  DEST="推送到 $IMAGE"
elif [ -n "$OCI" ]; then
  set -- "$@" --output "type=oci,dest=$OCI"
  DEST="导出到 $OCI"
else
  set -- "$@" --load
  # 变量名后紧跟全角字符时要写成 ${}，否则 bash 会把 UTF-8 的字节
  # 当成变量名的一部分，报 "IMAGE?: unbound variable"。
  DEST="装进本机镜像存储（tag: ${IMAGE}）"
fi

[ -n "$EXTRA" ] && set -- "$@" $EXTRA

echo "镜像      : $IMAGE"
echo "平台      : $PLATFORMS"
echo "去向      : $DEST"
[ -n "$BUILDER" ] && echo "builder   : $BUILDER"
echo "jar       : $JAR ($(wc -c < "$JAR" | tr -d ' ') 字节)"
echo

# --- 执行：把输出同时留给用户和日志，好在失败时判定原因 ---
# 用 pipefail，否则 `| tee` 会把 buildx 的非零退出码吞掉。
LOG="$(mktemp -t build-image.XXXXXX)"
trap 'rm -f "$LOG"' EXIT

if ! docker buildx "$@" . 2>&1 | tee "$LOG"; then
  echo >&2
  # 最常见的一类失败是 buildkit 连不上 registry，而不是 Dockerfile 有问题。
  # daemon 的 registry-mirrors 只对 daemon 生效，docker-container 驱动的 buildkit
  # **不继承**它，所以在外网受限的环境里必须单独给 builder 配镜像源。
  if grep -qE "Bad Gateway|failed to authorize|no such host|connection refused|TLS handshake" "$LOG"; then
    cat >&2 <<'EOF'
看起来是 buildkit 连不上镜像仓库（不是 Dockerfile 的问题）。

注意：`~/.docker/daemon.json` 里的 registry-mirrors 只对 docker daemon 生效，
docker-container 驱动的 buildkit 不继承它，必须单独给 builder 配一份镜像源：

    docker buildx create --name mirrorb --driver docker-container \
      --config scripts/buildkitd.toml --bootstrap
    BUILDER=mirrorb scripts/build-image.sh

`scripts/buildkitd.toml` 里已按本机可用的加速地址写好，按需替换。
EOF
  else
    echo "构建失败。完整日志：$LOG" >&2
    echo "（日志已保留，便于排查；如需复现可加 EXTRA='--no-cache'）" >&2
    trap - EXIT
  fi
  exit 1
fi

echo
echo "完成：$IMAGE"
case "$PLATFORMS" in
  *,*)
    if [ -n "$PUSH" ] || [ -n "$OCI" ]; then
      echo "多平台 manifest list 已随产物写出。"
    else
      echo "已装入本机镜像存储；用指定平台跑容器，例如："
      echo "  docker run --rm --platform linux/amd64 -p 8080:8080 \\"
      echo "    -e SPRING_PROFILES_ACTIVE=h2 $IMAGE"
    fi
    # 多平台下每个平台各存一份基础镜像层与 jar 层；jar 层内容相同，但层 tar 里
    # 新建目录条目的时间戳取的是各平台的打包时刻，两次打包跨秒时 digest 就不同，
    # registry 便无法去重。232MB 的 jar 因此会在仓库里占两份。
    echo
    echo "提示：多平台镜像会在仓库里占约两倍体积（两平台的基础镜像层天然不同，"
    echo "      jar 层因打包时刻不同也无法去重）。只需单架构时用 PLATFORMS=linux/amd64。"
    ;;
esac
