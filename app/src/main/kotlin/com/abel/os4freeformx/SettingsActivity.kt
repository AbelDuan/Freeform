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
        // 防御：某些 ROM 会按 Activity 的 label 生成一层"decor 标题"（清单里已去掉 label），
        // 万一仍存在就直接隐藏，避免与自绘顶栏重叠成"双层顶栏"。
        runCatching { actionBar?.hide() }
        runCatching { title = "" }
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
            setPadding(dp(20), dp(10), dp(20), 0)   // 底部不留白：否则头部色会在标题与内容之间露出一条色带
        }
        // 标题行：左标题 + 右上角小按钮（重启 SystemUI）。界面本身要自绘，不能用 ActionBar 按钮。
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "OS4FreeFromX  v${Constants.VERSION}"
            textSize = 22f
            setTextColor(Color.parseColor("#111114"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "重启 SystemUI"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                Toast.makeText(this@SettingsActivity, "正在重启 SystemUI…", Toast.LENGTH_SHORT).show()
                Thread {
                    val r = runCatching {
                        val p2 = ProcessBuilder("su", "-c", "killall com.android.systemui")
                            .redirectErrorStream(true).start()
                        p2.inputStream.bufferedReader().readText()
                        p2.waitFor()
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@SettingsActivity,
                            if (r.isSuccess) "已重启 SystemUI（需要 root 授权）"
                            else "重启失败：${r.exceptionOrNull()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.apply { isDaemon = true }.start()
            }
        })
        header.addView(titleRow)
        header.addView(TextView(this).apply {
            text = "HyperOS 4 小窗增强 · LSPosed"
            textSize = 12f
            setTextColor(Color.parseColor("#3E4A5E"))
            setPadding(0, dp(4), 0, 0)
        })
        outer.addView(header)
        // （原来这里有一条 1px 分隔色条；用户反馈"顶栏跟内容之间还有一条颜色条"，已去掉。）

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
        switchRow("小窗比例调节（三点菜单那排比例按钮 + 放大可调范围）", Constants.K_RATIO_MENU, Cfg.ratioMenu) { Cfg.ratioMenu = it }

        section("手势")
        // 角滑已下线（2026-10-02）：该手势需要吞 MIUI 输入流才能屏蔽系统手势，
        // 而这条链路会 ANR → SystemUI 被杀，故整体移除。设置项一并移除。

        section("小白条（手势导航条）")
        switchRow("启用小白条（淡入淡出 / 跟随手势）", Constants.K_GESTURE_HANDLE, Cfg.gestureHandle) { Cfg.gestureHandle = it }
        switchRow("小白条跟随手指滑动", Constants.K_GESTURE_HANDLE_FOLLOW, Cfg.gestureHandleFollow) { Cfg.gestureHandleFollow = it }
        switchRow("触摸小白条区域时显隐", Constants.K_GESTURE_HANDLE_TOUCH, Cfg.gestureHandleTouch) { Cfg.gestureHandleTouch = it }
        switchRow("空闲时自动隐藏（沉浸）", Constants.K_GESTURE_HANDLE_IDLE, Cfg.gestureHandleIdle) { Cfg.gestureHandleIdle = it }
        sliderRow("底部命中带距离（触发宽度，dp）", Constants.K_GESTURE_HANDLE_AREA, Cfg.gestureHandleArea, 0, 48) { Cfg.gestureHandleArea = it }
        // ── 导航栏（NBI）：两个功能，各带自己的应用名单 ──
        // 实现见 Nbi.kt / NbiApply.kt：写进 HyperOS 自带的沉浸导航名单
        // （/data/system/cloudFeature_navigation_bar_immersive_rules_list.json），
        // 再 `cmd miui_navigation_bar_immersive update` 让 system_server 重读 —— 全程免重启。
        // 两条规则只差一个字段：隐藏 = mode:1 + color:0（全透明）；取色 = mode:1（走采样）。
        // ── 导航栏（NBI）：一份名单，每个应用只归属一个功能 ──
        // 隐藏 = mode:2（整个底栏消失、内容铺到最底，与小鹏原版一致）；
        // 取色 = mode:1（不带 color ⇒ 采样界面主色）。
        // 落地：写系统名单 → `cmd miui_navigation_bar_immersive update` → 重启目标应用（免重启系统）。
        // ── 导航栏（NBI）──
        // 「导航栏调整」= 应用列表里给每个应用三选一（不启用 / 隐藏 / 取色），已启用置顶，
        // 改完点「应用」一次性提交：写系统名单 → `cmd miui_navigation_bar_immersive update`
        // → 模块代劳重启目标应用（NBI 要求 restart the application）。
        section("导航栏")
        navRow()

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

    /** 「导航栏调整」入口：显示当前统计，点进去打开应用列表。 */
    private fun navRow() {
        val label = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#55555A"))
            setPadding(0, dp(4), 0, dp(2))
        }
        fun refresh() {
            label.text = "当前：隐藏 ${Cfg.assignCount(Constants.NBI_HIDE)} 个应用 · " +
                    "取色 ${Cfg.assignCount(Constants.NBI_SAMPLE)} 个应用"
        }
        refresh()
        body.addView(label)

        body.addView(Button(this).apply {
            text = "导航栏调整（选择应用）"
            setOnClickListener {
                AppPickerActivity.open(this@SettingsActivity, Cfg.assignRaw) { map ->
                    val v = map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
                    AppPrefs.putString(this@SettingsActivity, Constants.K_NBI_ASSIGN, v)
                    Cfg.setAssign(v)
                    Cfg.reloadThrottled(0)
                    refresh()
                    NbiApply.applyAsync(this@SettingsActivity) { r ->
                        runOnUiThread {
                            Toast.makeText(
                                this@SettingsActivity,
                                if (r.ok) "已应用并重启目标应用；立即生效"
                                else "写入失败：${r.msg.take(160)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
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
