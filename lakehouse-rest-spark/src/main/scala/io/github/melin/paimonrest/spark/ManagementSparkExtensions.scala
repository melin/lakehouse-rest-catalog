package io.github.melin.paimonrest.spark

/**
 * 旧包名下的**兼容壳**，只为让既有部署不改配置就能升级。
 *
 * <p>这个类名是**唯一一处被写进用户配置的值**——`spark.sql.extensions` 填的是全限定类名。
 * 包名从 `io.github.melin.paimonrest` 换成别的之后，既有 Spark 会话会在启动时以
 * `ClassNotFoundException` 收场，而且报错点在 Spark 会话初始化，离「升级了服务端依赖」
 * 很远，排查成本很高。
 *
 * <p>所以旧名字保留下来：它是个空壳，只继承新实现、不覆盖任何方法。
 * Spark 拿到配置里的类名后实例化并当作 `SparkSessionExtensions => Unit` 用，
 * 而行为全在父类的 `apply` 里，子类实例完全等价。
 *
 * <p><b>新部署请写 `io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions`。</b>
 * 这个壳只是过渡，不承诺长期存在；将来要撤掉它时，先确认没有部署还在用旧名字。
 *
 * <p><b>为什么只有这一个壳，没有为 `io.github.melin.paimon.rest` 再留一个：</b>
 * 那个名字是一次未提交的工作区改动，从未构建出可发布的产物（`git log` 里没有它），
 * 因此不可能有部署在用它。兼容面只覆盖「真的在别人机器上跑过」的名字。
 *
 * <p>注意：全仓包名替换的脚本（`outputs/maven-module-refactor/` 下那两个）
 * 会把本文件里的包名一起改掉，从而让这个壳失效。它是**故意保留的旧包名**，
 * 重跑那类脚本时要把它排除，或在跑完后检查这里仍是 `paimonrest`。
 */
@deprecated("包名已改为 io.github.melin.lakehouse.rest，请更新 spark.sql.extensions", "0.0.1")
class ManagementSparkExtensions extends io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions
