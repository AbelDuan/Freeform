package com.abel.os4freeformx

import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * 功能 4：**把手（「···」）菜单同时给出左右与上下两对分屏按钮**。
 *
 * ## 来源与合规
 * 移植自开源项目 **HyperRefine**（AGPL-3.0）的 `CaptionSplitBothDirectionsHook`，
 * 按本模块 hook 风格独立重写。目标方法与签名已用本机 `_recon/out_jadx` 核对：
 *   - `MiuiCaptionContainerView#addSplitStateButton(int,int,int,int,boolean,boolean,int)`（7 参，private）
 *   - `…MiuiCaptionContainerView#createAndAddCaptionButton(int,int,int,int,int,int,int,boolean,MarginLayoutParams)`（9 参）
 *   - `MiuiDecorationDot#addWindow(View,int,int,int,int,int,int,boolean)`（8 参，private）
 *   - `MiuiDecorationAnim#updateDotPositionInfo(float,float,float,float,float)`（5 参）
 *
 * ## 做法（只调系统自身接口，不复制系统代码）
 * 1. 官方按当前方向创建一对按钮（左/上、右/下）后，紧接着**原地补一对另一方向**的按钮
 *    （复用官方 `createAndAddCaptionButton`，带本模块自己的 id），按钮顺序天然是
 *    「全屏 / 本方向两个 / 另一方向两个 / 小窗 / 关闭」，不用挪动任何子视图；
 * 2. 菜单窗口宽度是按按钮数算的，多出的一对会落在裁剪区外 ⇒ 在 `addWindow` 里补上两个槽位的
 *    宽度并左移半个补宽（再按屏幕边界钳制），同时补偿圆点缩放动画的枢轴；
 * 3. 窗口建好后给这两对按钮挂本模块的点击监听（官方按钮保持原监听，行为不变）：
 *    - 未进分屏：先 `SplitLayout#updateDynamicLeftRightSplit()` 对齐实际方向，再把 id 临时换成
 *      官方对应侧的 id 调官方 `onClick` —— 完整复用官方的校验 / 动画 / 关菜单逻辑；
 *    - 已进分屏且点了另一方向：调官方 `SoScUtilsImpl#switchSplitDirection()` 旋转分屏方向，
 *      并把「点击应用切换前的占比」交给 [SplitRatioBridge]（开了「换向保比例」时跟随应用）；
 *    - 已进分屏且点的是同方向：走官方语义（同侧聚焦 / 异侧换位）。
 *
 * 依赖「解除分屏方向限制」才能真正用上横屏上下分屏；关闭本开关则完全不介入。
 */
object CaptionSplitBoth {

    /** 本模块补出的分屏按钮 id（避开资源 id 段，只在 WMShell 进程内使用）。 */
    private const val ID_LR_LEFT = 0x0F0F1001
    private const val ID_LR_RIGHT = 0x0F0F1002
    private const val ID_TB_TOP = 0x0F0F1003
    private const val ID_TB_BOTTOM = 0x0F0F1004

    private val SPLIT_IDS = intArrayOf(ID_LR_LEFT, ID_LR_RIGHT, ID_TB_TOP, ID_TB_BOTTOM)
    /** 官方把手菜单里分屏按钮的兜底 id（正常从 WMShell 的 R 类取）。 */
    private const val R_ID_SPLIT_LEFT_OR_TOP = 1879704280
    private const val R_ID_SPLIT_RIGHT_OR_BOTTOM = 1879704281

    private const val RES_CONTENT_LEFT = "drag_shadow_split_left_open"
    private const val RES_CONTENT_RIGHT = "drag_shadow_split_right_open"
    private const val RES_CONTENT_TOP = "drag_shadow_split_top_open"
    private const val RES_CONTENT_BOTTOM = "drag_shadow_split_bottom_open"

    /**
     * 是否要「两对方向都给」。
     *
     * - `captionSplitBoth`：内屏原本就有的开关；
     * - `coverSplit`：外屏（合上手机）也要四个方向。外屏菜单本来被
     *   `MiuiDecorationDot#isHideSplitButtons()` 整块砍掉，[CoverSplit] 放出来以后
     *   官方只按当前方向建一对，这里必须同样补另一对，否则外屏依旧只有上下（或只有左右）。
     */
    private fun bothDirections(): Boolean = Cfg.captionSplitBoth || Cfg.coverSplit

    private var loader: ClassLoader? = null
    private var containerCls: Class<*>? = null
    private var createButton: Method? = null

    /**
     * 菜单被屏幕边界钳制时相对「居中位置」的位移（屏幕 px）；
     * 由 [widenMenuWindow] 写入、紧随其后的 `updateDotPositionInfo` 读取，用于修正圆点动画枢轴。
     */
    @Volatile private var pendingDotPivotShift = 0f

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        val cc = SoScSupport.findClass(cl, Constants.CLS_CAPTION_CONTAINER) ?: run {
            Logx.e("双向分屏: 找不到 ${Constants.CLS_CAPTION_CONTAINER}")
            return
        }
        containerCls = cc
        createButton = SoScSupport.methodOf(cc, "createAndAddCaptionButton", 9)

        var ok = 0
        runCatching { ok += hookAddSplitStateButton(m, cl, cc) }
            .onFailure { Logx.e("双向分屏: 挂 addSplitStateButton 失败", it) }
        runCatching { ok += hookMenuWindow(m, cl) }
            .onFailure { Logx.e("双向分屏: 挂 addWindow 失败", it) }
        runCatching { ok += hookDotPositionInfo(m, cl) }
            .onFailure { Logx.e("双向分屏: 挂 updateDotPositionInfo 失败", it) }
        if (ok == 0) {
            Logx.e("双向分屏: 一个都没挂上（本机把手菜单类名可能变了）")
            return
        }
        Logx.always("把手菜单双向分屏: 已挂 $ok/3（开关=${Cfg.captionSplitBoth} 外屏分屏=${Cfg.coverSplit}）")
    }

    // ---------------- 1) 官方建完一对按钮后补另一对 ----------------

    private fun hookAddSplitStateButton(m: MainHook, cl: ClassLoader, cls: Class<*>): Int {
        val method = SoScSupport.methodOf(cls, "addSplitStateButton", 7) ?: run {
            Logx.e("双向分屏: 找不到 addSplitStateButton/7")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                // 菜单构建不在热路径上：顺带节流刷新配置，改完开关打开一次菜单即可生效
                Cfg.reloadThrottled()
                if (bothDirections()) {
                    runCatching { addOppositeSplitPair(chain) }
                        .onFailure { Logx.e("双向分屏: 补另一方向按钮失败", it) }
                }
                r
            })) 1 else 0
    }

    /**
     * 官方创建完当前方向的一对按钮后，立刻补上另一方向的一对。
     *
     * 官方 `init` 的顺序是「全屏 → [分屏对] → 小窗 → (新窗口) → 关闭」，
     * 在分屏对之后创建，排列天然正确，不需要再移动任何子视图。
     */
    private fun addOppositeSplitPair(chain: XposedInterface.Chain) {
        val cl = loader ?: return
        val cc = containerCls ?: return
        val create = createButton ?: return
        val container = chain.thisObject as? ViewGroup ?: return
        val leftRight = chain.getArg(4) as? Boolean ?: return
        val supported = chain.getArg(5) as? Boolean ?: return
        if (!supported) return
        val width = chain.getArg(0) as? Int ?: return
        val height = chain.getArg(1) as? Int ?: return
        val padLr = chain.getArg(2) as? Int ?: return
        val padTb = chain.getArg(3) as? Int ?: return
        val containerPad = chain.getArg(6) as? Int ?: return
        val buttons = SoScSupport.call(container, "getCaptionButtonContainer") as? ViewGroup ?: return

        // 已经补过就不再补（避免重建菜单时出现两对）
        val firstOwnId = if (leftRight) ID_TB_TOP else ID_LR_LEFT
        if (buttons.findViewById<View>(firstOwnId) != null) return

        val bg = SoScSupport.wmRes(cl, "drawable", "caption_selector_press_selected")
        // 官方那一对是「当前方向」，我们补「另一方向」
        val icons = if (leftRight) {
            arrayOf("caption_selector_splittop", "caption_selector_splitbottom")
        } else {
            arrayOf("caption_selector_splitleft", "caption_selector_splitright")
        }
        val ids = if (leftRight) intArrayOf(ID_TB_TOP, ID_TB_BOTTOM) else intArrayOf(ID_LR_LEFT, ID_LR_RIGHT)
        val descs = if (leftRight) {
            arrayOf(RES_CONTENT_TOP, RES_CONTENT_BOTTOM)
        } else {
            arrayOf(RES_CONTENT_LEFT, RES_CONTENT_RIGHT)
        }

        var added = 0
        for (i in icons.indices) {
            val icon = SoScSupport.wmRes(cl, "drawable", icons[i])
            if (icon == 0) {
                Logx.e("双向分屏: 图标资源缺失 ${icons[i]}（WMShell 资源名可能变了）")
                continue
            }
            val desc = SoScSupport.wmRes(cl, "string", descs[i])
            runCatching {
                create.invoke(
                    container,
                    ids[i], bg, icon, desc,
                    padLr, padTb, containerPad, true,
                    ViewGroup.MarginLayoutParams(width, height)
                )
            }.onFailure { Logx.e("双向分屏: 创建按钮 0x${ids[i].toString(16)} 失败", it) }
            if (buttons.findViewById<View>(ids[i]) != null) added++
        }
        if (added > 0) Logx.always("双向分屏: 已补另一方向一对按钮（当前方向左右=$leftRight）")
    }

    // ---------------- 2) 菜单窗口补宽 + 挂自己的点击监听 ----------------

    private fun hookMenuWindow(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: run {
            Logx.e("双向分屏: 找不到 ${Constants.CLS_DECOR_DOT}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "addWindow", 8) ?: run {
            Logx.e("双向分屏: 找不到 MiuiDecorationDot#addWindow/8")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val args = chain.args.toMutableList()
                if (bothDirections()) {
                    runCatching { widenMenuWindow(chain.thisObject, args) }
                        .onFailure { Logx.e("双向分屏: 补宽菜单失败", it) }
                }
                val r = chain.proceed(args.toTypedArray())
                if (bothDirections()) {
                    runCatching { attachOwnListeners(chain.thisObject, args.getOrNull(0) as? ViewGroup) }
                        .onFailure { Logx.e("双向分屏: 挂按钮监听失败", it) }
                }
                r
            })) 1 else 0
    }

    /**
     * 补足窗口宽度、按屏幕边界避让、平移窗口 / 菜单位置点 / 缩放枢轴。
     *
     * 官方 `addWindow(view, x, y, width, height, centerX, ?, isPad)`：
     * 宽度与按钮行等宽（`(n+1)*margin + n*width`），补出来的两个按钮会落在裁剪区外。
     * 这里补上两个槽位（`槽位 = 按钮宽 + 左右 margin`），x 左移半个补宽，再按屏幕边界钳制；
     * `centerX`（第 5 个参数）同步补偿，菜单保持居中、收回动画仍收向圆点。
     */
    private fun widenMenuWindow(dot: Any?, args: MutableList<Any?>) {
        pendingDotPivotShift = 0f
        val container = args.getOrNull(0) as? ViewGroup ?: return
        val buttons = SoScSupport.call(container, "getCaptionButtonContainer") as? ViewGroup ?: return
        val ownCount = SPLIT_IDS.count { buttons.findViewById<View>(it) != null }
        if (ownCount == 0) return
        val width = args.getOrNull(3) as? Int ?: return
        val x = args.getOrNull(1) as? Int ?: return
        val each = buttonSlotWidth(buttons) ?: return
        val extra = each * ownCount
        val newWidth = width + extra
        // 先左移半个补宽保持菜单居中；再按屏幕边界钳制，让补宽后的菜单仍完整留在屏幕内
        // （官方的「超出屏幕边界自动避让」按原宽度算，补宽会破坏它）。
        val centered = x - extra / 2
        var newX = centered
        val displayWidth = displayWidth()
        if (displayWidth > 0 && newWidth < displayWidth) {
            newX = newX.coerceIn(0, displayWidth - newWidth)
        }
        pendingDotPivotShift = (newX - centered).toFloat()
        args[3] = newWidth
        args[1] = newX
        val shift = x - newX
        (args.getOrNull(5) as? Int)?.let { args[5] = it + shift }
        runCatching {
            (SoScSupport.call(dot, "getHandleMenuPosition") as? Point)?.also { it.x = newX }
        }.onFailure { Logx.e("双向分屏: 平移菜单位置点失败", it) }
    }

    /** 当前显示宽度（`MultiTaskingDisplayInfo#getDisplayWidth`），拿不到时 0。 */
    private fun displayWidth(): Int {
        val cl = loader ?: return 0
        val w = SoScSupport.callStatic(cl, Constants.CLS_MULTITASKING_DISPLAY_INFO, "getDisplayWidth") as? Int
        return (w ?: 0).takeIf { it > 0 } ?: 0
    }

    /** 单个按钮占用的横向宽度（按钮宽度 + 左右外边距），取已有按钮量得。 */
    private fun buttonSlotWidth(buttons: ViewGroup): Int? {
        for (i in 0 until buttons.childCount) {
            val lp = buttons.getChildAt(i).layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val slot = lp.width + lp.leftMargin + lp.rightMargin
            if (slot > 0) return slot
        }
        return null
    }

    /** 给本模块补出的按钮挂独立点击监听（官方按钮保持原监听器，不受影响）。 */
    private fun attachOwnListeners(dot: Any?, container: ViewGroup?) {
        if (container == null) return
        val official = SoScSupport.field(dot, "mOnMiuiCaptionClickListener") ?: return
        val listener = View.OnClickListener { view ->
            runCatching { onOwnSplitClick(dot, official, view) }
                .onFailure { Logx.e("双向分屏: 处理按钮点击失败", it) }
        }
        SPLIT_IDS.forEach { id -> container.findViewById<View>(id)?.setOnClickListener(listener) }
    }

    /**
     * `MiuiDecorationAnim#updateDotPositionInfo(dotX, dotY, pivotX, pivotY, scale)`：
     * 圆点（点开的「···」）放大动画的枢轴 = 菜单中心的镜像点；菜单被边界钳制后真实中心平移了，
     * 官方算法按原中心算，这里把枢轴同步 `-位移/scale`。
     */
    private fun hookDotPositionInfo(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_ANIM) ?: run {
            Logx.e("双向分屏: 找不到 ${Constants.CLS_DECOR_ANIM}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "updateDotPositionInfo", 5) ?: run {
            Logx.e("双向分屏: 找不到 updateDotPositionInfo/5")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val shift = pendingDotPivotShift
                if (shift == 0f) {
                    chain.proceed()
                } else {
                    pendingDotPivotShift = 0f
                    val args = chain.args.toMutableList()
                    val scale = args.getOrNull(4) as? Float ?: 1f
                    val pivotX = args.getOrNull(2) as? Float
                    if (pivotX != null && scale > 0f) args[2] = pivotX - shift / scale
                    chain.proceed(args.toTypedArray())
                }
            })) 1 else 0
    }

    // ---------------- 点击处理 ----------------

    /**
     * 处理补出来的分屏按钮。
     *
     * - 未进入分屏：先对齐方向，再把 id 临时替换成官方对应侧的按钮 id 并调用官方 `onClick`，
     *   完整复用官方的支持性校验、进入动画与关菜单逻辑（回调结束前恢复原 id）。
     * - 已进入分屏且点的方向与当前不同：调官方 `switchSplitDirection()` 旋转分屏方向。
     * - 已进入分屏且方向相同：不修改方向，走官方语义（同侧聚焦 / 异侧换位）。
     */
    private fun onOwnSplitClick(dot: Any?, official: Any, view: View) {
        val cl = loader ?: return
        val id = view.id
        val sideZero = when (id) {
            ID_LR_LEFT, ID_TB_TOP -> true
            ID_LR_RIGHT, ID_TB_BOTTOM -> false
            else -> return
        }
        val wantLeftRight = id == ID_LR_LEFT || id == ID_LR_RIGHT

        if (SoScSupport.isSoScActive(cl)) {
            val current = SoScSupport.layoutLeftRightSplit(cl)
            if (current != null && current != wantLeftRight && canSwitchDirection(cl)) {
                // 「跟随应用」：先把点击应用的占比换算成「切换后新布局槽位 0（左/上）的占比」
                // 再交给比例记忆 —— 方向切换保留各窗口的「第几侧」角色（上→左、下→右），
                // 应用会落在槽位 sideZero；落在槽位 1 时新分割线占比 = 1 - 应用占比。
                SplitRatioBridge.nextSwitchRatio = clickedAppShare(cl, official)
                    ?.let { share -> if (sideZero) share else 1f - share }
                // 官方的方向切换只改方向、保留各窗口「第几侧」的角色，于是会出现「点左却落到右」；
                // 先按需把两侧位置静默互换，旋转动画随后的目标就会落在用户点击的那一侧。
                alignSideForRotation(cl, official, sideZero)
                SoScSupport.call(SoScSupport.soScUtils(cl), "switchSplitDirection")
                SplitRatioBridge.nextSwitchRatio = null
                closeMenu(dot)
                Logx.always("双向分屏: 点击另一方向 → 旋转分屏方向（左右=$wantLeftRight）")
                return
            }
            Logx.v("双向分屏: 已分屏且方向相同，走官方同侧/换位语义")
        } else {
            val aligned = SoScSupport.ensureDirection(cl, wantLeftRight)
            Logx.always("双向分屏: 进入分屏前对齐方向 左右=$wantLeftRight 成功=$aligned")
        }

        val officialId = officialSplitId(cl, sideZero)
        if (officialId == 0) return
        val originalId = view.id
        view.id = officialId
        try {
            (official as? View.OnClickListener)?.onClick(view)
        } finally {
            view.id = originalId
        }
    }

    /**
     * 点击的分屏按钮对应的应用当前的占比（槽位 0 = 左 / 上）。
     * 取不到任务 id 或布局时返回 null —— 比例记忆会退回「切换瞬间的实时占比」。
     */
    private fun clickedAppShare(cl: ClassLoader, official: Any): Float? {
        val taskId = SoScSupport.field(official, "mTaskId") as? Int ?: return null
        val soSc = SoScSupport.soScUtils(cl) ?: return null
        val leftOrTop = SoScSupport.call(soSc, "isTaskInSoScLeftOrTopScreen", taskId) as? Boolean ?: return null
        val slotZeroRatio = SoScSupport.splitRatio(cl) ?: return null
        return if (leftOrTop) slotZeroRatio else 1f - slotZeroRatio
    }

    /**
     * 让「当前把手菜单对应的应用」在随后的方向切换里落到点击的那一侧。
     *
     * `switchSplitDirection` 的过渡保持各窗口原有的「第几侧」角色（上→左、下→右），因此当
     * 应用当前所在侧与点击侧不同时，先调官方 `setSideStagePosition(pos, false, null)`
     * 静默互换两侧位置（不触发额外的位移动画），再执行旋转，一次动画即可落到目标侧。
     */
    private fun alignSideForRotation(cl: ClassLoader, official: Any, sideZero: Boolean) {
        val taskId = SoScSupport.field(official, "mTaskId") as? Int ?: return
        val soSc = SoScSupport.soScUtils(cl) ?: return
        val currentSideZero =
            SoScSupport.call(soSc, "isTaskInSoScLeftOrTopScreen", taskId) as? Boolean ?: return
        if (currentSideZero == sideZero) return
        val coordinator = SoScSupport.stageCoordinator(cl) ?: return
        val position = SoScSupport.intField(coordinator, "mSideStagePosition") ?: return
        SoScSupport.call(coordinator, "setSideStagePosition", 1 - position, false, null)
        Logx.v("双向分屏: 旋转前先互换两侧位置（wantSideZero=$sideZero）")
    }

    /** 横屏是否允许切成上下分屏（官方条件：`supportTopBottomSplitFeature()`，或本就不在横屏）。 */
    private fun canSwitchDirection(cl: ClassLoader): Boolean {
        val soSc = SoScSupport.soScUtils(cl) ?: return false
        if (SoScSupport.call(soSc, "supportTopBottomSplitFeature") as? Boolean == true) return true
        return SoScSupport.call(soSc, "isLandscape") as? Boolean == false
    }

    /** 关闭当前把手菜单。 */
    private fun closeMenu(dot: Any?) {
        SoScSupport.call(dot, "closeHandleMenu", true, false)
    }

    /** 官方「左/上」「右/下」分屏按钮 id（`com.android.wm.shell.R.id.state_split*`）。 */
    private fun officialSplitId(cl: ClassLoader, sideZero: Boolean): Int {
        val name = if (sideZero) "state_splitleftortop" else "state_splitrightorbottom"
        val fallback = if (sideZero) R_ID_SPLIT_LEFT_OR_TOP else R_ID_SPLIT_RIGHT_OR_BOTTOM
        return SoScSupport.wmRes(cl, "id", name).takeIf { it != 0 } ?: fallback
    }
}
