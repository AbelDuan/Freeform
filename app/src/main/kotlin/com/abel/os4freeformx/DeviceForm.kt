package com.abel.os4freeformx

import android.content.res.Resources

/**
 * 设备形态判定 —— 决定「小窗无极调节」走哪套链路。
 *
 * ## 为什么必须分两套
 * HyperOS 对小窗有两套完全不同的实现，且**按设备形态分流**：
 *
 * | 形态 | 小窗体系 | 包 | 缩放落点 |
 * |---|---|---|---|
 * | 平板 | 无极小窗（infinity mode） | `multitasking.miuiinfinitymode` | `MiuiInfinityModeRatioPositioner` / `MiuiInfinityModeTaskWrapper#updateDestinationAttributes` |
 * | 折叠屏 / 手机 | 经典小窗（freeform） | `multitasking.miuifreeform` | `MiuiFreeformModeResizeHandler#calBoundsAfterMoving` + `MultiTaskingAnimTarget#setAnimParam` |
 *
 * 参考实现 HyperRefine 只写了前者（`FreeformFreeRatioHook`，`deviceScope = {PAD, FOLD}`），
 * 但它的类在折叠屏上根本不存在 —— 真机取证：本机 `Miui-WindowManager-Shell.jar` 的 dex 里
 * `miuiinfinitymode` 包 **0 命中**，所以那套在折叠屏上等于空转。本模块两套都实现，按形态选。
 *
 * ## 判定依据（与 HyperRefine `DeviceContext` 同思路，独立实现）
 * 1. `miui.os.Build.IS_TABLET` —— MIUI 平板硬件标记，最权威；
 * 2. `persist.sys.multi_display_type` 低 4 位：2 后屏 / **3 内折** / 4 翻盖 / 5 外折；
 *    回退旧键 `persist.sys.muiltdisplay_type`：1 后屏 / 2 内折；
 * 3. 最后才看 `smallestScreenWidthDp >= 600`。
 *
 * ⚠ 顺序不能反：折叠屏展开态宽度常常 ≥ 600dp，若先看宽度会把它误判成平板，
 *   进而去挂一套本机不存在的 infinity 链路。
 *
 * 实测本机（小米 18 Fold / lhasa）：`multi_display_type=3`、`muiltdisplay_type=2` ⇒ **FOLD**。
 */
object DeviceForm {

    enum class Form { PAD, FOLD, PHONE }

    private const val KEY_MULTI_DISPLAY = "persist.sys.multi_display_type"
    private const val KEY_MULTI_DISPLAY_LEGACY = "persist.sys.muiltdisplay_type"
    private const val TABLET_MIN_WIDTH_DP = 600

    @Volatile private var cached: Form? = null

    /** 进程内缓存（形态在运行期不变）。 */
    val current: Form
        get() = cached ?: detect().also { cached = it }

    private fun detect(): Form {
        // 1) MIUI 平板标记优先：这样展开态折叠屏（sw 可能 ≥600dp）不会被误判
        val miuiTablet = runCatching {
            Class.forName("miui.os.Build").getField("IS_TABLET").getBoolean(null)
        }.getOrDefault(false)
        if (miuiTablet) return Form.PAD

        // 2) 折叠屏：多屏形态属性
        val multi = propInt(KEY_MULTI_DISPLAY, 1)
        if (multi > 1) {
            when (multi and 0x0F) {
                3, 4, 5 -> return Form.FOLD     // 内折 / 翻盖 / 外折
            }
        } else {
            when (propInt(KEY_MULTI_DISPLAY_LEGACY, 0)) {
                1, 2 -> return Form.FOLD        // 后屏 / 内折
            }
        }

        // 3) 兜底：宽度
        val sw = runCatching {
            Resources.getSystem().configuration.smallestScreenWidthDp
        }.getOrDefault(0)
        return if (sw >= TABLET_MIN_WIDTH_DP) Form.PAD else Form.PHONE
    }

    /** 日志用：把判定输入一起打出来，误判时一眼能看出是哪一项导致的。 */
    fun describe(): String = runCatching {
        val miuiTablet = runCatching {
            Class.forName("miui.os.Build").getField("IS_TABLET").getBoolean(null)
        }.getOrDefault(false)
        val sw = runCatching {
            Resources.getSystem().configuration.smallestScreenWidthDp
        }.getOrDefault(0)
        "miuiTablet=$miuiTablet multi_display_type=${propInt(KEY_MULTI_DISPLAY, -1)}" +
                "(legacy=${propInt(KEY_MULTI_DISPLAY_LEGACY, -1)}) sw=${sw}dp"
    }.getOrElse { "判定异常: $it" }

    /** 折叠屏 / 手机走经典小窗；平板走无极小窗。 */
    val useInfinity: Boolean get() = current == Form.PAD

    private fun propInt(key: String, def: Int): Int = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getDeclaredMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.trim()?.toIntOrNull() ?: def
    }.getOrDefault(def)
}
