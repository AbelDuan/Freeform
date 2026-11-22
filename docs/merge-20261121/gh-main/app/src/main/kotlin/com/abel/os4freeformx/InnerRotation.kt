package com.abel.os4freeformx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * 展开内屏时联动自动旋屏（自 OS4 Themer 的内屏联动移植并精简，2026-11-20）。
 *
 * ## 用户要的行为（OS4 Themer 2026-10-20 / 10-21 逐条确认）
 * > 「合上内屏切换到外屏时，把内屏方向调到 270°，展开时第一画面就是 270°，
 * >   然后解除方向锁定，按传感器方向去看。」
 *
 * 也就是：
 * 1. **合上**（内屏 → 外屏）：把内屏预置成 270°（摄像头右上角的横屏），
 *    这样下次展开的第一帧就是横的，不会出现「先竖后横」的跳动；
 * 2. **展开**（外屏 → 内屏）：若内屏处于**锁定**状态，临时解除锁定
 *    （打开 `accelerometer_rotation_inner`），跟随传感器 [Constants.INNER_FOLLOW_DURATION_MS]，
 *    到点把当时的方向重新锁定；
 * 3. 若内屏本来就是**自动旋转**（用户自己开的），**一律不碰** —— 用户意志优先。
 *
 * ## 铁律（本机纪律，不可违反）
 * - 只切「旋转锁定」这一个开关；**锁定后锁在哪个方向完全由系统当前值决定**；
 * - **不读、不算、不写任何方向值**（`user_rotation*` 一律不碰）；
 * - 唯一例外：合上时预置 270° 需要用 `setRotationLockForFold` 指定方向，
 *   这只作用于**当前不可见的内屏**，且展开后立刻解除锁定交还给系统。
 *
 * ## 为什么需要「占用标记」
 * 模块临时打开的自动旋转，如果进程中途被杀，开关会永远停在「开」——
 * 表现为「用久了锁再也不锁回去」。所以每次打开都落一个带到期时刻的标记，
 * 下次启动先自愈（比对后收回），同时也能区分「这个 1 是模块写的」还是「用户开的」。
 */
internal object InnerRotation {

    private const val TAG = "[内屏联动]"

    /** 等 Context 的最多尝试次数（每 1.5s 一次）——覆盖 SystemUI 冷启动。 */
    private const val INSTALL_MAX_TRIES = 15
    private const val INSTALL_RETRY_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    private const val PREFS = "os4freeformx_inner_state"
    private const val KEY_USER_INTENT = "user_intent"
    private const val KEY_OCCUPY_UNTIL = "module_unlock_until"

    /** 用户意志：-2 尚未加载；-1 没表过态；0 要锁定；1 要自由旋转。 */
    @Volatile private var userIntent = -2
    /** 模块占用旋转锁的到期时刻（wall clock ms）。>0 表示「现在这个 1 是模块写的」。 */
    @Volatile private var occupyUntil = 0L
    /** 自动旋转开关**真的变了**（用户亲手改过）。 */
    @Volatile private var userOpenedByHand = false
    @Volatile private var selfWriteAt = 0L
    @Volatile private var lastSeenCr = -1

    /** 折叠状态（由边沿事件记录，不做实时查询）。 */
    @Volatile private var innerActive = false
    @Volatile private var innerStateAt = 0L
    /** 是否正在跟随窗口内（期间不重复触发，否则会把计时器重置）。 */
    @Volatile private var following = false
    /** 上次跟随窗口结束时刻，用于同一次亮屏的去重冷却。 */
    @Volatile private var lastWindowEndAt = 0L
    private const val INNER_WINDOW_COOLDOWN_MS = 4000L

    @Volatile private var ctxRef: Context? = null
    @Volatile private var observerInstalled = false
    @Volatile private var relockRunnable: Runnable? = null

    /** 合上时预置的内屏方向：270° = 摄像头在右上角的横屏。 */
    private const val UNFOLD_PIN_ROTATION = 3

    // ======================================================================

    /**
     * 挂到 SystemUI 进程。
     *
     * 与 RotationHint 同样要**等 Context 就绪**：SystemUI 冷启动早期 AppCtx 内部
     * 的 createPackageContext 会抛 NameNotFoundException（实测 2026-11-20），
     * 这时应该延后重试，而不是让它抛出去。
     */
    fun install() {
        scheduleInstall(attempt = 0)
    }

    private fun scheduleInstall(attempt: Int) {
        main.postDelayed({
            runCatching {
                val ctx = AppCtx.get()
                if (ctx == null) {
                    if (attempt + 1 < INSTALL_MAX_TRIES) scheduleInstall(attempt + 1)
                    else Logx.always("$TAG 安装放弃：$INSTALL_MAX_TRIES 次都没拿到 Context")
                    return@postDelayed
                }
                ctxRef = ctx
                loadState(ctx)
                ensureObserver(ctx)
                // 自愈：上次跟随被打断留下的「开」→ 收回
                healIfStale("安装自检")
                Logx.always("$TAG 已安装（开关=${Cfg.innerFollowRotation}，第 ${attempt + 1} 次尝试）")
            }.onFailure {
                Logx.e("$TAG 安装失败（第 ${attempt + 1} 次）", it)
                if (attempt + 1 < INSTALL_MAX_TRIES) scheduleInstall(attempt + 1)
            }
        }, if (attempt == 0) 0L else INSTALL_RETRY_MS)
    }

    /** 当前是否处于「内屏 + 可自由旋转」的窗口期（RotationHint 用它抑制旋钮）。 */
    fun isUnfoldWindowActive(): Boolean =
        following && Cfg.innerFollowRotation

    /** 折叠状态变化（来自 DisplayListener / 亮屏广播）。 */
    fun onFoldStateMaybeChanged(reason: String) {
        if (!Cfg.innerFollowRotation) {
            Logx.once("inner-off", "$TAG 跳过：联动开关未开启")
            return
        }
        // 熄屏期间一律不进入「展开」判定：屏幕不面向用户，没有任何跟随的意义，
        // 而且 AOD 亮起时会被误当成展开、把竖屏改成横屏（OS4 Themer 实测）。
        val ctx = ctxRef ?: AppCtx.get() ?: return
        ctxRef = ctx
        if (!isInteractive(ctx)) return

        runCatching {
            ensureObserver(ctx)
            val isInner = isInnerNow(ctx)
            if (!isInner) {
                if (innerActive) Logx.always("$TAG 已合上（$reason）")
                innerActive = false
                innerStateAt = SystemClock.uptimeMillis()
                lastWindowEndAt = 0L
                // 跟随中合上 → 取消计时并立即回锁
                if (following) {
                    cancelRelock()
                    relock(ctx.contentResolver, "合上时仍在计时，已取消并恢复锁定")
                } else if (occupyUntil > 0L) {
                    relock(ctx.contentResolver, "合上时发现跟随遗留，已恢复锁定")
                }
                // 合上时把内屏预置成 270°：展开第一帧即是横的（用户 2026-11-13 定的方案）
                pinInner(UNFOLD_PIN_ROTATION)
                return@runCatching
            }
            // 进入内屏：只处理展开边沿（已在内屏则返回）
            if (innerActive) return@runCatching
            innerActive = true
            innerStateAt = SystemClock.uptimeMillis()
            Logx.always("$TAG 检测到展开（$reason）")
            onUnfold(ctx)
        }.onFailure { Logx.e("$TAG 折叠状态处理异常", it) }
    }

    private fun onUnfold(ctx: Context) {
        val cr = ctx.contentResolver
        if (following) {
            Logx.always("$TAG 计时中，忽略重复触发")
            return
        }
        // 冷却：同一次亮屏会连发多次回调
        val sinceEnd = SystemClock.uptimeMillis() - lastWindowEndAt
        if (lastWindowEndAt > 0L && sinceEnd >= 0 && sinceEnd < INNER_WINDOW_COOLDOWN_MS) {
            Logx.always("$TAG 距上次跟随仅 ${sinceEnd}ms，冷却中")
            return
        }
        // 用户自己开着自动旋转 → 一律不碰（用户意志优先）
        if (isAutoRotateOn(cr)) {
            // 判「是不是模块刚写的 1」：是就继续走流程，不是就是用户亲手开的
            if (occupyUntil > 0L && occupyUntil < System.currentTimeMillis()) {
                relock(ctx.contentResolver, "检测到上次跟随的遗留占用，已恢复锁定（自愈）")
                return
            }
            if (!wasSelfWritten()) {
                if (userIntent != 1) {
                    userIntent = 1
                    persistState()
                }
                Logx.always("$TAG 旋转锁本来就是打开且不是模块所写 → 视为用户选择，联动让位")
                return
            }
            Logx.always("$TAG 开关为 1 但属模块刚写的，继续联动流程")
        }
        // 正式放开：解除锁定 + 跟随
        following = true
        callWmRotationLocked(false)
        setInnerAutoRotate(cr, true)
        markOccupy()
        persistState()
        Logx.always("$TAG 展开：已放开旋转锁，${Constants.INNER_FOLLOW_DURATION_MS}ms 后恢复锁定")
        RotationHint.reconcile("内屏联动：临时放开锁定")
        scheduleRelock(cr, Constants.INNER_FOLLOW_DURATION_MS)
    }

    /** 恢复锁定 —— 唯一出口。 */
    private fun relock(cr: android.content.ContentResolver, reason: String) {
        callWmRotationLocked(true)
        setInnerAutoRotate(cr, false)
        following = false
        occupyUntil = 0L
        persistState()
        Logx.always("$TAG $reason")
        RotationHint.reconcile("内屏联动：$reason")
    }

    private fun scheduleRelock(cr: android.content.ContentResolver, delayMs: Long) {
        cancelRelock()
        val r = Runnable {
            runCatching {
                if (!following) return@Runnable
                // 占用标记已被用户操作清零 → 开关不归模块管，保持现状
                if (occupyUntil <= 0L) {
                    following = false
                    persistState()
                    Logx.always("$TAG 计时结束：占用已被用户清零，保持现状不回锁")
                    return@Runnable
                }
                // 用户在窗口期内自己开了自动旋转 → 已记为用户意志，最高优先级
                if (userIntent == 1) {
                    following = false
                    occupyUntil = 0L
                    persistState()
                    Logx.always("$TAG 计时结束：用户已选择自由旋转，保持现状不回锁")
                    return@Runnable
                }
                if (!isAutoRotateOn(cr)) {
                    callWmRotationLocked(true)
                    following = false
                    occupyUntil = 0L
                    persistState()
                    Logx.always("$TAG 计时结束（开关已被关闭），已对齐旋转锁")
                    return@Runnable
                }
                relock(cr, "计时结束，已恢复锁定")
            }.onFailure { Logx.e("$TAG 恢复锁定失败", it) }.also {
                following = false
                lastWindowEndAt = SystemClock.uptimeMillis()
            }
        }
        relockRunnable = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelRelock() {
        relockRunnable?.let { main.removeCallbacks(it) }
        relockRunnable = null
    }

    // ======================================================================
    //  用户意志与占用标记
    // ======================================================================

    /**
     * 盯用户面开关 `accelerometer_rotation`：只要被**用户**改过，
     * 就记成用户意志并落盘，此后联动一律让位。
     *
     * 为什么用通用键而不是 `_inner`：实测 `_inner` 会被 MIUI 自己同步成 1
     * （用户关了它也可能还是 1），拿它判断会把「用户要锁定」误读成「要自由旋转」。
     * 用户能看见、能操作的就是 `accelerometer_rotation`，以它为准。
     */
    private fun ensureObserver(ctx: Context) {
        if (observerInstalled) return
        runCatching {
            val cr = ctx.contentResolver
            lastSeenCr = readInt(cr, "accelerometer_rotation", -1)
            val obs = object : android.database.ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    runCatching { onSwitchMaybeChanged() }
                        .onFailure { Logx.e("$TAG 处理设置变化失败", it) }
                }
            }
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation"), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation_inner"), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation_outer"), false, obs)
            observerInstalled = true
            Logx.always("$TAG 已装用户意志监听")
        }.onFailure { Logx.e("$TAG 用户意志监听安装失败", it) }
    }

    private fun onSwitchMaybeChanged() {
        val ctx = ctxRef ?: return
        val cr = ctx.contentResolver
        val crV = readInt(cr, "accelerometer_rotation", -1)
        if (crV < 0) return
        // 是模块自己写的回声 → 不算用户操作
        val elapsed = SystemClock.uptimeMillis() - selfWriteAt
        if (elapsed < 400L && lastSeenCr != crV && wasSelfWritten()) {
            lastSeenCr = crV
            return
        }
        if (lastSeenCr == crV) return
        val prev = lastSeenCr
        lastSeenCr = crV
        if (prev < 0) return
        // 用户亲手改了 → 记为最高优先级
        userIntent = if (crV != 0) 1 else 0
        occupyUntil = 0L                        // 这把锁不再归模块
        persistState()
        Logx.always("$TAG 检测到手动改动 accelerometer_rotation=$prev→$crV，记为用户意志（${if (crV != 0) "自由旋转" else "锁定旋转"}）")
        RotationHint.reconcile("用户手动改旋转开关")
    }

    private fun markOccupy() {
        // 跟随窗口 + 3 秒自愈余量
        occupyUntil = System.currentTimeMillis() + Constants.INNER_FOLLOW_DURATION_MS + 3000L
        selfWriteAt = SystemClock.uptimeMillis()
        userOpenedByHand = false
    }

    /** 模块刚写过 → 键上的 1 不是用户意志。 */
    private fun wasSelfWritten(): Boolean =
        SystemClock.uptimeMillis() - selfWriteAt < 800L

    /** 上次跟随被打断留下的「开」→ 收回（进程重启也能自愈）。 */
    private fun healIfStale(reason: String) {
        if (following) return
        if (occupyUntil <= 0L) return
        val ctx = ctxRef ?: return
        val cr = ctx.contentResolver
        if (!isAutoRotateOn(cr)) {
            occupyUntil = 0L
            persistState()
            return
        }
        // 已过期且用户没有表过"要自由旋转" → 是我们的遗留
        if (occupyUntil < System.currentTimeMillis() && userIntent != 1) {
            relock(cr, "$reason：发现跟随遗留占用，已恢复锁定")
        }
    }

    // ======================================================================
    //  系统状态读写
    // ======================================================================

    private fun isInnerNow(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, "current_device_state", -1) == 3
    } catch (t: Throwable) {
        innerActive
    }

    private fun isInteractive(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm == null || pm.isInteractive
    } catch (t: Throwable) {
        true
    }

    private fun isAutoRotateOn(cr: android.content.ContentResolver): Boolean {
        val inner = readInt(cr, "accelerometer_rotation_inner", -1)
        val common = readInt(cr, "accelerometer_rotation", -1)
        return (inner != 0) || (common != 0)
    }

    /**
     * 只切「内屏自动旋转开关」这一个值。
     *
     * ✅ 正确（本机铁律）：只写 `accelerometer_rotation_inner` 这个**开关**；
     * ❌ 绝不写 `user_rotation_inner` 之类的**方向值**。
     */
    private fun setInnerAutoRotate(cr: android.content.ContentResolver, unlocked: Boolean) {
        runCatching {
            val v = if (unlocked) 1 else 0
            Settings.System.putInt(cr, "accelerometer_rotation_inner", v)
            selfWriteAt = SystemClock.uptimeMillis()
            Logx.v("$TAG accelerometer_rotation_inner=$v")
        }.onFailure { Logx.e("$TAG 写内屏自动旋转开关失败", it) }
    }

    /** 合上时把（此刻不可见的）内屏预置到指定方向。 */
    private fun pinInner(rotation: Int) {
        runCatching {
            callWmRotationLocked(true, rotation)
            Logx.always("$TAG 合上：内屏方向已预置为 $rotation（展开第一帧即 270°）")
        }.onFailure { Logx.v("$TAG 预置内屏方向失败: $it") }
    }

    /** 调 WMS 的旋转锁（`setRotationLockForFold` 优先）。 */
    private fun callWmRotationLocked(locked: Boolean, rotation: Int = -1) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)
            // setRotationLockForFold(arg0, arg1, arg2)
            //   arg0 true → 操作外屏；false → 操作内屏
            //   arg1 = 方向（-1 表示沿用当前）
            val m = wm.javaClass.getMethod(
                "setRotationLockForFold",
                java.lang.Boolean.TYPE, Int::class.javaPrimitiveType, java.lang.Boolean.TYPE
            )
            m.isAccessible = true
            val inner = !OrientationForcer.isInnerScreen(ctxRef)
            m.invoke(wm, inner, rotation, false)
            Logx.v("$TAG setRotationLockForFold(外屏=$inner, locked=$locked, rot=$rotation)")
        }.onFailure { Logx.v("$TAG setRotationLockForFold 不可用: $it") }
    }

    private fun readInt(cr: android.content.ContentResolver, key: String, def: Int): Int =
        runCatching { Settings.System.getInt(cr, key, def) }.getOrDefault(def)

    // ---------------- 落盘 ----------------

    private fun loadState(ctx: Context) {
        if (userIntent != -2) return
        runCatching {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            userIntent = sp.getInt(KEY_USER_INTENT, -1)
            occupyUntil = sp.getLong(KEY_OCCUPY_UNTIL, 0L)
            Logx.always("$TAG 已加载状态：userIntent=$userIntent occupyUntil=$occupyUntil")
        }.onFailure {
            userIntent = -1
            Logx.e("$TAG 读取本地状态失败", it)
        }
    }

    private fun persistState() {
        val ctx = ctxRef ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_USER_INTENT, userIntent)
                .putLong(KEY_OCCUPY_UNTIL, occupyUntil)
                .apply()
        }.onFailure { Logx.e("$TAG 保存本地状态失败", it) }
    }
}
