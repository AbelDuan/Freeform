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

    /** 两次触发之间的最小间隔 */
    private const val COOLDOWN_MS = 1_500L

    /**
     * 进入折叠判定的落差阈值（折下去多少度才认为用户"开始折了"）。
     * **默认 20° = 原有手感**；可在设置页改灵敏度：灵敏 10° / 标准 20° / 迟钝 35°。
     * 在传感器线程上读 [Cfg.foldSplitDepth]（volatile 字符串）+ when，无锁、无阻塞。
     */
    private fun d1(): Float = when (Cfg.foldSplitDepth) {
        Constants.FOLD_SPLIT_DEPTH_SENSITIVE -> 10f   // 灵敏
        Constants.FOLD_SPLIT_DEPTH_SLUGGISH -> 35f    // 迟钝
        else -> 20f                                   // 标准（默认，原行为）
    }

    /** 回弹：回到"基线 - D2"以内 ⇒ 认为折完松手了 */
    private const val D2 = 6f

    /** 合上保护：比基线低这么多度 ⇒ 认为这是要合上手机，不触发（≈ 半开位） */
    private const val D3 = 90f

    /** 判定时限：从【开始折】起算，超时就不触发（用户口径 = 1 秒） */
    private const val WIN_MS = 1_000L

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
    )

    private enum class Outcome { NOTHING, REARM, ENTER, TRIGGER, CANCEL_CLOSE, CANCEL_TIMEOUT }

    @Volatile private var state = FoldState()
    @Volatile private var lastTriggerMs = 0L

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
        var s = s0.copy(window = win, lastSample = last, dropStartMs = dropStart)
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

        // ② 未进入：先过冷却，再看落差够不够
        if (!s0.folding) {
            if (nowMs - lastTriggerMs < COOLDOWN_MS) {
                state = s
                return
            }
            if (a0 > OPEN_MIN && a0 - angle > d1()) {
                state = s.copy(
                    folding = true,
                    foldStartMs = if (s.dropStartMs >= 0L) s.dropStartMs else nowMs,
                    minDuringFold = angle,
                )
                Logx.v("轻折加分屏: 进入折叠判定 angle=${angle.f1()} A0=${a0.f1()} 差=${(a0 - angle).f1()}°")
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
            // (a) 合上：取消 + 闩住 + **清空基线窗口**（物理上：合上之前那个 179° 已不代表当前基线）
            a0 - angle >= D3 -> {
                state = s.copy(folding = false, suppressed = true, window = emptyList(), lastSample = Float.NaN, dropStartMs = -1L)
                Logx.always("轻折加分屏: 判为要合上（最低 ${minFold.f1()}°）⇒ 不触发，闩住")
            }

            // (b) ★ 超时：**必须在两条回弹之前**（见类注释第 1 条）
            nowMs - s0.foldStartMs > WIN_MS -> {
                state = s.copy(folding = false, suppressed = true)
                Logx.always("轻折加分屏: 超时未回弹（${nowMs - s0.foldStartMs}ms > ${WIN_MS}ms）⇒ 不触发，闩住")
            }

            // (c) 回弹①：完全弹回基线附近
            angle >= a0 - D2 -> {
                state = s.copy(folding = false)
                lastTriggerMs = nowMs
                Logx.always("轻折加分屏: 轻折回弹，历时 ${nowMs - s0.foldStartMs}ms 最低 ${minFold.f1()}° ⇒ 触发")
                fire()
            }

            // (d) 回弹②：自谷底明显回升（松手必回弹，不必等它回到展开位）
            angle - minFold >= d1() -> {
                state = s.copy(folding = false)
                lastTriggerMs = nowMs
                Logx.always("轻折加分屏: 轻折回弹（自谷底回升 ${(angle - minFold).f1()}°）历时 ${nowMs - s0.foldStartMs}ms ⇒ 触发")
                fire()
            }

            else -> Unit // 还在往下折，继续观望
        }
    }

    private fun Float.f1(): String = "%.1f".format(this)

    // ───────────────────────── 触发 ─────────────────────────

    private fun fire() {
        // ★ 每次触发**同步**取一次真值：开关是热开关，用户随手改完立刻就能生效，
        //   不该"这一折还按旧值处理"（一次轻折一条 IPC，成本可忽略）。
        Cfg.refreshSync()
        if (!Cfg.foldSplit) {
            Logx.always("轻折加分屏: 开关为关 ⇒ 不执行（热开关，改完无需重启 SystemUI）")
            return
        }
        val clNow = classLoader
        if (clNow != null && SoScSupport.inMinimizedMode(clNow)) {
            // ★ v0.7.5 的铁律：单侧分屏（SplitState=-1）是雷区，官方状态机会被搅坏
            Logx.always("轻折加分屏: 当前是单侧（minimized）分屏 ⇒ 不介入")
            return
        }
        val ctrl = controllerOrNull()
        if (ctrl == null) {
            Logx.always("轻折加分屏: 还没有双分屏控制器 ⇒ 放弃本次")
            return
        }
        val h = wmShellMainHandler(ctrl)
        if (h == null) {
            Logx.always("轻折加分屏: 拿不到 WMShell 主 Handler ⇒ 放弃本次")
            return
        }
        // ★ 并发闸：上一次还没飞完就丢弃这次（丢而不是排队 —— 排队会"折得越快、排得越多"）
        if (appending) {
            Logx.always("轻折加分屏: 上一次还在进行 ⇒ 丢掉这一折")
            return
        }
        appending = true
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
        Logx.always("轻折加分屏: stage=$n ⇒ ${if (n >= 2) "追加一格" else "splitPrimaryTask(pos=$pos)"}")
        if (n >= 2) {
            append(ctrl)
            return
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
