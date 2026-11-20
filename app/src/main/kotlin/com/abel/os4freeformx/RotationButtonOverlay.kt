package com.abel.os4freeformx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * 自己绘制的旋转提示悬浮按钮（自 OS4 Themer 的 RotationButtonOverlay 移植，2026-11-20）。
 *
 * 原始设计（OS4 Themer 用户 2026-10-22 定稿）：
 * > 「到了每个角度，计算一个值，算出这个角标应该显示在什么地方，然后让它显示不就好了吗？」
 *
 * 所以这里不借用系统的 `FloatingRotationButton`（它有 `mIsShowing` 状态机、
 * 依赖 addView/removeView 重建、位置由 gravity + ±40px translation 组合决定，
 * 快速旋转时反复拆建窗口就会丢失/跟不上）。本类只做三件事：
 * 1. 创建**一个**窗口，生命周期内不重建（除非换屏或屏幕尺寸真变）；
 * 2. 位置由 [cornerFor] 算出的角落决定，用 `WindowManager.LayoutParams.x/y` 绝对坐标，
 *    一次 `updateViewLayout` 就到位；
 * 3. 显示/隐藏只改 `View.VISIBLE / GONE`，不碰窗口。
 *
 * ⚠️ 两个从血泪里换来的实现要点（照抄 OS4 Themer，不要「优化」掉）：
 * - **可见性绝不交给动画**：addView 后视图往往还没 attach，
 *   `animate().alpha(0f→1f)` 一旦没跑，alpha 就永久停在 0 —— 窗口明明在，
 *   肉眼却什么都没有。所以先 `setAlpha(1f)`，动画只做缩放。
 * - **隐藏时彻底 removeView**：悬浮窗（TYPE_NAVIGATION_BAR_PANEL）常驻会参与
 *   系统的 dim/覆盖层计算，长按通知等场景会让「整屏亮度/通透度」变化。用完即移除。
 */
internal object RotationButtonOverlay {

    private const val TAG = "[旋转按钮]"

    /** 按钮直径（dp）。 */
    private const val SIZE_DP = 56

    /** 距屏幕边缘（dp）。往对角线内侧收，不贴边。 */
    private const val MARGIN_DP = 64

    /**
     * `WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL` 的数值。
     *
     * 这个是 `@hide` 常量，编译期的 android.jar 里看不到，只能写死数值。
     * 2024 与系统自己的 `FloatingRotationButton` 用的是同一个窗口类型，
     * 这样层级与 `TYPE_APPLICATION_OVERLAY` 相比更贴合导航条、不会被应用窗口盖住。
     * 该数值是 Android 平台侧的稳定事实，不随 ROM 版本漂移。
     */
    private const val NAV_BAR_PANEL_TYPE = 2024

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var sizePx = 0
    private var marginPx = 0
    private var added = false
    private var lastCtx: Context? = null
    private var showingCorner = -1
    private var onClick: (() -> Unit)? = null

    /** 按下是否发生在按钮上（抬起才触发一次）。 */
    @Volatile private var downInView = false

    /** 窗口创建时的屏幕尺寸：只有真的变了才重建窗口。 */
    private var lastW = 0
    private var lastH = 0

    fun setOnClick(r: () -> Unit) {
        onClick = r
    }

    fun showingCornerOf(): Int = showingCorner

    /**
     * 算出按钮该出现在哪个角落。
     *
     * 输入是「传感器方向」与「屏幕方向」（都是 Surface.ROTATION_*），
     * 输出 0~3 表示钟表意义的四个角（0=左上 1=右上 2=右下 3=左下）。
     *
     * 推导：传感器方向 `s` 表示「手机被转到哪边」，屏幕方向 `r` 表示「当前画面朝哪边」，
     * 两者做差得到「相对当前画面，用户手持的那个方向在哪个角」。
     * 注意传感器与屏幕的方向编号并不是同一套（1 和 3 互换），所以要再过一次 `cw` 表。
     */
    fun cornerFor(sensorRotation: Int, screenRotation: Int): Int {
        val physical = when (sensorRotation) {
            1 -> 2
            2 -> 0
            3 -> 1
            else -> 3
        }
        val cw = intArrayOf(0, 1, 3, 2)
        var physIdx = 0
        for (i in 0 until 4) {
            if (cw[i] == physical) {
                physIdx = i
                break
            }
        }
        val screenIdx = ((physIdx - screenRotation) % 4 + 4) % 4
        return cw[screenIdx]
    }

    /** 显示到 [corner]（0=左上 1=右上 2=右下 3=左下）。 */
    fun show(ctx: Context, corner: Int) {
        val r = Runnable { showInner(ctx, corner) }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
    }

    private fun showInner(ctx: Context, corner: Int) {
        try {
            lastCtx = ctx
            ensureWindow(ctx)
            val wm0 = wm ?: return
            val v = view ?: return
            val lp0 = lp ?: return
            val dm = ctx.resources.displayMetrics
            var w = dm.widthPixels
            var h = dm.heightPixels
            val m = marginPx
            val s = sizePx
            // 🔑 以「当前窗口容器」的尺寸为准（OS4 Themer v3.294.0 的修复）。
            //   系统在 orientation 变化时会**先缩容器、再旋转**，这中间
            //   Display#rotation 已经是新值而容器还是旧尺寸 —— 只按 displayMetrics
            //   算，按钮会落到屏幕外（表现为「点了没反应」）。
            runCatching {
                val root = v.parent as? View
                if (root != null && root.width > s && root.height > s) {
                    w = root.width
                    h = root.height
                }
            }
            // 角落编号与坐标严格照 OS4 Themer 原版：
            //   0=左上 1=右上 2=**左下** 3=**右下**
            when (corner) {
                0, 2 -> lp0.x = m
                else -> lp0.x = w - s - m
            }
            if (corner <= 1) lp0.y = m else lp0.y = h - s - m
            lp0.gravity = Gravity.TOP or Gravity.START

            if (!added) {
                runCatching { wm0.addView(v, lp0) }
                added = true
            } else {
                // 关键：只改 x/y，一次调用到位，不拆建窗口
                runCatching { wm0.updateViewLayout(v, lp0) }
            }
            applyThemeTint(ctx)
            val wasHidden = v.visibility != View.VISIBLE
            v.visibility = View.VISIBLE
            if (wasHidden) {
                fadeIn(v)
                // 恢复系统图标的旋转动画（独立实例，不会和系统按钮抢）
                playIconAnimation()
            } else {
                // 可能正处在淡出中 → 拉回完全不透明
                v.animate().cancel()
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
            }
            showingCorner = corner
            Logx.always("$TAG 已显示 corner=$corner (${lp0.x},${lp0.y})")
        } catch (t: Throwable) {
            Logx.e("$TAG 显示失败", t)
        }
    }

    /** 立即收起（同步，无过渡）：方向一变就调用，图标当场消失。 */
    fun hideNow() {
        val r = Runnable { hideInner() }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
    }

    private fun hideInner() {
        try {
            showingCorner = -1
            val v = view
            if (v != null) {
                v.animate().cancel()
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
                v.visibility = View.GONE
            }
            // 隐藏时彻底移除窗口：常驻透明窗口会参与系统 dim 计算，
            // 长按通知等场景会让整屏亮度变化（OS4 Themer 实测踩过）。用完即移除。
            if (added) {
                runCatching { wm?.removeViewImmediate(v) }
                added = false
            }
        } catch (t: Throwable) {
            Logx.v("$TAG hideNow 异常: $t")
        }
    }

    /** 出现：只做缩放，**可见性绝不交给动画**。 */
    private fun fadeIn(v: View) {
        runCatching {
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 0.88f
            v.scaleY = 0.88f
            v.animate().scaleX(1f).scaleY(1f).setDuration(110L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /**
     * 屏幕切换时丢弃窗口，下次显示时按当前活跃屏幕重建。
     *
     * OS4 Themer 2026-10-22 用户反馈：外屏切内屏后图标位置沿用了外屏尺寸（不在边缘）。
     * 根因是窗口创建时绑定的是当时的默认屏幕，折叠切换后那块屏尺寸变了、窗口还留在原屏。
     *
     * ⚠️ 但**不能每次都重建**：`DisplayListener.onDisplayChanged` 在转屏过程中每帧都可能触发，
     * 无条件销毁重建会让「手指还按在旧窗口上，UP 送不到新窗口」→ 点击被整段丢弃
     * （用户看到「图标闪一下、没反应」）。所以只有**尺寸真的变了**才重建。
     */
    fun reset() {
        val r = Runnable {
            val v = view
            val showing = showingCorner
            val ctx = lastCtx
            // 读当前屏幕尺寸
            var newW = 0
            var newH = 0
            runCatching {
                val d = v?.display ?: ctx?.display
                if (d != null) {
                    val dm = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    d.getRealMetrics(dm)
                    newW = dm.widthPixels
                    newH = dm.heightPixels
                }
            }
            val needRebuild = v == null || !added ||
                    (lastW > 0 && newW > 0 && (lastW != newW || lastH != newH))
            // 只在读到有效尺寸时才更新基准：拿 0 覆盖会让下次永远判不出变化
            if (newW > 0) {
                lastW = newW
                lastH = newH
            }
            if (!needRebuild) {
                Logx.v("$TAG 屏幕回调但尺寸未变，保留窗口（不重建）")
                return@Runnable
            }
            Logx.always("$TAG 换屏/尺寸变化，重建窗口 (${newW}x$newH)")
            hideInner()
            view = null
            lp = null
            // 之前在显示 → 立刻按新屏幕重建，避免按钮凭空消失
            if (showing >= 0 && ctx != null) {
                showInner(ctx, showing)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
    }

    // ======================================================================
    //  图标与主题（完全照 OS4 Themer 的实现）
    // ======================================================================

    /**
     * 取系统自己的旋转建议图标，让按钮外观与系统原生按钮**完全一致**。
     *
     * 本机 MiuiSystemUI.apk 实测存在这 4 个资源（就是系统 FloatingRotationButton 用的那套）：
     * ```
     *   ic_sysbar_rotate_button_ccw_start_0    逆时针、起点 0
     *   ic_sysbar_rotate_button_ccw_start_90   逆时针、起点 90
     *   ic_sysbar_rotate_button_cw_start_0     顺时针、起点 0
     *   ic_sysbar_rotate_button_cw_start_90    顺时针、起点 90
     * ```
     * 它们是 `AnimatedVectorDrawable`（带动画），所以这里拿到后还要在显示时 `start()`。
     *
     * ⚠️ 绝不能直接共享系统 `FloatingRotationButton.mAnimatedDrawable`：
     * Drawable 带 callback，同一实例挂两个 View 会互相打断动画
     * （OS4 Themer 实测「要等上一个界面的动画播完，5 秒后下一个才出现」）。
     * 所以 `ctx.getDrawable(id)` 每次新建独立实例。
     */
    private fun systemIcon(ctx: Context): android.graphics.drawable.Drawable? {
        val res = ctx.resources
        // 按"继续往哪边转"挑图标：ccw = 逆时针（rotation 增加），cw = 顺时针
        val names = arrayOf(
            "ic_sysbar_rotate_button_ccw_start_0",
            "ic_sysbar_rotate_button_cw_start_0",
            "ic_sysbar_rotate_button_ccw_start_90",
            "ic_sysbar_rotate_button_cw_start_90"
        )
        // ① SystemUI 包里的（系统按钮用的就是它）
        for (n in names) {
            runCatching {
                val id = res.getIdentifier(n, "drawable", "com.android.systemui")
                if (id != 0) {
                    val d = ctx.getDrawable(id)
                    if (d != null) {
                        Logx.always("$TAG 使用系统图标 $n (id=$id) → ${d.javaClass.simpleName}")
                        return d
                    }
                }
            }
        }
        // ② 退一步：按资源名在整个系统资源里找（MIUI 有时把资源放在别的包）
        for (n in names) {
            runCatching {
                val id = android.content.res.Resources.getSystem().getIdentifier(n, "drawable", "android")
                if (id != 0) {
                    val d = android.content.res.Resources.getSystem().getDrawable(id, null)
                    if (d != null) {
                        Logx.always("$TAG 使用框架图标 $n")
                        return d
                    }
                }
            }
        }
        Logx.always("$TAG 未取到系统图标，使用自绘")
        return null
    }

    /**
     * 按深色/浅色模式给图标着色并配圆形底。
     *
     * OS4 Themer 用户 2026-10-22 反馈：系统的 `AnimatedVectorDrawable` 颜色固定为白，
     * 浅色模式下在浅色背景上几乎看不见（「消失了」）。所以做成**反色块**：
     * 浅色模式 → 深色底 + 白图标；深色模式 → 浅色底 + 近黑图标。
     */
    private fun applyThemeTint(ctx: Context) {
        val v = view ?: return
        runCatching {
            val night = (ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val iconColor: Int
            val bgColor: Int
            if (night) {
                iconColor = 0xFF101014.toInt()   // 近黑图标
                bgColor = 0x99EDEDED.toInt()     // 浅灰底 · 60%
            } else {
                iconColor = 0xFFFFFFFF.toInt()   // 白图标
                bgColor = 0x992A2A2E.toInt()     // 深灰底 · 60%
            }
            v.colorFilter = android.graphics.PorterDuffColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN)
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(bgColor)
            }
        }
    }

    /** 播放图标自带的旋转动画（独立实例，不影响系统按钮）。 */
    private fun playIconAnimation() {
        runCatching {
            val d = view?.drawable
            if (d is android.graphics.drawable.AnimatedVectorDrawable) {
                d.reset()
                d.start()
            }
        }
    }

    /** 惰性创建窗口（尺寸变化时重建）。 */
    private fun ensureWindow(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (view != null && w == lastW && h == lastH) return
        lastW = w
        lastH = h
        val density = dm.density
        sizePx = (SIZE_DP * density).toInt()
        marginPx = (MARGIN_DP * density).toInt()

        val wm0 = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = wm0
        val v = ImageView(ctx).apply {
            // 图标与系统自己的旋转建议按钮**完全一致**（照 OS4 Themer）：
            // 优先用 SystemUI 里那个带动画的 AnimatedVectorDrawable，取不到才自绘。
            setImageDrawable(systemIcon(ctx) ?: RotationIcon(sizePx))
            setOnClickListener { onClick?.invoke() }
            visibility = View.GONE
        }
        // 按下必须在按钮内、抬起也在按钮内才算一次点击（防御误触）
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> downInView = true
                android.view.MotionEvent.ACTION_CANCEL -> downInView = false
            }
            false
        }
        view = v
        lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            NAV_BAR_PANEL_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setTitle("os4ffx-rotation-button")
        }
        added = false
    }
}

/**
 * 兜底图标：一个带弧线箭头的旋转符号，**纯白剪影**。
 *
 * 为什么要纯白：显示时 `applyThemeTint` 会用 `SRC_IN` 的 `colorFilter` 把它整体染色
 * （深色模式染近黑、浅色模式染纯白）。如果这里自己再画白底深色箭头，
 * 染色后会变成一整块纯色 —— 图标就"消失"了（2026-11-20 实测踩到）。
 * 底色统一由 `applyThemeTint` 的圆形 GradientDrawable 提供。
 */
private class RotationIcon(private val size: Int) : android.graphics.drawable.Drawable() {

    private val paint = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()      // 纯白剪影，交给 colorFilter 染色
        style = Paint.Style.STROKE
        strokeWidth = size * 0.085f
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }
    private val arrowPath = Path()

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = size / 2f
        val arcR = r * 0.44f

        // ① 3/4 圆弧
        val oval = android.graphics.RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        canvas.drawArc(oval, -60f, 285f, false, paint)

        // ② 弧末端箭头（指向顺时针方向）
        val ang = Math.toRadians(-60.0)
        val tipX = cx + (arcR * Math.cos(ang)).toFloat()
        val tipY = cy + (arcR * Math.sin(ang)).toFloat()
        val a = size * 0.115f
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY - a * 0.9f)
        arrowPath.lineTo(tipX + a, tipY + a * 0.35f)
        arrowPath.lineTo(tipX - a * 0.9f, tipY + a * 0.55f)
        arrowPath.close()
        canvas.drawPath(arrowPath, fillPaint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("固定为半透明绘制")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
