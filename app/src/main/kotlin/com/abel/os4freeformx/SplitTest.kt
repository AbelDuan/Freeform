package com.abel.os4freeformx

/**
 * 「分屏 / 小窗」主动触发通道（SystemUI 侧）。
 *
 * 背景（2026-11-02 实测结论）：
 *   落点判定在桌面 Flutter（com.miui.home）里完成，它通过 Binder 调
 *   `IMultiTaskingStateManager.dockSplitFromRecent(Bundle)` 让 SystemUI 执行；
 *   桌面是 HyperOS Runtime（无 dex），LSPosed 注入不了 ⇒ 只能在 SystemUI 侧自己判、自己调。
 *
 * 走的是系统原生入口：
 *   SoScUtilsImpl.getInstance().enterSplitScreen(RunningTaskInfo, WindowContainerTransaction, boolean)
 * 与系统自己 `startSingleTaskByGesture` → `prepareDragDropTaskToSoSc` 的实现同源。
 *
 * 调试开关（仅调试）：
 *   setprop debug.os4ffx.split_test 1
 *   setprop debug.os4ffx.split_left 1   # 1=分屏 0=小窗
 */
object SplitTest {
    private const val TAG = "SplitTest"
    private const val PROP_KEY = "debug.os4ffx.split_test"
    private const val PROP_LEFT = "debug.os4ffx.split_left"

    /** 手势按下瞬间的前台任务（松手时前台可能已变成桌面，必须提前记）。 */
    @Volatile private var pendingTask: Any? = null

    /**
     * SystemUI 进程的 ClassLoader。
     * 必须用它加载 com.android.wm.shell.* —— 这些类在 SystemUI 的 APK/共享库里，
     * 模块自己的 ClassLoader 看不到（实测 ClassNotFoundException）。
     */
    @Volatile private var uiLoader: ClassLoader? = null

    /**
     * 手势按下时调用。
     * 注意：这里**不能**查询任务列表 —— getTasks 是 Binder 调用，在输入线程里执行会
     * 阻塞事件处理，实测直接把手势事件丢掉（只收到 23px 位移）。
     * 真正的任务在松手后由 [dyeingTask] 决定。
     */
    fun rememberFrontTask() {
        pendingTask = null
    }

    /** hook 到 SoSc 类时用它的真实 loader 覆盖（WMShell 可能不在 SystemUI 主 loader 里）。 */
    fun updateLoader(cl: ClassLoader?) {
        if (cl != null && cl !== uiLoader) {
            uiLoader = cl
            Logx.always("$TAG: 更新 ClassLoader=${cl.javaClass.simpleName}")
        }
    }

    fun install(cl: ClassLoader) {
        uiLoader = cl
        Logx.always("$TAG: 就绪（调试：setprop $PROP_KEY 1）loader=${cl.javaClass.simpleName}")
    }

    /** onGestureStart 时调用：调试开关打开就试一次。 */
    fun onGesture(ctrl: Any?) {
        if (propInt(PROP_KEY, 0) != 1) return
        val left = propInt(PROP_LEFT, 1) == 1
        runCatching { fire(left) }.onFailure { Logx.e("$TAG: 调试触发失败", it) }
    }

    /** 真正触发一次分屏 / 小窗。left=true 分屏，false 小窗。 */
    fun fire(left: Boolean) {
        val utils = soScUtils()
        if (utils == null) {
            Logx.always("$TAG: 拿不到 SoScUtilsImpl 单例，跳过")
            return
        }
        val ti = pendingTask ?: dyeingTask()
        if (ti == null) {
            Logx.always("$TAG: 拿不到 RunningTaskInfo，跳过")
            return
        }
        val id = taskIdOf(ti)
        val pkg = packageOf(ti)

        val wct = runCatching {
            Class.forName("android.window.WindowContainerTransaction", false, loader())
                .getDeclaredConstructor().newInstance()
        }.getOrElse {
            Logx.e("$TAG: 创建 WindowContainerTransaction 失败", it)
            return
        }

        val m = utils.javaClass.methods.firstOrNull {
            it.name == "enterSplitScreen" && it.parameterTypes.size == 3 &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (m == null) {
            Logx.always("$TAG: 没有 enterSplitScreen(RunningTaskInfo,WCT,boolean)")
            return
        }
        Logx.always("$TAG: 调用 SoScUtilsImpl.enterSplitScreen(task=$id pkg=$pkg isSplit=$left)")
        try {
            m.invoke(utils, ti, wct, left)
            Logx.always("$TAG: enterSplitScreen 调用完成")
        } catch (t: Throwable) {
            Logx.e("$TAG: enterSplitScreen 抛出", t)
            t.cause?.let { Logx.e("$TAG: 内层原因", it) }
        }
    }

    // —————————————— 反射工具 ——————————————

    private fun soScUtils(): Any? = runCatching {
        Class.forName("com.android.wm.shell.sosc.SoScUtilsImpl", false, loader())
            .getMethod("getInstance").invoke(null)
    }.getOrElse {
        Logx.e("$TAG: 取 SoScUtilsImpl 失败", it)
        null
    }

    private fun loader(): ClassLoader = uiLoader ?: ClassLoader.getSystemClassLoader()

    private fun propInt(key: String, def: Int): Int = runCatching {
        val c = Class.forName("android.os.SystemProperties", false, loader())
        c.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, key, def) as Int
    }.getOrDefault(def)

    /** 取当前最近任务里的第一个 RunningTaskInfo（全量对象，含包名）。 */
    private fun frontTaskInfoObject(): Any? = runCatching {
        val atmCls = Class.forName("android.app.ActivityTaskManager", false, loader())
        val atm = atmCls.getMethod("getService").invoke(null)
        val getTasks = atm.javaClass.methods.firstOrNull {
            it.name == "getTasks" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return@runCatching null
        val params = getTasks.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        args[0] = 1
        for (k in 1 until params.size) {
            args[k] = when (params[k]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        (getTasks.invoke(atm, *args) as? List<*>)?.firstOrNull()
    }.getOrElse {
        Logx.e("$TAG: 取任务失败", it)
        null
    }

    /**
     * 取"应该被分屏的任务"：最近任务列表里第一个不是桌面的 App。
     * 松手时前台通常已经切回桌面（上滑手势的副作用），所以要往后找一个真实 App。
     */
    private fun dyeingTask(): Any? {
        val list = taskList(4)
        if (list.isEmpty()) return null
        for (o in list) {
            if (o == null) continue
            val pkg = packageOf(o)
            if (pkg == "com.miui.home" || pkg == "?") continue
            return o
        }
        Logx.always("$TAG: 最近任务里没有非桌面应用（只有桌面）")
        return null
    }

    private fun taskList(max: Int): List<Any?> = runCatching {
        val atmCls = Class.forName("android.app.ActivityTaskManager", false, loader())
        val atm = atmCls.getMethod("getService").invoke(null)
        val getTasks = atm.javaClass.methods.firstOrNull {
            it.name == "getTasks" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return@runCatching emptyList<Any?>()
        val params = getTasks.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        args[0] = max
        for (k in 1 until params.size) {
            args[k] = when (params[k]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        (getTasks.invoke(atm, *args) as? List<*>) ?: emptyList<Any?>()
    }.getOrElse {
        Logx.e("$TAG: 取任务列表失败", it)
        emptyList()
    }

    private fun taskIdOf(ti: Any): Int = runCatching {
        ti.javaClass.getField("taskId").getInt(ti)
    }.getOrDefault(-1)

    private fun packageOf(ti: Any): String = runCatching {
        (ti.javaClass.getField("topActivity").get(ti))
            ?.let { act -> act.javaClass.getMethod("getPackageName").invoke(act) as? String }
            ?: "?"
    }.getOrDefault("?")
}
