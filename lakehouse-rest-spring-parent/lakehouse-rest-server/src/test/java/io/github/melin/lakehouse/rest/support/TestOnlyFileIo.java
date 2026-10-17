package io.github.melin.lakehouse.rest.support;

import java.util.Map;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;

/**
 * 只供测试用的 {@link FileIO} 桩：**不碰任何文件**，被真的用到就立刻抛。
 *
 * <p><b>为什么需要一个桩。</b>Iceberg 客户端在 {@code initialize} 时就会
 * {@code CatalogUtil.loadFileIO(io-impl, …)} 把 FileIO 装出来，而它的默认实现
 * {@code ResolvingFileIO} 声明了 {@code implements HadoopConfigurable}——
 * 于是**类加载**阶段就要 {@code org.apache.hadoop.conf.Configurable}。
 * 本仓库刻意不让 Hadoop 客户端栈进服务端类路径（见
 * {@code lakehouse-rest-spring-parent/pom.xml} 对 {@code paimon-format} 的
 * {@code hadoop-common} 排除及其说明：多一份 hadoop-common 会带进大版本不符的
 * Guava / protobuf，是常见冲突来源）。为了一个测试用例推翻那条不变量不划算，
 * 所以这里用一个不需要 Hadoop 的实现顶上，靠 {@code io-impl} 指定。
 *
 * <p><b>它不会让测试变得不真实。</b>客户端在这个测试里从不读写数据文件：
 * 建表、改 schema、读元数据，这些都由服务端写文件、客户端只走 HTTP 报文。
 * FileIO 唯一的作用是「能被构造出来」。真正的 Spark / Flink 客户端自带 Hadoop，
 * 走的是默认的 {@code ResolvingFileIO}——这条差异只落在**客户端环境**上，
 * 与服务端的协议实现无关。
 *
 * <p><b>三个方法都抛而不是返回 null。</b>这是一个活体断言：将来某条路径真的开始
 * 读数据文件时，测试会立刻炸在「这个桩不做文件 IO」上，而不是静默地拿到 null
 * 继续跑出一片绿。
 */
public class TestOnlyFileIo implements FileIO {

    @Override
    public void initialize(Map<String, String> properties) {
        // 没有可初始化的东西：这个实现不持有任何资源
    }

    @Override
    public InputFile newInputFile(String path) {
        throw new UnsupportedOperationException(
                "测试用的 FileIO 桩不读文件，却被要求读: " + path);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        throw new UnsupportedOperationException(
                "测试用的 FileIO 桩不写文件，却被要求写: " + path);
    }

    @Override
    public void deleteFile(String path) {
        throw new UnsupportedOperationException(
                "测试用的 FileIO 桩不删文件，却被要求删: " + path);
    }
}
