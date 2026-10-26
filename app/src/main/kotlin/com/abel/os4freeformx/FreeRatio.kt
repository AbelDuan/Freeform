package com.abel.os4freeformx

import android.graphics.Rect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 小窗「无极调节」——四角拖动时宽高各自跟手，不再被吸回系统的等比档位。
 *
 * ## 来源与合规
 * 功能移植自开源项目 **HyperRefine**（AGPL-3.0）的 `FreeformFreeRatioHook`，本文件为独立实现
 * （按本模块的 hook 风格重写，未拷贝其框架代码）。
 *
 * ## ★ 落点变更（2026-10-26 真机取证，很重要）
 * 参考实现挂的是 `miuiinfinitymode`（`MiuiInfinityModeRatioPositioner` / `MiuiInfinityModeTaskWrapper`）。
 * 但真机实测报「找不到这条链路」，于是把 **当前 ROM 的 `/system_ext/framework/Miui-WindowManager-Shell.jar`**
 * 拉下来核对 dex：
 *   - `miuiinfinitymode` 包**根本不存在**（multitasking 下只有 common / miuidesktopmode /
 *     miuifreeform / miuimultiwinswitch / stubs）；
 *   - `MiuiInfinityModeRatioPositioner` 0 次命中。
 * 也就是说本机（OS4.0.26.0.XPNCNXM）走的是**经典小窗** `miuifreeform` 链路。
 * 之前依据的 `_recon/out_jadx` 是**另一版 ROM**（jar 8,999,939B）反编译的，与本机（8,693,603B）不同，
 * 那次"逐条核对签名"其实核对了一个不存在的世界 —— 教训：recon 必须来自当前设备的 jar。
 *
 * ## 为什么这条链路只能等比（dex 级证据）
 * `MiuiFreeformModeResizeHandler#calBoundsAfterMoving` 里：
 * ```
 * float fWidth = rect.width() / rect.height();   // 拖动开始时的宽高比
 * int   iAfterFriction = right - left;           // 宽度，按拖动量 ±
 * int   i7 = (int) (iAfterFriction / fWidth);    // ★ 高度 = 宽度 / 宽高比 ⇒ 结构上只能等比
 * return resizeAboutCtrlType(ctrlType, l, t, r, b, iAfterFriction, i7);
 * ```
 * **宽度**已经是"跟手"的自由值（只受最小/最大宽与摩擦限幅约束），
 * 唯一的锁死点是高度被宽高比反推。所以不必动宽度，只把**高度**按纵向拖动量重算，
 * 并保持"被拖动的那个边不动"即可。
 *
 * ctrlType 位（与上面 width 的 `&1 / &2` 同源）：1=左 2=右 4=上 8=下。
 *   - 含 4（上边跟着手）：`top = bottom - h`，h = baseH - dy
 *   - 含 8（下边跟着手）：`bottom = top + h`，h = baseH + dy
 *   - 只含 1/2（纯左右手柄）：高度本就该不变，不干预
 *
 * ## 开关
 * `Cfg.freeRatio`（键 `freeform_free_ratio`，默认开）。hook 常驻安装、热路径只读 volatile，
 * 关掉立刻退回系统原来的等比行为，**不需要重启任何进程**。
 */
object FreeRatio {

    /** 经典小窗缩放处理器（WMShell → 跑在 system_server）。 */
    private const val CLS_RESIZE_HANDLER =
        "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeResizeHandler"

    /** 参考实现那条链路；本机没有，保留兼容别的 ROM（不存在就只是少挂 4 个钩子）。 */
    private const val CLS_INFINITY_POS =
        "com.android.wm.shell.multitasking.miuiinfinitymode.positiner.MiuiInfinityModeRatioPositioner"
    private const val CLS_INFINITY_WRAP =
        "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeTaskWrapper"

    /** 成功接管次数（诊断：能区分"hook 没挂上"和"挂了但链路没走"）。 */
    @Volatile private var hits = 0

    fun install(m: MainHook, cl: ClassLoader) {
        runCatching { installFreeform(m, cl) }
            .onFailure { Logx.e("无极调节: 安装 miuifreeform 链路失败", it) }
        // 兼容别的 ROM：本机没有 miuiinfinitymode，这里只会留一条 verbose 日志
        runCatching { installInfinity(m, cl) }
            .onFailure { Logx.v("无极调节: infinity 链路安装异常: $it") }
    }

    // ---------------- 主链路：miuifreeform ----------------

    private fun installFreeform(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName(CLS_RESIZE_HANDLER, false, cl) }.getOrNull()
        if (cls == null) {
            Logx.e("无极调节: 找不到 $CLS_RESIZE_HANDLER（本机小窗链路不明，开关无效）")
            return
        }
        // 一次性把候选方法签名打进日志：以后 ROM 改签名时不用再费劲反编译
        runCatching {
            val all = cls.declaredMethods
                .filter { it.name.contains("calBounds", true) || it.name.contains("Resize", true) }
                .joinToString(" | ") { md ->
                    md.name + "(" + md.parameterTypes.joinToString(",") { it.simpleName } + "):" +
                            md.returnType.simpleName
                }
            Logx.v("无极调节: 候选方法 $all")
        }

        val method = findCalBounds(cls)
        if (method == null) {
            Logx.e(
                "无极调节: 没找到 calBoundsAfterMoving(…,Rect,…) —— ROM 可能改签名了，" +
                        "logcat 里搜「候选方法」看实际列表"
            )
            return
        }

        val ps = method.parameterTypes
        // 前 4 个 float 依次是 (当前x, 当前y, 按下x, 按下y)
        val floatIdx = ps.indices.filter { ps[it] == Float::class.javaPrimitiveType }
        val rectIdx = ps.indexOfFirst { Rect::class.java.isAssignableFrom(it) }
        val infoIdx = ps.indexOfFirst { it.name.endsWith("MiuiFreeformModeTaskInfo") }
        if (floatIdx.size < 4 || rectIdx < 0 || infoIdx < 0) {
            Logx.e("无极调节: 参数结构不符 float=$floatIdx rect=$rectIdx info=$infoIdx（开关无效）")
            return
        }
        val iF2 = floatIdx[1]
        val iF4 = floatIdx[3]
        val iRect = rectIdx
        val iInfo = infoIdx

        val ok = try {
            method.isAccessible = true
            m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val ret = chain.proceed()
                // 热路径：只读 volatile；异常一律吞掉，绝不影响系统自身缩放
                runCatching { freeHeight(chain, ret, iF2, iF4, iRect, iInfo) } ?: ret
            })
        } catch (t: Throwable) {
            Logx.e("无极调节: hook calBoundsAfterMoving 失败", t)
            false
        }
        Logx.always(
            "无极调节: miuifreeform 链路已挂 ${if (ok) 1 else 0}/1" +
                    "（${method.name}/${ps.size} 参，开关=${Cfg.freeRatio}）"
        )
    }

    /** 找 `calBoundsAfterMoving(...Rect...): Rect`；签名变了也能靠"返回 Rect + 含 Rect 参数"兜住。 */
    private fun findCalBounds(cls: Class<*>): Method? {
        val cands = cls.declaredMethods.filter {
            Rect::class.java.isAssignableFrom(it.returnType) &&
                    it.parameterTypes.any { p -> Rect::class.java.isAssignableFrom(p) }
        }
        return cands.firstOrNull { it.name == "calBoundsAfterMoving" }
            ?: cands.firstOrNull { it.name.contains("calBounds", true) }
    }

    /**
     * 把原方法算出的「等比高度」换成「按纵向拖动量跟手的高度」，横向完全交给原逻辑。
     * 返回 null 表示不干预（调用方会退回原返回值）。
     */
    private fun freeHeight(
        chain: XposedInterface.Chain,
        ret: Any?,
        iF2: Int,
        iF4: Int,
        iRect: Int,
        iInfo: Int
    ): Any? {
        val out = ret as? Rect ?: return null
        if (!Cfg.freeRatio) return null
        val base = chain.getArg(iRect) as? Rect ?: return null
        val info = chain.getArg(iInfo) ?: return null
        val ctrl = runCatching {
            info.javaClass.getMethod("getCtrlType").invoke(info) as Int
        }.getOrNull() ?: return null

        val baseH = base.height()
        if (baseH <= 0) return null
        val dy = (chain.getArg(iF2) as? Float ?: return null) -
                (chain.getArg(iF4) as? Float ?: return null)

        val minH = (baseH / 4).coerceAtLeast(120)
        val free = Rect(out)
        when {
            ctrl and 4 != 0 -> {
                // 上边跟着手：底边固定
                val h = (baseH - dy).toInt().coerceAtLeast(minH)
                free.top = free.bottom - h
            }
            ctrl and 8 != 0 -> {
                // 下边跟着手：顶边固定
                val h = (baseH + dy).toInt().coerceAtLeast(minH)
                free.bottom = free.top + h
            }
            else -> return null   // 纯左右手柄：高度本就该不变
        }
        if (free.height() == out.height() && free.top == out.top) return null

        val n = ++hits
        if (n == 1) {
            Logx.always(
                "无极调节: 首次接管（ctrl=$ctrl dy=$dy 等比高=${out.height()} → 自由高=${free.height()} " +
                        "宽=${free.width()}）"
            )
        } else {
            Logx.v("无极调节: 接管 #$n ctrl=$ctrl ${free.width()}x${free.height()}")
        }
        return free
    }

    // ---------------- 兼容：miuiinfinitymode（本机不存在） ----------------

    private fun installInfinity(m: MainHook, cl: ClassLoader) {
        val posCls = runCatching { Class.forName(CLS_INFINITY_POS, false, cl) }.getOrNull()
        val wrapCls = runCatching { Class.forName(CLS_INFINITY_WRAP, false, cl) }.getOrNull()
        if (posCls == null || wrapCls == null) {
            Logx.v("无极调节: 本机没有 miuiinfinitymode（走 miuifreeform，正常）")
            return
        }
        val move = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragMove" && it.parameterCount == 2 }
        val end = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragEnd" && it.parameterCount == 2 }
        val update = wrapCls.declaredMethods.firstOrNull {
            it.name == "updateDestinationAttributes" && it.parameterCount == 4
        }
        if (move == null || end == null || update == null) {
            Logx.v("无极调节: infinity 签名不匹配，跳过")
            return
        }
        var ok = 0
        if (hookInfinityMove(m, move, wrapCls)) ok++
        if (hookPatch(m, update, "updateDestinationAttributes")) ok++
        if (hookPatch(m, move, "onGestureDragMove")) ok++
        if (hookPatch(m, end, "onGestureDragEnd")) ok++
        Logx.always("无极调节: infinity 链路已挂 ${ok}/4（其它 ROM 用）")
    }

    /** infinity 链路里"真正的自由比例"矩形（仅其它 ROM 用）。 */
    @Volatile private var lastBounds: Rect? = null
    @Volatile private var lastWrapper: Any? = null

    private fun hookInfinityMove(m: MainHook, method: Method, wrapCls: Class<*>): Boolean = try {
        method.isAccessible = true
        m.hookExecutable(method, XposedInterface.Hooker { chain ->
            val r = chain.proceed()
            runCatching {
                if (!Cfg.freeRatio) {
                    clearInfinity()
                } else {
                    val pos = chain.thisObject
                    val b = fieldValue(pos, "mLastMovingBounds") as? Rect
                    val w = fieldValue(pos, "mTaskWrapper")
                    val mode = fieldValue(fieldValue(pos, "guess") ?: Unit, "mode") as? Int
                    if (b != null && w != null && b.isEmpty.not() && mode == 1) {
                        lastBounds = Rect(b)
                        lastWrapper = w
                    }
                }
            }
            r
        })
    } catch (t: Throwable) {
        Logx.v("无极调节: infinity move 挂载失败: $t")
        false
    }

    private fun hookPatch(m: MainHook, method: Method, name: String): Boolean = try {
        method.isAccessible = true
        m.hookExecutable(method, XposedInterface.Hooker { chain ->
            val r = chain.proceed()
            runCatching {
                if (!Cfg.freeRatio) return@runCatching
                if (name == "updateDestinationAttributes") {
                    val b = lastBounds ?: return@runCatching
                    val w = lastWrapper ?: return@runCatching
                    if (chain.thisObject !== w) return@runCatching
                    if ((chain.getArg(1) as? Int) != 1) return@runCatching
                    setDest(w, b)
                } else if (name == "onGestureDragEnd") {
                    clearInfinity()
                }
            }
            r
        })
    } catch (t: Throwable) {
        false
    }

    private fun setDest(wrapper: Any, bounds: Rect) {
        runCatching {
            wrapper.javaClass.methods.firstOrNull { it.name == "setDestinationBounds" }
                ?.also { it.isAccessible = true }?.invoke(wrapper, Rect(bounds))
            wrapper.javaClass.methods.firstOrNull { it.name == "setDestinationVisualBounds" }
                ?.also { it.isAccessible = true }?.invoke(wrapper, Rect(bounds))
            wrapper.javaClass.methods.firstOrNull { it.name == "setDestinationNormalScale" }
                ?.also { it.isAccessible = true }?.invoke(wrapper, 1.0f)
        }
    }

    private fun clearInfinity() {
        lastBounds = null
        lastWrapper = null
    }

    // ---------------- 反射工具（本文件自包含） ----------------

    private fun fieldValue(o: Any, name: String): Any? = runCatching {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(o)
            }
            c = c.superclass
        }
        null
    }.getOrNull()
}
