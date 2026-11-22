package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * 分屏吸附守卫：拦住 `SplitLayout#snapToTarget` 收到 **null 吸附目标** 的情况。
 *
 * ## 现场（真机取证）
 * 竖屏、只把**一个应用放进单侧**（另一侧是桌面）时，拖动分隔条松手 100% 崩 SystemUI：
 * ```
 * java.lang.NullPointerException: Attempt to read from field
 *   'int com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm$SnapTarget.snapPosition'
 *   on a null object reference
 *   in method 'void SplitLayout.snapToTarget(int, SnapTarget, int, Interpolator)'
 *     at com.android.wm.shell.sosc.common.split.SplitLayout.snapToTarget
 *     at com.android.wm.shell.sosc.common.split.DividerView.onTouch(:592)
 * ```
 * 即 `DividerView` 松手时算出的吸附目标是 null，`snapToTarget` 内部直接读
 * `target.snapPosition` 就炸。横屏不崩 —— 横屏走的是两应用分屏，目标列表完整。
 *
 * ## 为什么是我们这边兜
 * 本模块的「分屏无极调节」会把任意位置包成自定义目标（`snapPosition=103`），
 * 单侧分屏时这套目标并不在官方状态机的白名单里，松手求目标就可能拿到 null。
 * 彻底改掉无极调节代价太大（用户明确要这个功能），所以在这里做**最后一道防线**：
 * 目标为 null 时不进官方方法，改成按松手位置就地吸附；实在拿不到就跳过本次吸附
 * （分隔条停在手松开的位置，不会再「弹回去」）。
 *
 * ## 安全性
 * - 只在 `target == null` 时介入 —— 正常情况下**零行为改变**；
 * - 兜底调用的目标来自官方 `calculateNonDismissingSnapTarget` / `mFixedSnapTarget`，
 *   不自己造 id，状态机认得出；
 * - 反射重入用 [inFallback] 挡住，避免自己 hook 自己形成递归。
 */
object SplitSnapGuard {

    /** 正在执行兜底调用（防重入：反射调用会再次命中本 hook）。 */
    @Volatile
    private var inFallback = false

    fun install(m: MainHook, cl: ClassLoader) {
        val cls = SoScSupport.findClass(cl, SoScSupport.SPLIT_LAYOUT) ?: run {
            Logx.e("分屏吸附守卫: 找不到 ${SoScSupport.SPLIT_LAYOUT}")
            return
        }
        val method = SoScSupport.methodOf(cls, "snapToTarget", 4) ?: run {
            Logx.e("分屏吸附守卫: 找不到 SplitLayout#snapToTarget/4")
            return
        }
        val ok = m.hookExecutable(method, XposedInterface.Hooker { chain ->
            val target = chain.getArg(1)
            if (target != null || inFallback) {
                chain.proceed()
            } else {
                val layout = chain.thisObject
                val x = chain.getArg(0) as? Int
                val fallback = resolveFallback(layout, x)
                if (fallback == null) {
                    Logx.always("分屏吸附守卫: snapToTarget 目标=null（x=$x）且取不到兜底目标，跳过本次吸附")
                    null
                } else {
                    Logx.always(
                        "分屏吸附守卫: snapToTarget 目标=null（x=$x）→ 改用就位吸附 " +
                            "position=${SoScSupport.intField(fallback, "position")}" +
                            "/snapPosition=${SoScSupport.intField(fallback, "snapPosition")}"
                    )
                    inFallback = true
                    try {
                        SoScSupport.call(
                            layout, "snapToTarget", x, fallback,
                            chain.getArg(2), chain.getArg(3)
                        )
                    } finally {
                        inFallback = false
                    }
                    null
                }
            }
        })
        if (ok) Logx.always("分屏吸附守卫: 已挂 1/1（防 snapToTarget 空目标崩 SystemUI）")
        else Logx.e("分屏吸附守卫: hookExecutable 失败")
    }

    /**
     * 取一个官方认可的目标：
     * 1. `mFixedSnapTarget`（单侧分屏时官方自己维护的固定目标，最贴合当前布局）；
     * 2. 按松手位置 `calculateNonDismissingSnapTarget(x)`；
     * 3. 中线目标（至少保证方法内读到的字段非空）。
     */
    private fun resolveFallback(layout: Any?, x: Int?): Any? {
        SoScSupport.field(layout, "mFixedSnapTarget")?.let { return it }
        val algorithm = SoScSupport.call(layout, "getDividerSnapAlgorithm") ?: return null
        if (x != null) {
            SoScSupport.call(algorithm, "calculateNonDismissingSnapTarget", x)?.let { return it }
        }
        return SoScSupport.field(algorithm, "mMiddleTarget")
            ?: SoScSupport.field(algorithm, "mFirstSplitTarget")
    }
}
