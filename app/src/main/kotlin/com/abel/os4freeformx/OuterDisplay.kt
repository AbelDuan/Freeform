package com.abel.os4freeformx

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * 折叠屏「外屏（副屏）」识别与几何。
 *
 * ## 为什么要单独一个文件
 * 反编译 `Miui-WindowManager-Shell.jar` 后发现两处**屏幕维度**的坑（详见
 * `docs/merge-20261121/RECON-findings.md`）：
 *
 * 1. `MultiTaskingDisplayInfo` 的 `mDisplayLayout` 是 **static**，且只在
 *    `onDisplayChanging(displayId == 0, …)` 里更新 ⇒ 外屏上 `getDisplayWidth()` /
 *    `getDisplayHeight()` / `isLandscape()` 返回的**全是内屏的值**。
 *    而 `MiuiDecorationDot#calculateFreeformHandleMenuPos` 又硬编码
 *    `mDisplayController.getDisplayLayout(0)` ⇒ 外屏三点菜单的位置与越界判定混用两套基准。
 * 2. `SoScStageCoordinator#isLandscape()` 读的是 `mSplitLayout.getRootBounds()` ——
 *    这个**是当前屏的真实边界**，所以分屏方向判定本身会随屏变化，不用改。
 *
 * 所以这里只做一件事：**给出「这块屏真实的尺寸与方向」**，供菜单定位与方向判定使用，
 * 不去动系统那两个静态字段（改静态字段会影响内屏，得不偿失）。
 *
 * ## 本机实测拓扑
 * | DisplayId | 分辨率（竖屏） | 挖孔 `getBoundingRectTop()` |
 * |---|---|---|
 * | 0 内屏 | 1672×2364 | 左侧 `(0,0)-(164,140)` |
 * | 1 外屏 | 1168×1712 | **顶部居中 `(557,0)-(611,126)`** |
 *
 * ⚠️ DisplayId 是动态分配的，**不写死 0/1**：按 `DisplayManager` 里唯一那块
 * 「非默认、`FLAG_PRESENTATION` 或 `canHostTasks=false` 的内置屏」来认外屏。
 */
object OuterDisplay {

    /** 一块屏的实时几何。 */
    data class Geometry(
        val displayId: Int,
        val width: Int,
        val height: Int,
        /** 竖屏分辨率（未旋转），用于消除方向歧义。 */
        val naturalWidth: Int,
        val naturalHeight: Int,
        /** 顶部居中的挖孔矩形（无则 null）。 */
        val cutoutTop: android.graphics.Rect?,
    ) {
        val landscape: Boolean get() = width > height
    }

    /** 外屏的 displayId；设备上没有副屏时返回 -1（每次现查，不缓存）。 */
    fun outerDisplayId(ctx: Context?): Int {
        val dm = displayManager(ctx) ?: return -1
        return runCatching {
            dm.displays.firstOrNull { isOuter(it) }?.displayId ?: -1
        }.getOrDefault(-1)
    }

    /**
     * 判定一块屏是不是「外屏」。
     *
     * 折叠屏的外屏特征（本机 XR 折叠屏实测）：内置屏、非默认屏、`FLAG_PRESENTATION`、
     * 且 `canHostTasks` 为 false。不按 displayId 数值判断（会随启动顺序漂移）。
     */
    private fun isOuter(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY) return false
        val flags = d.flags
        val presentation = (flags and Display.FLAG_PRESENTATION) != 0
        return presentation
    }

    /** 当前「是否在外屏上」（以默认屏的 displayId 为准：默认屏变了就说明翻到了外屏）。 */
    fun isOnOuter(ctx: Context?): Boolean {
        val dm = displayManager(ctx) ?: return false
        return runCatching {
            val def = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
            isOuter(def)
        }.getOrDefault(false)
    }

    /** 取某块屏的实时几何；拿不到返回 null。 */
    fun geometry(ctx: Context?, displayId: Int): Geometry? {
        if (displayId < 0) return null
        val dm = displayManager(ctx) ?: return null
        return runCatching {
            val d = dm.getDisplay(displayId) ?: return@runCatching null
            val m = Display.Mode::class.java
            val real = runCatching {
                // API 17+: getRealSize / getRealMetrics 拿真实像素
                val p = android.graphics.Point()
                @Suppress("DEPRECATION")
                d.getRealSize(p)
                p
            }.getOrNull()
            val size = real ?: android.graphics.Point(
                runCatching { (d.javaClass.getMethod("getWidth").invoke(d) as Int) }.getOrDefault(0),
                runCatching { (d.javaClass.getMethod("getHeight").invoke(d) as Int) }.getOrDefault(0),
            )
            val mode = runCatching { d.mode }.getOrNull()
            val natW = if (mode != null && mode.physicalWidth > 0) mode.physicalWidth else size.x
            val natH = if (mode != null && mode.physicalHeight > 0) mode.physicalHeight else size.y
            val topCut = runCatching {
                val c = d.cutout
                c?.getBoundingRectTop()?.takeIf { !it.isEmpty }
            }.getOrNull()
            Geometry(
                displayId = displayId,
                width = size.x,
                height = size.y,
                naturalWidth = natW,
                naturalHeight = natH,
                cutoutTop = topCut,
            )
        }.getOrNull()
    }

    /**
     * 顶部居中挖孔（摄像头）的屏幕坐标矩形；没有则 null。
     *
     * 系统 `DisplayCutout#getBoundingRectTop()` 给的是**未旋转**的上边挖孔，
     * 旋转后要按方向换算，否则横屏时会指到错误的位置。
     */
    fun cameraRectOnScreen(ctx: Context?, displayId: Int): android.graphics.Rect? {
        val g = geometry(ctx, displayId) ?: return null
        return cameraRect(g, displayId, ctx)
    }

    private fun cameraRect(g: Geometry, displayId: Int, ctx: Context?): android.graphics.Rect? {
        val cut = g.cutoutTop ?: return null
        val dm = displayManager(ctx) ?: return null
        val rot = runCatching { dm.getDisplay(displayId)?.rotation }.getOrNull() ?: 0
        val w = g.width
        val h = g.height
        val nw = g.naturalWidth
        val nh = g.naturalHeight
        return when (rot) {
            android.view.Surface.ROTATION_0 -> cut
            android.view.Surface.ROTATION_180 -> android.graphics.Rect(w - cut.right, h - cut.bottom, w - cut.left, h - cut.top)
            // 90°：原「上边」转到了屏幕左边
            android.view.Surface.ROTATION_90 -> {
                val l = cut.left
                val r = cut.right
                android.graphics.Rect(l, nw - r.coerceAtMost(nw), r.coerceAtMost(nw), nw - l.coerceAtLeast(0))
            }
            // 270°：原「上边」转到了屏幕右边
            android.view.Surface.ROTATION_270 -> {
                val l = cut.left
                val r = cut.right
                android.graphics.Rect(nh - r.coerceAtMost(nh), l.coerceAtLeast(0), nh - l.coerceAtLeast(0), r.coerceAtMost(nh))
            }
            else -> cut
        }
    }

    private fun displayManager(ctx: Context?): DisplayManager? = runCatching {
        val c = ctx ?: return@runCatching null
        c.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }.getOrNull()
}
