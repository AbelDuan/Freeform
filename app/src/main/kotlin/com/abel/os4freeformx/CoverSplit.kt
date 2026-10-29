package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * 功能 5：**外屏（合上手机后那块屏）也支持分屏，且与内屏行为一致**。
 *
 * ## 系统到底卡在哪（本机 dex 逐条核对，两处，都是「外屏专属」）
 *
 * ### 1) ··· 菜单里不建分屏按钮
 * `MiuiDecorationDot#isHideSplitButtons()`（private，0 参）：
 * ```java
 * return isDevice("lhasa") && MultiTaskingDeviceUtils.isFoldOutScreen(mContext);
 * ```
 * 调用点 `createHandleMenu(int, String)`（jadx 反编译失败、已用 dexdump 读字节码核对）：
 * ```java
 * boolean hide = isDesktopMode || isHideSplitButtons();
 * container.init(casting, multiInstance, supportSplit, supportFreeform,
 *                isLeftRightSplit, selectedPos, hide, padCasting, inSplit);
 * ```
 * `init` 里 `if (z6) { /* 什么都不建 */ }` ⇒ **外屏的 ··· 菜单固定只有
 * 「全屏 / 小窗 / 关闭」**，连灰掉的"不支持分屏"图标都没有。
 * 菜单宽度也是按 `3 + (hide ? 0 : 2) + 多实例 + 投屏` 算的，同一路标志，改这里宽度自然跟着对。
 *
 * ### 2) 方向被 `isLargeScreen` 锁死
 * SoSc 的 `SplitLayout#updateDynamicLeftRightSplit()` 重新算方向时走
 * `SplitScreenUtils.isLeftRightSplit(allowLR, isLargeScreen, isLandscape)`：
 * ```java
 * if (allowLR && isLargeScreen) return SplitUtilsImpl.isLeftRightSplit();  // 尊重动态方向标志
 * return isLandscape;                                                      // 否则按朝向硬算
 * ```
 * 外屏 `smallestScreenWidthDp < 600` ⇒ `mIsLargeScreen = false` ⇒ **恒走第二行**：
 * 竖屏只能上下、横屏只能左右，用户从菜单里选的方向被静默丢弃。
 * 内屏 `smallestScreenWidthDp ≥ 600`，所以内屏一切正常 —— 这就是"内外屏不一样"的根因。
 *
 * ## 做法（只翻两个开关，不复制系统逻辑）
 * 1. `isHideSplitButtons()` 结果为 true 时改回 false ⇒ 菜单重新建出分屏按钮，
 *    后续「补另一方向一对按钮」（[CaptionSplitBoth]）与补宽逻辑自然接上 ⇒ 四个方向都在；
 * 2. 两个 `SplitScreenUtils.isLeftRightSplit(...)` 静态入口在 `isLargeScreen == false` 时
 *    改为返回 [SoScSupport.effectiveLeftRightSplit]（`左右 = 横屏 == 动态方向标志`），
 *    与内屏同一条判定；于是 `ensureDirection()` 翻转动态标志后布局真的会跟着翻，
 *    「点左就进左」在外屏同样成立。
 *
 * 两个静态类的重载都挂：折叠屏走 `sosc.common.split.SplitScreenUtils(ZZZ)`，
 * 普通分屏（`common.split.SplitLayout` / `MultipleSplitLayout`）走 `common.split.SplitScreenUtils(ZZZI)`。
 *
 * ## 3) 全屏应用连 ··· 把手都没有（v0.8.1）
 * `MultiTaskingDeviceUtils#isFoldInnerScreen(Context)` = `isFoldDevice() && isExpand(ctx)`，
 * 只有内屏为真。而 `MiuiDecorationDot#shouldShowCaptionForWindowingMode()` 里
 * ```java
 * if (windowingMode == 1) return isPadDevice() || isFoldInnerScreen(ctx) || 投屏;
 * ```
 * ⇒ 外屏的全屏应用**根本不建顶部把手**（不是隐藏，是没建），于是外屏全屏时没有任何
 * 进分屏的入口。放开这一条后系统自己会在顶部正中画出 `···`（外屏正中最上方就是摄像头），
 * 点击即出分屏菜单；同一判据还决定「把手能不能被点到」
 * （`MulWinSwitchDecorViewModel#touchedWindowHasCaption`）与「分屏底部小白条的位置」
 * （`MulWinSwitchSoScStateListener#updateSoScHomeParams`），一并放开。
 *
 * ## 4) 比例档位（v0.8.1）
 * `SoScUtilsImpl#supportSplitRatio1_9()` 内层是 `isFold() && !isFoldedState()` ⇒ 只有内屏有 1:9 档。
 * 放开后外屏与内屏用同一套比例档与吸附判定。
 *
 * 只在外屏（`isLargeScreen == false`）且确认是折叠屏时才接管 —— 内屏路径一行都不碰。
 */
object CoverSplit {

    private var loader: ClassLoader? = null

    @Volatile private var unhideLogged = false
    @Volatile private var dirLogged = false
    @Volatile private var innerLogged = false
    @Volatile private var ratioLogged = false
    @Volatile private var touchLogged = false
    @Volatile private var geometryLogged = false

    /**
     * 本机是否折叠屏（`SoScUtilsImpl.isFold()` 读的静态字段 `IS_FOLD` = `persist.sys.muiltdisplay_type==2`）。
     *
     * `null` = 还没问到（SystemUI 刚起时 SoSc 单例可能还没 injectDependencies），
     * 之后每次调用重试一次直到问到 —— 不能一次问不到就永久放弃，否则整个功能静默失效。
     */
    @Volatile private var foldDevice: Boolean? = null
    /** 探测失败的重试次数上限：超过就当折叠屏处理（本模块只服务折叠屏机型）。 */
    private var foldTries = 0

    private fun isFoldDevice(cl: ClassLoader): Boolean {
        foldDevice?.let { return it }
        if (foldTries >= 8) {
            foldDevice = true
            return true
        }
        foldTries++
        val v = SoScSupport.call(SoScSupport.soScUtils(cl), "isFold") as? Boolean ?: return true
        foldDevice = v
        if (!v) Logx.always("外屏分屏: 本机不是折叠屏，方向接管不生效（菜单解锁仍可用）")
        return v
    }

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl

        var ok = 0
        runCatching { ok += hookHideSplitButtons(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 isHideSplitButtons 失败", it) }
        runCatching { ok += hookSoscDirection(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 sosc 方向判定失败", it) }
        runCatching { ok += hookCommonDirection(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 common 方向判定失败", it) }
        runCatching { ok += hookFoldInnerScreen(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 isFoldInnerScreen 失败", it) }
        runCatching { ok += hookRatio19(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 supportSplitRatio1_9 失败", it) }
        runCatching { ok += hookTouchedCaption(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 touchedWindowHasCaption 失败", it) }
        runCatching { ok += hookCaptionGeometry(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 updateCaptionGeometry 失败", it) }
        runCatching { ok += hookCaptionGeometry(m, cl) }
            .onFailure { Logx.e("外屏分屏: 挂 updateCaptionGeometry 失败", it) }

        if (ok == 0) {
            Logx.e("外屏分屏: 一个都没挂上（本机 WMShell 类名可能变了）")
            return
        }
        Logx.always("外屏分屏: 已挂 $ok 个钩子（菜单/方向/内屏判据/比例档/触摸/把手位置；开关=${Cfg.coverSplit}）")
    }

    // ---------------- 1) 外屏 ··· 菜单重新建出分屏按钮 ----------------

    /**
     * `MiuiDecorationDot#isHideSplitButtons(): boolean`。
     *
     * 原实现只有「lhasa + 外屏」一个条件，因此**只要系统想要隐藏就交回 false**，
     * 不需要再判机型/折叠态；内屏本来就返回 false，不受影响。
     */
    private fun hookHideSplitButtons(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_DECOR_DOT}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "isHideSplitButtons", 0) ?: run {
            Logx.e("外屏分屏: 找不到 MiuiDecorationDot#isHideSplitButtons()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                // 菜单构建不在热路径上：顺带节流刷新配置，改完开关打开一次 ··· 即可生效
                Cfg.reloadThrottled()
                val hidden = chain.proceed() as? Boolean ?: false
                if (hidden && Cfg.coverSplit) {
                    if (!unhideLogged) {
                        unhideLogged = true
                        Logx.always("外屏分屏: 已解除外屏「隐藏分屏按钮」（··· 菜单会给出四个方向）")
                    }
                    false
                } else {
                    hidden
                }
            })) 1 else 0
    }

    // ---------------- 2) 外屏方向不再被 isLargeScreen 锁死 ----------------

    /** `com.android.wm.shell.sosc.common.split.SplitScreenUtils#isLeftRightSplit(Z,Z,Z)` */
    private fun hookSoscDirection(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_SOSC_SPLIT_SCREEN_UTILS) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_SOSC_SPLIT_SCREEN_UTILS}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "isLeftRightSplit", 3) ?: run {
            Logx.e("外屏分屏: 找不到 sosc SplitScreenUtils#isLeftRightSplit/3")
            return 0
        }
        return hookDirection(m, method)
    }

    /** `com.android.wm.shell.common.split.SplitScreenUtils#isLeftRightSplit(Z,Z,Z,I)` */
    private fun hookCommonDirection(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_SPLIT_SCREEN_UTILS) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_SPLIT_SCREEN_UTILS}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "isLeftRightSplit", 4) ?: run {
            Logx.e("外屏分屏: 找不到 common SplitScreenUtils#isLeftRightSplit/4")
            return 0
        }
        return hookDirection(m, method)
    }

    /**
     * 两个重载的第 2 个参数都是 `isLargeScreen`。
     *
     * - `isLargeScreen == true`（内屏 / 平板）⇒ **完全不接管**，交回系统；
     * - `isLargeScreen == false`（外屏、手机形态）⇒ 改用与内屏一致的动态方向判定
     *   （[SoScSupport.effectiveLeftRightSplit]，取不到就交回系统，绝不写死方向）。
     *
     * 方向判定在拖分隔条 / 每次 relayout 都会调用，所以这里只读 volatile 开关 + 反射缓存，
     * 不做任何 IO（配置刷新只在 `isHideSplitButtons` 那条低频钩子里做）。
     */
    private fun hookDirection(m: MainHook, method: java.lang.reflect.Method): Int =
        if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val cl = loader
                val isLargeScreen = chain.getArg(1) as? Boolean
                val want = if (cl != null && Cfg.coverSplit && isLargeScreen == false && isFoldDevice(cl)) {
                    SoScSupport.effectiveLeftRightSplit(cl)
                } else {
                    null
                }
                if (want != null) {
                    if (!dirLogged) {
                        dirLogged = true
                        Logx.always("外屏分屏: 方向判定已接管（外屏也按「横屏 == 动态方向标志」算）")
                    }
                    want
                } else {
                    chain.proceed()
                }
            })) 1 else 0

    // ---------------- 3) 外屏按「内屏」处理：全屏应用也有 ··· 把手 ----------------

    /**
     * `MultiTaskingDeviceUtils#isFoldInnerScreen(Context): boolean`（**静态**）。
     *
     * 原实现 = `isFoldDevice() && isExpand(context)` —— 只有展开的内屏为真。
     * 它是多窗口子系统里「这块屏算不算折叠屏大屏」的**总判据**，被十几处直接引用，
     * 其中三处正好是用户这次要的三件事：
     *
     * 1. `MiuiDecorationDot#shouldShowCaptionForWindowingMode()`：
     *    ```java
     *    if (windowingMode == 1) {
     *        return isPadDevice() || isFoldInnerScreen(ctx) || 投屏;
     *    }
     *    ```
     *    ⇒ **外屏的全屏应用压根不建顶部把手**（不是隐藏，是没建）。
     *    放开这一条，系统自己就会在全屏应用顶部正中画出 `···`——外屏正中最上方就是摄像头，
     *    于是「摄像头位置的三点」不需要我们自己画视图，直接用系统那套（点击即出分屏菜单）。
     * 2. `MulWinSwitchDecorViewModel#touchedWindowHasCaption()`：外部触摸能不能落到把手上
     *    （只显示不可点是没用的，这条必须一起放开）。
     * 3. `MulWinSwitchSoScStateListener#updateSoScHomeParams()`：分屏底部小白条的中心与宽度
     *    （决定「从底部上滑」的手势区域是否按分屏来算）。
     *
     * 内屏原本就返回 true，`!r` 不成立 ⇒ 内屏路径一行不碰。
     */
    private fun hookFoldInnerScreen(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_MULTITASKING_DEVICE_UTILS) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_MULTITASKING_DEVICE_UTILS}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "isFoldInnerScreen", 1) ?: run {
            Logx.e("外屏分屏: 找不到 MultiTaskingDeviceUtils#isFoldInnerScreen(Context)")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val c = loader
                val r = chain.proceed() as? Boolean ?: false
                if (!r && c != null && Cfg.coverSplit && isFoldDevice(c)) {
                    if (!innerLogged) {
                        innerLogged = true
                        Logx.always("外屏分屏: 已把外屏当内屏处理（全屏应用顶部 ··· 把手已放开）")
                    }
                    true
                } else {
                    r
                }
            })) 1 else 0
    }

    // ---------------- 4) 比例档位与内屏一致（1:9 档） ----------------

    /**
     * `SoScUtilsImpl#supportSplitRatio1_9(): boolean`。
     *
     * 原实现里有一行 `if (isFold() && !mStageCoordinator.isFoldedState()) return true;`
     * ⇒ **只有内屏支持 1:9 比例档**，外屏恒 false。它同时决定：
     *   - `getRatio(int)` 用哪套档位标签（`getRatioFor1_9` / `getRatioForDefault`）；
     *   - `SplitLayout#initDividerPosition` 里 `is1_9SplitSnapMode()` 之后的分支；
     *   - 分隔条/装饰层对「当前是不是 1:9 态」的判断（`isInSplitRatio1_9LeftState`）。
     *
     * 放开后外屏与内屏同一套档位、同一套吸附判定（内屏本来就是 true，不受影响）。
     */
    private fun hookRatio19(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_SOSC_UTILS_IMPL) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_SOSC_UTILS_IMPL}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "supportSplitRatio1_9", 0) ?: run {
            Logx.e("外屏分屏: 找不到 SoScUtilsImpl#supportSplitRatio1_9()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val c = loader
                val r = chain.proceed() as? Boolean ?: false
                if (!r && c != null && Cfg.coverSplit && isFoldDevice(c)) {
                    if (!ratioLogged) {
                        ratioLogged = true
                        Logx.always("外屏分屏: 比例档位已与内屏对齐（1:9 档在外屏同样可用）")
                    }
                    true
                } else {
                    r
                }
            })) 1 else 0
    }

    // ---------------- 5) 让外屏全屏的 ··· 点得动 ----------------

    /**
     * `MulWinSwitchDecorViewModel#touchedWindowHasCaption(int): boolean`。
     *
     * 把手画出来只是第一步，**触摸还要过这道闸**：
     * ```java
     * if (!fullscreen || mIsStatusbarShowing || 投屏) {
     *     return !(fullscreen || inSoScSingleMode) || isPad || isFoldInnerScreen || 投屏;
     * }
     * Slog.d(TAG, "touchedWindowHasCaption::fullscreen decoration refuse touch event when statusbar is hidden");
     * return false;
     * ```
     * 真机日志（外屏、全屏的抖音）：
     * ```
     * isTouchedInTopCaption: captionTouchRegion = RectF(515.0, 21.0, 653.0, 160.0) point = PointF(586.0, 154.0)
     * MulWinSwitchDecorViewModel: touchedWindowHasCaption::fullscreen decoration refuse touch event when statusbar is hidden
     * ```
     * —— 点在把手正中，仍被"状态栏被隐藏"这条拒掉（外屏上 MIUI 自己的 `mIsStatusbarShowing` 一直是 false）。
     * 这里只在外屏（[isFoldOutScreen] 为真）把 false 改成 true，内屏不受影响。
     */
    private fun hookTouchedCaption(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_VIEW_MODEL) ?: run {
            Logx.e("外屏分屏: 找不到 ${Constants.CLS_DECOR_VIEW_MODEL}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "touchedWindowHasCaption", 1) ?: run {
            Logx.e("外屏分屏: 找不到 MulWinSwitchDecorViewModel#touchedWindowHasCaption(int)")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed() as? Boolean ?: false
                if (!r && Cfg.coverSplit && isFoldOutScreen(chain.thisObject)) {
                    if (!touchLogged) {
                        touchLogged = true
                        Logx.always("外屏分屏: 全屏把手的触摸已被放行（··· 现在点得动）")
                    }
                    true
                } else {
                    r
                }
            })) 1 else 0
    }

    /** `MultiTaskingDeviceUtils#isFoldOutScreen(Context)`：折叠屏 + 当前是外屏。 */
    private fun isFoldOutScreen(owner: Any?): Boolean = runCatching {
        val c = loader ?: return false
        val ctx = SoScSupport.field(owner, "mContext") ?: return false
        SoScSupport.callStatic(
            c, Constants.CLS_MULTITASKING_DEVICE_UTILS, "isFoldOutScreen", ctx
        ) as? Boolean ?: false
    }.getOrDefault(false)

    // ---------------- 6) 把手顶到摄像头里（用户要求：不避位，让挖孔盖住） ----------------

    /**
     * `MiuiDecorationDot#updateCaptionGeometry(RelayoutResult)`（private，1 参）。
     *
     * 系统给把手算 Y 的公式（jadx 反编译 + 真机日志 `mCaptionY=139 captionOffsetY=21 dotY=122`）：
     * ```java
     * mIsDotAvoidingCutout = taskBounds.top == 0 && captionOffsetY != 0 && (...);   // 这里为 true
     * dotY     = captionHeight - dotRadius;        // 126 - 4 = 122（为了躲开挖孔，往下压）
     * mCaptionY = captionOffsetY + dotY - surfaceHeight/2;                          // 21 + 122 - 4 = 139
     * ```
     * 也就是说把手中心被**刻意放到挖孔下沿之下**（143 vs 挖孔中心 63）。
     *
     * 用户要求「不避位摄像头，往上移到与摄像头重合、让挖孔挡住」⇒ 把中心改到
     * `captionHeight / 2`（本机 = 63，正好是挖孔 `Rect(557,0-611,126)` 的垂直中心）。
     * 只改这一个结果对象的 Y，视图 / 触摸 / 菜单逻辑一律不动（触摸区用的是另一套
     * `isTouchedInTopCaption` 的 RectF，不受影响）。
     */
    private fun hookCaptionGeometry(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_DECOR_DOT) ?: return 0
        val method = SoScSupport.methodOf(cls, "updateCaptionGeometry", 1) ?: run {
            Logx.e("外屏分屏: 找不到 MiuiDecorationDot#updateCaptionGeometry(1)")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (Cfg.coverSplit) {
                    runCatching {
                        val dot = chain.thisObject
                        if (isFoldOutScreen(dot)) {
                            val result = chain.getArg(0) ?: return@runCatching
                            val height = (SoScSupport.field(result, "mCaptionHeight") as? Float) ?: 0f
                            if (height <= 0f) return@runCatching
                            val host = SoScSupport.field(dot, "mMiuiDecorationRootViewHost")
                            val surfaceH = (SoScSupport.call(host, "getSurfaceHeight") as? Float) ?: 0f
                            val scale = (SoScSupport.field(dot, "mScale") as? Float)?.takeIf { it > 0f } ?: 1f
                            val half = (surfaceH / scale) / 2f
                            val want = (height / 2f - half).toInt().coerceAtLeast(0)
                            val old = (SoScSupport.field(result, "mCaptionY") as? Int) ?: return@runCatching
                            if (old != want) {
                                SoScSupport.setField(result, "mCaptionY", want)
                                // 取色监听的采样矩形跟着同一处偏移，否则颜色采样会错位
                                val sampling = (SoScSupport.field(result, "mSamplingOffsetY") as? Int) ?: old
                                SoScSupport.setField(result, "mSamplingOffsetY", sampling - (old - want))
                                if (!geometryLogged) {
                                    geometryLogged = true
                                    Logx.always("外屏分屏: 把手已上移到挖孔中心（y $old → $want）")
                                }
                            }
                        }
                    }.onFailure { Logx.e("外屏分屏: 上移把手失败", it) }
                }
                r
            })) 1 else 0
    }
}
