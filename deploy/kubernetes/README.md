# Kubernetes 部署

`deploy/kubernetes/` 只有一个文件 `lakehouse-rest.yaml`，里面三个文档按顺序排好：

```
1. ConfigMap     lakehouse-rest-config（application-k8s.yml，数据库连接串在这里）
2. Deployment    lakehouse-rest-server
3. Service       lakehouse-rest-server（NodePort 30818）
```

**Namespace 不在这个文件里**，需要先建：

```bash
kubectl create namespace lakehouse-rest        # 仅在不存在时
kubectl apply -f deploy/kubernetes/lakehouse-rest.yaml
kubectl -n lakehouse-rest get pods -w
```

命名空间是集群级前置资源，通常由平台统一创建（并附带 ResourceQuota、NetworkPolicy、
镜像仓库凭据）。写进应用清单的代价是引入「谁先建」的顺序依赖：
`kubectl apply -f <目录>` 会**按文件名排序**逐个 apply，`configmap.yaml` 排在
`namespace.yaml` 前面，于是第一个对象就报
`Error from server (NotFound): namespaces "lakehouse-rest" not found`——而 Namespace
明明「已经创建」，只是创建在后面。现在清单里不声明它，这个顺序问题从根上消失了，
代价是首次部署多一条命令。

三个文档都必须自己写 `namespace: lakehouse-rest`（没有 kustomize 统一注入）。
漏写的那份会落到 `default`，表现是「apply 成功但 Pod 找不到 ConfigMap」。

---

## 1. 为什么不带数据库、卷、Secret、PDB、Ingress

上一版是一套自洽清单：MySQL StatefulSet、warehouse PVC、Secret、PDB、Ingress、
kustomize 组装，一条 `kubectl apply -k` 起全套。**这一版把这些都推到集群外部**，
理由是这套部署的真实用法：元数据库是现成的外部实例，仓库是节点本地目录，
入口只需要一个固定端口。

| 删掉的 | 现在怎么来 |
| --- | --- |
| `mysql.yaml`（StatefulSet + Service） | 外部 MySQL。连接串写在 `configmap.yaml` 的 `spring.datasource.url`，建表语句见仓库 `sql/schema-mysql.sql` |
| `warehouse-pvc.yaml`（PVC） | 节点本地目录 `/data/paimon-warehouse`，`hostPath` 挂进容器（单副本，Pod 重建数据不丢） |
| `secret.yaml`（Secret） | 不做 Secret。鉴权默认关闭，数据库口令直接写在 ConfigMap 里；配置中没有任何 `${...}` 占位符 |
| `pdb.yaml`（PodDisruptionBudget） | 单副本本来没有可用性冗余，PDB 只能二选一：`minAvailable: 1` 会卡住节点排空，`maxUnavailable: 1` 等于没有保护 |
| `ingress.yaml`（Ingress） | Service 用 NodePort 30818，从节点 IP 直接访问，不需要 Ingress 控制器 |
| `service.yaml` | 合并进同一个文件（Service 对象还在，只是不再单独一个文件） |
| `namespace.yaml` | 也不在清单里。命名空间改为使用方预先创建（`kubectl create namespace lakehouse-rest`），清单只管往里放对象 |
| `kustomization.yaml` | 不再组装。三个文档各自写 `namespace: lakehouse-rest`，单文件直接 apply |

**代价说清楚**：

- 没有 Secret，数据库口令是明文写在 ConfigMap 里的——任何能读 ConfigMap 的人都能看到。
  要换回「凭据单独授权」，得加一个 Secret + 两个 `secretKeyRef`，并在配置里写回
  `${DB_PASSWORD}`；那正是这次简化删掉的东西。
- hostPath 把 Pod 绑在节点上：换节点部署会看不到原来的数据。要跨节点，先换对象存储。
- 没有 PDB，节点排空时这个 Pod 直接被驱逐；没有 Ingress，只有 NodePort 一条入口。

---

## 2. 部署步骤

### 2.1 前置条件

| 条件 | 说明 |
| --- | --- |
| Kubernetes | 1.24 及以上（清单只用 `apps/v1` 与 `v1` 的稳定 API） |
| 外部 MySQL | 8.0，库为 `lakehouse_catalog`，已执行 `sql/schema-mysql.sql`（26 张表）。`ddl-auto: validate` 会在启动时校验表结构，不一致则启动失败 |
| 节点目录 | `/data/paimon-warehouse` 由清单自动创建（`DirectoryOrCreate`），属主由 initContainer 修正为 10001 |
| 镜像 | 需先构建并让节点拿得到，见 2.2 |

### 2.2 构建镜像

镜像里只有 jar，不编译：

```bash
mvn -o -pl :lakehouse-rest-server -am clean package -DskipTests
docker build -t registry.cn-hangzhou.aliyuncs.com/melin1204/lakehouse-rest-catalog:1.0.0 .
```

单节点集群（kind / minikube）可以直接送进节点，跳过 registry：

```bash
kind load docker-image registry.cn-hangzhou.aliyuncs.com/melin1204/lakehouse-rest-catalog:1.0.0
minikube image load registry.cn-hangzhou.aliyuncs.com/melin1204/lakehouse-rest-catalog:1.0.0
```

多节点集群推 registry；节点架构不一致时要用多架构镜像
（`PLATFORMS=linux/amd64,linux/arm64 PUSH=1 ../../scripts/build-image.sh`，详见仓库根 README §8.1）。

**改镜像 tag 要同步改清单**：`lakehouse-rest.yaml` 里两处引用（`prepare-warehouse`
与主容器）。`scripts/verify-k8s-manifests.py` §9 会核对清单引用的镜像是不是
Dockerfile 里构建的那个。

### 2.3 改配置

文件里 ConfigMap 段的 `application-k8s.yml` 是完整配置（相对镜像内 `application.yml`
只写覆盖项）。部署前至少要确认三处：

```yaml
spring.datasource.url:      jdbc:mysql://<外部 MySQL>:3306/lakehouse_catalog?...
spring.datasource.password: <口令>
lakehouse.rest.default-warehouse: file:///tmp/paimon-warehouse   # 见 2.4 的路径说明
```

### 2.4 warehouse 路径为什么还是 `/tmp/paimon-warehouse`

节点上的目录是 `/data/paimon-warehouse`，容器里挂的却是 `/tmp/paimon-warehouse`。
不是笔误：**已登记 catalog 的 warehouse 存在 MySQL 里**（`catalog.warehouse` 列），
不随清单改。把容器内路径换掉，这些 catalog 会指向一个不存在的目录，表现为
「表还在、数据没了」。所以容器内沿用旧路径，节点目录取个清楚的名字。

代价是这一层挂载落在 emptyDir `/tmp` 之内——`volumeMounts` 里 `/tmp` 必须排在
`/tmp/paimon-warehouse` 之前，否则子挂载会被父挂载遮蔽，数据写进 emptyDir，
Pod 一重建就丢。

新建集群（库里没有 catalog 记录）可以把两边都改成 `/data/paimon-warehouse`，
配置也一起改，那样更直观。

### 2.5 应用与验证

```bash
kubectl apply -f deploy/kubernetes/lakehouse-rest.yaml
kubectl -n lakehouse-rest rollout status deploy/lakehouse-rest-server
```

访问：

```bash
# 集群外（NodePort，端口写死 30818）
curl -s http://<节点IP>:30818/v1/config

# 本机临时转发，不必关心节点 IP
kubectl -n lakehouse-rest port-forward deploy/lakehouse-rest-server 8080:8080 &
curl -s http://127.0.0.1:8080/v1/config

# 管理面：列出 catalog
curl -s http://127.0.0.1:8080/api/management/v1/catalogs
```

`/v1/config` 会返回 warehouse 与 prefix。鉴权默认关闭（`lakehouse.rest.auth.enabled: false`），
要开的话在 ConfigMap 里打开并配上 `tokens`，客户端带 `Authorization: Bearer <token>`。

---

## 3. 几个取舍

### 3.1 副本数固定为 1

warehouse 是节点本地目录 + `file://` 协议。扩到 2 个副本，第二个 Pod 要么调度到
同一节点、要么各写一份互不相干的数据（表现为「建的表时有时无」）。
要横向扩展必须先把仓库换成对象存储（S3 / Azure Blob / GCS / 华为云 OBS / 阿里云 OSS），
换成 `s3://...` 之后 `hostPath` 卷与 `prepare-warehouse` 都可以删掉。

### 3.2 探针用 exec + curl，而不是 httpGet

`httpGet` 只把 200–399 视为成功。清单若开启鉴权，`/v1/config` 无令牌返回 401，
用 `httpGet` 的话 readinessProbe 永远失败、Pod 永不进 Endpoints，表现是
「容器在跑、日志正常，但 Service 没有后端」。所以探针显式把 401/403 也算作
「进程已经在处理请求」，与 Dockerfile 的 `HEALTHCHECK` 一致。

### 3.3 hostPath 的属主要自己修

`hostPath` 目录由 kubelet 以 root 创建（0755），而主容器以 uid 10001 运行、
根文件系统只读。**`fsGroup` 对 hostPath 不生效**（这是它与 PVC 的关键差别），
所以有 `prepare-warehouse` 这个 initContainer：以 root 跑一次 `chmod 0750` +
`chown 10001:10001`，之后主容器全程非 root。不修的话启动报「warehouse 目录不可写」。

**这里有两个必须一起做对的细节**，错任一个都只在真集群里暴露：

```yaml
command: ["/bin/sh", "-c", "chmod 0750 /data && chown 10001:10001 /data"]
securityContext:
  runAsUser: 0
  capabilities: {drop: [ALL], add: [CHOWN, FOWNER]}
```

- **顺序**：必须先 `chmod` 再 `chown`。`chown` 一执行，`/data` 的属主就变成 10001，
  此时当前 fsuid 仍是 0——再 `chmod` 就是「改别人的文件」，需要 `CAP_FOWNER`。
- **能力**：`drop: [ALL]` 之后 root 只剩显式 add 的能力。`chown` 要 `CAP_CHOWN`，
  `chmod` 在属主不是 0 时要 `CAP_FOWNER`。只给 `CHOWN` 的后果是
  `chmod: changing permissions of '/data': Operation not permitted`，
  Pod 卡在 `Init:0/1`，日志里只有这一句。

它复用服务端镜像而不是 busybox——多一个镜像就是多一处镜像源与架构问题，
而这里的命令只需要 `sh` + `chmod` + `chown`。`scripts/verify-k8s-manifests.py` §12
会按命令里出现的 `chmod`/`chown` 反查能力是否够（漏了直接红）。

### 3.4 建表语句走镜像，不走 ConfigMap

DDL 由 Dockerfile 打进镜像，与 jar 来自同一次构建；`ddl-auto: validate` 校验的
正是 jar 里的实体定义，两者天然同版本。代价：**改完 DDL 必须重新构建镜像**，
并在库上手工执行迁移（`/docker-entrypoint-initdb.d` 那套随 `mysql.yaml` 一起删了，
现在建库是外部 MySQL 的事）。

### 3.5 优雅停机的三处配合

| 位置 | 值 | 作用 |
| --- | --- | --- |
| `application-k8s.yml` | `spring.lifecycle.timeout-per-shutdown-phase: 30s` | 在途请求排空的等待上限 |
| Deployment | `terminationGracePeriodSeconds: 45` | 必须大于上一项，留 15s 给 preStop 与 JVM 收尾 |
| Deployment | `preStop: sleep 5` | 等 Service 的 Endpoints 先摘掉本 Pod——摘除是异步的，不等的话这段窗口里的新请求会打到已停止监听的端口 |

只设其中的一两项，都会让优雅停机在某条路径上失效。

---

## 4. 校验

清单的内部一致性有脚本检查，不需要集群：

```bash
python3 scripts/verify-k8s-manifests.py
```

它直接读这一个文件（按 `---` 拆成三个资源），核对跨文件引用：selector 能否选中 Pod、
卷名与卷定义是否对得上、`ConfigMap` 引用是否存在、**配置里的 `${...}` 是否都有
对应环境变量**、探针端口与 containerPort 是否一致、清单引用的镜像是否都在
Dockerfile 里有构建命令。

§10 盯「简化」的形态：目录只能有这一个 YAML、**清单里不许出现 Namespace 对象**
（命名空间由使用方预先创建）、集群内只允许 ConfigMap / Deployment / Service 三种对象、
不允许残留 Secret / PVC 引用、Service 必须是 NodePort 30818。

§11 盯 schema 里容易写错的一处：所有 `exec` 必须写成 `exec.command: [...]`。
把命令直接挂在 `exec:` 下面（YAML 列表）能过 YAML 解析、也能过
`--dry-run=client --validate=false`，但 apiserver 会拒绝：
`cannot unmarshal array into Go struct field LifecycleHandler...preStop.exec of type v1.ExecAction`。

§12 盯 root initContainer 的能力是否配齐：脚本里出现 `chmod` 就必须有 `CAP_FOWNER`、
出现 `chown` 就必须有 `CAP_CHOWN`。缺了的表现是 Init 阶段
`chmod: changing permissions of '/data': Operation not permitted`——
dry-run 和 schema 校验都不会拦，只有 Pod 真的跑起来才报。

这些错误 `kubectl apply` 时都不会报，只在运行时表现成 Pod Pending 或启动时报「解析不了占位符」。

---

## 5. 已验证到什么程度

| 项目 | 方式 | 结果 |
| --- | --- | --- |
| YAML 语法与跨文件引用 | `scripts/verify-k8s-manifests.py` | §1–§12 全部通过（3 个资源） |
| API schema 能否被 apiserver 接受 | `kubectl apply --dry-run=client --validate=true -f deploy/kubernetes/lakehouse-rest.yaml` | 三件对象均通过（`--validate=true` 会拉集群 OpenAPI，`preStop.exec` 那类 schema 错误能抓到；不需要写权限，命名空间正在 Terminating 时也能跑） |
| 配置能否被服务端解析 | 把 ConfigMap 里的 `application-k8s.yml` 原样交给服务端 | profile 生效、Hikari 连上 MySQL、`/v1/config` 返回配置的 warehouse |
| 鉴权与授权 | 同上实例 | 无令牌 401、错误令牌 401、正确令牌 200 |
| 端到端可用 | 同上实例 | 建库 200、建表 200、读回表列表正确、MySQL 中有对应记录 |

**Pod 真正跑起来这件事没有在本仓库验证过**：本地只有 `--dry-run=server` 这一层
（它证明 schema 与命名空间都对，但不会调度 Pod）。首次在集群里部署时重点看三处：
hostPath 的属主是否修好、`/tmp` 与 `/tmp/paimon-warehouse` 两层挂载的先后、
NodePort 是否被节点防火墙放行。

> 提一句代价：`--dry-run=client --validate=false` 只做 YAML 解析，抓不到 schema 级
> 错误——`preStop.exec` 写成裸数组就是被它放过去的。要提前发现这类问题，
> 本地要么用 `--dry-run=server`，要么靠 §11 那条断言。

---

## 6. 常见问题

**Pod 一直 ContainerCreating，事件里说找不到 configmap**
apply 的是 `deploy/kubernetes/lakehouse-rest.yaml` 这一个文件（三个文档按序创建）。
如果只 apply 了其中一段（例如把 Deployment 单独摘出来），就会缺 ConfigMap。

**apply 报 `namespaces "lakehouse-rest" not found`**
命名空间还没建（本版清单**不再声明 Namespace**）：`kubectl create namespace lakehouse-rest`。
早期版本把 Namespace 写在多文件里，`kubectl apply -f <目录>` 按文件名排序会让
ConfigMap 排在它前面，也会报同一个错——那种情况下 Namespace 其实建成了，只是靠后。

**命名空间一直 `Terminating`，里面已经空了**
`kubectl get ns lakehouse-rest` 显示 `Terminating` 且 `kubectl -n lakehouse-rest get all`
为空，通常是 finalizer 卡住（残留的 APIService、webhook 或 CRD 未清理干净）：

```bash
kubectl get ns lakehouse-rest -o json | jq '.spec.finalizers'
kubectl get namespace lakehouse-rest -o json \
  | jq '.spec.finalizers = []' \
  | kubectl replace --raw /api/v1/namespaces/lakehouse-rest/finalize -f -
```

强制清 finalizer 前先确认里面真的没有还在用的资源（PV、外部 LoadBalancer 等）。

**apply 报 `cannot unmarshal array into Go struct field ... preStop.exec of type v1.ExecAction`**
`exec` 下少了 `command:` 键。正确形状是
`exec: {command: [sleep, "5"]}`，不是 `exec: [sleep, "5"]`。
`--dry-run=client --validate=false` 看不出这个错，用 `--dry-run=server` 才能提前发现。

**Pod 起不来，日志报 warehouse 目录不可写**
`prepare-warehouse` 没跑成功，或者节点上的 `/data/paimon-warehouse` 被别的
Pod/人以别的属主占着。先确认 initContainer 的日志：
`kubectl -n lakehouse-rest logs <pod> -c prepare-warehouse`。
手工修：`sudo chmod 0750 /data/paimon-warehouse && sudo chown -R 10001:10001 /data/paimon-warehouse`。

**initContainer 报 `chmod: changing permissions of '/data': Operation not permitted`**
两种成因，按顺序排查：
1. `securityContext.capabilities` 只 add 了 `CHOWN`，缺 `FOWNER`——`drop: [ALL]` 之后
   root 也只剩显式列出的能力。补上 `FOWNER`（§3.3）。
2. 命令顺序是 `chown` 在前、`chmod` 在后：`chown` 已经把属主改成 10001，
   随后的 `chmod` 就是在改别人的文件。调成先 `chmod` 再 `chown`。

如果节点上的目录根本不允许改（NFS/CIFS 带 `root_squash`、或宿主机是
只读挂载），那不是能力问题，`chmod` 永远会 EPERM——这种场景要把 warehouse
换成对象存储，或改用能正常做属主管理的 PVC。

**表在、数据没了**
容器内 warehouse 路径与 `catalog.warehouse` 对不上（见 2.4），或者
`/tmp` 与 `/tmp/paimon-warehouse` 的挂载顺序被写反了（数据写进了 emptyDir）。

**启动失败，报 schema validation 错误**
外部 MySQL 的表结构与 jar 里的实体定义不一致。外部库需要自己执行
`sql/schema-mysql.sql`（新建）或做迁移（升级）。

**ImagePullBackOff，但 `docker images` 里明明有这个镜像**
镜像是本地构建的，节点拉的却不是同一个。单节点集群用 2.2 的 `load` 命令；
多节点集群推仓库。混合架构集群要推多架构镜像。

**从集群外连不上 30818**
先确认 `kubectl -n lakehouse-rest get svc` 里 `nodePort: 30818` 已生效，
再确认节点安全组/防火墙放行该端口；也可以用 2.5 的 `port-forward` 绕开网络问题。
