package com.abel.os4freeformx

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule

/**
 * 配置快照。模块进程（system_server / systemui）通过 LSPosed remote preferences 读取模块 App 的配置，
 * 热路径只读 volatile 字段，绝不每次查 prefs。
 */
object Cfg {
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
    /** 外屏分屏适配（三点菜单定位修正）。 */
    @Volatile var outerSplit = Constants.DEF_OUTER_SPLIT
    // 轻折加分屏（折叠屏）
    @Volatile var foldSplit = Constants.DEF_FOLD_SPLIT
    @Volatile var foldSplitSide = Constants.DEF_FOLD_SPLIT_SIDE
    /** 轻折触发灵敏度：进入判定需折下去的落差（度）。10~90 可调，默认 15°。 */
    @Volatile var foldSplitDepth = Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()
    /** 轻折触发后的行为：每折加一格（append）还是进双分屏后交换（swap）。 */
    @Volatile var foldSplitMode = Constants.DEF_FOLD_SPLIT_MODE
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
    // ── 旋屏三项（2026-11-20 自 OS4 Themer 移植）──
    /** 方向不一致时弹出旋转按钮。 */
    @Volatile var rotationHint = Constants.DEF_ROTATION_HINT
    /** 点击旋钮强制旋转（同时决定「自动旋转开着但应用不跟随」时是否弹旋钮）。 */
    @Volatile var forceRotate = Constants.DEF_FORCE_ROTATE
    /** 展开内屏时联动自动旋转（跟随 5 秒后重新锁定）。 */
    @Volatile var innerFollowRotation = Constants.DEF_INNER_FOLLOW_ROTATION
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

    /**
     * 模块内（被 hook 的进程）：只记引用，配置延迟到启动完成后读。
     *
     * ⚠ 绝不能在 SystemUI 启动路径上同步读配置：`AppCtx.get()` 的
     * `createPackageContext` 在 PMS 锁竞争时能卡好几秒，SystemUI 发布
     * ContentProvider 超过 10 秒就被 AMS 杀掉（"timeout publishing content providers"），
     * 连死 4 次后 LSPosed 判定不稳定、进安全模式（2026-11-24 实测）。
     */
    fun attachRemote(m: XposedModule) {
        runCatching { mod = m }.onFailure { Logx.e("attachRemote 失败", it) }
        reloadSoon(2000)
    }

    /** 启动完成后再刷新配置：主线程延迟 + 后台线程执行，调用方永不阻塞。 */
    fun reloadSoon(delayMs: Long = 3000) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { pool.execute { runCatching { reload() } } }
            }, delayMs)
        }
    }

    /** 模块 App 自身进程 */
    fun attachLocal(ctx: Context) {
        AppPrefs.migrateIfNeeded(ctx)
        migrateOldDepth(ctx)
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

    /**
     * 旧版配置一次性迁移：Settings.System 里还没有标记时，从模块 App 的 Provider 读一遍旧值写进去。
     * 只发生一次（升级后的第一次进程启动），之后配置只走系统设置，不再叫醒模块 App。
     */
    private fun migrateLegacyOnce(cr: android.content.ContentResolver) {
        if (Store.has(cr, Store.KEY_MIGRATED)) return
        val ctx = runCatching { AppCtx.get() }.getOrNull() ?: return
        val b = runCatching {
            ctx.contentResolver.call(
                android.net.Uri.parse("content://${Constants.AUTHORITY}"), "getCfg", null, null
            )
        }.getOrNull() ?: return
        runCatching {
            b.keySet().forEach { k ->
                when (val v = b.get(k)) {
                    is Boolean -> Store.putBool(cr, k, v)
                    is String -> Store.putString(cr, k, v)
                    is Float -> Store.putFloat(cr, k, v)
                    is Int -> android.provider.Settings.System.putInt(cr, Store.name(k), v)
                }
            }
            Store.putBool(cr, Store.KEY_MIGRATED, true)
            Logx.always("旧配置已迁移到 Settings.System（${b.size()} 项）")
        }.onFailure { Logx.e("配置迁移失败", it) }
    }

    fun reload() {
        // ★ 2026-11-24：配置改存 Settings.System —— hook 侧直接读系统设置，
        //   不再经模块 App 的 Provider（会被拉起），也不用 LSPosed remote prefs（进程内快照，读不到新值）。
        val appCtx = runCatching { AppCtx.get() }.getOrNull()
        val cr = appCtx?.contentResolver
        if (cr == null) return
        runCatching { migrateLegacyOnce(cr) }
        runCatching {
            log = Store.getBool(cr, Constants.K_ENABLE_LOG, Constants.DEF_ENABLE_LOG)
            immersive = Store.getBool(cr, Constants.K_IMMERSIVE, Constants.DEF_IMMERSIVE)
            rememberBounds = Store.getBool(cr, Constants.K_REMEMBER_BOUNDS, Constants.DEF_REMEMBER_BOUNDS)
            rememberFold = Store.getBool(cr, Constants.K_REMEMBER_FOLD, Constants.DEF_REMEMBER_FOLD)
            ratioMenu = Store.getBool(cr, Constants.K_RATIO_MENU, Constants.DEF_RATIO_MENU)
            splitUnlockDir = Store.getBool(cr, Constants.K_SPLIT_UNLOCK_DIR, Constants.DEF_SPLIT_UNLOCK_DIR)
            multiSplitFree = Store.getBool(cr, Constants.K_MULTI_SPLIT_FREE, Constants.DEF_MULTI_SPLIT_FREE)
            splitRatioKeep = Store.getBool(cr, Constants.K_SPLIT_RATIO_KEEP, Constants.DEF_SPLIT_RATIO_KEEP)
            captionSplitBoth = Store.getBool(cr, Constants.K_CAPTION_SPLIT_BOTH, Constants.DEF_CAPTION_SPLIT_BOTH)
            outerSplit = Store.getBool(cr, Constants.K_OUTER_SPLIT, Constants.DEF_OUTER_SPLIT)
            foldSplit = Store.getBool(cr, Constants.K_FOLD_SPLIT, Constants.DEF_FOLD_SPLIT)
            foldSplitSide = Store.getString(cr, Constants.K_FOLD_SPLIT_SIDE, Constants.DEF_FOLD_SPLIT_SIDE)
                ?: Constants.DEF_FOLD_SPLIT_SIDE
            // ⚠ 旧版把灵敏度存成字符串枚举（"normal" 等），本版改成 Float 角度。
            // 老值若还在，getFloat 会抛 ClassCastException —— 用 runCatching 兜住，回退默认，绝不连累其它配置。
            foldSplitDepth = runCatching {
                Store.getFloat(cr, Constants.K_FOLD_SPLIT_DEPTH, Constants.DEF_FOLD_SPLIT_DEPTH.toFloat())
            }.getOrNull() ?: Constants.DEF_FOLD_SPLIT_DEPTH.toFloat()
            foldSplitMode = Store.getString(cr, Constants.K_FOLD_SPLIT_MODE, Constants.DEF_FOLD_SPLIT_MODE)
                ?: Constants.DEF_FOLD_SPLIT_MODE
            gestureHandle = Store.getBool(cr, Constants.K_GESTURE_HANDLE, Constants.DEF_GESTURE_HANDLE)
            gestureHandleFollow = Store.getBool(cr, Constants.K_GESTURE_HANDLE_FOLLOW, Constants.DEF_GESTURE_HANDLE_FOLLOW)
            gestureHandleTouch = Store.getBool(cr, Constants.K_GESTURE_HANDLE_TOUCH, Constants.DEF_GESTURE_HANDLE_TOUCH)
            gestureHandleIdle = Store.getBool(cr, Constants.K_GESTURE_HANDLE_IDLE, Constants.DEF_GESTURE_HANDLE_IDLE)
            gestureHandleArea = Store.getFloat(cr, Constants.K_GESTURE_HANDLE_AREA, Constants.DEF_GESTURE_HANDLE_AREA)
            forceFreeform = Store.getBool(cr, Constants.K_FORCE_FREEFORM, Constants.DEF_FORCE_FREEFORM)
            multiFreeform = Store.getBool(cr, Constants.K_MULTI_FREEFORM, Constants.DEF_MULTI_FREEFORM)
            edgeFreeformFront = Store.getBool(cr, Constants.K_EDGE_FREEFORM_FRONT, Constants.DEF_EDGE_FREEFORM_FRONT)
            notificationSlide = Store.getBool(cr, Constants.K_NOTIFICATION_SLIDE, Constants.DEF_NOTIFICATION_SLIDE)
            readRotationPrefs(cr)
            parseAssign(Store.getString(cr, Constants.K_NBI_ASSIGN, Constants.DEF_NBI_ASSIGN) ?: Constants.DEF_NBI_ASSIGN)
            magicWindow = Store.getBool(cr, Constants.K_MAGIC_WINDOW, Constants.DEF_MAGIC_WINDOW)
            parseMagic(Store.getString(cr, Constants.K_MAGIC_RATIO_MAP, Constants.DEF_MAGIC_RATIO_MAP) ?: Constants.DEF_MAGIC_RATIO_MAP)
            Logx.verbose = log
            loaded = true
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

    /** 配置是否已经成功读取过（SystemUI 启动早期可能还拿不到 Context）。 */
    @Volatile
    var loaded = false
        private set

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
     * 热路径用的配置刷新：节流后**异步**经 LSPosed remote prefs 读真实值。
     *
     * 2026-11-24：配置改存 Settings.System，读取不再经模块 App 的 Provider
     * （旧实现每次刷新都会把被杀掉的模块 App 拉起一次）。
     * 仍放线程池 —— 小窗打开路径上不能同步读，会拖慢启动。
     */
    fun refreshAsync(intervalMs: Long = 1500) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReload < intervalMs) return
        lastReload = now
        // 懒挂载：第一次真正用到配置时（小窗打开，远晚于开机）才去读 Settings.System
        if (mod == null) return
        runCatching { pool.execute { runCatching { reload() } } }
    }

    /**
     * **同步**刷一次配置（读 Settings.System，只是不丢线程池）。
     *
     * 给「轻折加分屏」这类**低频但需要当次就拿到真值**的场景用：它跑在传感器线程、
     * 一次轻折才调一次，几十毫秒的 IPC 完全付得起；而 [refreshAsync] 的结果要等下一次
     * 调用才看得到，会出现"用户明明开了开关、这一折却按关处理"。
     * ⛔ 别放在热路径（小窗打开 / 每帧回调）上。
     */
    fun refreshSync() {
        runCatching { reload() }
    }

    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "os4ffx-cfg").apply { isDaemon = true }
    }


    /**
     * 读旋屏三项（数据来自 Settings.System）。
     *
     * 关掉「旋转提示」或「强制旋转」时要立刻把 WMS 里那个全局开关还回去，
     * 否则强制状态会残留到下次熄屏 —— 别的应用会莫名其妙跟着转。
     */
    private fun readRotationPrefs(cr: android.content.ContentResolver) {
        rotationHint = Store.getBool(cr, Constants.K_ROTATION_HINT, Constants.DEF_ROTATION_HINT)
        forceRotate = Store.getBool(cr, Constants.K_FORCE_ROTATE, Constants.DEF_FORCE_ROTATE)
        innerFollowRotation = Store.getBool(
            cr, Constants.K_INNER_FOLLOW_ROTATION, Constants.DEF_INNER_FOLLOW_ROTATION
        )
        if (!forceRotate || !rotationHint) {
            OrientationForcer.release(if (forceRotate) "旋转提示已关闭" else "强制旋转开关已关闭")
        }
    }

    /** 分屏四项的开关快照（只用于日志与变化判定）。 */
    private val splitExtras: String
        get() = "解锁方向=$splitUnlockDir 多分屏无级=$multiSplitFree " +
                "换向保比例=$splitRatioKeep 双向分屏=$captionSplitBoth 外屏适配=$outerSplit"

    /** 兼容旧调用点。 */
    fun reloadThrottled(intervalMs: Long = 1500) = refreshAsync(intervalMs)
}
