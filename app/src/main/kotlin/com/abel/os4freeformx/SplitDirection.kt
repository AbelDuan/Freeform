package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * 功能 1 / 4：**解除分屏方向限制**（附方向判定兼容层）。
 *
 * ## 来源与合规
 * 移植自开源项目 **HyperRefine**（AGPL-3.0）的 `SplitDirectionUnlockHook` +
 * `SplitDirectionCompatHook`，按本模块 hook 风格独立重写（LibXposed API 102 的
 * `Chain` 与老 XposedBridge 不同：没有 `setResultValue`，改结果要直接返回、
 * 改参数要 `proceed(新数组)`，所以这里全部重写而不是照抄）。
 *
 * ## 官方限制在哪（本机 `_recon/out_jadx` 取证）
 * - **竖屏不许左右**：`SplitUtilsImpl#allowLeftRightSplitInPortrait(Context)`
 *   返回 `mAllowLRSplitInPortrait && DEBUG_MIUI_SPLIT_ENABLE`，前者来自资源
 *   `config_leftRightSplitInPortrait_miui`。恒返回 true 即可放行。
 *   静态版 `SplitScreenUtils#allowLeftRightSplitInPortrait(Resources)` 一并放行。
 * - **横屏不许上下**：`SoScUtilsImpl#supportTopBottomSplitFeature()`。本机实现有两个分支：
 *   `supportedRatioCustomization()` 时 `(isFoldedState() || isLandscape()) ? false : true`，
 *   否则 `isPad() ? !isLandscape() : …` —— 折叠屏横屏直接被否掉。
 *   `supportTopBottomSplitDevice()` 是它的前提（`IS_TOPBOTTOM_SPLIT_DEVICE || supportedRatioCustomization()`），
 *   两个一起放行才彻底。
 *
 * ## 方向判定兼容层（无独立开关，依附「解除限制」或「把手菜单双向分屏」）
 * `SplitUtilsImpl#isLeftRightSplit()` 在平板横屏时无视「动态方向标志」，折叠屏横屏走
 * `supportedRatioCustomization() && isLandscape() → true`，同样绕过了标志 ⇒
 * **横屏切成上下不生效**。`MulWinSwitchSplitUtils#isLeftRightSplit()` 未进分屏时恒返回「上下」，
 * 于是把手菜单的分屏图标与实际方向不符。
 *
 * 这里把两处统一为 [SoScSupport.effectiveLeftRightSplit]（`左右 = 横屏 == 动态方向标志`）。
 * 与参考实现不同的是：**不再限定 `isPad()`** —— 本机是折叠屏（`isPad()` 为 false），
 * 限定平板等于没修；改为只要 `mReady` 就接管，并按开关门禁。
 */
object SplitDirection {

    fun install(m: MainHook, cl: ClassLoader) {
        runCatching { installUnlock(m, cl) }.onFailure { Logx.e("解除分屏方向限制: 安装失败", it) }
        runCatching { installCompat(m, cl) }.onFailure { Logx.e("分屏方向兼容层: 安装失败", it) }
    }

    // ---------------- 解除限制 ----------------

    /**
     * 四个闸门恒返回 true。
     *
     * 都是「能力询问」型方法：返回 true 只是告诉系统「这个方向允许」，
     * 不会自己触发任何分屏动作，关掉开关立刻恢复原判定。
     */
    private fun installUnlock(m: MainHook, cl: ClassLoader) {
        var ok = 0
        ok += hookTrue(m, cl, SoScSupport.SPLIT_UTILS_IMPL, "allowLeftRightSplitInPortrait", 1)
        ok += hookTrue(m, cl, SoScSupport.SPLIT_SCREEN_UTILS, "allowLeftRightSplitInPortrait", 1)
        ok += hookTrue(m, cl, SoScSupport.SOSC_UTILS_IMPL, "supportTopBottomSplitFeature", 0)
        ok += hookTrue(m, cl, SoScSupport.SOSC_UTILS_IMPL, "supportTopBottomSplitDevice", 0)
        if (ok == 0) {
            Logx.e("解除分屏方向限制: 一个都没挂上（本机 WMShell 类名可能变了，先别开这个开关）")
            return
        }
        Logx.always("解除分屏方向限制: 已挂 $ok/4（开关=${Cfg.splitUnlockDir}）")
    }

    private fun hookTrue(
        m: MainHook,
        cl: ClassLoader,
        className: String,
        name: String,
        params: Int
    ): Int {
        val cls = SoScSupport.findClass(cl, className) ?: run {
            Logx.e("解除分屏方向限制: 找不到 $className")
            return 0
        }
        val method = SoScSupport.methodOf(cls, name, params) ?: run {
            Logx.e("解除分屏方向限制: 找不到 $className#$name/$params")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                // 这些是「能力询问」方法，不在拖动热路径上：顺带节流刷新一次配置，
                // 让设置页改完开关后不用重启 SystemUI 就能生效（1~2 秒）。
                Cfg.reloadThrottled()
                if (Cfg.splitUnlockDir) true else chain.proceed()
            })) 1 else 0
    }

    // ---------------- 方向判定兼容层 ----------------

    private fun installCompat(m: MainHook, cl: ClassLoader) {
        var ok = 0
        ok += hookCompat(m, cl, SoScSupport.SPLIT_UTILS_IMPL)
        ok += hookCompat(m, cl, SoScSupport.MULWIN_SPLIT_UTILS)
        Logx.always("分屏方向判定兼容层: 已挂 $ok/2（依附 解除限制=${Cfg.splitUnlockDir} 双向分屏=${Cfg.captionSplitBoth}）")
    }

    private fun hookCompat(m: MainHook, cl: ClassLoader, className: String): Int {
        val cls = SoScSupport.findClass(cl, className) ?: run {
            Logx.e("分屏方向兼容层: 找不到 $className")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "isLeftRightSplit", 0) ?: run {
            Logx.e("分屏方向兼容层: 找不到 $className#isLeftRightSplit()")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                // 热路径：两个 volatile 开关读一次即可；都关着就完全不干预
                val want = if (Cfg.splitUnlockDir || Cfg.captionSplitBoth) {
                    runCatching { SoScSupport.effectiveLeftRightSplit(cl) }.getOrNull()
                } else {
                    null
                }
                if (want != null) want else chain.proceed()
            })) 1 else 0
    }
}
