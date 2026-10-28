# 表存储优化任务的运行镜像。
#
# 这不是本服务端的镜像（那个在仓库根目录的 Dockerfile），而是**被服务端投到 k8s 的
# 那个 Spark 任务**的镜像：服务端按 `lakehouse.rest.optimization.image` 创建 k8s Job，
# 容器里跑的就是这里打出来的东西，容器的退出码就是 Job 的成功/失败依据。
#
# 为什么需要一个单独的镜像，而不是让 Job 去拉一个通用 Spark 镜像再挂载 jar：
# 优化任务要在集群上执行 `CALL sys.compact`（Paimon / Iceberg）与
# `OPTIMIZE` / `VACUUM`（Lance）这类语句，因此镜像里必须有**与目标集群一致的
# 三种格式的 Spark 运行时**（各自的 bundle + FileIO 实现）。把它们钉进镜像，
# 与服务端「格式只影响引擎侧」的分工一致：服务端不需要 Paimon / Iceberg / Lance，
# 优化任务只需要这三份运行时，一个镜像服务三种批次的表。
#
# 用法（先在宿主机上打好包；镜像里不装 Maven，与根 Dockerfile 的理由相同）：
#
#   JAVA_HOME=/path/to/jdk-17 mvn -o -DskipTests -pl :lakehouse-rest-optimizer package
#   docker build -f lakehouse-rest-optimizer/Dockerfile -t lakehouse-rest-optimizer:1.0.0 .
#   docker push <registry>/lakehouse-rest-optimizer:1.0.0
#   # 再把 lakehouse.rest.optimization.image 指向推上去的地址
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
FROM docker.1ms.run/apache/spark:3.5.9-scala2.12-java17-python3-ubuntu

WORKDIR /opt/optimizer

# 本模块的产物。它是普通 jar（不是 uber jar）：Spark / Jackson 依赖都是 provided，
# 由发行版提供。
COPY lakehouse-rest-optimizer/target/lakehouse-rest-optimizer-1.0.0.jar /opt/optimizer/optimizer.jar

# Paimon 的 Spark bundle 与 S3 FileIO 插件。它们由本模块 POM 的
# maven-dependency-plugin 拷到 target/jars（Docker 的 COPY 不能取构建上下文之外的文件，
# 也不能展开 ~），这里只负责搬进 spark 的 jars 目录。
#
# 为什么钉进镜像而不是靠 spark.jars.packages 启动时拉：生产集群常常没有公网出口，
# 而「任务起不来」的最常见原因就是拉包失败。对象存储的访问凭据仍由服务端下发，
# 那是数据面的事，与这里无关。
#
# **文件名不带版本号**：`maven-dependency-plugin` 的 `stripVersion=true` 让 target/jars
# 里的文件叫 `paimon-spark-3.5_2.12.jar` 这种固定名字，版本只由 pom 的 `paimon.version`
# 决定。这里曾经写死 `…-2.0.0.jar`，升到 2.1.0 后 COPY 直接失败
# （"failed to compute cache key … not found"，与「忘了 package」同一句报错）。
# 固定名字还顺带保证：target/jars 里残留的旧版本文件不会被装进镜像。
COPY lakehouse-rest-optimizer/target/jars/paimon-spark-3.5_2.12.jar /opt/spark/jars/
COPY lakehouse-rest-optimizer/target/jars/paimon-s3.jar /opt/spark/jars/

# Iceberg 的 Spark bundle。一个镜像同时服务三种格式的批次：会话的
# `spark.sql.extensions` 与 catalog 实现由任务按批次格式选，jar 本身一起放着即可。
#
# 版本必须与 lakehouse-rest-optimizer/pom.xml 的 iceberg.version 对齐（同样由 pom 决定，
# 文件名不带版本号，理由见上面 Paimon 两行的注释）；
# 对象存储是 s3:// 时还需要 org.apache.iceberg:iceberg-aws-bundle（本镜像没有内置，
# 见 docs/iceberg-storage-optimization.md 的「部署前提」一节）。
COPY lakehouse-rest-optimizer/target/jars/iceberg-spark-runtime-3.5_2.12.jar /opt/spark/jars/

# Lance 的 Spark bundle（connector + lance-core + 原生库 + arrow，全部 shade 在一个 jar 里）。
# 一个镜像同时服务三种格式的批次：会话的 `spark.sql.extensions` 与 catalog 实现由任务按
# 批次格式选，jar 本身一起放着即可——**三个扩展不共存于同一个会话**，只在同一个进程的
# 类路径上共存，这是安全的（各自注册自己的 parser 规则，不会同时生效）。
#
# 为什么是 bundle 而不是 `lance-spark-3.5_2.12`：薄包缺 org.lance.Dataset 与原生库，
# 补那些要手工复刻一份依赖闭包，还必须挑 arrow 版本（Spark 发行版自带 arrow，
# 同名类按 jar 文件名排序加载，版本对不上时表现为 NoSuchMethodError）。
COPY lakehouse-rest-optimizer/target/jars/lance-spark-bundle-3.5_2.12.jar /opt/spark/jars/

# Lance 打开数据集时反射访问 sun.nio.ch（native IO 的一段缓冲区映射），JDK 17 默认关闭。
#
# **必须写在 ENV 里，不能靠 SparkSession.builder().config(...)**：
# `--add-opens` 是 JVM 启动参数，进程起来之后再设没用；`spark.driver.extraJavaOptions`
# 只对它自己 fork 的 driver 生效，而我们的入口是 spark-submit 在本进程里 local 模式跑，
# 没有那层 fork。ENV 里对 driver 与将来的 executor JVM 都生效。
#
# 症状是运行到「打开数据集」时才抛 InaccessibleObjectException，而栈里没有一句话提到
# Lance——这也是为什么它值得单独一行注释。
ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

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
#
# `spark.driver.extraClassPath` 把 Lance 的 bundle **排到类路径最前面**，这不只是效率问题：
# bundle 里自带一份未 relocate 的 `org.apache.arrow.*`（connector 编译用的是 arrow 15/18），
# 而 Spark 发行版的 `arrow-vector-12.0.1.jar` 也在 `/opt/spark/jars` 里。两者的类名完全
# 相同，谁先被加载取决于 classpath 顺序——按文件名排序时 `arrow-vector-12.0.1.jar`
# 排在 `lance-spark-bundle-…` 前面，旧版本会赢，症状是 Lance 侧 `NoSuchMethodError`。
# extraClassPath 的条目在 spark-submit 拼出的 classpath 里是**前置**的，因此这一行
# 让 bundle 的 arrow 优先。代价是 Spark 自己的 arrow 被遮住——本任务不用 pandas UDF，
# 也不走 ArrowConverters，这条路径不受影响。
ENTRYPOINT ["/opt/spark/bin/spark-submit", \
    "--master", "local[*]", \
    "--class", "io.github.melin.lakehouse.rest.optimizer.TableOptimizerJob", \
    "--conf", "spark.ui.enabled=false", \
    "--conf", "spark.driver.extraClassPath=/opt/spark/jars/lance-spark-bundle-3.5_2.12.jar", \
    "/opt/optimizer/optimizer.jar"]
