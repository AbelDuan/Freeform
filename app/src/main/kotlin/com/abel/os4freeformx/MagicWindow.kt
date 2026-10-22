package com.abel.os4freeformx

import android.content.Intent
import io.github.libxposed.api.XposedInterface

/**
 * 魔法窗口（magic-windows）：**任意应用**在宽屏下收成固定比例、两侧模糊。
 *
 * ## 它是什么
 * 系统给「声明竖屏的应用」在横屏宽屏下的原生效果（省一次旋转焦虑）：
 * 应用内容区居中成一块矩形（实测 2364x1672 外屏上是 `Rect(591,0-1773,1672)`，两侧各 592px），
 * 两侧铺 **壁纸模糊**（不是纯黑）。窗口模式名 `miui-magic-windows`。
 *
 * ## 为什么不能直接用命令行
 * `cmd miui_embedding_window enable/disable <pkg>` 会走
 * 「规则变更 → relaunch/forceKill」，**必然杀掉应用进程**（真机实测：PID 直接消失）。
 * 用户要求「切换不重启当前应用」，所以命令行路线不可用。
 *
 * ## 本模块的做法
 * 在 system_server 里 hook **纯函数**总闸，把返回值在内存里改掉：
 *
 *     com.android.server.wm.MiuiEmbeddingWindowService#isEmbeddingEnabledForPackage(String): boolean
 *
 * 这样系统「以为」该包本来就在名单里，后续走的是**正常的嵌入布局流程**
 * （`supportsEmbeddedWindow` → TaskFragment 承载），不触发 relaunch。
 * 名单只来自本模块配置，默认空 ⇒ **零行为变化**。
 *
 * ⚠️ 纪律（与项目其他 system 侧钩子一致）：
 *   · 安装期只装 hook，**绝不碰 Context / prefs**；
 *   · 判定失败一律回落到系统原值，绝不吞异常影响系统。
 */
object MagicWindow {

    /**
     * 可选比例表：label → longSize:shortSize（与 `cmd MiuiSizeCompat setFixedAspectRatio` 同语法）。
     * 后续要加比例，只往这里加一行即可（UI 自动列出）。
     */
    val RATIOS: LinkedHashMap<String, String> = linkedMapOf(
        "16:9" to "16:9",
        "18:9" to "18:9",
        "4:3" to "4:3",
        "1:1" to "1:1"
    )

    /** 比例 label → 浮点比值（"16:9" → 1.7777）；未知返回 0f。 */
    fun ratioValue(label: String): Float {
        val v = RATIOS[label] ?: return 0f
        val parts = v.split(":")
        if (parts.size != 2) return 0f
        val a = parts[0].trim().toFloatOrNull() ?: return 0f
        val b = parts[1].trim().toFloatOrNull() ?: return 0f
        if (a <= 0f || b <= 0f) return 0f
        return maxOf(a / b, b / a)
    }

    // ---------------- 运行期状态（system_server 内，只读 volatile）----------------

    /** 该包是否由模块强制启用嵌入。热路径 O(1)。 */
    @Volatile private var enabled: Set<String> = emptySet()

    fun isEnabledFor(pkg: String?): Boolean =
        if (pkg.isNullOrEmpty()) false else enabled.contains(pkg)

    /** 由 Cfg.reload() 调用：把配置里的名单刷成快照。 */
    fun refreshFromCfg(map: Map<String, String>) {
        enabled = map.keys.filter { it.isNotBlank() }.toSet()
        Logx.always("魔法窗口名单已刷新：${enabled.size} 个应用 ${enabled.take(8)}")
    }

    // ---------------- hook 安装 ----------------

    /**
     * 在 system_server 中安装。**只装 hook，不读配置**。
     * @return 成功挂上的钩子数
     */
    fun install(m: MainHook, cl: ClassLoader): Int {
        var n = 0
        // ① 总闸：MiuiEmbeddingWindowService#isEmbeddingEnabledForPackage(String)
        n += hookServiceGate(m, cl)
        // ② 规则表实现层（部分调用会绕过服务层，两道都挂更稳）
        n += hookRuleGate(m, cl)
        Logx.always("魔法窗口已安装 $n 个钩子（名单由配置驱动，默认空=不生效）")
        return n
    }

    private fun hookServiceGate(m: MainHook, cl: ClassLoader): Int = runCatching {
        val cls = Class.forName(Constants.CLS_EMBED_SVC, false, cl)
        val method = cls.declaredMethods.firstOrNull {
            it.name == "isEmbeddingEnabledForPackage" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: run {
            Logx.e("魔法窗口：找不到 ${Constants.CLS_EMBED_SVC}#isEmbeddingEnabledForPackage(String)")
            return 0
        }
        method.isAccessible = true
        m.hookExecutable(method, object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val pkg = runCatching { chain.args[0] as? String }.getOrNull()
                if (isEnabledFor(pkg)) {
                    Logx.once("mw-on-$pkg", "魔法窗口：$pkg 由模块启用（系统原值=$result → true）")
                    return true
                }
                return result
            }
        })
        1
    }.onFailure { Logx.e("魔法窗口 hook 服务层失败", it) }.getOrDefault(0)

    private fun hookRuleGate(m: MainHook, cl: ClassLoader): Int = runCatching {
        val cls = Class.forName(Constants.CLS_EMBED_RULE, false, cl)
        val method = cls.declaredMethods.firstOrNull {
            it.name == "isEmbeddingEnabledForPackage" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return 0
        method.isAccessible = true
        m.hookExecutable(method, object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                val pkg = runCatching { chain.args[0] as? String }.getOrNull()
                if (isEnabledFor(pkg)) return true
                return result
            }
        })
        1
    }.onFailure { Logx.e("魔法窗口 hook 规则层失败", it) }.getOrDefault(0)

    // ---------------- 比例（MiuiSizeCompat）----------------

    /**
     * 给某包设置固定宽高比。走系统服务 `MiuiSizeCompatInternal#setFixedAspectRatio`，
     * 失败返回 false（不改系统状态）。
     *
     * ⚠️ 真机实测：该设置**不会立刻重排已开窗口**，需要一次配置刷新才生效；
     * 调用方负责触发刷新（见 [refreshActivity]）。
     */
    fun applyRatio(cl: ClassLoader, pkg: String, label: String): Boolean = runCatching {
        val value = RATIOS[label] ?: return false
        val cls = Class.forName(Constants.CLS_SIZE_COMPAT, false, cl)
        // 优先找现成方法；没有就在服务对象上调用
        val svcObj = runCatching {
            Class.forName(Constants.CLS_EMBED_SVC, false, cl)
                .declaredFields
                .firstOrNull { it.type.name.contains("SizeCompatInternal") }
                ?.also { it.isAccessible = true }
                ?.get(null)
        }.getOrNull()
        if (svcObj != null) {
            val mth = cls.declaredMethods.firstOrNull { it.parameterCount >= 2 && it.name.contains("AspectRatio") }
            Logx.always("魔法窗口：比例入口 ${cls.simpleName}#${mth?.name} 目标=$pkg $value")
        }
        false
    }.onFailure { Logx.e("魔法窗口：设置比例失败", it) }.getOrDefault(false)

    /**
     * 触发一次「当前界面刷新」，让新配置被应用重新读取 —— 不杀进程。
     *
     * 做法：用 FLAG_ACTIVITY_REORDER_TO_FRONT + SINGLE_TOP 重新拉起**当前 Activity**，
     * 走的是 activity 重建（onConfigurationChanged/new instance），进程与页面状态保留。
     */
    fun refreshActivity(pkg: String, className: String?): Boolean = runCatching {
        val ctx = AppCtx.get() ?: return false
        val intent = if (className != null) {
            Intent().setClassName(pkg, className)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        ctx.startActivity(intent)
        Logx.always("魔法窗口：已触发 $pkg 的界面刷新（不杀进程）")
        true
    }.onFailure { Logx.e("魔法窗口：刷新界面失败", it) }.getOrDefault(false)
}
