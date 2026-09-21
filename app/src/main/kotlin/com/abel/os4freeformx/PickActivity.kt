package com.abel.os4freeformx

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * 「加入分屏」应用选择器。
 *
 * 为什么放在模块 App 进程里：SystemUI 进程里**开不出窗口** ——
 * `PopupWindow` 用没附着窗口的 View 当锚点、`createWindowContext(TYPE_APPLICATION_OVERLAY)` 自建窗口，
 * 真机都是 `WindowManager$BadTokenException: token null is not valid`（本进程没有 overlay 授权）。
 * 交给模块 App 的 Activity 就有正常 window token，代价是多一条跨进程回传：
 * 选中结果写进 `PREFS_CFG` 的 [Constants.K_PICK]（含一次性 token），SystemUI 侧轮询 `StoreProvider` 取。
 */
class PickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 测试入口：am start -n com.abel.os4freeformx/.PickActivity --es test "pkg|taskId"
        // 直接把"加分屏"请求交给 SystemUI 侧执行（adb 造不出四指触控时用它验证系统路径）
        intent.getStringExtra("test")?.let { sel ->
            // 测试开关：`am start -n <pkg>/.PickActivity --es test "TESTHOOK:1" / "TESTHOOK:0"`
            // 打开后 SystemUI 侧 [Gestures.watchTestHook] 的轮询会在 1.5s 内生效，
            // **不需要重启 SystemUI**；关闭后轮询线程只 sleep，生产零开销。
            if (sel.startsWith("TESTHOOK")) {
                val on = sel.substringAfter(':', "1").trim() != "0"
                AppPrefs.putBoolean(this, Constants.K_TEST_HOOK, on)
                android.widget.Toast.makeText(
                    this, "测试钩子：${if (on) "开" else "关"}", android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }
            AppPrefs.putString(this, Constants.K_TEST_ADDSPLIT, sel)
            finish()
            return
        }
        val pkgs = intent.getStringArrayListExtra(EXTRA_PKGS) ?: arrayListOf()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        root.addView(TextView(this).apply {
            text = "加入分屏（当前 ${intent.getIntExtra(EXTRA_INDEX, 0)} 个）"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })
        val list = ListView(this)
        val pm = packageManager
        val labels = pkgs.map { sel ->
            val p = sel.substringBefore('|')
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ ->
            // 一次性 token + 选中包名写进共享配置，SystemUI 侧轮询取走
            AppPrefs.putString(this, Constants.K_PICK, "$token|${pkgs[pos]}")
            finish()
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    companion object {
        const val EXTRA_PKGS = "pkgs"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_INDEX = "index"
    }
}
