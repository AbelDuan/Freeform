package com.abel.os4freeformx

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import java.lang.reflect.Proxy

/**
 * 折叠期间的触摸拦截 —— **用 SystemUI 自己的 InputMonitor + pilfer**。
 *
 * <h2>为什么是这条路</h2>
 * 前面试过两条都不行：
 * 1. **全屏覆盖窗口**（`TYPE_SYSTEM_ERROR`）：一旦没及时撤下，整机（含锁屏）失去触摸。
 *    实机出过「锁屏都解不了」，已彻底弃用。
 * 2. **在 system_server 拦截输入分发**：本模块始终没能注入 system_server 进程，
 *    拦截压根装不上（日志实证：模块只出现在 com.android.systemui）。
 *
 * 现在用 SystemUI 自带的 `InputMonitorCompat` + `pilfer`：
 * - 它就是导航手势用的同一套机制，`pilfer` 能把触摸**从应用手里抢过来**
 * - **不创建任何窗口** ⇒ 不可能残留、不可能锁死屏幕
 * - **手指已经在屏幕上也能抢走** ⇒ 满足「哪怕在触摸时折叠也能屏蔽」
 *
 * <h2>安全性</h2>
 * 只在折叠判定期间调用 `pilfer`，抢的也只是**当前这一条手势**。
 * 没有"保持拦截"的状态 —— 抢完即结束，下一次触摸照常分发给应用。
 * 最坏情况是误抢一次手势，绝不会让屏幕整体失去触摸。
 *
 * <p>本类只作用于 [com.android.systemui] 进程；`GestureHandle` 已有自己的
 * 监听器（只旁听），这里是一个独立的、可主动 pilfer 的监视器。
 */
internal object FoldTouchBlock {

    private const val TAG = "折叠触摸屏蔽"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var monitor: Any? = null
    /** 真正的 android.view.InputMonitor（pilferPointers 在它身上，不在 Compat 包装上）。 */
    @Volatile private var rawMonitor: Any? = null
    @Volatile private var receiver: Any? = null
    @Volatile private var installed = false

    /** 需要持续抢指针的截止时刻（uptimeMillis）。0 = 不在拦截期。 */
    @Volatile private var pilferUntilMs = 0L

    /** 本轮已经抢过的指针流数量（每次需要覆盖 pointerCount 条流）。 */
    @Volatile private var pilferedCount = 0
    /** 本轮已经打卡的前一次 pointerCount，用来判断是否需要补抢。 */
    @Volatile private var coveredPointers = 0

    @Volatile private var loader: ClassLoader? = null

    /** 折叠开始时调用：从此刻起 [durationMs] 内，把手放在屏幕上的触摸抢走。 */
    fun holdFor(durationMs: Long) {
        val until = SystemClock.uptimeMillis() + durationMs
        if (until > pilferUntilMs) {
            pilferUntilMs = until
            // 新的一轮拦截：允许对下一条手势再抢一次
            pilferedCount = 0
            Logx.always("$TAG: 开始拦截 ${durationMs}ms")
            ensureInstalled()
            // 手指可能此刻正按着：立刻抢一次，不等下一个 DOWN
            tryPilfer("折叠开始，抢走进行中的手势")
        }
    }

    /** 提前结束拦截（正常路线不依赖它，到期会自动失效）。 */
    fun release(reason: String) {
        if (pilferUntilMs > SystemClock.uptimeMillis()) {
            Logx.always("$TAG: 结束拦截（$reason）")
        }
        pilferUntilMs = 0L
        pilferedCount = 0
    }

    private fun shouldBlock(): Boolean {
        val u = pilferUntilMs
        if (u <= 0L) return false
        if (SystemClock.uptimeMillis() >= u) {
            pilferUntilMs = 0L
            pilferedCount = 0
            return false
        }
        return true
    }

    /** 每次拿到输入事件时调用：拦截期内把这条手势抢过来。 */
    private fun onInput(ev: MotionEvent) {
        if (!shouldBlock()) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势开始：允许再抢
                pilferedCount = 0
                pilferForAllPointers(ev, "拦截期内按下")
            }
            // ★ 用户 2026-11-20：「折叠的时候是单侧屏蔽了，触摸另外一侧没有」。
            //
            //   根因：pilferPointers 一次只抢**当前这一条指针流**。用户两根手指
            //   分别按在屏幕左右两侧 = 两条指针流，抢了第一条，第二条仍归分屏应用
            //   ⇒ 只能挡住一侧。
            //
            //   解法：按**当前指针数量**连续抢。`ev.pointerCount` 是这一帧真实按着的
            //   手指数，抢够这么多次就覆盖了全部指针流。
            //   （不能只等 ACTION_POINTER_DOWN：两指同时按下时只有一个 ACTION_DOWN，
            //     根本不会产生 POINTER_DOWN 事件。）
            MotionEvent.ACTION_POINTER_DOWN -> {
                pilferedCount = 0
                pilferForAllPointers(ev, "新手指按下")
            }
            else -> {
                // 手指本来是压着屏幕滑的（折叠开始前就按下了）：
                // 没有新的 DOWN，也要在第一个样本上抢一次
                if (pilferedCount < ev.pointerCount) pilferForAllPointers(ev, "拦截期内补抢")
            }
        }
    }

    /**
     * 按当前指针数量连续抢指针。
     *
     * <p>`pilferPointers` 一次只抢一条指针流；两根手指压两侧就是两条流，
     * 所以按 `ev.pointerCount` 抢同样次数，确保每条流都被抢走。
     */
    private fun pilferForAllPointers(ev: MotionEvent, why: String) {
        val n = ev.pointerCount.coerceAtLeast(1)
        while (pilferedCount < n) {
            pilferedCount++
            tryPilfer("$why（第 $pilferedCount/$n 条指针流）")
        }
        coveredPointers = n
    }

    /** 真正执行 pilfer。同一条手势只成功执行一次。 */
    @Volatile private var methodProbed = false

    private fun tryPilfer(why: String) {
        val m = rawMonitor ?: return
        runCatching {
            m.javaClass.getMethod("pilferPointers").invoke(m)
            Logx.always("$TAG: 已抢走触摸（$why）")
        }.onFailure {
            Logx.e("$TAG: pilfer 失败", it)
        }
    }

    /** 懒安装监视器（首帧之后才装，避免和 SystemUI 启动抢资源）。 */
    fun ensureInstalled() {
        if (installed) return
        val cl = loader ?: return
        installed = true
        main.post {
            runCatching { doInstall(cl) }
                .onFailure {
                    installed = false
                    Logx.e("$TAG: 监视器安装失败", it)
                }
        }
    }

    /** 由 SystemUI 安装时注入 ClassLoader，并立即挂载监视器（便于启动即看到方法列表）。 */
    fun attach(cl: ClassLoader) {
        loader = cl
        ensureInstalled()
    }

    private fun doInstall(cl: ClassLoader) {
        val monitorType = Class.forName(
            "com.android.systemui.shared.system.InputMonitorCompat", false, cl
        )
        val listenerType = Class.forName(
            "com.android.systemui.shared.system.InputChannelCompat\$InputEventListener", false, cl
        )
        val listener = Proxy.newProxyInstance(cl, arrayOf(listenerType)) { _, method, args ->
            if (method?.name == "onInputEvent" && args != null && args.size == 1
                && args[0] is MotionEvent
            ) {
                runCatching { onInput(args[0] as MotionEvent) }
                    .onFailure { Logx.e("$TAG: 处理输入事件异常", it) }
            }
            null
        }
        // ⚠️ chained=0（与 GestureHandle 一致，实测能收到事件）。
        //    chained=1 实测收不到任何事件 —— 那是"允许拦截"模式，
        //    需要额外的 InputConsumer 注册，仅靠 getInputReceiver 拿不到事件流。
        //    抢指针靠的是 InputMonitor.pilferPointers，不依赖 chained。
        val m = monitorType.getConstructor(String::class.java, Integer.TYPE)
            .newInstance("os4ffx-fold-block", 0)
        val r = monitorType.getMethod(
            "getInputReceiver", Looper::class.java, android.view.Choreographer::class.java, listenerType
        ).invoke(m, Looper.getMainLooper(), android.view.Choreographer.getInstance(), listener)
        monitor = m
        receiver = r
        // pilferPointers 属于 android.view.InputMonitor（Compat 内部字段 mInputMonitor），
        // 不在 InputMonitorCompat 本身 —— 实机反射确认：Compat 只有 dispose/getInputReceiver。
        runCatching {
            val f = monitorType.getDeclaredField("mInputMonitor")
            f.isAccessible = true
            rawMonitor = f.get(m)
            Logx.always("$TAG: 已取到 InputMonitor，可调 pilferPointers")
        }.onFailure { Logx.e("$TAG: 取 mInputMonitor 失败", it) }
        // 立刻枚举真实方法名：本机 InputMonitorCompat 的 API 与 AOSP 不同，
        // 必须先看清到底有哪个方法，才知道该调谁去抢指针。
        val names = m.javaClass.declaredMethods.joinToString(", ") { "${it.name}(${it.parameterTypes.size})" }
        Logx.always("$TAG: InputMonitor 已挂载，方法=[$names]")
    }
}
