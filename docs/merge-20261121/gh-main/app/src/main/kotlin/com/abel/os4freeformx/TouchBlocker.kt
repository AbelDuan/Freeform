package com.abel.os4freeformx

import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface

/**
 * 折叠期间在**输入分发层**丢弃触摸（不创建任何窗口）。
 *
 * ## 为什么不用覆盖窗口
 * `TYPE_SYSTEM_ERROR` 覆盖层一旦没能及时撤下，整块屏幕（含锁屏）都会失去触摸 ——
 * 实机出过"锁屏解不开"的严重故障。覆盖层这条路在折叠屏上不安全，彻底弃用。
 *
 * ## 现在的做法
 * hook 输入分发链上的 `injectInputEvent`，在窗口分发**之前**直接丢弃：
 * · 不创建窗口 ⇒ 不可能留下残留覆盖层
 * · 在分发层丢弃 ⇒ 手指已经在屏幕上按着时也能立刻生效
 *
 * ## ⚠️ 重要：这不是"转场期间屏蔽点击"的开关
 * 本类只服务「折叠手势」，由 [FoldSplitTrigger] 在折叠开始/结束时通过
 * [hold] / [release] 精确控制时间窗。**不要**在分屏转场时用它去挡点击 ——
 * 那会让用户点了没反应（正是 2026-11-21 排查的那个现象）。
 */
object TouchBlocker {

    private const val TAG = "触摸屏蔽"

    /** 屏蔽到期时刻（uptimeMillis）。0 = 不屏蔽。 */
    @Volatile private var untilMs = 0L
    @Volatile private var installed = false
    @Volatile private var dropped = 0L

    /**
     * 在 system_server 侧安装拦截。
     *
     * @return 成功挂上的 hook 数（0 表示本机接口不匹配）
     */
    fun installSystem(m: MainHook, cl: ClassLoader): Int {
        if (installed) return 0
        installed = true
        var n = hookInject(m, cl, "android.hardware.input.InputManagerGlobal")
        if (n == 0) n += hookInject(m, cl, "android.hardware.input.InputManager")
        if (n <= 0) {
            Logx.always("$TAG: 未能安装拦截（本机接口不匹配）⇒ 折叠期间不做触摸屏蔽")
            return 0
        }
        Logx.always("$TAG: 输入分发拦截已安装（$n 处）")
        return n
    }

    private fun hookInject(m: MainHook, cl: ClassLoader, className: String): Int {
        val cls = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: return 0
        var n = 0
        for (method in cls.declaredMethods) {
            if (method.name != "injectInputEvent") continue
            val pts = method.parameterTypes
            if (pts.isEmpty() || !InputEvent::class.java.isAssignableFrom(pts[0])) continue
            m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val ev = chain.getArg(0) as? MotionEvent
                if (ev == null || !shouldBlock()) {
                    chain.proceed()
                } else {
                    noteDrop()
                    true
                }
            })
            n++
        }
        return n
    }

    /** 开始遮蔽 [durationMs] 毫秒（取较晚的到期时刻，不缩短已经更晚的）。 */
    fun hold(durationMs: Long) {
        val target = SystemClock.uptimeMillis() + durationMs
        if (target <= untilMs) return
        val wasIdle = untilMs <= SystemClock.uptimeMillis()
        untilMs = target
        if (wasIdle) Logx.always("$TAG: 开始遮蔽 ${durationMs}ms（输入分发层，无窗口）")
    }

    /** 提前放行。 */
    fun release(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (untilMs > now) {
            Logx.always("$TAG: 提前放行（$reason），本次共丢弃 $dropped 个事件")
        }
        untilMs = 0L
        dropped = 0L
    }

    fun shouldBlock(): Boolean {
        val until = untilMs
        if (until <= 0L) return false
        if (SystemClock.uptimeMillis() < until) return true
        untilMs = 0L
        if (dropped > 0) {
            Logx.always("$TAG: 到期放行，本次共丢弃 $dropped 个事件")
            dropped = 0L
        }
        return false
    }

    private fun noteDrop() {
        dropped++
        if (dropped == 1L) Logx.always("$TAG: 首个触摸事件已被丢弃（说明分发层拦截有效）")
    }
}
