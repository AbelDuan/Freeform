package com.abel.os4freeformx

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import io.github.libxposed.api.XposedInterface

/**
 * ★★ 轻折一下 → 分屏 +1（折叠屏专属）。
 *
 * ## 功能
 * 展开内屏使用手机时，**轻轻折一下再松开**，当前应用进分屏（另一侧由系统补位）；
 * 已经在多分屏（3 格及以上）时再折一下 → **追加一格**。
 * 展开方向**可选**：当前应用留在**左**还是**右**（设置页两项单选）。
 *
 * ## 设计来源
 * 实现思路来自 HyperPlus v0.9.0-alpha（`FoldJudge.kt` / `SplitTrigger.kt`，AGPL-3.0），
 * 按其公开的行为规格重写，并适配本机 `Miui-WindowManager-Shell.jar` 的类名
 * （已在 dex 里逐个 grep 验证：`SoScSplitScreenController` / `splitPrimaryTask` /
 * `multiple_split_dock_task` / `getCurrentActiveStageCount` 全部存在）。
 *
 * ## 绑不住的三件事（照抄来源项目的踩坑结论，别改）
 * 1. **超时必须排在两条回弹之前**。`hinge_angle` 是 on-change 传感器 —— 手机静置时**没有任何回调**。
 *    「折一下停几秒再展开」这条路上只有"展开那一下"一个样本，它天然满足「自谷底回升 ≥ D1」；
 *    若把超时放到最后，它永远够不着 ⇒ 用户报的「过了动作时限还是分屏」就是这么来的。
 * 2. **判为"合上"时要清空展开基线窗口**。否则合上之前的 179° 还在窗口里，
 *    重新展开时 `A0(179) - 158 = 21 > D1` ⇒ **打开手机这个动作本身被当成"轻折又松手"**。
 * 3. **计时起点必须是"开始往下折"那一刻**，不是"折深已越过 D1"那一刻 ——
 *    用户口径的「1 秒内」是**一个完整动作**的时长，含"折下去"那一段。
 *
 * ## 纪律（本机代码跑在 SystemUI 进程里）
 * - 传感器监听挂**自建 HandlerThread**，判定里要反射、要打日志，不能占主线程的帧预算；
 * - 每一步 `runCatching`；拿到反射值才动手，**拿不到就安静放弃**，绝不重试、绝不提示用户；
 * - 真动作一律投递到 **WMShell 主线程**执行（controller 内部有记账状态，错线程会留下脏状态）；
 * - **单侧（minimized）分屏绝不介入** —— 这是 v0.7.5 拿崩 SystemUI 换来的铁律。
 */
object FoldSplitTrigger {

    // ───────────────────────── 接地的 WMShell 类（dex 里已逐个验证存在） ─────────────────────────

    /** 双分屏控制器：`splitPrimaryTask(int pos, int taskId)` 是三指滑那条链，天然只做 0 → 2。 */
    private const val CTRL_CLASS = "com.android.wm.shell.sosc.SoScSplitScreenController"

    private const val ORG_CLASS = "com.android.wm.shell.ShellTaskOrganizer"

    /** 多分屏真身：`dockMultipleSplitTasks(Bundle)` 是"拖到多分屏图标"那条链，追加一格走它。 */
    private const val MSPLIT_ORG = "com.android.wm.shell.multiplesplit.MultipleSplitRootTaskOrganizer"

    private const val SOSC_UTILS = "com.android.wm.shell.sosc.SoScUtilsImpl"

    /** `dockMultipleSplitTasks` 的 Bundle 键（装的是**分屏 root 的 RunningTaskInfo**）。 */
    private const val DOCK_KEY = "multiple_split_dock_task"

    // ───────────────────────── 判定参数 ─────────────────────────

    /** 只把 > 这个值的角度当作"展开态"样本（本机实测：全展≈179、半开 89~92、合上 2~3）。 */
    private const val OPEN_MIN = 150f

    /**
     * 两次触发之间的最小防抖间隔。
     *
     * 用户 2026-11-12 要求：「交换完成后折叠就要能马上再次交换」。
     * 原来 1500ms 的冷却会让"交换完立刻再折"被直接丢掉，手感像卡住。
     *
     * 只留 300ms 纯粹防抖 —— 真正防止"同一次折叠被判两次"的是状态机里的两道硬条件：
     *   · `a0 > OPEN_MIN`：必须从展开态（>150°）折下来；
     *   · `dropStartMs` / `START_DROP`：必须真的往下走了才算新的一次折叠。
     * 所以把冷却压到 300ms 不会引入重复触发。
     */
    private const val COOLDOWN_MS = 300L

    /**
     * 一次「有效折叠」的最短历时。
     *
     * <p>判据放在"松手"那一刻（进入等待摊平），不足这个时长就作废本次。
     *
     * <h2>为什么是 350ms（实机实测两头卡出来的）</h2>
     * | 场景 | 历时 | 回升幅度 |
     * |---|---|---|
     * | 误触发（旋转时蹭到） | **243ms** | 15° |
     * | 正常折叠 | **543ms** | 33° |
     *
     * 两者差一倍多，350ms 落在中间：既拦得住手滑，又不误伤正常使用。
     *
     * <p>⚠️ 曾试图用「加大折叠深度」来防误触（15°→30°），
     * 用户反馈「影响正常想分屏时折叠的使用」⇒ 已回退。
     * 深度决定手感、时间决定真伪，防线放在时间这一维。
     */
    private const val MIN_FOLD_DURATION_MS = 150L

    /** SoSc 分屏的两个 stage 字段名（`SoScStageCoordinator` 上）。 */
    private val SOSC_STAGE_FIELDS = arrayOf("mMainStage", "mSideStage")

    /**
     * 进入折叠判定的落差阈值（折下去多少度才认为用户"开始折了"）。
     * 由设置页滑动条给出，**范围 10°~90°，默认 15°**。直接读 [Cfg.foldSplitDepth]
     * （volatile Float，已在 App 侧把旧枚举迁移成数字），无锁、无阻塞。
     */
    private fun d1(): Float = Cfg.foldSplitDepth

    /**
     * 回弹：回到"基线 - D2"以内 ⇒ 认为折完松手了。
     *
     * <p>⚠️ 2026-11-20 用户要求「折叠后需要屏幕**完全展开**再触发分屏」：
     * 现在这条只用于**判断"还在折/已松手"**，**不再直接触发** ——
     * 真正触发必须等角度回到 [FULL_OPEN_MIN] 以上。见 (c)/(d) 分支。
     */
    private const val D2 = 6f

    /**
     * 「完全展开」的判定线：铰链角 ≥ 这个值才认为内屏真的摊平了。
     *
     * <p>用户 2026-11-20：「折叠后需要屏幕完全展开再触发分屏（可以给个两三度的容错）」。
     *
     * <p>⚠️ 2026-11-20 实测修正：本机摊平后传感器报的是 **178~179°**，
     * 但**重新折完往回掰时，手常常停在 176°** 就以为已经到底了
     * （日志实测：折完回升的最大值序列 = 179 / 176 / 171 / 170 / 168）。
     *
     * <p>原来按 3° 容错算成 177°，结果 176° 差 1 度判不到 ⇒
     * 用户看到的就是「折叠根本不分屏」。现在按 5° 容错 ⇒ **175°**，
     * 覆盖住 176° 这个真实停点，同时 170° 以下（还有明显弧度）依然不算展开。
     */
    private const val FULL_OPEN_TOLERANCE = 5f
    private const val FULL_OPEN_MIN = 180f - FULL_OPEN_TOLERANCE   // 175°

    /**
     * 「已经基本摊平」的快速触发线。
     *
     * <p>用户 2026-11-20：「稍微间隔一秒左右折叠交换分屏有延迟」。
     * 实测「松手 → 摊平到 175°」物理上要 522/560ms，再加上分屏动画，
     * 手感就是一秒。这段时间其实有相当一部分是**用户已经认为掰开了、
     * 但传感器还差最后几度**。
     *
     * <p>所以加一条：只要回升到 **170°**（离摊平只差 5°，肉眼已是"张开"），
     * 且**正在继续往回张开**，就直接触发 —— 不必死等 175°。
     * 170° 以下仍要求 175°，避免"折一半就分屏"。
     */
    private const val NEAR_OPEN_MIN = 170f

    /**
     * 角度稳定判定：两次样本角度差 < 这个值且持续 [SETTLE_MS] ⇒ 认为屏幕已停稳。
     *
     * <p>`hinge_angle` 是 **on-change** 传感器，手机静止时**完全没有回调** ——
     * 所以"稳定"必须靠**超时推断**：最后一次动作后 [SETTLE_MS] 内没有新样本，
     * 就认为是停稳了。这也正是恢复触摸的时机。
     */
    private const val SETTLE_EPS = 0.5f
    private const val SETTLE_MS = 260L

    /**
     * 「原地不动」判定：**超过 1 秒**角度都在 ±[STILL_RANGE] 以内 ⇒ 认为用户把屏幕停住了。
     *
     * <p>用户 2026-11-20 口径：「几乎摊平，几乎合上，或者**原地等待超过 1 秒**
     * 折叠角度都在 **±5 度内没变**再恢复」。
     *
     * <p>⚠️ 这替代了之前那个"260ms 角度一停就恢复"的判据 ——
     * 那个太激进：实测折叠时只要手上稍一顿（284ms 没新样本）窗口就被撤掉，
     * 而动作其实还在继续，于是"折叠中还能滑动"。
     * 现在要求**满 1 秒**并且**全程波动不超过 ±5°**，才是真的停手了。
     */
    private const val STILL_MS = 1_000L
    private const val STILL_RANGE = 5f

    /** 「几乎合上」的判定线：铰链角低于这个值就算合上了（本机合上实测 2~3°）。 */
    private const val NEAR_CLOSED_MAX = 10f

    /**
     * 半折叠驻留超时：屏幕停在中段超过这么久 ⇒ **本次折叠作废**，之后展开也不再触发。
     *
     * <p>用户 2026-11-20：「轻折交换和轻折分屏需要增加超时功能，
     * 避免长时间在半折叠屏状态，展开后还是触发分屏或者交换了」。
     *
     * <p>场景：用户折了一下、把手机当"帐篷"立在桌上用了几分钟，然后再掰平 ——
     * 这时**不该**因为"掰平了"就突然分屏。折叠意图在时间上早已失效。
     *
     * <p>取 2 秒：足够覆盖正常"折一下再摊平"（实测往返 1~2 秒），
     * 又能在用户明显是在"停着用"时及时作废。
     */
    private const val HALF_FOLD_TIMEOUT_MS = 2_000L

    /**
     * 每次请求触摸遮蔽的时长。
     *
     * <p>折叠/摊平的主要动作在 2 秒内结束，取 2.5 秒留余量。
     * 角度继续变化时会**再次请求**，到期时刻顺延（TouchBlocker.hold 取较晚者），
     * 所以动作持续多久就能挡多久；而一旦停手，最多 2.5 秒后自动放行。
     */
    private const val FOLD_TOUCH_HOLD_MS = 1_200L

    /** 合上保护：比基线低这么多度 ⇒ 认为这是要合上手机，不触发（≈ 半开位） */
    private const val D3 = 90f

    /**
     * 判定时限：从【开始折】起算，超过这么久还没摊平就放弃本次。
     *
     * <p>⚠️ 2026-11-20 从 1000ms 放宽到 4000ms —— 这是「完全展开才触发」带来的**必然调整**：
     * 原来的口径是「快速折一下马上松手」，1 秒足够；
     * 现在要求**把屏幕重新摊平**（≥177°）才分屏，摊平这个动作本身就要 1~2 秒，
     * 加上手上动作慢一点，1 秒必然超时。
     * 日志实证：用户折叠时记录到的历时是 1784 / 2203 / 2260 / 5404ms，
     * 全部超过旧的 1000ms ⇒ 每次都被判超时 ⇒ 表现为「折叠根本不分屏」。
     *
     * <p>⚠️ 二次修正（2026-11-20 实机）：放宽到 4 秒**仍然不够** ——
     * 实测「折下去 → 再摊平」的耗时是 4040 / 4388ms，刚好卡在边界上，又被判超时。
     * 所以再放到 **8 秒**：这是"人把手机掰开摊平"的合理上限，
     * 再慢就不是这个动作了。误触防护不靠这个时限，靠 `suppressed` 闩住 + 必须从
     * `A0 > OPEN_MIN` 折下去才进入判定。
     */
    private const val WIN_MS = 8_000L

    /** 自基线下降超过这么多 ⇒ 记本次动作的"开局"（< D1，才能把"折下去"那段圈进窗口） */
    private const val START_DROP = 5f

    /** 展开基线 A0 的滑动窗口宽度（取窗口内**最大**角度当基线） */
    private const val A0_WINDOW_MS = 8_000L

    /** 样本入窗口的最小角度变化（滤噪） */
    private const val EPS = 0.35f

    private const val MIN_INIT = 180f

    /** 方向与 `splitPrimaryTask` 的 pos：pos=0 ⇒ 当前应用留在**左**侧。 */
    private fun posFor(side: String): Int = if (side == Constants.FOLD_SPLIT_RIGHT) 1 else 0

    // ───────────────────────── 捕获到的运行期实例 ─────────────────────────

    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var taskOrganizer: Any? = null
    @Volatile private var controller: Any? = null
    @Volatile private var msplitOrganizer: Any? = null
    @Volatile private var sensorReady = false

    /** 上一次投递还没飞完 ⇒ 丢掉这一折（不是闩锁，丢掉后的下一折照样能用）。 */
    @Volatile private var appending = false

    // ───────────────────────── 判定状态（整份进出，不散落成多个 var） ─────────────────────────

    private data class FoldState(
        val folding: Boolean = false,
        val suppressed: Boolean = false,
        val foldStartMs: Long = 0L,
        val dropStartMs: Long = -1L,
        val minDuringFold: Float = MIN_INIT,
        val window: List<Pair<Long, Float>> = emptyList(),
        val lastSample: Float = Float.NaN,
        /** 最近一次收到角度样本的时刻（uptimeMillis），用于推断"是否已停稳"。 */
        val lastEventMs: Long = 0L,
        /** 最后一次触发触摸屏蔽的时刻；0 = 当前没在屏蔽。 */
        /**
         * 「静止窗口」：本轮开始静止的时刻，以及窗口内的角度极值。
         *
         * <p>用来实现用户 2026-11-20 的恢复触摸条件：
         * **原地等待超过 1 秒，且这 1 秒内角度波动不超过 ±5°** ⇒ 用户把屏幕停住了，放手。
         *
         * <p>只要角度超出 ±5° 范围，就把窗口起点重置到当前时刻重新计时 ——
         * 这样"慢慢连续掰动"不会被误判成"停住"。
         */
        /** 本次折叠是否已经触发过分屏（保证"一次折叠只触发一次"）。 */
        val fired: Boolean = false,
        /**
         * 折叠动作已经完成（用户松手了），正在等屏幕摊平。
         *
         * <p>为什么需要这个独立状态：用户 2026-11-20 要求「完全展开才触发」，
         * 而「折下去 → 松手 → 再摊平」是一个**跨多个传感器样本**的过程。
         * 松手那一刻角度往往只有 150~170°，还没到摊平线；
         * 如果此时把 `folding` 直接置 false 退回"未进入"分支，
         * 接下来的摊平就**再也没有机会被检查** ——
         * 实机日志正是这样：`已松手…等待完全展开` 之后收到 `角度=176°`，
         * 但因为没有"等待摊平"这个状态，176° 被当成新一折的开头，永远触发不了。
         */
        val awaitingOpen: Boolean = false,
    )

    private enum class Outcome { NOTHING, REARM, ENTER, TRIGGER, CANCEL_CLOSE, CANCEL_TIMEOUT }

    @Volatile private var state = FoldState()
    @Volatile private var lastTriggerMs = 0L

    /**
     * 稳定检查用的常驻 Handler。
     *
     * <p>⚠️ 必须独立于传感器回调：`hinge_angle` 是 on-change 传感器，
     * 屏幕停稳之后**再也不会来新样本** —— 只靠 onAngle 里的时间差判断，
     * "恢复触摸"这一步永远等不到，用户会一直点不动。
     */
    // ───────────────────────── 安装 ─────────────────────────

    fun install(m: MainHook, cl: ClassLoader): Int {
        classLoader = cl
        // ★ 这里**不看开关**：要支持热开关，安装只负责"接电"，真正的闸门在 [fire] 里读 Cfg。
        //   （`hinge_angle` 是 on-change 传感器，静止时零回调 ⇒ 常驻监听也不费电）
        var ok = 0
        val orgCls = runCatching { Class.forName(ORG_CLASS, false, cl) }.getOrNull()
        if (orgCls == null) {
            Logx.once("fs-nocls", "轻折加分屏: 找不到 $ORG_CLASS ⇒ 拿不到 WMShell 实例")
        } else {
            for (name in ORG_HOOK_NAMES) {
                for (method in orgCls.declaredMethods) {
                    if (method.name != name) continue
                    if (m.hookExecutable(method, captureHooker)) ok++
                }
            }
            Logx.always("轻折加分屏: ShellTaskOrganizer 抓取钩子 $ok 个")
        }
        // ★ 这两行是排查"折了没反应"的入口眼：挂载情况 + 方向取值。
        //   ⚠ 这里**不读开关**：进程刚起来时 AppCtx 可能还是 package=android 的早期 Context，
        //     走 provider 会被 `SecurityException: calling package android does not match caller's uid`
        //     拒掉（AppCtx.kt 记过的老坑）。开关真值在 [fire] 里同步读 —— 那时一定拿得到。
        Logx.always("轻折加分屏: 已接电 —— 方向=${Cfg.foldSplitSide}（pos=${posFor(Cfg.foldSplitSide)}）；" +
                "开关真值见触发时日志（热开关）")
        scheduleSensor()
        return ok
    }

    private val ORG_HOOK_NAMES = setOf(
        "onTaskInfoChanged", "onTaskAppeared", "onTaskVanished",
        "updateTaskListenerIfNeeded", "addMiuiShellTaskListener",
    )

    /**
     * 只做一件事：把 `this` / 参数里的 WMShell 实例**存下来**，然后原样放行。
     * ⛔ 不做任何判定：这些方法是 WMShell 的热路径。
     */
    private val captureHooker = XposedInterface.Hooker { chain ->
        runCatching {
            val self = chain.thisObject
            if (self != null && self.javaClass.name == ORG_CLASS && taskOrganizer == null) {
                taskOrganizer = self
                Logx.once("fs-org", "轻折加分屏: 已捕获 $ORG_CLASS")
            }
            // 白捡一条：`addMiuiShellTaskListener(t)` 的参数**就是**双分屏控制器
            if (controller == null) for (a in chain.args) take(a)
        }
        chain.proceed()
    }

    private fun take(o: Any?) {
        val n = o?.javaClass?.name ?: return
        if (n == CTRL_CLASS) {
            controller = o
            Logx.once("fs-ctrl", "轻折加分屏: 已捕获 ${o.javaClass.simpleName}")
        } else if (n == MSPLIT_ORG) {
            msplitOrganizer = o
            Logx.once("fs-msplit", "轻折加分屏: 已捕获 ${o.javaClass.simpleName}")
        }
    }

    // ───────────────────────── 传感器 ─────────────────────────

    /** SystemUI 刚起来时 AppCtx 还拿不到 ⇒ 主线程轮询若干次再放弃。 */
    private fun scheduleSensor() {
        val main = Handler(Looper.getMainLooper())
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                if (sensorReady) return
                val ctx = AppCtx.get()
                if (ctx != null && installHinge(ctx)) {
                    sensorReady = true
                    return
                }
                if (++tries < 15) main.postDelayed(this, 2_000L)
                else Logx.once("fs-sensor-fail", "轻折加分屏: 注册折角传感器失败（拿不到 Context / SensorManager）")
            }
        }
        main.postDelayed(task, 1_500L)
    }

    private fun installHinge(ctx: Context): Boolean {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = hingeSensor(sm) ?: run {
            Logx.once("fs-nosensor", "轻折加分屏: 本机没有 hinge_angle 传感器 ⇒ 功能不可用")
            return true // 不是"注册失败"，别再重试
        }
        val thread = HandlerThread("os4-hinge").apply { start() }
        val ok = runCatching {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))
        }.getOrDefault(false)
        Logx.always("轻折加分屏: 折角传感器=${sensor.name}(type=${sensor.type}) ${if (ok) "已注册" else "注册被拒"}")
        return ok
    }

    @SuppressLint("NewApi")
    private fun hingeSensor(sm: SensorManager): Sensor? =
        runCatching { sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) }.getOrNull()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val a = e.values.firstOrNull() ?: return
            // ★ 把折角同步给旋屏状态机：它据此判断"是否真的合到外屏"。
            //   折叠**过程中** current_device_state 会提前变 2，只有折角能说明
            //   屏幕其实还没合上（用户 2026-11-21 要求此时不要触发旋转锁定）。
            InnerRotationHost.setHingeAngle(a)
            // ⛔ 任何异常都不许冒泡到 SystemUI 的传感器线程
            runCatching { onAngle(a, SystemClock.elapsedRealtime()) }
        }

        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
    }

    // ───────────────────────── 判定 ─────────────────────────

    private fun onAngle(angle: Float, nowMs: Long) {
        val s0 = state

        // ⓪ 基线窗口推进一步（**必须在所有分支之前**：闩住期间也要持续收样本，
        //    否则解开闩锁后 A0 无处可建）
        var win = s0.window
        var last = s0.lastSample
        if (angle > OPEN_MIN && (last.isNaN() || kotlin.math.abs(angle - last) >= EPS)) {
            win = win + (nowMs to angle)
            last = angle
        }
        win = win.dropWhile { nowMs - it.first > A0_WINDOW_MS }
        val a0 = win.maxOfOrNull { it.second } ?: angle

        // ⓪.5 跟踪"本次往下折"的开局时刻（只记第一次；回到基线附近即复位）
        val dropStart = if (a0 - angle > START_DROP) {
            if (s0.dropStartMs < 0L) nowMs else s0.dropStartMs
        } else if (s0.dropStartMs >= 0L) -1L else s0.dropStartMs

        // ⓪.6 只要确认掉进"合上"区间，旧的展开基线**一律作废**（与状态机走到哪无关）
        var s = s0.copy(window = win, lastSample = last, dropStartMs = dropStart,
                        lastEventMs = nowMs)
        if (a0 - angle >= D3 && win.isNotEmpty()) s = s.copy(window = emptyList(), lastSample = Float.NaN)

        // ① 闩住中：只等手机**真的回到展开位**（用绝对角度，不用 A0 —— A0 只会跌，
        //    拿一个只会跌的参照去判"回到高处"早晚会误判）
        if (s0.suppressed) {
            if (angle > OPEN_MIN) {
                state = s.copy(suppressed = false)
                Logx.v("轻折加分屏: 已回到展开态 ${angle.f1()}° ⇒ 重新武装")
            } else {
                state = s
            }
            return
        }

        // ②′ ★ 等待摊平：用户已经折完松手，正在把屏幕掰开
        //
        //   这是「完全展开才触发」的**必经状态**（用户 2026-11-20 要求）。
        //   松手时角度通常只有 150~170°，还没到摊平线 175°；
        //   必须留在这个状态里继续等后续样本把角度顶上来，否则永远触发不了。
        if (s0.awaitingOpen && !s0.folding) {
            val st2 = s.copy(lastEventMs = nowMs)
            when {
                // 快速通道：已基本摊平且还在往回张开 ⇒ 立即触发（省掉最后几度的等待）
                angle >= NEAR_OPEN_MIN && angle > s0.minDuringFold + d1() -> {
                    if (s0.fired) {
                        state = st2.copy(awaitingOpen = false, fired = false)
                    } else {
                        Logx.always("轻折加分屏: 已基本摊平 ${angle.f1()}°（≥${NEAR_OPEN_MIN.f1()}且持续张开）⇒ 触发")
                        lastTriggerMs = nowMs
                        state = st2.copy(awaitingOpen = false, fired = true)
                        fire()
                    }
                }
                // 完全摊平 ⇒ 触发（唯一触发点在这里）
                angle >= FULL_OPEN_MIN -> {
                    if (s0.fired) {
                        Logx.v("轻折加分屏: 已完全展开 ${angle.f1()}°，本次已触发过 ⇒ 不重复")
                        state = st2.copy(awaitingOpen = false, fired = false)
                    } else {
                        Logx.always("轻折加分屏: 完全展开 ${angle.f1()}°（≥${FULL_OPEN_MIN.f1()}）⇒ 触发")
                        lastTriggerMs = nowMs
                        state = st2.copy(awaitingOpen = false, fired = true)
                        fire()
                    }
                }
                // ★ 半折叠驻留超时：停在中段太久 ⇒ 本次折叠作废，且之后展开也不触发
                //
                //   用 suppressed=true 而不是单纯清 awaitingOpen：
                //   要让"用户后来摊平"这个动作也**被明确挡掉**，否则摊平那一下照样能进触发分支。
                //   而 suppressed 只在角度回到 >OPEN_MIN(150°) 时才会解除，
                //   解除那一刻本轮已经结束，不会回头再补一次触发。
                nowMs - s0.foldStartMs > HALF_FOLD_TIMEOUT_MS -> {
                    Logx.always("轻折加分屏: 半折叠驻留 ${nowMs - s0.foldStartMs}ms > ${HALF_FOLD_TIMEOUT_MS}ms ⇒ 本次作废，展开不再触发")
                    state = st2.copy(awaitingOpen = false, fired = false, suppressed = true)
                }
                // 兜底超时（更长的上限）
                nowMs - s0.foldStartMs > WIN_MS -> {
                    Logx.always("轻折加分屏: 等待摊平超时（${nowMs - s0.foldStartMs}ms > ${WIN_MS}ms）⇒ 放弃")
                    state = st2.copy(awaitingOpen = false, fired = false, suppressed = true)
                }
                // 又折下去了（用户反复折）：继续等，但重置起点，避免被旧起点误判超时
                a0 - angle > d1() -> {
                    Logx.v("轻折加分屏: 等待摊平期间又折下去 ${angle.f1()}° ⇒ 继续等待")
                    state = st2.copy(foldStartMs = nowMs)
                }
                else -> state = st2
            }
            return
        }

        // ② 未进入：先过冷却，再看落差够不够
        if (!s0.folding) {
            // ★ 2026-11-20：只要从展开态开始往下掉（>START_DROP=5°），立刻请求触摸遮蔽。
            //
            //   用户要求「哪怕在触摸时折叠，也能屏蔽触摸事件」。折叠判定要掉 15° 才算，
            //   而那时用户的手指早已在屏幕上滑动 —— 所以这里抢在 5° 就请求遮蔽，
            //   把生效时刻提前到物理上最早的可能点。
            //
            //   ⚠️ 遮蔽是"到期自动放行"的（TouchBlocker 里只维护一个到期时刻），
            //      不依赖任何撤销调用 ⇒ 最坏情况是多挡一会儿，绝不会像覆盖窗口那样锁死屏幕。
            if (a0 > OPEN_MIN && a0 - angle > START_DROP) {
                // 用 InputMonitor + pilferPointers 把触摸从分屏应用手里抢走。
                // ⚠️ 不能再用覆盖窗口：那会导致整机（含锁屏）失去触摸。
                FoldTouchBlock.holdFor(FOLD_TOUCH_HOLD_MS)
            }
            if (nowMs - lastTriggerMs < COOLDOWN_MS) {
                state = s
                return
            }
            if (a0 > OPEN_MIN && a0 - angle > d1()) {
                state = s.copy(
                    folding = true,
                    foldStartMs = if (s.dropStartMs >= 0L) s.dropStartMs else nowMs,
                    minDuringFold = angle,
                    // ⚠️ 每次进入折叠都必须清掉「已触发」标记。
                    //    否则上一折留下的 fired=true 会把这一折挡掉 ——
                    //    实机日志正是这样：17:48:27.419 松手后走了快速通道，
                    //    角度 171° 已达标，却因为 fired 还是上一折的 true 而静默跳过。
                    fired = false,
                )
                Logx.v("轻折加分屏: 进入折叠判定 angle=${angle.f1()} A0=${a0.f1()} 差=${(a0 - angle).f1()}°")
                // 用户 2026-11-20：折叠过程中屏蔽触摸，避免手蹭到屏幕误触。
            } else {
                state = s
            }
            return
        }

        // ③ 折叠中
        val minFold = minOf(s0.minDuringFold, angle)
        s = s.copy(minDuringFold = minFold)
        state = s

        when {
            // (a⁻) ★ 半折叠驻留超时：折到一半就停住了（用户把手机架着用）
            //
            //      用户 2026-11-20：「避免长时间在半折叠屏状态，展开后还是触发分屏或者交换」。
            //      这里必须与"等待摊平"分支用同一个时限，否则"折下去就不动了"
            //      这条路会绕过超时 —— 因为它在 folding 分支里，根本走不到 awaitingOpen。
            nowMs - s0.foldStartMs > HALF_FOLD_TIMEOUT_MS -> {
                Logx.always("轻折加分屏: 折叠中驻留 ${nowMs - s0.foldStartMs}ms > ${HALF_FOLD_TIMEOUT_MS}ms ⇒ 本次作废")
                state = s.copy(folding = false, awaitingOpen = false, fired = false, suppressed = true)
            }

            // (a) 合上：取消 + 闩住 + **清空基线窗口**（物理上：合上之前那个 179° 已不代表当前基线）
            a0 - angle >= D3 -> {
                state = s.copy(folding = false, suppressed = true, window = emptyList(),
                        lastSample = Float.NaN, dropStartMs = -1L, fired = false)
                Logx.always("轻折加分屏: 判为要合上（最低 ${minFold.f1()}°）⇒ 不触发，闩住")
                // 合上 ⇒ 屏幕不再变化，此时角度稳定，触摸本就该恢复
            }

            // (c) ★ 完全展开：**唯一触发点**（用户 2026-11-20 要求）
            //
            //   原来是「回弹到基线 - 6°」就触发 —— 那意味着屏幕才张开到 170° 出头就分屏了，
            //   用户看到的是一次「折叠中途突然分屏」。现在必须**摊平**（≥177°，3° 容错）才触发。
            //
            //   ⚠️ 这条必须排在 (d) 之前：摊平时两个条件可能同时成立，
            //   优先按「完全展开」这条走，日志与语义才一致。
            angle >= FULL_OPEN_MIN -> {
                state = s.copy(folding = false, suppressed = false, dropStartMs = -1L)
                if (s0.fired) {
                    Logx.v("轻折加分屏: 已完全展开 ${angle.f1()}°，本次已触发过 ⇒ 不重复")
                } else {
                    lastTriggerMs = nowMs
                    Logx.always("轻折加分屏: 完全展开 ${angle.f1()}°（≥${FULL_OPEN_MIN.f1()}），历时 ${nowMs - s0.foldStartMs}ms 最低 ${minFold.f1()}° ⇒ 触发")
                    state = state.copy(fired = true)
                    fire()
                }
                // 屏幕已经摊平且稳定在展开态：立刻恢复触摸，用户马上就能操作新分屏
            }

            // (b) 超时：见 (c) 之后的说明
            nowMs - s0.foldStartMs > WIN_MS -> {
                state = s.copy(folding = false, suppressed = true, fired = false)
                Logx.always("轻折加分屏: 超时未回弹（${nowMs - s0.foldStartMs}ms > ${WIN_MS}ms）⇒ 不触发，闩住")
                // 超时 ⇒ 用户只是把屏幕固定在某个角度在用，必须立刻恢复触摸，
                // 否则「把屏幕停在半开位」这种正常用法会变成点不动
            }

            // (d) 自谷底明显回升 ⇒ 只是「松手了」，**不再触发**
            //
            //   用户 2026-11-20 要求必须完全展开才分屏，所以这里只把状态推回
            //   "等待摊平"，真正的触发在上面的 (c)。此时**继续保持触摸屏蔽** ——
            //   手还在往回掰的过程中，此刻放开触摸最容易被手背/拇指蹭到。
            angle - minFold >= d1() -> {
                val tookMs = nowMs - s0.foldStartMs
                if (tookMs < MIN_FOLD_DURATION_MS) {
                    // ★ 太快了 ⇒ 是手滑蹭到，不是有意折叠。直接作废，不进入等待摊平。
                    Logx.always("轻折加分屏: 折叠太快 ${tookMs}ms < ${MIN_FOLD_DURATION_MS}ms ⇒ 判为误触，作废" +
                            "（最低 ${minFold.f1()}° / A0=${a0.f1()}° / 深 ${(a0 - minFold).f1()}°）")
                    state = s.copy(folding = false, awaitingOpen = false, fired = false, suppressed = true)
                } else {
                    Logx.always("轻折加分屏: 已松手（自谷底回升 ${(angle - minFold).f1()}°，历时 ${tookMs}ms）⇒ 等待完全展开")
                    // ⚠️ 不能直接 folding=false 退回"未进入"分支，否则后续摊平无人检查。
                    //    改由 awaitingOpen 接管，见下面的 ②′ 分支。
                    state = s.copy(folding = false, awaitingOpen = true)
                }
            }

            else -> Unit // 还在往下折，继续观望
        }
    }

    // ───────────────────────── 折叠期间屏蔽触摸 ─────────────────────────

    /**
     * 折叠过程中的触摸拦截窗口。
     *
     * <p>用户 2026-11-20：「折叠过程中屏蔽触摸避免误触，直到屏幕角度不变的时候恢复触摸
     * （要注意不要影响评估固定在某个角度的时候的触摸）」。
     *
     * <h2>为什么必须真的"屏蔽"，不能只做手势过滤</h2>
     * 折叠时手指容易蹭到屏幕边缘，若只是"忽略这次点击"，
     * 事件仍会落到下面的 App 上（比如列表滚动、按钮按下），
     * 用户看到的就是「折了一下，页面莫名其妙动了」。所以需要一个
     * **真正吃掉事件的窗口**。
     *
     * <h2>为什么不会影响"固定在某个角度"的触摸</h2>
     * 只在**角度正在变化**时挂窗口；`SETTLE_MS`（260ms）内没有新样本就立刻撤掉。
     * 而 `hinge_angle` 是 on-change 传感器 —— 角度不变时**一个回调都没有**，
     * 所以「固定在某个角度」的情况下这个窗口根本不会被创建。
     *
     * <h2>窗口类型为什么用 2024</h2>
     * 先试过 `TYPE_ACCESSIBILITY_OVERLAY`(2032) —— **加不上**，报
     * `BadTokenException: token null is not valid`：那个类型要求由
     * 无障碍服务的 Context 创建，普通应用/SystemUI 的 Context 拿不到它的 token。
     *
     * 改用它 `TYPE_NAVIGATION_BAR_PANEL`(2024)：SystemUI 进程对这个类型有权限
     * （同一进程里 `RotationButtonOverlay` 的悬浮窗用的就是它，实测能正常 addView）。
     *
     * <p>FLAG 里**不能**加 `NOT_TOUCHABLE`，否则事件会穿过去，等于没屏蔽。
     */
    private var blockView: View? = null
    private var blockWm: android.view.WindowManager? = null

    /**
     * 窗口是否已挂上（**独立于 state**）。
     *
     * <p>⚠️ 不能只靠 `state.touchBlockedAtMs` 去重：`onAngle` 每次都会用一份
     * 早先读出的快照 `s.copy(...)` 回写整个 state，会把刚写进去的 touchBlockedAtMs
     * 冲掉 —— 实机日志实测到同一毫秒连挂 4 个窗口（18:24:41.711~750）。
     * 用 AtomicBoolean 做唯一真相，state 里的字段只作辅助记录。
     */
    private val blockAttached = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 挂上触摸拦截。重复调用安全（已在拦就不重复加）。 */
    /**
     * ⛔ 2026-11-20 已废弃。
     *
     * <p>原实现用 `TYPE_SYSTEM_ERROR`(2010) 全屏覆盖窗口吃触摸。
     * 实机事故：「分屏后屏幕就无法被触摸了，锁屏都无法解除」——
     * 2010 排在包括锁屏在内的所有窗口之上，释放逻辑一旦漏一步就永久锁死屏幕。
     *
     * <p>**已改为 [TouchBlocker]（输入分发层丢弃，不创建任何窗口）。**
     */
    @Suppress("UNUSED_PARAMETER")
    private fun blockTouch(nowMs: Long) {
        // 见上方说明：屏蔽已改由 TouchBlocker 在输入分发层完成，这里不再做任何事。
    }

    /** 取"当前点亮那块屏"的 Context（折叠屏内外屏切换时必须用这个）。 */
    private fun activeDisplayContext(ctx: Context): Context {
        return runCatching {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager
            dm?.displays?.firstOrNull { it.state == android.view.Display.STATE_ON }
                ?.let { ctx.createDisplayContext(it) } ?: ctx
        }.getOrDefault(ctx)
    }

    /** 撤掉触摸拦截。幂等。 */
    /**
     * ⛔ 2026-11-20 已废弃。
     *
     * <p>原实现用 `TYPE_SYSTEM_ERROR`(2010) 全屏覆盖窗口吃触摸。
     * 实机事故：「分屏后屏幕就无法被触摸了，锁屏都无法解除」——
     * 2010 排在包括锁屏在内的所有窗口之上，释放逻辑一旦漏一步就永久锁死屏幕。
     *
     * <p>**已改为 [TouchBlocker]（输入分发层丢弃，不创建任何窗口）。**
     */
    @Suppress("UNUSED_PARAMETER")
    private fun Float.f1(): String = "%.1f".format(this)

    // ───────────────────────── 触发 ─────────────────────────

    private fun fire() {
        // ★ 2026-11-19：**不再同步 refreshSync()**。
        //   原来这里每次折都发一条跨进程 IPC 去读配置。虽然折角传感器回调不持系统锁，
        //   但 IPC 一旦被 provider 端阻塞，就会占住 SensorManager 回调线程并推迟触发。
        //   现在投递到后台线程（1.5s 节流）：本轮直接用内存里的配置值，
        //   改开关最多滞后一拍，但绝不阻塞本折。
        Cfg.reloadThrottled()
        if (!Cfg.foldSplit) {
            Logx.always("轻折加分屏: 开关为关 ⇒ 不执行（热开关，改完无需重启 SystemUI）")
            return
            FoldTouchBlock.release("本次不执行：开关为关")
        }
        val clNow = classLoader
        if (clNow != null && SoScSupport.inMinimizedMode(clNow)) {
            // ★ v0.7.5 的铁律：单侧分屏（SplitState=-1）是雷区，官方状态机会被搅坏
            Logx.always("轻折加分屏: 当前是单侧（minimized）分屏 ⇒ 不介入")
            return
            FoldTouchBlock.release("本次不执行：单侧分屏")
        }
        val ctrl = controllerOrNull()
        if (ctrl == null) {
            Logx.always("轻折加分屏: 还没有双分屏控制器 ⇒ 放弃本次")
            return
            FoldTouchBlock.release("本次不执行：无控制器")
        }
        val h = wmShellMainHandler(ctrl)
        if (h == null) {
            Logx.always("轻折加分屏: 拿不到 WMShell 主 Handler ⇒ 放弃本次")
            return
            FoldTouchBlock.release("本次不执行：无Handler")
        }
        // ★ 并发闸：上一次还没飞完就丢弃这次（丢而不是排队 —— 排队会"折得越快、排得越多"）
        if (appending) {
            Logx.always("轻折加分屏: 上一次还在进行 ⇒ 丢掉这一折")
            return
            FoldTouchBlock.release("本次不执行：并发丢弃")
        }
        appending = true
        // ★ 2026-11-21：分屏已经确定要执行 ⇒ **立刻放行触摸**。
        //
        //   用户的反馈：「单侧分屏之后，点击桌面图标要等一会儿才生效」。
        //   根因就是这里：屏蔽原本要等 `FOLD_TOUCH_HOLD_MS` 到期才放行，
        //   而折叠判定成功时用户往往已经松手，白等 2.5 秒。
        //   屏蔽的唯一目的是防止折叠过程中手蹭到屏幕；分屏一旦开始执行，
        //   这个目的已经达成，继续拦只会让用户觉得"点了没反应"。
        FoldTouchBlock.release("分屏已触发")
        val posted = h.post {
            try {
                runCatching { doSplit(ctrl) }.onFailure { Logx.e("轻折加分屏: doSplit 异常（已吞）", it) }
            } finally {
                appending = false
            }
        }
        if (!posted) {
            // ⛔ Handler.post 返回 false ⇒ Runnable 永远不会跑 ⇒ finally 不会到 ⇒ 必须当场放闸
            appending = false
            Logx.e("轻折加分屏: 投递失败（Looper 已退出？）⇒ 释放并发闸", null)
        }
    }

    /**
     * 按当前形态分流：
     * - 全屏（stage < 2）⇒ `splitPrimaryTask(pos, taskId)`（三指滑那条）
     * - 已在多分屏（≥2）⇒ `dockMultipleSplitTasks(Bundle)`（拖到多分屏图标那条，追加一格）
     *
     * ★ 分流的理由不是我们发明的：`splitPrimaryTask` 第一行就是 `if (isSplitScreenVisible()) return false;`
     *   ⇒ 它自己对已有分屏一律拒绝，多分屏必须换一条链。
     */
    private fun doSplit(ctrl: Any) {
        val pos = posFor(Cfg.foldSplitSide)
        val n = stageCount()
        // 两种模式共存（2026-11 新增）：默认仍是「每折加一格」，可选「进双分屏后每次折都交换」。
        if (Cfg.foldSplitMode == Constants.FOLD_SPLIT_MODE_SWAP) {
            if (n >= 3) {
                // 交换模式只接管「单屏 → 双屏」和「双屏互换」；三分屏及以上仍按原逻辑继续加格。
                Logx.always("轻折加分屏[交换]: stage=$n ⇒ 超过双屏，追加一格")
                append(ctrl)
                return
            }
            if (n == 2) {
                Logx.always("轻折加分屏[交换]: stage=2 ⇒ 交换左右两格")
                swap()
                return
            }
            Logx.always("轻折加分屏[交换]: stage=$n ⇒ splitPrimaryTask(pos=$pos)（进左右双分屏）")
            // 「左右双分屏」是这一模式的既定语义：进分屏前把布局方向对齐到左右
            // （竖屏握持时系统默认会是上下，不对齐就变成上下分屏了）。
            classLoader?.let { cl ->
                val aligned = SoScSupport.ensureDirection(cl, true)
                Logx.always("轻折加分屏[交换]: 进入前对齐左右方向 成功=$aligned")
            }
        } else {
            Logx.always("轻折加分屏: stage=$n ⇒ ${if (n >= 2) "追加一格" else "splitPrimaryTask(pos=$pos)"}")
            if (n >= 2) {
                append(ctrl)
                return
            }
        }
        val taskId = focusedTaskId()
        if (taskId <= 0) {
            Logx.always("轻折加分屏: 拿不到聚焦任务 ⇒ 安静放弃")
            return
        }
        val r = runCatching {
            SoScSupport.call(ctrl, "splitPrimaryTask", pos, taskId) as? Boolean
        }.onFailure { Logx.e("轻折加分屏: splitPrimaryTask 异常（已吞）", it) }.getOrNull()
        Logx.always("轻折加分屏: splitPrimaryTask(pos=$pos, taskId=$taskId) ⇒ $r")
    }

    /**
     * 交换两格位置（「交换」模式、且当前正好是双分屏时调用）。
     *
     * 走**官方交换按钮**那条路：`SoScUtilsImpl.switchSplitPosition()`（无参）。
     * 反汇编 `MiuiSplitMenuView.lambda$onFinishInflate$1` 确认，分屏中间小白条上的交换按钮
     * 点击后就是调它；它内部再调 `SoScStageCoordinator.switchSplitPosition(String)`
     * （传 `"Click Menu"`），由那个重载完成整套 surface 交换动画 + 事务提交。
     *
     * 之前用的是 `SoScSplitScreenController.swapSplitTasks(WindowContainerTransaction)`，
     * 那条路走的是另一个重载 `switchSplitPosition(WindowContainerTransaction)`，
     * 交换能生效但没有动画 —— 与官方按钮不是同一条实现。
     */
    private fun swap() {
        val cl = classLoader
        if (cl == null) {
            Logx.always("轻折加分屏[交换]: 拿不到 ClassLoader ⇒ 放弃交换")
            return
        }
        val utils = SoScSupport.soScUtils(cl)
        if (utils == null) {
            Logx.always("轻折加分屏[交换]: 拿不到 SoScUtilsImpl 单例 ⇒ 放弃交换")
            return
        }

        // ★★ 2026-11-11 定稿：单边分屏（另一格是桌面 / 空）**屏蔽交换**。
        //
        // 原理（真机实测 + 反汇编确认）：这种形态下桌面根本不在分屏 stage 里，
        // 只是被裁在底层的普通全屏窗口（mSideStage=null，桌面 task visible=false）。
        // 桌面不支持作为分屏的一格（活动类型 home，系统不允许进 SoSc stage），所以：
        //   · 调系统 switchSplitPosition → 只换两个 stage，空的那格没东西可换，
        //     应用换边但桌面图标不重排，图标被应用窗口遮住（用户报的问题）；
        //   · 强行把 home 塞进 stage → 系统拒/状态错乱（实测分屏消失或窗口错乱）。
        // 结论：这个形态下**没有正确的交换语义**，直接不做任何事，
        //       既不会把图标遮住，也不会把分屏弄坏。
        val stageCount = occupiedStageCount(cl)
        if (stageCount != null && stageCount < 2) {
            Logx.always(
                "轻折加分屏[交换]: 当前只有 $stageCount 格有任务（单边分屏 / 另一格是桌面）" +
                " ⇒ 桌面不支持分屏格，屏蔽交换（不动作）"
            )
            return
        }
        Logx.always("轻折加分屏[交换]: ${stageCount?.let { "双应用分屏（$it 格）" } ?: "格数未知"} ⇒ 交换")
        val r = runCatching { SoScSupport.call(utils, "switchSplitPosition") }
            .onFailure { Logx.e("轻折加分屏[交换]: switchSplitPosition 异常（已吞）", it) }
            .getOrNull()
        Logx.always("轻折加分屏[交换]: SoScUtils.switchSplitPosition() ⇒ $r")
    }

    /**
     * 当前分屏里有任务的 stage 数量；拿不到 coordinator 时返回 null（表示"未知"，不是"单边"）。
     *
     * 单边分屏时系统会留一个**没有子任务的 stage**（实测 `mSideStage=null` 或 childCount=0），
     * 那个位置露出来的是底层被裁的桌面 —— 它不在 stage 里，所以不能参与交换。
     */
    private fun occupiedStageCount(cl: ClassLoader): Int? {
        val coord = SoScSupport.stageCoordinator(cl) ?: return null
        var n = 0
        for (name in SOSC_STAGE_FIELDS) {
            val stage = SoScSupport.field(coord, name) ?: continue
            val childCount = runCatching {
                SoScSupport.call(stage, "getChildCount") as? Int
            }.getOrNull() ?: 0
            val pkg = packageOfTaskInfo(SoScSupport.field(stage, "mRootTaskInfo"))
            Logx.v("轻折加分屏[交换]: $name pkg=$pkg childCount=$childCount")
            if (pkg != null || childCount > 0) n++
        }
        return n
    }

    private fun packageOfTaskInfo(info: Any?): String? {
        if (info == null) return null
        val act = SoScSupport.field(info, "topActivity") ?: SoScSupport.field(info, "baseActivity")
        return runCatching {
            act?.javaClass?.getMethod("getPackageName")?.invoke(act) as? String
        }.getOrNull()
    }

    /** 追加一格：把分屏 **root** 的 RunningTaskInfo 装进 Bundle —— 与官方 shell 命令逐字一致。 */
    private fun append(ctrl: Any) {
        val org = msplitOrganizerOrNull()
        val info = splitRootTaskInfoOrNull()
        if (org == null || info == null) {
            Logx.always("轻折加分屏: 拿不到多分屏组织者 / 分屏 root info ⇒ 放弃追加")
            return
        }
        val b = Bundle().apply { putParcelable(DOCK_KEY, info as? android.os.Parcelable) }
        val r = runCatching {
            SoScSupport.call(org, "dockMultipleSplitTasks", b) as? Boolean
        }.onFailure { Logx.e("轻折加分屏: dockMultipleSplitTasks 异常（已吞）", it) }.getOrNull()
        Logx.always("轻折加分屏: dockMultipleSplitTasks ⇒ $r（控制器=${ctrl.javaClass.simpleName}）")
    }

    // ───────────────────────── 反射取值（拿不到一律返回 null，绝不抛） ─────────────────────────

    /** 双分屏控制器。取到即缓存；取不到返回 null，不重试、不报错。 */
    private fun controllerOrNull(): Any? {
        controller?.let { return it }
        val to = taskOrganizer ?: return null
        val list = SoScSupport.field(to, "mSplitScreenListener") ?: return null
        val size = runCatching { list.javaClass.getMethod("size").invoke(list) as? Int }.getOrNull() ?: return null
        val get = runCatching { list.javaClass.getMethod("get", Int::class.java) }.getOrNull() ?: return null
        for (i in 0 until size) {
            val e = runCatching { get.invoke(list, i) }.getOrNull() ?: continue
            if (e.javaClass.name == CTRL_CLASS) {
                controller = e
                Logx.once("fs-ctrl2", "轻折加分屏: 从 mSplitScreenListener[$i] 取到 ${e.javaClass.simpleName}")
                return e
            }
        }
        return null
    }

    private fun msplitOrganizerOrNull(): Any? {
        msplitOrganizer?.let { return it }
        val to = taskOrganizer ?: return null
        val arr = SoScSupport.field(to, "mTaskListeners") ?: return null
        val size = runCatching { arr.javaClass.getMethod("size").invoke(arr) as? Int }.getOrNull() ?: return null
        val valueAt = runCatching { arr.javaClass.getMethod("valueAt", Int::class.java) }.getOrNull() ?: return null
        for (i in 0 until size) {
            val v = runCatching { valueAt.invoke(arr, i) }.getOrNull() ?: continue
            if (v.javaClass.name == MSPLIT_ORG) {
                msplitOrganizer = v
                Logx.once("fs-msplit2", "轻折加分屏: 取到 ${v.javaClass.simpleName}（mTaskListeners[$i]/$size）")
                return v
            }
        }
        return null
    }

    /** 当前多分屏格数；拿不到按 0 处理（会走 splitPrimaryTask，最坏"什么都没发生"）。 */
    private fun stageCount(): Int {
        val byOrg = runCatching {
            msplitOrganizerOrNull()?.let { SoScSupport.call(it, "getCurrentActiveStageCount") as? Int }
        }.getOrNull()
        if (byOrg != null && byOrg > 0) return byOrg
        val vis = controllerOrNull()?.let {
            runCatching { SoScSupport.call(it, "isSplitScreenVisible") as? Boolean }.getOrNull()
        }
        return if (vis == true) 2 else 0
    }

    /** 分屏 root 的 RunningTaskInfo：SoSc 全屏模式向 [SOSC_UTILS] 要，多分屏则从 organizer 字段读。 */
    private fun splitRootTaskInfoOrNull(): Any? {
        val cl0 = classLoader ?: return null
        runCatching {
            val cls = Class.forName(SOSC_UTILS, false, cl0)
            val inst = runCatching { SoScSupport.callStatic(cl0, SOSC_UTILS, "getInstance") }.getOrNull()
                ?: runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()
            if (inst != null && SoScSupport.call(inst, "inSoScFullMode") == true) {
                return SoScSupport.call(inst, "getSplitRootTaskInfo")
            }
        }
        return msplitOrganizerOrNull()?.let { SoScSupport.field(it, "mSplitRootTaskInfo") }
    }

    /**
     * 当前**聚焦且可见**的任务 id —— `splitPrimaryTask` 的 taskId 是外部算好传进来的，
     * 系统三指滑时由 system_server 给出，我们只能自己找。
     *
     * ★ 主路用 `ActivityTaskManager.getTasks`（与 `Gestures.kt#topTaskViaAtm` 同源，本机已验证）：
     *   `ShellTaskOrganizer.mTasks` 这条路在本机取不到值（真机实测 `拿不到聚焦任务`），
     *   但仍留作兜底 —— 换 ROM 时它可能才是唯一能用的那条。
     */
    private fun focusedTaskId(): Int {
        atmTaskId()?.let { if (it > 0) return it }
        val to = taskOrganizer ?: return -1
        val tasks = SoScSupport.field(to, "mTasks") ?: return -1
        val size = runCatching { tasks.javaClass.getMethod("size").invoke(tasks) as? Int }.getOrNull() ?: return -1
        val valueAt = runCatching { tasks.javaClass.getMethod("valueAt", Int::class.java) }.getOrNull() ?: return -1
        var fallback = -1
        for (i in 0 until size) {
            val holder = runCatching { valueAt.invoke(tasks, i) }.getOrNull() ?: continue
            val ti = runCatching { holder.javaClass.getMethod("getTaskInfo").invoke(holder) }.getOrNull() ?: continue
            val id = SoScSupport.intField(ti, "taskId") ?: continue
            val focused = SoScSupport.intField(ti, "isFocused") == 1
            val visible = SoScSupport.intField(ti, "isVisible") == 1
            if (visible && fallback < 0) fallback = id   // 个别时刻 focused 会短暂为 false
            if (focused && visible && id > 0) return id
        }
        return fallback
    }

    /**
     * 前台任务的 id：`ActivityTaskManager.getTasks` 返回真 `RunningTaskInfo`，
     * SystemUI（system uid）有调用权限；跳过桌面 / launcher。
     * @return null 表示这条路的签名都试过了也没取到（交给调用方兜底）。
     */
    private fun atmTaskId(): Int? = runCatching {
        val atmCls = Class.forName("android.app.ActivityTaskManager")
        val atm = atmCls.getMethod("getInstance").invoke(null) ?: return null
        // 候选签名（不同固件略有差异，逐条试）
        val tries = listOf(
            arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false, false),
            arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, 0, false),
            arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false),
        )
        for ((sig, args) in tries) {
            val m = runCatching { atmCls.getMethod("getTasks", *sig) }.getOrNull() ?: continue
            val tasks = runCatching { m.invoke(atm, *args) as? List<*> }.getOrNull() ?: continue
            for (e in tasks) {
                val t = e as? android.app.ActivityManager.RunningTaskInfo ?: continue
                val pkg = (t.topActivity ?: t.baseActivity)?.packageName ?: continue
                if (pkg == "com.miui.home" || pkg.endsWith(".launcher") || pkg == "android") continue
                Logx.v("轻折加分屏: 前台任务 $pkg taskId=${t.taskId}")
                return t.taskId
            }
        }
        null
    }.getOrNull()

    /** WMShell 主线程：controller 内部有记账状态，必须在它自己的线程上调用，否则会留下脏状态。 */
    private fun wmShellMainHandler(ctrl: Any): Handler? {
        val candidates = listOf(
            { SoScSupport.field(SoScSupport.field(ctrl, "mTransitions")?.let { SoScSupport.field(it, "mMainExecutor") }, "mHandler") },
            { SoScSupport.field(ctrl, "mMainHandler") },
            { SoScSupport.field(SoScSupport.field(ctrl, "mMainExecutor"), "mHandler") },
        )
        for (get in candidates) {
            val h = runCatching { get() }.getOrNull() as? Handler
            if (h != null) return h
        }
        return null
    }
}
