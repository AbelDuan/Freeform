package com.abel.os4freeformx

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * 配置快照。模块进程（system_server / systemui）通过 LSPosed remote preferences 读取模块 App 的配置，
 * 热路径只读 volatile 字段，绝不每次查 prefs。
 */
object Cfg {
    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var mod: XposedModule? = null

    @Volatile var log = Constants.DEF_ENABLE_LOG
    @Volatile var immersive = Constants.DEF_IMMERSIVE
    @Volatile var rememberBounds = Constants.DEF_REMEMBER_BOUNDS
    @Volatile var rememberFold = Constants.DEF_REMEMBER_FOLD
    @Volatile var resize = Constants.DEF_RESIZE
    @Volatile var defaultW = 0
    @Volatile var defaultH = 0
    @Volatile var gestures = Constants.DEF_GESTURES
    @Volatile var cornerFreeform = Constants.DEF_CORNER_FREEFORM
    @Volatile var fourFingerSplit = Constants.DEF_FOUR_FINGER_SPLIT

    /** 只记住模块引用，不读 prefs（system_server 启动阶段只允许这一步）。 */
    fun setModule(m: XposedModule) {
        mod = m
    }

    /** 模块内（被 hook 的进程） */
    fun attachRemote(m: XposedModule) {
        runCatching {
            mod = m
            sp = m.getRemotePreferences(Constants.PREFS_CFG)
            reload()
        }.onFailure { Logx.e("attachRemote 失败", it) }
    }

    /** 模块 App 自身进程 */
    fun attachLocal(ctx: Context) {
        sp = ctx.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
        reload()
    }

    fun reload() {
        // LSPosed 的 remote preferences 是快照对象，配置改动后必须重新取一次，否则永远是旧值（2026-09-19 实测）
        mod?.let { sp = runCatching { it.getRemotePreferences(Constants.PREFS_CFG) }.getOrNull() ?: sp }
        val p = sp ?: return
        runCatching {
            log = p.getBoolean(Constants.K_ENABLE_LOG, Constants.DEF_ENABLE_LOG)
            immersive = p.getBoolean(Constants.K_IMMERSIVE, Constants.DEF_IMMERSIVE)
            rememberBounds = p.getBoolean(Constants.K_REMEMBER_BOUNDS, Constants.DEF_REMEMBER_BOUNDS)
            rememberFold = p.getBoolean(Constants.K_REMEMBER_FOLD, Constants.DEF_REMEMBER_FOLD)
            resize = p.getBoolean(Constants.K_RESIZE, Constants.DEF_RESIZE)
            defaultW = p.getInt(Constants.K_DEFAULT_W, 0)
            defaultH = p.getInt(Constants.K_DEFAULT_H, 0)
            gestures = p.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES)
            cornerFreeform = p.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM)
            fourFingerSplit = p.getBoolean(Constants.K_FOUR_FINGER_SPLIT, Constants.DEF_FOUR_FINGER_SPLIT)
            Logx.verbose = log
        }.onFailure { Logx.e("reload 失败", it) }
    }

    private var lastReload = 0L

    /**
     * 热路径用的配置刷新：节流后**异步**经模块 App 的 [StoreProvider] 取真实值。
     *
     * 为什么不用 LSPosed 的 remote preferences：它在被 hook 的进程里是快照，App 改配置后
     * 该进程永远读到旧值（2026-09-19 实测：DE 已写入 immersive=false，systemui 仍读到 true）。
     * 也不能同步调用 provider——小窗打开路径上阻塞几百毫秒会拖慢启动，所以放线程池。
     */
    fun refreshAsync(intervalMs: Long = 1500) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReload < intervalMs) return
        lastReload = now
        // 懒挂载：第一次真正用到配置时（小窗打开，远晚于开机）才去读 LSPosed remote prefs
        if (sp == null) mod?.let { m -> runCatching { attachRemote(m) } }
        runCatching { pool.execute { readFromProvider() } }
    }

    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "os4ffx-cfg").apply { isDaemon = true }
    }

    private fun readFromProvider() {
        val ctx = AppCtx.get()
        if (ctx == null) {
            Logx.once("cfgNoCtx", "读配置失败: 取不到 Context")
            return
        }
        val call = runCatching {
            ctx.contentResolver.call(
                android.net.Uri.parse("content://${Constants.AUTHORITY}"), "getCfg", null, null
            )
        }
        if (call.isFailure) {
            Logx.once("cfgFail", "读配置失败: ${call.exceptionOrNull()}")
            return
        }
        val b = call.getOrNull()
        if (b == null) {
            Logx.once("cfgNull", "读配置失败: provider 返回 null（authority=${Constants.AUTHORITY}）")
            return
        }
        val before = "$log|$immersive|$rememberBounds"
        runCatching {
            log = b.getBoolean(Constants.K_ENABLE_LOG, Constants.DEF_ENABLE_LOG)
            immersive = b.getBoolean(Constants.K_IMMERSIVE, Constants.DEF_IMMERSIVE)
            rememberBounds = b.getBoolean(Constants.K_REMEMBER_BOUNDS, Constants.DEF_REMEMBER_BOUNDS)
            rememberFold = b.getBoolean(Constants.K_REMEMBER_FOLD, Constants.DEF_REMEMBER_FOLD)
            resize = b.getBoolean(Constants.K_RESIZE, Constants.DEF_RESIZE)
            defaultW = b.getInt(Constants.K_DEFAULT_W, 0)
            defaultH = b.getInt(Constants.K_DEFAULT_H, 0)
            gestures = b.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES)
            cornerFreeform = b.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM)
            fourFingerSplit = b.getBoolean(Constants.K_FOUR_FINGER_SPLIT, Constants.DEF_FOUR_FINGER_SPLIT)
            Logx.verbose = log
        }.onFailure { Logx.e("读配置失败", it) }
        val after = "$log|$immersive|$rememberBounds"
        if (before != after) Logx.always("配置更新: log/immersive/top/bottom/remember = $after")
    }

    /** 兼容旧调用点。 */
    fun reloadThrottled(intervalMs: Long = 1500) = refreshAsync(intervalMs)
}
