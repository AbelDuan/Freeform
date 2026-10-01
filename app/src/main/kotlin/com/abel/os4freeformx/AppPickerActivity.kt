package com.abel.os4freeformx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 「导航栏调整」：一个应用列表，给每个应用三选一 —— 不启用 / 隐藏导航栏 / 导航栏取色。
 *
 *   · **点整行循环切换**：不启用 → 隐藏 → 取色 → 不启用；
 *   · **已启用的应用置顶**（一眼看到自己配了哪些）；
 *   · 所有修改**只在点「应用」时一次性提交**（写系统名单 + 让目标应用重启生效）；
 *     想撤销用底部「重置」。
 *
 * 为什么不做成"先选功能、再勾应用"两步：那样同一应用可能被两个功能都选到。
 * 这里每个应用只有**一个**归属状态，结构上不存在冲突。
 */
class AppPickerActivity : Activity() {

    private class AppEntry(val pkg: String, val name: String, val isSystem: Boolean)

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    /** 包名 → 归属（1=隐藏导航栏 2=导航栏取色）。没有键 = 不启用。 */
    private val assign = HashMap<String, Int>()

    /** 进入页面时的快照，供「重置」使用。 */
    private val original = HashMap<String, Int>()

    private var onlyAssigned = false
    private var hideSystem = true

    private lateinit var listView: ListView
    private lateinit var adapter: Adapter
    private lateinit var countView: TextView
    private lateinit var query: EditText

    /** 不启用 → 隐藏 → 取色 → 不启用 */
    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].pkg.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = dp(16)
                setPadding(pad, dp(10), pad, dp(10))
                setBackgroundColor(Color.WHITE)
            }
            val e = shown[position]
            val m = assign[e.pkg]

            // 右侧（其实是左侧）三选一下拉：不启用 / 隐藏 / 取色
            val spinner = row.getChildAt(0) as? Spinner ?: Spinner(this@AppPickerActivity).apply {
                adapter = ArrayAdapter(
                    this@AppPickerActivity,
                    android.R.layout.simple_spinner_item,
                    arrayOf("不启用", "隐藏", "取色")
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                layoutParams = LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT)   // 装得下"不启用"三个字
                row.addView(this)
            }
            // ⚠️ ListView 会复用行：必须先摘掉监听器再 setSelection，
            // 否则滚动时会把上一行的选择写到这一行上（Spinner 的经典坑）。
            spinner.onItemSelectedListener = null
            spinner.setSelection(
                when (m) {
                    Constants.NBI_HIDE -> 1
                    Constants.NBI_SAMPLE -> 2
                    else -> 0
                }
            )
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    when (position) {
                        1 -> assign[e.pkg] = Constants.NBI_HIDE
                        2 -> assign[e.pkg] = Constants.NBI_SAMPLE
                        else -> assign.remove(e.pkg)
                    }
                    // 只更新统计，**不重排列表** —— 否则刚改成"不启用"就会沉底，编辑过程没法操作
                    updateCount()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val text = row.getChildAt(1) as? TextView ?: TextView(this@AppPickerActivity).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#111114"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(this)
            }
            text.text = "${e.name}\n${e.pkg}"
            text.setPadding(dp(10), 0, 0, 0)

            // 行本身不再整体可点（避免与下拉箭头抢事件）
            row.setOnClickListener(null)
            row.isClickable = false
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val topInset = statusBarHeight()

        assign.clear()
        intent.getStringArrayListExtra(EXTRA_ASSIGN)?.forEach { line ->
            val parts = line.split('=')
            if (parts.size == 2) {
                val m = parts[1].trim().toIntOrNull()
                if (m == Constants.NBI_HIDE || m == Constants.NBI_SAMPLE) assign[parts[0].trim()] = m
            }
        }
        original.clear(); original.putAll(assign)

        val headerBg = Color.parseColor("#D6DEEE")
        val divider = Color.parseColor("#AEBBD4")
        val accent = Color.parseColor("#3B6EF5")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, topInset, 0, 0)
        }
        root.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
        }
        header.addView(TextView(this).apply {
            text = "导航栏调整"
            textSize = 20f
            setTextColor(Color.parseColor("#111114"))
            setBackgroundColor(headerBg)
            setPadding(dp(16), dp(12), dp(16), dp(2))
        })
        header.addView(TextView(this).apply {
            text = "每个应用用左侧下拉选：不启用 / 隐藏 / 取色；改完点底部「应用」"
            textSize = 12f
            setTextColor(Color.parseColor("#4A5568"))
            setBackgroundColor(headerBg)
            setPadding(dp(16), 0, dp(16), dp(6))
        })

        query = EditText(this).apply {
            hint = "搜索应用名或包名"
            textSize = 14f
            setSingleLine()
            setTextColor(Color.parseColor("#111114"))
            setHintTextColor(Color.parseColor("#9A9AA0"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusableInTouchMode = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }.apply {
            addView(query, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        fun toolBtn(label: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#2A3346"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { onClick(this) }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(3), dp(4), dp(3))
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(headerBg)
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        tools.addView(toolBtn("只显示已启用") { b ->
            onlyAssigned = !onlyAssigned
            b.text = if (onlyAssigned) "显示全部" else "只显示已启用"
            applyFilter(query.text?.toString().orEmpty())
        }, lp)
        tools.addView(toolBtn("含系统应用") { b ->
            hideSystem = !hideSystem
            b.text = if (hideSystem) "含系统应用" else "仅用户应用"
            applyFilter(query.text?.toString().orEmpty())
        }, lp)
        header.addView(tools)
        root.addView(header)

        root.addView(View(this).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        countView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(countView)

        listView = ListView(this).apply {
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = Adapter()
        listView.adapter = adapter
        root.addView(listView)

        root.addView(View(this).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        bar.addView(Button(this).apply {
            text = "重置"
            textSize = 13f
            setOnClickListener {
                assign.clear(); assign.putAll(original)
                applyFilter(query.text?.toString().orEmpty())
                Toast.makeText(this@AppPickerActivity, "已还原为进入前的设置（未提交）", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        bar.addView(Button(this).apply {
            text = "应用"
            textSize = 13f
            setOnClickListener {
                onResult?.invoke(HashMap(assign))
                Toast.makeText(this@AppPickerActivity, "已提交，正在应用…", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        root.addView(bar)

        setContentView(root)
        loadApps()
        applyFilter("")
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val list = runCatching {
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map {
                        val label = runCatching { pm.getApplicationLabel(it).toString() }
                            .getOrDefault(it.packageName)
                        val sys = (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                (it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        AppEntry(it.packageName, label, sys)
                    }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.pkg }))
                    .toList()
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                all = list
                applyFilter(query.text?.toString().orEmpty())
            }
        }.apply { isDaemon = true; name = "os4ffx-nbi-picker" }.start()
    }

    /** 过滤 + 排序：**已启用的一律置顶**，其余按名称。搜索时前缀命中仍优先（组内）。 */
    private fun applyFilter(q: String) {
        val key = q.trim().lowercase()
        var seq = all.asSequence()
        if (hideSystem) seq = seq.filter { !it.isSystem }
        if (onlyAssigned) seq = seq.filter { assign.containsKey(it.pkg) }
        if (key.isNotEmpty()) {
            seq = seq.filter { it.name.lowercase().contains(key) || it.pkg.lowercase().contains(key) }
        }
        shown = seq.sortedWith(
            compareBy(
                { !assign.containsKey(it.pkg) },                       // 已启用置顶
                { key.isNotEmpty() && !it.name.lowercase().startsWith(key) && !it.pkg.lowercase().startsWith(key) },
                { it.name.lowercase() }, { it.pkg }
            )
        ).toList()
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        val sysNote = if (hideSystem) "（已隐藏系统应用）" else "（含系统应用）"
        countView.text = "已启用 隐藏 ${assign.count { it.value == Constants.NBI_HIDE }} 个 · " +
                "取色 ${assign.count { it.value == Constants.NBI_SAMPLE }} 个 · " +
                "当前显示 ${shown.size} 个 · 共 ${all.size} 个$sysNote"
    }

    /** 返回键 = 放弃本次改动（必须点「应用」才提交，符合用户要求）。 */
    @Deprecated("兼容旧 API")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (h > 0) h else dp(28)
    }

    companion object {
        private const val EXTRA_ASSIGN = "assign"

        private var onResult: ((HashMap<String, Int>) -> Unit)? = null

        /** [current] 为"包名=模式"逐行文本（与 Cfg.assignRaw 同格式）。 */
        fun open(ctx: Context, current: String, cb: (HashMap<String, Int>) -> Unit) {
            onResult = cb
            val cur = current.split('\n', ',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            ctx.startActivity(Intent(ctx, AppPickerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_ASSIGN, ArrayList(cur))
            })
        }
    }
}
