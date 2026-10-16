# 表存储优化任务的运行镜像。
#
# 这不是本服务端的镜像（那个在仓库根目录的 Dockerfile），而是**被服务端投到 k8s 的
# 那个 Spark 任务**的镜像：服务端按 `paimon.rest.optimization.image` 创建 k8s Job，
# 容器里跑的就是这里打出来的东西，容器的退出码就是 Job 的成功/失败依据。
#
# 为什么需要一个单独的镜像，而不是让 Job 去拉一个通用 Spark 镜像再挂载 jar：
# 优化任务要在集群上执行 `CALL sys.compact` 一类的语句，因此镜像里必须有
# **与目标集群一致的 Paimon Spark 运行时**（paimon-spark bundle + 它依赖的 FileIO 实现）。
# 把这份运行时钉进镜像，与服务端「Paimon 版本只影响引擎侧」的分工一致：
# 服务端不需要 Paimon，优化任务只需要 Paimon。
#
# 用法（先在宿主机上打好包；镜像里不装 Maven，与根 Dockerfile 的理由相同）：
#
#   JAVA_HOME=/path/to/jdk-17 mvn -o -DskipTests -pl :lakehouse-rest-optimizer package
#   docker build -f lakehouse-rest-optimizer/Dockerfile -t lakehouse-rest-optimizer:0.0.1 .
#   docker push <registry>/lakehouse-rest-optimizer:0.0.1
#   # 再把 paimon.rest.optimization.image 指向推上去的地址
#
# 多架构（Apple Silicon 上开发、x86 节点上跑）与根 Dockerfile 同一套做法，
# 见 scripts/build-image.sh 的说明——这里没有单独的脚本：与那个镜像不同，
# 本镜像的产物需要先经过一次 maven package（把 Paimon 的运行时 jar 拷进 target/jars），
# 多一层包装反而容易让人漏掉那一步。
#
# 基线镜像用官方 Spark **发行版**而不是 JRE + 自己拼 Spark：spark-submit 那套 JDK
# 打开参数、log4j2 配置、Hadoop native 库都在发行版里配好了，自己拼一遍是最容易出错、
# 又最没有收益的那部分工作。版本必须与根 POM 的 `spark.version` / `paimon.version` 对齐。

# 与 lakehouse-rest-spark 的目标运行时一致：Spark 3.5.9 / Scala 2.12 / Java 17。
FROM apache/spark:3.5.9-scala2.12-java17-python3-ubuntu

WORKDIR /opt/optimizer

# 本模块的产物。它是普通 jar（不是 uber jar）：Spark / Jackson 依赖都是 provided，
# 由发行版提供。
COPY lakehouse-rest-optimizer/target/lakehouse-rest-optimizer-0.0.1-SNAPSHOT.jar /opt/optimizer/optimizer.jar

# Paimon 的 Spark bundle 与 S3 FileIO 插件。它们由本模块 POM 的
# maven-dependency-plugin 拷到 target/jars（Docker 的 COPY 不能取构建上下文之外的文件，
# 也不能展开 ~），这里只负责搬进 spark 的 jars 目录。
#
# 为什么钉进镜像而不是靠 spark.jars.packages 启动时拉：生产集群常常没有公网出口，
# 而「任务起不来」的最常见原因就是拉包失败。对象存储的访问凭据仍由服务端下发，
# 那是数据面的事，与这里无关。
COPY lakehouse-rest-optimizer/target/jars/paimon-spark-3.5_2.12-2.0.0.jar /opt/spark/jars/
COPY lakehouse-rest-optimizer/target/jars/paimon-s3-2.0.0.jar /opt/spark/jars/

# 容器就是 driver：整个批次只建一次 Spark 会话，在一张 pod 里以 local 模式跑完所有表。
#
# 为什么不是 `spark-submit --master k8s://...`（在容器里再投一个 driver pod）：
# 那会让 Job 的退出码变成「提交器」的退出码，而不是 Spark 应用的，服务端就没法用
# k8s Job 的 succeeded/failed 计数判定批次终态（见 KubernetesJobSubmitter.readState）。
# 组批本来的目的就是摊薄会话启动开销，一个批次一张 pod 已经达到了；要横向扩的是
# 每张表内部的读写并行度，那由 pod 的 CPU/内存限额与 `local[N]` 决定。
#
# 参数（--job-id / --management-url / --token / --catalog-token）由服务端在创建
# k8s Job 时追加，见 KubernetesJobSubmitter —— 这里不写默认值：
# 缺参数时优化任务会以「这个参数由服务端生成」的提示退出（exit 2），比悄悄跑错更好。
ENTRYPOINT ["/opt/spark/bin/spark-submit", \
    "--master", "local[*]", \
    "--class", "io.github.melin.lakehouse.rest.optimizer.TableOptimizerJob", \
    "--conf", "spark.ui.enabled=false", \
    "/opt/optimizer/optimizer.jar"]
