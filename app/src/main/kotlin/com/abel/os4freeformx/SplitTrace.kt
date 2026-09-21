package com.abel.os4freeformx

import android.app.PendingIntent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 诊断探针：把所有"分屏 / 多分屏"入口方法原样打出来（方法名 + 参数值）。
 *
 * 目的：抓**真实手势**（双分屏 → 拖出三分屏 / 四分屏）究竟调用了哪个入口、参数是什么，
 * 然后照抄那个入口去调用，而不是自己拼 `startMultipleSplits`（那条产出的窗口输入通道是死的）。
 *
 * 触发频率极低（只在用户真的操作分屏时命中），常开无压力。
 * 日志直接走 `android.util.Log`（tag=OS4FreeFromX），避开 Logx 的 400 行上限。
 */
object SplitTrace {

    private const val T = Constants.TAG

    /** MIUI 多窗切换：拖图标进分屏 / 全屏转分屏 / 各种 start* 入口都在这。 */
    private const val CLS_MULWIN_TRANS =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchTransition"

    /** 多分屏（三分屏起）控制器：插入 / 快速视图 / SoSc 转多分屏。 */
    private const val CLS_MULTIPLE_CTL =
        "com.android.wm.shell.multiplesplit.MultipleSplitController"

    /** 多分屏根任务组织者：真正建立 stage 的地方。 */
    private const val CLS_MULTIPLE_ORG =
        "com.android.wm.shell.multiplesplit.MultipleSplitRootTaskOrganizer"

    /** 热区控制器：手势落在哪个热区（左上 / 中间 / 右侧…）。 */
    private const val CLS_HOTAREA =
        "com.android.wm.shell.multitasking.common.cover.MultiTaskingHotAreaController"

    /** 折叠屏 SoSc 分屏协调者（双分屏本体）。 */
    private const val CLS_SOSC_COORD =
        "com.android.wm.shell.sosc.SoScStageCoordinator"

    private val MULWIN_KEYS = listOf(
        "startIconInsetSplit", "startIconInsetMultipleSplit", "startIconReplaceMultipleSplit",
        "startIconDragSplitScreen", "startIconDragFullscreen", "startIconDragFreeform",
        "startTransitionForMultiSplit", "openWindowFromFullscreen", "openWindowFromSingleOpen",
        "startFreeformToSplit", "startSplitToFull", "startFreeformReplaceSplit", "startSplitSwap",
        "startFreeformSqueezeOrFillSplit", "startToFreeform",
    )

    private val MULTIPLE_KEYS = listOf(
        "prepareDragIntentToMultipleSplit", "insertMultipleSplitByIntent", "insertMultipleSplitByTask",
        "enterQuickViewMode", "exitQuickViewMode", "finishEnterMultipleSplit",
        "transferSoScToMultipleSplit", "restoreMultipleSplitToSoSc",
        "startMultipleSplits", "startPendingIntents", "startTasks", "startTask", "startIntent",
        "updateMultipleSplitFocusStage", "replaceMultipleSplitByIntent", "replaceMultipleSplitByTask",
        "reorderMultipleSplitStages", "getStageBoundsInQuickViewMode", "getActiveStageList",
        "dockMultipleSplitTasks", "moveOffscreenStageToScreen", "removeMultipleSplit",
    )

    private val ORG_KEYS = listOf(
        "onTaskAppeared", "onTaskVanished", "onSplitRootTaskAppeared", "prepareEnterMultipleSplit",
        "enterMultipleSplit", "transferSoScToMultipleSplit", "createStageRoot", "createRootTask",
        "onStageRootTaskAppeared", "insertTask", "addTask", "startMultiSplit",
    )

    private val HOTAREA_KEYS = listOf(
        "handleHotArea", "hotArea", "getHotAreaType", "updateHotArea", "notifyHotArea",
        "onHotArea", "setHotArea", "calculateHotArea", "hitHotArea",
    )

    /** SoSc 是热类（475 个方法），只挂"分屏/插入/多分屏/阶段"相关的窄集合。 */
    private val SOSC_KEYS = listOf(
        "Inset", "Multiple", "Transfer", "Reparent", "AddStage", "RemoveStage",
        "CreateStage", "Snap", "Divider", "Dismiss", "Fling", "StageRoot",
    )

    fun install(m: MainHook, cl: ClassLoader) {
        var total = 0
        total += trace(m, cl, CLS_MULWIN_TRANS, MULWIN_KEYS, "MulWinTrans")
        total += trace(m, cl, CLS_MULTIPLE_CTL, MULTIPLE_KEYS, "MultiCtl")
        total += trace(m, cl, CLS_MULTIPLE_ORG, ORG_KEYS, "MultiOrg")
        total += traceFiltered(m, cl, CLS_HOTAREA, HOTAREA_KEYS, "HotArea")
        total += traceFiltered(m, cl, CLS_SOSC_COORD, SOSC_KEYS, "SoScCoord")
        Log.i(T, "SplitTrace: 共挂 $total 个探针")
    }

    /** 按方法名精确列表挂。 */
    private fun trace(m: MainHook, cl: ClassLoader, cls: String, keys: List<String>, tag: String): Int {
        var n = 0
        runCatching {
            val c = Class.forName(cls, false, cl)
            for (mm in c.declaredMethods) {
                if (mm.name !in keys) continue
                n += hookOne(m, mm, tag)
            }
            Log.i(T, "SplitTrace[$tag]: 命中 $n 个方法")
        }.onFailure { Log.e(T, "SplitTrace[$tag] 安装失败", it) }
        return n
    }

    /** 按关键字模糊匹配（用于方法名不确定的类）。 */
    private fun traceFiltered(
        m: MainHook, cl: ClassLoader, cls: String, keys: List<String>?, tag: String
    ): Int {
        var n = 0
        runCatching {
            val c = Class.forName(cls, false, cl)
            for (mm in c.declaredMethods) {
                if (!isPlain(mm)) continue
                if (keys != null && keys.none { mm.name.contains(it, ignoreCase = true) }) continue
                n += hookOne(m, mm, tag)
            }
            Log.i(T, "SplitTrace[$tag]: 命中 $n 个方法")
        }.onFailure { Log.e(T, "SplitTrace[$tag] 安装失败", it) }
        return n
    }

    private fun isPlain(mm: java.lang.reflect.Method): Boolean {
        val n = mm.name
        // 合成/桥接方法（`-$$Nest$xxx`、`$r8$lambda$xxx`）不挂：`$` 在字符串模板里要转义
        if (n.startsWith("-\$\$Nest\$") || n.startsWith("\$r8\$lambda")) return false
        if (n.startsWith("access\$") || n == "<clinit>" || n == "<init>") return false
        if (mm.isSynthetic) return false
        return true
    }

    private fun hookOne(m: MainHook, mm: java.lang.reflect.Method, tag: String): Int {
        if (!isPlain(mm)) return 0
        val ok = m.hookExecutable(mm, XposedInterface.Hooker { chain ->
            val line = runCatching {
                val sb = StringBuilder("TRACE $tag#${mm.name}(")
                for (i in 0 until chain.args.size) {
                    if (i > 0) sb.append(", ")
                    sb.append(fmt(chain.getArg(i)))
                }
                sb.append(')')
                sb.toString()
            }.getOrElse { "TRACE $tag#${mm.name}(<args err: $it>)" }
            Log.i(T, line)
            chain.proceed()
        })
        return if (ok) 1 else 0
    }

    /** 参数美化：只打印真正有用的信息（包名 / taskId / 尺寸 / 热区码）。 */
    private fun fmt(o: Any?): String = when (o) {
        null -> "null"
        is Int, is Long, is Boolean, is Float, is Double -> o.toString()
        is String -> "\"$o\""
        is Bundle -> bundle(o)
        is PendingIntent -> "PI(pkg=${runCatching { o.creatorPackage }.getOrNull()})"
        is Rect -> "Rect(${o.left},${o.top},${o.right},${o.bottom})"
        is IntArray -> o.joinToString(",", "[", "]")
        is kotlin.collections.List<*> -> o.joinToString(", ", "[", "]") { fmt(it) }
        else -> runCatching { o.toString() }.getOrDefault(o.javaClass.simpleName).let {
            if (it.length > 110) it.take(110) + "…" else it
        }
    }

    private fun bundle(b: Bundle): String = runCatching {
        val sb = StringBuilder("{")
        var first = true
        for (k in b.keySet()) {
            if (!first) sb.append(", ")
            first = false
            sb.append(k).append('=').append(runCatching { b.get(k).toString() }.getOrNull())
        }
        sb.append('}')
        if (sb.length > 160) sb.take(160).toString() + "…}" else sb.toString()
    }.getOrDefault("Bundle(?)")
}
