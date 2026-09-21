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
    private const val CORNER_ZONE_W = 0.40f   // 左右各 40% 宽算"角"
    private const val CORNER_ZONE_H = 0.45f   // 屏幕下 45% 高算"角"

    private val cornerMinTravel get() = dp(140f)
    private const val CORNER_MIN_RATIO = 0.6f
    private const val CORNER_MAX_RATIO = 2.6f

    private const val FF_MIN_POINTERS = 4
    private val ffMinTravel get() = dp(70f)
    private const val FF_MAX_MS = 1200L

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

    /** 命中后本串事件不再交给 MIUI 自己的手势逻辑（避免它再解释一遍）。 */
    @Volatile private var swallow = false

    private fun cls(name: String): Class<*> =
        Class.forName(name, false, uiLoader ?: Gestures.javaClass.classLoader)

    // ---------------- 安装 ----------------

    fun install(m: MainHook, cl: ClassLoader) {
        uiLoader = cl
        refreshDisplay()
        // ① 全局输入：挂 MIUI 的 EventReceiver，拿到所有 MotionEvent
        m.hookMethod(cl, CLS_EVENT_RECEIVER, "onInputEvent",
            arrayOf(android.view.InputEvent::class.java),
            XposedInterface.Hooker { chain ->
                var consumed = false
                try {
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
        Logx.always("installGestures: 全局输入已挂钩（gestures=${Cfg.gestures} 屏=${screenW}x${screenH} 密度=$density）")
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
        if (y >= h * (1f - CORNER_ZONE_H) && y <= h) {
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
        if (ffArmed && ev.pointerCount - 1 < FF_MIN_POINTERS) ffArmed = false
        if (cornerArmed) cornerBad = true
        return false
    }

    private fun armFourFinger(ev: MotionEvent) {
        ffArmed = true
        ffX = ev.rawX
        ffY = ev.rawY
        ffStart = ev.eventTime
        ffPeak = ev.pointerCount
        Logx.v("手势: 四指起手 pointers=${ev.pointerCount} @${ev.rawX.toInt()},${ev.rawY.toInt()}")
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
            if (ev.pointerCount < FF_MIN_POINTERS) ffArmed = false else ffPeak = maxOf(ffPeak, ev.pointerCount)
        }
        return false
    }

    private fun onUp(ev: MotionEvent): Boolean {
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
                        "dx=${dx.toInt()} dy=${dy.toInt()} 用时=${ev.eventTime - cornerTime}ms"
                )
                swallow = true
                consumed = true
                if (Cfg.cornerFreeform) cornerSwipeToFreeform()
            } else {
                Logx.v("手势: 角滑未命中 行程=${dist.toInt()} dx=${dx.toInt()} dy=${dy.toInt()} bad=$cornerBad")
            }
        }
        if (ffArmed) {
            val dy = ev.rawY - ffY
            val dx = ev.rawX - ffX
            val used = ev.eventTime - ffStart
            if (dy <= -ffMinTravel && kotlin.math.abs(dx) < dp(200f) && used <= FF_MAX_MS && ffPeak >= FF_MIN_POINTERS) {
                Logx.always(
                    "手势: 四指上滑命中 行程=${(-dy).toInt()}px dx=${dx.toInt()} 用时=${used}ms " +
                        "手指数=$ffPeak 多分屏=${splitActive()}"
                )
                swallow = true
                consumed = true
                if (Cfg.fourFingerSplit) fourFingerAddSplit()
            } else {
                Logx.v("手势: 四指未命中 dy=${dy.toInt()} dx=${dx.toInt()} used=${used}ms peak=$ffPeak")
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

    private fun fourFingerAddSplit() {
        // 已确认的官方入口：
        //   MultiTaskingControllerImpl.getMultiTaskingStateManager().dockSplitFromRecent(Bundle)
        //   MultipleSplitController.insertMultipleSplitByTask(wct, taskId, index)
        Logx.always("四指上滑: 加窗动作待接线（多分屏=${splitActive()}）")
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
            o.javaClass.getMethod("getTaskInfo").invoke(o)?.let { if (it !== o) return it }
        }
        declaredField(o, "mTaskInfo")?.let { if (it !== o) return it }
        return o
    }

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
