#!/bin/bash
# 敏感识别「下推扫描」的真集群 e2e：服务端跑在集群里，扫描作业也跑在集群里。
#
# 为什么必须有这一条，而单测不够：
#   `SensitivityPushScanTests` 把 `ScanJobSubmitter` 换成了一个返回固定坐标的桩，
#   它证明的是「状态机与回调契约自洽」，证明不了下面这些——每一条都只在真环境成立：
#     1. 这份 kubeconfig 能不能建 Job（RBAC、TokenRequest 的 audience、CA）；
#     2. 扫描镜像能不能被节点拉起来（镜像里有没有内核 jar、Lance bundle 在不在
#        driver classpath 上）；
#     3. **Pod → 服务端**这个方向通不通（探活的第 5 步只验了反方向，注释里写明了）；
#     4. 鉴权打开时作业的回调会不会被 Bearer 拦成 401（本脚本第一次跑就撞上了，
#        修法在 `WebConfig` 的豁免清单，回归用例是 `ScanCallbackAuthTests`）；
#     5. 作业真的按 SCANNED 逐表回报，服务端的计数与 finding 落库对不对得上。
#
# 为什么扫描用 METADATA_ONLY：
#   这一次验证的是**链路**（提交 / 拉起 / 回调 / 收尾 / 取消），不是采样算法。
#   采样要读明文，需要一个两种 Pod 都能读到的仓库（对象存储或共享卷），
#   而扫描 Job 的报文里没有卷——把 e2e 架在「每个集群都恰好有 S3」上，
#   它就只能在写脚本的那台机器上跑。采样那条路径由 P1 的单测与
#   `scripts/e2e-*-spark.sh` 覆盖。
#   下推默认拒绝 METADATA_ONLY（400），所以要显式打开 `allow-metadata-on-spark`：
#   这一条本身也是「守卫还在」的证据。
#
# 前置：
#   - kubectl 指向一个可用集群，账号能建 namespace / role / deployment / job；
#   - 两份镜像，都由本仓库构建：
#       SERVER_IMAGE   根 Dockerfile 的产物（含本轮的 WebConfig 豁免）
#       SCANNER_IMAGE  lakehouse-rest-optimizer/Dockerfile 的产物
#                      （镜像里必须有 /opt/spark/jars/lakehouse-rest-scan-kernel.jar）
#   - 节点能拉到这两份镜像。私有仓库请自己建好 docker-registry Secret 并传
#     PULL_SECRET=<名字>；不传时脚本会尝试用本机 docker 凭据建一个。
#   - jq
#
# 用法：
#   SERVER_IMAGE=registry/x/lakehouse-rest-catalog:e2e \
#   SCANNER_IMAGE=registry/x/lakehouse-rest-optimizer:e2e \
#     ./scripts/e2e-sensitivity-scan-k8s.sh
#
#   KEEP=1 ...                 # 保留 namespace（排障用）
#   NAMESPACE=my-ns ...        # 换一个命名空间（默认 lakehouse-rest-scan-e2e）
#   PULL_SECRET=my-secret ...  # 用现成的拉取凭据
#   TIMEOUT=600 ...            # 等终态的上限（秒）
#
# 退出码：0 = 全部断言通过；1 = 有断言失败；2 = 前置不满足（没起服务端之前就退出）。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NS=${NAMESPACE:-lakehouse-rest-scan-e2e}
SERVER_IMAGE=${SERVER_IMAGE:-}
SCANNER_IMAGE=${SCANNER_IMAGE:-}
PULL_SECRET=${PULL_SECRET:-}
ADMIN_TOKEN=${ADMIN_TOKEN:-root}
PORT=${PORT:-18080}
TIMEOUT=${TIMEOUT:-600}
KEEP=${KEEP:-0}
BASE=http://127.0.0.1:$PORT
M=/api/management/v1

PASSED=0
FAILED=0
PF_PID=

pass() { PASSED=$((PASSED + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '  \033[31m✗\033[0m %s\n' "$1"; }
say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
die() { printf '\033[31m前置不满足：%s\033[0m\n' "$1"; exit 2; }

assert_eq() { # 描述 实际 期望
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1（实际：$2 / 期望：$3）"; fi
}
assert_contains() { # 描述 文本 片段
  case "$2" in *"$3"*) pass "$1" ;; *) fail "$1（没找到：$3）" ;; esac
}
assert_absent() { # 描述 文本 片段
  case "$2" in *"$3"*) fail "$1（不该出现：$3）" ;; *) pass "$1" ;; esac
}

cleanup() {
  [ -n "$PF_PID" ] && { kill "$PF_PID" 2>/dev/null; wait "$PF_PID" 2>/dev/null; }
  if [ "$KEEP" = "1" ]; then
    printf '\n保留命名空间 %s（KEEP=1）；清理：kubectl delete namespace %s\n' "$NS" "$NS"
  else
    kubectl delete namespace "$NS" --wait=false >/dev/null 2>&1
    printf '\n已删除命名空间 %s\n' "$NS"
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------ 前置
command -v kubectl >/dev/null || die "没有 kubectl"
command -v jq >/dev/null || die "没有 jq"
[ -n "$SERVER_IMAGE" ] || die "要指定 SERVER_IMAGE（根 Dockerfile 的产物）"
[ -n "$SCANNER_IMAGE" ] || die "要指定 SCANNER_IMAGE（lakehouse-rest-optimizer 的产物）"
kubectl version >/dev/null 2>&1 || die "kubectl 连不上集群：$(kubectl version 2>&1 | head -2)"

# 状态码写进文件而不是变量：`x=$(api ...)` 是子 shell，变量赋值回不到父 shell。
# 第一次跑就栽在这上面——建单明明回了 202，断言读到的却是上一次请求的 200。
STATUS_FILE=$(mktemp -t e2e-status.XXXXXX)
api() { # 方法 路径 [body] -> 打印 body；状态码写进 $STATUS_FILE
  local method=$1 path=$2 body=${3:-}
  local tmp
  tmp=$(mktemp)
  if [ -n "$body" ]; then
    curl -s -o "$tmp" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -d "$body" "$BASE$path" > "$STATUS_FILE"
  else
    curl -s -o "$tmp" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE$path" > "$STATUS_FILE"
  fi
  cat "$tmp"
  rm -f "$tmp"
}
http_code() { cat "$STATUS_FILE"; }

# ------------------------------------------------------------------ 1. 命名空间与 RBAC
say "1. 命名空间、ServiceAccount 与最小 RBAC"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# 拉取凭据：私有仓库要有它，否则探活第 4 步只给 WARN、而 WARN 不算可派发
if [ -z "$PULL_SECRET" ]; then
  REGISTRY_HOST=$(printf '%s' "$SCANNER_IMAGE" | cut -d/ -f1)
  for helper in docker-credential-desktop docker-credential-osxkeychain docker-credential-wincred; do
    command -v "$helper" >/dev/null || continue
    cred=$(printf '%s' "$REGISTRY_HOST" | "$helper" get 2>/dev/null) || continue
    user=$(printf '%s' "$cred" | jq -r '.Username // empty')
    secret=$(printf '%s' "$cred" | jq -r '.Secret // empty')
    [ -n "$user" ] && [ -n "$secret" ] || continue
    auth=$(printf '%s:%s' "$user" "$secret" | base64 | tr -d '\n')
    dc=$(jq -nc --arg h "$REGISTRY_HOST" --arg u "$user" --arg p "$secret" --arg a "$auth" \
      '{auths: {($h): {username: $u, password: $p, auth: $a}}}')
    kubectl -n "$NS" create secret generic e2e-registry \
      --from-literal=.dockerconfigjson="$dc" \
      --type=kubernetes.io/dockerconfigjson --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    PULL_SECRET=e2e-registry
    printf '  用本机 docker 凭据建了拉取 Secret %s（%s）\n' "$PULL_SECRET" "$REGISTRY_HOST"
    break
  done
fi
[ -n "$PULL_SECRET" ] && printf '  拉取凭据：%s\n' "$PULL_SECRET"

cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: v1
kind: ServiceAccount
metadata: {name: scan-runner, namespace: $NS}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: scan-runner, namespace: $NS}
rules:
  # jobs：提交 / 探活 / 取消（探活第 3 步逐条自检这四个动词）
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["create", "get", "list", "watch", "delete"]
  # pods 与 pods/log：收尾排障（第 3 步把它们记为 WARN 而不是 FAIL）
  - apiGroups: [""]
    resources: ["pods", "pods/log"]
    verbs: ["get", "list"]
  # serviceaccounts：探活第 4 步会读一次它，确认 SA 真的存在
  - apiGroups: [""]
    resources: ["serviceaccounts"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: scan-runner, namespace: $NS}
subjects:
  - {kind: ServiceAccount, name: scan-runner, namespace: $NS}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: scan-runner}
EOF
pass "namespace / ServiceAccount / Role 就绪"

kubectl -n "$NS" get cm kube-root-ca.crt -o jsonpath='{.data.ca\.crt}' | base64 | tr -d '\n' > /tmp/e2e-ca.b64
CA_B64=$(cat /tmp/e2e-ca.b64)
SA_TOKEN=$(kubectl -n "$NS" create token scan-runner --duration=2h)
KUBECONFIG_FILE=$(mktemp -t e2e-kubeconfig.XXXXXX)
cat > "$KUBECONFIG_FILE" <<EOF
apiVersion: v1
kind: Config
clusters:
  - name: e2e
    cluster: {server: "https://kubernetes.default.svc", certificate-authority-data: "$CA_B64"}
contexts:
  - name: e2e
    context: {cluster: e2e, user: e2e, namespace: $NS}
current-context: e2e
users:
  - name: e2e
    user: {token: "$SA_TOKEN"}
EOF
assert_contains "kubeconfig 里是 in-cluster 地址" "$(head -5 "$KUBECONFIG_FILE")" "kubernetes.default.svc"

# ------------------------------------------------------------------ 2. 服务端
say "2. 服务端（H2 内存库，鉴权打开）"
PULL_SECRET_BLOCK=""
if [ -n "$PULL_SECRET" ]; then
  PULL_SECRET_BLOCK="      imagePullSecrets:
        - name: $PULL_SECRET"
fi
cat <<EOF | kubectl apply -f - >/dev/null
apiVersion: apps/v1
kind: Deployment
metadata: {name: lakehouse-rest, namespace: $NS}
spec:
  replicas: 1
  selector: {matchLabels: {app: lakehouse-rest}}
  template:
    metadata: {labels: {app: lakehouse-rest}}
    spec:
$PULL_SECRET_BLOCK
      containers:
        - name: server
          image: $SERVER_IMAGE
          # 必须是 Always：同一个 tag 重推之后，kubelet 认的还是第一次拉下来的那些层。
          # 本轮踩过——修完回调 401 重跑仍然 401，直到把策略改成 Always。
          imagePullPolicy: Always
          args:
            # 鉴权打开是**这条 e2e 的价值所在**：回调豁免的洞只在 auth.enabled=true 时出现
            - --lakehouse.rest.auth.enabled=true
            - --lakehouse.rest.auth.tokens[0]=$ADMIN_TOKEN
            # 下推默认拒绝元数据档；这里显式打开，见文件头
            - --lakehouse.rest.sensitivity.scan.allow-metadata-on-spark=true
            # 提交节拍调快：默认 10 秒会让整条链路看起来「卡住了」
            - --lakehouse.rest.sensitivity.schedule.dispatch-interval=PT3S
          env:
            - {name: SPRING_PROFILES_ACTIVE, value: h2}
          ports: [{containerPort: 8080}]
          volumeMounts: [{name: wh, mountPath: /tmp/wh}]
          readinessProbe:
            exec:
              command: ["/bin/sh", "-c", "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/config | grep -Eq '200|401|403'"]
            periodSeconds: 3
            failureThreshold: 60
      volumes:
        - name: wh
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata: {name: lakehouse-rest, namespace: $NS}
spec:
  selector: {app: lakehouse-rest}
  ports: [{port: 8080, targetPort: 8080}]
EOF

kubectl -n "$NS" rollout status deploy/lakehouse-rest --timeout=240s >/dev/null 2>&1
if [ $? -ne 0 ]; then
  fail "服务端 240 秒内没就绪"
  kubectl -n "$NS" logs deploy/lakehouse-rest --tail=30 2>&1 | tail -20
  exit 1
fi
pass "服务端就绪（镜像 ${SERVER_IMAGE}）"

kubectl -n "$NS" port-forward svc/lakehouse-rest "$PORT":8080 >/dev/null 2>&1 &
PF_PID=$!
for _ in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v1/config" || true)
  [ "$code" = "401" ] || [ "$code" = "200" ] && break
  sleep 1
done
assert_eq "服务端可访问且鉴权生效" "$code" "401"

# ------------------------------------------------------------------ 3. catalog / 库 / 表
say "3. catalog、库与两张表"
api POST "$M/catalogs" '{"catalog":{"type":"INTERNAL","catalogFormat":"PAIMON","name":"e2e",
  "properties":{},"storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/wh"]}}}' >/dev/null
assert_eq "建 catalog e2e" "$(http_code)" "201"

api POST "/v1/e2e/databases" '{"name":"hr"}' >/dev/null
assert_eq "建库 hr" "$(http_code)" "200"

api POST "/v1/e2e/databases/hr/tables" '{"identifier":{"database":"hr","object":"user_profile"},
  "name":"user_profile","schema":{"type":"struct","fields":[
    {"id":1,"name":"id","type":"bigint","required":true},
    {"id":2,"name":"id_card_no","type":"string","required":false},
    {"id":3,"name":"mobile","type":"string","required":false}]},
  "partitionKeys":[],"primaryKeys":[],"properties":{}}' >/dev/null
assert_eq "建表 hr.user_profile" "$(http_code)" "200"

api POST "/v1/e2e/databases/hr/tables" '{"identifier":{"database":"hr","object":"orders"},
  "name":"orders","schema":{"type":"struct","fields":[
    {"id":1,"name":"id","type":"bigint","required":true},
    {"id":2,"name":"amount","type":"decimal(12,2)","required":false}]},
  "partitionKeys":[],"primaryKeys":[],"properties":{}}' >/dev/null
assert_eq "建表 hr.orders" "$(http_code)" "200"

# ------------------------------------------------------------------ 4. 执行集群与探活
say "4. 执行集群配置与五步探活"
PUT_BODY=$(jq -nc --rawfile kube "$KUBECONFIG_FILE" --arg ns "$NS" --arg image "$SCANNER_IMAGE" \
  --arg pull "$PULL_SECRET" '{
    enabled: true, clusterType: "SELF_HOSTED", kubeconfig: $kube,
    namespace: $ns, image: $image, imagePullPolicy: "Always",
    serviceAccount: "scan-runner", callbackBaseUrl: "http://lakehouse-rest:8080",
    maxConcurrentJobs: 1, maxScanTablesPerJob: 20, scanJobTimeoutSeconds: 600,
    imagePullSecrets: $pull, entityVersion: 0}')
api PUT "$M/catalogs/e2e/execution-cluster" "$PUT_BODY" >/dev/null
assert_eq "写入执行集群配置" "$(http_code)" "200"

probe=$(api POST "$M/catalogs/e2e/execution-cluster/probe" '{}')
assert_eq "探活结论 PASS（五步全过）" "$(printf '%s' "$probe" | jq -r .status)" "PASS"
printf '%s' "$probe" | jq -r '.steps[] | "    \(.step) \(.title)：\(.status) — \(.summary)"' | head -6
# 第 5 步只验了「服务端 → 回调地址」这一个方向，真正的反方向由后面的实际回调验
assert_eq "回调地址那一步不是 FAIL" \
  "$(printf '%s' "$probe" | jq -r '.steps[] | select(.step=="5") | .status')" "PASS"

# ------------------------------------------------------------------ 5. 发起下推扫描
say "5. execution=SPARK：建单 → 提交 → 回调 → 终态"
started=$(api POST "$M/sensitivity-scans" \
  '{"catalog":"e2e","scope":"CATALOG","mode":"METADATA_ONLY","execution":"SPARK"}')
assert_eq "建单返回 202" "$(http_code)" "202"
SCAN=$(printf '%s' "$started" | jq -r .scanId)
assert_eq "建单即 PENDING（提交在另一个节拍里）" "$(printf '%s' "$started" | jq -r .status)" "PENDING"
assert_eq "execution 落库为 SPARK" "$(printf '%s' "$started" | jq -r .execution)" "SPARK"

# 等它被提交（PENDING → RUNNING），同时看 Job 是否真的出现
JOB=paimon-scan-$SCAN
for _ in $(seq 1 60); do
  summary=$(api GET "$M/sensitivity-scans/$SCAN")
  state=$(printf '%s' "$summary" | jq -r .status)
  [ "$state" = "RUNNING" ] || [ "$state" = "SUCCEEDED" ] || [ "$state" = "FAILED" ] && break
  sleep 2
done
assert_eq "扫描被提交并进入 RUNNING" "$state" "RUNNING"
assert_eq "Job 坐标落库" "$(printf '%s' "$summary" | jq -r .executionResource)" "$JOB"

job_json=$(kubectl -n "$NS" get job "$JOB" -o json 2>/dev/null)
if [ -n "$job_json" ]; then pass "集群里存在 $JOB"; else fail "集群里没有 $JOB"; fi
assert_eq "backoffLimit=0（重试是表级语义，不是重跑整轮）" \
  "$(printf '%s' "$job_json" | jq -r .spec.backoffLimit)" "0"
assert_eq "TTL 不小于扫描超时 + 10 分钟" \
  "$(printf '%s' "$job_json" | jq -r '.spec.ttlSecondsAfterFinished >= 1200')" "true"
command_json=$(printf '%s' "$job_json" | jq -c '.spec.template.spec.containers[0].command')
args_json=$(printf '%s' "$job_json" | jq -c '.spec.template.spec.containers[0].args')
assert_contains "命令用 spark-submit 起另一个 main class" "$command_json" "SensitivityScannerJob"
assert_contains "覆盖 ENTRYPOINT 后仍带上 Lance 的 extraClassPath" "$command_json" "spark.driver.extraClassPath"
assert_contains "命令行包含 --scan-id" "$args_json" "--scan-id"
assert_contains "命令行包含回调地址" "$args_json" "--management-url"
assert_contains "命令行包含本次扫描的回调令牌" "$args_json" "--token"
assert_contains "默认 Spark 参数是 1g" "$command_json" "spark.driver.memory=1g"
assert_absent "不带 shuffle.partitions（小数据量的纯开销）" "$command_json" "spark.sql.shuffle.partitions"

# 等终态
for _ in $(seq 1 "$TIMEOUT"); do
  summary=$(api GET "$M/sensitivity-scans/$SCAN")
  state=$(printf '%s' "$summary" | jq -r .status)
  case "$state" in SUCCEEDED|FAILED|CANCELLED) break ;; esac
  sleep 2
done
if [ "$state" != "SUCCEEDED" ]; then
  fail "扫描没有以 SUCCEEDED 收尾（${state}）：$(printf '%s' "$summary" | jq -r '.errorMessage // ""')"
  printf '\n--- 作业日志 ---\n'
  kubectl -n "$NS" logs "job/$JOB" --tail=40 2>&1 | tail -30
  printf '\n--- 服务端日志 ---\n'
  kubectl -n "$NS" logs deploy/lakehouse-rest --tail=20 2>&1 | tail -12
else
  pass "扫描以 SUCCEEDED 收尾"
fi
assert_eq "两张表都算「扫过」" "$(printf '%s' "$summary" | jq -r .tablesScanned)" "2"
assert_eq "没有失败的表" "$(printf '%s' "$summary" | jq -r .tablesFailed)" "0"
findings=$(api GET "$M/sensitivity-findings?catalog=e2e")
# 粒度是**列 × 类型**，不是「一列一条」：`id_card_no` 会同时命中 CN_ID_CARD 与
# BANK_CARD（名字层两个正则都吃 `card` 这个词干），这是探测器的重叠而不是重复落库
# ——第一次跑把期望写成「一列一条」，于是 2 条命中被当成了 bug。
assert_eq "id_card_no 命中 CN_ID_CARD（纯列名证据）" \
  "$(printf '%s' "$findings" | jq -r '[.items[] | select(.column=="id_card_no" and .typeCode=="CN_ID_CARD")] | length')" "1"
assert_eq "第二列也有结论（mobile → CN_MOBILE）" \
  "$(printf '%s' "$findings" | jq -r '[.items[] | select(.column=="mobile" and .typeCode=="CN_MOBILE")] | length')" "1"
assert_eq "finding 落在正确的表上（columnRef 三段全对）" \
  "$(printf '%s' "$findings" | jq -r '[.items[] | select(.column=="id_card_no" and .typeCode=="CN_ID_CARD")][0].columnRef')" \
  "e2e.hr.user_profile.id_card_no"
assert_eq "元数据档的置信度就是 0.35（只有名字层命中）" \
  "$(printf '%s' "$findings" | jq -r '[.items[] | select(.column=="id_card_no" and .typeCode=="CN_ID_CARD")][0].confidence')" \
  "0.35"
assert_eq "全部 finding 都停在 PENDING（0.35 < 0.85 自动应用阈值）" \
  "$(printf '%s' "$findings" | jq -r '[.items[] | select(.state!="PENDING")] | length')" "0"

# 元数据档不该读值：日志里既没有 SELECT，也不该出现回调令牌
# 这两条守的是 `SparkSessions.restoreApplicationLogLevel()`：`setLogLevel("WARN")` 改的是
# **根** logger，不把本包的级别捞回来，作业日志就只剩 Spark 启动那几十行——作业成功、
# 逐表回报也在，但排障时看不到任何结论。这一点单测看不出来（单测里没有 SparkContext）。
logs=$(kubectl -n "$NS" logs "job/$JOB" --tail=200 2>&1)
assert_contains "作业日志记录了逐表扫描（本模块的 INFO 没被 setLogLevel 一起吞掉）" \
  "$logs" "scanned hr.user_profile"
assert_contains "作业日志有整轮总结" "$logs" "table(s) scanned"
CALLBACK_VALUE=$(printf '%s' "$args_json" | jq -r 'if index("--token") then .[index("--token") + 1] else "" end')
if [ -z "$CALLBACK_VALUE" ]; then
  fail "命令行里找不到 --token 的值，无法检查它是否漏进日志"
else
  # 不回显令牌本身：命中时只说「漏了」
  case "$logs" in
    *"$CALLBACK_VALUE"*) fail "回调令牌漏进了作业日志" ;;
    *) pass "作业日志里没有回调令牌" ;;
  esac
fi
assert_absent "日志里没有 SELECT（元数据档不读值）" "$logs" "SELECT \`"

# ------------------------------------------------------------------ 6. 取消
say "6. 取消：作业被删、状态 CANCELLED、终态再取消 409"
# 用单表扫描：两次都是同一张表会被 409 挡（同表并发重入），所以换一张
second=$(api POST "$M/sensitivity-scans" \
  '{"catalog":"e2e","scope":"TABLE","database":"hr","table":"orders","mode":"METADATA_ONLY","execution":"SPARK"}')
SECOND=$(printf '%s' "$second" | jq -r .scanId)
for _ in $(seq 1 30); do
  s2=$(api GET "$M/sensitivity-scans/$SECOND")
  [ "$(printf '%s' "$s2" | jq -r .status)" = "RUNNING" ] && break
  sleep 2
done
assert_eq "第二条扫描进入 RUNNING" "$(printf '%s' "$s2" | jq -r .status)" "RUNNING"

cancelled=$(api POST "$M/sensitivity-scans/$SECOND:cancel")
assert_eq "取消后状态为 CANCELLED" "$(printf '%s' "$cancelled" | jq -r .status)" "CANCELLED"
deleted=no
for _ in $(seq 1 15); do
  if ! kubectl -n "$NS" get job "paimon-scan-$SECOND" >/dev/null 2>&1; then deleted=yes; break; fi
  sleep 2
done
assert_eq "取消会删掉 Job（不是只改库里的状态）" "$deleted" "yes"
api POST "$M/sensitivity-scans/$SECOND:cancel" >/dev/null
assert_eq "终态再取消回 409" "$(http_code)" "409"

# ------------------------------------------------------------------ 汇总
say "结果"
printf '  通过 %d 项，失败 %d 项\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ] || exit 1
