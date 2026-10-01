package com.abel.os4freeformx

import java.io.File
import java.util.Base64

/**
 * 沉浸导航栏（NBI）—— **走 HyperOS 自带的名单，不 hook SystemUI**。
 *
 * 本机实测（2026-09-30，Xiaomi 18 Fold / HyperOS 4）：
 *  - 系统名单：`/data/system/cloudFeature_navigation_bar_immersive_rules_list.json`
 *    （`system:system 0644`，`dataVersion` 钉死可防云端覆盖）。名单里的应用，系统**不再为它
 *    预留底部导航栏区域/底色**，而**手势横条本身保留** —— 正是"消除底部横条、保留手势条"。
 *  - 该文件是**持久载体**：框架（system_server）启动时读取；运行中改写不会重新加载
 *    （`reload-rule` 在 `cmd` 下不被识别，重启 SystemUI 也刷不动它）。
 *  - `cmd miui_navigation_bar_immersive enable/disable <包名>` 是**实时态**，服务自述
 *    "restart the application to take effect" —— 目标应用重启后即可见到效果。
 *
 * 所以这里做两件事：① 改写名单文件（跨系统重启持久）；② 逐个包调 enable/disable（尽快生效）。
 * 写 `/data/system` 与调 `cmd` 都需要 root，统一走 `su -c`（LSPosed 用户必然有 root）。
 *
 * 纯逻辑（[mergeRules]/[buildJson]/[extractKeys]/[parseSet]/[join]）不依赖 Android，
 * 可在主机侧用 `./check.sh` 跑自检；[apply] 是本进程外的副作用，只能真机验证。
 */
object Nbi {

    const val PATH = "/data/system/cloudFeature_navigation_bar_immersive_rules_list.json"
    const val SERVICE = "miui_navigation_bar_immersive"

    /** 钉死版本号：本机观察到的"自定配置"写法，避免云端把我们的条目覆盖掉。 */
    private const val VERSION = "999999"


    /**
     * 两种规则（反编译取证 + 真机实测）：
     *
     *   RULE_HIDE   = { "mode": 2 }  → setNavigationBarForceImmersive()
     *       **整个底栏消失**：应用内容铺到屏幕最底、不再为导航栏预留区域。
     *       与小鹏当初那条厂商规则完全一致（用户已确认"整个底栏没了"就是想要的效果）。
     *
     *   RULE_SAMPLE = { "mode": 1 }  → updateNavigationBarColor(false, -1)
     *       不带 color ⇒ 走 getNavZoneDominantColor() **采样界面主色**，导航栏跟随当前界面。
     *       （mode:1 才会读 color；带上 color:0 只是"底色透明"，底栏区域仍然占位 —— 那不是"消失"。）
     *
     * mode 是策略选择器（com.android.internal.policy.NavigationBarImmersiveController
     * .handleActivityImmersive）：0=DISABLED · 1=用自定义颜色 · 2=强制沉浸布局。
     */
    const val RULE_HIDE = "{ \"enable\": true, \"activityRules\": { \"*\": { \"mode\": 2 } } }"
    const val RULE_SAMPLE = "{ \"enable\": true, \"activityRules\": { \"*\": { \"mode\": 1 } } }"

    class Result(val ok: Boolean, val msg: String)

    /** `"a, b ,,c"` → `{"a","b","c"}`；容忍空白与空项。 */
    fun parseSet(s: String?): Set<String> =
        s?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSortedSet()
            ?: emptySet()

    /** 稳定序列化（排序），保证幂等与可比较。 */
    fun join(set: Set<String>): String = set.filter { it.isNotEmpty() }.sorted().joinToString(",")

    /**
     * 名单合并（按【规则值】而不是只按包名）：
     *   保留外部条目 → 加入/更新我们这次要写的 → 只移除"上次由我们写入、这次取消了勾选"的。
     * 传 Map 是为了支持"同一个应用从取色改成隐藏"（规则体必须被替换）。
     */
    fun mergeRules(
        existing: Map<String, String>,
        applied: Set<String>,
        desired: Map<String, String>
    ): Map<String, String> = (existing - applied) + desired

    /** 包名字符集判定（收窄匹配面，避免把 JSON 里的字段名当成包名）。 */
    private fun isPkg(s: String): Boolean {
        if (s.isEmpty() || s.length > 200) return false
        var dot = false
        for (c in s) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' -> {}
                c == '.' -> dot = true
                else -> return false
            }
        }
        return dot
    }

    /** 生成名单文件内容。 */
    fun buildJson(desired: Map<String, String>): String {
        val sorted = desired.keys.filter { isPkg(it) }.sorted()
        val rules = sorted.joinToString(",\n") { "    \"$it\": ${desired[it]}" }
        return buildString {
            append("{\n")
            append("  \"dataVersion\": \"").append(VERSION).append("\",\n")
            append("  \"modules\": \"navigation_bar_immersive_application_config_new\",\n")
            append("  \"modifyApps\": \"modifyApps\",\n")
            append("  \"NBIRules\": {\n")
            append(rules)
            if (sorted.isNotEmpty()) append("\n")
            append("  }\n}\n")
        }
    }

    /**
     * 从名单文件里取回包名。
     *
     * 只认 `NBIRules` 对象**第一层**的键、且该键后面紧跟一个对象 —— 这样
     * `modules`/`dataVersion` 这类顶层键、以及 `enable`/`activityRules`/`mode` 这些
     * 规则体内部的字段都不会被误当成包名。手写扫描是为了让本文件不依赖 Android
     * （`org.json` 在主机侧 classpath 里没有，会让 `./check.sh` 编不过）。
     */
    fun extractRules(json: String): Map<String, String> {
        val at = json.indexOf("\"NBIRules\"")
        if (at < 0) return emptyMap()
        var i = json.indexOf('{', at)
        if (i < 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var depth = 0
        var pkg: String? = null
        var valueStart = -1
        while (i < json.length) {
            when (json[i]) {
                '{' -> {
                    depth++
                    if (depth == 2 && pkg != null) valueStart = i
                }
                '}' -> {
                    if (depth == 2 && pkg != null && valueStart >= 0) {
                        out[pkg!!] = json.substring(valueStart, i + 1)
                        pkg = null; valueStart = -1
                    }
                    depth--
                    if (depth == 0) break
                }
                '"' -> {
                    val end = json.indexOf('"', i + 1)
                    if (end < 0) break
                    if (depth == 1 && pkg == null) {
                        val name = json.substring(i + 1, end)
                        var j = end + 1
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j < json.length && json[j] == ':' && isPkg(name)) pkg = name
                    }
                    i = end
                }
            }
            i++
        }
        return out
    }

    /** 只要包名（内部/测试用）。 */
    fun extractKeys(json: String): Set<String> = extractRules(json).keys

    /**
     * 拼出真正交给 `su -c` 执行的脚本；单元测试覆盖，避免"拼错了却没人发现"。
     *
     * 内容用 base64 过管道写文件：JSON 里有引号/换行，直接 printf 会被 shell 拆掉；
     * 也不用临时文件，省掉"root 能否读应用私有目录"的 SELinux 不确定性。
     */
    /**
     * 让目标应用重启的脚本（NBI 服务明确要求"restart the application to take effect"）。
     * `am force-stop` 比杀进程干净：下一次启动就会重新读取规则。由模块用 root 代劳，
     * 用户点一次「应用」即可见效，不必自己手动重启每个应用。
     */
    internal fun buildRestartScript(pkgs: List<String>): String =
        pkgs.filter { it.isNotBlank() }.distinct().sorted()
            .joinToString("; ") { "am force-stop $it" }

    fun restartApps(pkgs: List<String>): Boolean = runCatching {
        val script = buildRestartScript(pkgs)
        if (script.isEmpty()) return@runCatching true
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor() == 0
    }.getOrDefault(false)

    internal fun buildScript(b64: String, desired: List<String>, removed: List<String>): String = buildString {
        append("echo '").append(b64).append("' | base64 -d > '").append(PATH).append("' && ")
        append("chown system:system '").append(PATH).append("' && chmod 0644 '").append(PATH).append("' && ")
        // ★ 关键一步：让 system_server 重新读取名单文件并应用。
        // 没有它，文件写了也只是躺在磁盘上 —— 实测（2026-10-01）：缺这一步时"加入白名单"完全不生效，
        // 必须重启框架；加上之后，一条 update 即生效（无需任何重启）。
        // 取证：MiuiNBIManagerService$Shell.onCommand 的 "update" 分支 → mService.applyNewNBIConfig()
        append("cmd ").append(SERVICE).append(" update; ")
        desired.sorted().forEach { append("cmd ").append(SERVICE).append(" enable ").append(it).append("; ") }
        removed.sorted().forEach { append("cmd ").append(SERVICE).append(" disable ").append(it).append("; ") }
        append("echo NBI_OK")
    }

    /**
     * 落地：读回现有名单 → 合并 → 用 `su -c` 写回并逐个 enable/disable。
     */
    fun apply(desired: Map<String, String>, applied: Set<String>): Result {
        val existing = runCatching { File(PATH).readText() }.getOrNull()
        val rules = mergeRules(
            existing = if (existing == null) emptyMap() else extractRules(existing),
            applied = applied,
            desired = desired
        )
        val b64 = Base64.getEncoder().encodeToString(buildJson(rules).toByteArray(Charsets.UTF_8))
        val script = buildScript(b64, desired.keys.toList(), (applied - desired.keys).toList())
        return runCatching {
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            if (rc == 0 && out.contains("NBI_OK")) Result(true, "ok")
            else Result(false, "rc=$rc ${out.trim().take(300)}")
        }.getOrElse { Result(false, it.toString()) }
    }
}
