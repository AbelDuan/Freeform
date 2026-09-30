package com.abel.os4freeformx

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.app.KeyguardManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.comparisons.maxOf
import kotlin.comparisons.minOf
import kotlin.math.roundToInt

/**
 * 小白条（手势导航条）引擎：跟随手势 / 淡入淡出 / 触摸显隐 / 空闲自动隐藏。
 *
 * 源自 HyperModifier 的 GestureHandle 子系统（精简核心，去掉逐应用 preset / rules / scope / UI）。
 * hook 目标：NavigationHandle / QuickswitchOrientedNavHandle（绘制）、NavigationBarFrame（触摸监听）、
 * MiuiTopActivityObserver（前台变化）、NavigationBarControllerImpl（宿主保活）。
 * 透明度用 saveLayerAlpha，跟随手势用阻尼弹簧位移；原生导航输入 / 动画 / insets 一律不动。
 *
 * 纪律：所有 hook 走 [MainHook.hookExecutable]（PROTECTIVE + try/catch）；配置读 [Cfg] volatile 字段。
 */
object GestureHandle {

    private const val HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle"
    private const val ROTATED = "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle"
    private const val TOP_OBSERVER = "com.miui.systemui.functions.MiuiTopActivityObserver"
    private const val FRAME = "com.android.systemui.navigationbar.views.NavigationBarFrame"
    private const val CONTROLLER = "com.android.systemui.navigationbar.NavigationBarControllerImpl"

    private val POLICY = GestureHandlePolicy()
    private val MOTION = GestureHandleMotion()
    private val installed = AtomicBoolean(false)
    private val HANDLES = Collections.synchronizedMap(WeakHashMap<View, HandleVisual?>())

    private var loader: ClassLoader? = null
    private var main: Handler? = null
    private var runtimeContext: Context? = null
    private var controller: WeakReference<Any?> = WeakReference(null)
    private var receiverInstalled = false
    private var inputMonitorInstalled = false
    private var inputMonitor: Any? = null
    private var inputReceiver: Any? = null
    private var stockHidden = false
    private var lastEnabled = true
    private var activeTouchHandle: View? = null
    private var activeSwipeRevealed = false

    private val DETACHED_GESTURE_TIMEOUT = Runnable { finishActiveGesture(SystemClock.uptimeMillis()) }
    private val HIDE = Runnable { invalidateHandles() }

    /** 安装全部 hook（幂等：每个 SystemUI 进程只装一次；开关在运行时经 [Cfg] 实时判断）。 */
    fun install(m: MainHook, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        loader = cl
        Cfg.reload()
        try {
            val handle = Class.forName(HANDLE, false, cl)
            hook(m, handle.getDeclaredMethod("onDraw", Canvas::class.java)) { chain -> draw(chain) }
            // OS3 走 View 继承的方法而非自己声明；首帧绘制同样是合法的宿主登记点，生命周期 hook 可选。
            try {
                hook(m, handle.getDeclaredMethod("onAttachedToWindow")) { chain ->
                    val result = chain.proceed()
                    if (Cfg.gestureHandle) trackHandle(chain.getThisObject() as View)
                    result
                }
                hook(m, handle.getDeclaredMethod("onDetachedFromWindow")) { chain ->
                    val view = chain.getThisObject() as View
                    HANDLES.remove(view)
                    if (activeTouchHandle === view) {
                        main?.removeCallbacks(DETACHED_GESTURE_TIMEOUT)
                        main?.postDelayed(DETACHED_GESTURE_TIMEOUT, 750L)
                    }
                    chain.proceed()
                }
            } catch (ignored: NoSuchMethodException) {
                // OS3 的 NavigationHandle 没有生命周期 override。
            }
        } catch (t: Throwable) {
            Logx.e("小白条: draw hook 不可用", t)
        }
        try {
            val observer = Class.forName(TOP_OBSERVER, false, cl)
            for (method in observer.declaredMethods) {
                if (method.name != "updateTopActivity") continue
                val parameters = method.parameterTypes
                val taskArg = parameters.size - 1
                if (taskArg < 0 || parameters[taskArg] != ActivityManager.RunningTaskInfo::class.java) continue
                hook(m, method) { chain ->
                    val result = chain.proceed()
                    dispatch { foreground() }
                    result
                }
            }
        } catch (t: Throwable) {
            Logx.e("小白条: foreground hook 不可用", t)
        }
        try {
            // 横屏快速切换用覆盖式绘制方法而非基础 pill。
            val rotated = Class.forName(ROTATED, false, cl)
            hook(m, rotated.getDeclaredMethod("onDraw", Canvas::class.java)) { chain -> draw(chain) }
        } catch (t: Throwable) {
            Logx.e("小白条: 旋转小白条 hook 不可用", t)
        }
        try {
            val frame = Class.forName(FRAME, false, cl)
            hook(m, frame.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)) { chain ->
                try {
                    observeTouch(chain.getThisObject() as View, chain.getArg(0) as MotionEvent)
                } catch (t: Throwable) {
                    Logx.e("小白条: 触摸监听不可用", t)
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            Logx.e("小白条: 触摸监听 hook 不可用", t)
        }
        try {
            installHostHooks(m, cl)
        } catch (t: Throwable) {
            Logx.e("小白条: host override 不可用", t)
        }
    }

    private fun draw(chain: XposedInterface.Chain): Any? {
        // 拉模型：每帧顺带刷新配置（节流），并在总开关翻转时重新套用（启动/停止输入监听、保活宿主）。
        Cfg.reloadThrottled()
        if (Cfg.gestureHandle != lastEnabled) {
            lastEnabled = Cfg.gestureHandle
            refresh()
        }
        if (!Cfg.gestureHandle) return chain.proceed()
        val view = chain.getThisObject() as View
        val display = view.display
        if (display == null || display.displayId != 0) return chain.proceed()
        trackHandle(view)
        val now = SystemClock.uptimeMillis()
        val hidden = if (appliesTo(view)) POLICY.hidden(now, stockHidden) else stockHidden
        val visual: HandleVisual
        synchronized(HANDLES) {
            visual = HANDLES[view] ?: HandleVisual(hidden).also { HANDLES[view] = it }
        }
        val alpha = visual.fade.alpha(
            hidden, now, Cfg.gestureHandleFollow && POLICY.swipeRevealActive(now)
        )
        if (visual.fade.running(now) || MOTION.running(now)) view.postInvalidateOnAnimation()
        if (alpha <= 0f) return null
        // 只对原生 pill 绘制套透明度。View alpha、原生导航 / 快速切换动画、触摸处理、insets 一律不动。
        val canvas = chain.getArg(0) as Canvas
        val save = if (alpha >= 1f) canvas.save()
        else canvas.saveLayerAlpha(
            0f, 0f, view.width.toFloat(), view.height.toFloat(), (alpha * 255f).roundToInt()
        )
        try {
            if (Cfg.gestureHandleFollow && appliesTo(view)) {
                canvas.translate(MOTION.x(now), MOTION.y(now))
            }
            return chain.proceed()
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun trackHandle(view: View) {
        synchronized(HANDLES) {
            if (HANDLES.containsKey(view)) return
            HANDLES[view] = null
        }
        MOTION.rebaseForNewHost(SystemClock.uptimeMillis())
        initialize(view.context)
        Cfg.reload()
        refresh()
    }

    private fun observeTouch(frame: View?, event: MotionEvent) {
        if (!Cfg.gestureHandle
            || (!Cfg.gestureHandleTouch && !Cfg.gestureHandleFollow)
            || (frame != null && !appliesTo(frame))
        ) return
        val action = event.actionMasked
        val now = SystemClock.uptimeMillis()
        main?.removeCallbacks(DETACHED_GESTURE_TIMEOUT)
        if (action == MotionEvent.ACTION_DOWN) {
            if (activeTouchHandle != null) finishActiveGesture(now)
            val handle = handleAt(frame, event.rawX, event.rawY)
            activeTouchHandle = handle
            activeSwipeRevealed = false
            if (handle == null || !appliesTo(handle)) return
            if (Cfg.gestureHandleTouch) {
                POLICY.touchDown(now)
                scheduleHide()
            }
            if (Cfg.gestureHandleFollow) {
                MOTION.start(event.rawX, event.rawY, now)
            }
            invalidateHandles()
        } else {
            val handle = activeTouchHandle ?: return
            val terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN
            if (Cfg.gestureHandleTouch) {
                if (terminal) {
                    POLICY.touchUp(now)
                    scheduleHide()
                } else {
                    POLICY.touchEvent(now)
                }
            }
            if (action == MotionEvent.ACTION_MOVE && Cfg.gestureHandleFollow) {
                revealForSwipe(handle, MOTION, event, now)
                if (activeSwipeRevealed) POLICY.swipeEvent(now)
                updateMotion(handle, MOTION, event)
                invalidateHandles()
            } else if (terminal) {
                if (Cfg.gestureHandleFollow && action != MotionEvent.ACTION_POINTER_DOWN) {
                    revealForSwipe(handle, MOTION, event, now)
                    updateMotion(handle, MOTION, event)
                }
                finishActiveGesture(now)
            }
        }
    }

    private fun finishActiveGesture(now: Long) {
        val handle = activeTouchHandle ?: return
        main?.removeCallbacks(DETACHED_GESTURE_TIMEOUT)
        if (Cfg.gestureHandleTouch) POLICY.touchUp(now)
        if (activeSwipeRevealed) POLICY.swipeUp(now)
        MOTION.release(now)
        activeTouchHandle = null
        activeSwipeRevealed = false
        scheduleHide()
        invalidateHandles()
    }

    private fun revealForSwipe(handle: View, motion: GestureHandleMotion, event: MotionEvent, now: Long) {
        if (activeSwipeRevealed) return
        val slop = ViewConfiguration.get(handle.context).scaledTouchSlop
        if (!motion.movedBeyond(event.rawX, event.rawY, slop.toFloat())) return
        activeSwipeRevealed = true
        POLICY.swipeDown(now)
        scheduleHide()
    }

    private fun updateMotion(handle: View, motion: GestureHandleMotion, event: MotionEvent) {
        val pill = pillRect(handle) ?: return
        val density = handle.resources.displayMetrics.density
        val margin = 2f * density
        val maxHorizontal = 24f * density
        motion.move(
            event.rawX, event.rawY,
            minOf(maxHorizontal, maxOf(0f, pill.left - margin)),
            minOf(maxHorizontal, maxOf(0f, handle.width - pill.right - margin)),
            maxOf(0f, pill.top - margin),
            maxOf(0f, handle.height - pill.bottom - margin),
            SystemClock.uptimeMillis()
        )
    }

    private fun handleAt(frame: View?, rawX: Float, rawY: Float): View? {
        val views = synchronized(HANDLES) { ArrayList(HANDLES.keys) }
        for (view in views) {
            if (view == null || !view.isAttachedToWindow || view.visibility != View.VISIBLE
                || view.width <= 0 || view.height <= 0
            ) continue
            var p = view.parent
            while (p != null && p !== frame) p = (p as? View)?.parent
            if (frame != null && p !== frame) continue
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val density = view.resources.displayMetrics.density
            val pill = pillRect(view)
            val onPill = pill != null && rawX >= location[0] + pill.left - 12f * density
                    && rawX <= location[0] + pill.right + 12f * density
                    && rawY >= location[1] + pill.top - 12f * density
                    && rawY <= location[1] + pill.bottom + 12f * density
            val display = view.display ?: continue
            val size = Point()
            display.getRealSize(size)
            val inBottomGestureArea = GestureHandleTouchArea.contains(
                rawX, rawY, size.x, size.y, (location[1] + view.height).toFloat(), density,
                GestureHandleTouchArea.DEFAULT_DP
            )
            if (onPill || inBottomGestureArea) return view
        }
        return null
    }

    private fun pillRect(handle: View): RectF? {
        return try {
            handle.javaClass.getMethod("getPillRect").invoke(handle) as? RectF
        } catch (ignored: ReflectiveOperationException) {
            // OS3 跨整个 handle 宽度绘制，不再暴露 getPillRect()。
            val radiusValue = fieldValue(handle, "mRadius")
            val bottomValue = fieldValue(handle, "mBottom")
            if (radiusValue !is Number || bottomValue !is Number) return null
            val radius = radiusValue.toFloat()
            val bottom = bottomValue.toFloat()
            val bottomEdge = handle.height - bottom
            RectF(0f, bottomEdge - 2f * radius, handle.width.toFloat(), bottomEdge)
        }
    }

    private class HandleVisual(hidden: Boolean) {
        val fade = GestureHandleFade(hidden)
    }

    private fun installHostHooks(m: MainHook, cl: ClassLoader) {
        val type = Class.forName(CONTROLLER, false, cl)
        for (method in type.declaredMethods) {
            if (method.name == "createNavigationBar") {
                hook(m, method) { chain ->
                    val owner = chain.getThisObject() as Any
                    controller = WeakReference<Any?>(owner)
                    val context = fieldValue(owner, "mContext") as? Context
                    if (context != null && Cfg.gestureHandle) initialize(context)
                    val injector = injector(owner)
                    stockHidden = bool(injector, "mHideGestureLine")
                    val override = Cfg.gestureHandle && bool(injector, "mIsFsgMode") && stockHidden
                    // 即便系统全局隐藏手势条，也保留一个原生宿主（绝不写 Settings.Global / 改导航模式）。
                    if (override) setHiddenFlag(injector, false)
                    try {
                        return@hook chain.proceed()
                    } finally {
                        if (override) {
                            setHiddenFlag(injector, true)
                            stockHidden = true
                            invalidateHandles()
                        }
                    }
                }
            } else if (method.name == "removeNavigationBar") {
                hook(m, method) { chain ->
                    val owner = chain.getThisObject()
                    val injector = injector(owner)
                    stockHidden = bool(injector, "mHideGestureLine")
                    val result = chain.proceed()
                    // 允许原生拆卸（含主题 / 折叠变化），再用原生创建路径重建（仍检查 display 与极小屏支持）。
                    if ((chain.getArg(0) as Number).toInt() == 0
                        && Cfg.gestureHandle && bool(injector, "mIsFsgMode") && stockHidden
                    ) {
                        Handler(Looper.getMainLooper()).post { refresh() }
                    }
                    result
                }
            }
        }
    }

    private fun hook(m: MainHook, method: Method, hooker: XposedInterface.Hooker): Boolean =
        m.hookExecutable(method, hooker)

    private fun initialize(context: Context) {
        if (receiverInstalled) return
        main = Handler(Looper.getMainLooper())
        runtimeContext = context.applicationContext ?: context
        receiverInstalled = true
        installInputMonitor()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (!Cfg.gestureHandle) return
                if (Intent.ACTION_USER_PRESENT == intent?.action) {
                    POLICY.reveal(SystemClock.uptimeMillis())
                    scheduleHide()
                } else {
                    POLICY.clearTouchReveal()
                    POLICY.clearSwipeReveal()
                    clearMotion()
                    activeTouchHandle = null
                    main?.removeCallbacks(DETACHED_GESTURE_TIMEOUT)
                    main?.removeCallbacks(HIDE)
                }
                invalidateHandles()
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED)
        // 安装可能晚于 observer 的首个事件，先做一次展示快照。
        POLICY.reveal(SystemClock.uptimeMillis())
    }

    private fun installInputMonitor() {
        if (!Cfg.gestureHandle || hasInputMonitor() || loader == null) return
        var monitor: Any? = null
        try {
            // 导航条窗口收不到每条手势输入流；用与 SystemUI 相同的被动监听包装，不截获、不消费事件。
            val monitorType = Class.forName(
                "com.android.systemui.shared.system.InputMonitorCompat", false, loader
            )
            val listenerType = Class.forName(
                "com.android.systemui.shared.system.InputChannelCompat\$InputEventListener", false, loader
            )
            val listener = Proxy.newProxyInstance(loader, arrayOf(listenerType)) { _, method, args ->
                if (method?.name == "onInputEvent" && args != null && args.size == 1 && args[0] is MotionEvent) {
                    try {
                        observeTouch(null, args[0] as MotionEvent)
                    } catch (t: Throwable) {
                        Logx.e("小白条: 被动输入监听不可用", t)
                    }
                }
                null
            }
            monitor = monitorType.getConstructor(String::class.java, Integer.TYPE)
                .newInstance("os4ffx-gesture-handle", 0)
            val receiver = monitorType.getMethod(
                "getInputReceiver", Looper::class.java, Choreographer::class.java, listenerType
            ).invoke(monitor, Looper.getMainLooper(), Choreographer.getInstance(), listener)
            inputMonitor = monitor
            inputReceiver = receiver
            inputMonitorInstalled = true
            Logx.always("小白条: 被动输入监听已挂载")
        } catch (t: Throwable) {
            if (monitor != null) {
                try { monitor.javaClass.getMethod("dispose").invoke(monitor) } catch (ignored: Throwable) {}
            }
            Logx.e("小白条: 被动输入监听不可用，退回视图事件", t)
        }
    }

    private fun hasInputMonitor(): Boolean =
        inputMonitorInstalled && inputMonitor != null && inputReceiver != null

    private fun stopInputMonitor() {
        val receiver = inputReceiver
        val monitor = inputMonitor
        inputReceiver = null
        inputMonitor = null
        inputMonitorInstalled = false
        try { receiver?.javaClass?.getMethod("dispose")?.invoke(receiver) } catch (ignored: Throwable) {}
        try { monitor?.javaClass?.getMethod("dispose")?.invoke(monitor) } catch (ignored: Throwable) {}
    }

    private fun foreground() {
        // 前台变化：重置展示计时（重新展示片刻再进入沉浸）。
        POLICY.reveal(SystemClock.uptimeMillis())
        refresh()
    }

    /** 配置变化时（运行时经 [Cfg] 实时读取）刷新策略与宿主。 */
    private fun refresh() {
        dispatch {
            if (!Cfg.gestureHandle) {
                POLICY.clearTouchReveal()
                POLICY.clearSwipeReveal()
                activeTouchHandle = null
                activeSwipeRevealed = false
                MOTION.reset()
                stopInputMonitor()
            } else {
                if (receiverInstalled) installInputMonitor()
            }
            if (!Cfg.gestureHandleTouch) POLICY.clearTouchReveal()
            if (!Cfg.gestureHandleFollow) {
                POLICY.clearSwipeReveal()
                clearMotion()
            }
            val nextPresent = Cfg.gestureHandle
            val owner = controller.get()
            if (owner != null) {
                if (nextPresent && !receiverInstalled) {
                    val context = fieldValue(owner, "mContext") as? Context
                    if (context != null) initialize(context)
                }
                val injector = injector(owner)
                stockHidden = bool(injector, "mHideGestureLine")
                if (stockHidden && bool(injector, "mIsFsgMode")) {
                    try {
                        val view = defaultNavigationBar(owner)
                        if (nextPresent) {
                            if (view == null) owner.javaClass.getMethod("addDefaultNavigationBar").invoke(owner)
                        } else if (view != null) {
                            // 总开关关闭时，移除此前为规则保留的宿主。
                            owner.javaClass.getMethod("removeNavigationBar", Integer.TYPE)
                                .invoke(owner, 0)
                        }
                    } catch (t: Throwable) {
                        Logx.e("小白条: host 刷新不可用", t)
                    }
                }
            }
            scheduleHide()
            invalidateHandles()
        }
    }

    private fun defaultNavigationBar(owner: Any): Any? = try {
        owner.javaClass.getMethod("getDefaultNavigationBarView").invoke(owner)
    } catch (ignored: NoSuchMethodException) {
        owner.javaClass.getMethod("getDefaultNavigationBar").invoke(owner)
    }

    private fun scheduleHide() {
        main?.removeCallbacks(HIDE)
        if (!Cfg.gestureHandle) return
        val remaining = POLICY.remaining(SystemClock.uptimeMillis())
        if (remaining > 0L) main?.postDelayed(HIDE, remaining)
    }


    private fun clearMotion() {
        if (!Cfg.gestureHandleTouch) activeTouchHandle = null
        activeSwipeRevealed = false
        MOTION.reset()
    }

    private fun appliesTo(view: View): Boolean {
        val display = view.display ?: return false
        if (display.displayId != 0) return false
        val keyguard = view.context.getSystemService(KeyguardManager::class.java)
        return keyguard == null || !keyguard.isKeyguardLocked
    }

    private fun invalidateHandles() {
        val views = synchronized(HANDLES) { ArrayList(HANDLES.keys) }
        for (view in views) if (view != null && view.isAttachedToWindow) view.invalidate()
    }

    private fun dispatch(action: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run()
        else Handler(Looper.getMainLooper()).post(action)
    }

    private fun injector(owner: Any): Any? = fieldValue(owner, "mNavigationModeControllerInjector")

    private fun bool(owner: Any?, field: String): Boolean =
        java.lang.Boolean.TRUE == fieldValue(owner, field)

    private fun setHiddenFlag(owner: Any?, value: Boolean) {
        owner ?: return
        owner.javaClass.getField("mHideGestureLine").setBoolean(owner, value)
    }

    private fun fieldValue(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            target.javaClass.getField(fieldName).get(target)
        } catch (ignored: ReflectiveOperationException) {
            null
        }
    }
}
