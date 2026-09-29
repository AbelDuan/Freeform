package com.abel.os4freeformx

/** 小白条绘制透明度动画，独立于可见性策略与它的超时计时。源自 HyperModifier GestureHandleFade。 */
class GestureHandleFade(hidden: Boolean) {
    companion object {
        const val DURATION_MS = 220L
        const val QUICK_REVEAL_DURATION_MS = 90L
    }

    private var from: Float = if (hidden) 0f else 1f
    private var target: Float = from
    private var startedAt: Long = 0L
    private var duration: Long = DURATION_MS

    fun alpha(hidden: Boolean, now: Long): Float = alpha(hidden, now, false)

    fun alpha(hidden: Boolean, now: Long, quickReveal: Boolean): Float {
        val next = if (hidden) 0f else 1f
        val current = value(now)
        if (next != target) {
            // 从当前帧反向插值，而不是跳到任一端点。
            from = current
            target = next
            startedAt = now
            duration = if (!hidden && quickReveal) QUICK_REVEAL_DURATION_MS else DURATION_MS
        }
        return current
    }

    fun running(now: Long): Boolean = from != target && now - startedAt < duration

    private fun value(now: Long): Float {
        val progress = maxOf(0f, minOf(1f, (now - startedAt) / duration.toFloat()))
        val eased = progress * progress * (3f - 2f * progress)
        return from + (target - from) * eased
    }
}
