package com.abel.os4freeformx

import android.content.Context

/**
 * 在被 hook 的进程里取 Context。
 *
 * 注意：SystemUI 里 `getSystemContext()` 拿到的是包名 `android` 的 context（uid 却是 10193），
 * 用它去 `ContentResolver.call` 会被 `SecurityException: Given calling package android does not
 * match caller's uid` 拒掉（2026-09-19 实测）。所以拿到 context 后按进程名校正包名。
 */
object AppCtx {
    @Volatile private var cached: Context? = null

    fun get(): Context? {
        cached?.let { return it }
        // system_server：systemContext 的 package=android 与 uid 1000 匹配，provider 调用合法
        if (procName() == "system") {
            val sys = runCatching { systemContext() }.getOrNull()
            if (sys != null) {
                cached = sys
                return sys
            }
        }
        // 优先 currentApplication()：SystemUI 的 Application context 的 opPackageName 是正确的
        // com.android.systemui。模块加载时它可能还是 null，所以只有拿到真值才缓存（不能缓存兜底的
        // systemContext，否则整个进程生命周期里 op=android，provider/广播全被 SecurityException 拒掉）。
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull()
        if (app is Context) {
            cached = app
            Logx.once("appctx", "AppCtx: application ${app.packageName}/op=${runCatching { app.opPackageName }.getOrNull()}")
            return app
        }
        return runCatching { fixPackage(fromActivityThread()) }.getOrNull()
    }

    private fun procName(): String? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()

    /**
     * 进程名与 context 包名/opPackageName 不一致时校正。
     *
     * AMS 是用 **opPackageName** 校验 ContentProvider / 广播调用者的：
     * SystemUI 里 systemContext 的 packageName=android、op=android、uid=10193，
     * 于是 `ContentResolver.call` 与 `registerReceiver` 全部被 SecurityException 拒掉。
     * `createPackageContext` 只改 packageName，opPackageName 仍是 android，必须再显式设置。
     */
    private fun fixPackage(ctx: Context?): Context? {
        if (ctx == null) return null
        val proc = procName() ?: return ctx
        var fixed = ctx
        if (proc != ctx.packageName) {
            fixed = runCatching { ctx.createPackageContext(proc, 0) }.getOrDefault(ctx)
        }
        val op = runCatching { fixed.opPackageName }.getOrNull()
        if (op != proc) {
            runCatching {
                Class.forName("android.app.ContextImpl")
                    .getMethod("setOpPackageName", String::class.java)
                    .invoke(fixed, proc)
            }
        }
        return fixed
    }

    private fun systemContext(): Context? {
        val at = Class.forName("android.app.ActivityThread")
        runCatching {
            val thread = at.getMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                val ctx = at.getMethod("getSystemContext").invoke(thread)
                if (ctx is Context) return ctx
            }
        }
        runCatching {
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread)
            if (ctx is Context) return ctx
        }
        return null
    }

    private fun fromActivityThread(): Context? {
        val at = Class.forName("android.app.ActivityThread")
        // SystemUI / SystemServer
        runCatching {
            val thread = at.getMethod("currentActivityThread").invoke(null)
            if (thread != null) {
                runCatching {
                    val ctx = at.getMethod("getSystemUiContext").invoke(thread)
                    if (ctx is Context) return ctx
                }
                val ctx = at.getMethod("getSystemContext").invoke(thread)
                if (ctx is Context) return ctx
            }
        }
        runCatching {
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread)
            if (ctx is Context) return ctx
        }
        return null
    }
}
