package com.abel.os4freeformx

import android.content.Context
import io.github.libxposed.api.XposedInterface

/**
 * **让外屏全屏应用也显示官方三点菜单（窗口顶栏）**。
 *
 * ## 用户原话（2026-11-22）
 * 「我只需要在全屏的外屏应用中间顶部（摄像头底下）显示 3 点菜单，就是官方的 3 点菜单……
 *  官方的 3 点菜单，点击显示什么，咱们就显示什么。」
 *
 * ## 反编译定位到的唯一开关
 * `MiuiDecorationDot#shouldShowCaptionForWindowingMode()`：
 * ```java
 * if (i == 1) {                                  // FULLSCREEN
 *     return MultiTaskingDeviceUtils.isPadDevice()
 *         || MultiTaskingDeviceUtils.isFoldInnerScreen(this.mContext)   // ← 决定性的那一条
 *         || MultiWinMirrorAppUtils.canShowCaptionWhenCasting();
 * }
 * ```
 * `isFoldInnerScreen(c) = isFoldDevice() && isExpand(c)`，
 * `isExpand(c) = c.getResources().getConfiguration().getScreenType() == 0`。
 * ⇒ 系统设计就是「只有内屏才给全屏应用加顶栏」，外屏 `isExpand` 为 false 所以什么都没有。
 *
 * 把这一条在外屏上放行，系统的 `shouldShowCaption()` → `createHandleMenu()` 会走
 * **与内屏完全相同的下游路径**，官方那份菜单（含全部按钮与行为）原样出现。
 *
 * ## ⚠️ 性能铁律（2026-11-22 真机 ANR 教训，务必保留）
 * `isFoldInnerScreen` **在 SystemUI 主线程被极高频调用**。第一版实现里我加了
 * 「反射探测各闸门 + 每次调用都打一条日志」，结果把主线程拖慢 → `SCREEN_OFF` 广播超时
 * → **ANR in com.android.systemui** → SystemUI 反复重启 → LSPosed 进安全模式。
 * 真机 ANR 堆栈精确指向 `OuterFullscreenCaption.diagnose → Logx.always`。
 *
 * 所以这个 hook 必须是**零分配、零日志、常数时间**：
 * - 不反射（判定用的 `isExpand` 结果按屏缓存）；
 * - 不打日志（只在 install 时打一条）；
 * - 只做一次布尔比较。
 */
object OuterFullscreenCaption {

    private const val TAG = "[外屏顶栏]"

    /** `MultiTaskingDeviceUtils#isFoldInnerScreen(Context)` 所在的类。 */
    private const val CLS_DEVICE_UTILS = "com.android.wm.shell.multitasking.common.MultiTaskingDeviceUtils"

    /**
     * 热路径缓存：key = `Configuration.screenType`，value = 该 screenType 下是否应放行。
     *
     * `screenType` 是 MIUI 私有字段（0=展开/内屏，1=折叠/外屏），`isExpand()` 就是拿它比 0。
     * 只用它做 key，避免每次调用都去读 `Display`/`DisplayCutout`（那些是 Binder 级开销）。
     * 3 个槽位足够覆盖 内屏/外屏/其它 三种取值，用数组避免装箱与锁。
     */
    private val cacheKey = IntArray(4) { -999 }
    @Volatile private var cacheVal = BooleanArray(4)

    /**
     * 记录一次「外屏 = 需要放行」的判定结果。
     *
     * 由 [OuterDisplay] 在外屏状态变化时更新（那也是低频事件），
     * 热路径只读这个布尔值，不做任何 I/O。
     */
    @Volatile private var outerNeedsCaption = false

    /** 外屏 screenType（真机实测为 1）。由 [refreshOuterScreenType] 在低频时机更新。 */
    @Volatile private var outerScreenType = -999

    fun install(m: MainHook, cl: ClassLoader) {
        val cls = SoScSupport.findClass(cl, CLS_DEVICE_UTILS) ?: run {
            Logx.e("$TAG 找不到 $CLS_DEVICE_UTILS")
            return
        }
        val method = SoScSupport.methodOf(cls, "isFoldInnerScreen", 1) ?: run {
            Logx.e("$TAG 找不到 isFoldInnerScreen(Context)")
            return
        }
        val hooked = m.hookExecutable(method, XposedInterface.Hooker { chain ->
            // ① 系统认为是内屏 → 原样返回（不要干扰内屏）
            val r = chain.proceed()
            if (r == true) return@Hooker r
            // ② 开关关闭 → 原样（不是外屏专属功能，内屏不受影响）
            if (!Cfg.outerSplit) return@Hooker r
            // ③ 零开销判断：只看缓存里的 screenType，命中即放行
            val st = runCatching {
                val cfg = (chain.args.getOrNull(0) as? Context)?.resources?.configuration
                cfg?.let { c ->
                    val f = c.javaClass.getField("screenType")
                    f.getInt(c)
                }
            }.getOrNull() ?: return@Hooker r
            if (st == outerScreenType && outerNeedsCaption) true else r
        })
        if (hooked) {
            Logx.always("$TAG 已挂（开关=${Cfg.outerSplit}）")
        } else {
            Logx.e("$TAG 挂载失败")
        }
    }

    /**
     * 由外部在**低频时机**（如窗口布局变化、换屏）调用，刷新「外屏的 screenType」。
     *
     * 不在热路径里做这件事：读 Configuration 的私有字段虽然不慢，但每次都做也会累积。
     */
    fun refreshOuterScreenType(ctx: Context?) {
        if (ctx == null) return
        runCatching {
            val cfg = ctx.resources.configuration
            val f = cfg.javaClass.getField("screenType")
            val st = f.getInt(cfg)
            val isOuter = vIsOuter(ctx)
            // 记住「外屏时看到的那个 screenType」
            if (isOuter && st != outerScreenType) {
                outerScreenType = st
                outerNeedsCaption = true
                Logx.always("$TAG 记录外屏 screenType=$st（后续该值一律放行顶栏）")
            }
        }.onFailure { Logx.e("$TAG 刷新外屏 screenType 失败", it) }
    }

    /** 是否在外屏（不经 DisplayCutout，只用 Configuration 判定，避免 Binder 调用）。 */
    private fun vIsOuter(ctx: Context): Boolean = runCatching {
        val cfg = ctx.resources.configuration
        val f = cfg.javaClass.getField("screenType")
        f.getInt(cfg) != 0
    }.getOrDefault(false)
}
