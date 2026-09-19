package com.abel.os4freeformx

/**
 * 小窗比例调整的算法（三点菜单那排比例按钮）。
 *
 * 纯算术、不碰 Android，所以能在主机侧跑一遍（`./check.sh`）。
 *
 * 角柄不再由模块接管：MIUI 原生的角柄缩放本来就是「锁当前比例改尺寸」，
 * 这里只负责把「换个比例」这件事算出来。
 */

/** 菜单里那排比例按钮：标签 → 比例数值（宽/高）。横竖屏按钮不放这里（它取当前比例的倒数）。 */
val RATIO_BUTTONS = listOf(
    "18:9" to 18f / 9f,
    "16:9" to 16f / 9f,
    "4:3" to 4f / 3f,
    "1:1" to 1f
)

/**
 * 目标比例（宽/高）。
 *
 * 菜单里「横竖屏」是一个**状态**（按钮上直接显示当前是横屏还是竖屏），比例按钮按这个状态取
 * 横屏形态（[landscape] = true，即 picked 本身）或竖屏形态（1/picked）。
 * 这样一次进菜单就能同时定好「比例 + 方向」。
 */
fun ratioFor(picked: Float, landscape: Boolean): Float =
    if (picked <= 0f) 1f else if (landscape) picked else 1f / picked

/** 当前窗口是不是横屏（宽 > 高）。 */
fun isLandscape(w: Int, h: Int): Boolean = w > h

/**
 * 按比例算新尺寸：**保持当前的长边**，另一条边按比例算出来；放不下就整体缩到能放进 [area]。
 *
 * 不保持宽度而保持长边：否则竖屏窗口连点几次比例会越调越窄（真机反馈「大小被限制了」）。
 *
 * @param currentW 当前可视宽
 * @param currentH 当前可视高
 * @return [宽, 高]
 */
fun fitRatio(currentW: Int, currentH: Int, ratio: Float, area: IntArray): IntArray {
    if (currentW <= 0 || currentH <= 0 || ratio <= 0f) return intArrayOf(1, 1)
    val longSide = maxOf(currentW, currentH)
    var w: Int
    var h: Int
    if (ratio >= 1f) {
        w = longSide
        h = (longSide / ratio).toInt()
    } else {
        h = longSide
        w = (longSide * ratio).toInt()
    }
    val maxW = area[2] - area[0]
    val maxH = area[3] - area[1]
    if (w > maxW) {
        w = maxW
        h = (w / ratio).toInt()
    }
    if (h > maxH) {
        h = maxH
        w = (h * ratio).toInt()
    }
    return intArrayOf(w.coerceAtLeast(1), h.coerceAtLeast(1))
}

/** 把 [w,h] 等比缩到能放进 maxW×maxH（用于「真实 bounds 不能超出屏幕」）。 */
fun fitInto(w: Int, h: Int, maxW: Int, maxH: Int): IntArray {
    if (w <= 0 || h <= 0 || maxW <= 0 || maxH <= 0) return intArrayOf(1, 1)
    if (w <= maxW && h <= maxH) return intArrayOf(w, h)
    val k = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
    return intArrayOf((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
}
