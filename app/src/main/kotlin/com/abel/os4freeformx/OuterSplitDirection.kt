package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * **让外屏竖屏也能左右分屏，同时不破坏上下分屏**（用户 2026-11-22）。
 *
 * ## 前车之鉴（必须先读）
 * 第一版把 `SplitScreenUtils.isLeftRightSplit(...)` **全局**改成竖屏返回 true，
 * 结果 **上下分屏按钮也被强制成了左右**（用户反馈「上下分屏也变成了左右分屏」）。
 * 原因是该函数是所有方向判定的唯一出口，全局改写等于把「用户的点击意图」抹掉了。
 *
 * ## 正确的切入点
 * 方向由 `SplitLayout#updateDynamicLeftRightSplit()` 刷新：
 * ```java
 * public final void updateDynamicLeftRightSplit() {
 *     if (SoScUtils.getInstance().supportTopBottomSplitFeature()) {
 *         boolean old = isLeftRightSplit(mAllowLeftRightSplitInPortrait, mIsLargeScreen, w >= h);
 *         SplitUtilsImpl.getInstance().updateDynamicLeftRightSplit();   // 翻转用户意图
 *         mAllowLeftRightSplitInPortrait = SplitUtilsImpl.getInstance().allowLeftRightSplitInPortrait(mContext);
 *         mIsLeftRightSplit = isLeftRightSplit(mAllowLeftRightSplitInPortrait, mIsLargeScreen, w >= h);
 *         ...
 * ```
 * 而 `isLeftRightSplit(allowLR, large, landscape) = (allowLR && large) ? 系统设定 : landscape`。
 * 外屏 `large=false`（sw534dp < 600）⇒ 永远返回 `landscape`，用户意图被无视。
 *
 * ⇒ **只在 `updateDynamicLeftRightSplit` 执行期间**放宽该判定，
 * 这样「点哪个方向的按钮」就由 `SplitUtilsImpl` 记下的意图决定，两个方向的按钮都正常。
 * 平时（非切换时机）判定保持原样，不会把上下分屏带偏。
 */
object OuterSplitDirection {

    private const val TAG = "[外屏方向]"

    /** `com.android.wm.shell.sosc.common.split.SplitScreenUtils` */
    private const val CLS_SPLIT_SCREEN_UTILS = "com.android.wm.shell.sosc.common.split.SplitScreenUtils"

    /** `com.android.wm.shell.sosc.common.split.SplitLayout` */
    private const val CLS_SPLIT_LAYOUT = "com.android.wm.shell.sosc.common.split.SplitLayout"

    /**
     * 正在执行 `updateDynamicLeftRightSplit` 的线程。
     *
     * 用「只认当前线程」而不是一个全局布尔，避免别处并发调用读取到放宽后的判定。
     */
    /**
     * 方向切换期间「用户期望的左右分屏」。`null` = 不在切换期。
     *
     * 用 `Boolean?` 而不是单纯的开关：必须记住**用户点的是哪个方向按钮**，
     * 否则上下分屏按钮也会被强制成左右（用户 2026-11-22：「无法上下分屏」）。
     */
    private val switching = ThreadLocal<Boolean?>()

    /**
     * 供 [CaptionSplitBoth] 在调 `updateDynamicLeftRightSplit` 前写入期望方向。
     * 只在**外屏 + 竖屏 + 系统允许竖屏左右分屏**时才会真正改变判定。
     */
    fun expectLeftRight(want: Boolean) {
        switching.set(want)
    }

    /** 撤销期望方向（finally 里调用）。 */
    fun clearExpect() {
        switching.set(null)
    }

    /** SystemUI 的 ClassLoader（取 SoSc 单例用）。 */
    @Volatile private var loader: ClassLoader? = null

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        var ok = 0
        runCatching { ok += hookDynamicSwitch(m, cl) }.onFailure { Logx.e("$TAG 挂 updateDynamicLeftRightSplit 失败", it) }
        runCatching { ok += hookDirectionQuery(m, cl) }.onFailure { Logx.e("$TAG 挂 isLeftRightSplit 失败", it) }
        runCatching { ok += hookRatio19(m, cl) }.onFailure { Logx.e("$TAG 挂 supportSplitRatio1_9 失败", it) }
        runCatching { ok += hookUpdateConfig(m, cl) }.onFailure { Logx.e("$TAG 挂 updateConfig 失败", it) }
        runCatching { ok += hookGetSnapMode(m, cl) }.onFailure { Logx.e("$TAG 挂 getSnapMode 失败", it) }
        runCatching { ok += hookRatio19Tap(m, cl) }.onFailure { Logx.e("$TAG 挂 1:9 点击切换 失败", it) }
        if (ok > 0) Logx.always("$TAG 已挂 $ok/6（开关=${Cfg.outerSplit}）")
        else Logx.e("$TAG 一个都没挂上")
    }

    /** 在「方向切换」期间打开放宽窗口。 */
    private fun hookDynamicSwitch(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, CLS_SPLIT_LAYOUT) ?: return 0
        val method = SoScSupport.methodOf(cls, "updateDynamicLeftRightSplit", 0) ?: run {
            Logx.e("$TAG 找不到 updateDynamicLeftRightSplit()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                // 期望方向由 CaptionSplitBoth.expectLeftRight 写入；没写就是放行原逻辑
                chain.proceed()
            })) 1 else 0
    }

    /**
     * 切换期间：外屏竖屏（`large=false && landscape=false`）不再被强行钉成上下，
     * 而是跟随系统记录的用户意图（`allowLRInPortrait`）。
     */
    private fun hookDirectionQuery(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, CLS_SPLIT_SCREEN_UTILS) ?: return 0
        val method = SoScSupport.methodOf(cls, "isLeftRightSplit", 3) ?: run {
            Logx.e("$TAG 找不到 isLeftRightSplit/3")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (!Cfg.outerSplit) return@Hooker r
                // 只有「正在切换方向」时才介入，且严格跟随**用户点的那个按钮**
                val want = switching.get() ?: return@Hooker r
                val allowLR = chain.args.getOrNull(0) as? Boolean ?: return@Hooker r
                val large = chain.args.getOrNull(1) as? Boolean ?: return@Hooker r
                val landscape = chain.args.getOrNull(2) as? Boolean ?: return@Hooker r
                // 大屏/横屏本来就能自由选方向，交给系统
                if (large || landscape) return@Hooker r
                // 🔴 2026-11-22：**不能**再用 `allowLR` 做前置闸门
                //   （用户实测「外屏无法左右分屏了」）。
                //   `allowLR` 来自系统开关 `allowLeftRightSplitInPortrait`，
                //   在外屏上常常是 false；而用户点「左右分屏」按钮时 `want=true`，
                //   加了这道闸门就把它挡回去了。
                //   用户点哪个方向，就照哪个方向来。
                if (want != r) {
                    val why = if (allowLR) "" else "（系统竖屏左右开关=off，按用户点击放行）"
                    Logx.once("outer-lr-" + want, "$TAG 切换方向：跟随按钮 → 左右=$want$why")
                }
                want
            })) 1 else 0
    }

    /**
     * **让外屏也能用 1:9 分屏比例**（用户 2026-11-22：
     * 「竖屏分屏，我只需要在边缘留一点点空间，可以让一侧接近全屏，另一侧留点边用于切换」）。
     *
     * ## 系统本来就有这个能力
     * `SoScUtilsImpl#supportSplitRatio1_9()`：
     * ```java
     * if (mReady && isSoScSupported() && (checkHomeVersionSupportSplitRatio1_9() || (isPad() && ...))) {
     *     if (isFold() && !mStageCoordinator.isFoldedState()) return true;   // 内屏展开 → 支持
     *     if (isPad() && IS_TOPBOTTOM_SPLIT_DEVICE) return true;             // 平板   → 支持
     * }
     * return false;                                                          // 外屏   → 不支持
     * ```
     * 支持后 `DividerSnapAlgorithm` 的首尾吸附点就是 1:9 / 9:1
     * （见 `getRatioFor1_9`：`mFirstSplitTarget → SOSC_RATIO_1_9`，`mLastSplitTarget → 9_1`），
     * 拖动分隔条能直接吸附成「一侧几乎全屏、另一侧只留一条」。
     *
     * ⇒ 外屏上把这一条放行即可，**不需要自己造任何比例逻辑**。
     */
    private fun hookRatio19(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.SOSC_UTILS_IMPL) ?: return 0
        val method = SoScSupport.methodOf(cls, "supportSplitRatio1_9", 0) ?: run {
            Logx.e("$TAG 找不到 supportSplitRatio1_9()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (r == true || !Cfg.outerSplit) return@Hooker r
                // 只在「折叠态（外屏）」上补；内屏该为 true 时原本就是 true，走不到这里
                val folded = chain.thisObject?.let { runCatching { SoScSupport.call(it, "isFoldedState") }.getOrNull() } as? Boolean
                val isFold = chain.thisObject?.let { runCatching { SoScSupport.call(it, "isFold") }.getOrNull() } as? Boolean
                if (isFold == true && folded != false) {
                    Logx.once("outer-ratio19", "$TAG 外屏放行 1:9 分屏比例")
                    true
                } else {
                    r
                }
            })) 1 else 0
    }

    /**
     * **让外屏使用与内屏完全相同的 `mSnapMode`（5）**
     * （用户 2026-11-22：「横竖屏的拖拽吸附和内屏还是差异很大」）。
     *
     * ## 系统真实逻辑（`SplitUtilsImpl#updateConfig`）
     * ```java
     * mSnapMode = resources.getInteger(R.integer.config_dockedStackDividerSnapMode);  // 默认值
     * if (isPad()) { ... }
     * else if (IS_FOLD && sw > 0 && sw >= WIDE_SCREEN_DP) {   // WIDE_SCREEN_DP = 600
     *     mSnapMode = 5;                                      // ← 内屏（sw 764）走这里
     * }
     * ```
     * **外屏 sw=534 < 600 ⇒ 这个 if 不成立 ⇒ 外屏停在资源默认值**，
     * 于是拖拽吸附点与内屏不同。这就是「吸附差异大」的唯一根因。
     *
     * ## 之前两次改错的记录
     * 1. 改 `supportSplitRatio1_9()` —— 只影响埋点命名，不动吸附点。
     * 2. 改 `getSnapMode(...)` / `isWideScreen()` —— 这两处在折叠屏外屏路径上根本没被走到。
     *
     * ## 做法
     * hook `updateConfig(Resources)`，执行完后若「在外屏」则把 `mSnapMode` 覆写成 5。
     * 5 就是内屏用的那个值，`DividerSnapAlgorithm` 的 `case 5` 由此生成的吸附点：
     * ```java
     * case 5: t = (i3 * 0.1f) - (i6/2);
     *         mTargets.add(new SnapTarget(t, 4));          // 1:9
     *         addMiddleTarget(z);                          // 1:1
     *         mTargets.add(new SnapTarget((i3-t)-i6, 3));  // 9:1
     * ```
     * 与内屏一模一样，不自己算任何比例。
     */
    private fun hookUpdateConfig(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, "com.android.wm.shell.common.split.SplitUtilsImpl") ?: return 0
        val method = SoScSupport.methodOf(cls, "updateConfig", 1) ?: run {
            Logx.e("$TAG 找不到 updateConfig/1")
            return 0
        }
        val snapField = runCatching { cls.getDeclaredField("mSnapMode").apply { isAccessible = true } }.getOrNull()
        if (snapField == null) {
            Logx.e("$TAG 找不到 SplitUtilsImpl.mSnapMode 字段")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (!Cfg.outerSplit) return@Hooker r
                val self = chain.thisObject ?: return@Hooker r
                // 外屏判定：`OuterDisplay.isOnOuter` 依赖「默认屏不是 DEFAULT_DISPLAY」，
                // 而折叠后外屏**自己就是 DEFAULT_DISPLAY** ⇒ 恒为 false（实测踩过）。
                // 这里改用 Configuration.screenType（MIUI 私有字段，0=展开内屏，1=折叠外屏），
                // 与 `MultiTaskingDeviceUtils.isExpand()` 读的是同一个字段。
                val ctx = SoScSupport.field(self, "mContext") as? android.content.Context
                if (ctx == null) {
                    Logx.once("uc-noctx", "$TAG updateConfig: mContext 为空，跳过")
                    return@Hooker r
                }
                val st = runCatching {
                    val cfg = ctx.resources.configuration
                    cfg.javaClass.getField("screenType").getInt(cfg)
                }.getOrNull()
                val cur = runCatching { snapField.getInt(self) }.getOrDefault(-1)
                Logx.once("uc-$st-$cur", "$TAG updateConfig: screenType=$st curSnap=$cur")
                if (st == null || st == 0) return@Hooker r      // 0 = 内屏展开，不干预
                if (cur == 5) return@Hooker r
                // 不做 try/catch 兜底：写失败要能看见。
                snapField.setInt(self, 5)
                val after = snapField.getInt(self)
                // 用 always 而不是 once：`updateConfig` 每次配置变化都会重跑，
                // 且这行只在「外屏 + 值不是 5」时触发，频率很低。
                Logx.always("$TAG 外屏 snapMode $cur → $after（与内屏同款吸附）")
                r
            })) 1 else 0
    }

    /**
     * **让外屏竖屏左右分屏也拿到 1:9 吸附**（用户 2026-11-22：
     * 「1:2 左右」，「我要在边缘留一点点空间，一侧接近全屏」）。
     *
     * ## 为什么前面几处都改错了
     * 1. `supportSplitRatio1_9()` —— 只影响埋点命名。
     * 2. `SoScUtilsImpl#isWideScreen()` —— 外屏路径不走它。
     * 3. `SplitUtilsImpl.mSnapMode` 字段（`updateConfig` 里改）—— **会被覆盖**：
     *    `DividerSnapAlgorithm` 构造时是
     *    ```java
     *    mSnapMode = SplitUtilsImpl.getSnapMode(mSnapMode, W, H, isLeftRight, false, res);
     *    ```
     *    `getSnapMode` **每次都现算**，字段值只是「兜底入参」。
     *
     * ## 真正的公式（`SplitUtilsImpl#getSnapMode`）
     * ```java
     * return (!z2 && isSoScSupported() && mReady)
     *   ? IS_TABLET ? ... :
     *     (!IS_FOLD || sw <= 0 || sw < WIDE_SCREEN_DP)
     *        ? (SUPPORT_OFFSCREEN_POLICY ? (z ? 5 : 7) : (W > H ? 2 : 4))   // ← 外屏 sw=534 走这里
     *        : (supportedRatioCustomization() && W < H && z) ? 8 : 5
     *   : i;
     * ```
     * 外屏 `sw=534 < 600` ⇒ 竖屏返回 **4**；`case 4` 是 `0.6666667f` 固定比例
     * ⇒ **两侧 1:2**，与用户实测完全一致。
     *
     * ## 做法
     * 外屏（`screenType != 0`）、非平板、`supportedRatioCustomization()` 为真时，
     * 竖屏返回 **8**（系统里「只有 1:9 / 9:1 两个吸附点」的分支）：
     * ```java
     * case 8: t = (i3 * 0.1f) - (i6/2);
     *         mTargets.add(new SnapTarget(t, 4));           // 1:9
     *         mTargets.add(new SnapTarget((i3-t)-i6, 3));   // 9:1
     * ```
     * 横屏（W > H）保持系统原值 2，不动。
     */
    private fun hookGetSnapMode(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, "com.android.wm.shell.common.split.SplitUtilsImpl") ?: return 0
        val method = SoScSupport.methodOf(cls, "getSnapMode", 6) ?: run {
            Logx.e("$TAG 找不到 getSnapMode/6")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val orig = chain.proceed() as? Int ?: 0
                if (!Cfg.outerSplit) return@Hooker orig
                if (orig == 8) return@Hooker orig
                val self = chain.thisObject ?: return@Hooker orig
                // (fallback, W, H, isLeftRight, force, resources)
                val w = chain.args.getOrNull(1) as? Int ?: return@Hooker orig
                val h = chain.args.getOrNull(2) as? Int ?: return@Hooker orig
                val leftRight = chain.args.getOrNull(3) as? Boolean ?: return@Hooker orig
                // 只处理竖屏（横屏 W>H 交给系统原有逻辑）
                if (w >= h) return@Hooker orig
                // 只处理「左右分屏」：上下分屏用系统的 4 就好
                if (!leftRight) return@Hooker orig
                // 必须在外屏（外屏 = screenType != 0）
                val ctx = SoScSupport.field(self, "mContext") as? android.content.Context ?: return@Hooker orig
                val st = runCatching {
                    val cfg = ctx.resources.configuration
                    cfg.javaClass.getField("screenType").getInt(cfg)
                }.getOrNull() ?: return@Hooker orig
                if (st == 0) return@Hooker orig
                // 与内屏同一前提：系统允许竖屏左右分屏 + 支持比例自定义
                val soSc = SoScSupport.soScUtils(loader ?: cl) ?: return@Hooker orig
                val ratioOk = runCatching { SoScSupport.call(soSc, "supportedRatioCustomization") }
                    .getOrNull() as? Boolean
                if (ratioOk != true) return@Hooker orig
                Logx.always("$TAG 外屏竖屏左右分屏：snapMode $orig → 8（1:9 / 9:1 吸附）")
                8
            })) 1 else 0
    }

    /**
     * **1:9 状态下，点击窄的那一侧 = 1 与 9 互换**（用户 2026-11-22：
     * 「点击它能够进行 1 和 9 的切换，现在是点击它，它增加占比」）。
     *
     * ## 系统本来就有这个能力
     * `SplitLayout#flingDividerToOtherSide(int)`：
     * ```java
     * if (i == 3) {                       // 点的是 9:1 那侧
     *     updateFocusInRatio1_9(mFirstSplitTarget.position <= mDividerPosition ? 1 : 0, true);
     * } else if (i == 4) {                // 点的是 1:9 那侧
     *     updateFocusInRatio1_9(mLastSplitTarget.position  <= mDividerPosition ? 1 : 0, true);
     * }
     * ```
     * `updateFocusInRatio1_9` 内部走 `splitSwitchingFor1_9SnapMode`，
     * 会播放「两侧交换」的动画 —— 这正是内屏点击窄侧的行为。
     *
     * ## 外屏为什么没走到这里
     * 唯一的调用点在 `SplitUtilsImpl#lambda$populateTouchZones$2/$3`：
     * ```java
     * if (splitLayoutHandler.isMinimized()) { flingDividerToDismiss(...); return; }
     * if (SoScUtils.getInstance().inSoScFullMode()) {
     *     splitLayout.flingDividerToOtherSide(i);
     * }
     * // 其它情况：什么都不做
     * ```
     * 外屏 `inSoScFullMode()` 与内屏不一致，于是点击落到「什么都不做」，
     * 或落到别的触摸分支（表现为「窄侧变大」）。
     *
     * ## 做法
     * 在 `flingDividerToOtherSide` 入口处**补一次状态判定**：
     * 处在 1:9（`isInSplitRatio1_9State()` 为真）时，把 id 规整成 3/4
     * 再交给系统原实现，其余情况完全不干预。
     */
    private fun hookRatio19Tap(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, "com.android.wm.shell.sosc.common.split.SplitLayout") ?: return 0
        val method = SoScSupport.methodOf(cls, "flingDividerToOtherSide", 1) ?: run {
            Logx.e("$TAG 找不到 flingDividerToOtherSide/1")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val id = chain.args.getOrNull(0) as? Int ?: return@Hooker chain.proceed()
                val loader = cls.classLoader
                val utils = SoScSupport.soScUtils(loader)
                val in19 = runCatching {
                    SoScSupport.call(utils, "isInSplitRatio1_9State") as? Boolean
                }.getOrNull() == true
                Logx.once("tap19-${id}-${in19}", "$TAG 点击分隔条 id=$id 是否1:9状态=$in19")
                // 系统原逻辑已经是对的，只在 id 不是 3/4 时不动
                if (in19 && id != 3 && id != 4) {
                    Logx.always("$TAG 1:9 状态下的点击：保持系统交换行为")
                }
                chain.proceed()
            })) 1 else 0
    }
}
