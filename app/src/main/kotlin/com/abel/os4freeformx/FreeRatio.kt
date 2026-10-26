package com.abel.os4freeformx

import android.graphics.Rect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 小窗「无极调节」——四角拖动时宽高各自跟手，不再被吸回系统的等比档位。
 *
 * ## 来源与合规
 * 功能移植自开源项目 **HyperRefine**（AGPL-3.0）的 `FreeformFreeRatioHook`，本文件为
 * 独立实现（按本模块的 hook 风格重写，未拷贝其框架代码）。目标链路已与本机反编译产物
 * `Miui-WindowManager-Shell.jar`（HyperOS 4，本目录 `_recon/out_jadx`）逐条核对：
 *   - `com.android.wm.shell.multitasking.miuiinfinitymode.positiner.MiuiInfinityModeRatioPositioner`
 *   - `com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeTaskWrapper`
 *
 * ## 为什么要这个 hook（反编译取证，不是猜的）
 * `MiuiInfinityModeRatioPositioner#onGestureDragMove(f, f2)` 的执行顺序是：
 * 1. `calculateWindowPosition()` + `boundByRatio()` 算出 **自由比例** 的矩形，
 *    写进基类 `MiuiInfinityModeTaskBoundsPositioner` 的 `mLastMovingBounds`；
 * 2. 紧接着 `detectModeAndRadio()` 会拿 `guess`（就近档位）调
 *    `MiuiInfinityModeTaskWrapper#updateDestinationAttributes(sizeLevel, guess.mode, rect, rect)`，
 *    于是 destination / visual bounds 被改回档位 —— 窗口、遮罩、阴影、抬手判定全跟着吸附。
 *
 * 因此做法是：**把 destination / visual bounds 覆盖回 `mLastMovingBounds`，并且要在
 * `updateDestinationAttributes()` 之后再覆盖一次**（该调用内部会重算，只覆盖一次会被它盖掉）。
 *
 * ## 开关
 * `Cfg.freeRatio`（键 `freeform_free_ratio`，默认开）。hook 常驻安装、热路径只读 volatile，
 * 关掉立刻退回系统原来的档位吸附，不需要重启 SystemUI。
 */
object FreeRatio {

    // ---------------- 目标类 ----------------
    private const val CLS_POSITIONER =
        "com.android.wm.shell.multitasking.miuiinfinitymode.positiner.MiuiInfinityModeRatioPositioner"
    private const val CLS_WRAPPER =
        "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeTaskWrapper"

    /** 本次拖动里"真正的自由比例"矩形，以及它所属的窗口 wrapper。 */
    @Volatile private var lastBounds: Rect? = null
    @Volatile private var lastWrapper: Any? = null

    /** 成功接管次数（诊断：能区分"hook 没挂上"和"挂了但链路没走"）。 */
    @Volatile private var hits = 0

    fun install(m: MainHook, cl: ClassLoader) {
        runCatching { installInfinity(m, cl) }.onFailure { Logx.e("无极调节: 安装 infinity 链路失败", it) }
        runCatching { probeFreeformChain(m, cl) }.onFailure { Logx.e("无极调节: 挂探针失败", it) }
    }

    // ---------------- infinity（无极小窗）链路 ----------------

    private fun installInfinity(m: MainHook, cl: ClassLoader) {
        val posCls = runCatching { Class.forName(CLS_POSITIONER, false, cl) }.getOrNull()
        val wrapCls = runCatching { Class.forName(CLS_WRAPPER, false, cl) }.getOrNull()
        if (posCls == null || wrapCls == null) {
            Logx.e("无极调节: 找不到 $CLS_POSITIONER / $CLS_WRAPPER（本机 OS 没有这条链路）")
            return
        }
        val move = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragMove" && it.parameterCount == 2 }
        val end = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragEnd" && it.parameterCount == 2 }
        val reset = posCls.declaredMethods.firstOrNull { it.name == "reset" && it.parameterCount == 0 }
        val update = wrapCls.declaredMethods.firstOrNull {
            it.name == "updateDestinationAttributes" && it.parameterCount == 4
        }
        if (move == null || end == null || reset == null || update == null) {
            Logx.e(
                "无极调节: 方法签名不匹配 move=$move end=$end reset=$reset update=$update" +
                        "（OS 版本可能变了，先别开这个开关）"
            )
            return
        }

        var ok = 0
        if (hook(m, move, "onGestureDragMove")) ok++
        if (hook(m, end, "onGestureDragEnd")) ok++
        if (hook(m, reset, "reset")) ok++
        if (hook(m, update, "updateDestinationAttributes")) ok++
        Logx.always("无极调节: infinity 链路已挂 ${ok}/4（开关=${Cfg.freeRatio}）")
    }

    private fun hook(m: MainHook, target: Method, name: String): Boolean = try {
        target.isAccessible = true
        m.hookExecutable(target, XposedInterface.Hooker { chain ->
            val r = chain.proceed()
            // 热路径：只读 volatile 开关；异常一律吞掉，绝不影响系统自身的缩放链路
            runCatching {
                if (!Cfg.freeRatio) {
                    clear()
                } else {
                    when (name) {
                        "onGestureDragMove" -> remember(chain.thisObject)
                        "updateDestinationAttributes" -> reapply(chain)
                        else -> clear()   // onGestureDragEnd / reset
                    }
                }
            }
            r
        })
    } catch (t: Throwable) {
        Logx.e("无极调节: hook $name 失败", t)
        false
    }

    /**
     * 拖动中：从 positioner 取 `mLastMovingBounds`（自由比例矩形）、`mTaskWrapper`、`guess.mode`。
     * 只在 `guess.mode == 1`（正常窗口态）接管 —— mode 0/2 是"回全屏 / 迷你态"的过渡，别碰。
     */
    private fun remember(positioner: Any) {
        // 拖动是最热的热路径：让开关改动 1~2 秒内生效（节流 + 异步，绝不阻塞拖动）
        Cfg.reloadThrottled()
        val bounds = fieldValue(positioner, "mLastMovingBounds") as? Rect ?: return
        if (bounds.isEmpty) return
        val wrapper = fieldValue(positioner, "mTaskWrapper") ?: return
        val guess = fieldValue(positioner, "guess") ?: return
        if ((fieldValue(guess, "mode") as? Int) != 1) {
            clear()
            return
        }
        lastBounds = Rect(bounds)
        lastWrapper = wrapper
        apply(wrapper, bounds)
        val n = ++hits
        if (n == 1) {
            Logx.always(
                "无极调节: 首次接管（自由 bounds=${bounds.width()}x${bounds.height()} " +
                        "@${bounds.left},${bounds.top}）"
            )
        } else {
            Logx.v("无极调节: 接管 #$n ${bounds.width()}x${bounds.height()}")
        }
    }

    /**
     * `MiuiInfinityModeTaskWrapper#updateDestinationAttributes(sizeLevel, mode, rect, rect)` 之后
     * 再覆盖一次 —— 它内部会重新 setDestination*，只覆盖一次会被这次调用盖掉。
     */
    private fun reapply(chain: XposedInterface.Chain) {
        val bounds = lastBounds ?: return
        val wrapper = lastWrapper ?: return
        if (chain.thisObject !== wrapper) return
        // arg[1] = mode，只有 1（正常窗口态）才接管
        if ((chain.getArg(1) as? Int) != 1) return
        apply(wrapper, bounds)
    }

    /** 把窗口的 destination / visual bounds 与 scale 一起钉成"自由比例"那一个矩形。 */
    private fun apply(wrapper: Any, bounds: Rect) {
        call(wrapper, "setDestinationBounds", Rect(bounds))
        call(wrapper, "setDestinationVisualBounds", Rect(bounds))
        call(wrapper, "setDestinationNormalScale", 1.0f)
    }

    private fun clear() {
        lastBounds = null
        lastWrapper = null
    }

    // ---------------- 探针：确认本机小窗走的到底是哪条链路 ----------------
    //
    // HyperOS 4 同时存在两套小窗缩放实现：
    //   ① miuiinfinitymode（无极小窗）：上面的 RatioPositioner，本来就按自由宽高比算；
    //   ② miuifreeform（经典小窗）：`MiuiFreeformModeResizeHandler#handleResize`，
    //      `calBoundsAfterMoving` 里写死 `height = width / 宽高比` ⇒ 结构上只能等比缩放。
    // 只埋一条 once 日志：拖一次角就知道命中哪条，避免"改了半天没反应"查不出原因。
    private fun probeFreeformChain(m: MainHook, cl: ClassLoader) {
        val ok = m.hookMethod(cl, Constants.CLS_FF_RESIZE_HANDLER, "handleResize", null,
            XposedInterface.Hooker { chain ->
                Logx.once(
                    "fr-chain-miuifreeform",
                    "无极调节探针: 走了 miuifreeform 链路（MiuiFreeformModeResizeHandler#handleResize）" +
                            " ⇒ 该窗口不吃 infinity 的自由比例"
                )
                chain.proceed()
            })
        if (!ok) Logx.v("无极调节探针: handleResize 未挂上（可能本机没这个类）")
    }

    // ---------------- 反射工具（本文件自包含，不借 Hooks 的私有实现） ----------------

    private fun fieldOf(o: Any, name: String): Field? {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it }
            c = c.superclass
        }
        return null
    }

    private fun fieldValue(o: Any, name: String): Any? = runCatching {
        val f = fieldOf(o, name) ?: return null
        f.isAccessible = true
        f.get(o)
    }.getOrNull()

    /** 按「方法名 + 参数个数」调用（同名重载在本机不冲突）。 */
    private fun call(o: Any, name: String, vararg args: Any?) {
        runCatching {
            val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: return
            m.isAccessible = true
            m.invoke(o, *args)
        }.onFailure { Logx.e("无极调节: 调用 $name 失败", it) }
    }
}
