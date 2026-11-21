package com.abel.os4freeformx

/**
 * 给 Java 侧（旋屏三件套，从 OS4 Themer 原样移植）用的日志适配层。
 *
 * Java 代码不方便直接调 Kotlin 的 [Logx]，所以这里提供静态入口：
 * 启动时由 [Hooks] 用 `LogUtil.setSink { Logx.always(it) }` 把 Kotlin 日志接进来。
 */
object LogUtil {
    /** Java 侧调用的接口（按 Java 习惯写成带 always 方法的匿名类）。 */
    interface Sink {
        fun always(msg: String)
    }

    private val ONCE: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    @Volatile
    private var sink: Sink? = null

    @JvmStatic
    fun setSink(s: Sink?) {
        sink = s
    }

    @JvmStatic
    fun logAlways(msg: String) {
        val s = sink ?: return
        try {
            s.always(msg)
        } catch (_: Throwable) {
            // Java 侧回调异常绝不能把主流程带崩
        }
    }

    @JvmStatic
    fun logAlwaysOnce(tag: String, msg: String) {
        if (ONCE.add(tag)) logAlways(msg)
    }
}
