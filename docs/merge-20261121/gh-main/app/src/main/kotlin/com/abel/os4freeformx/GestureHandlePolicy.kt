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

    /**
     * ★★★ v0.4.46 用户反馈③：「酷安的导航栏小白条，我没操作、或在操作别的地方时，
     *   它都不会自动隐藏」。
     *
     *   根因：`hidden()` 判据是 `now - shownAt >= IMMERSIVE_DURATION_MS`，
     *   而 `shownAt` 只在 `reveal()` 里更新、**初值为 0**。
     *   ⇒ 尚未 reveal 过时 `now - 0` 是天文数字，`remaining()` 算出 **0**，
     *     `scheduleHide()`（`if (remaining > 0L)`）于是**不排任何定时器**；
     *     而 `draw()` 只在淡入淡出动画进行中才 `postInvalidateOnAnimation()`。
     *   ⇒ 画面一旦静止（酷安正是长停留场景），**再没有任何东西触发重绘**，
     *     最后一帧（可见）就永久留在屏幕上 → 永不隐藏。
     *
     *   修法：用 `noteSeen()` 在首次参与判定时把 `shownAt` 补上，
     *   使 `remaining()` 一定能给出正数倒计时，从而 `scheduleHide()` 必然排上定时器。
     */
    fun noteSeen(now: Long) {
        if (shownAt == 0L) shownAt = now
    }

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

    /** 当前是否应隐藏小白条。 */
    fun hidden(now: Long, @Suppress("UNUSED_PARAMETER") systemHidden: Boolean): Boolean {
        if (touchRevealActive(now) || swipeRevealActive(now)) return false
        if (!Cfg.gestureHandleIdle) return false          // 空闲不自动隐藏 → 始终可见
        return now - shownAt >= IMMERSIVE_DURATION_MS     // 沉浸：展示超过阈值后淡出
    }

    /** 距下次隐藏还剩多少毫秒（用于排程隐藏）。 */
    fun remaining(now: Long): Long {
        if (!Cfg.gestureHandleIdle) return 0L
        // ★ v0.4.46：shownAt 未初始化时先补上，否则 `now - 0` 会算出 0 毫秒 ⇒ 永不排程隐藏。
        noteSeen(now)
        val immersive = maxOf(0L, IMMERSIVE_DURATION_MS - (now - shownAt))
        val touch = if (touchHeld) 0L else maxOf(0L, touchRevealUntil - now)
        val swipe = if (swipeHeld) 0L else maxOf(0L, swipeRevealUntil - now)
        return maxOf(immersive, maxOf(touch, swipe))
    }

    private fun revealOnTouch(now: Long) { touchRevealUntil = now + IMMERSIVE_DURATION_MS }
    private fun revealOnSwipe(now: Long) { swipeRevealUntil = now + IMMERSIVE_DURATION_MS }
}
