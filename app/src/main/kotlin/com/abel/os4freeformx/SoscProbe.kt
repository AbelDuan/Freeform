package com.abel.os4freeformx

/**
 * 分屏/小窗手势判定探针（2026-11-01）。
 *
 * 目的：确认「按住任务条拖到左上/右上」这条链路上，
 * 究竟哪个类/哪个矩形在算判定位置。只打日志，不改任何行为。
 *
 * v2（22:30）：新增热区转储 —— 直接反射读 MultiTaskingHotAreaController
 * 的热区列表字段，把每个 HotArea 的 region / type 打印出来，
 * 一次就能看到「左上/右上」的分屏、小窗落点到底要求拖多远。
 */
object SoscProbe {

    private const val TAG = "SoscProbe"

    private val SOSC_CLASSES = listOf(
        "com.android.wm.shell.sosc.SoScSplitScreenController",
        "com.android.wm.shell.sosc.SoScStageCoordinator",
        "com.android.wm.shell.sosc.SoScSplitScreen",
        // ★ 2026-11-02：桌面通过 Binder 调这里通知 SystemUI 执行分屏，
        //   Bundle 里带着桌面算好的位置信息 —— 这是唯一能看到「判定结果」的地方。
        "com.android.wm.shell.multitasking.common.MultiTaskingStateManager",
        // ★ 真正的 Binder 实现类（Stub 的 onTransact 会转发到它），
        //   实测外层类 hookAll 挂不上 dockSplitFromRecent，必须单独挂内部类。
        "com.android.wm.shell.multitasking.common.MultiTaskingStateManager\$IMultiTaskingStateManagerImpl"
    )

    private const val HOTAREA_CLASS =
        "com.android.wm.shell.multitasking.common.cover.MultiTaskingHotAreaController"

    /** 拖动落点判定的真正策略类（2026-11-01 由 dex 方法表挖出）。 */
    private const val DRAGDROP_CLASS =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuidraganddrop.MiuiDragAndDropPolicy"

    /** 全面屏手势/无边界的「是否在热区内」判定。 */
    private const val INFINITY_CLASS =
        "com.android.wm.shell.multitasking.stubs.infinitymode.MiuiInfinityModeStub"

    private val DRAGDROP_METHODS = listOf(
        "getNewHotArea",
        "getNewHotAreaForMultiSplit",
        "getNewHotAreaForFullSplitHori",
        "handleActiveHotArea",
        "handleInactiveHotArea",
        "handleMultipleActiveHotArea",
        "handleMultipleInactiveHotArea",
        "applyMiddleDividerSticky"
    )

    private val INFINITY_METHODS = listOf("isWithinHotAreaRegion")

    private val SOSC_METHODS = listOf(
        "getSplitLeftUnableCoordinate",
        "getSplitRightUnableCoordinate",
        "getSplitEnterWay",
        "getSplitPosition",
        "getSoScSplitInfo",
        "getSmallWindowRatio",
        "onLayoutSizeChanged",
        "startSplitScreen",
        "startSmallWindow",
        "splitPrimaryTask",
        // ── 真正「进入分屏」的入口：打调用栈就能看到判定方是谁 ──
        "enterSplitScreen",
        "finishEnterSplitScreen",
        "activateSplit",
        "deactivateSplit",
        "getActivateSplitPosition",
        "isPositionAtTopOrLeft",
        "getStageBounds",
        "getRefStageBounds",
        "getSideStageBounds",
        "getMainStageBounds",
        // ── 拖拽落点判定的上游（2026-11-02 由调用栈追出）──
        "prepareDragDropTaskToSoSc",
        "prepareDragDropIntentToSoSc",
        "prepareDragDropTaskToSoScForTopBottom",
        "prepareEnterSplitScreen",
        "prepareEnterSoSc",
        "prepareActiveSplit",
        "prepareBringSplit",
        // ── 手势阶段的 stage 记录/判定（2026-11-02）──
        "recordStageOnGestureStart",
        "onGestureStart",
        "onGestureEnd",
        "onRecentsGestureEnd",
        "startSingleTaskByGesture",
        "startSingleSplitTask",
        "isGestureNotTouchFullRange",
        "isGestureNotCloseSideTask",
        "isGestureSingleCloseRootTask",
        "setDivideRatioOnGestureEnd"
    )

    /** 这些方法命中时额外打印调用栈，用来定位「谁在判定落点」。 */
    private val STACK_METHODS = setOf(
        "enterSplitScreen",
        "finishEnterSplitScreen",
        "activateSplit",
        "startSplitScreen",
        "getActivateSplitPosition",
        "isPositionAtTopOrLeft",
        "prepareDragDropTaskToSoSc",
        "prepareDragDropIntentToSoSc",
        "prepareDragDropTaskToSoScForTopBottom",
        "prepareEnterSplitScreen",
        "prepareActiveSplit",
        "recordStageOnGestureStart",
        "startSingleTaskByGesture",
        // ★ 2026-11-02：桌面 Binder 入口，Bundle 里带落点信息
        "dockSplitFromRecent",
        "startMultipleSplits"
    )

    private val HOTAREA_METHODS = listOf(
        "getHotAreaAtPosition",
        "getHotAreaForDrag",
        "getHotAreaTypeForDrag",
        "getPhoneHotArea",
        "getFoldHotArea",
        "getPadHotArea",
        "getHotArea",
        "findHotArea",
        "initHotAreaForTaskDrag",
        "initPhoneHotAreaList",
        "initFoldHotAreaList",
        "initPadHotAreaList",
        "updateHotAreaList",
        "onSplitBoundsChanged",
        "onSplitBoundsChanging",
        // ── MultipleSplit（多分屏）专用热区：本次真正命中的链路 ──
        "getMultipleSplitHotArea",
        "getMultipleSplitHotAreaAtPosition",
        "getMultipleSplitHotAreaForDrag",
        "getMultipleSplitPageHotAreaList",
        "getMultipleSplitPreInsertHotAreaList",
        "getMultipleSplitPreReplaceHotAreaList",
        "getMultipleSplitOriginalHotAreaList",
        "getMultipleSplitPageHotAreaIndex",
        "getMultipleSplitQuickViewModeHotAreaIndex",
        "initMultipleSplitHotAreaForDrag",
        "initHotAreaForMultipleSplitQuickViewMode",
        "getHotAreaInQuickViewMode",
        "clearTwoSplitInsetHotAreaLists"
    )

    /** 每个方法最多打印多少条，防止高频方法刷屏。 */
    private const val MAX_LOG_PER_METHOD = 120
    private val methodCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 热区列表字段（来自 dex 字符串表，全部是 List<HotArea>）。 */
    private val HOTAREA_FIELDS = listOf(
        "mFoldSplitHotAreaList",
        "mFoldFullHotAreaList",
        "mFoldFreeFormHotAreaList",
        "mFoldQuickSwitchLeftHotAreaList",
        "mFoldQuickSwitchRightHotAreaList",
        "mFoldSplitBottomHotAreaList",
        "mPadSplitHotAreaList",
        "mPadFullHotAreaList",
        "mPadFreeFormHotAreaList",
        "mPadQuickSwitchSplitLeftHotAreaList",
        "mPadQuickSwitchSplitRightHotAreaList",
        "mPadSplitBottomHotAreaList",
        "mDragTopBottomSplitFreeFormHotAreaList",
        // ── MultipleSplit（多分屏）热区列表 ──
        "mMultipleSplitHotAreaList",
        "mMultipleSplitOriginalHotAreaList",
        "mMultipleSplitQuickViewModeHotAreaList",
        "mMultipleSplitPreInsertHotAreaList",
        "mMultipleSplitPreReplaceHotAreaList",
        "mMultipleSplitPageHotAreaList",
        "mPhoneMultipleSplitHotAreaList",
        "mFoldMultipleSplitHotAreaList"
    )

    /** 整个进程只转储一次，避免刷屏。 */
    @Volatile private var dumped = false

    fun install(m: MainHook, cl: ClassLoader) {
        var ok = 0
        for (cn in SOSC_CLASSES) ok += hookAll(m, cl, cn, SOSC_METHODS)
        ok += hookAll(m, cl, HOTAREA_CLASS, HOTAREA_METHODS)
        ok += hookAll(m, cl, DRAGDROP_CLASS, DRAGDROP_METHODS)
        ok += hookAll(m, cl, INFINITY_CLASS, INFINITY_METHODS)
        Logx.always("$TAG: 安装完成，共挂上 $ok 个方法")
    }

    private fun hookAll(m: MainHook, cl: ClassLoader, className: String, methods: List<String>): Int {
        val cls = runCatching { Class.forName(className, false, cl) }.getOrNull()
        if (cls == null) {
            Logx.always("$TAG: 类不存在 $className")
            return 0
        }
        val simple = cls.simpleName
        var n = 0
        for (mt in cls.declaredMethods) {
            if (mt.name !in methods) continue
            val ps = mt.parameterTypes
            val r = runCatching {
                m.hookMethod(cl, className, mt.name, ps) { chain ->
                    runCatching {
                        SplitTest.updateLoader(chain.thisObject?.javaClass?.classLoader)
                    }
                    if (mt.name == "onGestureStart") {
                        runCatching { SplitTest.onGesture(chain.thisObject) }
                    }
                    val n = methodCount.merge(mt.name, 1, Int::plus) ?: 0
                    val log = n <= MAX_LOG_PER_METHOD
                    val args = runCatching { chain.args }.getOrNull()
                    if (log) {
                        val desc = args?.joinToString(",") { fmt(it) } ?: "?"
                        Logx.v("$TAG[$simple#${mt.name}] args=($desc)")
                        if (mt.name in STACK_METHODS) {
                            Logx.v("$TAG[$simple#${mt.name}] 调用栈: " + stackOf())
                        }
                    }
                    val ret = chain.proceed()
                    if (log) Logx.v("$TAG[$simple#${mt.name}] ret=${fmt(ret)}")
                    if (className == HOTAREA_CLASS) {
                        runCatching { dumpArgLists(mt.name, args) }
                        runCatching { dumpHotAreas(chain.thisObject) }
                    }
                    ret
                }
            }
            if (r.isSuccess) n++ else Logx.e("$TAG: 挂载失败 $className#${mt.name}", r.exceptionOrNull())
        }
        return n
    }

    /** 每个方法最多打这么多条，避免刷屏。 */
    private val argDumpCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 转储方法参数里的列表（热区很可能就藏在 onSplitBoundsChanged 的 ArrayList 参数里）。 */
    private fun dumpArgLists(method: String, args: List<Any?>?) {
        if (args == null) return
        val n = argDumpCount.merge(method, 1, Int::plus) ?: 0
        if (n > 6) return
        for (i in args.indices) {
            val items: List<Any> = when (val a = args[i]) {
                is Iterable<*> -> a.filterNotNull()
                is Array<*> -> a.filterNotNull()
                else -> continue
            }
            if (items.isEmpty()) continue
            Logx.v("$TAG[$method] 参数[$i] 列表共 ${items.size} 项（第 $n 次）")
            for (it in items.take(16)) {
                runCatching { Logx.v("$TAG[$method]   " + describe(it)) }
            }
        }
    }

    /** 把一个对象展开成一行：优先用它自己的 toString（HotArea 自带 toString），失败再反射。 */
    private fun describe(o: Any?): String {
        if (o == null) return "null"
        if (o is android.graphics.Rect) return fmtRegion(o)
        val own = runCatching { o.toString() }.getOrNull()
        if (!own.isNullOrBlank() && !own.contains("@")) {
            return if (own.length > 400) own.substring(0, 400) + "…" else own
        }
        return runCatching {
            val cls = o.javaClass
            val sb = StringBuilder(cls.simpleName).append('{')
            for (f in cls.declaredFields) {
                if (f.isSynthetic) continue
                val v = runCatching { f.apply { isAccessible = true }.get(o) }.getOrNull()
                sb.append(f.name).append('=')
                sb.append(when (v) {
                    null -> "null"
                    is android.graphics.Rect -> fmtRegion(v)
                    is Number, is Boolean, is String -> v.toString()
                    else -> v.javaClass.simpleName
                })
                sb.append("; ")
            }
            sb.append('}').toString()
        }.getOrElse { "ERR:" + it.javaClass.simpleName + ":" + it.message }
    }

    /** 把 controller 上所有热区列表的「类型 + 矩形」打出来；只在区域就绪后转储一次。 */
    private fun dumpHotAreas(owner: Any?) {
        if (owner == null || dumped) return
        // 先把各项读出来，全部是空矩形就说明热区还没算，等下一次
        var ready = false
        val groups = ArrayList<Pair<String, List<Any>>>()
        for (fn in HOTAREA_FIELDS) {
            val v = runCatching {
                owner.javaClass.getDeclaredField(fn).apply { isAccessible = true }.get(owner)
            }.getOrNull() ?: continue
            val items: List<Any> = when (v) {
                is Iterable<*> -> v.filterNotNull()
                is Array<*> -> v.filterNotNull()
                else -> listOf(v)
            }
            if (items.isEmpty()) continue
            groups.add(fn to items)
            for (it in items) {
                val region = runCatching {
                    it.javaClass.getDeclaredField("hotAreaRegion").apply { isAccessible = true }.get(it)
                }.getOrNull()
                if (region !is android.graphics.Rect || region.width() > 0 || region.height() > 0) ready = true
            }
        }
        if (!ready) return
        dumped = true
        Logx.v("$TAG[热区] ===== 开始转储 ${owner.javaClass.simpleName} =====")
        Logx.v("$TAG[热区] controller 字段: " + owner.javaClass.declaredFields.joinToString("; ") { f ->
            val v = runCatching { f.apply { isAccessible = true }.get(owner) }.getOrNull()
            "${f.name}(${f.type.simpleName})=" + (if (v is Collection<*>) "list[${v.size}]" else fmt(v))
        })
        var total = 0
        for ((fn, items) in groups) {
            total += items.size
            Logx.v("$TAG[热区] $fn 共 ${items.size} 项")
            for (it in items) {
                val fields = it.javaClass.declaredFields.joinToString("; ") { f ->
                    val v = runCatching { f.apply { isAccessible = true }.get(it) }.getOrNull()
                    "${f.name}=${if (v is android.graphics.Rect) fmtRegion(v) else fmt(v)}"
                }
                Logx.v("$TAG[热区]   ${it.javaClass.simpleName}{$fields}")
            }
        }
        // 常量表：HotArea 类的 static 字段（类型编号 → 名字）
        runCatching {
            val cls = Class.forName(HOTAREA_CLASS + "$" + "HotArea", false, owner.javaClass.classLoader)
                ?: return@runCatching
            Logx.v("$TAG[热区] HotArea 字段声明: " + cls.declaredFields.joinToString("; ") { "${it.name}:${it.type.simpleName}" })
        }
        runCatching {
            val cls = Class.forName(HOTAREA_CLASS + "$" + "HotAreaType", false, owner.javaClass.classLoader)
                ?: return@runCatching
            Logx.v("$TAG[热区] HotAreaType 常量: " + cls.declaredFields.joinToString("; ") { "${it.name}=${runCatching { it.get(null) }.getOrNull()}" })
        }
        Logx.v("$TAG[热区] ===== 转储结束，共 $total 项 =====")
    }

    /** 打印调用栈，用来定位「谁在判定落点」。 */
    private fun stackOf(): String = runCatching {
        Throwable().stackTrace.drop(1).take(14).joinToString(" <- ") {
            it.className.substringAfterLast('.') + "#" + it.methodName + ":" + it.lineNumber
        }
    }.getOrElse { "ERR" }

    private fun fmtRegion(v: Any?): String = when (v) {
        null -> "null"
        is android.graphics.Rect -> "Rect(${v.left},${v.top},${v.right},${v.bottom})"
        is android.graphics.Region -> "Region(${v.bounds.left},${v.bounds.top},${v.bounds.right},${v.bounds.bottom})"
        else -> v.toString()
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is Int, is Long, is Boolean, is Float, is Double, is String -> v.toString()
        is android.graphics.Rect -> "Rect(${v.left},${v.top},${v.right},${v.bottom})"
        is android.graphics.Region -> fmtRegion(v)
        // ★ Bundle 是桌面判定结果的载体，必须完整展开（toString 会 unparcel）
        is android.os.Bundle -> runCatching {
            v.keySet().joinToString(",") { "$it=${v.get(it)}" }
        }.getOrElse { "Bundle(展开失败)" }
        else -> runCatching { v.toString() }.getOrNull()
            ?.let { if (it.length > 300) it.substring(0, 300) + "…" else it }
            ?: v.javaClass.simpleName
    }
}
