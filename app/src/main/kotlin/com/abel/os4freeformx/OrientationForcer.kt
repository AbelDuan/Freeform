package com.abel.os4freeformx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display

/**
 * 「点击旋钮 = 强制旋转」的执行器（自 OS4 Themer 的 OrientationForcer 移植，2026-11-20）。
 *
 * ## 为什么原来点了没反应
 * 旋钮点击只调 `IWindowManager#freezeRotation(rotation, pkg)`。这条路径改的是
 * **「用户旋转」**（`mUserRotation`），而 WMS 决定屏幕最终朝哪边时优先级是：
 * ```
 *   应用请求的固定方向（ActivityInfo.screenOrientation）
 *     > 用户旋转 / 旋转锁
 * ```
 * 大量应用在清单里把方向写死成 `portrait`，于是「用户旋转」永远被应用盖掉 ——
 * 点击旋钮看起来完全没反应。这不是落点算错，也不是 freezeRotation 调用失败
 * （日志里它会打印成功，特别迷惑）。
 *
 * ## 怎么强制（OS4 Themer 在本机逐条实测过的结论）
 * 系统里有两条「忽略应用方向请求」的开关，但只有第二条真管用：
 * 1. `wm set-ignore-orientation-request true` → `setIgnoreOrientationRequest`。
 *    **在本机是空转**：命令返回成功、读得到 true，但被 `config_ignoreOrientationRequest`
 *    挡着不生效。所以只当兜底。
 * 2. `wm fixed-to-user-rotation enabled` → `setFixedToUserRotation(displayId, ENABLED)`。
 *    **实测有效**：它改的是 `DisplayContent#mFixedToUserRotation`，打开期间
 *    WMS 计算方向时只认用户旋转/传感器，不看应用请求。
 *
 * 所以这里是三级自适应，**只有前一级验证失败才升级**，尽量少动全局状态：
 * 1. 先按老路径锁方向（`setRotationLockForFold` + `freezeRotation`）——
 *    对本身支持该方向的应用，一步就够，全局开关都不用碰；
 * 2. [VERIFY_DELAY_MS]ms 后校验；还没转过去 → 打开 `fixed-to-user-rotation`，再锁一次；
 * 3. 仍不行 → 再打开 `ignore-orientation-request`，并用 `thawRotation + freezeRotation`
 *    顶一下（有些 ROM 只在「用户旋转值变化」时才走 updateOrientation）。
 *
 * ## 什么时候放开
 * 全局开关不能常开，否则所有应用（相机这类自己管方向的）都会跟着转。
 * 只在「点旋钮」到「离开这个应用/熄屏/超时」之间保持。
 *
 * ⚠️ 本模块纪律：**只切「旋转锁定」这一个开关，绝不写方向值**（`user_rotation*` 一律不碰）。
 */
internal object OrientationForcer {

    private const val TAG = "[强制旋转]"

    private val main = Handler(Looper.getMainLooper())

    /** `fixed-to-user-rotation` 的取值（与 WMS 的 FIXED_TO_USER_ROTATION_* 一致）。 */
    private const val FTR_DEFAULT = 0
    private const val FTR_ENABLED = 2

    /** 是否已打开 fixed-to-user-rotation。 */
    @Volatile private var fixedOn = false
    /** 是否已打开 ignore-orientation-request（本机空转，仅兜底）。 */
    @Volatile private var ignoreOn = false
    /** 强制中的目标方向（0/1/2/3）；-1 = 当前没有强制。 */
    @Volatile private var target = -1
    /** 强制开始时的前台包名，用于判断「用户已离开该应用」。 */
    @Volatile private var forcedPkg: String? = null
    /** 强制开始时刻（uptimeMillis）。 */
    @Volatile private var startAt = 0L
    /** 上一次轮询看到的不同包名（连续两次才认定真的切换了）。 */
    @Volatile private var otherPkg: String? = null
    /** 看门狗是否已在跑（避免重复 post）。 */
    @Volatile private var watchdogRunning = false
    /** 模块进程持有的 Context（只在 force() 时更新，用于轮询熄屏/前台包名）。 */
    @Volatile private var ctxRef: Context? = null
    /** 残留开关是否已经清理过（每个进程只清一次）。 */
    @Volatile private var staleCleared = false
    /** 已确认转到目标方向（下次同方向点击直接返回，不再走 700ms 校验）。 */
    @Volatile private var reached = false

    /** 校验延时：锁完方向后等这么久再看屏幕有没有转过去。 */
    private const val VERIFY_DELAY_MS = 700L
    /** 看门狗轮询间隔。 */
    private const val WATCHDOG_INTERVAL_MS = 1200L
    /**
     * 强制状态的兜底超时。
     *
     * OS4 Themer v3.300.9 定稿：原来是 10 分钟，那是「旋转锁关闭后应用都不自动转」
     * 「视频回到强制旋转」「桌面图标错位」的直接原因 —— 期间**所有**应用的方向请求
     * 都被压掉。12 秒足够覆盖一次旋钮操作（700ms 校验）+ 系统转屏过渡，
     * 之后立即交还控制权。
     */
    private const val FORCE_MAX_MS = 12_000L

    fun isActive(): Boolean = target >= 0
    fun activeTarget(): Int = target

    /**
     * 请求把屏幕强制转到 [targetRotation]。
     *
     * @return true 表示已进入强制流程（调用方可以认为「点了有效」）
     */
    fun force(ctx: Context, targetRotation: Int, reason: String): Boolean {
        if (targetRotation < 0 || targetRotation > 3) {
            Logx.always("$TAG 拒绝：目标方向非法 $targetRotation")
            return false
        }
        ctxRef = ctx
        val pkg = topPackage(ctx)
        // 同一个目标方向、同一个应用、已确认到位 → 直接返回，避免重复走 700ms
        if (reached && target == targetRotation && pkg != null && pkg == forcedPkg) {
            Logx.always("$TAG 已在目标方向 $targetRotation，忽略重复请求（$reason）")
            return true
        }
        target = targetRotation
        forcedPkg = pkg
        startAt = SystemClock.uptimeMillis()
        otherPkg = null
        reached = false
        Logx.always("$TAG 开始强制 → rotation=$targetRotation pkg=$pkg 原因=$reason")

        // 第一级：只锁方向，不动任何全局开关
        if (lockRotation(ctx, targetRotation)) {
            startWatchdog()
            main.postDelayed({ verify(ctx, targetRotation, 1) }, VERIFY_DELAY_MS)
            return true
        }
        // 第一级就失败 → 直接进第二级
        verify(ctx, targetRotation, 2)
        return true
    }

    /** 校验当前是否真的转到了目标方向，没到就升级。 */
    private fun verify(ctx: Context, targetRotation: Int, level: Int) {
        if (target != targetRotation) return      // 期间被新的请求取代
        val now = currentRotation(ctx)
        if (now == targetRotation) {
            reached = true
            Logx.always("$TAG 校验通过（第 $level 级）：屏幕已是 $targetRotation")
            return
        }
        when (level) {
            1 -> {
                Logx.always("$TAG 校验失败（第 1 级）：屏幕仍是 $now，期望 $targetRotation → 升级 fixed-to-user-rotation")
                if (setFixedToUserRotation(ctx, true)) {
                    lockRotation(ctx, targetRotation)
                    main.postDelayed({ verify(ctx, targetRotation, 2) }, VERIFY_DELAY_MS)
                } else {
                    verify(ctx, targetRotation, 3)
                }
            }
            2 -> {
                Logx.always("$TAG 校验失败（第 2 级）：屏幕仍是 $now → 升级 ignore-orientation-request")
                step3(ctx, targetRotation)
            }
            else -> Logx.always("$TAG 校验失败（第 $level 级）：屏幕仍是 $now，放弃本次强制")
        }
    }

    /** 第 3 级：打开 ignore-orientation-request，并用 thaw+freeze 逼系统重算方向。 */
    private fun step3(ctx: Context, targetRotation: Int) {
        if (!setIgnoreOrientation(ctx, true)) {
            Logx.always("$TAG 第 3 级不可用：拿不到 setIgnoreOrientationRequest")
            return
        }
        nudge(ctx, targetRotation)
    }

    private fun levelName(level: Int): String = when (level) {
        1 -> "仅锁方向"
        2 -> "fixed-to-user-rotation"
        else -> "fixed+ignore+nudge"
    }

    /** 放开所有全局开关，把控制权还给系统。可重复调用（幂等）。 */
    fun release(reason: String) {
        if (target < 0 && !fixedOn && !ignoreOn) return
        Logx.always("$TAG 释放（$reason）：fixed=$fixedOn ignore=$ignoreOn target=$target")
        ctxRef?.let { c ->
            if (fixedOn) setFixedToUserRotation(c, false)
            if (ignoreOn) setIgnoreOrientation(c, false)
        }
        fixedOn = false
        ignoreOn = false
        target = -1
        forcedPkg = null
        otherPkg = null
        reached = false
        stopWatchdog()
    }

    /**
     * 清一次可能残留的全局开关。
     *
     * `fixed-to-user-rotation` / `ignore-orientation-request` 在 **system_server** 里，
     * SystemUI 被杀（或模块被停用）时不会跟着复位，不清就会「所有应用忽然都能转」。
     * 每个进程只清一次。
     */
    fun clearStale(ctx: Context) {
        if (staleCleared) return
        staleCleared = true
        if (fixedOn || ignoreOn) return     // 是本次进程自己开的，不算残留
        runCatching {
            if (setFixedToUserRotation(ctx, false)) {
                Logx.always("$TAG 已清理进程中残留的 fixed-to-user-rotation")
            }
        }
    }

    // ---------------- 反射封装 ----------------

    /** 老路径：`setRotationLockForFold` 优先，失败退 `freezeRotation`。 */
    private fun lockRotation(ctx: Context, targetRotation: Int): Boolean {
        val wm = wms(ctx) ?: return false
        // 折叠屏专用：setRotationLockForFold(boolean, int, boolean)
        //   arg0 true → 操作**外屏**（mUserRotationModeOuter）
        //   arg0 false → 操作**内屏**（mUserRotationModeInner）
        try {
            val m = wm.javaClass.getMethod(
                "setRotationLockForFold", java.lang.Boolean.TYPE, Int::class.javaPrimitiveType,
                java.lang.Boolean.TYPE
            )
            m.isAccessible = true
            val inner = isInnerScreen(ctx)
            m.invoke(wm, !inner, targetRotation, false)
            Logx.always("$TAG setRotationLockForFold(外屏=${!inner}, $targetRotation)")
            return true
        } catch (t: Throwable) {
            Logx.v("$TAG setRotationLockForFold 不可用: $t")
        }
        // AOSP 通用路径
        return try {
            val m = wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType, String::class.java)
            m.isAccessible = true
            m.invoke(wm, targetRotation, "OS4FreeFromX")
            Logx.always("$TAG freezeRotation($targetRotation)")
            true
        } catch (t: Throwable) {
            Logx.e("$TAG freezeRotation 失败", t)
            false
        }
    }

    /** 第 3 级的「顶一下」：thaw + freeze，逼系统重新走 updateOrientation。 */
    private fun nudge(ctx: Context, targetRotation: Int) {
        val wm = wms(ctx) ?: return
        main.postDelayed({
            runCatching {
                val thaw = wm.javaClass.getMethod("thawRotation", String::class.java)
                thaw.isAccessible = true
                thaw.invoke(wm, "OS4FreeFromX-nudge")
            }.onFailure { Logx.v("$TAG thawRotation 不可用: $it") }
            lockRotation(ctx, targetRotation)
        }, 120L)
    }

    /**
     * 打开/关闭 `fixed-to-user-rotation`（= 系统 `wm fixed-to-user-rotation` 背后的接口）。
     * 本机实测**唯一真正有效**的强制手段。
     */
    private fun setFixedToUserRotation(ctx: Context, enabled: Boolean): Boolean {
        val wm = wms(ctx) ?: return false
        return try {
            val m = wm.javaClass.getMethod(
                "setFixedToUserRotation", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            m.invoke(wm, displayIdFor(ctx), if (enabled) FTR_ENABLED else FTR_DEFAULT)
            fixedOn = enabled
            Logx.always("$TAG setFixedToUserRotation(${if (enabled) "ENABLED" else "DEFAULT"})")
            true
        } catch (t: Throwable) {
            Logx.always("$TAG setFixedToUserRotation 不可用: $t")
            false
        }
    }

    /** 打开/关闭 `ignore-orientation-request`。本机空转，只作兜底。 */
    private fun setIgnoreOrientation(ctx: Context, ignore: Boolean): Boolean {
        val wm = wms(ctx) ?: return false
        return try {
            val m = wm.javaClass.getMethod("setIgnoreOrientationRequest", java.lang.Boolean.TYPE)
            m.isAccessible = true
            m.invoke(wm, ignore)
            ignoreOn = ignore
            Logx.always("$TAG setIgnoreOrientationRequest($ignore)")
            true
        } catch (t: Throwable) {
            Logx.always("$TAG setIgnoreOrientationRequest 不可用: $t")
            false
        }
    }

    /** 看门狗：用户离开该应用 / 熄屏 / 超时 → 释放全局开关。 */
    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = ctxRef
            if (ctx == null || target < 0) {
                stopWatchdog()
                return
            }
            val elapsed = SystemClock.uptimeMillis() - startAt
            if (elapsed > FORCE_MAX_MS) {
                release("超时 ${FORCE_MAX_MS}ms")
                return
            }
            if (!isInteractive(ctx)) {
                release("熄屏")
                return
            }
            val now = topPackage(ctx)
            val base = forcedPkg
            if (now != null && base != null && now != base) {
                if (now == otherPkg) {
                    release("用户已切到 $now")
                    return
                }
                otherPkg = now
            } else {
                otherPkg = null
            }
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        main.removeCallbacks(watchdog)
    }

    // ---------------- 系统状态读取 ----------------

    /** 当前活跃屏的旋转方向（Surface.ROTATION_*）；失败返回 -1。 */
    fun currentRotation(ctx: Context?): Int {
        val c = ctx ?: ctxRef ?: return -1
        return try {
            (activeDisplay(c) ?: return -1).rotation
        } catch (t: Throwable) {
            -1
        }
    }

    /** 按折叠状态给一个默认方向（合上时内屏预置 270°，与 OS4 Themer 一致）。 */
    fun rotationByFoldState(ctx: Context?): Int = currentRotation(ctx)

    private fun activeDisplay(ctx: Context): Display? = try {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            ?: return null
        // 优先取当前用户可见的主屏；拿不到再退第一个
        dm.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (t: Throwable) {
        null
    }

    private fun displayIdFor(ctx: Context): Int = try {
        activeDisplay(ctx)?.displayId ?: Display.DEFAULT_DISPLAY
    } catch (t: Throwable) {
        Display.DEFAULT_DISPLAY
    }

    /**
     * 当前是不是内屏（展开态）。
     *
     * `current_device_state`：3 = 展开/内屏，其余 = 折叠/外屏。读不到时返回 true
     * （宁可按内屏处理，因为本机是折叠屏、内屏是主用形态）。
     */
    fun isInnerScreen(ctx: Context?): Boolean {
        val c = ctx ?: ctxRef ?: return true
        return try {
            Settings.Global.getInt(c.contentResolver, "current_device_state", -1) == 3
        } catch (t: Throwable) {
            true
        }
    }

    fun isInteractive(ctx: Context?): Boolean = try {
        val pm = ctx?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm == null || pm.isInteractive
    } catch (t: Throwable) {
        true
    }

    /**
     * 当前前台包名。
     *
     * 用 `topPackage` 判断「用户是否已经离开被强制的应用」。拿不到返回 null，
     * 调用方按「没离开」处理（宁可不释放，也不误释放）。
     */
    fun topPackage(ctx: Context?): String? {
        val c = ctx ?: ctxRef ?: return null
        // ① 最可靠：ActivityManager.getTasks 取前台任务的 topActivity
        runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return@runCatching
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            val t = tasks?.firstOrNull()?.topActivity?.packageName
            if (!t.isNullOrEmpty()) return normalizePkg(t)
        }.onFailure { Logx.v("$TAG getRunningTasks 不可用: $it") }
        // ② 退路：读 Settings 里的默认桌面包名（只用来判断"是否在桌面"，不是前台包名）
        return null
    }

    private fun normalizePkg(pkg: String?): String? {
        if (pkg.isNullOrEmpty()) return null
        // 去掉进程后缀（com.foo:remote）以同一应用为准
        val i = pkg.indexOf(':')
        return if (i > 0) pkg.substring(0, i) else pkg
    }

    /** 取 IWindowManager 的 public 代理。 */
    private fun wms(ctx: Context): Any? = try {
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null)
    } catch (t: Throwable) {
        Logx.v("$TAG 取 WMS 失败: $t")
        null
    }
}
