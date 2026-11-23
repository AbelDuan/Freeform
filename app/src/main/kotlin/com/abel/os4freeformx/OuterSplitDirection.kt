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

    /** 内屏使用的 snapMode（系统 `getSnapMode` 在折叠屏内屏恒返回它）。 */
    private const val SNAP_MODE_INNER = 5

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

    /**
     * 当前线程是否正处在「用户点击了某个方向按钮」的切换期。
     *
     * [SplitDirection] 的全局方向兼容层读它：切换期内**必须让位于用户点击的意图**，
     * 否则 `effectiveLeftRightSplit()` 会把用户点的「上下」又算回「左右」
     * （或反之），两个 hook 互相打架。
     */
    fun expectingInThisThread(): Boolean = switching.get() != null

    /** SystemUI 的 ClassLoader（取 SoSc 单例用）。 */
    @Volatile private var loader: ClassLoader? = null

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        var ok = 0
        runCatching { ok += hookDynamicSwitch(m, cl) }.onFailure { Logx.e("$TAG 挂 updateDynamicLeftRightSplit 失败", it) }
        runCatching { ok += hookDirectionQuery(m, cl) }.onFailure { Logx.e("$TAG 挂 isLeftRightSplit 失败", it) }
        runCatching { ok += hookRatio19(m, cl) }.onFailure { Logx.e("$TAG 挂 supportSplitRatio1_9 失败", it) }
        runCatching { ok += hookGetSnapMode(m, cl) }.onFailure { Logx.e("$TAG 挂 getSnapMode 失败", it) }
        runCatching { ok += hookRatio19Tap(m, cl) }.onFailure { Logx.e("$TAG 挂 1:9 点击切换 失败", it) }
        runCatching { ok += hookTopBottomFeature(m, cl) }.onFailure { Logx.e("$TAG 挂 supportTopBottomSplitFeature 失败", it) }
        if (ok > 0) Logx.always("$TAG 已挂 $ok/6（开关=${Cfg.outerSplit}）")
        else Logx.e("$TAG 一个都没挂上")
    }

    /** 在「方向切换」期间打开放宽窗口。 */
    /**
     * 用户通过「分隔条菜单里的方向按钮」显式选定的方向。`null` = 没选过，跟随系统。
     *
     * ## 为什么必须记住这个值（2026-11-22 真机日志实证）
     *
     * 外屏 `isLeftRightSplit` 系统公式恒等于 `landscape`：
     * ```java
     * (allowLR && large) ? 用户设置 : landscape
     * //         ↑ false        ↑ sw=534 -> false      ↑ 竖屏 false / 横屏 true
     * ```
     * 而 `SoScStageCoordinator#switchSplitDirection` 的**第二行**就是
     * `this.mSplitLayout.updateDynamicLeftRightSplit();` —— 每点一次按钮，
     * 这段就把方向**按 landscape 重算一遍**，于是：
     * ```
     * 21:06:06  old: true,  new: true   → 翻成 false
     * 21:06:17  old: false, new: false  → 翻成 true
     * 21:06:19  old: false, new: false  → 没有翻转日志（状态被上一步搅乱）
     * ```
     * **「翻转当前值」这种写法本身就错**：`updateDynamicLeftRightSplit` 是幂等的
     * （它永远只按屏幕方向算），所以「和进入前比有没有变」这个判据永远成立，
     * 每调一次就翻一次，方向在两端来回横跳。
     *
     * ## 正确做法
     * 记住**用户选定的方向**，让 `updateDynamicLeftRightSplit` 算完之后
     * 直接采用它；只有用户没选过时才完全跟随系统。
     * 屏幕旋转（配置变化）时清空 —— 新方向按新的屏幕几何重新开始。
     */
    @Volatile
    private var userChoiceLr: Boolean? = null

    /** 上次见到的分割轴几何（宽,高）。用于识别屏幕旋转。 */
    @Volatile
    private var lastGeometry: Pair<Int, Int>? = null

    /** 上一次「应用过方向」时的几何。与 [lastGeometry] 分开：后者只负责检测旋转。 */
    @Volatile
    private var appliedGeometry: Pair<Int, Int>? = null

    private fun hookDynamicSwitch(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, CLS_SPLIT_LAYOUT) ?: return 0
        val method = SoScSupport.methodOf(cls, "updateDynamicLeftRightSplit", 0) ?: run {
            Logx.e("$TAG 找不到 updateDynamicLeftRightSplit()")
            return 0
        }
        val lrField = runCatching {
            cls.getDeclaredField("mIsLeftRightSplit").apply { isAccessible = true }
        }.getOrNull()
        if (lrField == null) {
            Logx.e("$TAG 找不到 SplitLayout.mIsLeftRightSplit")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                chain.proceed()
                val self = chain.thisObject ?: return@Hooker null
                if (!Cfg.outerSplit) return@Hooker null
                if (!isOuterScreen(self)) return@Hooker null
                // 屏幕旋转 ⇒ 几何关系变了（横屏/竖屏的轴长、可用比例完全不同），
                // 上一轮的用户选择不再适用，回到跟随系统。用「根边界宽高」判定，
                // 这是 `updateDynamicLeftRightSplit` 每帧都在用的同一个量，最可靠。
                val root = SoScSupport.field(self, "mRootBounds") as? android.graphics.Rect
                val geom = root?.let { it.width() to it.height() }
                if (geom != null && geom != lastGeometry) {
                    if (lastGeometry != null) clearUserDirection()
                    lastGeometry = geom
                }
                val want = userChoiceLr ?: return@Hooker null
                val cur = runCatching { lrField.getBoolean(self) }.getOrNull() ?: return@Hooker null
                // 🔴 2026-11-22：**不能因为「方向已经对了」就提前 return**。
                //   真机日志：
                //   ```
                //   21:44:16 记录用户选定方向：左右=true      ← 切到左右，没有重置分隔条
                //   21:44:22 记录用户选定方向：左右=true
                //   21:44:22 分隔条已按 setDividerPosition(572)
                //   ```
                //   第一次切到左右时，系统 `isLeftRightSplit` 恰好也算出 true（横屏时
                //   恒等于 landscape），于是 `cur == want` → 直接返回 → **吸附算法和
                //   分隔条位置都没跟着新轴重建**，要再点一次才补上。
                //   方向对不代表几何对：横轴/纵轴长度完全不同，必须照常重建。
                val changed = cur != want
                if (changed) lrField.setBoolean(self, want)
                // 只有「方向真的翻转」或「几何变了（横竖屏切换）」才重建算法并归位；
                // 否则每帧都重置会把用户正在拖动的分隔条拽回正中。
                val geometryChanged = geom != null && geom != appliedGeometry
                if (!changed && !geometryChanged) return@Hooker null
                appliedGeometry = geom
                rebuildSnapAlgorithm(cls, self)
                // 松手后要吸附回的目标：设为新算法的中点，与 setDividerPosition 一致
                runCatching {
                    val algo = cls.getDeclaredField("mDividerSnapAlgorithm").apply { isAccessible = true }.get(self)
                    SoScSupport.field(algo, "mMiddleTarget")?.let {
                        SoScSupport.setField(self, "mFixedSnapTarget", it)
                    }
                }
                // 🔴 方向变了，**必须走系统的 setDividerPosition 把位置同步到视图**
                //   （2026-11-22 用户实测：「切换的时候分隔条和中间小条的位置异常」）。
                //
                //   第一版错误做法：直接写 mDividerPosition 字段再调 updateStateWithCurrentPosition()。
                //   => 只有「状态数字」对了，**视图根本没动**：分隔条矩形、把手位置、
                //      两侧窗口边界全都还是旧轴算出来的，于是画面错位。
                //
                //   系统的正确入口是：
                //   ```java
                //   setDividerPosition(int i, boolean z) {
                //       mDividerPosition = i;                    // ① 记位置
                //       updateBounds(i);                         // ② 重算两侧边界 + 分隔条矩形
                //       if (z) mSplitLayoutHandler.onLayoutSizeChanged(this);  // ③ 让视图重新摆放
                //   }
                //   ```
                //   三步缺一不可 —— ②③ 才是真正把位置画到屏幕上的地方。
                runCatching {
                    val algoField = cls.getDeclaredField("mDividerSnapAlgorithm").apply { isAccessible = true }
                    val algo = algoField.get(self) ?: return@runCatching
                    val midPos = SoScSupport.field(SoScSupport.field(algo, "mMiddleTarget"), "position") as? Int
                        ?: return@runCatching
                    // z=true 才会触发 onLayoutSizeChanged 让视图重排
                    SoScSupport.call(self, "setDividerPosition", midPos, true)
                    Logx.always("$TAG 分隔条已按 setDividerPosition($midPos) 同步到视图")
                }.onFailure { Logx.e("$TAG 切换方向：重置分隔条位置失败", it) }
            })) 1 else 0
    }

    /** 方向变了必须按新轴重建吸附算法，否则档位还是旧轴的像素值（1168 轴 / 1712 轴差一个量级）。 */
    private fun rebuildSnapAlgorithm(cls: Class<*>, self: Any) {
        val ctx = SoScSupport.field(self, "mContext") as? android.content.Context ?: return
        val root = SoScSupport.field(self, "mRootBounds") as? android.graphics.Rect ?: return
        val algoField = runCatching {
            cls.getDeclaredField("mDividerSnapAlgorithm").apply { isAccessible = true }
        }.getOrNull() ?: return
        runCatching { SoScSupport.call(self, "getSnapAlgorithm", ctx, root) }
            .getOrNull()?.let { runCatching { algoField.set(self, it) } }
    }

    /** 用户显式选定方向（按钮点的就是这个方向）。 */
    fun setUserDirection(leftRight: Boolean) {
        userChoiceLr = leftRight
        Logx.always("$TAG 记录用户选定方向：左右=$leftRight")
    }

    /** 屏幕旋转等配置变化时清空用户选择，回到跟随系统。 */
    fun clearUserDirection() {
        if (userChoiceLr != null) {
            userChoiceLr = null
            Logx.always("$TAG 配置变化，清除用户选定方向，回跟随系统")
        }
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

    // —— 已删除 `hookUpdateConfig`（2026-11-22）——
    // 它把 `SplitUtilsImpl.mSnapMode` **字段**改成 5，但 `DividerSnapAlgorithm` 构造时是
    //   `mSnapMode = getSnapMode(mSnapMode, W, H, isLeftRight, false, res);`（现算）
    // 字段值只是入参兜底，被现算结果覆盖 ⇒ 该做法**从未生效**。
    // 真正生效的是 [`hookGetSnapMode`] 改写 `getSnapMode` 的返回值。

    /**
     * **让外屏的拖拽吸附与内屏完全一致**（用户 2026-11-22）。
     *
     * ## 唯一正确的切入点：`SplitUtilsImpl#getSnapMode` 的**返回值**
     * `DividerSnapAlgorithm` 每次构造都现算：
     * ```java
     * mSnapMode = SplitUtilsImpl.getInstance().getSnapMode(mSnapMode, W, H, isLeftRight, false, res);
     * ```
     * 所以改 `updateConfig` 里那个**字段**是无效的（会被这次现算覆盖）。
     *
     * ## 系统真实公式（反编译 `Miui-WindowManager-Shell.jar` 实证）
     * ```java
     * return (!force && isSoScSupported() && mReady)
     *   ? IS_TABLET ? ((W <= H && leftRight) ? 5 : 7)
     *   : (!IS_FOLD || sw <= 0 || sw < 600)          // ← 外屏 sw=534：走这条
     *       ? (SUPPORT_OFFSCREEN_POLICY ? (leftRight ? 5 : 7)
     *                                  : (W > H ? 2 : 4))
     *       : ((supportedRatioCustomization() && W < H && leftRight) ? 8 : 5)   // ← 内屏：恒为 5
     *   : fallback;
     * ```
     * ⇒ **内屏无论横竖都返回 5**；外屏被 `sw < 600` 挡在门外，
     *   竖屏拿到 **4**（`0.6666` 固定比例 = 两侧 1:2），横屏拿到 **2**（只有 1:1）。
     *
     * ## 各 snapMode 生成的吸附点（`DividerSnapAlgorithm#calculateTargets`）
     * | mode | 吸附点 | 是否符合用户要求 |
     * |---|---|---|
     * | 2 | 只有 1:1 | ❌ 外屏横屏「只有 5:5」的根因 |
     * | 4 | 两侧各 2/3 → **1:2** | ❌ 不是 1:9 |
     * | 5 | **1:9 · 1:1 · 9:1** | ✅ 内屏用的就是这个，正式要求 |
     * | 7 | 1:9 · 1:1 · 1:3 · 9:1 | ⚠️ 多一个 1:3，与内屏不一致 |
     * | 8 | 只有 1:9 · 9:1（无中点） | ❌ 缺 5:5 |
     *
     * ## 做法
     * 外屏（`screenType != 0`）一律返回 **5**，与内屏**逐字相同**：
     * - 竖屏（上下分屏、左右分屏）= 1:9 / 5:5 / 9:1；
     * - 横屏（左右分屏、上下分屏）= 1:9 / 5:5 / 9:1。
     * 不自己造任何档位、不写状态机字段 —— 因此「点窄侧切换主应用」由系统
     * `flingDividerToOtherSide(3/4)` 原生处理，与内屏一致。
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
                if (orig == SNAP_MODE_INNER) return@Hooker orig
                val self = chain.thisObject ?: return@Hooker orig
                // (fallback, W, H, isLeftRight, force, resources)
                if (chain.args.getOrNull(4) as? Boolean == true) return@Hooker orig  // force 时交还系统
                if (!isOuterScreen(self)) return@Hooker orig
                Logx.once("outer-snap-$orig", "$TAG 外屏 snapMode $orig → $SNAP_MODE_INNER（与内屏同款 1:9/5:5/9:1）")
                SNAP_MODE_INNER
            })) 1 else 0
    }

    /**
     * 判定当前是否处在**外屏（折叠态）**。
     *
     * 用 MIUI 私有的 `Configuration.screenType`（与 `MultiTaskingDeviceUtils.isExpand()` 同源）：
     * `0` = 内屏展开，非 0 = 折叠外屏。
     *
     * 不用 `OuterDisplay.isOnOuter()` —— 它按「默认屏不是 DISPLAY_DEFAULT」判断，
     * 而折叠后外屏**自己就是默认屏**，恒为 false（真机踩过）。
     */
    private fun isOuterScreen(self: Any): Boolean {
        val ctx = SoScSupport.field(self, "mContext") as? android.content.Context ?: return false
        val st = runCatching {
            val cfg = ctx.resources.configuration
            cfg.javaClass.getField("screenType").getInt(cfg)
        }.getOrNull() ?: return false
        return st != 0
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

    /**
     * **让外屏竖屏能切到左右分屏**（用户 2026-11-22：
     * 「竖屏的时候，只有上下分屏，左右分屏切换不了，比例上是没问题的」）。
     *
     * ## 卡在哪里（反编译 `SoScUtilsImpl` 实证）
     * `SoScStageCoordinator#switchSplitDirection(String)` 的**第一行**就是门：
     * ```java
     * public void switchSplitDirection(String str) {
     *     if (SoScUtils.getInstance().supportTopBottomSplitFeature()) {
     *         ... 整个切换方向的动作全在这个 if 里面 ...
     *     }
     * }   // 不满足就直接什么都不做 —— 界面毫无反应
     * ```
     * 而它的实现在 `SoScUtilsImpl`：
     * ```java
     * public boolean supportTopBottomSplitFeature() {
     *     if (mReady && supportTopBottomSplitDevice()) {
     *         if (supportedRatioCustomization()) {
     *             return (mStageCoordinator.isFoldedState() || isLandscape()) ? false : true;
     *         }                                                   // ↑ 外屏折叠态 → false
     *         if (isPad()) return !isLandscape();
     *     }
     *     return false;
     * }
     * ```
     * `supportedRatioCustomization()` 在本机就是 `IS_Q18_CUSTOMIZE = true`，
     * 于是**外屏一折叠就恒为 false** —— 系统有意不让外屏用「上下分屏」，
     * 顺手把「丢给它的所有方向切换」也一起关了。
     *
     * ## 做法
     * 只在**用户刚点了方向按钮**的切换期（`expectingInThisThread()`）放行返回 true，
     * 让 `switchSplitDirection` 能进得去；其余时刻（例如系统自己判断要不要显示
     * 上下分屏入口）**完全保持系统原值**，不改变任何默认行为。
     */
    private fun hookTopBottomFeature(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.SOSC_UTILS_IMPL) ?: return 0
        val method = SoScSupport.methodOf(cls, "supportTopBottomSplitFeature", 0) ?: run {
            Logx.e("$TAG 找不到 supportTopBottomSplitFeature()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                if (r == true || !Cfg.outerSplit) return@Hooker r
                val self = chain.thisObject ?: return@Hooker r
                if (!isOuterScreen(self)) return@Hooker r
                // 🔴 外屏**始终**放行，不再只在「切换期」放行（2026-11-22 修正）。
                //   原因：方向按钮的真正调用点是
                //   ```java
                //   SoScStageCoordinator#startTasks(...):
                //     int i5 = bundle.getInt("splitscreen_direction", -1);
                //     if (supportTopBottomSplitFeature() && i5 == 1 && isLeftRightSplit() && ...)
                //         mSplitLayout.updateDynamicLeftRightSplit();
                //   ```
                //   以及 `switchSplitDirection()` 的**第一行**闸门。这两处都在
                //   `expectingInThisThread()` 为 false 的调用栈上（跨线程 / 跨消息），
                //   所以「切换期放行」实际从未命中 —— 按钮点了毫无反应。
                //   外屏本来就需要这套方向能力（用户要求「外屏与内屏同样逻辑」），
                //   放行它不会带来副作用：它只控制「能否切换方向」，不改变默认方向。
                Logx.once("outer-tb-feature", "$TAG 外屏放行 supportTopBottomSplitFeature（否则方向调整整段被跳过）")
                true
            })) 1 else 0
    }
}
