package com.abel.os4freeformx

/**
 * 小白条可见性策略（精简核心，去掉逐应用 preset / rules / scope）。
 * 只负责：触摸显隐、滑动显隐、空闲自动隐藏（沉浸）的超时与判定。
 * 源自 HyperModifier GestureHandlePolicy。
 */
class GestureHandlePolicy {
    companion object {
        const val IMMERSIVE_DURATION_MS = 3000L
    }

    private var shownAt: Long = 0L
    private var touchRevealUntil: Long = 0L
    private var swipeRevealUntil: Long = 0L
    private var touchHeld = false
    private var swipeHeld = false

    fun reveal(now: Long) { shownAt = now }

    fun touchDown(now: Long) {
        touchHeld = true
        revealOnTouch(now)
    }

    fun touchEvent(now: Long) {
        if (touchHeld) revealOnTouch(now)
    }

    fun touchUp(now: Long) {
        if (!touchHeld) return
        touchHeld = false
        revealOnTouch(now)
    }

    fun swipeDown(now: Long) {
        swipeHeld = true
        revealOnSwipe(now)
    }

    fun swipeEvent(now: Long) {
        if (swipeHeld) revealOnSwipe(now)
    }

    fun swipeUp(now: Long) {
        if (!swipeHeld) return
        swipeHeld = false
        revealOnSwipe(now)
    }

    fun touchRevealActive(now: Long) = touchHeld || now < touchRevealUntil
    fun swipeRevealActive(now: Long) = swipeHeld || now < swipeRevealUntil

    fun clearTouchReveal() {
        touchHeld = false
        touchRevealUntil = 0L
    }

    fun clearSwipeReveal() {
        swipeHeld = false
        swipeRevealUntil = 0L
    }

    /** 前台应用变化：重置显示计时（重新展示片刻再进入沉浸）。 */
    fun foreground(now: Long) { shownAt = now }

    /** 当前是否应隐藏小白条。 */
    fun hidden(now: Long, @Suppress("UNUSED_PARAMETER") systemHidden: Boolean): Boolean {
        if (touchRevealActive(now) || swipeRevealActive(now)) return false
        if (!Cfg.gestureHandleIdle) return false          // 空闲不自动隐藏 → 始终可见
        return now - shownAt >= IMMERSIVE_DURATION_MS     // 沉浸：展示超过阈值后淡出
    }

    /** 距下次隐藏还剩多少毫秒（用于排程隐藏）。 */
    fun remaining(now: Long): Long {
        if (!Cfg.gestureHandleIdle) return 0L
        val immersive = maxOf(0L, IMMERSIVE_DURATION_MS - (now - shownAt))
        val touch = if (touchHeld) 0L else maxOf(0L, touchRevealUntil - now)
        val swipe = if (swipeHeld) 0L else maxOf(0L, swipeRevealUntil - now)
        return maxOf(immersive, maxOf(touch, swipe))
    }

    private fun revealOnTouch(now: Long) { touchRevealUntil = now + IMMERSIVE_DURATION_MS }
    private fun revealOnSwipe(now: Long) { swipeRevealUntil = now + IMMERSIVE_DURATION_MS }
}
