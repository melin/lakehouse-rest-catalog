FROM eclipse-temurin:17-jre-noble

# 构建方式（两个都写在这里，不只写在 README 里）：
#
#   mvn -o -pl :lakehouse-rest-server -am clean package -DskipTests
#   docker build -t lakehouse-rest-server:0.0.1 .
#
# 第二条是脚本 verify-k8s-manifests.py §9 的输入：它从本文件抓「构建镜像的那条命令」
# 里的镜像名，再核对 deploy/kubernetes 下的清单有没有引用同一个镜像。所以改镜像名时
# 这里与清单必须一起改——忘了改清单，集群里表现成 ImagePullBackOff，而
# `docker images` 里明明躺着这个镜像。

# curl 不是运行时依赖，只为两件事装：Docker 的 HEALTHCHECK，以及容器内排查
# （kubectl exec 进容器后能直接 curl 本机端点）。Kubernetes 的探针不依赖它。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行。uid/gid 固定成 10001，便于与 Kubernetes 的 runAsUser、
# emptyDir 的 fsGroup 对齐；随机 uid 会让挂载卷的属主判定变得不可预期。
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# 宿主机构建好的 jar。文件名是 POM 的约定产物名——改版本号时这里要跟着改，
# `.dockerignore` 那条放行规则写的是通配符，不需要动。
#
# 落位时改名 app.jar：ENTRYPOINT 里的路径就不会随版本号漂移。
COPY --chown=app:app lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-0.0.1-SNAPSHOT.jar /app/app.jar

# 建表语句一并放进镜像：部署时 initContainer 要拿它初始化空库，
# 直接从镜像里取可以避免「镜像里的代码」与「集群里的 DDL」版本不一致。
# （DDL 与 jar 都取自同一个仓库状态，因此仍然是同版本的一对。）
#
# 为什么先建目录、再单独复制文件，而不是一句 COPY 带 --chmod 搞定：
#
# 1) 不能省掉权限设置。COPY 会原样保留源文件的权限位，而
#    scripts/gen-mysql-ddl.sh 用 mktemp 生成中间文件（0600）再拷到交付路径，
#    于是镜像里也会是 0600——只有属主 app(10001) 读得到，
#    而 K8s 的 initContainer 以别的 uid 运行时直接失败。
#
# 2) 但也不能直接把 --chmod 加在一条指向 /app/sql/schema-mysql.sql 的 COPY 上。
#    Docker 会为多级目标路径自动创建中间目录，而 --chmod 同样作用在这些目录上，
#    结果是 /app/sql 变成 drw-r--r--（0644）。目录没有 x 位就无法遍历，
#    里面的文件即便 0644 也读不到，而报错说的是
#    "cannot open /app/sql/schema-mysql.sql: Permission denied"——指向文件，
#    真正的原因在目录上，很容易往错的方向查。
#
# 所以目录与文件的权限分开设：目录 0755（可遍历），文件 0644（可读不可写）。
RUN mkdir -p /app/sql && chown app:app /app/sql && chmod 0755 /app/sql

COPY --chown=app:app --chmod=0644 sql/schema-mysql.sql /app/sql/schema-mysql.sql

USER 10001:10001

EXPOSE 8080

# 用 JAVA_TOOL_OPTIONS 而不是 ENTRYPOINT 里的 sh -c "$JAVA_OPTS"：
# 后者会多起一层 shell，信号（SIGTERM）需要靠 exec 才能正确传给 JVM，
# 而容器里 SIGTERM 不达会导致每次滚动更新都等到 grace period 结束才被杀。
# 这里用 JVM 自己认识的环境变量，ENTRYPOINT 保持 exec 形式，信号直达。
#
# -XX:MaxRAMPercentage 让堆随容器内存限额伸缩；配合 K8s 的 limits 使用。
# ExitOnOutOfMemoryError 让 OOM 后进程退出由编排层重启，而不是留着一个抖动的进程。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# 探针打 GET /v1/config：它是规格里的目录发现端点，也是唯一在授权开启时
# 仍豁免权限判定的端点（见 CatalogAccessRules.isPublic 与 docs/authorization.md）。
# 鉴权开启时无令牌会得到 401，那同样说明进程已经能处理请求，因此把 401/403 也算作存活。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/v1/config \
        | grep -qE '^(200|401|403)$' || exit 1

# exec 形式：java 是 PID 1，SIGTERM 直接到达 JVM，由 Spring Boot 走优雅停机。
# Kubernetes 传参时用 args:（会追加在这条命令之后），
# 例如 args: ["--spring.profiles.active=h2"]；JVM 参数则用 JAVA_TOOL_OPTIONS 覆盖。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
