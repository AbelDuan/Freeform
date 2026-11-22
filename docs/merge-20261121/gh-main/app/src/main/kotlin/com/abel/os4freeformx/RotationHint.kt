package com.abel.os4freeformx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 锁定方向时的旋转提示（自 OS4 Themer 的 RotationHint 移植，2026-11-20）。
 *
 * ## 需求
 * 手机锁定了屏幕方向（关闭「自动旋转」）时，如果**重力传感器感受到的方向**
 * 与**当前屏幕方向**不一致，就弹出一个旋转按钮，让用户一键把界面转过去。
 *
 * ## 为什么系统不自己弹
 * AOSP 本来就有 `FloatingRotationButton`，但原生逻辑是「系统建议你旋转时才弹」，
 * 判断依据是一大套 RotationPolicy（OEM 开关、无障碍、车载模式等）。
 * 小米把这套策略调得很保守 —— 实测锁定方向后怎么转都不弹。
 * 用户要的就是把这个行为补回来：**方向真的不一致就弹**。
 *
 * ## 方向判定为什么用「重力轮询」而不是 TYPE_DEVICE_ORIENTATION
 * OS4 Themer 实测（2026-10-22）：`TYPE_DEVICE_ORIENTATION` 是 **on-change** 传感器，
 * 只在方向跨过边界时上报一次。高强度快速旋转时两次上报间隔能到 3 秒以上，
 * 中间无论怎么转都不会有事件 —— 按钮就停在旧角度不动，甚至「卡在某个方向」。
 *
 * 所以这里挂一路**持续型重力传感器**（`TYPE_ACCELEROMETER`），用标准公式自己算朝向：
 * ```
 *   roll     = atan2(gx, gy)                    // 设备绕屏幕法线的转角
 *   rotation = (round(roll/90) + 4) % 4
 * ```
 * 验证：roll 0→0、-90→3(270)、180→2、+90→1(90)，与 Android 的 Surface.ROTATION_* 一致。
 *
 * ## 稳定性与延迟（每一条都是 OS4 Themer 用真机日志调出来的）
 * - **稳态 [STABLE_MS]**：要求同一方向连续维持 180ms 才显示。
 *   太短会被「拿手机的过渡姿态」和传感器抖动骗到（0↔2 来回跳就闪一下按钮）；
 *   太长用户会觉得跟不上。800→500→380→**180** 是取值过程。
 * - **收→显不等待**、**停稳确认延时为 0**：用户明确要求取消所有延迟。
 * - **自动旋转开着时**：屏幕会自己转，本来不该弹；但应用把方向写死时会转不动，
 *   这时等 [SELF_ROTATE_GRACE_MS] 看系统是否真的没转，没转才给旋钮
 *   （用户定的规则：「能转就别管，转不动才介入」）。
 *
 * ## 安全边界
 * - 总开关默认**开**，关掉后不注册传感器、不碰任何系统状态；
 * - 视���只在本模块自己的悬浮窗里，不 hook 系统旋转按钮；
 * - 全程 try/catch，出错只记日志，绝不连累 SystemUI。
 */
internal object RotationHint {

    private const val TAG = "[旋转提示]"

    /** 等 Context 的最多尝试次数（每 1.5s 一次）——覆盖 SystemUI 冷启动。 */
    private const val INSTALL_MAX_TRIES = 15
    /** 每次重试间隔。 */
    private const val INSTALL_RETRY_MS = 1_500L

    /** 方向传感器回调到实际弹按钮之间的稳定延时（用户要求取消所有延迟 → 0）。 */
    private const val STABLE_DELAY_MS = 0L

    /** 导航栏视图类（提供 getRotationButtonController）——保留用于「系统按钮去重」。 */
    private const val NAV_BAR_CLASS =
        "com.android.systemui.navigationbar.views.NavigationBarView"

    /** 主线程 Handler。 */
    private val main = Handler(Looper.getMainLooper())

    // ---------------- 传感器状态 ----------------

    @Volatile private var sensorMgr: SensorManager? = null
    @Volatile private var listener: SensorEventListener? = null
    @Volatile private var sensorRegistered = false
    /** 最近一次重力读数。 */
    @Volatile private var lastGx = 0f
    @Volatile private var lastGy = 0f
    @Volatile private var lastGz = 0f
    /** 加速度计算出的候选方向与其连续计数（去抖）。 */
    @Volatile private var pendingAccelRotation = -1
    @Volatile private var accelSameCount = 0
    /**
     * 传感器此刻感知到的手机物理姿态（Surface.ROTATION_*）。落点与显示判断都用它。
     */
    @Volatile private var sensorTargetRotation = -1

    /** 要求同一方向连续多少次才采纳（约 300ms @10Hz，滤掉转动过程的抖动）。 */
    private const val ACCEL_SAME_COUNT = 3

    // ---------------- 稳态与显示状态 ----------------

    /** 稳态时长：同一方向维持这么久才认为「用户确实停在这个方向」。 */
    private const val STABLE_MS = 180L

    /** 当前候选方向。 */
    @Volatile private var stableTarget = -1
    /** 该候选方向是从什么时候开始持续的（uptimeMillis）。 */
    @Volatile private var stableSince = 0L

    /** 当前按钮是为哪个方向显示的；-1 表示没显示。 */
    @Volatile private var shownFor = -1
    /**
     * 按钮当前**视觉上代表的方向**快照。
     *
     * 只在 [showNow] 真正把按钮画出来时写入，之后传感器怎么抖都不改它。
     * 点击时读这个值，保证「看到哪个方向就转到哪个方向」。
     * （OS4 Themer 实测：读实时 sensor 值会出现「点下去转错方向」。）
     */
    @Volatile private var shownForAt = -1

    /** 挂起中的「停稳后显示」任务（单例，避免快速旋转时任务堆积）。 */
    @Volatile private var pendingShow: Runnable? = null
    /** 稳态复检任务（单例）。 */
    @Volatile private var stableRecheck: Runnable? = null
    /** 自动旋转场景的延迟判定任务（单例）。 */
    @Volatile private var forceHintTask: Runnable? = null

    /** ��击后的静默期截止时刻：这之前不再因传感器尾帧弹/收按钮。 */
    @Volatile private var suppressedUntil = 0L
    /** 点击后的静默时长：够系统转完 + 校验一次，又不至于吞掉后续真实旋转。 */
    private const val CLICK_QUIET_MS = 700L

    /** 自动屏：等这么久屏幕仍没跟上，才认定「应用拒绝旋转」，给旋钮。 */
    private const val SELF_ROTATE_GRACE_MS = 2000L

    /** 已提示过的目标方向（避免同方向重复显示动画）。 */
    @Volatile private var lastShowAt = 0L

    // ---------------- 系统状态监听 ----------------

    @Volatile private var watchersInstalled = false
    @Volatile private var watchedScreenRotation = -1
    @Volatile private var screenOnAt = 0L

    /** SystemUI 的 Context（传感器注册与 Settings 读取都需要）。 */
    @Volatile private var ctxRef: Context? = null

    /** 屏幕方向（0/1/2/3）。 */
    @Volatile private var screenRotation = -1

    // ======================================================================
    //  安装
    // ======================================================================

    /**
     * 挂到 SystemUI 进程。
     *
     * 与 OS4 Themer 的差异：那边要 hook `NavigationBarView` 的生命周期方法才能拿到
     * Context / RotationButtonController；本模块**自绘**按钮、不需要系统控制器，
     * 因此改用 `AppCtx.get()` 直接取 Context，省掉三个生命周期 hook。
     * 这样也不会和本模块已有的 NavigationBar hook 抢调用链。
     */
    fun install(m: XposedModule, cl: ClassLoader) {
        // SystemUI 刚起来时 AppCtx.get() 内部要做 createPackageContext，
        // 而此刻 PackageManager 可能还没就绪 → NameNotFoundException（实测 2026-11-20）。
        // 这不是"该吞掉的异常"，而是**时机没到**：这里主动等到 Context 可用再装，
        // 与 FoldSplitTrigger 注册折角传感器用的是同一套做法。
        scheduleInstall(attempt = 0)
    }

    /** 等 AppCtx 就绪再安装（每 1.5s 一次，最多 15 次 ≈ 22 秒，足够覆盖 SystemUI 冷启动）。 */
    private fun scheduleInstall(attempt: Int) {
        main.postDelayed({
            runCatching {
                val ctx = AppCtx.get()
                if (ctx == null) {
                    if (attempt + 1 < INSTALL_MAX_TRIES) {
                        scheduleInstall(attempt + 1)
                    } else {
                        Logx.always("$TAG 安装放弃：${INSTALL_MAX_TRIES} 次都没拿到 Context")
                    }
                    return@postDelayed
                }
                ctxRef = ctx
                ensureWatchers(ctx)
                RotationButtonOverlay.setOnClick { applyRotationSuggestion() }
                if (Cfg.rotationHint) registerSensor()
                Logx.always("$TAG 已安装（开关=${Cfg.rotationHint}，第 ${attempt + 1} 次尝试）")
            }.onFailure {
                Logx.e("$TAG 安装失败（第 ${attempt + 1} 次）", it)
                if (attempt + 1 < INSTALL_MAX_TRIES) scheduleInstall(attempt + 1)
            }
        }, if (attempt == 0) 0L else INSTALL_RETRY_MS)
    }



    /** 配置变化（设置页改开关）时调用。 */
    fun onPrefsChanged() {
        if (Cfg.rotationHint) {
            registerSensor()
            reconcile("开关已打开")
        } else {
            unregisterSensor()
            if (shownFor >= 0) hideButtonNow()
            OrientationForcer.release("旋转提示已关闭")
        }
    }

    /** 亮/熄屏回调：熄屏时注销传感器省电，亮屏时重新注册并重算。 */
    fun onScreenStateChanged(on: Boolean) {
        if (on) {
            screenOnAt = SystemClock.uptimeMillis()
            if (Cfg.rotationHint) {
                registerSensor()
                // 亮屏瞬间屏幕方向可能还没刷新，延后一点再算
                main.postDelayed({ reconcile("亮屏") }, 300L)
            }
        } else {
            unregisterSensor()
        }
    }

    // ======================================================================
    //  传感器
    // ======================================================================

    private fun registerSensor() {
        if (sensorRegistered) return
        val ctx = ctxRef ?: AppCtx.get() ?: return
        ctxRef = ctx
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorMgr = sm
        // 优先用重力传感器（已滤过线性加速度，比裸加速度计稳）；没有就退加速度计
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Logx.always("$TAG 无重力/加速度传感器，旋转提示不可用")
            return
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                runCatching { onGravity(event) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // 10Hz：够判断方向，又不像 60Hz 那样持续唤醒 CPU（OS4 Themer 的省电结论）
        val ok = sm.registerListener(l, sensor, 100_000) // 100ms ≈ 10Hz
        if (ok) {
            listener = l
            sensorRegistered = true
            Logx.always("$TAG 已注册方向传感器（${sensor.name} @10Hz）")
        } else {
            Logx.always("$TAG 注册方向传感器失败（registerListener 返回 false）")
        }
    }

    private fun unregisterSensor() {
        val l = listener ?: return
        runCatching { sensorMgr?.unregisterListener(l) }
        listener = null
        sensorRegistered = false
        Logx.v("$TAG 已注销方向传感器（省电）")
    }

    /**
     * 重力读数 → 设备朝向。
     *
     * 用 `atan2(gx, gy)` 求绕屏幕法线的转角，再量化到 4 个方向。
     * 连续 [ACCEL_SAME_COUNT] 次算出一致方向才采纳，滤掉转动过程中的抖动。
     */
    private fun onGravity(event: SensorEvent) {
        if (event.values.size < 3) return
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        lastGx = gx
        lastGy = gy
        lastGz = gz
        // 设备几乎平放时 gx/gy 都很小，atan2 噪声极大 → 不参与判定，保持上一方向
        if (Math.abs(gx) < 1.0f && Math.abs(gy) < 1.0f) return

        val roll = Math.toDegrees(Math.atan2(gx.toDouble(), gy.toDouble()))
        val rot = ((Math.round((roll / 90.0)).toInt() + 4) % 4)
        if (rot == pendingAccelRotation) {
            accelSameCount++
        } else {
            pendingAccelRotation = rot
            accelSameCount = 1
            return
        }
        if (accelSameCount < ACCEL_SAME_COUNT) return
        accelSameCount = 0
        onOrientationChanged(rot)
    }

    /** 方向确实变了（已去抖）。 */
    private fun onOrientationChanged(target: Int) {
        if (!Cfg.rotationHint) return
        if (target == sensorTargetRotation) return
        sensorTargetRotation = target
        Logx.v("$TAG 传感器方向 → $target（屏幕=${screenRotation}）")

        // ① 点击后的静默期：方向尾帧一律不理（否则按钮会被拉走/乱弹）
        if (isClickQuiet()) return

        // ② 方向一变就立即收起旧按钮（用户要求：「再旋转就立即收回，不要显示」）
        if (shownFor >= 0 && shownFor != target) {
            hideButtonNow()
        }

        // ③ 稳态计时：同方向继续计时，换了方向重头算
        if (stableTarget != target) {
            stableTarget = target
            stableSince = SystemClock.uptimeMillis()
        }
        scheduleStableRecheck(STABLE_MS)
    }

    // ======================================================================
    //  判定与显示
    // ======================================================================

    /**
     * 重算「现在到底该不该显示旋钮」，是唯一的显示判据入口。
     *
     * 判据（与 OS4 Themer 用户确认过的规则一致）：
     * 1. 开关关着 → 收起；
     * 2. 传感器方向 == 屏幕方向 → 收起，用户姿势已经对了；
     * 3. 旋转锁开着（锁定中）→ 方向不一致就显示；
     * 4. 旋转锁关着（自动旋转）→ 屏幕会自己转，等系统转；只有「应用不跟随」才显示
     *    （需 [Cfg.forceRotate] 打开）。
     *
     * 注意这**不是**轮询：只在「屏幕方向变了 / 锁开关变了 / 亮屏」这些离散事件上跑一次，
     * 静默期没有任何定时器。
     */
    fun reconcile(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { reconcile(reason) }
            return
        }
        runCatching {
            Logx.v("$TAG reconcile 进入：reason=$reason 开关=${Cfg.rotationHint} ctx=${ctxRef != null} 静默=${isClickQuiet()}")
            if (!Cfg.rotationHint) {
                Logx.v("$TAG reconcile 退出：旋转提示开关关闭")
                if (shownFor >= 0) hideButtonNow()
                return
            }
            val ctx = ctxRef ?: AppCtx.get()
            if (ctx == null) {
                Logx.v("$TAG reconcile 退出：拿不到 Context")
                return
            }
            ctxRef = ctx
            // 点���后的静默期内，显/收都交给点击收尾决定
            if (isClickQuiet()) return

            screenRotation = currentScreenRotation(ctx)
            val screen = screenRotation
            if (screen < 0) return
            val sensor = sensorTargetRotation
            if (sensor < 0) {
                Logx.once("rh-nosensor", "$TAG $reason：还不知道物理姿态（sensor<0），等传感器")
                return
            }
            Logx.v("$TAG $reason：屏幕=$screen 传感器=$sensor 锁=${isRotationLocked(ctx)} 锁屏=${isKeyguardShowing(ctx)}")

            // 锁屏界面不显示：用户在锁屏上不需要转屏建议，旋钮还会盖住内容
            if (isKeyguardShowing(ctx)) {
                Logx.v("$TAG $reason：判据=锁屏中，不显示")
                if (shownFor >= 0) hideButtonNow()
                return
            }
            if (!isRotationLocked(ctx) && !allowHintWhileUnlocked(ctx)) {
                if (shownFor >= 0) {
                    Logx.always("$TAG $reason：旋转锁未开启（或仍在等应用自己转），收起")
                    hideButtonNow()
                }
                return
            }
            if (sensor == screen) {
                if (shownFor >= 0) {
                    Logx.always("$TAG $reason：方向已一致，收起")
                    hideButtonNow()
                }
                return
            }
            if (shownFor == sensor) return   // 已在为这个方向显示，别重置超时

            // 稳态钳制：方向至少稳定 STABLE_MS 才显示
            val stable = SystemClock.uptimeMillis() - stableSince
            if (stable < STABLE_MS) {
                scheduleStableRecheck(STABLE_MS - stable + 20L)
                return
            }
            showNow(sensor, screen)
        }.onFailure { Logx.e("$TAG reconcile 异常", it) }
    }

    /**
     * 自动旋转开着时，是否该把旋钮弹出来（= 应用拒绝旋转）。
     *
     * 规则（OS4 Themer 用户 2026-11-17 定稿）：
     * 「如果软件本身支持旋转、或者它进行了旋转，那模块就不要去干涉；
     * 如果软件在屏幕切换方向之后没有旋转，模块才介入。」
     *
     * 所以要给系统充分的自行旋转时间（视频类应用转屏通常 300~900ms），
     * 期间只要屏幕方向对上了就立刻退出干预。
     */
    private fun allowHintWhileUnlocked(ctx: Context): Boolean {
        if (!Cfg.forceRotate) return false
        val age = SystemClock.uptimeMillis() - stableSince
        val want = stableTarget
        val now = currentScreenRotation(ctx)
        if (want >= 0 && now >= 0 && want == now) return false   // 系统已转好，无需介入
        if (age < SELF_ROTATE_GRACE_MS) {
            scheduleForceHint(SELF_ROTATE_GRACE_MS - age + 40L)
            return false
        }
        Logx.always("$TAG 自动旋转已开但方向仍为 $now（期望 $want），判定应用不跟随，提供旋钮")
        return true
    }

    /** 唯一显示出口。 */
    private fun showNow(targetRotation: Int, current: Int) {
        if (isClickQuiet()) return
        val ctx = ctxRef ?: return
        val corner = RotationButtonOverlay.cornerFor(targetRotation, current)
        RotationButtonOverlay.show(ctx, corner)
        shownFor = targetRotation
        shownForAt = targetRotation
        lastShowAt = SystemClock.uptimeMillis()
        Logx.always("$TAG 已显示（当前=$current 目标=$targetRotation 角落=$corner）")
    }

    /** 收起按钮（同步，立即消失）。 */
    private fun hideButtonNow() {
        shownFor = -1
        RotationButtonOverlay.hideNow()
    }

    /** 点击后的静默期。 */
    private fun isClickQuiet(): Boolean =
        SystemClock.uptimeMillis() < suppressedUntil

    // ---------------- 点击 ----------------

    /**
     * 点击旋钮：把屏幕转到按钮此刻代表的方向。
     *
     * 只用 [shownForAt] 这个**已冻结的快照**，绝不读实时的 [sensorTargetRotation] ——
     * OS4 Themer 实测：手指按下的轻微晃动会让传感器翻页，读实时值会出现
     * 「点了没反应」或「转错方向」，因为那一刻窗口可能已经被传感器尾帧摘掉了。
     */
    private fun applyRotationSuggestion() {
        val shown = shownForAt
        val target = if (shown >= 0) shown else sensorTargetRotation
        Logx.always("$TAG 点击旋钮：目标=$target（快照=$shown 传感器=$sensorTargetRotation）")
        val ctx = ctxRef ?: AppCtx.get()
        if (target < 0 || ctx == null) {
            Logx.always("$TAG 点击失败：target=$target ctx=$ctx")
            hideButtonNow()
            return
        }
        // 关闭「方向变了自动收起」这条通道，直到转完/超时
        suppressedUntil = SystemClock.uptimeMillis() + CLICK_QUIET_MS

        if (Cfg.forceRotate && OrientationForcer.force(ctx, target, "点击旋钮")) {
            Logx.always("$TAG 已请求强制旋转 → rotation=$target")
            // 保留按钮直到屏幕真的转过去（用户能看到「点上了」的反馈）
            rotateUntilIdle(target, ctx)
            return
        }
        // 老路径：只锁方向
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)
            val m = wm.javaClass.getMethod("freezeRotation", Int::class.javaPrimitiveType, String::class.java)
            m.isAccessible = true
            m.invoke(wm, target, "OS4FreeFromX")
            Logx.always("$TAG 已接受旋转建议，转到 rotation=$target")
            rotateUntilIdle(target, ctx)
        }.onFailure { Logx.e("$TAG 点击旋转失败", it) }
    }

    /**
     * 点击后等屏幕转到目标方向（或静默期结束）再收起按钮。
     *
     * OS4 Themer v3.295.0 的教训：点击时**立刻**收起，用户会看到「图标闪一下就没了」，
     * 不知道点没点上；而屏幕真正转过去要 300~600ms。所以保留按钮，转到位再收。
     */
    private fun rotateUntilIdle(target: Int, ctx: Context) {
        val deadline = SystemClock.uptimeMillis() + CLICK_QUIET_MS + 400L
        fun tick() {
            val now = currentScreenRotation(ctx)
            if (now == target || SystemClock.uptimeMillis() > deadline) {
                Logx.always("$TAG 点击收尾：屏幕=$now 目标=$target → 收起")
                hideButtonNow()
                return
            }
            main.postDelayed({ tick() }, 80L)
        }
        main.postDelayed({ tick() }, 120L)
    }

    // ---------------- 定时任务（单例，避免堆积）----------------

    private fun scheduleStableRecheck(delayMs: Long) {
        stableRecheck?.let { main.removeCallbacks(it) }
        val r = Runnable {
            stableRecheck = null
            reconcile("稳态复检")
        }
        stableRecheck = r
        main.postDelayed(r, delayMs.coerceAtLeast(16L))
    }

    private fun scheduleForceHint(delay: Long) {
        forceHintTask?.let { main.removeCallbacks(it) }
        val r = Runnable {
            forceHintTask = null
            reconcile("自动旋转下的延迟判定")
        }
        forceHintTask = r
        main.postDelayed(r, delay)
    }

    // ======================================================================
    //  系统状态判定
    // ======================================================================

    /**
     * 当前屏幕的「旋转锁定」是否处于开启状态。
     *
     * 折叠屏分内外屏，两者**各自独立**（实测 outer=1 而 common=0 的组合存在）：
     * - 内屏（`current_device_state==3`）读 `accelerometer_rotation_inner`；
     * - 外屏优先读 `accelerometer_rotation_outer`（该 ROM 没有这个键时才退通用键）。
     *
     * 读错键会把「用户已取消锁定」误判成「仍锁定」，模块于是接管旋转、弹出旋钮 ——
     * 而系统本来能自己转，表现就是用户说的「不用模块能转，用了模块就得点旋钮」。
     *
     * @return 锁定中返回 true；读不到时返回 true（宁可显示，不让功能整体失效）
     */
    private fun isRotationLocked(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        val state = Settings.Global.getInt(cr, "current_device_state", -1)
        if (state == 3) {
            val auto = Settings.System.getInt(cr, "accelerometer_rotation_inner", -1)
            auto < 0 || auto == 0
        } else {
            val outer = Settings.System.getInt(cr, "accelerometer_rotation_outer", -1)
            if (outer >= 0) {
                outer == 0
            } else {
                val auto = Settings.System.getInt(cr, "accelerometer_rotation", -1)
                auto < 0 || auto == 0
            }
        }
    } catch (t: Throwable) {
        true
    }

    /** 锁屏是否正在显示（含亮屏未解锁）—— 这期间不该出现旋钮。 */
    private fun isKeyguardShowing(ctx: Context): Boolean = try {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km != null && km.isKeyguardLocked
    } catch (t: Throwable) {
        false
    }

    /** 当前活跃屏的方向。 */
    private fun currentScreenRotation(ctx: Context): Int = try {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1
    } catch (t: Throwable) {
        -1
    }

    // ======================================================================
    //  状态监听：屏幕方向 / 旋转锁 / 折叠状态
    // ======================================================================

    /**
     * 装「状态变化」监听，让旋钮在**任何**相关状态变化后重算一次。
     *
     * 为什么必须有这一层（OS4 Themer 2026-10-24 修复「锁定旋转后转屏幕不弹旋钮」）：
     * 只盯「传感器方向」这一个输入时，「该不该显示」其实还取决于屏幕方向和旋转锁开关。
     * 这两个变了不重算，就会出现「屏幕转了、物理仍相反，但旋钮不出现」（日志实测）。
     */
    private fun ensureWatchers(ctx: Context) {
        if (watchersInstalled) return
        runCatching {
            // ① 屏幕方向变化
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.registerDisplayListener(object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) {
                    RotationButtonOverlay.reset()
                    reconcile("屏幕被移除")
                }

                override fun onDisplayChanged(displayId: Int) {
                    val c = ctxRef ?: return
                    val now = currentScreenRotation(c)
                    if (now < 0 || now == watchedScreenRotation) return
                    watchedScreenRotation = now
                    reconcile("屏幕方向变化→$now")
                }
            }, main)

            // ② 旋转锁 / 折叠状态变化
            val cr = ctx.contentResolver
            val obs = object : android.database.ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    Logx.once("rh-obs-first", "$TAG 状态监听已触发（首次）")
                    // 用 vitals 级别记录每次回调的对象，避免"没日志就以为没触发"
                    Logx.v("$TAG 状态变化回调 → reconcile")
                    reconcile("旋转锁/设备状态变化")
                }
            }
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation"), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation_inner"), false, obs)
            cr.registerContentObserver(Settings.System.getUriFor("accelerometer_rotation_outer"), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor("current_device_state"), false, obs)

            watchersInstalled = true
            watchedScreenRotation = currentScreenRotation(ctx)
            Logx.always("$TAG 已装状态监听（屏幕方向 / 旋转锁 / 折叠状态）")
        }.onFailure { Logx.e("$TAG 状态监听安装失败", it) }
    }
}
