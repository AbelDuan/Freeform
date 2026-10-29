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
    @Volatile var ratioMenu = Constants.DEF_RATIO_MENU
    // ── 分屏四项（移植自 HyperRefine）──
    @Volatile var splitUnlockDir = Constants.DEF_SPLIT_UNLOCK_DIR
    @Volatile var multiSplitFree = Constants.DEF_MULTI_SPLIT_FREE
    @Volatile var splitRatioKeep = Constants.DEF_SPLIT_RATIO_KEEP
    @Volatile var captionSplitBoth = Constants.DEF_CAPTION_SPLIT_BOTH
    /** 外屏（合上手机后的那块屏）也支持分屏。实现见 CoverSplit.kt。 */
    @Volatile var coverSplit = Constants.DEF_COVER_SPLIT
    // 轻折加分屏（折叠屏）
    @Volatile var foldSplit = Constants.DEF_FOLD_SPLIT
    @Volatile var foldSplitSide = Constants.DEF_FOLD_SPLIT_SIDE
    /** 轻折触发灵敏度：进入判定需折下去的落差（度）。10~90 可调，默认 15°。 */
    @Volatile var foldSplitDepth = Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()
    @Volatile var gestures = Constants.DEF_GESTURES
    @Volatile var cornerFreeform = Constants.DEF_CORNER_FREEFORM
    @Volatile var gestureHandle = Constants.DEF_GESTURE_HANDLE
    @Volatile var gestureHandleFollow = Constants.DEF_GESTURE_HANDLE_FOLLOW
    @Volatile var gestureHandleTouch = Constants.DEF_GESTURE_HANDLE_TOUCH
    @Volatile var gestureHandleIdle = Constants.DEF_GESTURE_HANDLE_IDLE
    @Volatile var gestureHandleArea = Constants.DEF_GESTURE_HANDLE_AREA
    @Volatile var assignRaw = Constants.DEF_NBI_ASSIGN
    /** 魔法窗口总开关 + 逐应用比例表（包名 → 比例 label） */
    @Volatile var magicWindow = Constants.DEF_MAGIC_WINDOW
    @Volatile var magicRatioRaw = Constants.DEF_MAGIC_RATIO_MAP
    @Volatile private var magicMap: Map<String, String> = emptyMap()

    /** 该包是否由模块启用魔法窗口（总开关关掉时一律 false）。 */
    fun magicRatioOf(pkg: String?): String? =
        if (!magicWindow || pkg == null) null else magicMap[pkg]

    fun magicSnapshot(): Map<String, String> = magicMap
    // 小窗增强（移植自 HyperCeiler）
    @Volatile var forceFreeform = Constants.DEF_FORCE_FREEFORM
    @Volatile var multiFreeform = Constants.DEF_MULTI_FREEFORM
    @Volatile var edgeFreeformFront = Constants.DEF_EDGE_FREEFORM_FRONT
    /** 通知栏下滑开小窗（解除 canNotificationSlide 的应用限制） */
    @Volatile var notificationSlide = Constants.DEF_NOTIFICATION_SLIDE
    @Volatile private var assign: Map<String, Int> = emptyMap()

    /** 该应用的归属（0=不处理 1=隐藏 2=取色）。热路径只读 volatile。 */
    fun assignOf(pkg: String?): Int = if (pkg == null) Constants.NBI_NONE else (assign[pkg] ?: Constants.NBI_NONE)

    /** 该应用要用的 NBI 规则；不处理则 null。 */
    fun ruleFor(pkg: String?): String? = when (assignOf(pkg)) {
        Constants.NBI_HIDE -> Nbi.RULE_HIDE
        Constants.NBI_SAMPLE -> Nbi.RULE_SAMPLE
        else -> null
    }

    /** 归属映射的只读快照（UI 用）。 */
    fun assignSnapshot(): Map<String, Int> = assign

    /** 写名单用："包名 → 规则体"。 */
    fun desiredRules(): Map<String, String> = assign.entries.associate { (p, m) ->
        p to if (m == Constants.NBI_HIDE) Nbi.RULE_HIDE else Nbi.RULE_SAMPLE
    }

    fun assignCount(mode: Int): Int = assign.count { it.value == mode }

    /** 设置界面用：同步写入并重新解析（不等异步 reload）。 */
    fun setAssign(v: String) = parseAssign(v)

    private fun parseAssign(s: String) {
        assignRaw = s
        assign = s.split('\n', ',', ' ', '\t').mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val parts = t.split('=')
            if (parts.size != 2) return@mapNotNull null
            val mode = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            if (mode != Constants.NBI_HIDE && mode != Constants.NBI_SAMPLE) return@mapNotNull null
            parts[0].trim() to mode
        }.toMap()
    }

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
        migrateOldDepth(ctx)
        sp = ctx.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
        reload()
    }

    /**
     * 把 v0.7.7 及以前的三档灵敏度枚举（"sensitive"/"normal"/"sluggish"）迁移成数字角度，
     * 写回成 Float。这样老用户升级后不会因类型不匹配掉回默认，也能继续用滑动条微调。
     * 已经不是字符串（已是 Float 或键不存在）则不动。
     */
    private fun migrateOldDepth(ctx: Context) {
        val p = ctx.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
        val raw = runCatching { p.all[Constants.K_FOLD_SPLIT_DEPTH] }.getOrNull()
        if (raw !is String) return
        val deg = when (raw) {
            Constants.FOLD_SPLIT_DEPTH_SENSITIVE -> 10f
            Constants.FOLD_SPLIT_DEPTH_SLUGGISH -> 35f
            else -> Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()   // normal 或未知 → 默认 15°
        }
        AppPrefs.putFloat(ctx, Constants.K_FOLD_SPLIT_DEPTH, deg)
        Logx.once("depthMigrate", "轻折灵敏度旧值=$raw 已迁移为 ${deg.toInt()}°")
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
            ratioMenu = p.getBoolean(Constants.K_RATIO_MENU, Constants.DEF_RATIO_MENU)
            splitUnlockDir = p.getBoolean(Constants.K_SPLIT_UNLOCK_DIR, Constants.DEF_SPLIT_UNLOCK_DIR)
            multiSplitFree = p.getBoolean(Constants.K_MULTI_SPLIT_FREE, Constants.DEF_MULTI_SPLIT_FREE)
            splitRatioKeep = p.getBoolean(Constants.K_SPLIT_RATIO_KEEP, Constants.DEF_SPLIT_RATIO_KEEP)
            captionSplitBoth = p.getBoolean(Constants.K_CAPTION_SPLIT_BOTH, Constants.DEF_CAPTION_SPLIT_BOTH)
            coverSplit = p.getBoolean(Constants.K_COVER_SPLIT, Constants.DEF_COVER_SPLIT)
            foldSplit = p.getBoolean(Constants.K_FOLD_SPLIT, Constants.DEF_FOLD_SPLIT)
            foldSplitSide = p.getString(Constants.K_FOLD_SPLIT_SIDE, Constants.DEF_FOLD_SPLIT_SIDE)
                ?: Constants.DEF_FOLD_SPLIT_SIDE
            // ⚠ 旧版把灵敏度存成字符串枚举（"normal" 等），本版改成 Float 角度。
            // 老值若还在，getFloat 会抛 ClassCastException —— 用 runCatching 兜住，回退默认，绝不连累其它配置。
            foldSplitDepth = runCatching {
                p.getFloat(Constants.K_FOLD_SPLIT_DEPTH, Constants.DEF_FOLD_SPLIT_DEPTH.toFloat())
            }.getOrNull() ?: Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()
            gestures = p.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES)
            cornerFreeform = p.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM)
            gestureHandle = p.getBoolean(Constants.K_GESTURE_HANDLE, Constants.DEF_GESTURE_HANDLE)
            gestureHandleFollow = p.getBoolean(Constants.K_GESTURE_HANDLE_FOLLOW, Constants.DEF_GESTURE_HANDLE_FOLLOW)
            gestureHandleTouch = p.getBoolean(Constants.K_GESTURE_HANDLE_TOUCH, Constants.DEF_GESTURE_HANDLE_TOUCH)
            gestureHandleIdle = p.getBoolean(Constants.K_GESTURE_HANDLE_IDLE, Constants.DEF_GESTURE_HANDLE_IDLE)
            gestureHandleArea = p.getFloat(Constants.K_GESTURE_HANDLE_AREA, Constants.DEF_GESTURE_HANDLE_AREA)
            forceFreeform = p.getBoolean(Constants.K_FORCE_FREEFORM, Constants.DEF_FORCE_FREEFORM)
            multiFreeform = p.getBoolean(Constants.K_MULTI_FREEFORM, Constants.DEF_MULTI_FREEFORM)
            edgeFreeformFront = p.getBoolean(Constants.K_EDGE_FREEFORM_FRONT, Constants.DEF_EDGE_FREEFORM_FRONT)
            notificationSlide = p.getBoolean(Constants.K_NOTIFICATION_SLIDE, Constants.DEF_NOTIFICATION_SLIDE)
            parseAssign(p.getString(Constants.K_NBI_ASSIGN, Constants.DEF_NBI_ASSIGN) ?: Constants.DEF_NBI_ASSIGN)
            magicWindow = p.getBoolean(Constants.K_MAGIC_WINDOW, Constants.DEF_MAGIC_WINDOW)
            parseMagic(p.getString(Constants.K_MAGIC_RATIO_MAP, Constants.DEF_MAGIC_RATIO_MAP) ?: Constants.DEF_MAGIC_RATIO_MAP)
            Logx.verbose = log
        }.onFailure { Logx.e("reload 失败", it) }
    }

    /** 解析 "包名=比例" 逐行配置，并推给 [MagicWindow] 的运行期名单。 */
    private fun parseMagic(raw: String) {
        magicRatioRaw = raw
        val m = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val i = t.indexOf('=')
            if (i <= 0) return@forEach
            val pkg = t.substring(0, i).trim()
            val r = t.substring(i + 1).trim()
            if (pkg.isNotEmpty() && MagicWindow.RATIOS.containsKey(r)) m[pkg] = r
        }
        magicMap = m
        MagicWindow.refreshFromCfg(if (magicWindow) m else emptyMap())
    }

    private var lastReload = 0L

    /**
     * **输入热路径专用**：异步取一次配置，绝不阻塞调用线程。
     *
     * 为什么必须异步（2026-10-01 真机复现两次，SystemUI 被杀的元凶）：
     * `Gestures.onUp()` 跑在 MIUI 全局手势监视器 `[Gesture Monitor] MultiTaskSwitch` 的
     * **输入派发线程**上，而 `reload()` 每次都同步走 LSPosed 的 `getRemotePreferences`（binder）。
     * 这次调用一超时，输入派发就报
     * `Input dispatching timed out ... MotionEvent(action=UP)` → ANR → **SystemUI 进程被杀**。
     * 后果正是用户看到的现象：抬手那一刻窗口连同"刚拖好的位置"一起没了，记录链路（relayout）
     * 来不及落盘 ⇒ 表现为「位置记不住 / 记忆不生效」。
     *
     * 代价：改完开关后**当次**手势仍按上一份快照判定，下一次手势生效 —— 不能拿 SystemUI 的命换。
     */
    fun reloadAsync() {
        runCatching { pool.execute { runCatching { reload() } } }
    }

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

    /**
     * **同步**刷一次配置（走同一条 provider 链路，只是不丢线程池）。
     *
     * 给「轻折加分屏」这类**低频但需要当次就拿到真值**的场景用：它跑在传感器线程、
     * 一次轻折才调一次，几十毫秒的 IPC 完全付得起；而 [refreshAsync] 的结果要等下一次
     * 调用才看得到，会出现"用户明明开了开关、这一折却按关处理"。
     * ⛔ 别放在热路径（小窗打开 / 每帧回调）上。
     */
    fun refreshSync() {
        runCatching { readFromProvider() }
    }

    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "os4ffx-cfg").apply { isDaemon = true }
    }

    private fun readFromProvider() {
        // Context 在 SystemUI 启动早期可能还不完整（真机见过 getDefaultClassLoader NPE），
        // 这里整体包起来，失败只记一次日志，绝不向外抛。
        val ctx = runCatching { AppCtx.get() }.getOrNull()
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
        val before = "$log|$immersive|$rememberBounds|$ratioMenu|$splitExtras|$assignRaw|$foldSplit|$foldSplitSide|$foldSplitDepth"
        runCatching {
            log = b.getBoolean(Constants.K_ENABLE_LOG, Constants.DEF_ENABLE_LOG)
            immersive = b.getBoolean(Constants.K_IMMERSIVE, Constants.DEF_IMMERSIVE)
            rememberBounds = b.getBoolean(Constants.K_REMEMBER_BOUNDS, Constants.DEF_REMEMBER_BOUNDS)
            rememberFold = b.getBoolean(Constants.K_REMEMBER_FOLD, Constants.DEF_REMEMBER_FOLD)
            ratioMenu = b.getBoolean(Constants.K_RATIO_MENU, Constants.DEF_RATIO_MENU)
            splitUnlockDir = b.getBoolean(Constants.K_SPLIT_UNLOCK_DIR, Constants.DEF_SPLIT_UNLOCK_DIR)
            multiSplitFree = b.getBoolean(Constants.K_MULTI_SPLIT_FREE, Constants.DEF_MULTI_SPLIT_FREE)
            splitRatioKeep = b.getBoolean(Constants.K_SPLIT_RATIO_KEEP, Constants.DEF_SPLIT_RATIO_KEEP)
            captionSplitBoth = b.getBoolean(Constants.K_CAPTION_SPLIT_BOTH, Constants.DEF_CAPTION_SPLIT_BOTH)
            coverSplit = b.getBoolean(Constants.K_COVER_SPLIT, Constants.DEF_COVER_SPLIT)
            foldSplit = b.getBoolean(Constants.K_FOLD_SPLIT, Constants.DEF_FOLD_SPLIT)
            foldSplitSide = b.getString(Constants.K_FOLD_SPLIT_SIDE, Constants.DEF_FOLD_SPLIT_SIDE)
                ?: Constants.DEF_FOLD_SPLIT_SIDE
            foldSplitDepth = runCatching {
                b.getFloat(Constants.K_FOLD_SPLIT_DEPTH, Constants.DEF_FOLD_SPLIT_DEPTH.toFloat())
            }.getOrNull() ?: Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()
            gestures = b.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES)
            cornerFreeform = b.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM)
            gestureHandle = b.getBoolean(Constants.K_GESTURE_HANDLE, Constants.DEF_GESTURE_HANDLE)
            gestureHandleFollow = b.getBoolean(Constants.K_GESTURE_HANDLE_FOLLOW, Constants.DEF_GESTURE_HANDLE_FOLLOW)
            gestureHandleTouch = b.getBoolean(Constants.K_GESTURE_HANDLE_TOUCH, Constants.DEF_GESTURE_HANDLE_TOUCH)
            gestureHandleIdle = b.getBoolean(Constants.K_GESTURE_HANDLE_IDLE, Constants.DEF_GESTURE_HANDLE_IDLE)
            gestureHandleArea = b.getFloat(Constants.K_GESTURE_HANDLE_AREA, Constants.DEF_GESTURE_HANDLE_AREA)
            forceFreeform = b.getBoolean(Constants.K_FORCE_FREEFORM, Constants.DEF_FORCE_FREEFORM)
            multiFreeform = b.getBoolean(Constants.K_MULTI_FREEFORM, Constants.DEF_MULTI_FREEFORM)
            edgeFreeformFront = b.getBoolean(Constants.K_EDGE_FREEFORM_FRONT, Constants.DEF_EDGE_FREEFORM_FRONT)
            parseAssign(b.getString(Constants.K_NBI_ASSIGN, Constants.DEF_NBI_ASSIGN) ?: Constants.DEF_NBI_ASSIGN)
            Logx.verbose = log
        }.onFailure { Logx.e("读配置失败", it) }
        val after = "$log|$immersive|$rememberBounds|$ratioMenu|$splitExtras|$assignRaw|$foldSplit|$foldSplitSide|$foldSplitDepth"
        // 把悬浮门禁（开关/模式/名单）一并打出来：实测排查中，"名单为空 ⇒ 门禁恒 false ⇒ hook 空转"
        // 曾让人误判成"名单和开关都无效"。这条日志让配置是否送达被 hook 进程一眼可见。
        if (before != after) {
            Logx.always(
                "配置更新: log=$log immersive=$immersive rememberBounds=$rememberBounds " +
                        "比例菜单=$ratioMenu 分屏[$splitExtras] 导航栏归属=[$assignRaw]"
            )
        }
    }

    /** 分屏四项的开关快照（只用于日志与变化判定）。 */
    private val splitExtras: String
        get() = "解锁方向=$splitUnlockDir 多分屏无级=$multiSplitFree " +
                "换向保比例=$splitRatioKeep 双向分屏=$captionSplitBoth 外屏分屏=$coverSplit"

    /** 兼容旧调用点。 */
    fun reloadThrottled(intervalMs: Long = 1500) = refreshAsync(intervalMs)
}
