package io.github.melin.lakehouse.rest.spark.policy

/**
 * 引擎侧策略强制失败的统一出口（fail-closed 的形状，§5.4 / §5.6）。
 *
 * <p><b>为什么是一个专门的异常。</b>这条路径上的失败语义是「拒读，并说清为什么」：
 * 拉不到策略、解析不出表名、表达式里的列在 schema 里找不到——每一类都**必须**中断查询，
 * 而不是降级成「没策略」。设计稿第 3.4 节 Kyuubi #3186 的教训：漏判的默认症状是
 * 静默放行，所以这条路径上一切「跳过」都等于放行明文。
 *
 * <p>继承 {@code RuntimeException}：它从 Catalyst 规则与 planner strategy 里抛出，
 * 两者都没有受检异常的口子。报错文本直接面向用户（会出现在查询异常里），因此
 * 都写成「能照做的提示」。
 */
final class PolicyEnforcementException(message: String, cause: Throwable)
  extends RuntimeException(message, cause) {

  def this(message: String) = this(message, null)
}
