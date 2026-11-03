#!/bin/bash
# 真实 Keycloak 的 SSO 端到端联调：登录 → 建主体 → 拿角色 → 换票 → 数据面。
#
# 为什么需要它：单测里的 OIDC 是拿本地生成的 RSA 密钥自签的令牌（OidcServiceTests），
# 它证明得了「验签逻辑对」，证明不了「真实 IdP 的令牌长什么样」。而真实 IdP 的差异
# 恰恰都落在最容易配错、又最不报错的地方：
#   - `aud` 到底是 client_id 还是 `account`（Keycloak 的 access_token 是后者）；
#   - `groups` 是数组还是逗号串、组名带不带前导斜杠（full group path 打开后是 `/data`）；
#   - `preferred_username` / `email` 在不在 id_token 里（取决于 client scope）。
# 每一项配错的表现都一样：**登录成功、一个角色都没有、每个页面 403**。
# 所以这里不看代码，只对真实运行的 Keycloak 发真实的 HTTP 请求。
#
# 前置（两步）：
#   1) Keycloak 26.x 起来，管理账号可用：
#        docker run -d --name kc-dev -p 8081:8080 \
#          -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
#          quay.io/keycloak/keycloak:26.7.4 start-dev
#   2) 服务端以「认证 + 授权 + OIDC 全开」启动，**端口要与 REDIRECT 一致**：
#        java -jar lakehouse-rest-server-*.jar --server.port=8080 \
#          --spring.profiles.active=h2 \
#          --lakehouse.rest.auth.enabled=true \
#          --lakehouse.rest.authorization.enabled=true \
#          --lakehouse.rest.auth.access-token.signing-key="$(openssl rand -base64 48)" \
#          --lakehouse.rest.auth.console.password.principals[admin]=root \
#          --lakehouse.rest.auth.console.oidc.enabled=true \
#          --lakehouse.rest.auth.console.oidc.issuer-uri=http://127.0.0.1:8081/realms/lakehouse \
#          --lakehouse.rest.auth.console.oidc.client-id=paimon-console \
#          --lakehouse.rest.auth.console.oidc.redirect-uri=http://localhost:8080/console/auth/callback \
#          --lakehouse.rest.auth.console.oidc.principal-claim=preferred_username \
#          --lakehouse.rest.auth.console.oidc.audience=paimon-console
#     两点不是随便写的：
#       `--server.port` 显式给 —— 环境里若存在 SERVER_PORT，Tomcat 会去绑那个端口，
#         而 Keycloak 侧登记的 redirect_uri 写死 8080，两边对不上就只报 invalid_redirect_uri；
#       `principals[admin]=root` —— 控制台密码账号默认只映射成 `admin`，而 `admin`
#         不是服务管理员（service-admins 默认只有 root），改配置时会 403。
#
# 脚本会**删掉并重建** realm（默认 `lakehouse`），它只该指向本地开发用的 Keycloak。
#
# 用法：
#   BASE=http://127.0.0.1:8080 KC=http://127.0.0.1:8081 ./scripts/e2e-keycloak-sso.sh
#
# 变量：
#   KC / BASE             Keycloak 与服务端地址，默认 8081 / 8080
#   REALM                 被测 realm，默认 lakehouse（会被重建）
#   KC_ADMIN(_PASSWORD)   Keycloak 管理账号，默认 admin/admin
#   OIDC_CLIENT           在 realm 里建的公开客户端，默认 paimon-console
#   REDIRECT              回调地址，必须与服务端 `oidc.redirect-uri` 逐字相同
#   SSO_USER(_PASSWORD)   用来登录的 IdP 用户，默认 alice/alice
#   SSO_GROUP / ROLE      IdP 组与它映射到的 principal role，默认 sso-analysts / analyst
#   CONSOLE_USER(_PASSWORD) 服务端控制台密码账号，默认 admin/admin（映射到 root）
#
# 每行格式为 <标记>[实际/期望] 说明，`ok ` 一致，`!! ` 不一致。退出码 1 表示有不一致。
set -u

KC=${KC:-http://127.0.0.1:8081}
B=${BASE:-http://127.0.0.1:8080}
REALM=${REALM:-lakehouse}
KC_ADMIN=${KC_ADMIN:-admin}
KC_ADMIN_PASSWORD=${KC_ADMIN_PASSWORD:-admin}
OIDC_CLIENT=${OIDC_CLIENT:-paimon-console}
REDIRECT=${REDIRECT:-http://localhost:8080/console/auth/callback}
SSO_USER=${SSO_USER:-alice}
SSO_PASSWORD=${SSO_PASSWORD:-alice}
SSO_EMAIL=${SSO_EMAIL:-alice@corp.example.com}
SSO_GROUP=${SSO_GROUP:-sso-analysts}
ROLE=${ROLE:-analyst}
CONSOLE_USER=${CONSOLE_USER:-admin}
CONSOLE_PASSWORD=${CONSOLE_PASSWORD:-admin}

for tool in curl python3 openssl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "需要 $tool" >&2
    exit 2
  fi
done

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT
BODY="$TMPD/body.json"
HEADER="$TMPD/header.txt"
JAR="$TMPD/cookies.txt"
PAGE="$TMPD/login.html"

FORM="Content-Type: application/x-www-form-urlencoded"
JSON="Content-Type: application/json"

pass=0
fail=0

note() { printf '\n========== %s ==========\n' "$1"; }
skip() { printf '(跳过：%s)\n' "$1"; }

hit() { # hit <期望状态码> <说明> <curl 参数...>；响应体留在 $BODY，响应头留在 $HEADER
  local want="$1"; shift
  local label="$1"; shift
  local code
  code=$(curl -s --noproxy '*' -D "$HEADER" -o "$BODY" -w '%{http_code}' "$@" 2>/dev/null)
  local mark='ok '
  if [ "$code" = "$want" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s/%s] %s\n' "$mark" "$code" "$want" "$label"
}

jval() { # jval <点分路径>：取 JSON 字段，缺失得 <缺失>，布尔得 true/false
  python3 - "$BODY" "$1" <<'PY'
import json, sys
try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("<不是 JSON>"); raise SystemExit
try:
    for part in sys.argv[2].split("."):
        if not part:
            continue
        node = node[int(part)] if isinstance(node, list) else node.get(part)
except (KeyError, IndexError, TypeError, ValueError):
    node = None
if node is None:
    print("<缺失>")
elif isinstance(node, bool):
    print("true" if node else "false")
elif isinstance(node, list):
    print(",".join(str(x) for x in node))
else:
    print(node)
PY
}

jf() { # jf <期望值> <点分路径> <说明>
  local want="$1" label="$3" got
  got=$(jval "$2")
  local mark='ok '
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s] %-52s 期望=%b\n' "$mark" "$got" "$label" "$want"
}

jhas() { # jhas <点分路径> <子串> <说明>
  local needle="$2" label="$3" got
  got=$(jval "$1")
  local mark='ok '
  case "$got" in
    *"$needle"*) pass=$((pass + 1)) ;;
    *) mark='!! '; fail=$((fail + 1)) ;;
  esac
  printf '%s[%s] %s（应含 %s）\n' "$mark" "$got" "$label" "$needle"
}

vf() { # vf <期望值> <实际值> <说明>：比的是两个 shell 变量，不是响应体
  local mark='ok '
  if [ "$1" = "$2" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s] %s\n' "$mark" "$2" "$3"
}

jwt() { # jwt <令牌> <点分路径>：解出 JWT 载荷里的一个字段，不验签（验签是服务端的事）
  python3 - "$1" "$2" <<'PY'
import base64, json, sys
token, path = sys.argv[1], sys.argv[2]
try:
    part = token.split(".")[1]
except IndexError:
    print("<不是 JWT>"); raise SystemExit
part += "=" * (-len(part) % 4)
try:
    node = json.loads(base64.urlsafe_b64decode(part))
except Exception:
    print("<载荷不是 JSON>"); raise SystemExit
try:
    for name in path.split("."):
        if not name:
            continue
        node = node[int(name)] if isinstance(node, list) else node.get(name)
except (KeyError, IndexError, TypeError, ValueError):
    node = None
if node is None:
    print("<缺失>")
elif isinstance(node, bool):
    print("true" if node else "false")
elif isinstance(node, list):
    print(",".join(str(x) for x in node))
else:
    print(node)
PY
}

jwt_f() { # jwt_f <令牌> <期望值> <点分路径> <说明>
  local got
  got=$(jwt "$1" "$3")
  local mark='ok '
  if [ "$got" = "$2" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s] %s\n' "$mark" "$got" "$4"
}

auth_header() { printf 'Authorization: Bearer %s' "$1"; }

# ------------------------------------------------------------------ 0

note "0. 前置检查"

code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$KC/realms/master/.well-known/openid-configuration" 2>/dev/null)
if [ "$code" != "200" ]; then
  echo "Keycloak 不可达：${KC}（HTTP $code）"
  echo "先按脚本头部的 docker run 起一个，或把 KC 指到已有实例"
  exit 2
fi
printf 'ok [] Keycloak 可达：%s\n' "$KC"

code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$B/api/console/v1/auth" 2>/dev/null)
if [ "$code" != "200" ]; then
  echo "服务端不可达或未按 OIDC 方式启动：${B}（HTTP $code）"
  exit 2
fi
printf 'ok [] 服务端可达：%s\n' "$B"

KCTOK=$(curl -s --noproxy '*' -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -H "$FORM" -d grant_type=password -d client_id=admin-cli \
  -d "username=$KC_ADMIN" -d "password=$KC_ADMIN_PASSWORD" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("access_token",""))')
if [ -z "$KCTOK" ]; then
  echo "拿不到 Keycloak 管理员令牌（KC_ADMIN / KC_ADMIN_PASSWORD 不对？）"
  exit 2
fi
printf 'ok [len=%s] Keycloak 管理员令牌\n' "${#KCTOK}"

kc() { # kc <方法> <路径> [json]：响应体进 $BODY，状态码进 KC_CODE
  local method="$1" path="$2" payload="${3:-}"
  if [ -n "$payload" ]; then
    KC_CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -X "$method" \
      -H "$(auth_header "$KCTOK")" -H "$JSON" -d "$payload" "$KC$path" 2>/dev/null)
  else
    KC_CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -X "$method" \
      -H "$(auth_header "$KCTOK")" "$KC$path" 2>/dev/null)
  fi
}

kf() { # kf <期望状态码> <说明>：断在上面那次 kc 的状态码上
  local mark='ok '
  if [ "$KC_CODE" = "$1" ]; then pass=$((pass + 1)); else mark='!! '; fail=$((fail + 1)); fi
  printf '%s[%s/%s] %s\n' "$mark" "$KC_CODE" "$1" "$2"
}

# ------------------------------------------------------------------ 1

note "1. 在 Keycloak 里准备 realm / 客户端 / 组 / 用户"

kc DELETE "/admin/realms/$REALM"
printf '(重建 realm %s：DELETE 返回 %s，404 表示它本来就不存在)\n' "$REALM" "$KC_CODE"

kc POST /admin/realms "{\"realm\":\"$REALM\",\"enabled\":true,\"sslRequired\":\"none\",\"registrationAllowed\":false}"
kf 201 "创建 realm $REALM"

# 公开客户端 + PKCE：控制台是纯静态资源，没有地方存客户端密钥
kc POST "/admin/realms/$REALM/clients" "{
  \"clientId\":\"$OIDC_CLIENT\",\"name\":\"Lakehouse Console\",\"enabled\":true,
  \"publicClient\":true,\"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":false,
  \"redirectUris\":[\"$REDIRECT\"],\"webOrigins\":[\"+\"],\"protocol\":\"openid-connect\",
  \"attributes\":{\"pkce.code.challenge.method\":\"S256\"}
}"
kf 201 "创建公开客户端 ${OIDC_CLIENT}（强制 S256）"

kc GET "/admin/realms/$REALM/clients?clientId=$OIDC_CLIENT"
CLIENT_UUID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))[0]["id"])' "$BODY" 2>/dev/null || true)
if [ -n "${CLIENT_UUID:-}" ]; then
  pass=$((pass + 1)); printf 'ok [%s] 客户端 UUID\n' "$CLIENT_UUID"
else
  fail=$((fail + 1)); printf '!! [] 查不到客户端 UUID，后续步骤无意义\n'; exit 1
fi

# 组 → claim 的映射器。**这是「拿角色」的唯一来源**：不装它，令牌里就没有 `groups`，
# 服务端按 sync-mode 的默认值（on-login）什么都不做，现象是登录成功但零角色。
# full.path=false 要显式写出来：打开它组名变成 `/sso-analysts`（两种都实测过），
# 而映射表里配的是不带斜杠的、匹配又是精确的（不 trim、不加前缀），
# 差一个斜杠就是一个角色都同步不过去，现象与「没配映射」一模一样。
kc POST "/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" "{
  \"name\":\"groups\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-group-membership-mapper\",
  \"config\":{\"claim.name\":\"groups\",\"full.path\":\"false\",
              \"id.token.claim\":\"true\",\"access.token.claim\":\"true\",\"userinfo.token.claim\":\"true\"}
}"
kf 201 "装组映射器（claim=groups，full.path=false，写进 id_token）"

kc POST "/admin/realms/$REALM/groups" "{\"name\":\"$SSO_GROUP\"}"
kf 201 "创建 IdP 组 $SSO_GROUP"

kc POST "/admin/realms/$REALM/users" "{
  \"username\":\"$SSO_USER\",\"enabled\":true,\"emailVerified\":true,\"email\":\"$SSO_EMAIL\",
  \"firstName\":\"Alice\",\"lastName\":\"Chen\",
  \"credentials\":[{\"type\":\"password\",\"value\":\"$SSO_PASSWORD\",\"temporary\":false}],
  \"groups\":[\"/$SSO_GROUP\"]
}"
kf 201 "创建用户 ${SSO_USER}（密码登录、已归入 ${SSO_GROUP}）"

# ------------------------------------------------------------------ 2

note "2. 服务端侧准备：服务管理员、principal role、组映射"

hit 200 '控制台密码登录（admin → root）' -X POST -H "$JSON" \
  -d "{\"username\":\"$CONSOLE_USER\",\"password\":\"$CONSOLE_PASSWORD\"}" "$B/api/console/v1/login"
jf root principal "账号映射到服务管理员 root（未配 principals 时会是 admin，改配置会 403）"
ADMIN_TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("accessToken",""))' "$BODY")

hit 200 '登录引导（匿名）' "$B/api/console/v1/auth"
jhas methods oidc "支持的方式里有 OIDC（说明发现文档拿得到）"
jf "$KC/realms/$REALM" oidc.issuer "下发的是我们配的 issuer"
jf "$OIDC_CLIENT" oidc.clientId "客户端标识"
jf "$REDIRECT" oidc.redirectUri "回调地址（IdP 侧登记的是同一个串）"
jhas oidc.authorizationEndpoint "$KC/realms/$REALM" "授权端点来自发现文档，不是硬编码"

# 清掉上一轮的痕迹：Keycloak 侧的 realm 每轮重建，服务端侧的 h2 却不会跟着清。
# 不清就有两种假失败：重复建角色/映射得到 409，以及主体清单里多出一行旧的 alice。
quiet() { # quiet <说明> <curl 参数...>：只做清理，不判定
  local label="$1"; shift
  local code
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$@" 2>/dev/null)
  printf '(清理：%s → %s)\n' "$label" "$code"
}

curl -s --noproxy '*' -o "$BODY" -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/management/v1/group-mappings?group=$SSO_GROUP&pageSize=200" 2>/dev/null
for id in $(python3 -c '
import json, sys
try:
    rows = json.load(open(sys.argv[1], encoding="utf-8")).get("mappings", [])
except Exception:
    rows = []
print(" ".join(row["id"] for row in rows if row.get("id")))
' "$BODY" 2>/dev/null); do
  quiet "删除上一轮的组映射 $id" -X DELETE -H "$(auth_header "$ADMIN_TOKEN")" \
    "$B/api/management/v1/group-mappings/$id"
done
quiet "删除上一轮的 OIDC 主体 $SSO_USER" -X DELETE -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/management/v1/principals/$SSO_USER"
quiet "删除上一轮的角色 $ROLE" -X DELETE -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/management/v1/principal-roles/$ROLE"

hit 201 '创建 principal role' -X POST -H "$(auth_header "$ADMIN_TOKEN")" -H "$JSON" \
  -d "{\"principalRole\":{\"name\":\"$ROLE\"}}" "$B/api/management/v1/principal-roles"
hit 201 '创建组映射（provider=default）' -X POST -H "$(auth_header "$ADMIN_TOKEN")" -H "$JSON" \
  -d "{\"provider\":\"default\",\"group\":\"$SSO_GROUP\",\"role\":\"$ROLE\",\"enabled\":true}" \
  "$B/api/management/v1/group-mappings"

hit 200 '同步预览（不写库）' -X POST -H "$(auth_header "$ADMIN_TOKEN")" -H "$JSON" \
  -d "{\"provider\":\"default\",\"groups\":[\"$SSO_GROUP\"]}" "$B/api/management/v1/group-mappings/preview"
jf "$ROLE" desiredRoles "预览算出的角色与登录时算的是同一段代码"
jf '' unmappedGroups "组已映射，没有被漏掉的组"
jf '' unknownRoles "角色已存在"

# ------------------------------------------------------------------ 3

note "3. 浏览器侧：授权码 + PKCE（不打开浏览器，但走的是同一条路）"

sso_login() { # 跑一遍完整的授权码流程，结果放进 ID_TOKEN / KC_ACCESS_TOKEN
  local verifier challenge state url action location code
  verifier=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
  challenge=$(printf '%s' "$verifier" | openssl dgst -binary -sha256 | openssl base64 | tr '+/' '-_' | tr -d '=')
  state=$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=')
  url=$(python3 -c '
import sys, urllib.parse
base, client, redirect, state, challenge = sys.argv[1:6]
print(base + "?" + urllib.parse.urlencode({
    "response_type": "code", "client_id": client, "redirect_uri": redirect,
    "scope": "openid profile email", "state": state,
    "code_challenge": challenge, "code_challenge_method": "S256"}))
' "$KC/realms/$REALM/protocol/openid-connect/auth" "$OIDC_CLIENT" "$REDIRECT" "$state" "$challenge")

  rm -f "$JAR"
  curl -s --noproxy '*' -c "$JAR" -b "$JAR" -o "$PAGE" "$url" 2>/dev/null
  action=$(python3 - "$PAGE" <<'PY'
import html, re, sys
try:
    page = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    raise SystemExit
match = re.search(r'<form[^>]*action="([^"]+)"', page)
if match:
    print(html.unescape(match.group(1)))
PY
)
  if [ -z "$action" ]; then
    printf '   Keycloak 登录页里没有表单；URL=%s\n' "$url" >&2
    return 1
  fi
  location=$(curl -s --noproxy '*' -c "$JAR" -b "$JAR" -D - -o /dev/null -X POST "$action" \
    -H "$FORM" --data-urlencode "username=$SSO_USER" --data-urlencode "password=$SSO_PASSWORD" \
    2>/dev/null | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
  if [ -z "$location" ]; then
    printf '   登录表单提交后没有拿到跳转（用户名/密码不对？）\n' >&2
    return 1
  fi
  STATE_ROUNDTRIP=$(python3 -c '
import sys, urllib.parse
query = urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query)
print("true" if query.get("state", [""])[0] == sys.argv[2] else "false")' "$location" "$state")
  code=$(python3 -c '
import sys, urllib.parse
query = urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query)
print(query.get("code", [""])[0])' "$location")
  if [ -z "$code" ]; then
    printf '   跳转里没有 code：%s\n' "$location" >&2
    return 1
  fi
  curl -s --noproxy '*' -o "$BODY" -X POST "$KC/realms/$REALM/protocol/openid-connect/token" -H "$FORM" \
    --data-urlencode grant_type=authorization_code --data-urlencode "code=$code" \
    --data-urlencode "redirect_uri=$REDIRECT" --data-urlencode "client_id=$OIDC_CLIENT" \
    --data-urlencode "code_verifier=$verifier" 2>/dev/null
  ID_TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("id_token",""))' "$BODY")
  KC_ACCESS_TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("access_token",""))' "$BODY")
  [ -n "$ID_TOKEN" ]
}

if sso_login; then
  pass=$((pass + 1)); printf 'ok [len=%s] 走完授权码 + PKCE，拿到 id_token\n' "${#ID_TOKEN}"
else
  fail=$((fail + 1)); printf '!! [] 授权码流程失败（后面的断言都无法进行）\n'; exit 1
fi

vf true "$STATE_ROUNDTRIP" "state 原样回来了（CSRF 防护生效）"
jwt_f "$ID_TOKEN" "$KC/realms/$REALM" iss "id_token 的签发者就是配置的 issuer"
jwt_f "$ID_TOKEN" "$OIDC_CLIENT" aud "id_token 的受众是 client_id（PKCE 公开客户端的默认行为）"
jwt_f "$ID_TOKEN" "$SSO_USER" preferred_username "principal-claim 取的就是这个字段"
jwt_f "$ID_TOKEN" "$SSO_EMAIL" email "display-name-claim 取自 email"
jwt_f "$ID_TOKEN" "$SSO_GROUP" groups "组映射器把成员关系写进了 id_token（不带前导斜杠）"

# Keycloak 的 access_token 与 id_token 是两个受众：前者是 `account`，后者是 client_id。
# 控制台优先用 id_token 正是因为这个——下面第 6 节会验证前者会被服务端拒。
KC_AUD=$(jwt "$KC_ACCESS_TOKEN" aud)
vf account "$KC_AUD" "同一个响应的 access_token 受众是 account（所以它不能当主体的票）"

# ------------------------------------------------------------------ 4

note "4. 服务端认领：JIT 建主体（供给挂在 GET /api/console/v1/auth 上）"

hit 200 '带 id_token 回到登录引导' -H "$(auth_header "$ID_TOKEN")" "$B/api/console/v1/auth"
jf true session.authenticated "服务端认可这张 IdP 令牌"
jf "$SSO_USER" session.principal "主体名 = principal-claim"
jf oidc session.source "来源标记为 oidc（供给就是在这里被触发的）"
jf false session.credentialRotationRequired "JIT 主体没有凭据，不涉及轮换"

hit 200 '主体清单按来源过滤' -H "$(auth_header "$ADMIN_TOKEN")" "$B/api/management/v1/principals?source=OIDC&pageSize=200"
jf "$SSO_USER" principals.0.name "JIT 建出来的主体"
jf OIDC principals.0.source "来源是 OIDC，不是 LOCAL"
jf "$SSO_EMAIL" principals.0.displayName "显示名取自 email claim"
jhas principals.0.externalId "/realms/$REALM|" "external_id 是「issuer|sub」，换 IdP 或换人会不同"
SUBJECT=$(jwt "$ID_TOKEN" sub)
jhas principals.0.externalId "$SUBJECT" "external_id 里的 sub 来自令牌"
LAST_LOGIN=$(jval principals.0.lastLoginAt)
case "$LAST_LOGIN" in
  '' | '<缺失>' | '<不是 JSON>')
    fail=$((fail + 1)); printf '!! [%s] last_login_at 没写进去\n' "$LAST_LOGIN" ;;
  *)
    pass=$((pass + 1)); printf 'ok [%s] last_login_at 已刷新\n' "$LAST_LOGIN" ;;
esac

# ------------------------------------------------------------------ 5

note "5. 组 → 角色（只认 granted_by=IDP 的那几行）"

hit 200 '主体已获得的角色' -H "$(auth_header "$ADMIN_TOKEN")" "$B/api/management/v1/principals/$SSO_USER/principal-roles"
jf "$ROLE" roles.0.name "组映射把 $SSO_GROUP 落成了角色 $ROLE"

hit 200 '审计：登录/供给/同步三类事件' -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/console/v1/events?category=PRINCIPAL&pageSize=50"
# 三类事件是三个独立切面、按写入时间倒序，这里只要求它们都出现过；
# 具体属性打印在下面，供人一眼看出「是不是这次登录产生的」
if python3 - "$BODY" <<'PY'
import json, sys
events = json.load(open(sys.argv[1], encoding="utf-8")).get("events", [])
for event in events:
    attributes = event.get("attributes") or {}
    picked = {k: attributes[k] for k in ("result", "jitCreated", "groupsCount", "added", "removed") if k in attributes}
    print(f"   {event.get('type'):26s} {event.get('target')}  {picked}")
types = {event.get("type") for event in events}
missing = [t for t in ("principal.login", "principal.provisioned", "principal.roles_synced") if t not in types]
raise SystemExit(0 if not missing else 1)
PY
then
  pass=$((pass + 1)); printf 'ok [] 登录 / 供给 / 同步三类事件都在审计里\n'
else
  fail=$((fail + 1)); printf '!! [] 审计事件不全（应为 login + provisioned + roles_synced）\n'
fi

# ------------------------------------------------------------------ 6

note "6. 换票（RFC 8693）：用 IdP 的票换一张 catalog 的票"

hit 200 'token-exchange' -X POST -H "$FORM" "$B/api/catalog/v1/oauth/tokens" \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
  --data-urlencode "subject_token=$ID_TOKEN" \
  --data-urlencode 'subject_token_type=urn:ietf:params:oauth:token-type:access_token'
jf bearer token_type "token_type"
jf PRINCIPAL_ROLE:ALL scope "scope 被归一成唯一的可接受取值（换票不放大权限）"
jhas issued_token_type access_token "issued_token_type 只出现在交换响应里（client_credentials 不带它）"
EXCHANGED=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("access_token",""))' "$BODY")
jwt_f "$EXCHANGED" "$SSO_USER" sub "换来的票主体仍是 IdP 登录的那个人"
jwt_f "$EXCHANGED" paimon-rest iss "签发者换成了本服务端"

hit 401 '不带令牌访问数据面' "$B/v1/paimon/databases"
hit 200 '用换来的票访问数据面' -H "$(auth_header "$EXCHANGED")" "$B/v1/paimon/databases"
jf default databases.0 "读到的是真实 catalog 里的库"
hit 403 '同一张票访问管理面（不是服务管理员）' -H "$(auth_header "$EXCHANGED")" "$B/api/management/v1/principals"

# IdP 的 access_token 受众是 `account`，服务端按 `audience` 拒掉它。这不是缺陷，
# 而是「客户端要选对令牌」：控制台选 id_token 的理由就写在这里。
hit 400 '拿 IdP 的 access_token 换票（aud=account，被拒）' -X POST -H "$FORM" \
  "$B/api/catalog/v1/oauth/tokens" \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
  --data-urlencode "subject_token=$KC_ACCESS_TOKEN"
jf invalid_grant error "错误码：授权许可不能再用（不是 invalid_request）"

# ------------------------------------------------------------------ 7

note "7. 再来一次：幂等与「不重复建主体」"

if sso_login; then
  pass=$((pass + 1)); printf 'ok [] 第二次登录也拿到了 id_token\n'
else
  fail=$((fail + 1)); printf '!! [] 第二次登录失败\n'
fi
hit 200 '第二次带 id_token 回到登录引导' -H "$(auth_header "$ID_TOKEN")" "$B/api/console/v1/auth"
jf true session.authenticated "仍然认得"
jf "$SSO_USER" session.principal "还是同一个人（同名同 external_id 不新建）"

hit 200 '审计里最新一条登录' -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/console/v1/events?category=PRINCIPAL&type=principal.login&pageSize=10"
jf false events.0.attributes.jitCreated "这次没有新建主体（jitCreated=false）"
jf success events.0.attributes.result "结果仍是 success"

hit 200 '主体清单只有一行 OIDC 主体' -H "$(auth_header "$ADMIN_TOKEN")" \
  "$B/api/management/v1/principals?source=OIDC&pageSize=200"
jf 1 page.total "没有因为重复登录多出一行"

# ------------------------------------------------------------------

printf '\n'
if [ "$fail" -gt 0 ]; then
  printf '验收未通过：%d / %d 项不一致\n' "$fail" "$((pass + fail))"
  exit 1
fi
printf '全部通过：%d 项\n' "$pass"
