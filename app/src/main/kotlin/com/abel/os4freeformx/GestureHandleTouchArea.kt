package com.abel.os4freeformx

/** 屏幕底部触摸带：小白条被动输入监听的命中区域。源自 HyperModifier（精简，去掉可调 DP 设置）。 */
object GestureHandleTouchArea {
    const val DEFAULT_DP = 16f
    const val MAX_DP = 32f

    fun normalize(distanceDp: Float): Float {
        if (distanceDp.isNaN() || distanceDp.isInfinite()) return DEFAULT_DP
        return maxOf(0f, minOf(MAX_DP, distanceDp))
    }

    fun contains(rawX: Float, rawY: Float, displayWidth: Int, displayHeight: Int,
                 handleBottomY: Float, density: Float, distanceDp: Float): Boolean {
        val distance = normalize(distanceDp)
        return distance > 0f && density > 0f && displayWidth > 0 && displayHeight > 0
                && handleBottomY >= displayHeight - 32f * density
                && rawX >= 0f && rawX <= displayWidth
                && rawY >= displayHeight - distance * density && rawY <= displayHeight
    }
}
