package com.abel.os4freeformx

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量日志，两条通道分开限流（2026-11-03）。
 *
 * - `always`：里程碑日志。Xposed 面板 + logcat 双写，**两条通道各自有上限**。
 *   历史问题：logcat 侧曾无条件写，热路径日志会无限增长，把
 *   `/data/adb/lspd/log/verbose_*.log` 刷到几 MB（实测单模块占 78%）。
 * - `v`：详细日志，仅 logcat，受设置项「记录详细日志」（`Cfg.log`，默认关）控制。
 *   探针、逐事件（MotionEvent / 每次方法调用）之类的高频日志一律走这里。
 * - `once`：同一 key 全进程只记一条，适合"只想知道发生过一次"的场景。
 */
object Logx {
    /** Xposed 面板上限（LSPosed 界面按行渲染，过大会卡）。 */
    private const val MAX_XPOSED_LINES = 4000

    /** logcat 上限：verbose 日志是 logcat 镜像，不限流会把 lspd 日志刷到几 MB。 */
    private const val MAX_LOGCAT_LINES = 8000

    private val lines = AtomicInteger(0)
    @Volatile private var mod: XposedModule? = null
    @Volatile var verbose: Boolean = Constants.DEF_ENABLE_LOG

    fun attach(m: XposedModule) {
        mod = m
    }

    fun always(msg: String) {
        val n = lines.incrementAndGet()
        when {
            n <= MAX_XPOSED_LINES -> runCatching { mod?.log(Log.INFO, Constants.TAG, msg) }
            n == MAX_XPOSED_LINES + 1 -> runCatching {
                mod?.log(Log.INFO, Constants.TAG, "…Xposed 日志达 $MAX_XPOSED_LINES 条上限，后续仅剩 logcat")
            }
        }
        when {
            n <= MAX_LOGCAT_LINES -> runCatching { Log.i(Constants.TAG, msg) }
            n == MAX_LOGCAT_LINES + 1 -> runCatching {
                Log.i(Constants.TAG, "…logcat 日志达 $MAX_LOGCAT_LINES 条上限，本进程后续静默（重启进程恢复）")
            }
        }
    }

    fun v(msg: String) {
        if (!verbose) return
        runCatching { Log.d(Constants.TAG, msg) }
    }

    private val onceKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** once 语义：同一 key 全进程只记一条，避免热路径刷日志。 */
    fun once(key: String, msg: String) {
        if (onceKeys.add(key)) always(msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        runCatching { Log.e(Constants.TAG, msg, t) }
        runCatching { mod?.log(Log.ERROR, Constants.TAG, if (t == null) msg else "$msg: $t") }
    }
}
