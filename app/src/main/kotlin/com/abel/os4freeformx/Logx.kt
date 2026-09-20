package com.abel.os4freeformx

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量日志：always 走 Xposed 日志（LSPosed 界面可看），verbose 走 logcat。
 * 热路径禁止调用 verbose（见 ponytail 注释）。
 */
object Logx {
    private val xposedLines = AtomicInteger(0)
    @Volatile private var mod: XposedModule? = null
    @Volatile var verbose: Boolean = Constants.DEF_ENABLE_LOG

    fun attach(m: XposedModule) {
        mod = m
    }

    fun always(msg: String) {
        val n = xposedLines.incrementAndGet()
        val line = if (n <= 400) msg else if (n == 401) "…日志超限，后续仅 logcat" else null
        if (line != null) {
            runCatching { mod?.log(Log.INFO, Constants.TAG, line) }
        }
        if (verbose || line != null) runCatching { Log.i(Constants.TAG, msg) }
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
