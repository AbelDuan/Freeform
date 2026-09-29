package com.abel.os4freeformx

import kotlin.math.tanh

/**
 * 阻尼弹簧：小白条跟随手指。只改绘制坐标，不动原生导航输入 / 快速切换动画 / 触摸处理 / insets。
 * 源自 HyperModifier GestureHandleMotion。
 */
class GestureHandleMotion {
    companion object {
        const val RELEASE_HOLD_MS = 260L
        private const val HOST_HOLD_MS = 200L
        private const val FOLLOW_FACTOR = 0.5f
        private const val DRAG_STIFFNESS = 150f
        private const val DRAG_DAMPING = 25f
        private const val RETURN_STIFFNESS = 105f
        private const val RETURN_DAMPING = 16f
        private const val SETTLED_DISTANCE = 0.15f
        private const val SETTLED_SPEED = 2f
    }

    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var left = 0f
    private var right = 0f
    private var up = 0f
    private var down = 0f
    private var lastFrameAt = 0L
    private var holdUntil = 0L
    private var releasedAt = 0L
    private var dragging = false

    fun start(rawX: Float, rawY: Float, now: Long) {
        advance(now)
        startX = rawX
        startY = rawY
        targetX = 0f
        targetY = 0f
        holdUntil = 0L
        releasedAt = 0L
        dragging = true
        lastFrameAt = now
    }

    fun move(rawX: Float, rawY: Float, maxLeft: Float, maxRight: Float,
             maxUp: Float, maxDown: Float, now: Long) {
        if (!dragging) return
        advance(now)
        left = maxLeft
        right = maxRight
        up = maxUp
        down = maxDown
        targetX = resisted((rawX - startX) * FOLLOW_FACTOR, maxLeft, maxRight)
        targetY = resisted((rawY - startY) * FOLLOW_FACTOR, maxUp, maxDown)
    }

    fun movedBeyond(rawX: Float, rawY: Float, slop: Float): Boolean {
        if (!dragging) return false
        val dx = rawX - startX
        val dy = rawY - startY
        return dx * dx + dy * dy >= slop * slop
    }

    fun release(now: Long) {
        if (!dragging) return
        advance(now)
        dragging = false
        releasedAt = now
        holdUntil = now + RELEASE_HOLD_MS
    }

    fun rebaseForNewHost(now: Long) {
        if (dragging) {
            lastFrameAt = now
        } else if (releasedAt != 0L && now - releasedAt < 1000L
                && (targetX != 0f || targetY != 0f)) {
            // 旧导航视图消失期间，不要把整个回弹动画耗完。
            lastFrameAt = now
            holdUntil = maxOf(holdUntil, now + HOST_HOLD_MS)
        }
    }

    fun reset() {
        dragging = false
        x = 0f; y = 0f; velocityX = 0f; velocityY = 0f
        targetX = 0f; targetY = 0f
        lastFrameAt = 0L; holdUntil = 0L; releasedAt = 0L
    }

    fun x(now: Long): Float { advance(now); return x }
    fun y(now: Long): Float { advance(now); return y }

    fun running(now: Long): Boolean {
        advance(now)
        val goalX = if (dragging || now < holdUntil) targetX else 0f
        val goalY = if (dragging || now < holdUntil) targetY else 0f
        return kotlin.math.abs(x - goalX) > SETTLED_DISTANCE
                || kotlin.math.abs(y - goalY) > SETTLED_DISTANCE
                || kotlin.math.abs(velocityX) > SETTLED_SPEED
                || kotlin.math.abs(velocityY) > SETTLED_SPEED
                || (!dragging && now < holdUntil && (targetX != 0f || targetY != 0f))
    }

    private fun advance(now: Long) {
        if (lastFrameAt == 0L || now <= lastFrameAt) {
            lastFrameAt = now
            return
        }
        // 限制被挂起的一帧长度；一秒足够弹簧收敛。
        var cursor = maxOf(lastFrameAt, now - 1000L)
        while (cursor < now) {
            val stepMs = minOf(16L, now - cursor)
            cursor += stepMs
            val holding = dragging || cursor < holdUntil
            val stiffness = if (dragging) DRAG_STIFFNESS else RETURN_STIFFNESS
            val damping = if (dragging) DRAG_DAMPING else RETURN_DAMPING
            val dt = stepMs / 1000f
            val goalX = if (holding) targetX else 0f
            val goalY = if (holding) targetY else 0f
            velocityX += ((goalX - x) * stiffness - velocityX * damping) * dt
            velocityY += ((goalY - y) * stiffness - velocityY * damping) * dt
            x = clamp(x + velocityX * dt, -left, right)
            y = clamp(y + velocityY * dt, -up, down)
        }
        lastFrameAt = now
        if (!dragging && now >= holdUntil && kotlin.math.abs(x) < SETTLED_DISTANCE
                && kotlin.math.abs(y) < SETTLED_DISTANCE
                && kotlin.math.abs(velocityX) < SETTLED_SPEED
                && kotlin.math.abs(velocityY) < SETTLED_SPEED) {
            x = 0f; y = 0f; velocityX = 0f; velocityY = 0f; targetX = 0f; targetY = 0f
        }
    }

    private fun resisted(displacement: Float, negativeLimit: Float, positiveLimit: Float): Float {
        val limit = if (displacement < 0f) negativeLimit else positiveLimit
        if (limit <= 0f) return 0f
        val amount = limit * tanh(kotlin.math.abs(displacement) / limit)
        return if (displacement < 0f) -amount else amount
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(max, value))
}
