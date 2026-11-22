package com.abel.os4freeformx

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import io.github.libxposed.api.XposedInterface

/**
 * **把三点菜单固定到顶部摄像头位置**（折叠屏外屏，用户 2026-11-22 要求）。
 *
 * ## 需求原话
 * 「3 点菜单指的是内屏展开时顶部的那个分屏的 3 点菜单，也是小窗顶部的那个分屏 3 点菜单。」
 * 「把三点菜单隐藏在正中间摄像头位置，这样点击摄像头就能弹出分屏的三点菜单，并且完全被摄像头覆盖。」
 *
 * ## 系统原本怎么摆（反编译实证，见 `docs/merge-20261121/RECON-findings.md`）
 * `MiuiDecorationDot#calculateHandleMenuPos` 按窗口位置算菜单坐标：
 * - 小窗（`windowingMode==5`）：`calculateFreeformHandleMenuPos` —— 菜单落在**窗口顶边中间**；
 * - 分屏（`==6`）：`calculateFullOrSplitHandleMenuPos` —— 落在**stage 顶边中间**。
 * 然后 `adjustHandleMenuPos` 做挖孔避让，但它**只在菜单矩形与挖孔相交时才动手**：
 * 小窗不在屏幕正中时，菜单根本碰不到顶部的摄像头 ⇒ 永远不会被吸过去。
 *
 * ## 这里的做法
 * 直接改写 `calculateHandleMenuPos` 的返回值：**菜单左上角 = 摄像头左上角**。
 * 坐标系已核对：`addWindow` 用 `attachToDisplayArea(0, …)` + `setPosition(x, y)`，
 * 而 `mHandleMenuPosition` 直接作为该 x/y 传入 ⇒ **屏幕坐标系**，与 `DisplayCutout` 同系。
 *
 * 副效果（正是用户要的）：菜单本体压在挖孔上，**被摄像头遮住一部分**；
 * 系统的伸展动画 `updateDotPositionInfo` 仍按原逻辑从圆点展开，交互不变。
 *
 * ## 只在外屏生效
 * 内屏挖孔在**左侧**（(0,0)-(164,140)），那里不是「正中间摄像头」，
 * 按用户描述只处理顶部居中的外屏；内屏保持系统原行为。
 */
object CaptionMenuOuter {

    /** `com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationInfo` */
    private const val CLS_DECOR_INFO =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationInfo"

    /** 子菜单与摄像头下沿之间的空隙（dp）。 */
    private const val CAPTION_GAP_DP = 12f

    /** 下半区分屏任务：菜单距该任务顶边的空隙（dp）。 */
    private const val MENU_GAP_DP = 8f

    /** 外屏物理短边（px）。用于兜底判断「是否在外屏」，内屏短边 1672。 */
    private const val OUTER_SHORT_SIDE = 1168

    /** 外屏物理长边（px）。 */
    private const val OUTER_LONG_SIDE = 1712

    private var loader: ClassLoader? = null

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        var ok = 0
        runCatching { ok += hookCalculatePos(m, cl) }
            .onFailure { Logx.e("摄像头三点菜单: 挂 calculateHandleMenuPos 失败", it) }
        runCatching { ok += hookAdjustPos(m, cl) }
            .onFailure { Logx.e("摄像头三点菜单: 挂 adjustHandleMenuPos 失败", it) }
        runCatching { ok += hookCaptionOffset(m, cl) }
            .onFailure { Logx.e("摄像头三点菜单: 挂 calculateCaptionOffsetY 失败", it) }
        runCatching { ok += hookHideSplitButtons(m, cl) }
            .onFailure { Logx.e("摄像头三点菜单: 挂 isHideSplitButtons 失败", it) }
        Logx.always("摄像头三点菜单: 已挂 $ok/4（开关=${Cfg.outerSplit}）")
    }

    /**
     * 把菜单坐标改写成摄像头位置。
     *
     * 挂在 `MiuiDecorationDot#calculateHandleMenuPos` 外层，拿到系统算好的坐标后原地覆盖。
     * 拿不到真实挖孔就**原样放行**（不等于兜底：内屏/无挖孔时系统怎么算就怎么用）。
     */
    private fun hookCalculatePos(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: run {
            Logx.e("摄像头三点菜单: 找不到 ${Constants.CLS_DECOR_DOT}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "calculateHandleMenuPos", 6) ?: run {
            Logx.e("摄像头三点菜单: 找不到 calculateHandleMenuPos/6")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (!Cfg.outerSplit) return@Hooker r
                val point = r as? Point ?: return@Hooker r
                runCatching { moveToCamera(chain.thisObject, chain.args, point) }
                    .onFailure { Logx.e("摄像头三点菜单: 改写坐标失败", it) }
                r
            })) 1 else 0
    }

    /**
     * 把 [point] 改写到摄像头左上角。
     *
     * @param dot `MiuiDecorationDot` 实例
     * @param args 原方法参数：`(Rect taskBounds, float scale, int menuW, int menuH, int captionOffsetY, MulWinSwitchResourceManager)`
     * @param point 系统算出的菜单左上角（屏幕坐标），原地修改
     */
    private fun moveToCamera(dot: Any?, args: List<Any?>, point: Point) {
        val ctx = SoScSupport.field(dot, "mContext") as? Context ?: return
        val task = SoScSupport.field(dot, "mRunningTaskInfo")
        val displayId = (SoScSupport.field(task, "displayId") as? Int) ?: return

        val cam = OuterDisplay.cameraRectOnScreen(ctx, displayId) ?: return
        val g = OuterDisplay.geometry(ctx, displayId) ?: return
        // 只认「顶部居中」的挖孔（外屏）。内屏挖孔在左侧 —— 不是用户说的位置，原样放行。
        // 诊断：横屏时把几何打出来（每屏一次）
        Logx.once("geo-" + displayId + "-" + g.width + "x" + g.height,
            "[外屏顶栏] displayId=$displayId 屏=${g.width}x${g.height} 挖孔=$cam " +
                "topCentered=${isTopCentered(cam, g.width)} sideCam=${isSideCamera(cam, g.height)}")
        if (!isTopCentered(cam, g.width)) return

        val menuW = args.getOrNull(2) as? Int ?: return
        val oldX = point.x
        val oldY = point.y

        val taskBounds = args.getOrNull(0) as? Rect
        val taskTop = taskBounds?.top ?: 0

        var x: Int
        var y: Int

        // **用系统自己的判定**（用户 2026-11-22：「下分屏的三点菜单还是顶着部」）。
        //
        // 之前用 `taskTop >= 屏高/2` 猜「是不是下半区」，实测不成立：
        // 分屏 stage 里给到 `calculateHandleMenuPos` 的 rect 是**相对 stage 的**，
        // 上下分屏时下半区 rect.top 也可能接近 0，于是被判成上半区
        // → 菜单被送到摄像头（屏幕最上方），正是用户看到的现象。
        //
        // `SoScUtils#isTaskInSoScRightOrBottomScreen(taskId)` 是系统判断
        // 「该任务在右/下那一格」的权威接口（`sendSoScStateChanged` 的
        // rightOrBottomStageBounds 就来自它），直接用它。
        val taskId = (SoScSupport.field(task, "taskId") as? Int) ?: -1
        val inLowerStage = runCatching {
            val soSc = SoScSupport.soScUtils(loader ?: ctx.javaClass.classLoader) ?: return@runCatching false
            SoScSupport.call(soSc, "isTaskInSoScRightOrBottomScreen", taskId) as? Boolean ?: false
        }.getOrDefault(false)

        if (inLowerStage) {
            // ── 下半区：菜单跟随**该任务自己的顶边**，不再跳回屏幕顶部。
            //
            //    反编译 `calculateFullOrSplitHandleMenuPos`：折叠屏分支下
            //    `i4 = 状态栏高度 - FlipMenuOffset`，这个值与任务位置完全无关，
            //    所以交换左右/上下位置后菜单仍停在屏幕顶部（用户实测「跑到上面去」）。
            //    这里改成落在该任务顶边稍微往下一点，贴着它自己的窗口。
            val density = ctx.resources.displayMetrics.density
            val gap = (MENU_GAP_DP * density).toInt()
            x = (g.width - menuW) / 2
            x = x.coerceIn(0, (g.width - menuW).coerceAtLeast(0))
            // 系统算出的 point.y 就是相对本任务顶边的位置，直接沿用；并钳在任务可见范围内，
            // 避免落到屏幕外（横屏/竖屏都适用）。
            val fallbackY = if (taskTop > 0) taskTop + gap else point.y
            y = fallbackY.coerceIn(0, (g.height - 1).coerceAtLeast(0))
        } else {
            // ── 上半个屏（含全屏）：菜单落在「状态栏正中 = 摄像头位置」，用户要的那个位置
            x = cam.centerX() - menuW / 2
            x = x.coerceIn(0, (g.width - menuW).coerceAtLeast(0))
            y = cam.bottom
        }

        point.x = x
        point.y = y

        // 一次性日志（每任务只打一次，避免刷屏）
        Logx.once(
            "cam-menu-" + (SoScSupport.field(task, "taskId") ?: 0),
            "摄像头三点菜单: task=${SoScSupport.field(task, "taskId")} 菜单=${menuW}px " +
                "taskBounds=$taskBounds taskTop=$taskTop taskId=$taskId lower=$inLowerStage " +
                "(${oldX},${oldY}) → (${point.x},${point.y}) 挖孔=$cam"
        )
    }

    /** 挖孔是否「顶部居中」（左右留白近似对称）。 */
    private fun isTopCentered(cam: Rect, screenWidth: Int): Boolean {
        if (cam.top > 0) return false
        val leftGap = cam.left
        val rightGap = screenWidth - cam.right
        return Math.abs(leftGap - rightGap) < cam.width()
    }

    /**
     * 拦掉系统的挖孔避让。
     *
     * `calculateHandleMenuPos` 末尾会调 `adjustHandleMenuPos`，它发现菜单**压到挖孔**就会把菜单
     * **下移到挖孔下方**（`point.y = cutout.bottom + margin`）—— 那正是我们刚放进去的位置，
     * 不拦的话菜单会被它推回来，就永远吸不到摄像头上。
     *
     * 现在「菜单压摄像头」是**有意的**（用户要求菜单藏在摄像头下面），所以：
     * 只在外屏顶部居中挖孔上跳过该方法，其余情况（内屏 / 无挖孔 / 其它屏）原样执行。
     */
    private fun hookAdjustPos(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: return 0
        val method = SoScSupport.methodOf(cls, "adjustHandleMenuPos", 5) ?: run {
            Logx.e("摄像头三点菜单: 找不到 adjustHandleMenuPos/5")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                if (Cfg.outerSplit && isOuterTopCamera(chain.thisObject)) {
                    Logx.once("skip-adjust", "摄像头三点菜单: 跳过挖孔避让（菜单有意压在摄像头上）")
                    return@Hooker null
                }
                chain.proceed()
            })) 1 else 0
    }

    /** 当前这个 decoration 是否落在「顶部居中挖孔」的屏上（= 外屏）。 */
    /**
     * 是否「在外屏」——**与挖孔位置无关**。
     *
     * ⚠️ 早期版本这里用的是 `isTopCentered(cam, width)`（只在外屏**竖屏**成立），
     * 结果外屏一横屏，挖孔跑到侧边、判定变 false，
     * `isHideSplitButtons` 又回到 ROM 的 true ⇒ **横屏三点菜单里没有分屏按钮**
     * （用户 2026-11-22：「外屏横屏的三点菜单，没有显示各种分屏的能力」）。
     *
     * ROM 隐藏分屏按钮的条件是 `isDevice("lhasa") && isFoldOutScreen(ctx)`，
     * 只看「是不是外屏」，与横竖无关 ⇒ 这里也必须只看外屏。
     */
    private fun isOuterTopCamera(dot: Any?): Boolean {
        val ctx = SoScSupport.field(dot, "mContext") as? Context ?: return false
        // **第一优先：直接读 MIUI 的 `Configuration.screenType`**（与 ROM
        // `MultiTaskingDeviceUtils.isExpand()` 读的是同一个字段：0=内屏展开，非 0=外屏）。
        // 这是 ROM 自己判定「内外屏」的权威依据，与横竖、挖孔位置都无关。
        // 之前几版都用挖孔位置/屏幕尺寸去猜，横屏时挖孔在侧边就判错，
        // 导致 ROM 的 isHideSplitButtons 回到 true ⇒ 横屏菜单没有分屏按钮。
        val screenType = runCatching {
            val cfg = ctx.resources.configuration
            cfg.javaClass.getField("screenType").getInt(cfg)
        }.getOrNull()
        if (screenType != null) return screenType != 0

        // 退化路径（拿不到私有字段时）：仍按挖孔/尺寸判断
        val task = SoScSupport.field(dot, "mRunningTaskInfo")
        val displayId = (SoScSupport.field(task, "displayId") as? Int) ?: return false
        val cam = OuterDisplay.cameraRectOnScreen(ctx, displayId) ?: return false
        val g = OuterDisplay.geometry(ctx, displayId) ?: return false
        // 竖屏：挖孔顶部居中；横屏：挖孔在侧边 —— 两种都算外屏。
        // ROM 的 isHideSplitButtons 只看「是不是外屏」(isFoldOutScreen)，与横竖无关，
        // 所以这里必须把两种方向都认作外屏，否则横屏分屏按钮会被 ROM 隐藏。
        if (isTopCentered(cam, g.width) || isSideCamera(cam, g.height)) return true
        // 挖孔拿不到/识别不了时，退回按**外屏物理尺寸**认（1168×1712，横竖都算）。
        // 内屏是 1672×2364，不会误判。
        val shortSide = Math.min(g.width, g.height)
        val longSide = Math.max(g.width, g.height)
        return shortSide == OUTER_SHORT_SIDE && longSide == OUTER_LONG_SIDE
    }

    /** 挖孔是否在侧边（横屏时的外屏）。 */
    private fun isSideCamera(cam: Rect, screenHeight: Int): Boolean {
        if (cam.left > 0 && cam.right > 0) return false          // 不在左边缘
        val topGap = cam.top
        val bottomGap = screenHeight - cam.bottom
        return cam.width() <= cam.height() && Math.abs(topGap - bottomGap) < cam.height()
    }

    /**
     * **让展开的子菜单从三点按钮下方弹出，而不是压到状态栏**（用户 2026-11-22 要求）。
     *
     * ## 反编译定位
     * `MiuiDecorationInfo#calculateCaptionOffsetY`：
     * ```java
     * if (taskInfo.getWindowingMode() == 5) return 0;      // 小窗：紧贴圆点，不偏移
     * ...
     * return getFullscreenOffsetY(...);                    // 全屏：返回状态栏高度
     * ```
     * `getFullscreenOffsetY` 在全屏下 `return SystemBarUtils.getStatusBarHeight(context)`
     * ⇒ **全屏应用的子菜单被顶到状态栏高度处**，这正是「太靠上、压到状态栏」的原因。
     *
     * ## 做法
     * 外屏 + 顶部居中挖孔时，改成返回「三点按钮底部再往下一点」的偏移，
     * 让子菜单像小窗那样**从按钮下方展开**。
     *
     * 只改纵向偏移，**不动字节宽度、不动按钮、不动透明**，所以子菜单
     * （左右分屏 / 上下分屏 / 小窗 / 关闭）全部原样显示、原样可用。
     */
    private fun hookCaptionOffset(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, CLS_DECOR_INFO) ?: run {
            Logx.e("摄像头三点菜单: 找不到 $CLS_DECOR_INFO")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "calculateCaptionOffsetY", 6) ?: run {
            Logx.e("摄像头三点菜单: 找不到 calculateCaptionOffsetY/6")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (!Cfg.outerSplit) return@Hooker r
                val ctx = chain.args.getOrNull(0) as? Context ?: return@Hooker r
                val task = chain.args.getOrNull(1)
                val displayId = (SoScSupport.field(task, "displayId") as? Int) ?: return@Hooker r
                val cam = OuterDisplay.cameraRectOnScreen(ctx, displayId) ?: return@Hooker r
                val g = OuterDisplay.geometry(ctx, displayId) ?: return@Hooker r
                if (!isTopCentered(cam, g.width)) return@Hooker r
                // 窗口顶边已经贴在摄像头下沿（见 moveToCamera），这里不再额外下推，
                // 保持「子菜单紧贴按钮下方展开」= 小窗那种手感。
                if (r == 0) return@Hooker r
                Logx.once("cam-caption-offset", "摄像头三点菜单: 子菜单偏移 $r → 0（窗口已在挖孔下沿 $cam）")
                0
            })) 1 else 0
    }

    /**
     * **让外屏也显示分屏按钮**（用户 2026-11-22：「只有全屏和小窗还有关闭，没有其他的分屏按钮」）。
     *
     * ## 反编译找到的 ROM 硬编码
     * `MiuiDecorationDot#isHideSplitButtons()`：
     * ```java
     * private boolean isHideSplitButtons() {
     *     return isDevice("lhasa") && MultiTaskingDeviceUtils.isFoldOutScreen(this.mContext);
     * }
     * ```
     * `isDevice("lhasa")` = 本机机型；`isFoldOutScreen()` = 当前在外屏。
     * ⇒ **ROM 故意在外屏隐藏分屏按钮**（`isHideSplitButtons=true` 会让
     * `MiuiCaptionContainerView#init(...)` 少加 2 个按钮槽位，`i6 = 3 + 0 + ...`）。
     * 真机日志实证：`createHandleMenu: ... isHideSplitButtons=true`。
     *
     * ## 做法
     * 外屏上把这一条改回 false ⇒ 系统按**内屏完全相同的分支**建菜单，
     * 左右/上下分屏按钮、小窗、关闭全部到位；不改任何按钮实现。
     *
     * ## 副作用
     * 分屏按钮出现后菜单会变宽（约 360px），但子菜单已由
     * [hookCaptionOffset] 下移到摄像头下方展开，不会再压状态栏。
     */
    private fun hookHideSplitButtons(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: return 0
        val method = SoScSupport.methodOf(cls, "isHideSplitButtons", 0) ?: run {
            Logx.e("摄像头三点菜单: 找不到 isHideSplitButtons()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                if (Cfg.outerSplit && isOuterTopCamera(chain.thisObject)) {
                    Logx.once("outer-split-buttons", "摄像头三点菜单: 外屏放行分屏按钮（isHideSplitButtons → false）")
                    false
                } else {
                    chain.proceed()
                }
            })) 1 else 0
    }
}
