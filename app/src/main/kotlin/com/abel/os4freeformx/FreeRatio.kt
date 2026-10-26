package com.abel.os4freeformx

import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
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
 * ## 形态分流（两套体系，二选一）
 * HyperOS 按设备形态用**两种完全不同的小窗**，所以本文件两套都保留、运行时择一：
 *   - **平板** → 无极小窗 `miuiinfinitymode`（`installInfinity`）；
 *   - **折叠屏 / 手机** → 经典小窗 `miuifreeform`（`installFreeform` + `installAnimTarget`）。
 * 判定见 `DeviceForm.kt`；平板若没挂上（有些 ROM 也用经典小窗）自动回退到后者。
 * 参考实现 HyperRefine 只写了前者（`deviceScope={PAD,FOLD}`），在折叠屏上等于空转。
 *
 * ## 为什么这条链路只能等比（dexdump 级证据，2 处锁死）
 *
 * ① 高度被宽高比反推 —— `calBoundsAfterMoving(FFFF Rect II TaskInfo F)` 里：
 * ```
 * float ratio = rect.width() / rect.height();   // 拖动开始时的宽高比
 * int   w     = right - left;                   // 宽度，按 dx 增减
 * int   h     = (int)(w / ratio);               // ★ 高度 = 宽度 / 宽高比
 * return resizeAboutCtrlType(ctrlType, l, t, r, b, w, h);
 * ```
 * 注意 4 个 float 里**只有 p1-p3（dx）参与运算**，p2/p4（dy）被编译器判定为无用而丢弃 ——
 * 原方法根本没接收纵向输入，所以结构上不可能是自由比例。
 *
 * ② ★ 更关键：`calBoundsAfterMoving` 的返回高度**根本没人用**。
 * `handleResize` 拿到返回值后只做两件事：
 * ```
 * bounds.offsetTo(result.left, result.top);      // 只取位置，尺寸不动
 * float scale = result.width() / bounds.width(); // 只取宽度
 * target.setAnimParam(bounds, scale, scale, 0f); // ★ scaleX 与 scaleY 传同一个 scale
 * ```
 * 于是"改返回值的高度"注定无效（第一版就踩了这个坑）：高度在多帧之后必然被 `scale` 拉回等比。
 *
 * ## 真正生效的通道
 * `MultiTaskingAnimTarget` 的 `scaleX / scaleY` 是**两个独立字段**：
 * ```
 * getWidth()  = taskBounds.width()  * scaleX
 * getHeight() = taskBounds.height() * scaleY
 * ```
 * 且 `applyResizeAnimation` 把它们分别喂给 Folme 的 `FOLME_WIDTH` / `FOLME_HEIGHT` 两个独立属性。
 * 所以只要把 `scaleY` 换成"自由高度 / 当前高度"，高度就自由了 —— 这是唯一有效的落点。
 *
 * ## 实现（两段配合）
 * 1. `calBoundsAfterMoving`：按纵向拖动量算自由高度，改返回 Rect 的 `top/bottom`
 *    （它决定窗口位置，因为 `offsetTo` 取的就是 `result.left/top`），并把高度记为 pending；
 * 2. `MultiTaskingAnimTarget#setAnimParam`：紧接其后消费 pending，把 `scaleY` 换成
 *    `pendingFreeH / bounds.height()`。位置由 ① 给出、尺寸由 ② 给出，两者自洽。
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

    /** 经典小窗缩放处理器（WMShell → 本机由 **SystemUI** 加载，不是 system_server）。 */
    private const val CLS_RESIZE_HANDLER =
        "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeResizeHandler"

    /** ★ 真正决定"缩放到多大"的载体：scaleX / scaleY 是两个独立字段。 */
    private const val CLS_ANIM_TARGET =
        "com.android.wm.shell.multitasking.common.animation.MultiTaskingAnimTarget"

    /** 松手落盘用的窗口事务（framework 类，SystemUI 里可直接拿到）。 */
    private const val CLS_WCT = "android.window.WindowContainerTransaction"

    /** 参考实现那条链路；本机没有，保留兼容别的 ROM（不存在就只是少挂 4 个钩子）。 */
    private const val CLS_INFINITY_POS =
        "com.android.wm.shell.multitasking.miuiinfinitymode.positiner.MiuiInfinityModeRatioPositioner"
    private const val CLS_INFINITY_WRAP =
        "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeTaskWrapper"

    /** 成功接管次数（诊断：能区分"hook 没挂上"和"挂了但链路没走"）。 */
    @Volatile private var hits = 0

    /**
     * 本帧要的自由高度：由 `calBoundsAfterMoving` 写入、`setAnimParam` 消费。
     * 用「线程 + 时间窗」双重约束，保证只作用于紧随其后的那一次 resize 动画参数，
     * 绝不会污染 translate / mini 等其它动画。
     */
    @Volatile private var pendingFreeH = 0
    @Volatile private var pendingAtMs = 0L
    @Volatile private var pendingThread: Thread? = null

    /**
     * 松手落盘用的自由尺寸（视觉坐标）。
     *
     * 拖动中改 `scaleY` 只能让**视觉**自由；抬手时
     * `MiuiFreeformModeAnimation$3#onContinuousAnimationUp` 会走
     * `wct.setBounds(token, destinationBounds)` 把系统的等比 rect 提交给 WindowManager，
     * 于是尺寸又被拉回等比 —— 真机上表现为"能拉动，一松手就弹回原比例"。
     *
     * 这里单独记一份，由 `WindowContainerTransaction#setBounds` 消费，把落盘尺寸改对。
     * 与 `pendingFreeH` 分开是因为后者会被 `setAnimParam` 每帧消费清空。
     */
    @Volatile private var landFreeH = 0
    @Volatile private var landFreeW = 0
    @Volatile private var landTaskW = 0
    @Volatile private var landAtMs = 0L

    /**
     * 落盘时必须的换算系数（★ 0.6.17 修正的关键）。
     *
     * dexdump 证据：`handleResize` 里
     * ```
     * scale = result.width() / taskInfo.getBounds().width();  // 分母是 **task 宽度**
     * setAnimParam(taskBounds, scale, scale, 0f);
     * ```
     * 而 `getWidth() = taskBounds.width()  * scaleX`、`getHeight() = taskBounds.height() * scaleY`。
     * 抬手时 `applyResizeAnimation` 取 `getCurrentBounds()`（= **taskBounds 原样**，尺寸**不乘** scale）
     * 去 `setBounds`，另把 `getScaleX()` 存进 `MiuiFreeFormInfoChange.setMiuiFreeformScale`。
     *
     * 也就是说**真正落盘的物理量是「task 高 + scale」，视觉高 = task 高 × scale**。
     * 所以写回时必须 `task 高 = 视觉高 / scale`；0.6.16 直接把视觉高写进 task 高，
     * 差了整整一个 scale 倍（本机 ≈1.14），表现为"松手后尺寸不对 / 仍按原比例"。
     */
    @Volatile private var landScaleX = 0f
    /** 落盘时那一帧的 task 高度（诊断用：核对 rect.height 是否就是这个值）。 */
    @Volatile private var landTaskH = 0
    /** 诊断去重：记录已经打过日志的那一帧时间戳。 */
    @Volatile private var landDiagAt = 0L

    fun install(m: MainHook, cl: ClassLoader) {
        val form = DeviceForm.current
        Logx.always("无极调节: 设备形态=${form.name}（${DeviceForm.describe()}）")

        if (form == DeviceForm.Form.PAD) {
            // 平板：HyperOS 走「无极小窗」miuiinfinitymode，与折叠屏的经典小窗是两套体系
            val ok = runCatching { installInfinity(m, cl) }
                .onFailure { Logx.e("无极调节: 平板（infinity）链路安装失败", it) }
                .getOrDefault(false)
            if (ok) return
            // 有些平板 ROM 也走经典小窗；形态判定与 ROM 实现未必一致，回退保证不会整体失效
            Logx.always("无极调节: 平板链路未挂上 → 回退经典小窗（miuifreeform）链路")
        } else {
            // 折叠屏 / 手机：经典小窗 miuifreeform（本机 lhasa 实测就是这条）
            Logx.always("无极调节: ${form.name} → 使用经典小窗（miuifreeform）链路")
        }

        runCatching { installFreeform(m, cl) }
            .onFailure { Logx.e("无极调节: 安装 miuifreeform 链路失败", it) }
        runCatching { installAnimTarget(m, cl) }
            .onFailure { Logx.e("无极调节: 安装动画高度通道失败", it) }
        runCatching { installLanding(m, cl) }
            .onFailure { Logx.e("无极调节: 安装松手落盘通道失败", it) }
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
                // ⚠ 绝对不能写成 `runCatching { ... } ?: ret`：
                //   runCatching 返回的是 kotlin.Result 对象，`?:` 判的是它是否为 null（永不为 null），
                //   于是会把整个 Result 当成返回值交给 libxposed ⇒
                //     ClassCastException: Return value's type from hook callback does not match
                //       the hooked method
                //   ⇒ SystemUI 在 wmshell.main 线程直接 FATAL（0.6.13 实测，一拖角就崩）。
                // 必须 getOrNull() 取出真实值，并且再做一次类型白名单，任何异常都退回系统原值。
                val patched = try {
                    freeHeight(chain, ret, iF2, iF4, iRect, iInfo)
                } catch (t: Throwable) {
                    Logx.v("无极调节: 计算自由高度异常，退回原值: $t")
                    null
                }
                if (patched is Rect) patched else ret
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
        val h = when {
            // 上边跟着手：底边固定
            ctrl and 4 != 0 -> baseH - dy
            // 下边跟着手：顶边固定
            ctrl and 8 != 0 -> baseH + dy
            // 纯左右手柄（只有 1/2 位）：高度本就该不变，不干预
            else -> return null
        }.toInt().coerceIn(minH, maxOf(minH, maxHeightPx))

        when {
            ctrl and 4 != 0 -> free.top = free.bottom - h
            else -> free.bottom = free.top + h
        }
        // ⚠ 这里**不能**"高度没变就 return null"。
        //   纯横向拖动（dy=0）时 h 恰好等于 baseH，正是要靠这次覆盖把高度钉回原值；
        //   否则系统会拿新的宽度按原宽高比把高度一起放大 —— 那还是等比，只是换了个方向。
        //   同理，只有 dy==0 且宽度也没变（手指没动）时后面 setAnimParam 的 sy 才会≈1，
        //   开销可以忽略。

        // 位置这一步就定了（下面 offsetTo 用的是 result.left/top）；
        // 尺寸要等紧随其后的 setAnimParam 把 scaleY 换掉，这里先把目标高度挂出去。
        pendingFreeH = free.height()
        pendingAtMs = SystemClock.uptimeMillis()
        pendingThread = Thread.currentThread()
        // 同时记一份给"松手落盘"用（见 landFreeH 注释）
        landFreeH = free.height()
        landFreeW = free.width()
        // 落盘的 rect 到底用视觉宽度还是任务宽度，不同 ROM 不一样（实测本机
        // taskBounds=919 而 currentBounds=1049，差一个 leash scale），两种都记下来。
        landTaskW = runCatching {
            (info.javaClass.getMethod("getTaskBounds").invoke(info) as? Rect)?.width() ?: 0
        }.getOrDefault(0)
        landAtMs = pendingAtMs
        logHotZoneOnce(info, base)
        Logx.v(
            "无极调节: 帧内目标 ctrl=$ctrl dy=$dy baseH=$baseH 等比高=${out.height()} → 自由高=$h"
        )
        return free
    }

    @Volatile private var hotLogged = false

    /**
     * 一次性把「角柄热区」的真实尺寸打进日志（诊断用）。
     *
     * dex 证据（`MiuiFreeformModeTaskInfo#updateLeftResizeRegion`）：
     * ```
     * hot = Rect(0, 0, mResizeWidth + mHotSpaceWidthOffset, mResizeHeight + mHotSpaceBottomOffset)
     * hot.offsetTo(bounds.left - mHotSpaceWidthOffset, bounds.bottom - mResizeHeight)
     * ```
     * 也就是说热区贴着 **task bounds** 的角，而不是视觉角 —— 视觉窗口按 scale 放大过，
     * 两者差一截，自动化拖动时按视觉角点去点必然落空。把真实数值打出来就不用猜。
     */
    private fun logHotZoneOnce(info: Any, base: Rect) {
        if (hotLogged) return
        hotLogged = true
        runCatching {
            val c = info.javaClass
            fun i(n: String): Int = runCatching {
                c.getMethod("get${n.replaceFirstChar { it.uppercase() }}").invoke(info) as Int
            }.getOrElse {
                runCatching {
                    c.getDeclaredField("m$n").also { it.isAccessible = true }.getInt(info)
                }.getOrDefault(-1)
            }
            val rw = i("ResizeWidth")
            val rh = i("ResizeHeight")
            val ow = i("HotSpaceWidthOffset")
            val oh = i("HotSpaceBottomOffset")
            val tb = runCatching {
                (c.getMethod("getTaskBounds").invoke(info) as? Rect)?.toShortString()
            }.getOrNull()
            Logx.always(
                "无极调节: 角柄热区 resizeW=$rw resizeH=$rh offW=$ow offB=$oh " +
                        "（热区 ${rw + ow}x${rh + oh}，贴 base 角 $base，task=$tb）"
            )
        }
    }

    /** 高度上限：屏幕高（防止往上/下拖出屏幕外，系统对 freeform 不一定钳制）。 */
    private val maxHeightPx: Int by lazy {
        runCatching { Resources.getSystem().displayMetrics.heightPixels }.getOrDefault(4000)
    }

    // ---------------- 关键通道：把 scaleY 换成自由高度 ----------------

    /**
     * `MultiTaskingAnimTarget#setAnimParam(Rect bounds, float scaleX, float scaleY, float anchorY)`
     *
     * 系统对 resize 传的是 `setAnimParam(bounds, scale, scale, 0)` —— 宽高共用同一个 scale，
     * 所以高度必然 = 宽度 / 原宽高比。这里只把第 3 个参数（scaleY）换成
     * `自由高度 / bounds.height()`，宽度与位置完全不碰。
     *
     * 严格只在 resize 帧内生效：pending 由 `calBoundsAfterMoving` 设置，
     * 且要求同一线程 + 150ms 内，消费一次即清空。
     */
    private fun installAnimTarget(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName(CLS_ANIM_TARGET, false, cl) }.getOrNull()
        if (cls == null) {
            Logx.e("无极调节: 找不到 $CLS_ANIM_TARGET（高度通道无法接管）")
            return
        }
        val floats = Float::class.javaPrimitiveType
        val method = cls.declaredMethods.firstOrNull {
            it.name == "setAnimParam" && it.parameterCount == 4 &&
                    Rect::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    it.parameterTypes[1] == floats &&
                    it.parameterTypes[2] == floats &&
                    it.parameterTypes[3] == floats
        }
        if (method == null) {
            Logx.e(
                "无极调节: setAnimParam 签名不符（实际 " +
                        cls.declaredMethods.filter { md -> md.name == "setAnimParam" }
                            .joinToString("|") { md ->
                                md.name + "(" + md.parameterTypes.joinToString(",") { it.simpleName } + ")"
                            } + "）"
            )
            return
        }
        val ok = try {
            method.isAccessible = true
            m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val fh = pendingFreeH
                // 非 resize 帧一律不干预（热路径，先做最便宜的判断）
                if (fh <= 0 || Thread.currentThread() !== pendingThread ||
                    SystemClock.uptimeMillis() - pendingAtMs > 150L
                ) {
                    return@Hooker chain.proceed()
                }
                pendingFreeH = 0
                val bounds = chain.getArg(0) as? Rect
                val oldH = bounds?.height() ?: 0
                if (oldH <= 0) return@Hooker chain.proceed()
                // ★ 落盘基准：这一帧的 scaleX（系统会原样存成 miuiFreeformScale）与 task 高度。
                //   抬手时 `setBounds` 收到的是 taskBounds（尺寸**不乘** scale），
                //   所以写回时要 `taskH = 视觉高 / landScaleX`（见 landScaleX 注释）。
                landScaleX = (chain.getArg(1) as? Float) ?: 1f
                landTaskH = oldH
                landAtMs = SystemClock.uptimeMillis()
                val sy = fh.toFloat() / oldH
                if (sy.isNaN() || sy <= 0.05f || sy > 20f) return@Hooker chain.proceed()

                val args = chain.args.toTypedArray()
                args[2] = sy
                val r = chain.proceed(args)
                val n = ++hits
                if (n == 1) {
                    Logx.always(
                        "无极调节: 首次接管 ctrl 生效，高度 ${oldH}px × $sy = ${fh}px" +
                                "（宽 scaleX=${args[1]}）"
                    )
                } else {
                    Logx.v("无极调节: 接管 #$n 高 $oldH→$fh（scaleY=$sy）")
                }
                r
            })
        } catch (t: Throwable) {
            Logx.e("无极调节: hook setAnimParam 失败", t)
            false
        }
        Logx.always("无极调节: 动画高度通道已挂 ${if (ok) 1 else 0}/1（开关=${Cfg.freeRatio}）")
    }

    // ---------------- 松手落盘：把提交给 WindowManager 的尺寸改对 ----------------

    /**
     * 抬手时 `MiuiFreeformModeAnimation$3#onContinuousAnimationUp` 会走
     * `WindowContainerTransaction#setBounds(token, destinationBounds)` 把系统算的**等比** rect
     * 提交给 WindowManager —— 这一步会把刚才拖动中的自由高度整个覆盖掉。
     *
     * 这里拦一次 `setBounds`：用「宽度对得上 + 距最后一次 resize 帧不超过 2s」两个条件
     * 锁定那一次落盘，只把 `bottom` 换成**换算到 task 空间**的自由高度，其余（位置、宽度、token）
     * 原样保留。换算公式 `task 高 = 视觉高 / scaleX` 的推导见 `landScaleX` 字段注释。
     */
    private fun installLanding(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName(CLS_WCT, false, cl) }.getOrNull()
        if (cls == null) {
            Logx.e("无极调节: 找不到 $CLS_WCT（松手后无法保持自由比例）")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "setBounds" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == Rect::class.java
        }
        if (method == null) {
            Logx.e(
                "无极调节: setBounds 签名不符（实际 " +
                        cls.declaredMethods.filter { it.name == "setBounds" }
                            .joinToString("|") { md ->
                                md.name + "(" + md.parameterTypes.joinToString(",") { it.simpleName } + ")"
                            } + "）"
            )
            return
        }
        val ok = try {
            method.isAccessible = true
            m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val rect = chain.getArg(1) as? Rect
                val fh = landFreeH
                if (rect == null || fh <= 0 || !Cfg.freeRatio ||
                    SystemClock.uptimeMillis() - landAtMs > 2000L
                ) {
                    return@Hooker chain.proceed()
                }
                // 宽度对得上才认为是"这次 resize 的落盘"：
                // 视觉宽度与任务宽度都接受（两者差一个 leash scale，不同 ROM 取值不同）
                val wMatch = kotlin.math.abs(rect.width() - landFreeW) <= 8 ||
                        (landTaskW > 0 && kotlin.math.abs(rect.width() - landTaskW) <= 8)
                if (!wMatch) {
                    // 诊断：每次 resize 只打一条（用 always，否则开关 log=false 时无从排查）
                    if (landDiagAt != landAtMs) {
                        landDiagAt = landAtMs
                        Logx.always(
                            "无极调节: setBounds 未匹配 rect宽=${rect.width()} " +
                                    "视觉=$landFreeW 任务=$landTaskW 高=${rect.height()}"
                        )
                    }
                    return@Hooker chain.proceed()
                }
                // 一次落盘只改一次，避免影响后续无关的 setBounds
                landFreeH = 0
                // ★ 视觉高 → task 高：task 高 = 视觉高 / scale（见 landScaleX 注释）
                val sx = landScaleX
                val wantTaskH = when {
                    sx > 0.05f && sx < 20f -> (fh / sx).toInt()
                    // 拿不到 scale 时退回旧行为（尺寸会偏一个 scale 倍，但总比等比强）
                    else -> fh
                }
                if (wantTaskH <= 0) return@Hooker chain.proceed()
                val args = chain.args.toTypedArray()
                val nb = Rect(rect)
                nb.bottom = nb.top + wantTaskH
                args[1] = nb
                Logx.always(
                    "无极调节: 松手落盘 视觉高 $fh → task 高 $wantTaskH" +
                            "（scaleX=$sx，收到 rect 高 ${rect.height()} 宽 ${rect.width()}，" +
                            "落盘后视觉高=${(wantTaskH * sx).toInt()}）"
                )
                chain.proceed(args)
            })
        } catch (t: Throwable) {
            Logx.e("无极调节: hook setBounds 失败", t)
            false
        }
        Logx.always("无极调节: 松手落盘通道已挂 ${if (ok) 1 else 0}/1（开关=${Cfg.freeRatio}）")
    }

    // ---------------- 平板：miuiinfinitymode（无极小窗） ----------------

    /** @return 是否真正挂上（拖动 + 写目的地这两条核心链路齐全才算）。 */
    private fun installInfinity(m: MainHook, cl: ClassLoader): Boolean {
        val posCls = runCatching { Class.forName(CLS_INFINITY_POS, false, cl) }.getOrNull()
        val wrapCls = runCatching { Class.forName(CLS_INFINITY_WRAP, false, cl) }.getOrNull()
        if (posCls == null || wrapCls == null) {
            Logx.v("无极调节: 没有 miuiinfinitymode（不是平板形态，正常）")
            return false
        }
        val move = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragMove" && it.parameterCount == 2 }
        val end = posCls.declaredMethods.firstOrNull { it.name == "onGestureDragEnd" && it.parameterCount == 2 }
        val update = wrapCls.declaredMethods.firstOrNull {
            it.name == "updateDestinationAttributes" && it.parameterCount == 4
        }
        if (move == null || end == null || update == null) {
            Logx.v("无极调节: infinity 签名不匹配（move=$move end=$end update=$update）")
            return false
        }
        var ok = 0
        if (hookInfinityMove(m, move, wrapCls)) ok++
        if (hookPatch(m, move, "onGestureDragMove")) ok++
        if (hookPatch(m, end, "onGestureDragEnd")) ok++
        val core = hookPatch(m, update, "updateDestinationAttributes")
        if (core) ok++
        Logx.always("无极调节: 平板 infinity 链路已挂 $ok/4（核心 updateDestinationAttributes=$core）")
        return core
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
