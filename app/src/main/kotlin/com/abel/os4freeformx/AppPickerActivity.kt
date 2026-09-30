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
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 可搜索的应用多选器（纯框架控件，不依赖 AndroidX）。
 *
 * 为什么单独开一个 Activity 而不是 AlertDialog：
 *   ① 已安装应用常有 300~500 个，AlertDialog 的 ScrollView 会被标题与按钮挤到只剩两三行高；
 *   ② 需要顶部搜索框 + 底部「已选 N 个」实时统计，独立页面才有足够空间；
 *   ③ 点击返回键/取消时不改动原名单，避免误操作。
 *
 * 结果通过 [onResult] 回调（静态引用）交回 SettingsActivity；进程被杀时回调为 null，静默放弃。
 */
class AppPickerActivity : Activity() {

    private class AppEntry(val pkg: String, val name: String, val isSystem: Boolean)

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private val selected = HashSet<String>()

    /** 进入页面时的原始名单，用于底部「重置为上次保存」。 */
    private val original = HashSet<String>()
    private var onlySelected = false
    private var hideSystem = true

    private lateinit var listView: ListView
    private lateinit var adapter: Adapter
    private lateinit var countView: TextView
    private lateinit var query: EditText

    private class Adapter(val host: AppPickerActivity) : BaseAdapter() {
        override fun getCount(): Int = host.shown.size
        override fun getItem(position: Int): Any = host.shown[position]
        override fun getItemId(position: Int): Long = host.shown[position].pkg.hashCode().toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = host.dp(16)
                setPadding(pad, host.dp(10), pad, host.dp(10))
                // 行底色显式给白，避免透明行透出窗口底色（深色模式下会压黑字看不清）
                setBackgroundColor(Color.WHITE)
            }
            val e = host.shown[position]
            val box = row.getChildAt(0) as? CheckBox ?: CheckBox(host).apply {
                // 复选框本身不接收点击，交给整行处理，避免"点框"与"点行"两套逻辑打架
                isClickable = false
                isFocusable = false
                row.addView(this)
            }
            box.isChecked = host.selected.contains(e.pkg)
            val text = row.getChildAt(1) as? TextView ?: TextView(host).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#111114"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(this)
            }
            text.text = "${e.name}\n${e.pkg}"
            // 整行可点：单击 = 勾选/取消，并且**立即写盘生效**，不需要再去点"确定"
            row.setOnClickListener {
                val now = !host.selected.contains(e.pkg)
                if (now) host.selected.add(e.pkg) else host.selected.remove(e.pkg)
                box.isChecked = now
                host.updateCount()
                host.commit()   // 即时保存（用户要求：点一下就生效）
            }
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自绘顶部栏（PickerTheme 是 NoActionBar），顶部放搜索框更省空间
        // 内容整体下移一个状态栏高度，避免标题被状态栏图标压住
        val topInset = statusBarHeight()

        selected.clear()
        selected.addAll(
            intent.getStringArrayListExtra(EXTRA_PKGS)
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        )
        // 记下初始快照，供「重置」使用；注意必须在 addAll 之后
        original.clear()
        original.addAll(selected)

        // 状态栏本身是 #F7F7F9（近白），若标题区也用同色，两者之间完全没有边界，
        // 圆角图标像是"浮"在标题上。这里把「状态栏下方的整个头部」做成一块蓝灰面板
        // （#DCE3F0）+ 底部 1px 分隔线，与下方纯白/浅灰列表形成清晰分层。
        // 同时头部顶边压一条 3dp 主题蓝，作为"这是模块自己的界面"的视觉锚点。
        val headerBg = Color.parseColor("#D6DEEE")
        val divider = Color.parseColor("#AEBBD4")
        val accent = Color.parseColor("#3B6EF5")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(0, topInset, 0, 0)
        }

        // 顶部主题色细条：即使状态栏图标与标题同色，也能一眼分清界面边界
        root.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // 头部容器：标题 + 搜索 + 工具行都挂在这块面板里，保证同色、同分隔线
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
        }

        // ── 顶部标题 ──
        header.addView(TextView(this).apply {
            text = "选择应用"
            textSize = 20f
            setTextColor(Color.parseColor("#111114"))
            setBackgroundColor(headerBg)
            setPadding(dp(16), dp(12), dp(16), dp(4))
        })

        // ── 搜索栏 ──
        query = EditText(this).apply {
            hint = "搜索应用名或包名"
            textSize = 14f
            setSingleLine()
            setTextColor(Color.parseColor("#111114"))
            setHintTextColor(Color.parseColor("#9A9AA0"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            // 打开页面时不自动弹键盘、不抢焦点，否则键盘盖住列表看不到内容
            isFocusableInTouchMode = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        // 搜索框左右留白并入头部面板，白框浮在浅蓝灰面板上，层次更明确
        val qWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        qWrap.addView(query, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(qWrap)

        // ── 工具行（两行两列，折叠态窄屏也不挤）：只显示已选 / 含系统应用 / 全选 / 清空 ──
        val toolsWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerBg)
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        fun toolBtn(label: String, onClick: (android.widget.Button) -> Unit) =
            Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#2A3346"))
                setBackgroundColor(Color.WHITE)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { onClick(this) }
            }
        val lp1x1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(3), dp(4), dp(3))
        }

        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowA.addView(toolBtn("只显示已选") { b ->
            onlySelected = !onlySelected
            b.text = if (onlySelected) "显示全部" else "只显示已选"
            applyFilter(query.text?.toString().orEmpty())
        }, lp1x1)
        rowA.addView(toolBtn("含系统应用") { b ->
            hideSystem = !hideSystem
            b.text = if (hideSystem) "含系统应用" else "仅用户应用"
            applyFilter(query.text?.toString().orEmpty())
        }, lp1x1)
        toolsWrap.addView(rowA)

        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(toolBtn("全选当前列表") {
            shown.forEach { selected.add(it.pkg) }
            adapter.notifyDataSetChanged()
            updateCount()
        }, lp1x1)
        rowB.addView(toolBtn("清空已选") {
            selected.clear()
            adapter.notifyDataSetChanged()
            updateCount()
        }, lp1x1)
        toolsWrap.addView(rowB)
        header.addView(toolsWrap)

        root.addView(header)

        // 头部与列表之间的 1px 分隔线（用 View 画，避免依赖 drawable 资源）
        root.addView(View(this).apply {
            setBackgroundColor(divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        countView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(countView)

        // ── 列表 ──
        listView = ListView(this).apply {
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = Adapter(this)
        listView.adapter = adapter
        root.addView(listView)

        // ── 底部：已完成（点行即保存，这里只是一个"我看完了"的出口）──
        root.addView(View(this).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        bar.addView(Button(this).apply {
            text = "重置为上次保存"
            textSize = 13f
            setOnClickListener {
                // 撤销本次所有改动（回到进入页面时的名单），并立即写回
                selected.clear()
                selected.addAll(original)
                adapter.notifyDataSetChanged()
                updateCount()
                commit()
                Toast.makeText(this@AppPickerActivity, "已还原并保存", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        bar.addView(Button(this).apply {
            text = "完成"
            textSize = 13f
            setOnClickListener { commit(); finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        root.addView(bar)

        setContentView(root)

        loadApps()
        applyFilter("")
    }

    /** 后台线程枚举已安装应用，避免主线程卡顿（几百个包名 getApplicationLabel 会明显掉帧）。 */
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val list = runCatching {
                pm.getInstalledApplications(0)
                    .asSequence()
                    // 只保留有启动入口的应用（可启动才有「前台应用」概念）
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map {
                        val label = runCatching { pm.getApplicationLabel(it).toString() }
                            .getOrDefault(it.packageName)
                        // FLAG_SYSTEM：预装/系统应用（如「设置」「系统界面」「电话」等）
                        // FLAG_UPDATED_SYSTEM_APP：被升级过的系统应用
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
        }.apply { isDaemon = true; name = "os4ffx-picker" }.start()
    }

    /** 过滤 + 排序展示；搜索时把「前缀命中」排在前面，便于快速定位。 */
    private fun applyFilter(q: String) {
        val key = q.trim().lowercase()
        var seq = all.asSequence()
        // 默认隐藏系统应用：用户配「悬浮」不会想给「系统界面」「设置」这类开悬浮，几百条里挑不出想要的
        if (hideSystem) seq = seq.filter { !it.isSystem }
        if (onlySelected) seq = seq.filter { selected.contains(it.pkg) }
        if (key.isNotEmpty()) {
            seq = seq.filter { it.name.lowercase().contains(key) || it.pkg.lowercase().contains(key) }
            val ordered = seq.sortedWith(
                compareBy(
                    { !it.name.lowercase().startsWith(key) && !it.pkg.lowercase().startsWith(key) },
                    { it.name.lowercase() }
                )
            ).toList()
            shown = ordered
        } else {
            shown = seq.toList()
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        val sysNote = if (hideSystem) "（已隐藏系统应用）" else "（含系统应用）"
        countView.text = "已选 ${selected.size} 个 · 当前显示 ${shown.size} 个 · 共 ${all.size} 个$sysNote"
    }

    /**
     * 返回键 = 保存并退出。
     *
     * 用户报告「修改不可控」的一个直接原因是：点行即已写盘，但如果他改用返回键退出，
     * 旧版实现不提交，下一次进来看到的是"自己没选过"的状态，感觉像配置被吞了。
     * 这里统一语义：**离开页面就是保存**（点行已经是即时保存，返回只是补一个出口）。
     * 想撤销用底部「重置为上次保存」。
     */
    @Deprecated("兼容旧 API；Android 13+ 推荐 OnBackInvokedCallback，但开关实现在 Activity，用这个更稳")
    override fun onBackPressed() {
        commit()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** 立即把当前勾选写回模块配置（点一下即生效，无需再点「确定」）。 */
    private fun commit() {
        onResult?.invoke(selected.toList().sorted())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 状态栏高度（px）。拿不到时给 28dp 兜底，避免标题被状态栏压住。 */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (h > 0) h else dp(28)
    }

    companion object {
        private const val EXTRA_PKGS = "pkgs"

        /** 跨 Activity 的回调（进程内静态引用；Activity 销毁置空即可） */
        private var onResult: ((List<String>) -> Unit)? = null

        fun open(ctx: Context, currentRaw: String, cb: (List<String>) -> Unit) {
            onResult = cb
            val cur = currentRaw.split('\n', ',', ' ', '\t')
                .map { it.trim() }.filter { it.isNotEmpty() }
            ctx.startActivity(Intent(ctx, AppPickerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PKGS, ArrayList(cur))
            })
        }
    }
}
