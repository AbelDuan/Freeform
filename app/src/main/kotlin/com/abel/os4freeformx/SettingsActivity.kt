package com.abel.os4freeformx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#F7F7F9"))
        }

        root.addView(TextView(this).apply {
            text = "OS4FreeFromX  v${Constants.VERSION}"
            textSize = 22f
            setTextColor(Color.parseColor("#111114"))
        })
        root.addView(TextView(this).apply {
            text = "HyperOS 4 小窗增强 · LSPosed"
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A8F"))
            setPadding(0, dp(4), 0, dp(12))
        })

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
        switchRow("分屏时四指上滑 → 增加分屏", Constants.K_FOUR_FINGER_SPLIT, Cfg.fourFingerSplit) { Cfg.fourFingerSplit = it }

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

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun section(title: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor("#3B6EF5"))
            setPadding(0, dp(16), 0, dp(4))
        })
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
