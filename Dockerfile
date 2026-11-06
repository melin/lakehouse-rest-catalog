FROM docker.1ms.run/eclipse-temurin:17-jdk

# 构建方式（两个都写在这里，不只写在 README 里）：
#
#   mvn -o -pl :lakehouse-rest-server -am clean package -DskipTests
#   docker buildx build -t registry.cn-hangzhou.aliyuncs.com/melin1204/lakehouse-rest-catalog:1.0.0 --platform linux/amd64 . --push
#
# 第一条已经把控制台前端一起构建了：server POM 里的 frontend-maven-plugin 绑在
# generate-resources，跑 npm ci + vite build 把产物写进 src/main/resources/static/console。
# 前端产物不入库，但对本镜像的构建链**不再是一道手工步骤**——`mvn package` 就是完整的
# （需要本机有 Node，或用 -Dfrontend.skip=true 复用已有产物，见 server POM）。
# scripts/build-image.sh 仍在 buildx 之前用 unzip 查一次 jar 里有没有 /console，
# 把「忘记构建前端」提前成构建前的失败。
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

WORKDIR /app

# 宿主机构建好的 jar。文件名是 POM 的约定产物名——改版本号时这里要跟着改，
# `.dockerignore` 那条放行规则写的是通配符，不需要动。
#
# 落位时改名 app.jar：ENTRYPOINT 里的路径就不会随版本号漂移。
# 镜像里只有 jar，**没有** sql/schema-mysql.sql。
#
# 库结构由 jar 内 classpath 上的 db/migration/{vendor}/V1__baseline.sql 在启动时
# 由 Flyway 自动应用，因此不再需要第二份 DDL 进镜像。此前那一份是给「K8s 的
# initContainer 初始化空库」用的，而清单里从来没有这样一个 initContainer
# （deploy/kubernetes/lakehouse-rest.yaml 的 initContainers 只有 prepare-warehouse）——
# 也就是说，它一直在为一件不存在的事准备着。容器里也没有 mysql 客户端，
# 真要手工执行，运维还是得把它从镜像或仓库里取出来。
#
# 删掉这一段还顺带消掉了一个坑：为了让 initContainer（以别的 uid 运行）能读到那份
# 文件，这里曾经要 RUN mkdir + chmod 0755，再单独 COPY --chmod=0644。原因是
# `COPY --chmod=0644 a/b.sql /app/sql/b.sql` 会把权限也套到 Docker 自动创建的
# 中间目录上，/app/sql 变成 drw-r--r--（没有 x 位就无法遍历，里面文件什么权限都
# 读不到），而报错指向的是文件、不是目录。现在没有那一层了，这一段随之消失。
COPY lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar /app/app.jar

# 不创建专用 app 用户，容器以 root 身份运行（与 k8s 清单里去掉所有 securityContext 一致）。
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
