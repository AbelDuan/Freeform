package com.abel.os4freeformx

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

/**
 * 新手势（SystemUI 进程）。单一功能共用一个全局输入源。
 *
 * **输入源**：MIUI 自己就有全局手势监视器 —— `MulWinSwitchEventController`（单例）用
 * `InputManager.monitorGestureInput` 拿到一条 InputMonitor，由内部类
 * `MulWinSwitchEventController$EventReceiver#onInputEvent(MotionEvent)` 收取全屏触摸，再分发给
 * 注册进来的 `EventHandler`。本模块直接挂它的 `onInputEvent`，**全屏 / 桌面 / 小窗 / 分屏**下的触摸
 * 都能看到，而且不需要任何额外权限（monitorGestureInput 已由 MIUI 在 SystemUI 里建好）。
 *
 * 一个手势：
 * 1. **角落斜滑**：屏幕下部左/右下角起手 → 向屏幕中心斜向滑动 → 松手时把当前前台应用转成小窗。
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
    // ★★ 方案一（用户 2026-10-01 拍板）：起手区 = **真角落的正方形**。
    //    边长 = 屏宽 1/6，**贴屏幕最底 + 贴屏幕外侧**（这才是"从角里滑出来"的手感）。
    //    全部按屏幕比例 ⇒ 内屏/外屏/不同机型一致（不写死 px/dp）。
    //
    //    ⚠️ 历史教训（我改坏过两次，都是自己没摸就交）：
    //      · 曾把"距底 1/8 屏高不起手"整片封掉 ⇒ R 角被自己封死，用户一次都摸不到；
    //      · 曾额外叠"底部正中 50% 让位" ⇒ 实际可起手区只剩一条窄缝。
    //    现在：**只要左右/底部让位区之外、且在最底那一块正方形内，就算起手**。
    private const val CORNER_ZONE_W = 1f / 6f     // 左右各 1/6 屏宽（底部左/右区，含 R 角）
    /**
     * ★ 起手区高度：**只取底部一条带，绝不向上延伸**（用户 2026-10-02 明确要求）。
     *   原因：竖边继续往上就是**小米侧边栏**的手势区，延伸上去会把侧边栏手势抢掉（用户实测）。
     *   现在整条起手区都在屏幕最底部 12% 屏高之内 ⇒ 与侧边栏完全不相干。
     */
    private const val CORNER_ZONE_H = 0.25f
    /** 底部正中让位：这一片留给 MIUI 自己的「底部中间上滑进多分屏 / 上滑回桌面」。 */
    private const val CENTER_KEEP_W = 0.34f       // 中间 34% 宽不起手
    private const val CENTER_KEEP_H = 0.12f       // 与起手区同高（最底一条带）

    /** 触发所需的最小滑动距离：按**屏幕对角线的比例**算，不写死 dp。 */
    private val cornerMinTravel get() = hypot(screenW.toFloat(), screenH.toFloat()) * 0.05f

    /**
     * 「滑到中间停住」判定时长。用户口径：**不强制角度**，滑到中间停住一会儿就切。
     * 260ms 实测太灵敏（刚滑到就触发）⇒ 放宽到 400ms。
     */
    private val cornerHoldMs get() = 300L

    /** 停住判定允许的抖动范围（按屏幕短边比例，不写死 dp）。 */
    private val cornerHoldSlop get() = minOf(screenW, screenH) * 0.008f


    @Volatile private var uiLoader: ClassLoader? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------------- 手势状态 ----------------
    private var cornerArmed = false
    private var cornerLeft = true
    private var cornerX = 0f
    private var cornerY = 0f
    private var cornerBad = false
    private var cornerTime = 0L
    private var cornerHoldAt = 0L        // 手指停住的起始时刻（0=还在动）
    private var cornerLastX = 0f
    private var cornerLastY = 0f


    /** 命中后本串事件不再交给 MIUI 自己的手势逻辑（避免它再解释一遍）。 */
    @Volatile private var ownMonitor: Any? = null
    @Volatile private var probeCount = 0
    @Volatile private var lastDispRefresh = 0L
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
        // ① 输入源：挂 MIUI 的 MulWinSwitchEventController#onInputEvent。
        //    ★ 2026-10-02 实测结论（别再走回头路）：
        //      · 自建 InputMonitorCompat 通道**在本机收不到任何事件**（探针 0 条）⇒ 用户怎么摸都没反应 ✗
        //      · 只有这条 MIUI 入口**确实有事件**（曾稳定产出「角滑命中」日志）✓
        //    本方案**只旁观、不吞**：钩子永远返回 chain.proceed()（原样放行）⇒
        //      不屏蔽任何系统手势（用户 2026-10-02 明确要求：不要屏蔽）；
        //      ANR 风险靠"判定保持纯算术 + 触发异步"压住（重活绝不在输入线程做）。
        val hooked = m.hookMethod(cl, CLS_EVENT_RECEIVER, "onInputEvent",
            arrayOf(android.view.InputEvent::class.java),
            XposedInterface.Hooker { chain ->
                try {
                    val ev = chain.getArg(0) as? MotionEvent
                    if (ev != null && Cfg.gestures) {
                        if (probeCount < 8) {
                            probeCount++
                            Logx.always(
                                "探针: 收到事件 action=${ev.actionMasked} x=${ev.rawX.toInt()} y=${ev.rawY.toInt()} " +
                                    "屏=${screenW}x${screenH}"
                            )
                        }
                        onMotion(ev)
                    }
                } catch (t: Throwable) {
                    Logx.e("手势处理失败", t)
                }
                // ★ 永远放行：不吞事件 ⇒ 不屏蔽系统手势（底部上滑回桌面/多分屏照常）
                chain.proceed()
            })

        // ② 注册 EventHandler + 确保 receiver 存在（全屏场景下 MIUI 可能还没建 receiver）
        main.post { ensureReceiver() }
        Logx.always(
            "installGestures: onInputEvent 挂载=$hooked（gestures=${Cfg.gestures} " +
                "屏=${screenW}x${screenH} 密度=$density）"
        )
    }

    /**
     * 触发瞬间的震动反馈（用户要求「滑出去停顿一会儿 → 震动一下 → 生效」）。
     * 取系统 Vibrator；取不到/被拒都**只记日志**，绝不影响功能（震动是体验，不是必需）。
     */
    private fun buzz() = runCatching {
        val ctx = AppCtx.get() ?: return@runCatching
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return@runCatching
        if (!vib.hasVibrator()) return@runCatching
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(35L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(35L)
        }
    }.onFailure { Logx.e("角滑: 震动失败", it) }

    /**
     * 自建输入通道（角滑专用）。
     *
     * 复用 SystemUI 的 `InputMonitorCompat`（小白条已在本机验证可用）：
     *   · 事件来自**独立** monitor，不经过 MIUI 的 `MulWinSwitchEventController` ⇒ 不再给那条流添负载；
     *   · 在本方法的调用线程（SystemUI 主线程）里只做**极轻**的分发，重活一律 `main.post`；
     *   · `monitorGestureInput` 是"只读旁观"型：我们吞掉自己的事件**不影响** MIUI 的派发，
     *     所以区域外的官方手势（底部上滑回桌面 / 多分屏 / 上滑到左上角）完全不受影响。
     *
     * @return 是否挂载成功（失败时退回空实现，角滑开关会显示为不可用而不是崩）
     */
    private fun installOwnInputMonitor(cl: ClassLoader): Boolean = runCatching {
        val monitorType = Class.forName(
            "com.android.systemui.shared.system.InputMonitorCompat", false, cl
        )
        val listenerType = Class.forName(
            "com.android.systemui.shared.system.InputChannelCompat\$InputEventListener", false, cl
        )
        val listener = Proxy.newProxyInstance(cl, arrayOf(listenerType)) { _, method, args ->
            if (method?.name == "onInputEvent" && args != null && args.size == 1 && args[0] is MotionEvent) {
                try {
                    val ev = args[0] as MotionEvent
                    // 探针：前几条事件打出来，确认自建通道**真的在派发**（起手=0 时必须靠它判断）
                    if (probeCount < 8) {
                        probeCount++
                        Logx.always(
                            "探针: 通道收到事件 action=${ev.actionMasked} x=${ev.rawX.toInt()} y=${ev.rawY.toInt()} " +
                                "屏=${screenW}x${screenH} gestures=${Cfg.gestures}"
                        )
                    }
                    if (Cfg.gestures) onMotion(ev)
                } catch (t: Throwable) {
                    Logx.e("角滑: 输入事件处理失败", t)
                }
            }
            null
        }
        val monitor = monitorType.getConstructor(String::class.java, Integer.TYPE)
            .newInstance("os4ffx-corner", 0)
        monitorType.getMethod(
            "getInputReceiver", Looper::class.java, Choreographer::class.java, listenerType
        ).invoke(monitor, Looper.getMainLooper(), Choreographer.getInstance(), listener)
        ownMonitor = monitor
        Logx.always("角滑: 已自建输入通道（os4ffx-corner，不再依赖 MIUI 手势流）")
        true
    }.onFailure { Logx.e("角滑: 自建输入通道失败", it) }.getOrDefault(false)

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
            // ⚠️ 死锁保护：swallow 置位后，若超过 700ms 仍没收到 UP/CANCEL（多指通道不稳定时会发生），
            // 自动解除，避免之后所有手势都被吞掉。
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
        // ★ 每次按下都按当前显示重算一次尺寸：内外屏切换后立即生效，不依赖启动快照
        //   （真机曾出现"展开态仍按外屏 1168 算"⇒ 起手区宽度偏窄、真人摸不到）
        if (android.os.SystemClock.uptimeMillis() - lastDispRefresh > 2000) {
            lastDispRefresh = android.os.SystemClock.uptimeMillis()
            refreshDisplay()
        }
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val x = ev.rawX
        val y = ev.rawY
        // ① 底部正中让位（只让中间那一块，且只在最底 1/8 高之内）：
        //    MIUI 自己的「底部中间上滑进多分屏 / 上滑回桌面」就在这一片。
        val half = CENTER_KEEP_W / 2f
        val inBottomCenter = y >= h * (1f - CENTER_KEEP_H) &&
            x > w * (0.5f - half) && x < w * (0.5f + half)
        // ② ★ 起手区 = 底部左/右区（各 1/6 屏宽）+ 最底一段高度，**不向上延伸**（不碰小米侧边栏）
        if (inBottomCenter && y >= h * (1f - CENTER_KEEP_H)) {
            Logx.once("corner-skip-center", "手势: 起手被跳过（底部正中让位区）@${x.toInt()},${y.toInt()}")
        }
        if (!inBottomCenter && y >= h * (1f - CORNER_ZONE_H)) {
            val left = x <= w * CORNER_ZONE_W
            val right = x >= w * (1f - CORNER_ZONE_W)
            if (left || right) {
                cornerArmed = true
                cornerLeft = left
                cornerX = x
                cornerY = y
                cornerLastX = x
                cornerLastY = y
                cornerHoldAt = 0L
                Logx.always("手势: 角滑起手 ✓ 侧=${if (left) "左" else "右"} @${x.toInt()},${y.toInt()} 屏=${screenW}x$screenH（区内=底${(h*(1-CORNER_ZONE_H)).toInt()}以下）")
                cornerTime = ev.eventTime
                Logx.v("手势: 角滑起手 侧=${if (left) "左" else "右"} @${x.toInt()},${y.toInt()} 屏=${screenW}x$screenH")
            }
        }
        return false
    }

    private fun onPointerDown(ev: MotionEvent): Boolean {
        if (cornerArmed) cornerBad = true           // 角滑只认单指
        return false
    }

    private fun onPointerUp(ev: MotionEvent): Boolean {
        if (cornerArmed) cornerBad = true
        return false
    }


    /**
     * 移动：**不强制角度**（用户口径）。只做两件事：
     *  ① 排除明显的"反方向"（往屏幕外侧 / 明显下滑）与多指；
     *  ② 维护「停住」计时——手指几乎不动时开始计时，一动就清零。
     * 触发展开发生在 [onUp]（抬起）或停够时长（见 [cornerCheckHold]）。
     */
    /**
     * 移动：**热路径，只做纯算术**（不查窗口状态、不打日志、不碰 binder）。
     * 2026-10-02 真机教训：这条路径上一旦有重活，就会拖慢输入派发 → ANR → SystemUI 被杀。
     */
    private fun onMove(ev: MotionEvent): Boolean {
        if (swallow) return true
        if (cornerArmed && !cornerBad) {
            val dx = ev.rawX - cornerX
            val dy = ev.rawY - cornerY
            val inward = if (cornerLeft) dx else -dx
            if (ev.pointerCount > 1) cornerBad = true
            else if (inward < -dp(30f)) cornerBad = true      // 往屏幕外侧滑
            else if (dy > dp(30f)) cornerBad = true           // 明显下滑
            else {
                // 停住判定：与上一次采样点的位移
                val moved = hypot(ev.rawX - cornerLastX, ev.rawY - cornerLastY)
                val now = android.os.SystemClock.uptimeMillis()
                if (moved <= cornerHoldSlop) {
                    if (cornerHoldAt == 0L) cornerHoldAt = now
                    cornerCheckHold(now)
                } else {
                    cornerHoldAt = 0L
                }
                cornerLastX = ev.rawX
                cornerLastY = ev.rawY
            }
        }
        return false
    }

    /**
     * 「滑到中间停住 → 切小窗」。条件（用户口径）：
     *   · 从 R 角起手（cornerArmed）
     *   · 往屏幕中心方向已滑够 [cornerMinTravel]
     *   · 手指停住超过 [cornerHoldMs]
     * 命中即吞掉后续事件并转小窗（`swallow` 兜底 700ms 自动解除，防多指通道不投 UP 时死锁）。
     */
    private fun cornerCheckHold(now: Long) {
        if (cornerHoldAt == 0L) return
        if (now - cornerHoldAt < cornerHoldMs) return
        val dx = cornerLastX - cornerX
        val dy = cornerLastY - cornerY
        val dist = hypot(dx, dy)
        val inward = if (cornerLeft) dx else -dx
        if (dist < cornerMinTravel || inward <= 0f) {
            cornerHoldAt = 0L
            return
        }
        // ⚠️ 到这一步才允许做"是否全屏"这类**查询**（它在热路径上，但一帧最多走到这里一次，
        //    且立刻 return/吞事件，不会像以前那样每个 MOVE 都查）。
        if (!Cfg.cornerFreeform) { cornerHoldAt = 0L; return }
        if (!isPlainFullscreen()) {
            // 不在全屏（分屏/已有小窗）⇒ 原样放行给系统，绝不能吞
            Logx.always("手势: 角滑命中但当前不是单应用全屏（分屏/小窗），放行给系统")
            cornerHoldAt = 0L
            return
        }
        Logx.always(
            "手势: 角滑命中(R角起手→中间停住) 侧=${if (cornerLeft) "左" else "右"} 行程=${dist.toInt()}px " +
                "dx=${dx.toInt()} dy=${dy.toInt()} 停=${now - cornerHoldAt}ms 开关=${Cfg.cornerFreeform}"
        )
        run {
            // ⚠️⚠️ 这里**绝不能**直接调 cornerSwipeToFreeform()（起小窗是跨进程的重活）：
            //    本函数是从 onMove → 输入派发线程里调进来的，占住输入线程就会让下一条 MOVE 派发超时
            //    → `ANR in com.android.systemui [Gesture Monitor] MultiTaskSwitch ... action=MOVE`
            //    → SystemUI 被杀（2026-10-02 真机连崩三次，pid 2899/6773/10357 全死在这个点上），
            //      表现就是用户说的「触发后小窗无法操作 + 大概率 SystemUI 重启」。
            //    所以：**只置标志 + 吞事件，然后异步投递到主线程再动手**。
            swallowAt = android.os.SystemClock.uptimeMillis()
            cornerHoldAt = 0L
            reset()
            Logx.always("手势: 角滑已触发，异步投递切小窗（输入线程立即返回）")
            buzz()
            main.post { runCatching { cornerSwipeToFreeform() }.onFailure { Logx.e("角滑切小窗失败", it) } }
        }
    }

    private fun onUp(ev: MotionEvent): Boolean {
        // ⚠️ 这里是 MIUI 全局手势监视器的**输入派发线程**：任何同步 binder 都可能让它错过派发时限
        //    → `ANR in com.android.systemui [Gesture Monitor] MultiTaskSwitch ... MotionEvent(action=UP)`
        //    → SystemUI 被杀（2026-10-01 真机复现两次）。而 reload() 每次都同步走 LSPosed
        //    `getRemotePreferences`；后果是"抬手那一刻窗口连同刚拖好的位置一起没了"，
        //    看起来就像"位置记不住"。所以这里只允许**异步**刷新：本次判定用上一份快照。
        runCatching { Cfg.reloadAsync() }
        var consumed = false
        if (cornerArmed && !cornerBad) {
            val dx = ev.rawX - cornerX
            val dy = ev.rawY - cornerY
            val dist = hypot(dx, dy)
            val inward = if (cornerLeft) dx else -dx
            // ★ 用户口径：**不强制角度**。只要从 R 角出发、朝屏幕中心滑够距离即可。
            if (dist >= cornerMinTravel && inward > dp(40f)) {
                Logx.always(
                    "手势: 角滑命中(抬起) 侧=${if (cornerLeft) "左" else "右"} 行程=${dist.toInt()}px " +
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
                    consumed = false   // ★ 不吞事件（用户要求：不屏蔽系统手势）
                    // 同上：输入线程上只置标志，动手交给主线程（避免 ANR → SystemUI 被杀）
                    Logx.always("手势: 角滑已触发(抬起)，异步投递切小窗")
                    buzz()
                    main.post { runCatching { cornerSwipeToFreeform() }.onFailure { Logx.e("角滑切小窗失败", it) } }
                } else if (Cfg.cornerFreeform) {
                    Logx.always("手势: 角滑命中但当前不是单应用全屏（分屏/小窗），放行给系统")
                }
            } else {
                Logx.v("手势: 角滑未命中 行程=${dist.toInt()} dx=${dx.toInt()} dy=${dy.toInt()} bad=$cornerBad")
            }
        }
        reset()
        return consumed
    }

    private fun reset() {
        cornerArmed = false
        cornerBad = false
        cornerHoldAt = 0L
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

    private fun socUtils(): Any? = runCatching {
        Class.forName("com.android.wm.shell.sosc.SoScUtils", false, uiLoader)
            .getMethod("getInstance").invoke(null)
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


    private fun soScActive(): Boolean = runCatching {
        val soc = socUtils() ?: return false
        (soc.javaClass.getMethod("isSoScActive").invoke(soc) as? Boolean) ?: false
    }.getOrDefault(false)

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

    /** 前台任务：优先框架标准接口 ActivityTaskManager.getTasks（跨 MIUI 固件稳定），其次 MIUI 仓库兜底。 */
    private fun topTask(): Any? {
        // 本平板固件（turner / HyperOS 4）的 MIUI 多任务仓库取不到前台全屏任务
        // （getVisibleFullTaskInfo 空、getMultiWindowTasksInZOrder 返回 List<Integer 且不含前台应用）。
        // Android 17 上 ActivityManager.getTasks(int) 已移除，改用 ActivityTaskManager.getTasks，
        // 其返回真 RunningTaskInfo，systemui（system uid）有调用权限。
        topTaskViaAtm()?.let { return it }
        // 兜底：MIUI 仓库（部分固件可用）
        val repo = taskRepo() ?: return null
        runCatching {
            val l = repo.javaClass.getMethod("getVisibleFullTaskInfo").invoke(repo) as? List<*>
            l?.firstOrNull { it != null && isRunningTaskInfo(unwrap(it)) }?.let { return unwrap(it) }
        }
        runCatching {
            val l = repo.javaClass.getMethod("getMultiWindowTasksInZOrder").invoke(repo) as? List<*>
            l?.lastOrNull { it != null && isRunningTaskInfo(unwrap(it)) }?.let { return unwrap(it) }
        }
        return null
    }

    /** 通过 ActivityTaskManager.getTasks 取最上的真实前台任务（RunningTaskInfo）。 */
    private fun topTaskViaAtm(): Any? {
        return runCatching {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val atm = atmCls.getMethod("getInstance").invoke(null) ?: return@runCatching null
            // 候选签名（不同固件略有差异）：getTasks(int,boolean,boolean) / (int,int,boolean) / (int,boolean)
            val tries = listOf(
                arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false, false),
                arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, 0, false),
                arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false),
            )
            var found: Any? = null
            for ((sig, args) in tries) {
                if (found != null) break
                runCatching {
                    val m = atmCls.getMethod("getTasks", *sig)
                    val tasks = m.invoke(atm, *args) as? List<*> ?: return@runCatching
                    for (e in tasks) {
                        val t = e as? android.app.ActivityManager.RunningTaskInfo ?: continue
                        val tn = t.topActivity ?: t.baseActivity
                        val pkg = tn?.packageName
                        if (pkg != null && pkg != "com.miui.home" && !pkg.endsWith(".launcher") && pkg != "android") {
                            found = t
                            return@runCatching
                        }
                    }
                }
            }
            found
        }.getOrNull().also { if (it == null) Logx.e("topTask: ActivityTaskManager.getTasks 未取得前台") }
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
            // ★ 2026-10-02 真机 bug：折叠屏展开态下 `defaultDisplay` 仍可能给外屏几何
            //   （实测回来的是 1168x1712，而机器在内屏 1672x2364）⇒ 起手区按错的宽度算，
            //   用户在内屏摸的位置会落在"区外"（我合成坐标能中、真人摸不到）。
            //   用 DisplayManager 取**默认显示**的真实尺寸；失败再退回老办法。
            val ok = runCatching {
                val dmgr = ctx.getSystemService(Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager
                val d = dmgr?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (d != null) {
                    val p = android.graphics.Point()
                    d.getRealSize(p)
                    if (p.x > 0 && p.y > 0) {
                        dm.widthPixels = p.x
                        dm.heightPixels = p.y
                        dm.density = ctx.resources.displayMetrics.density
                        true
                    } else false
                } else false
            }.getOrDefault(false)
            if (!ok) {
                @Suppress("DEPRECATION")
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getRealMetrics(dm)
            }
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                // ★★ 归一化（2026-10-02 真机 bug）：`getRealMetrics` 在旋转/折叠时给出的是**物理**宽高，
                //    本机实测回来的是 2364x1672（横置）。如果直接当"宽/高"用：
                //      · 右侧起手区起点 = w*(1-1/6) = 1970，而实际屏宽只有 1672 ⇒ **右角永远命中不到**（实测 0/2）
                //      · 左侧起点是 0，所以左角正常 —— 正好对上"左边能滑、右边滑不动"
                //    统一成「短边=宽、长边=高」，与旋转无关（和记忆 key 的 `短边x长边` 同一思路）。
                screenW = minOf(dm.widthPixels, dm.heightPixels)
                screenH = maxOf(dm.widthPixels, dm.heightPixels)
                density = dm.density
            }
        }
        if (screenW == 0) {
            screenW = 1672
            screenH = 2364
        }
    }
}
