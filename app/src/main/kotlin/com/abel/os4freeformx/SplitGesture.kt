package com.abel.os4freeformx

import android.content.res.Resources
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs

/**
 * 「分屏 / 小窗」手势判定（基于 SystemUI 的被动输入监听）。
 *
 * 需求（用户 2026-11-02 确认）：
 *   从底部手势条快速上拖，落点偏**左上** → 分屏；偏**右上** → 小窗。
 *   系统原生要求拖到屏幕左上/右上 1/8 处才认，太远；本模块把它放宽到「上半屏 + 明显横向偏移」。
 *
 * 为什么能这么干：
 *   桌面（Flutter/Rust）算落点后通过 Binder 让 SystemUI 执行分屏，
 *   桌面无法注入，所以我们在 SystemUI 侧自己判、自己调
 *   `SoScSplitScreenController#enterSplitScreen(taskId, isSplit)`。
 *
 * 开关：`setprop debug.os4ffx.split_gesture 0` 可临时关闭（默认开）。
 */
object SplitGesture {
    private const val TAG = "分屏手势"
    private const val PROP = "debug.os4ffx.split_gesture"

    // —— 判定阈值（相对屏幕尺寸）——
    /** 起手必须在屏幕底部这一比例之内（手势条的点击区）。 */
    private const val START_BOTTOM = 0.90f
    /** 上滑距离至少这么多屏高（过滤普通点按）。 */
    private const val MIN_UP = 0.30f
    /** 落点必须在屏幕上半部这个比例以内。 */
    private const val END_TOP = 0.62f
    /** 横向偏移至少这么多屏宽（判断「往左」还是「往右」的意图）。 */
    private const val MIN_DX = 0.22f
    /** 两次触发之间的最小间隔，防止同一次手势重复触发。 */
    private const val COOLDOWN_MS = 2500L

    /** 屏幕尺寸缓存（竖屏下 w<h）。旋转时由 next 次手势的 ratio 判定兜住。 */
    @Volatile private var cachedW = 0f
    @Volatile private var cachedH = 0f

    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var lastFire = 0L

    /** 由 InputMonitor 的监听器逐条喂进来（不消费事件，纯旁观）。 */
    fun onTouch(e: MotionEvent) {
        if (!enabled()) return
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) {
            // 逐事件日志：一次滑动就有几十~上百条，只能走 v（受「记录详细日志」开关控制）。
            Logx.v("$TAG: 收到 action=${e.actionMasked} x=${e.rawX.toInt()} y=${e.rawY.toInt()}")
        }
        val (w, h) = screenSize()
        if (w <= 0f || h <= 0f) return

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX
                startY = e.rawY
                tracking = startY > h * START_BOTTOM
                if (tracking) runCatching { SplitTest.rememberFrontTask() }
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return
                tracking = false
                val dx = e.rawX - startX
                val up = startY - e.rawY            // 上滑为正
                val endY = e.rawY
                if (up <= 0f || startY <= 0f) return
                // 全部用「相对起手点」的比例判定，不依赖屏幕尺寸/朝向：
                //   upRatio  = 上滑距离 / 起手点 y
                //   endRatio = 落点 y / 起手点 y
                //   slope    = 横向偏移 / 上滑距离（判断够不够斜）
                val upRatio = up / startY
                val endRatio = endY / startY
                val slope = abs(dx) / up
                Logx.always(
                    "$TAG: UP 判定 dx=${dx.toInt()} up=${up.toInt()} " +
                        "upRatio=${"%.2f".format(upRatio)} endRatio=${"%.2f".format(endRatio)} " +
                        "slope=${"%.2f".format(slope)} " +
                        "阈值 upRatio>$MIN_UP endRatio<$END_TOP slope>$MIN_DX"
                )
                if (upRatio < MIN_UP) return                       // 没上滑够
                if (endRatio > END_TOP) return                     // 没到上半屏
                if (slope < MIN_DX) return                         // 没有明确的横向意图
                val now = SystemClock.uptimeMillis()
                if (now - lastFire < COOLDOWN_MS) {
                    Logx.always("$TAG: 冷却中（${now - lastFire}ms），跳过")
                    return
                }
                lastFire = now

                val left = dx < 0f
                Logx.always(
                    "$TAG: 触发${if (left) "分屏(左上)" else "小窗(右上)"} " +
                        "dx=${dx.toInt()} up=${up.toInt()} endY=${endY.toInt()}"
                )
                runCatching { SplitTest.fire(left) }.onFailure { Logx.e("$TAG: 触发失败", it) }
            }

            MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    @Volatile private var sysPropCls: Class<*>? = null
    @Volatile private var sysPropGet: java.lang.reflect.Method? = null

    private fun enabled(): Boolean {
        val m = sysPropGet ?: runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val g = c.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            sysPropCls = c
            sysPropGet = g
            g
        }.getOrNull() ?: return true
        return runCatching { (m.invoke(null, PROP, 1) as Int) == 1 }.getOrDefault(true)
    }

    /**
     * 取当前屏幕尺寸（宽, 高）。
     * 注意：不能用 Resources.getSystem().displayMetrics —— 它在 SystemUI 里会返回
     * 与当前朝向相反的旧值（实测竖屏时给出 2360x1671），导致阈值全错。
     * 这里读 DisplayManagerGlobal 的 DisplayInfo.logicalWidth/Height。
     */
    private fun screenSize(): Pair<Float, Float> {
        val cw = cachedW
        val ch = cachedH
        if (cw > 0f && ch > 0f) return cw to ch
        val r = rawScreenSize()
        cachedW = r.first
        cachedH = r.second
        return r
    }

    private fun rawScreenSize(): Pair<Float, Float> = runCatching {
        val g = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)!!
        val info = g.javaClass
            .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(g, 0)!!
        val w = info.javaClass.getField("logicalWidth").getInt(info).toFloat()
        val h = info.javaClass.getField("logicalHeight").getInt(info).toFloat()
        if (w > 0f && h > 0f) w to h else fallbackSize()
    }.getOrElse { fallbackSize() }

    private fun fallbackSize(): Pair<Float, Float> {
        val m = Resources.getSystem().displayMetrics
        val a = m.widthPixels.toFloat()
        val b = m.heightPixels.toFloat()
        // 兜底时按长边为高度（竖屏）处理
        return if (a > b) b to a else a to b
    }
}
