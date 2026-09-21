package com.abel.os4freeformx

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

/**
 * 新手势（SystemUI 进程）。两个功能共用一个全局输入源。
 *
 * **输入源**：MIUI 自己就有全局手势监视器 —— `MulWinSwitchEventController`（单例）用
 * `InputManager.monitorGestureInput` 拿到一条 InputMonitor，由内部类
 * `MulWinSwitchEventController$EventReceiver#onInputEvent(MotionEvent)` 收取全屏触摸，再分发给
 * 注册进来的 `EventHandler`。本模块直接挂它的 `onInputEvent`，**全屏 / 桌面 / 小窗 / 分屏**下的触摸
 * 都能看到，而且不需要任何额外权限（monitorGestureInput 已由 MIUI 在 SystemUI 里建好）。
 *
 * 两个手势：
 * 1. **角落斜滑**：屏幕下部左/右下角起手 → 向屏幕中心斜向滑动 → 松手时把当前前台应用转成小窗。
 * 2. **四指上滑**：4 指（及以上）一起上滑 → 分屏场景下进入"加窗"流程。
 *
 * 纪律（照抄模块既有约定）：所有 hook 走 [MainHook.hookMethod]（PROTECTIVE + try/catch），
 * 热路径只用 `Logx.v` / `Logx.once`，`Logx.always` 留给一次性结论。
 */
object Gestures {

    // ---------------- 目标类（HyperOS 4 / Android 17 实测确认）----------------
    private const val CLS_EVENT_CONTROLLER =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController"
    private const val CLS_EVENT_RECEIVER =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController\$EventReceiver"
    private const val CLS_EVENT_HANDLER =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventController\$EventHandler"

    /** 模块自身包名（SystemUI 进程里 `ctx.packageName` 是 com.android.systemui，不能拿来当目标） */
    private const val MODULE_PKG = "com.abel.os4freeformx"

    private const val MODE_FREEFORM = 5
    private const val MODE_MULTI_WINDOW = 3   // WINDOWING_MODE_SPLIT_SCREEN（双应用分屏）
    private const val MODE_MULTI_SPLIT = 6    // HyperOS 多分屏（3~6 应用）

    // ---------------- 屏幕与阈值 ----------------
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    @Volatile private var density = 3f

    private fun dp(v: Float): Float = v * density

    /**
     * 角滑起手区：屏幕**下部的左右角**。
     *
     * 用**屏幕尺寸比例**而不是固定 dp：折叠屏内外屏 / 横竖屏切换时屏幕尺寸会变，
     * 固定 dp 会出现"内屏能滑、外屏滑不动"（真机踩过：`input swipe` 落到 (1324,1709) 时
     * 判定区却按另一组尺寸算，起手就被否掉）。
     */
    // ⚠️ 收紧到最角落：官方「上滑到左上角 / 底部中间上滑进多分屏」的起手区就在屏幕下部，
    // 之前 40%×45% 的判定区会大量误命中（真机演示：从右下角自然上滑就命中三次）。
    private const val CORNER_ZONE_W = 0.22f   // 左右各 22% 宽
    private const val CORNER_ZONE_H = 0.22f   // 屏幕下 22% 高

    private val cornerMinTravel get() = dp(200f)
    private const val CORNER_MIN_RATIO = 0.75f
    private const val CORNER_MAX_RATIO = 1.35f

    private const val FF_MIN_POINTERS = 4
    private val ffMinTravel get() = dp(45f)   // 放宽：起手就有机会命中
    private const val FF_MAX_MS = 2600L        // 放宽：慢一点的上滑也算

    @Volatile private var uiLoader: ClassLoader? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------------- 手势状态 ----------------
    private var cornerArmed = false
    private var cornerLeft = true
    private var cornerX = 0f
    private var cornerY = 0f
    private var cornerBad = false
    private var cornerTime = 0L

    private var ffArmed = false
    private var ffX = 0f
    private var ffY = 0f
    private var ffStart = 0L
    private var ffPeak = 0
    /** 四指手势本次是否已触发过（MOVE 上触发，防重复）。 */
    private var ffFired = false

    /** 命中后本串事件不再交给 MIUI 自己的手势逻辑（避免它再解释一遍）。 */
    @Volatile private var swallow = false
    @Volatile private var swallowAt = 0L

    private fun cls(name: String): Class<*> =
        Class.forName(name, false, uiLoader ?: Gestures.javaClass.classLoader)

    // ---------------- 安装 ----------------

    fun install(m: MainHook, cl: ClassLoader) {
        uiLoader = cl
        // 早期启动兜底：refreshDisplay 内部取 AppCtx 可能失败（Context 未就绪），绝不因此中断 install
        // （否则 onInputEvent 挂钩失败 → 手势全失效）。无论如何 2s 后重试一次，确保拿到真实屏幕尺寸。
        runCatching { refreshDisplay() }
        main.postDelayed({ runCatching { refreshDisplay() } }, 2000)
        // ① 全局输入：挂 MIUI 的 EventReceiver，拿到所有 MotionEvent
        val hooked = m.hookMethod(cl, CLS_EVENT_RECEIVER, "onInputEvent",
            arrayOf(android.view.InputEvent::class.java),
            XposedInterface.Hooker { chain ->
                var consumed = false
                try {
                    Logx.once("ev-first", "钩子已收到事件（onInputEvent 生效）")
                    val ev = chain.getArg(0) as? MotionEvent
                    if (ev != null && Cfg.gestures) consumed = onMotion(ev)
                } catch (t: Throwable) {
                    Logx.e("手势处理失败", t)
                }
                // 命中我们的手势时不再往下分发；否则原样放行（绝不能吞掉别的事件）
                if (consumed) null else chain.proceed()
            })

        // ② 注册 EventHandler + 确保 receiver 存在（全屏场景下 MIUI 可能还没建 receiver）
        main.post { ensureReceiver() }
        // ⚠️ 测试轮询默认**关闭**：它每 1.5s 跨进程 call 一次 ContentProvider，
        // 在 SystemUI 启动早期 Context 还没就绪时会抛
        // `NullPointerException: getDefaultClassLoader(...) must not be null`，
        // 真机表现为分屏里滑动黑屏/闪退。只有显式打开测试开关才启动。
        // 注意：必须在 post 的 lambda **内部**读取配置 —— 安装那一刻 Cfg 往往还没读到
        // remote prefs（真机踩过：provider 里 test_hook=true，钩子却按默认 false 跳过）
        // ⚠️ 临时调试：去掉 Cfg.testHook 门控，让测试轮询始终运行（FIRE:/MAKEPAIR:/ADDSPLIT: 驱动）。
        // 提交前需恢复为 `&& Cfg.testHook` 或改回默认关闭，避免常驻跨进程轮询。
        main.post { if (runCatching { Cfg.reload() }.isSuccess) watchTestHook() }
        Logx.always(
            "installGestures: onInputEvent 挂载=$hooked（gestures=${Cfg.gestures} " +
                "屏=${screenW}x${screenH} 密度=$density）"
        )
    }

    /**
     * 保证 MIUI 的全局手势 receiver 已建好。
     *
     * 平时它由 MIUI 在第一个小窗/分屏装饰创建时建（`MulWinSwitchDecorViewModel`），
     * 全屏场景下可能还没建 —— 那样我们的 `onInputEvent` 钩子就没有事件流，所以这里主动建一次
     * （该方法内部已做幂等判断：已建过直接 return）。
     */
    private fun ensureReceiver() {
        runCatching {
            val ctx = AppCtx.get() ?: return@runCatching
            val handlerCls = cls(CLS_EVENT_HANDLER)
            val loader = uiLoader ?: Gestures.javaClass.classLoader
            val proxy = Proxy.newProxyInstance(loader, arrayOf(handlerCls)) { _, method, args ->
                when (method.name) {
                    "onEvent" -> onMotion(args?.get(0) as? MotionEvent ?: return@newProxyInstance false)
                    "interceptEventWhenHandled" -> false
                    "toString" -> "OS4FFX-GestureHandler"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            }
            val ctl = cls(CLS_EVENT_CONTROLLER).getMethod("getInstance").invoke(null) ?: return@runCatching
            ctl.javaClass.getMethod("registerEventHandler", handlerCls).invoke(ctl, proxy)
            ctl.javaClass.getMethod("createEventReceiver", Context::class.java).invoke(ctl, ctx)
            Logx.always("installGestures: EventHandler 已注册，receiver 已确保存在")
        }.onFailure { Logx.e("ensureReceiver 失败", it) }
    }

    // ---------------- 输入分发 ----------------

    /** 返回 true = 消费该事件（不再交给 MIUI 自己的手势逻辑）。 */
    private fun onMotion(ev: MotionEvent): Boolean {
        if (swallow) {
            val a = ev.actionMasked
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallow = false
            // ⚠️ 死锁保护：四指手势是**在 MOVE 上触发**的，而 MIUI 的多指通道**不保证投递
            // ACTION_UP**（真机取证）。如果不加超时，swallow 会一直为 true，
            // 之后所有手势都被吞掉 —— 用户表现就是"第一次能进分屏、再滑就没反应"。
            if (android.os.SystemClock.uptimeMillis() - swallowAt > 700) swallow = false
            return true
        }
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(ev)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(ev)
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(ev)
            MotionEvent.ACTION_UP -> onUp(ev)
            MotionEvent.ACTION_CANCEL -> {
                reset()
                false
            }
            else -> false
        }
    }

    private fun onDown(ev: MotionEvent): Boolean {
        reset()
        if (screenW == 0 || screenH == 0) refreshDisplay()
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val x = ev.rawX
        val y = ev.rawY
        // 底部正中最下缘留给 MIUI 自己的"底部中间上滑进多分屏"，角滑不在这里起手
        val inBottomCenter = y >= h - dp(220f) && x > w * 0.25f && x < w * 0.75f
        if (!inBottomCenter && y >= h * (1f - CORNER_ZONE_H) && y <= h) {
            val left = x <= w * CORNER_ZONE_W
            val right = x >= w * (1f - CORNER_ZONE_W)
            if (left || right) {
                cornerArmed = true
                cornerLeft = left
                cornerX = x
                cornerY = y
                cornerTime = ev.eventTime
                Logx.v("手势: 角滑起手 侧=${if (left) "左" else "右"} @${x.toInt()},${y.toInt()} 屏=${screenW}x$screenH")
            }
        }
        if (ev.pointerCount >= FF_MIN_POINTERS && !cornerArmed) armFourFinger(ev)
        return false
    }

    private fun onPointerDown(ev: MotionEvent): Boolean {
        if (cornerArmed) cornerBad = true           // 角滑只认单指
        if (!ffArmed && !cornerArmed && ev.pointerCount >= FF_MIN_POINTERS) armFourFinger(ev)
        return false
    }

    private fun onPointerUp(ev: MotionEvent): Boolean {
        // 抬起一根手指不算放弃：4 指手势里手指本来就不会完全同步抬起
        if (ffArmed && ev.pointerCount - 1 < 2) ffArmed = false
        if (cornerArmed) cornerBad = true
        return false
    }

    private fun armFourFinger(ev: MotionEvent) {
        ffArmed = true
        ffX = ev.rawX
        ffY = ev.rawY
        ffStart = ev.eventTime
        ffPeak = ev.pointerCount
        Logx.always("手势: 四指起手 pointers=${ev.pointerCount} @${ev.rawX.toInt()},${ev.rawY.toInt()}")
    }

    private fun onMove(ev: MotionEvent): Boolean {
        if (cornerArmed && !cornerBad) {
            val dx = ev.rawX - cornerX
            val dy = ev.rawY - cornerY
            val inward = if (cornerLeft) dx else -dx
            if (ev.pointerCount > 1) cornerBad = true
            else if (inward < -dp(30f)) cornerBad = true      // 往屏幕外侧滑
            else if (dy > dp(30f)) cornerBad = true           // 明显下滑
        }
        if (ffArmed) {
            if (ev.pointerCount < 2) {
                ffArmed = false
                Logx.once("ff-abandon", "手势: 四指放弃（指针剩 ${ev.pointerCount}）")
            } else {
                ffPeak = maxOf(ffPeak, ev.pointerCount)
                val dy = ev.rawY - ffY
                val used = ev.eventTime - ffStart
                Logx.once(
                    "ff-move-${ev.eventTime / 300}",
                    "手势: 四指移动 pc=${ev.pointerCount} dy=${dy.toInt()} used=${used}ms"
                )
                // ⚠️ **在滑动中触发，不等 ACTION_UP**：真机取证 —— 四指抬起时 MIUI 的监视通道
                // 不投递 ACTION_UP（日志里 8 次四指起手、若干次四指移动，但"命中/未命中"计数为 0），
                // 所以把判定放在 MOVE 上：只要滑够距离且四指还在，立即触发一次。
                if (!ffFired && ffPeak >= FF_MIN_POINTERS &&
                    dy <= -ffMinTravel && used <= FF_MAX_MS &&
                    Cfg.fourFingerSplit
                ) {
                    ffFired = true
                    swallowAt = android.os.SystemClock.uptimeMillis()
                    swallow = true
                    Logx.always("手势: 四指上滑命中(MOVE) 行程=${(-dy).toInt()}px 用时=${used}ms 手指数=$ffPeak")
                    fourFingerAddSplit()
                }
            }
        }
        return false
    }

    private fun onUp(ev: MotionEvent): Boolean {
        // 命中时**强制同步**读一次配置：`Cfg` 平时是节流异步刷新（1500ms），
        // 否则用户刚改完开关、手势仍按旧快照执行（真机踩过：开关已开却报 开关=false）。
        // 这里在抬指时才调用一次，不在热路径上，开销可接受。
        runCatching { Cfg.reload() }
        var consumed = false
        if (cornerArmed && !cornerBad && !ffArmed) {
            val dx = ev.rawX - cornerX
            val dy = ev.rawY - cornerY
            val dist = hypot(dx, dy)
            val inward = if (cornerLeft) dx else -dx
            val ratio = if (inward > 1f) kotlin.math.abs(dy) / inward else 99f
            if (dist >= cornerMinTravel && inward > dp(60f) && dy <= -dp(30f) &&
                ratio in CORNER_MIN_RATIO..CORNER_MAX_RATIO
            ) {
                Logx.always(
                    "手势: 角滑命中 侧=${if (cornerLeft) "左" else "右"} 行程=${dist.toInt()}px " +
                        "dx=${dx.toInt()} dy=${dy.toInt()} 用时=${ev.eventTime - cornerTime}ms " +
                        "开关=${Cfg.cornerFreeform}"
                )
                // ⚠️ 开关关闭时必须**原样放行**：以前是先 swallow=true 再看开关，
                // 结果"关掉角滑"仍然会把这串事件吃掉，把 MIUI 自己的「底部中间上滑进多分屏」掐死
                // （真机反馈：角落上滑与多分屏中间底部上滑冲突，无法完成官方操作）。
                // ⚠️ 只在**单应用全屏**时才动作：分屏/多分屏/已有小窗的场景里，
                // 这一片正是 MIUI 自己「上滑到左上角进分屏 / 底部中间上滑进多分屏」的热区，
                // 我们一旦介入就会把官方手势掐死（真机演示踩过）。
                if (Cfg.cornerFreeform && isPlainFullscreen()) {
                    swallowAt = android.os.SystemClock.uptimeMillis()
                    swallow = true
                    consumed = true
                    cornerSwipeToFreeform()
                } else if (Cfg.cornerFreeform) {
                    Logx.always("手势: 角滑命中但当前不是单应用全屏（分屏/小窗），放行给系统")
                }
            } else {
                Logx.v("手势: 角滑未命中 行程=${dist.toInt()} dx=${dx.toInt()} dy=${dy.toInt()} bad=$cornerBad")
            }
        }
        if (ffArmed) {
            val dy = ev.rawY - ffY
            val dx = ev.rawX - ffX
            val used = ev.eventTime - ffStart
            // 判定用**峰值 ffPeak**，不用当前指针数（ACTION_UP 时通常只报 1 根）
            if (dy <= -ffMinTravel && kotlin.math.abs(dx) < dp(260f) && used <= FF_MAX_MS && ffPeak >= FF_MIN_POINTERS) {
                Logx.always(
                    "手势: 四指上滑命中 行程=${(-dy).toInt()}px dx=${dx.toInt()} 用时=${used}ms " +
                        "手指数=$ffPeak 多分屏=${splitActive()}"
                )
                if (Cfg.fourFingerSplit) {
                    swallow = true
                    consumed = true
                    fourFingerAddSplit()
                }
            } else {
                Logx.always("手势: 四指未命中 dy=${dy.toInt()} dx=${dx.toInt()} used=${used}ms peak=$ffPeak")
            }
        }
        reset()
        return consumed
    }

    private fun reset() {
        cornerArmed = false
        cornerBad = false
        ffArmed = false
        ffPeak = 0
        ffFired = false
    }

    private fun hypot(dx: Float, dy: Float): Float =
        kotlin.math.sqrt(dx * dx + dy * dy)

    // ---------------- 动作 1：角落斜滑 → 前台应用转小窗 ----------------

    private fun cornerSwipeToFreeform() {
        runCatching {
            val ctx = AppCtx.get() ?: run {
                Logx.e("角滑: 取不到 Context，放弃")
                return@runCatching
            }
            val info = topTask() ?: run {
                Logx.e("角滑: 取不到前台任务，放弃")
                return@runCatching
            }
            val pkg = pkgOf(info) ?: run {
                Logx.e("角滑: 前台任务没有包名，放弃 | ${describe(info)}")
                return@runCatching
            }
            val id = taskIdOf(info)
            val mode = modeOf(info)
            Logx.always("角滑: 前台 pkg=$pkg task=$id mode=$mode")
            when (mode) {
                MODE_FREEFORM -> Logx.always("角滑: 前台已是小窗，忽略")
                MODE_MULTI_WINDOW, MODE_MULTI_SPLIT -> {
                    if (!splitToFreeform(id)) launchFreeform(ctx, pkg, cornerX, cornerY)
                }
                else -> launchFreeform(ctx, pkg, cornerX, cornerY)
            }
        }.onFailure { Logx.e("角滑处理失败", it) }
    }

    /** 官方接口：`MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, x, y)` + startActivity。 */
    private fun launchFreeform(ctx: Context, pkg: String, x: Float, y: Float) {
        runCatching {
            val mmu = Class.forName(Constants.CLS_MULTIWINDOW_UTILS)
            val opts = mmu.getMethod(
                "getActivityOptions", Context::class.java, String::class.java,
                java.lang.Boolean.TYPE, Integer.TYPE, Integer.TYPE
            ).invoke(null, ctx, pkg, true, x.toInt(), y.toInt())
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
                Logx.e("角滑: 取不到 $pkg 的启动 Intent")
                return@runCatching
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val bundle = opts?.javaClass?.getMethod("toBundle")?.invoke(opts) as? android.os.Bundle
            ctx.startActivity(intent, bundle)
            Logx.always("角滑: 已请求以小窗启动 $pkg（x=${x.toInt()} y=${y.toInt()}）")
        }.onFailure { Logx.e("角滑: 小窗启动失败", it) }
    }

    /** 分屏里的应用转小窗：`MulWinSwitchAnimStarter.switchSplitToFreeform(taskId)`。 */
    private fun splitToFreeform(taskId: Int): Boolean = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return false
        val starter = ctl.javaClass.getMethod("getMulWinSwitchStarter").invoke(ctl) ?: return false
        starter.javaClass.getMethod("switchSplitToFreeform", Integer.TYPE).invoke(starter, taskId)
        Logx.always("角滑: 已请求分屏 task=$taskId 转小窗")
        true
    }.getOrElse {
        Logx.e("角滑: 分屏转小窗失败，改走重新启动", it)
        false
    }

    // ---------------- 动作 2：四指上滑 → 加分屏（下一轮接线）----------------

    /**
     * 四指上滑 → 弹出应用选择器 → 把选中的应用加进当前分屏组。
     *
     * 只在**分屏进行中**才动作（本 AOSP/MIUI 的分屏与自由小窗是互斥的场景）。
     * 候选来自 shell 已知的运行任务（`MultiTaskingTaskRepository`），过滤掉已在分屏里的。
     */
    /**
     * 四指上滑 → **调用系统自己的分屏吸附**（等价于把应用甩到屏幕角落、系统自动吸进分屏）。
     *
     * 系统入口（真机反编译确认，`com.android.wm.shell.sosc.SoScUtils`）：
     * - `enterSplitScreen(RunningTaskInfo, WindowContainerTransaction, boolean)` —— 把任务送进分屏；
     * - `addSplitPair(int, int)` —— 把两个任务配成一组 SoSc 分屏；
     * 收尾 `finishEnterSplitScreen(Transaction)`（取不到就退回 `ShellTaskOrganizer#applyTransaction`）。
     *
     * 这里**不弹任何自建窗口** —— 选哪个应用由 shell 已知任务决定（排除已在分屏内与自由小窗），
     * 与"把应用上滑到角落、系统自动吸附"走的是同一条系统路径。
     */
    private fun fourFingerAddSplit() {
        runCatching {
            val group = splitTaskIds()
            val all = allTasks()
            Logx.always(
                "四指上滑: SoSc=${soScActive()}(${runCatching { socUtils()?.javaClass?.getMethod("getSoScState")?.invoke(socUtils()) }.getOrNull()}) 多分屏=${splitActive()} " +
                    "组内=$group shell已知=${all.size} 前台=${topTask()?.let { "${pkgOf(it)}/${taskIdOf(it)}/mode=${modeOf(it)}" }}"
            )
            // ===== 按当前状态选接口（用户指定的逻辑）=====
            //  单任务        → 双分屏接口（openWindowFromFullscreen）
            //  双分屏(SoSc)  → 三分屏接口（startMultipleSplits 起多分屏模式）
            //  三/四/五/六分屏 → 同一个"加层"接口（startMultipleSplits 扩一层）
            // 每个入口都由系统自己去铺 stage / 弹"让用户选应用"，模块不自建 UI、不预塞应用。
            val free = freeformTasks()
            val inSplit = splitActive() || soScActive() || free.isNotEmpty()
            val cur = when {
                splitActive() || soScActive() -> splitTaskIds()
                free.isNotEmpty() -> free
                else -> emptyList()
            }
            Logx.always(
                "四指上滑状态判定: 多分屏=${splitActive()} SoSc=${soScActive()} 自由窗=$free " +
                    "当前层数=${if (inSplit) cur.size else 1} 组内=$cur"
            )
            if (!inSplit) {
                val fg = topTask()
                if (fg == null || taskIdOf(fg) <= 0) {
                    Logx.always("四指上滑: 取不到前台任务，放弃")
                    return@runCatching
                }
                Logx.always(
                    "四指上滑: 单任务 → 调**双分屏**接口（前台 pkg=${pkgOf(fg)} task=${taskIdOf(fg)}）"
                )
                dragToSplit(taskIdOf(fg), pkgOf(fg) ?: "?")
            } else {
                Logx.always("四指上滑: 已在分屏（${cur.size} 层）→ 调**加层/多分屏**接口")
                startMultipleSplits(cur, null)
            }
        }.onFailure { Logx.e("四指上滑处理失败", it) }
    }

    /**
     * 全屏 → 双分屏：复刻 MIUI 拖拽落点分发
     * `MulWinSwitchTransition#startIconDragSplitScreen(PendingIntent, hotAreaType, reason)`。
     *
     * hotAreaType 用 `HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP = 1`（就是"甩到左上角"那个热区），
     * reason 传 0；PendingIntent 由包的启动 Intent 现造（MIUI 那边也是从拖拽会话里拿 `mLaunchIntent`）。
     */
    private fun dragToSplit(taskId: Int, pkg: String) {
        // `MulWinSwitchTransition#startIconDragSplitScreen` 内部会调 `startTransition`，
        // 后者有线程断言（真机：`IllegalStateException: must be called on Handler {…}`），
        // 所以整段必须投到 MIUI 自己的 executor 上执行。
        val body = Runnable { dragToSplitInternal(taskId, pkg) }
        val posted = runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching false
            val trans = ctl.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl) ?: return@runCatching false
            val tr = declaredField(trans, "mTransitions")
                ?: declaredField(ctl, "mTransitions")
                ?: return@runCatching false
            val exec = tr.javaClass.getMethod("getMainExecutor").invoke(tr) as? java.util.concurrent.Executor
                ?: return@runCatching false
            exec.execute(body)
            Logx.always("进分屏: 已投递到 Transitions.mainExecutor（task=$taskId pkg=$pkg）")
            true
        }.getOrDefault(false)
        if (!posted) {
            Logx.always("进分屏: 取不到 Transitions.mainExecutor，改在主线程执行")
            main.post(body)
        }
    }

    private fun dragToSplitInternal(taskId: Int, pkg: String) {
        runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching
            val trans = ctl.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl) ?: return@runCatching
            // ✅ 用 `openWindowFromFullscreen(taskId, intent)` —— 真机验证过**能真正起分屏**，
            // 而且行为就是系统默认的"半屏应用 + 半屏桌面/让用户选"（用户实测确认）。
            // ⚠️ 不要换成 `prepareDragDropTaskToSoSc`：我用它试过一版，参数语义没摸对，
            //    真机表现是**两侧都黑屏**（2026-09-21 实测），已回退。
            trans.javaClass.getMethod(
                "openWindowFromFullscreen", Integer.TYPE, android.app.PendingIntent::class.java
            ).invoke(trans, Integer.valueOf(taskId), null)
            Logx.always("进分屏: 已请求 openWindowFromFullscreen(task=$taskId pkg=$pkg)")
        }.onFailure { e ->
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Logx.e("进分屏失败: ${root.javaClass.name}: ${root.message}", root)
        }
    }

    /**
     * 进入系统**多分屏模式**（三分屏起、最多六应用）。
     *
     * Bundle 键照抄官方（`MultipleSplitRootTaskOrganizer#startMultipleSplits` 读的就是这几个）：
     * `multiple_launch_taskIds`（任务 id 数组，含新加入那个）、`multiple_launch_bounds`（数组）、
     * `multiple_launch_way`（字符串）、`multiple_launch_enter_quick_view_mode`（布尔）。
     * 由系统自己去铺 stage、并**给出空位让用户选应用** —— 这才是原生行为。
     */
    private fun startMultipleSplits(group: List<Int>, cand: Any?, quickView: Boolean = true) {
        runCatching {
            val ids = ArrayList<Int>(group)
            cand?.let { c -> taskIdOf(c).takeIf { it > 0 }?.let { ids.add(it) } }
            // ⚠️ **绝不补位/去重后仍重复**：真机取证 —— 双分屏时我传 [7068,7073,7068]（第三个是补位补的），
            // 系统按"三个任务"去铺 stage，拿到重复 id 直接**黑屏**（用户实测）。
            // 因此这里严格**只传真实存在的任务 id**，重复的丢掉；数量不足就交给系统自己处理
            // （系统会为空 stage 留位/弹选择界面，这才是原生行为）。
            val uniq = LinkedHashSet<Int>()
            ids.forEach { if (it > 0) uniq.add(it) }
            ids.clear(); ids.addAll(uniq)
            Logx.always("四指上滑: 多分屏 taskIds=${ids}（去重后 ${ids.size} 个，不补位）")
            if (ids.isEmpty()) {
                Logx.always("四指上滑: 没有有效的任务 id，放弃")
                return@runCatching
            }
            val bounds = ArrayList<android.graphics.Rect>()
            ids.forEach { _ -> bounds.add(android.graphics.Rect(0, 0, screenW, screenH)) }
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: run {
                Logx.always("四指上滑: 取不到 MultiTaskingControllerImpl 单例，放弃")
                return@runCatching
            }
            // 诊断 + 取实现：先确认方法是否存在、impl 是否为 null、是否是 classloader 不匹配
            val mgrMethod = runCatching { ctl.javaClass.getMethod("getMultiTaskingStateManager") }.getOrNull()
            val impl = runCatching { mgrMethod?.invoke(ctl) }.getOrNull()
            val iface = runCatching {
                Class.forName("com.android.wm.shell.multitasking.common.IMultiTaskingStateManager", false, uiLoader)
            }.getOrNull()
            Logx.always(
                "四指上滑: DIAG getMultiTaskingStateManager 存在=${mgrMethod != null} " +
                    "impl=${impl?.javaClass?.name} implNull=${impl == null} " +
                    "iface=${iface?.name} isInstance=${iface?.isInstance(impl) ?: false} " +
                    "implIfaces=[${impl?.javaClass?.interfaces?.joinToString { it.name }}]"
            )
            if (impl == null) {
                Logx.e("四指上滑: getMultiTaskingStateManager 返回 null，放弃（不退回下层 controller）")
                return@runCatching
            }
            // 选方法：优先官方接口（isInstance 命中），否则**绕过 classloader 不匹配**直接在 impl 上按方法名调用
            // （binder 代理一定暴露 AIDL 的 startMultipleSplits 方法，getMethod 会沿接口链找到它）。
            val m = if (iface != null && iface.isInstance(impl)) {
                runCatching { iface.getMethod("startMultipleSplits", android.os.Bundle::class.java) }.getOrNull()
            } else {
                runCatching { impl.javaClass.getMethod("startMultipleSplits", android.os.Bundle::class.java) }.getOrNull()
            }
            if (m == null) {
                Logx.e("四指上滑: 找不到 startMultipleSplits 方法，放弃")
                return@runCatching
            }
            // 先试 enter_quick_view_mode=true（弹选择器，符合"进入单侧分屏的多任务界面"），失败再试 false
            // quickView=false 时（ADDSPLIT 已有明确目标应用）只试 false，避免二次弹选择器
            var ok = false
            for (qv in if (quickView) listOf(true, false) else listOf(false)) {
                val b = android.os.Bundle().apply {
                    putIntArray("multiple_launch_taskIds", ids.toIntArray())
                    putParcelableArray("multiple_launch_bounds", bounds.toTypedArray())
                    putString("multiple_launch_way", "gesture")
                    putBoolean("multiple_launch_enter_quick_view_mode", qv)
                }
                ok = runCatching { m.invoke(impl, b) }.fold(
                    onSuccess = {
                        Logx.always("四指上滑: startMultipleSplits 调用成功（enter_quick_view_mode=$qv）")
                        true
                    },
                    onFailure = { e1 ->
                        val rt = (e1 as? java.lang.reflect.InvocationTargetException)?.targetException ?: e1
                        Logx.e("四指上滑: startMultipleSplits 失败(qv=$qv) ${rt.javaClass.simpleName}: ${rt.message}", rt)
                        false
                    }
                )
                if (ok) break
            }
            Logx.always("四指上滑: 已请求进入多分屏 startMultipleSplits(taskIds=${ids}) ok=$ok")
        }.onFailure { e ->
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Logx.e("四指上滑: 进入多分屏失败 ${root.javaClass.simpleName}: ${root.message}", root)
        }
    }

    /** 多分屏里再加一个 stage：`MultipleSplitController#insertMultipleSplitByTask(wct, taskId, index)`。 */
    private fun insertPane(task: Any, index: Int) {
        runCatching {
            val ctx = AppCtx.get() ?: return@runCatching
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching
            val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl) ?: return@runCatching
            val wctCls = Class.forName("android.window.WindowContainerTransaction", false, uiLoader)
            val wct = wctCls.getDeclaredConstructor().newInstance()
            val id = taskIdOf(task)
            val pkg = pkgOf(task)
            // ⚠️ 首选 **insertMultipleSplitByIntent**：真机实测用 insertMultipleSplitByTask 把"已有任务"
            // 塞进去时，stage 建出来了但都是空的（sz=0）→ 用户看到的就是"另一侧黑屏"。
            // 用 PendingIntent 让系统自己把应用启动进那个 stage，才是系统"另一侧进桌面选择应用"的等价做法。
            val pi = if (pkg.isNullOrEmpty()) null else runCatching {
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching null
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                android.app.PendingIntent.getActivity(
                    ctx, 0, launch,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            }.getOrNull()
            if (pi != null) {
                sc.javaClass.getMethod("insertMultipleSplitByIntent", wctCls, android.app.PendingIntent::class.java, Integer.TYPE)
                    .invoke(sc, wct, pi, Integer.valueOf(index))
                Logx.always("四指上滑: 用 insertMultipleSplitByIntent 插桩（pkg=$pkg index=$index）")
            } else {
                sc.javaClass.getMethod("insertMultipleSplitByTask", wctCls, Integer.TYPE, Integer.TYPE)
                    .invoke(sc, wct, Integer.valueOf(id), Integer.valueOf(index))
                Logx.always("四指上滑: 回退 insertMultipleSplitByTask（task=$id index=$index）")
            }
            val org = orgOf(ctl) ?: orgOf(sc)
            if (org != null) {
                org.javaClass.getMethod("applyTransaction", wctCls).invoke(org, wct)
                Logx.always("四指上滑: 多分屏插桩已提交（task=$id index=$index）")
            } else {
                Logx.e("四指上滑: 找不到 ShellTaskOrganizer，WCT 未提交")
            }
        }.onFailure { Logx.e("多分屏插桩失败", it) }
    }

    /** `MultiTaskingHotAreaController.HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP` —— "甩到左上角"。 */
    private const val HOT_AREA_SPLIT_LEFT_OR_TOP = 1

    /** `MultiTaskingCommonUtils.supportSplit(RunningTaskInfo)` —— 系统自己的"这个应用能不能分屏"判断。 */
    private fun supportSplit(info: Any): Boolean = runCatching {
        val c = Class.forName("com.android.wm.shell.multitasking.common.MultiTaskingCommonUtils", false, uiLoader)
        (c.getMethod("supportSplit", Class.forName("android.app.ActivityManager\$RunningTaskInfo"))
            .invoke(null, info) as? Boolean) ?: false
    }.getOrDefault(false)

    /**
     * 测试入口：轮询 `StoreProvider` 的 `pending_test_addsplit`。
     *
     * 用途：adb 造不出四指触控，但"加分屏"那一段可以单独驱动 ——
     * `adb shell su -c "am start -a com.abel.os4freeformx.SETTEST --es pkg <pkg> --es id <id>"` 之类写进配置后，
     * 这里取走并直接执行 [addToSplit]，用来验证 SoScUtils 那条系统路径本身通不通。
     */
    private fun watchTestHook() {
        Thread {
            while (true) {
                runCatching {
                    val ctx = AppCtx.get()
                    if (ctx != null) {
                        val b = ctx.contentResolver.call(
                            android.net.Uri.parse("content://${Constants.AUTHORITY}"), "getCfg", null, null
                        )
                        val v = b?.getString(Constants.K_TEST_ADDSPLIT)
                        if (!v.isNullOrEmpty()) {
                            Logx.always("测试入口: 收到请求「$v」")
                            main.post {
                                when {
                                    v.startsWith("FIRE:") -> fourFingerAddSplit()
                                    v.startsWith("MAKEPAIR:") -> makeSplitPair(v.removePrefix("MAKEPAIR:"))
                                    v.startsWith("FW:") -> {
                                        // 把前台应用打成自由窗双分屏（复现用户"先进双分屏"的基座）
                                        val fg = topTask()
                                        if (fg != null) {
                                            val tid = taskIdOf(fg)
                                            val pk = pkgOf(fg) ?: "?"
                                            Logx.always("测试入口: openWindowFromFullscreen(top taskId=$tid pkg=$pk)")
                                            dragToSplit(tid, pk)
                                        } else {
                                            Logx.e("测试入口: FW 取不到前台任务")
                                        }
                                    }
                                    v.startsWith("ADDSPLIT:") -> addToSplit(v.removePrefix("ADDSPLIT:"))
                                    v.startsWith("MULTIQ:") -> {
                                        // 直接驱动 startMultipleSplits(quickView=false)：绕开选择器遮罩，
                                        // 用来隔离验证"高层多分屏接口产出的应用是否可触摸"（无选择器干扰）
                                        val ids = v.removePrefix("MULTIQ:")
                                            .split('|').mapNotNull { it.toIntOrNull() }.filter { it > 0 }
                                        Logx.always("测试入口: 直接 startMultipleSplits(ids=$ids, quickView=false)")
                                        startMultipleSplits(ids, null, quickView = false)
                                    }
                                    v.startsWith("MULTI:") -> {
                                        // 直接驱动 startMultipleSplits（绕开 fourFingerAddSplit 的路由判定），
                                        // 用来隔离验证"高层多分屏接口"本身通不通：MULTI:idA|idB|idC
                                        val ids = v.removePrefix("MULTI:")
                                            .split('|').mapNotNull { it.toIntOrNull() }.filter { it > 0 }
                                        Logx.always("测试入口: 直接 startMultipleSplits(ids=$ids)")
                                        startMultipleSplits(ids, null)
                                    }
                                    v.startsWith("DRAGADD:") -> {
                                        // 走"原生拖图标进分屏"路径：DRAGADD:<hotAreaType>|<pkg|HOME>
                                        val rest = v.removePrefix("DRAGADD:")
                                        val hot = rest.substringBefore('|').trim().toIntOrNull() ?: 1
                                        val pk = rest.substringAfter('|', "").trim().ifEmpty { null }
                                        Logx.always("测试入口: dragAddSplit(hot=$hot target=${pk ?: "HOME"}) SoSc=${soScActive()} 多分屏=${splitActive()} 组内=${splitTaskIds()}")
                                        dragAddSplit(hot, pk)
                                    }
                                    v.startsWith("DOCKADD:") -> {
                                        // 走**系统自己的**"双分屏→三分屏"路径（Dock 模式）：
                                        // DOCKADD:<pkg|空=只dock>
                                        //   → MultipleSplitController.dockMultipleSplitTasks(bundle{"multiple_split_dock_task"=RunningTaskInfo})
                                        //   → 等 dock 过渡完成 → 启动第 N 个应用 → 系统自行 DOCK_EXIT_SOSC_TO_THREE
                                        val pk = v.removePrefix("DOCKADD:").trim().ifEmpty { null }
                                        Logx.always("测试入口: dockAddSplit(target=${pk ?: "(仅dock)"}) SoSc=${soScActive()} 多分屏=${splitActive()} 组内=${splitTaskIds()}")
                                        dockAddSplit(pk)
                                    }
                                    else -> addToSplit(v)
                                }
                            }
                            runCatching {
                                ctx.contentResolver.call(
                                    android.net.Uri.parse("content://${Constants.AUTHORITY}"),
                                    "clearCfgKey", Constants.K_TEST_ADDSPLIT, null
                                )
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(1500)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 测试用：把两个任务程序化配成一组 SoSc 分屏
     * （`SoScUtils#addSplitPair(int, int)` —— 系统自己配对分屏用的就是它）。
     * 用途：adb 造不出四指触控、也造不出分屏手势时，先把"分屏状态"造出来，再验加分屏。
     */
    private fun makeSplitPair(sel: String) {
        runCatching {
            val a = sel.substringBefore('|').toIntOrNull()
            val b = sel.substringAfter('|', "").toIntOrNull()
            if (a == null || b == null) {
                Logx.e("测试入口: MAKEPAIR 参数需为 <idA>|<idB>，收到「$sel」")
                return@runCatching
            }
            val soc = socUtils() ?: run {
                Logx.e("测试入口: 取不到 SoScUtils")
                return@runCatching
            }
            Logx.always("测试入口: addSplitPair($a, $b) 之前 SoSc=${soScActive()} 多分屏=${splitActive()}")
            soc.javaClass.getMethod("addSplitPair", Integer.TYPE, Integer.TYPE)
                .invoke(soc, Integer.valueOf(a), Integer.valueOf(b))
            Logx.always("测试入口: addSplitPair 已调用")
        }.onFailure { e ->
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Logx.e("测试入口: addSplitPair 失败 ${root.javaClass.name}: ${root.message}", root)
        }
    }

    /**
     * 走系统**自己的**「双分屏 → 三分屏（及以上）」路径：**Dock 模式**。
     *
     * 用户真机演示 + 框架日志扒出来的真实链路（`Miui-WindowManager-Shell.jar` 反编译确认）：
     * ```
     * MultipleSplitController.dockMultipleSplitTasks(bundle{"multiple_split_dock_task": RunningTaskInfo})
     *   → MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks(Bundle)
     *        if (mDockedState != null)                       → "Already in dock mode"（忽略）
     *        if (getCurrentActiveStageCount() >= MAX_STAGES) → "Above max stages"   （忽略）
     *        if (isMultipleSplitActive())                    → dockMultipleTasks()  // 3分屏→4分屏
     *        if (SoScUtils.inSoScFullMode())                 → dockSoScTasks()      // 2分屏→3分屏
     *   → dockSoScTasks(): buildHomeToFront(wct)（桌面移到最前 = "两个任务收起、出现桌面"）
     *                      setFocusable(soscRoot,false) / mDockedState = new DockedState(...)
     *                      wct.setDockedState(stageA, 1) / 把 SoSc 根挪到 Rect(-2294,…)（移出屏外）
     *   → 【启动第 N 个应用】
     *   → MultipleSplitTransitionHandler.requestOpenToExitDockMode(mode=6, STAGE_C)
     *        → MultipleSplitUtilsImpl.handleOpenToExitDockMode
     *        → MultipleSplitRootTaskOrganizer.prepareDragTaskToMultipleSplit  // 建 STAGE_A/B/C
     *        → playDockChangeAnimation  extraType=DOCK_EXIT_SOSC_TO_THREE(11288)
     *        → finishEnterMultipleSplit → 原生多分屏落地
     * ```
     * 关键：**全程由系统自己建窗/接输入**，所以不会重演 `startMultipleSplits` 那种
     * "布局对、输入死"（`FocusedWindows: <none>` / `NO_INPUT_CHANNEL`）。
     *
     * @param pkg 第 N 个应用包名；为空则只做 dock（供手工选应用）。
     */
    /**
     * 取 dock 态下"等待填充"的那个 stage 的 WindowContainerToken。
     *
     * 原生成功链路的日志是 `requestOpenToExitDockMode … mode:6, stage:mId STAGE_C taskId:7694`，
     * 而 dock 进入时 `playDockChangeAnimation: enteringIndex=2` —— 即**索引 2** 的那个 stage。
     * 这里优先取"非活跃（空、等待填充）"的 stage，取不到就退回索引 2。
     */
    private fun dockStageToken(): Any? = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
            ?: return null
        val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl) ?: return null
        val org = sc.javaClass.getMethod("getMultipleSplitOrganizer").invoke(sc) ?: return null
        var fallback: Any? = null
        var idx = 0
        while (idx <= 5) {
            val tok = runCatching {
                val st = org.javaClass.getMethod("getStageForIndex", Integer.TYPE)
                    .invoke(org, Integer.valueOf(idx)) ?: return@runCatching null
                val rti = st.javaClass.getMethod("getRootTaskInfo").invoke(st) ?: return@runCatching null
                val active = runCatching {
                    st.javaClass.getMethod("isActive").invoke(st) as? Boolean ?: false
                }.getOrDefault(false)
                val t = declaredField(rti, "token")
                Logx.always("加分屏(dock): stage#$idx task=${taskIdOf(unwrap(rti))} active=$active token=${t != null}")
                if (idx == 2) fallback = t
                if (!active) t else null
            }.getOrNull()
            if (tok != null) return tok
            idx++
        }
        fallback
    }.getOrNull()

    private fun dockAddSplit(pkg: String?) {
        val info = dockParamTaskInfo()
        if (info == null) {
            Logx.e("加分屏(dock): 取不到 RunningTaskInfo —— 需要先进入分屏（真 SoSc 双分屏）")
            return
        }
        Logx.always(
            "加分屏(dock): 参数任务 taskId=${taskIdOf(info)} pkg=${runCatching { pkgOf(info) }.getOrNull()} " +
                "SoSc=${soScActive()} 多分屏=${splitActive()} 多分屏层数=${stageTaskIds().size}"
        )
        val out = runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                ?: return@runCatching null
            val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl)
                ?: return@runCatching null
            val b = android.os.Bundle()
            b.putParcelable(DOCK_KEY, info as android.os.Parcelable)
            sc.javaClass.getMethod("dockMultipleSplitTasks", android.os.Bundle::class.java)
                .invoke(sc, b)
        }.onFailure { e ->
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Logx.e("加分屏(dock)失败: ${root.javaClass.simpleName}: ${root.message}", root)
        }.getOrNull()
        Logx.always("加分屏(dock): dockMultipleSplitTasks → $out")

        if (pkg.isNullOrEmpty()) {
            Logx.always("加分屏(dock): 已进入 dock 态（桌面应已出现），请手工选应用")
            return
        }
        // dock 过渡 ≈ 1s（真机 DOCK_ENTER 在 18:29:08.875→08.877 finish，但 44ms 后才有
        // updateDockedState，稳妥给 1.2s），之后再启动目标应用，让系统走 DOCK_EXIT_SOSC_TO_THREE。
        main.postDelayed({
            runCatching {
                val ctx = AppCtx.get() ?: return@runCatching
                val it0 = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
                    Logx.e("加分屏(dock): 取不到 $pkg 的启动 Intent")
                    return@runCatching
                }
                it0.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                // ★ 关键：必须把它启动到 **dock 的那个 stage**（stage_c）下，让新任务的
                //   parentTaskId = stage_c。系统 `requestOpenToExitDockMode` 就是靠
                //   `mOrganizer.getStageTaskListener(triggerTask.parentTaskId)` 解析 stage 的
                //   （反编译确认）；不带 launch root 时 parentTaskId=-1、windowingMode=1
                //   → 日志出现 "mode : 1, stage : null" → 系统不做 DOCK_EXIT_SOSC_TO_THREE。
                val tok = dockStageToken()
                if (tok != null) {
                    runCatching {
                        val optsCls = Class.forName("android.app.ActivityOptions")
                        val opts = optsCls.getMethod("makeBasic").invoke(null)
                        val tokCls = Class.forName("android.window.WindowContainerToken")
                        optsCls.getMethod("setLaunchRootTask", tokCls).invoke(opts, tok)
                        val b = optsCls.getMethod("toBundle").invoke(opts) as android.os.Bundle
                        ctx.startActivity(it0, b)
                        Logx.always("加分屏(dock): 已启动 $pkg（launchRootTask=dock stage），等待 DOCK_EXIT_SOSC_TO_THREE")
                    }.onFailure {
                        Logx.e("加分屏(dock): setLaunchRootTask 失败，退回普通启动", it)
                        ctx.startActivity(it0)
                    }
                } else {
                    Logx.e("加分屏(dock): 取不到 dock stage token，退回普通启动（系统多半不会 exit dock）")
                    ctx.startActivity(it0)
                }
            }.onFailure { Logx.e("加分屏(dock): 启动 $pkg 失败", it) }
        }, 1200)
    }

    /**
     * 取一个 `ActivityManager.RunningTaskInfo` 塞进 dock bundle。
     *
     * 真机日志里 MIUI 传的是**分屏内已有子任务**（`dockMultipleSplitTasks: TaskInfo{taskId=7705}`，
     * 7705 就是当时分屏里的日历）。这里依次尝试：当前分屏子任务 → SoSc 根任务 → 前台任务。
     */
    private fun dockParamTaskInfo(): Any? {
        runCatching {
            val repo = taskRepo() ?: return@runCatching
            val l = repo.javaClass.getMethod("getVisibleSplitChildTaskInfo").invoke(repo) as? List<*>
            l?.firstOrNull { it != null }?.let { return unwrap(it) }
        }
        // 回退：SoSc 左右 **stage** 里的任务（注意不能用 getSplitRootTaskInfo —— 那是 SoSc 根任务，
        // 真机拿到的是 pkg=null isRunning=false 的空壳，喂给 dockMultipleSplitTasks 只会返回 null）
        runCatching {
            val soc = socUtils() ?: return@runCatching
            for (m in listOf("getLeftTopStage", "getRightBottomStage")) {
                val st = runCatching { soc.javaClass.getMethod(m).invoke(soc) }.getOrNull() ?: continue
                val rti = runCatching { st.javaClass.getMethod("getRunningTaskInfo").invoke(st) }.getOrNull()
                if (rti != null) {
                    val pkg = runCatching { pkgOf(unwrap(rti)) }.getOrNull()
                    if (!pkg.isNullOrEmpty()) return unwrap(rti)
                }
            }
        }
        return topTask()
    }

    /** `MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks` 唯一读取的 Bundle key。 */
    private const val DOCK_KEY = "multiple_split_dock_task"

    /**
     * 走系统**原生**「拖图标到分屏热区」入口：
     * `MulWinSwitchTransition#startIconDragSplitScreen(PendingIntent, hotAreaType, reason)`。
     *
     * 这才是 MIUI 自己「在分屏里再加一个」的真实路径（SoSc 2 分屏 → 多分屏），
     * 由系统自己跑 transition、建 SoSc 状态、接输入通道 —— 避免了直调 startMultipleSplits
     * 产出的"空壳窗口"（无输入通道 / NOT_TOUCHABLE / PAUSE_DISPATCHING，实测点不动）。
     *
     * hotAreaType（MultiTaskingHotAreaController）：0=全屏 1=SPLIT_LEFT_OR_TOP 2=SPLIT_RIGHT_OR_BOTTOM
     * 3=FREEFORM 4=BAR_OPEN 5=FREEFORM_MINI 6=MULTIPLE_SPLIT 7=SPLIT_QUICK_VIEW_MODE
     * 8/9/10=TWOSPLIT_INSET_LEFT/MIDDLE/RIGHT 16=MULTIPLE_SPLIT_REPLACE 19=MULTIPLE_SPLIT_ADD。
     * reason 传 0。
     */
    private fun dragAddSplit(hotArea: Int, pkg: String?) {
        // `startIconDragSplitScreen` 内部会跑 transition，有线程断言
        //（`HandlerExecutor.assertCurrentThread()`，真机：`IllegalStateException: must be called on Handler`）。
        // 因此与 dragToSplit 一致，投到 MIUI 自己的 Transitions.mainExecutor 上执行。
        val body = Runnable { dragAddSplitInternal(hotArea, pkg) }
        val posted = runCatching {
            val ctl0 = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching false
            val trans0 = ctl0.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl0) ?: return@runCatching false
            val tr = declaredField(trans0, "mTransitions") ?: declaredField(ctl0, "mTransitions") ?: return@runCatching false
            val exec = tr.javaClass.getMethod("getMainExecutor").invoke(tr) as? java.util.concurrent.Executor
                ?: return@runCatching false
            exec.execute(body)
            Logx.always("加分屏(drag): 已投递到 Transitions.mainExecutor（hot=$hotArea target=${pkg ?: "HOME"}）")
            true
        }.getOrDefault(false)
        if (!posted) {
            Logx.always("加分屏(drag): 取不到 Transitions.mainExecutor，改在主线程执行")
            main.post(body)
        }
    }

    private fun dragAddSplitInternal(hotArea: Int, pkg: String?) {
        runCatching {
            val ctx = AppCtx.get() ?: return@runCatching
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching
            val trans = ctl.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl) ?: return@runCatching
            val launch: android.content.Intent = if (pkg.isNullOrEmpty()) {
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                }
            } else {
                ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
                    Logx.e("加分屏(drag): 取不到 $pkg 的启动 Intent")
                    return@runCatching
                }
            }
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            trans.javaClass.getMethod(
                "startIconDragSplitScreen",
                android.app.PendingIntent::class.java, Integer.TYPE, Integer.TYPE
            ).invoke(trans, pi, Integer.valueOf(hotArea), Integer.valueOf(0))
            Logx.always("加分屏(drag): startIconDragSplitScreen(hot=$hotArea target=${pkg ?: "HOME"}) 已调用")
        }.onFailure { e ->
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Logx.e("加分屏(drag)失败: ${root.javaClass.simpleName}: ${root.message}", root)
        }
    }

    /** 提交 WCT（ShellTaskOrganizer.applyTransaction）。 */
    private fun applyWct(wct: Any, wctCls: Class<*>) {
        runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching
            val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl)
            val org = orgOf(ctl) ?: orgOf(sc) ?: run {
                Logx.e("提交 WCT: 找不到 ShellTaskOrganizer")
                return@runCatching
            }
            org.javaClass.getMethod("applyTransaction", wctCls).invoke(org, wct)
            Logx.always("四指上滑: WCT 已提交（ShellTaskOrganizer）")
        }.onFailure { Logx.e("提交 WCT 失败", it) }
    }

    private fun socUtils(): Any? = runCatching {
        Class.forName("com.android.wm.shell.sosc.SoScUtils", false, uiLoader)
            .getMethod("getInstance").invoke(null)
    }.getOrNull()

    private fun showPicker(ctx: Context, pkgs: List<String>, index: Int) {
        runCatching {
            val token = java.util.UUID.randomUUID().toString().take(8)
            pickToken = token
            val i = Intent().apply {
                setClassName(MODULE_PKG, "$MODULE_PKG.PickActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putStringArrayListExtra("pkgs", ArrayList(pkgs))
                putExtra("token", token)
                putExtra("index", index)
            }
            ctx.startActivity(i)
            Logx.always("四指上滑: 已拉起应用选择器（${pkgs.size} 个候选 token=$token）")
            awaitPick(ctx, token, pkgs)
        }.onFailure { Logx.e("拉起选择器失败", it) }
    }

    @Volatile private var pickToken: String? = null

    /**
     * 取选中的应用。
     *
     * 选择器跑在模块 App 进程（SystemUI 里开不出窗口），结果写在 CFG prefs 的 [Constants.K_PICK]，
     * 这里用带**一次性 token** 的轮询经 `StoreProvider` 取回 —— 只有 token 匹配才算数，
     * 所以旧结果或用户手动改配置都不会误触发。20 秒没选就当放弃。
     */
    private fun awaitPick(ctx: Context, token: String, pkgs: List<String>) {
        Thread {
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                runCatching {
                    val b = ctx.contentResolver.call(
                        android.net.Uri.parse("content://${Constants.AUTHORITY}"), "getCfg", null, null
                    )
                    val v = b?.getString(Constants.K_PICK)
                    if (!v.isNullOrEmpty() && v.startsWith("$token|")) {
                        val pkg = v.substringAfter('|')
                        pickToken = null
                        main.post { addToSplit(pkg) }
                        return@Thread
                    }
                }
                try {
                    Thread.sleep(250)
                } catch (ie: InterruptedException) {
                    return@Thread
                }
            }
            Logx.always("四指上滑: 选择器超时未选（候选 ${pkgs.size} 个）")
        }.apply { isDaemon = true }.start()
    }

    private fun prettyName(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * 把选中的应用加进当前分屏组。
     *
     * 两条官方路径（都从 SystemUI 进程调用）：
     * 1. **已在多分屏**（3~6 应用）：`MultipleSplitController#insertMultipleSplitByTask(wct, taskId, i)`
     *    —— 内部走 `prepareDragTaskToMultipleSplit`，`isMultipleSplitActive()` 为真时直接插桩；
     * 2. **双应用分屏（SoSc）**：先 `transferSoScToMultipleSplit(ids, types)` 把它转成多分屏
     *    （launcher 拖图标进分屏走的就是这条），再走 1。
     */
    private fun addToSplit(sel: String) {
        runCatching {
            val pkg = sel.substringBefore('|')
            val taskId = sel.substringAfter('|', "").toIntOrNull() ?: run {
                Logx.e("加分屏: 选择结果格式不对（$sel）")
                return@runCatching
            }
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return@runCatching
            val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl) ?: return@runCatching
            val group = splitTaskIds()
            Logx.always("加分屏: pkg=$pkg taskId=$taskId 组内=$group 多分屏=${splitActive()} SoSc=${soScActive()}")
            if (!splitActive() && !soScActive()) {
                // 没有分屏在跑 → 走"甩到左上角"那条官方分发，从全屏起一个分屏
                Logx.always("加分屏: 当前无分屏 → 走 startIconDragSplitScreen 起双分屏")
                dragToSplit(taskId, pkg)
                return@runCatching
            }

            if (!splitActive() && soScActive()) {
                // SoSc 双分屏 → 多分屏：直接用高层 startMultipleSplits 把"现有分组 + 新应用"一起铺成多分屏。
                // 之前尝试的 transferSoScToMultipleSplit(group, group.map{0}) 第二个参数系统要的是
                // AbsSplitStageTaskListener 列表（不是 Integer），实测会抛 ClassCastException，
                // 且该方法依赖系统拖拽上下文，从模块进程直接调不稳。startMultipleSplits 走 AIDL、
                // 由系统自己铺 stage，更可靠。
                val combined = ArrayList<Int>(group).apply { if (taskId > 0) add(taskId) }
                Logx.always("加分屏: SoSc→多分屏 改走 startMultipleSplits(ids=$combined, quickView=false)")
                startMultipleSplits(combined, null, quickView = false)
                return@runCatching
            }

            // WindowContainerTransaction 是 @hide，编译期看不到 → 运行时取
            val wctCls = Class.forName("android.window.WindowContainerTransaction", false, uiLoader)
            val wct = wctCls.getDeclaredConstructor().newInstance()
            sc.javaClass.getMethod(
                "insertMultipleSplitByTask", wctCls, Integer.TYPE, Integer.TYPE
            ).invoke(sc, wct, Integer.valueOf(taskId), Integer.valueOf(group.size))
            // 提交 WCT：ShellTaskOrganizer.applyTransaction(wct)。它挂在 controller / MultipleSplitController 上，
            // 字段名随版本变（mShellTaskOrganizer / mTaskOrganizer），所以按类型扫一遍字段。
            val orgInst = orgOf(ctl) ?: orgOf(sc)
            if (orgInst != null) {
                orgInst.javaClass.getMethod("applyTransaction", wctCls).invoke(orgInst, wct)
                Logx.always("加分屏: 已 applyTransaction（taskId=$taskId index=${group.size}）")
            } else {
                Logx.e("加分屏: 取不到 ShellTaskOrganizer，WCT 没能提交")
            }
        }.onFailure { Logx.e("加分屏失败", it) }
    }

    /** 在对象（含继承链）里找 ShellTaskOrganizer 实例。 */
    private fun orgOf(root: Any): Any? = runCatching {
        var c: Class<*>? = root.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!f.type.name.endsWith("ShellTaskOrganizer")) continue
                f.isAccessible = true
                (f.get(root) as? Any)?.let { return it }
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    /**
     * 当前是否"普通单应用全屏"。
     *
     * 真机演示确认的冲突：分屏/多分屏状态下，屏幕下部（尤其右侧斜滑）是 MIUI 自己的
     * 分屏热区，模块的角滑必须**完全退让**，只在单应用全屏时才接管。
     * 判定用的都是已封装的查询：`splitActive` / `soScActive` / 前台任务 windowingMode。
     */
    private fun isPlainFullscreen(): Boolean {
        if (splitActive() || soScActive()) return false
        val info = topTask() ?: return false
        return modeOf(info) == MODE_FULLSCREEN
    }

    /** `WINDOWING_MODE_FULLSCREEN`。 */
    private const val MODE_FULLSCREEN = 1

    /**
     * 当前分屏组里的任务 id。
     *
     * ⚠️ 只在**分屏真的在进行中**才返回内容：`getAllStageTaskInfo()` 会返回**历史遗留的 stage**
     * （真机实测：没有分屏时报 `SoSc=false 多分屏=false` 却给出 6 个 id，都是上次多分屏留下的），
     * 直接拿来当"当前分组"会导致候选被全部误排除、`enterSplitScreen` 拿到错参数返回 false。
     */
    private fun splitTaskIds(): List<Int> {
        if (!splitActive() && !soScActive()) return emptyList()
        return stageTaskIds()
    }

    private fun stageTaskIds(): List<Int> {
        // ⚠️ **不能用 `getAllStageTaskInfo()`**：它会把历史遗留的 stage 一起返回
        // （真机踩过两次：明明只在双分屏，却报"当前层数=6 组内=[6 个残留 id]"，
        //  于是 startMultipleSplits 拿到一堆过期 id → ok=true 但屏幕上没反应）。
        // 正确来源：仓库里**当前可见**的分屏子任务。
        runCatching {
            val repo = taskRepo() ?: return emptyList()
            val l = repo.javaClass.getMethod("getVisibleSplitChildTaskInfo").invoke(repo) as? List<*>
            val ids = l?.mapNotNull { it?.let { t -> taskIdOf(unwrap(t)).takeIf { id -> id > 0 } } } ?: emptyList()
            if (ids.isNotEmpty()) return ids
        }
        // 兜底 1：SoSc 左右 stage 里的任务
        runCatching {
            val soc = socUtils() ?: return@runCatching
            val ids = ArrayList<Int>()
            for (m in listOf("getLeftTopStage", "getRightBottomStage")) {
                val st = runCatching { soc.javaClass.getMethod(m).invoke(soc) }.getOrNull() ?: continue
                val rti = runCatching { st.javaClass.getMethod("getRunningTaskInfo").invoke(st) }.getOrNull() ?: continue
                taskIdOf(unwrap(rti)).takeIf { it > 0 }?.let { ids.add(it) }
            }
            if (ids.isNotEmpty()) return ids
        }
        // 兜底 2：多分屏的活跃 stage 列表
        runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return emptyList()
            val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl) ?: return emptyList()
            val l = sc.javaClass.getMethod("getActiveStageList").invoke(sc) as? List<*>
            l?.mapNotNull { st ->
                st?.let { s2 ->
                    runCatching { s2.javaClass.getMethod("getRunningTaskInfo").invoke(s2) }
                        .getOrNull()?.let { info -> taskIdOf(unwrap(info)).takeIf { id -> id > 0 } }
                }
            }?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }

    private fun soScActive(): Boolean = runCatching {
        val soc = socUtils() ?: return false
        (soc.javaClass.getMethod("isSoScActive").invoke(soc) as? Boolean) ?: false
    }.getOrDefault(false)

    /** shell 已知的全部运行任务（按 Z 序）。 */
    private fun allTasks(): List<Any> {
        val repo = taskRepo() ?: return emptyList()
        runCatching {
            val m = repo.javaClass.getMethod("getMultiTaskingTaskInfoList").invoke(repo)
            if (m is android.util.SparseArray<*>) {
                val out = ArrayList<Any>()
                for (i in 0 until m.size()) m.valueAt(i)?.let { out.add(unwrap(it)) }
                if (out.isNotEmpty()) return out
            }
        }
        runCatching {
            val l = repo.javaClass.getMethod("getMultiWindowTasksInZOrder").invoke(repo) as? List<*>
            l?.let { return it.filterNotNull().map { t -> unwrap(t) } }
        }
        return emptyList()
    }

    /**
     * 当前以「自由小窗 / 多窗口」(windowingMode 5/6) 存在的任务 id。
     *
     * 模块的"双分屏"首步用 `openWindowFromFullscreen` 把前台应用打成 mode=6 的自由半屏，
     * 它**不是**原生 SoSc / 多分屏（`soScActive()`/`splitActive()` 都 false），但用户确实处于
     * "半屏应用 + 另一侧桌面/选择"的二分布局。要把它升级成三分屏，四指上滑必须能识别这种状态、
     * 把这些自由窗任务当作基础交给 `startMultipleSplits`，再由系统弹选择器补剩下的槽位。
     */
    private fun freeformTasks(): List<Int> {
        val out = ArrayList<Int>()
        runCatching {
            for (t in allTasks()) {
                val id = taskIdOf(t)
                val mode = modeOf(t)
                if (id > 0 && (mode == 5 || mode == 6)) out.add(id)
            }
        }
        val seen = LinkedHashSet<Int>(); val res = ArrayList<Int>()
        for (i in out) if (seen.add(i)) res.add(i)
        return res
    }

    // ---------------- 任务 / 分屏查询 ----------------

    private fun splitActive(): Boolean = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return false
        val sc = ctl.javaClass.getMethod("getMultipleSplitController").invoke(ctl) ?: return false
        (sc.javaClass.getMethod("isMultipleSplitActive").invoke(sc) as? Boolean) ?: false
    }.getOrDefault(false)

    private fun taskRepo(): Any? = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null) ?: return null
        ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
    }.getOrNull()

    /** 前台任务：优先"可见的全屏任务"，其次 Z 序最上的多窗口任务。 */
    private fun topTask(): Any? {
        val repo = taskRepo() ?: return null
        runCatching {
            val l = repo.javaClass.getMethod("getVisibleFullTaskInfo").invoke(repo) as? List<*>
            l?.firstOrNull { it != null }?.let { return unwrap(it) }
        }
        runCatching {
            val l = repo.javaClass.getMethod("getMultiWindowTasksInZOrder").invoke(repo) as? List<*>
            l?.lastOrNull { it != null }?.let { return unwrap(it) }
        }
        return null
    }

    private fun taskIdOf(info: Any): Int = runCatching {
        (info.javaClass.getMethod("getTaskId").invoke(info) as? Int) ?: -1
    }.getOrDefault(-1)

    private fun modeOf(info: Any): Int = runCatching {
        (info.javaClass.getMethod("getWindowingMode").invoke(info) as? Int) ?: -1
    }.getOrDefault(-1)

    /**
     * 把仓库对象解包成真正的 `ActivityManager$RunningTaskInfo`。
     *
     * `MultiTaskingTaskRepository.getVisibleFullTaskInfo()` 返回的是 **`MultiTaskingTaskInfo`**
     * 包装对象（继承 `MultiTaskingBaseTaskInfo`），`topActivity`/`baseIntent` 都在它内层的
     * `mTaskInfo` 上 —— 真机日志：`class=...MultiTaskingTaskInfo fields[topActivity=null ...]`。
     * 解包入口：`getTaskInfo()`（→ `mTaskInfo` 字段）。
     */
    private fun unwrap(o: Any): Any {
        runCatching {
            o.javaClass.getMethod("getTaskInfo").invoke(o)?.let { if (isRunningTaskInfo(it)) return it }
        }
        declaredField(o, "mTaskInfo")?.let { if (isRunningTaskInfo(it)) return it }
        return o
    }

    /**
     * 只在真的是 `ActivityManager$RunningTaskInfo` 时才认。
     *
     * 真机踩过：某些 `MultiTaskingTaskInfo` 的 `getTaskInfo()` 返回的是 **Integer**（不是任务对象），
     * 不加判断就会把 Integer 当任务用，日志表现成
     * `角滑: 前台任务没有包名 … class=java.lang.Integer`。
     */
    private fun isRunningTaskInfo(o: Any): Boolean =
        o.javaClass.name == "android.app.ActivityManager\$RunningTaskInfo"

    /**
     * 取任务包名。`RunningTaskInfo` 的 `topActivity` / `baseActivity` 是**公开字段**
     * （真机反编译确认 `Landroid/app/ActivityManager$RunningTaskInfo;->topActivity:Landroid/content/ComponentName;`），
     * 并没有对应 getter —— 只按 getter 找会拿到 null（真机日志：「前台任务没有包名」）。
     */
    private fun pkgOf(raw: Any): String? {
        val info = unwrap(raw)
        listOf("getTopActivity", "getBaseActivity", "getRealActivity").forEach { g ->
            runCatching {
                (info.javaClass.getMethod(g).invoke(info) as? android.content.ComponentName)
                    ?.packageName?.let { if (it.isNotEmpty()) return it }
            }
        }
        listOf("topActivity", "baseActivity", "realActivity").forEach { f ->
            when (val v = declaredField(info, f)) {
                is android.content.ComponentName -> v.packageName?.let { if (it.isNotEmpty()) return it }
                is android.content.pm.ActivityInfo -> v.packageName?.let { if (it.isNotEmpty()) return it }
                is String -> if (v.isNotEmpty()) return v
            }
        }
        runCatching {
            val bi = (declaredField(info, "baseIntent")
                ?: info.javaClass.getMethod("getBaseIntent").invoke(info)) as? Intent
            bi?.component?.packageName?.let { if (it.isNotEmpty()) return it }
            bi?.`package`?.let { if (it.isNotEmpty()) return it }
        }
        return null
    }

    /** 沿继承链找字段（私有字段也能读）。 */
    private fun declaredField(o: Any, name: String): Any? = runCatching {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(o)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    /** 诊断：取不到包名时把对象的类名与候选字段/方法的存在性打出来（一次就能定位结构差异）。 */
    private fun describe(raw: Any): String {
        val o = unwrap(raw)
        val cls = o.javaClass
        val fields = listOf("topActivity", "baseActivity", "realActivity", "baseIntent", "taskDescription")
            .joinToString(",") { f -> "$f=" + (declaredField(o, f)?.javaClass?.simpleName ?: "null") }
        val methods = listOf("getTaskId", "getWindowingMode", "getTopActivity", "getBaseActivity", "getBaseIntent")
            .joinToString(",") { m ->
                val v = runCatching { cls.getMethod(m).invoke(o) }.getOrNull()
                "$m=" + (v?.javaClass?.simpleName ?: "null")
            }
        return "class=${cls.name} fields[$fields] methods[$methods]"
    }

    /** 内外屏切换 / 旋转后刷新屏幕尺寸与密度。 */
    fun refreshDisplay() {
        runCatching {
            val ctx = AppCtx.get() ?: return@runCatching
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels > 0) {
                screenW = dm.widthPixels
                screenH = dm.heightPixels
                density = dm.density
            }
        }
        if (screenW == 0) {
            screenW = 1672
            screenH = 2364
        }
    }
}
