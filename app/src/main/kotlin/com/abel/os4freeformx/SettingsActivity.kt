package com.abel.os4freeformx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 小窗参数 / 功能设置（纯代码构建 UI，不依赖 XML 布局与 AndroidX）。
 * 全部读写都在本 App 进程内完成，不调用系统隐藏 API，故 HyperOS 4 上不会像上游那样 NoSuchMethodError 闪退。
 */
class SettingsActivity : Activity() {

    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.attachLocal(this)
        buildUi()
    }


    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 状态栏高度（px）；拿不到给 28dp 兜底，避免顶部标题被状态栏图标压住。 */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (h > 0) h else dp(28)
    }

    private fun buildUi() {
        val headerBg = Color.parseColor("#D6DEEE")
        val divider = Color.parseColor("#AEBBD4")
        val accent = Color.parseColor("#3B6EF5")
        val topInset = statusBarHeight()

        // 外层：状态栏那一条也刷成头部色，避免"状态栏纯白 / 头部蓝灰"出现第二道边界
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
        }
        // 状态栏占位（同头部色）
        outer.addView(View(this).apply { setBackgroundColor(headerBg) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topInset))
        // 顶部主题色细条：作为界面边界锚点，状态栏图标与标题同色也能一眼分清
        outer.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "OS4FreeFromX  v${Constants.VERSION}"
            textSize = 22f
            setTextColor(Color.parseColor("#111114"))
        })
        header.addView(TextView(this).apply {
            text = "HyperOS 4 小窗增强 · LSPosed"
            textSize = 12f
            setTextColor(Color.parseColor("#3E4A5E"))
            setPadding(0, dp(4), 0, 0)
        })
        outer.addView(header)
        outer.addView(View(this).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)

        section("沉浸式小窗底栏（三点 + 手势条一起隐藏，功能保留）")
        // 三点栏与手势条成对隐藏（UI 不画，点击/上滑功能保留）
        switchRow("隐藏三点与手势条（保留功能）", Constants.K_IMMERSIVE, Cfg.immersive) { Cfg.immersive = it }

        section("小窗尺寸")
        switchRow("分应用记忆窗口尺寸", Constants.K_REMEMBER_BOUNDS, Cfg.rememberBounds) { Cfg.rememberBounds = it }
        switchRow("折叠屏状态分别记忆", Constants.K_REMEMBER_FOLD, Cfg.rememberFold) { Cfg.rememberFold = it }
        switchRow("允许拖动调整尺寸", Constants.K_RESIZE, Cfg.resize) { Cfg.resize = it }
        numberRow("默认小窗宽度 (px，0=系统默认)", Constants.K_DEFAULT_W, Cfg.defaultW) { Cfg.defaultW = it }
        numberRow("默认小窗高度 (px，0=系统默认)", Constants.K_DEFAULT_H, Cfg.defaultH) { Cfg.defaultH = it }

        section("手势")
        switchRow("启用手势总开关", Constants.K_GESTURES, Cfg.gestures) { Cfg.gestures = it }
        switchRow("左右下角斜向中间滑 → 前台应用转小窗", Constants.K_CORNER_FREEFORM, Cfg.cornerFreeform) { Cfg.cornerFreeform = it }

        section("小白条（手势导航条）")
        switchRow("启用小白条（淡入淡出 / 跟随手势）", Constants.K_GESTURE_HANDLE, Cfg.gestureHandle) { Cfg.gestureHandle = it }
        switchRow("小白条跟随手指滑动", Constants.K_GESTURE_HANDLE_FOLLOW, Cfg.gestureHandleFollow) { Cfg.gestureHandleFollow = it }
        switchRow("触摸小白条区域时显隐", Constants.K_GESTURE_HANDLE_TOUCH, Cfg.gestureHandleTouch) { Cfg.gestureHandleTouch = it }
        switchRow("空闲时自动隐藏（沉浸）", Constants.K_GESTURE_HANDLE_IDLE, Cfg.gestureHandleIdle) { Cfg.gestureHandleIdle = it }
        sliderRow("底部命中带距离（触发宽度，dp）", Constants.K_GESTURE_HANDLE_AREA, Cfg.gestureHandleArea, 0, 48) { Cfg.gestureHandleArea = it }
        // 「小白条悬浮」= SystemUI 侧把手势导航栏底色恒置为全透明，pill 直接浮在应用内容上。
        // 实现见 NavBarTransparent.kt：hook NavigationBarTransitions.onTransition，把 mode 改成 TRANSPARENT。
        // 作用域固定（system + com.android.systemui），无需也不允许把第三方应用勾进来。
        switchRow(
            "小白条悬浮：底栏完全透明，pill 浮在应用内容上",
            Constants.K_GESTURE_HANDLE_FLOAT, Cfg.gestureHandleFloat
        ) { Cfg.gestureHandleFloat = it }

        section("悬浮生效范围")
        modeRow()
        pkgListRow()

        section("其他")
        switchRow("记录详细日志", Constants.K_ENABLE_LOG, Cfg.log) { Cfg.log = it; Logx.verbose = it }

        section("已记忆的应用尺寸")
        val info = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#55555A"))
            setPadding(0, dp(6), 0, dp(6))
        }
        body.addView(info)
        refreshInfo(info)
        body.addView(Button(this).apply {
            text = "清空记忆"
            setOnClickListener {
                contentResolver.call(android.net.Uri.parse("content://${Constants.AUTHORITY}"), "clear", null, null)
                Toast.makeText(this@SettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
                refreshInfo(info)
            }
        })

        root.addView(Button(this).apply {
            text = "保存并生效"
            setOnClickListener {
                Toast.makeText(this@SettingsActivity, "已保存（1~2 秒内生效）", Toast.LENGTH_SHORT).show()
            }
        })

        outer.addView(ScrollView(this).apply { addView(root) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(outer)
    }

    private fun section(title: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor("#3B6EF5"))
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    /** 悬浮生效范围：所有应用 / 仅白名单 / 黑名单外。用 RadioGroup 表达三选一。 */
    private fun modeRow() {
        // 实时状态行：把「当前到底是不是所有应用都生效」写清楚，避免用户以为开了开关却只对个别应用生效
        val live = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#3B6EF5"))
            setPadding(0, dp(2), 0, dp(2))
            text = liveModeSummary()
        }
        body.addView(live)

        val group = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        val items = listOf(
            Constants.FLOAT_MODE_ALL to "所有应用",
            Constants.FLOAT_MODE_WHITELIST to "仅白名单",
            Constants.FLOAT_MODE_BLACKLIST to "黑名单外",
        )
        items.forEach { (mode, label) ->
            group.addView(android.widget.RadioButton(this).apply {
                id = mode + 1000
                text = label
                textSize = 13f
                isChecked = Cfg.floatMode == mode
                setPadding(0, dp(4), dp(14), dp(4))
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = checkedId - 1000
            AppPrefs.putInt(this, Constants.K_FLOAT_MODE, mode)
            Cfg.floatMode = mode
            live.text = liveModeSummary()
            Toast.makeText(this@SettingsActivity, "范围已改为：${Cfg.floatModeLabel()}", Toast.LENGTH_SHORT).show()
        }
        body.addView(group)
    }

    /** 一句话说明当前范围 + 名单条数，用于消除"到底对谁生效"的疑惑。 */
    private fun liveModeSummary(): String {
        val n = Cfg.floatPkgsRawLineCount()
        val sw = if (Cfg.gestureHandleFloat) "悬浮开关：已开启" else "悬浮开关：已关闭（下面范围不生效）"
        val scope = when (Cfg.floatMode) {
            Constants.FLOAT_MODE_WHITELIST -> "当前范围：仅所列 $n 个应用生效"
            Constants.FLOAT_MODE_BLACKLIST -> "当前范围：除所列 $n 个应用外都生效"
            else -> "当前范围：所有应用生效（名单忽略）"
        }
        return "$sw  ·  $scope"
    }

    /**
     * 白/黑名单包名编辑 + 从已安装应用一键勾选。
     * 写入 K_FLOAT_PKGS（换行分隔），Cfg 侧按逗号/空格/换行切分。
     */
    private fun pkgListRow() {
        val label = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#55555A"))
            setPadding(0, dp(4), 0, dp(2))
            text = "名单（${Cfg.floatModeLabel()}）：共 ${Cfg.floatPkgsRawLineCount()} 项"
        }
        body.addView(label)

        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(Cfg.floatPkgsRaw)
            textSize = 12f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        body.addView(edit)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "保存名单"
            setOnClickListener {
                val v = edit.text.toString()
                AppPrefs.putString(this@SettingsActivity, Constants.K_FLOAT_PKGS, v)
                Cfg.floatPkgsRaw = v
                // 触发 Cfg 重新切分集合（直接经 provider 拉一次最稳）
                Cfg.reloadThrottled(0)
                label.text = "名单（${Cfg.floatModeLabel()}）：共 ${Cfg.floatPkgsRawLineCount()} 项"
                Toast.makeText(this@SettingsActivity, "已保存，1~2 秒内生效", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(Button(this).apply {
            text = "从应用列表勾选"
            setOnClickListener { showAppPicker(edit, label) }
        })
        body.addView(row)
    }

    /**
     * 可搜索的已安装应用多选界面（纯框架控件，不依赖 AndroidX）。
     *
     * 旧版把所有应用一次性塞进 AlertDialog 的 ScrollView —— 几百个条目既卡又难找，
     * 而且 AlertDialog 里的 ScrollView 高度会被标题/按钮挤扁。这里改成：
     *   · 独立 Activity（AppPickerActivity）承载，整屏可用；
     *   · 顶部 EditText 实时过滤（名称 / 包名，忽略大小写）；
     *   · ListView + 稳定 id，勾选状态不因滚动错位；
     *   · 已选数量实时显示，支持「只显示已选」。
     */
    private fun showAppPicker(edit: EditText, label: TextView) {
        AppPickerActivity.open(this, Cfg.floatPkgsRaw) { picked ->
            val v = picked.joinToString("\n")
            edit.setText(v)
            AppPrefs.putString(this, Constants.K_FLOAT_PKGS, v)
            Cfg.floatPkgsRaw = v
            Cfg.reloadThrottled(0)
            label.text = "名单（${Cfg.floatModeLabel()}）：共 ${Cfg.floatPkgsRawLineCount()} 项"
            Toast.makeText(this, "已保存 ${picked.size} 个应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchRow(title: String, key: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        body.addView(Switch(this).apply {
            text = title
            textSize = 14f
            isChecked = checked
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, v ->
                AppPrefs.putBoolean(this@SettingsActivity, key, v)
                onChange(v)
            }
        })
    }

    private fun sliderRow(title: String, key: String, value: Float, min: Int, max: Int, onChange: (Float) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val label = TextView(this).apply {
            text = "$title：${value.toInt()}dp"
            textSize = 13f
            setTextColor(Color.parseColor("#111114"))
        }
        val seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            this.max = max - min
            progress = (value.toInt() - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = (prog + min).toFloat()
                    label.text = "$title：${v.toInt()}dp"
                    if (fromUser) {
                        AppPrefs.putFloat(this@SettingsActivity, key, v)
                        onChange(v)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(label)
        row.addView(seek)
        body.addView(row)
    }

    private fun numberRow(title: String, key: String, value: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            layoutParams = LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(edit)
        row.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                val v = edit.text.toString().toIntOrNull() ?: 0
                AppPrefs.putInt(this@SettingsActivity, key, v)
                onChange(v)
                Toast.makeText(this@SettingsActivity, "已保存 $v", Toast.LENGTH_SHORT).show()
            }
        })
        body.addView(row)
    }

    private fun refreshInfo(tv: TextView) {
        // App 进程内存里没有 bounds，必须先经 Provider 拉一次，否则列表永远是空的
        Bounds.loadNow(this)
        val dm = resources.displayMetrics
        val all = Bounds.all()
        val sb = StringBuilder()
        sb.append(Bounds.screenStateLabel(this))
        sb.append("    dpi=${dm.densityDpi}\n")
        sb.append("记忆 key = 包名|屏幕宽x高，共 ${all.size} 条\n")
        if (all.isEmpty()) sb.append("（暂无记录）")
        else all.entries.take(30).forEach { (k, r) -> sb.append("$k → ${r.width()}x${r.height()} @${r.left},${r.top}\n") }
        tv.text = sb.toString()
    }
}
