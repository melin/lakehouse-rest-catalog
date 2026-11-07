package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 守「作业日志里到底有没有结论」。
 *
 * <p>{@code SparkSessions.open()} 里那句 {@code setLogLevel("WARN")} 改的是**根** logger 的
 * 级别，本模块的 logger 就挂在这棵树上——于是「压掉 Spark 那几十行启动噪音」会顺手把
 * 「扫了哪张表、每张表几条 finding、批次最后成没成」也一起压掉，而且**不报错、只是安静**。
 *
 * <p>这个坑只能在真环境看见（单测里没有 SparkContext，本地看日志时也没有容器），
 * 真集群 e2e 第一次跑就是这样：作业成功、逐表回报都到了，{@code kubectl logs} 里只有
 * Spark 启动那几十行。所以这里用 log4j2 直接复刻那一步效果，把「修好了」与
 * 「别把 Spark 的噪音一起放开」两件事都钉住。
 *
 * <p>用 log4j2 的 API 而不是 slf4j：单测的 slf4j 绑定是 slf4j-simple，它没有级别切换这种
 * 东西；而集群里（{@code spark-submit}）绑的是 log4j2，事故也发生在那一侧。
 */
class ApplicationLogLevelTests {

    /** 本模块真实存在的子包：扫描任务的日志来自 {@code …rest.scan}。 */
    private static final String APPLICATION_LOGGER = "io.github.melin.lakehouse.rest.scan";

    /** Spark 自己的一个类名，用来确认噪音仍然被压着。 */
    private static final String SPARK_LOGGER = "org.apache.spark.sql.SparkSession";

    /** 测试会改动全局日志级别，跑完还原，免得影响别的测试类的输出。 */
    private final Level originalRoot = LogManager.getRootLogger().getLevel();

    @AfterEach
    void restoreRootLevel() {
        Configurator.setRootLevel(originalRoot == null ? Level.ERROR : originalRoot);
    }

    @Test
    void applicationPackageKeepsLoggingAtInfoAfterSparkQuietensTheRootLogger() {
        Logger application = LogManager.getLogger(APPLICATION_LOGGER);
        Logger spark = LogManager.getLogger(SPARK_LOGGER);

        // 复刻故障现象：这一步之后，本模块的 INFO 应当是不可见的
        Configurator.setRootLevel(Level.WARN);
        assertFalse(application.isInfoEnabled(),
                "前提：根级别压到 WARN 之后，本模块的 INFO 确实会被吞掉");

        SparkSessions.restoreApplicationLogLevel();

        assertTrue(application.isInfoEnabled(),
                "修好之后本模块必须重新看得见 INFO，否则作业日志里仍然只有 Spark 启动那几十行");
        assertFalse(spark.isInfoEnabled(),
                "不能顺手把整棵树都放开：Spark 的启动噪音要留在 WARN");
    }
}
