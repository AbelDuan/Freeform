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
 * 只在外屏（`isLargeScreen == false`）且确认是折叠屏时才接管 —— 内屏路径一行都不碰。
 */
object CoverSplit {

    private var loader: ClassLoader? = null

    @Volatile private var unhideLogged = false
    @Volatile private var dirLogged = false

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

        if (ok == 0) {
            Logx.e("外屏分屏: 一个都没挂上（本机 WMShell 类名可能变了）")
            return
        }
        Logx.always("外屏分屏: 已挂 $ok/3（开关=${Cfg.coverSplit}）")
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
}
