package io.github.melin.lakehouse.rest.spark

/**
 * 测试用的原生解析器替身（Spark 3.5）。
 *
 * <p>共享基类 {@link ParserDelegateStub} 已覆盖 3.5 的全部抽象成员，
 * 这里不需要补任何方法——存在的意义只是给共享测试源码一个可实例化的类名。
 */
class SparkParserDelegate extends ParserDelegateStub
