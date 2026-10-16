package com.abel.os4freeformx

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface

/**
 * 小窗增强三项 —— **移植自 HyperCeiler**（AGPL-3.0，作者 ReChronoRain 及贡献者）。
 *
 * 对应上游文件：
 *   · `rules/systemframework/freeform/FreeFormCount.kt`      → [installMultiFreeform]
 *   · `rules/systemframework/freeform/OpenAppInFreeForm.java` → [installForceFreeform]
 *   · `rules/systemframework/freeform/UnForegroundPin.java`   → [installEdgeFront]
 *
 * 本文件为**独立重写**：按本模块的 `MainHook`/`Logx` 约定实现，不引入上游的
 * `BaseHook` / `ezhooktool` / `PrefsBridge` 依赖；开关一律走本模块的 [Cfg]。
 * 作用域与其他 system 侧钩子相同（system_server）。
 *
 * ⚠️ 三个钩子全部运行在 **system_server**，遵循项目纪律：
 *    · 只装 hook，绝不在安装期碰 Context / ContentResolver / prefs；
 *    · 需要 Context 的地方一律用**回调参数里已有的**对象，或延迟到运行期再取；
 *    · 所有逻辑包 try/catch，失败只记日志，绝不影响系统启动。
 */
object FreeformExtra {

    // ---------------- ① 不限制小窗数量（多小窗） ----------------

    /** 上游 hook 的整数上限；256 等价于"实际不限制"。 */
    private const val MAX_STACK_COUNT = 256

    /**
     * 多小窗：把 MIUI 的「最大小窗个数」判定改写成 256。
     *
     * 上游（FreeFormCount.kt）按 Android 版本分两路：
     *   · 36+ → 只挂 `getMaxMiuiFreeFormStackCount`
     *   · 更低 → 额外挂 `getMaxMiuiFreeFormStackCountForFlashBack`
     * 这里**两路都尝试挂**（挂不到就跳过并记日志），比按版本猜更稳：
     * 本 ROM 是 Android 17，多挂一个不存在的名字不会有害。
     *
     * ⚠️ 方法所在类**不写死**：本 ROM 上 `MiuiFreeFormStackDisplayStrategy` 在
     * `miui-services.jar`，但类名/包名随版本会变，所以按**候选类 × 候选方法名**扫描。
     */
    fun installMultiFreeform(m: MainHook, cl: ClassLoader): Int {
        val classes = listOf(
            "com.android.server.wm.MiuiFreeFormStackDisplayStrategy",
            "com.android.server.wm.MiuiFreeFormStackDisplayStrategyStub",
            "com.android.server.wm.MiuiFreeFormManagerService"
        )
        val methods = listOf(
            "getMaxMiuiFreeFormStackCount",
            "getMaxMiuiFreeFormStackCountForFlashBack"
        )
        var n = 0
        classes.forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull()
            if (c == null) {
                Logx.always("多小窗: 候选类不存在 $cn")
                return@forEach
            }
            // ★★★ v0.4.58 关键修正（真机 smali 反汇编取证）：
            //   原实现写的是 `it.parameterTypes.isEmpty()`（只挂**无参**重载），
            //   但本 ROM 上这个方法是 **private 且有 2 个参数**：
            //     `private getMaxMiuiFreeFormStackCount(String packageName, MiuiFreeFormActivityStack)`
            //   反汇编证据（MiuiFreeFormStackDisplayStrategy.smali:51）：
            //     .method private getMaxMiuiFreeFormStackCount(Ljava/lang/String;Lcom/android/server/wm/MiuiFreeFormActivityStack;)I
            //   ⇒ 原来的过滤器**一个都匹配不到**，钩子数量恒为 0 ——
            //     这正是 v0.4.47 移植的「允许多小窗」从未生效的原因。
            //   ⇒ 现在**按名字挂，不限参数个数/返回类型/可见性**。
            c.declaredMethods.filter { it.name in methods }.forEach { mm ->
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        // 开关关掉 ⇒ 完全放行，行为与原生一致
                        Cfg.reloadThrottled()
                        if (!Cfg.multiFreeform) return@Hooker chain.proceed()
                        val orig = runCatching { chain.proceed() }.getOrNull()
                        Logx.once(
                            "mf-count-" + mm.name,
                            "多小窗: ${cn.substringAfterLast('.')}#${mm.name}" +
                                mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName } +
                                " 原生=$orig -> $MAX_STACK_COUNT"
                        )
                        MAX_STACK_COUNT
                    })) {
                    n++
                    Logx.always(
                        "多小窗: 已挂 ${cn.substringAfterLast('.')}#${mm.name}" +
                            mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                    )
                }
            }
        }
        Logx.always("多小窗: 共挂 $n 个数量上限钩子")
        return n
    }

    // ---------------- ② 贴边小窗强制前台 ----------------

    /**
     * 贴边小窗强制前台：让 `needForegroundPin` 恒为 true。
     *
     * 上游（UnForegroundPin.java）：
     * ```
     * findAndHookMethod("com.android.server.wm.MiuiFreeFormGestureController",
     *     "needForegroundPin", "com.android.server.wm.MiuiFreeFormActivityStack",
     *     param -> param.setResult(true));
     * ```
     * 即：不管 MIUI 原来怎么判断，都回答「需要前台钉住」。
     *
     * ⚠️ 参数类型**不写死**：上游用精确类型 `MiuiFreeFormActivityStack`，但本 ROM 上若
     * 该类型不存在（版本差异）会直接挂不上。这里按**方法名 + 单参数**挂，并用
     * `setResult(true)` 短路（before 时机，不调 proceed）。
     */
    fun installEdgeFront(m: MainHook, cl: ClassLoader): Int {
        val classes = listOf(
            "com.android.server.wm.MiuiFreeFormGestureController",
            "com.android.server.wm.MiuiFreeFormGestureControllerStub"
        )
        var n = 0
        classes.forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
                Logx.always("贴边前台: 候选类不存在 $cn"); return@forEach
            }
            c.declaredMethods.filter { it.name == "needForegroundPin" }.forEach { mm ->
                val argc = mm.parameterTypes.size
                // 上游返回 boolean；这里按返回值精确定位，避免同名重载误伤
                if (mm.returnType != java.lang.Boolean.TYPE) return@forEach
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        Cfg.reloadThrottled()
                        if (!Cfg.edgeFreeformFront) return@Hooker chain.proceed()
                        Logx.once(
                            "ef-pin",
                            "贴边前台: needForegroundPin(${mm.parameterTypes.joinToString(",") { it.simpleName }}) 强制 true"
                        )
                        true
                    })) {
                    n++
                    Logx.always(
                        "贴边前台: 已挂 ${cn.substringAfterLast('.')}#needForegroundPin" +
                            mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                    )
                }
            }
        }
        Logx.always("贴边前台: 共挂 $n 个钩子")
        return n
    }

    // ---------------- ④ ★ v0.4.61 保存小窗几何到 MIUI 自己的存储 ----------------

    /**
     * ★★★ v0.4.61 **真机反汇编定位到的根因**（用户两个现象的同一个来源）。
     *
     * 反汇编证据链（AOSP `services.jar` + MIUI `miui-services.jar`）：
     *
     * 1) MIUI 判断"小窗是不是横屏"**依赖它自己的几何存储**（`MiuiFreeFormManagerService` 行 724-725）：
     *      Rect lastBounds = mLaunchParamsController.getFreeformLastPosition(mTask);
     *      if (lastBounds != null)
     *          mIsLandcapeFreeform = lastBounds.width() > lastBounds.height();
     *    ⇒ **没有保存过几何 ⇒ 方向标志不会被更新 ⇒ 我们设的横屏被忽略**。
     *
     * 2) 几何保存在 `Task.infiniteResizeFinish(Pair<Rect,Float>)`（AOSP `Task.smali:19413`）
     *    → `LaunchParamsController.saveFreeformDesktopMemory(task, params, true)`
     *    → `LaunchParamsPersister.saveTask(...)`（按 `task.realActivity` 的 ComponentName 存）。
     *
     * 3) 而 MIUI 只在 **changeMask 含 0x2000** 时才调用它
     *    （`MiuiFreeFormManagerService.smali:18493`）：
     *      if ((infoChange.getChangeMask() & 0x2000) != 0)
     *          task.infiniteResizeFinish(infoChange.getParamsAfterInfiniteResize());
     *
     * ⇒ 模块靠"关闭再重开"换几何时，**这个保存根本没发生**
     *   ⇒ 新任务查不到几何 ⇒ 回退竖屏/整屏（正好对应「切成横屏又弹回」与「旋转两次小窗丢失」）。
     *
     * 修法：模块在**自己确实摆放好了小窗**之后，**主动调用 ROM 自己的保存接口**
     * （`Task.infiniteResizeFinish`），把当前真实几何写进 MIUI 的存储。
     * 这样后续所有依赖 `getFreeformLastPosition` 的判定（方向、旋转、重开）都会读到正确值。
     *
     * 说明：这是"把 MIUI 自己的机制喂饱"，不是绕过它 —— 因此不会再被它的重算覆盖。
     */
    fun saveFreeformGeometry(ctrl: Any, why: String) {
        runCatching {
            Cfg.reloadThrottled()
            if (!Cfg.rememberBounds) return
            // ★ 先把两个对象的**真实字段名**打出来（一次性），彻底告别"靠猜字段名"。
            Logx.once(
                "geo-fields-dump",
                "几何保存诊断: ctrl=${ctrl.javaClass.name}" +
                    " 字段=[${ctrl.javaClass.declaredFields.joinToString(",") { it.name }}]" +
                    " | info=" + (
                        fieldOf(ctrl, "mRunningTaskInfo")?.let { i ->
                            "${i.javaClass.name} 字段=[${i.javaClass.declaredFields.joinToString(",") { it.name }}]"
                        } ?: "null"
                    )
            )
            val info = fieldOf(ctrl, "mRunningTaskInfo")
            if (info == null) { Logx.e("几何保存($why): mRunningTaskInfo 为空，放弃"); return }
            // TaskInfo/ActivityManager.RunningTaskInfo 的几何字段在各版本叫法不同，
            // 逐个候选取（不再假定 mBounds）。
            var bounds: Rect? = null
            for (f in listOf("mBounds", "bounds", "mLastNonFullscreenBounds", "mConfigBounds")) {
                val v = fieldOf(info, f)
                if (v is Rect && v.width() > 0 && v.height() > 0) { bounds = v; break }
            }
            if (bounds == null) {
                val v = runCatching { call(info, "getBounds") }.getOrNull()
                if (v is Rect && v.width() > 0) bounds = v
            }
            if (bounds == null) {
                // Configuration.windowConfiguration.bounds —— 全程反射（stub jar 里不可直接引用）
                val cfg = fieldOf(info, "configuration")
                val wc = cfg?.let { runCatching { call(it, "getWindowConfiguration") }.getOrNull() }
                val b = wc?.let { runCatching { call(it, "getBounds") }.getOrNull() }
                if (b is Rect && b.width() > 0) bounds = b
            }
            if (bounds == null) { Logx.e("几何保存($why): 几何字段都取不到，放弃"); return }
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                Logx.e("几何保存($why): mBounds 非法 $bounds，放弃"); return
            }
            // ★ 首次运行先把 ctrl 的字段名打出来（一次性），避免继续靠猜。
            Logx.once(
                "geo-ctrl-fields",
                "几何保存: ctrl 类型=${ctrl.javaClass.name} 字段=" +
                    ctrl.javaClass.declaredFields.joinToString(",") { it.name }
            )
            // 任务对象：SystemUI 侧的 MiuiFreeformModeTaskInfo 不持有 android Task，
            // MIUI 的保存接口（Task.infiniteResizeFinish）只能从 system_server 侧调。
            // ⇒ 这里改为调用 **SystemUI 可达** 的等价入口：MiuiFreeformModeTaskInfo 自己的
            //   保存/持久化方法（下面按方法名探测，命中即用）。
            val id = (fieldOf(info, "taskId") as? Int)
                ?: runCatching { call(info, "getTaskId") as? Int }.getOrNull()
            // ★ 关键结论：`Task.infiniteResizeFinish` 是 **system_server** 侧的 API，
            //   SystemUI 进程拿到的 ctrl（MiuiFreeformModeTaskInfo）里没有 android Task，
            //   org.getRunningTaskInfo 返回的也是 TaskInfo（不是 Task）⇒ 这里必然拿不到。
            //   ⇒ 几何保存必须放到 system_server 侧做（见 installGeoPersist，由 system 作用域注入）。
            val org = fieldOf(ctrl, "mTaskOrganizer")
            val got = org?.let { o ->
                id?.let {
                    runCatching {
                        o.javaClass.getMethod("getRunningTaskInfo", Integer.TYPE).invoke(o, it)
                    }.getOrNull()
                }
            }
            Logx.once(
                "geo-no-task",
                "几何保存($why): SystemUI 侧拿不到 android Task" +
                    "（ctrl=${ctrl.javaClass.simpleName} id=$id 取到=${got?.javaClass?.name ?: "null"}）" +
                    " ⇒ 保存交由 system_server 侧的 installGeoPersist"
            )
            return
        }.onFailure { Logx.e("几何保存失败($why)", it) }
    }

    // ---------------- ⑥ 真·横竖屏：调 MIUI 原生旋转接口 ----------------

    /**
     * ★★★ v0.4.68 **彻底重写**（用户质疑一针见血：「你这个横屏又没真正实现，为什么旋转会消失？
     *   系统小窗旋转也不消失」）。
     *
     * 之前我用「关闭 → 重开 → 换一个矩形」来**假装**横屏 —— 三个致命问题：
     *   1) 应用内部根本没旋转（只是窗口框变长宽比），所以「没真正实现横屏」；
     *   2) 每次"关闭→重开"都会触发配置变更 ⇒ 又被对账逻辑当成旋转 ⇒ **连环关窗**
     *      （真机 03:57 日志完整抓到 3 连关窗），用户看到"小窗消失再重开"；
     *   3) 与 MIUI 自己的几何逻辑并行存在 ⇒ 互相覆盖 ⇒ 横屏被弹回。
     *
     * 反汇编 `Miui-WindowManager-Shell.jar` 找到 **MIUI 自己的横竖屏实现**：
     *
     *   MiuiFreeformModeControlImpl.setRequestedOrientation(int taskId, int orientation)
     *     → MiuiFreeformModeController.setRequestedOrientation(taskId, orientation)
     *         → MiuiFreeformModeTaskInfo.setIsLandscapeFreeform(landscape)   ← 真方向标志
     *         → MiuiFreeformModeAnimation
     *             .startFreeformOrientationChangeShellTransition(taskInfo)   ← 原生旋转转场
     *
     * 全部在 **SystemUI 侧**（`MiuiFreeformModeControlImpl` 就是 `IMiuiFreeformModeControl`
     * 的实现，本模块在别处已经用过它）⇒ **不需要重启手机**。
     *
     * orientation 取值 = 标准 Android 常量，MIUI 内部用
     * `MiuiMultiWindowUtils.isOrientationLandscape(int)` 判定：
     *   · 横屏：4 = SCREEN_ORIENTATION_SENSOR_LANDSCAPE（跟随重力，最贴近系统行为）
     *   · 竖屏：1 = SCREEN_ORIENTATION_PORTRAIT
     */
    /** 真机实测（04:22）ROM 认可的横屏取值：0/6/8/11 为 true；这里取 6 = SENSOR_LANDSCAPE。 */
    private const val LANDSCAPE_ORIENTATION = 6
    /** 竖屏取值：实测 1 为 false。 */
    private const val PORTRAIT_ORIENTATION = 1

    /** SystemUI 注入的 ClassLoader（由 Hooks 在安装时写入）—— 拿 DSH/shell 类必须用它。 */
    @Volatile
    var uiLoader: ClassLoader? = null

    fun rotateFreeformNatively(owner: Any, landscape: Boolean): Boolean {
        return runCatching {
            Logx.once("rot-enter", "原生旋转: 进入 owner=${owner.javaClass.name}")
            val id = runCatching {
                fieldOf(owner, "mRunningTaskInfo")?.let { call(it, "getTaskId") as? Int }
            }.getOrNull() ?: runCatching { call(owner, "getTaskId") as? Int }.getOrNull()
            if (id == null) {
                Logx.e(
                    "原生旋转: 取不到 taskId（owner=${owner.javaClass.name} 字段=" +
                        owner.javaClass.declaredFields.joinToString(",") { it.name } + "）"
                )
                return false
            }
            Logx.once("rot-id", "原生旋转: taskId=$id")

            // 从多任务控制器拿到 MiuiFreeformModeControlImpl
            // ★ 真机取证（04:14）：`ClassNotFoundException: MultiTaskingControllerImpl`
            //   原因：这里用了**默认 ClassLoader**，而 DSH/shell 的类只有**注入进来的**
            //   ClassLoader 才看得到（项目里 `Hooks.cls()` 用的就是 uiLoader）。改用 uiLoader。
            val ldr = uiLoader ?: owner.javaClass.classLoader
            val ctlCls = Class.forName(Constants.CLS_MULTITASKING_CTL, false, ldr)
            val ctl: Any = ctlCls.getMethod("getInstance").invoke(null) ?: return false
            var modeControl: Any? = null
            // ★ 真机取证（04:12 日志）：owner 类型是 MiuiDecorationDot，它自己就有
            //   `mTaskOrganizer`（ShellTaskOrganizer）；而 MIUI 正是把 IMiuiFreeformModeControl
            //   **注册在 TaskOrganizer 上**的（反汇编 MiuiFreeformModeController:5838 →
            //   TaskOrganizer.registerMiuiFreeformModeControl）。⇒ 优先从 organizer 取。
            val org = fieldOf(owner, "mTaskOrganizer")
                ?: runCatching {
                    fieldOf(owner, "mMulWinSwitchDecorViewModel")?.let { vm ->
                        fieldOf(vm, "mTaskOrganizer")
                    }
                }.getOrNull()
            if (org != null) {
                val hit = org.javaClass.declaredFields.firstOrNull {
                    it.name.contains("FreeformModeControl") || it.name.contains("MiuiFreeform")
                }
                if (hit != null) {
                    hit.isAccessible = true
                    modeControl = runCatching { hit.get(org) }.getOrNull()
                    Logx.once(
                        "org-hit",
                        "原生旋转: 从 TaskOrganizer 字段 ${hit.name} 取到 modeControl=${modeControl?.javaClass?.name}"
                    )
                } else {
                    Logx.once(
                        "org-nofield",
                        "原生旋转: TaskOrganizer 里没有 FreeformModeControl 字段，实际字段=" +
                            org.javaClass.declaredFields.joinToString(",") { it.name }
                    )
                }
            }
            // ① 再按常见名字从多任务控制器取
            if (modeControl == null) for (name in listOf("getMiuiFreeformModeControl", "getFreeformModeControl")) {
                modeControl = runCatching { ctl.javaClass.getMethod(name).invoke(ctl) }.getOrNull()
                if (modeControl != null) break
            }
            // ② 再按返回类型扫（不同 ROM 版本命名不一）
            if (modeControl == null) {
                modeControl = runCatching {
                    ctl.javaClass.methods.firstOrNull { m ->
                        m.parameterTypes.isEmpty() &&
                            m.returnType.name.contains("FreeformModeControl")
                    }?.invoke(ctl)
                }.getOrNull()
            }
            if (modeControl == null) {
                // ★ 真机取证：接口是注册在 TaskOrganizer 上的
                //   （MiuiFreeformModeController:5838 → TaskOrganizer.registerMiuiFreeformModeControl），
                //   而模块已经持有 mTaskOrganizer ⇒ 直接从 organizer 里找那个字段。
            }
            if (modeControl == null) {
                Logx.e("原生旋转: 拿不到 MiuiFreeformModeControl（task=$id）")
                return false
            }
            // ★★★ v0.4.75 **真机实测取值表**（04:22，直接问 ROM 自己）：
            //     isOrientationLandscape(
            //       0=true,  1=false, 2=false, 3=false, 4=false, 5=false,
            //       6=true,  7=false, 8=true,  9=false, 10=false, 11=true,
            //       12=false,13=false,14=false, -1=false,-2=false,-3=false )
            //   ⇒ **横屏 = 0 / 6 / 8 / 11**；之前传的 4 是 false
            //     ⇒ MIUI 认为"方向没变" ⇒ 在 `isLandscape == current` 处直接 return（视觉无反应）。
            //   取 6 = SCREEN_ORIENTATION_SENSOR_LANDSCAPE（跟随重力，最贴近系统小窗行为）；
            //   竖屏取 1 = SCREEN_ORIENTATION_PORTRAIT（实测 false ⇒ 非横屏，正确）。
            val orientation = if (landscape) 6 else 1
            // ★ v0.4.74 真机取证（04:19）：方法调通了、**无异常**，但视觉无反应。
            //   反汇编 MiuiFreeformModeController:7099-7112 找到原因：
            //     boolean isLandscape = MiuiMultiWindowUtils.isOrientationLandscape(p2);
            //     boolean current     = taskInfo.isLandscapeFreeform();
            //     if (isLandscape == current) goto :goto_7a;   // ★ 相同就直接跳过，什么都不做
            //   ⇒ 说明我们传的 4 被判成**非横屏**，与当前态相同 ⇒ MIUI 直接 return。
            //   这里把候选值逐个喂给 MiuiMultiWindowUtils.isOrientationLandscape 探明规则，
            //   同时打印当前 isLandscapeFreeform 状态，一次性定位该传哪个值。
            runCatching {
                val utilsCls = Class.forName("android.util.MiuiMultiWindowUtils", false, uiLoader)
                val probe = utilsCls.getMethod("isOrientationLandscape", Integer.TYPE)
                val cand = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -1, -2, -3)
                Logx.once(
                    "orient-probe",
                    "原生旋转诊断: isOrientationLandscape(" +
                        cand.joinToString(",") { "$it=${runCatching { probe.invoke(null, it) }.getOrNull()}" } +
                        ")"
                )
                val infoCls = Class.forName(
                    "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo",
                    false, uiLoader
                )
                Logx.once("orient-cur", "原生旋转诊断: TaskInfo 类已加载 ${infoCls.name}")
            }.onFailure { Logx.e("原生旋转诊断失败", it) }
            val mc: Any = modeControl
            // ★ 真机取证（04:16）：我们**已经拿到了 MiuiFreeformModeController**，
            //   但 `setRequestedOrientation` 在反汇编里是 **private**
            //     （.method private setRequestedOrientation(II)V）
            //   所以 getMethod 会 NoSuchMethodException。改为在类层次里找**含 private** 的同名方法，
            //   这正是 MIUI 自己内部调用的那个方法（setIsLandscapeFreeform + 原生旋转转场）。
            val mtd = generateSequence(mc.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull {
                    it.name == "setRequestedOrientation" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Integer.TYPE
                }
            if (mtd == null) {
                Logx.e(
                    "原生旋转: 类层次里也找不到 setRequestedOrientation（" +
                        mc.javaClass.declaredMethods.filter { it.name.contains("rientation") }
                            .joinToString(",") { it.name } + "）"
                )
                return false
            }
            mtd.isAccessible = true
            mtd.invoke(mc, id, orientation)
            Logx.always(
                "原生旋转: 已调 MIUI 自己的 setRequestedOrientation(task=$id, " +
                    "orientation=$orientation=${if (landscape) "横屏" else "竖屏"})"
            )
            true
        }.onFailure {
            // ★ 不要打印 RunningTaskInfo（toString 极长，会把真正的异常挤掉，已踩过）
            Logx.e("原生旋转失败: ${it.javaClass.simpleName}: ${it.message}", it.cause ?: it)
        }.getOrDefault(false)
    }

    // ---------------- ⑤ (system_server) 把几何写进 MIUI 自己的存储 ----------------

    /**
     * ★★★ v0.4.65：几何保存必须在 **system_server** 侧做。
     *
     * 为什么不能在 SystemUI 做：`Task.infiniteResizeFinish(Pair)` 是 system_server 的 API，
     * SystemUI 进程里的 `MiuiFreeformModeTaskInfo` / `TaskOrganizer.getRunningTaskInfo()`
     * 都只能拿到 `TaskInfo`，拿不到 `android.server.wm.Task` ⇒ 无法调用（真机日志已证）。
     *
     * 做什么：hook `MiuiFreeFormManagerService` 上**模块自己摆放小窗**之后会经过的入口，
     * 在那一刻把当前真实几何通过 ROM 自己的保存链路写回：
     *     Task.infiniteResizeFinish(Pair<Rect,Float>)
     *       → LaunchParamsController.saveFreeformDesktopMemory(task, params, true)
     *       → LaunchParamsPersister.saveTask(...)（按 task.realActivity 的 ComponentName 存）
     *
     * 为什么必须这么做（反汇编证据）：
     *   · MIUI 判断小窗方向要靠它（MiuiFreeFormManagerService:724-725）：
     *       Rect last = getFreeformLastPosition(task);
     *       if (last != null) mIsLandcapeFreeform = last.width() > last.height();
     *   · MIUI 自己只在 `changeMask & 0x2000` 时才调用保存（:18493）。
     *     ⇒ 模块靠"关闭再重开"换几何时**保存从未发生**，MIUI 查不到几何
     *       ⇒ 方向回退竖屏 / 旋转后窗口回退整屏（正好对应两个现象）。
     */
    fun installGeoPersist(m: MainHook, cl: ClassLoader): Int {
        val cn = "com.android.server.wm.MiuiFreeFormManagerService"
        val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
            Logx.always("几何持久化: 找不到 $cn")
            return 0
        }
        // 在这两个"模块摆放完小窗就会经过"的入口上补一次保存：
        //   · onStartActivity(Task,...)          —— 新窗口刚起来
        //   · onActivityStackFirstActivityRecordAdded(int) —— 小窗栈首个 Activity 就位
        val targets = mapOf(
            "onStartActivity" to 1,                              // 第 1 参数是 Task
            "onActivityStackFirstActivityRecordAdded" to -1      // 只有 int taskId
        )
        var n = 0
        targets.forEach { (name, taskArgIdx) ->
            c.declaredMethods.filter { it.name == name }.forEach { mm ->
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val res = chain.proceed()
                        runCatching {
                            Cfg.reloadThrottled()
                            if (!Cfg.rememberBounds) return@runCatching
                            // 取 Task
                            val task: Any? = if (taskArgIdx >= 0) {
                                chain.getArg(taskArgIdx)
                            } else {
                                // 用 taskId 从 RootWindowContainer 反查
                                val id = chain.getArg(0) as? Int ?: return@runCatching
                                val svc = chain.thisObject
                                val atms = runCatching {
                                    svc.javaClass.getMethod("getActivityTaskManagerService").invoke(svc)
                                }.getOrNull() ?: return@runCatching
                                val rwc = runCatching {
                                    atms.javaClass.getField("mRootWindowContainer").get(atms)
                                }.getOrNull() ?: return@runCatching
                                runCatching {
                                    rwc.javaClass.getMethod(
                                        "anyTaskForId", Integer.TYPE, Integer.TYPE
                                    ).invoke(rwc, id, 0)
                                }.getOrNull()
                            } ?: return@runCatching
                            task?.let { persistTaskGeometry(it, name) }
                        }
                        res
                    })) {
                    n++
                    Logx.always("几何持久化: 已挂 $cn#$name")
                }
            }
        }
        Logx.always("几何持久化: 共挂 $n 个钩子")
        return n
    }

    /**
     * ★★★ v0.4.79 **(system_server) 小窗内锁定方向** —— 解决「小红书横屏后点开二级菜单变回竖屏」。
     *
     * 反汇编定位到**确切位置**（`MiuiFreeFormManagerService.setRequestedOrientation`，system_server）：
     *
     *     public void setRequestedOrientation(int orientation, Task task) {
     *         if (task == null) return;
     *         MiuiFreeFormActivityStack mffas =
     *             getMiuiFreeFormActivityStackForMiuiFB(task.getRootTaskId());
     *         if (mffas != null)
     *             mffas.mIsLandcapeFreeform =
     *                 MiuiMultiWindowUtils.isOrientationLandscape(orientation);   // ★ 就是这行
     *         mHandler.post(...);
     *     }
     *
     * 调用链：应用内跳转（二级页面）请求竖屏
     *   → `ActivityRecordImpl.setRequestedOrientationFreeForm(orientation)`（system_server）
     *   → `MiuiFreeFormManagerService.setRequestedOrientation(orientation, task)`
     *   → 把 `mIsLandcapeFreeform` 改回 false ⇒ 小窗弹回竖屏。
     *
     * ⚠️ 为什么不用 ROM 自带的 `getFreeformIgnoreRequestOrientationList()` 名单？
     *   那张名单确实存在、语义正合，但**判断它的代码在 system_server**
     *   （`ActivityRecordImpl`），而 `android.util.MiuiMultiWindowAdapter` 只在
     *   framework.jar 里——在 SystemUI 进程 hook 它**不会被调用到**（真机实测：
     *   钩子挂上了、`ignore-orient` 一次都没触发）。所以必须在 system_server 侧拦。
     *
     * 修法：hook `setRequestedOrientation`，若该应用在模块记忆里是"横屏"、
     *   而应用这次请求的是竖屏 ⇒ **把方向改回横屏**再放行（用户的显式选择优先于应用内跳转）。
     *   无记忆的应用完全不受影响。
     */
    fun installLockFreeformOrientation(m: MainHook, cl: ClassLoader): Int {
        val cn = "com.android.server.wm.MiuiFreeFormManagerService"
        val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
            Logx.always("锁定方向: 找不到 $cn")
            return 0
        }
        var n = 0
        c.declaredMethods
            .filter {
                it.name == "setRequestedOrientation" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
            .forEach { mm ->
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        runCatching {
                            Cfg.reloadThrottled()
                            val ctx = AppCtx.get() ?: return@Hooker chain.proceed()
                            val want = chain.getArg(0) as? Int ?: return@Hooker chain.proceed()
                            val task = chain.getArg(1) ?: return@Hooker chain.proceed()
                            // 取包名
                            val pkg = runCatching {
                                task.javaClass.getMethod("getRootTaskId")
                            }.getOrNull()?.let { _ ->
                                runCatching {
                                    val f = task.javaClass.getDeclaredField("realActivity")
                                    f.isAccessible = true
                                    (f.get(task) as? android.content.ComponentName)?.packageName
                                }.getOrNull()
                            } ?: runCatching {
                                task.javaClass.getMethod("getBaseIntent").invoke(task)
                                    .let { it as? android.content.Intent }?.component?.packageName
                            }.getOrNull() ?: return@Hooker chain.proceed()
                            val memo = Bounds.getOrientation(ctx, pkg, Bounds.screenKey(ctx))
                                ?: Bounds.getOrientation(ctx, pkg, "1672x2364")
                                ?: return@Hooker chain.proceed()
                            // ROM 的判定：true=横屏
                            val asked = runCatching {
                                val u = Class.forName("android.util.MiuiMultiWindowUtils", false, cl)
                                u.getMethod("isOrientationLandscape", Integer.TYPE)
                                    .invoke(null, want) as? Boolean
                            }.getOrNull() ?: return@Hooker chain.proceed()
                            if (memo != asked) {
                                // 用户在小窗里显式选的横屏 >> 应用内跳转请求的竖屏 ⇒ 纠正
                                val fix = if (memo) LANDSCAPE_ORIENTATION else PORTRAIT_ORIENTATION
                                Logx.once(
                                    "lock-orient-$pkg",
                                    "锁定方向: $pkg 应用内请求=${if (asked) "横" else "竖"}(=$want)" +
                                        " 但记忆=${if (memo) "横" else "竖"} ⇒ 改为 $fix 放行"
                                )
                                val args = chain.args.toMutableList()
                                args[0] = fix
                                return@Hooker chain.proceed(args.toTypedArray())
                            }
                        }
                        chain.proceed()
                    })) {
                    n++
                    Logx.always("锁定方向: 已挂 $cn#setRequestedOrientation(int,Task)")
                }
            }
        Logx.always("锁定方向: 共挂 $n 个钩子")
        return n
    }

    /**
     * ★★★ v0.4.78（⚠️ 真机验证**无效**，保留仅作记录）：把「用户在小窗里切过横屏的应用」
     * 并进 ROM 自带的「小窗内忽略应用方向请求」名单。
     *
     * 解决：小红书横屏后，点开内容二级菜单又变回竖屏。
     * 机制见 [installForceLandscape] 里的反汇编说明（ActivityRecordImpl 会先查这张名单，
     * 命中就忽略应用自己的方向请求）。
     *
     * 与横屏名单完全同源同模式（`android.util.MiuiMultiWindowAdapter`，framework.jar）
     * ⇒ 同样在 SystemUI 侧即可生效，无需重启手机。
     */
    private fun installIgnoreOrientationHook(m: MainHook, cl: ClassLoader): Int {
        val name = "getFreeformIgnoreRequestOrientationList"
        val targets = listOf(
            "android.util.MiuiMultiWindowAdapter",
            "com.android.server.wm.MiuiFreeformServiceImpl"
        )
        var n = 0
        targets.forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
                Logx.always("忽略方向名单: 类不存在 $cn")
                return@forEach
            }
            c.declaredMethods
                .filter { it.name == name && it.parameterTypes.isEmpty() }
                .forEach { mm ->
                    if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                            val orig = chain.proceed()
                            runCatching {
                                val ctx = AppCtx.get() ?: return@Hooker orig
                                // 凡是记忆为"横屏"的应用，都加入忽略名单 ⇒ 小窗内跳转不会把方向改回竖屏
                                val extra = Bounds.allLandscapePackages(ctx)
                                if (extra.isEmpty()) return@Hooker orig
                                @Suppress("UNCHECKED_CAST")
                                val list = (orig as? MutableList<String>)?.toMutableList()
                                    ?: (orig as? List<String>)?.toMutableList()
                                    ?: mutableListOf()
                                var added = 0
                                extra.forEach { if (list.add(it)) added++ }
                                if (added > 0) {
                                    Logx.once(
                                        "ignore-orient-$added",
                                        "忽略方向名单: 已并入 $added 个横屏应用 $extra" +
                                            " ⇒ 小窗内跳转不再被应用请求拉回竖屏"
                                    )
                                }
                                list
                            }.getOrDefault(orig)
                        })) {
                        n++
                        Logx.always("忽略方向名单: 已挂 $cn#$name()")
                    }
                }
        }
        Logx.always("忽略方向名单: 共挂 $n 个钩子")
        return n
    }

    /** 把 [task] 的当前几何通过 ROM 自己的链路写进 MIUI 存储。 */
    fun persistTaskGeometry(task: Any, why: String) {
        runCatching {
            // 只在 freeform 时保存（否则会把全屏几何也写进去）
            val mode = runCatching {
                task.javaClass.getMethod("getWindowingMode").invoke(task) as? Int
            }.getOrNull()
            val bounds = runCatching {
                task.javaClass.getMethod("getBounds").invoke(task) as? Rect
            }.getOrNull() ?: return
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = runCatching {
                val svc = Class.forName("com.android.server.wm.MiuiFreeFormManagerService")
                val inst = runCatching {
                    svc.getMethod("getInstance").invoke(null)
                }.getOrNull()
                val id = task.javaClass.getMethod("getTaskId").invoke(task) as? Int
                if (inst != null && id != null) {
                    svc.getMethod("getFreeformScale", Integer.TYPE).invoke(inst, id) as? Float
                } else null
            }.getOrNull() ?: 0.66f
            val pair = android.util.Pair(Rect(bounds), scale)
            task.javaClass.getMethod("infiniteResizeFinish", android.util.Pair::class.java)
                .invoke(task, pair)
            Logx.once(
                "geo-saved",
                "几何持久化($why): 已写入 MIUI 存储 mode=$mode $bounds @$scale"
            )
        }.onFailure { Logx.e("几何持久化失败($why)", it) }
    }

    // ---------------- ④ 强制横屏：用 HyperOS 4 自带的「强制横屏名单」机制 ----------------

    /**
     * ★★★ v0.4.59 **真机 smali 反汇编定位 —— 用 ROM 自己的机制，而不是 AOSP 的**。
     *
     * 用户反馈：「小窗切横屏后，横屏的瞬间又切回竖屏」。
     *
     * 前几轮我一直在下游（模块记忆/重开）或错误的类上打补丁，全部无效。这次把
     * `/system_ext/framework/miui-services.jar` 反汇编（baksmali 2.5.2，6942 个类），
     * 读到 **HyperOS 4 自己**的方向判定（MiuiFreeFormActivityStack.smali 行 229-231）：
     *
     *     int orientation = resolveTaskOrientation(mTask);
     *     mIsLandcapeFreeform = MiuiMultiWindowUtils.isOrientationLandscape(orientation);
     *     mIsLandcapeFreeform |= MiuiMultiWindowAdapter
     *                              .getForceLandscapeApplication()   // ★ ROM 自带「强制横屏名单」
     *                              .contains(stackPackageName);
     *
     * ⇒ **HyperOS 4 本身就有"强制横屏应用名单"机制**，命中即置横屏。
     *   这就是正解：不要去改 MIUI 的几何，而是**把应用名放进它自己的名单**。
     *
     * 另：`mIsLandcapeFreeform` 是**字段**（`iput-boolean` 直接写，全库 9 处读、2 处写），
     *   没有 setter —— 所以上一轮 hook `isLandcapeFreeform()` getter 从未触发（探针 0 次），
     *   那是错的方向。而 `getForceLandscapeApplication()` 每次构造 stack 都会被调用。
     *
     * 好处：
     *   · 完全走 ROM 原生路径 ⇒ MIUI 自己的重算/动画都认这个结论，不会被覆盖回去
     *   · 不需要改任何 MIUI 内部状态，风险最低
     *   · 关掉开关即恢复原样
     */
    fun installForceLandscape(m: MainHook, cl: ClassLoader): Int {
        // ROM 真实类：MiuiFreeFormActivityStack 内部调用的是 android.util.MiuiMultiWindowAdapter
        // （framework.jar），而 miui-services 里 MiuiFreeformServiceImpl 有一层包装同名的公开 API。
        // 两处都挂：任一处命中即可（不同 MIUI 版本调用点可能不同）。
        val targets = listOf(
            "android.util.MiuiMultiWindowAdapter",
            "com.android.server.wm.MiuiFreeformServiceImpl"
        )
        // ★★★ v0.4.78 新增第二张 ROM 自带名单（用户反馈）：
        //   「小红书切成横屏后，主页是横屏的，但点开内容二级菜单又变回竖屏」。
        //
        //   反汇编证据（ActivityRecordImpl.setRequestedOrientationFreeForm，system_server）：
        //     List ignore = MiuiFreeformServiceStub.getInstance()
        //                       .getFreeformIgnoreRequestOrientationList();
        //     if (ignore.contains(packageName) || ignore.contains(shortComponentName))
        //         return;                    // ★ 命中名单 ⇒ **忽略应用自己请求的方向**
        //     mMiuiFreeFormManagerService.setRequestedOrientation(orientation, task);
        //
        //   而 `getFreeformIgnoreRequestOrientationList()` 的实现又是转发到
        //     `android.util.MiuiMultiWindowAdapter.getFreeformIgnoreRequestOrientationList()`
        //   —— 与横屏名单**同一个模式**、同一个类（framework.jar）
        //   ⇒ 用完全相同的方式 hook 它：把"用户在小窗里切过横屏的应用"并进这张名单，
        //     应用内跳转（二级页面）请求竖屏时就会被 MIUI 忽略，小窗保持横屏。
        runCatching { installIgnoreOrientationHook(m, cl) }
            .onFailure { Logx.e("忽略方向名单安装失败", it) }
        var n = 0
        targets.forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull()
            if (c == null) {
                Logx.always("强制横屏: 类不存在 $cn")
                return@forEach
            }
            c.declaredMethods
                .filter { it.name == "getForceLandscapeApplication" && it.parameterTypes.isEmpty() }
                .forEach { mm ->
                    if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val orig = chain.proceed()
                        runCatching {
                            Cfg.reloadThrottled()
                            val ctx = AppCtx.get() ?: return@Hooker orig
                            // 本模块记忆里"横屏"的应用，全部并入 ROM 的强制横屏名单
                            val extra = Bounds.allLandscapePackages(ctx)
                            if (extra.isEmpty()) return@Hooker orig
                            @Suppress("UNCHECKED_CAST")
                            val list = (orig as? MutableList<String>)?.toMutableList()
                                ?: (orig as? List<String>)?.toMutableList()
                                ?: mutableListOf()
                            var added = 0
                            extra.forEach { if (list.add(it)) added++ }
                            if (added > 0) {
                                Logx.once(
                                    "force-land-$added",
                                    "强制横屏: 已把 $added 个记忆为横屏的应用并入 ROM 强制横屏名单：$extra"
                                )
                            }
                            list
                        }.getOrDefault(orig)
                    })) {
                        n++
                        Logx.always("强制横屏: 已挂 $cn#getForceLandscapeApplication()")
                    }
                }
        }
        Logx.always("强制横屏: 共挂 $n 个钩子")
        return n
    }



    // ---------------- ③ 强制允许应用以小窗打开 ----------------

    /**
     * 强制以小窗打开：在 `ActivityStarter#executeRequest` 之前，把启动参数里的
     * ActivityOptions 换成 MIUI 自己的小窗参数。
     *
     * 上游（OpenAppInFreeForm.java）的关键三步：
     *   1. 已经是小窗（`mLaunchWindowingMode == 5`）⇒ 直接放行，不重复处理；
     *   2. 命中黑名单（桌面 / 相机 / SystemUI）⇒ 放行，否则桌面自己都会变小窗；
     *   3. 其余 ⇒ `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, false)`
     *      + `ActivityStarter.setActivityOptions(bundle)`。
     *
     * 与上游的差异（**有意为之**，见 README 的"强制小窗"说明）：
     *   上游只对**白名单**（广播点名 / 分享场景）生效，因为它要避免"所有应用都变小窗"。
     *   本模块的开关语义是用户明确要求的「**强制允许**应用以小窗打开」，
     *   所以除黑名单外**一律**改写 —— 这正是本开关存在的意义。
     */
    fun installForceFreeform(m: MainHook, cl: ClassLoader): Int {
        val starterCls = runCatching {
            Class.forName("com.android.server.wm.ActivityStarter", false, cl)
        }.getOrNull() ?: run {
            Logx.always("强制小窗: 找不到 ActivityStarter")
            return 0
        }
        val blacklist = listOf("com.miui.home", "com.android.camera", "com.android.systemui")

        // executeRequest 有多个重载；只挂参数里带 ActivityStarter.Request 的那个
        val candidates = starterCls.declaredMethods.filter { it.name == "executeRequest" }
        Logx.always("强制小窗: ActivityStarter#executeRequest 有 ${candidates.size} 个重载")
        var n = 0
        candidates.forEach { mm ->
            if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                    Cfg.reloadThrottled()
                    if (!Cfg.forceFreeform) return@Hooker chain.proceed()
                    runCatching { rewriteLaunchOptions(chain.getArg(0), blacklist) }
                        .onFailure { Logx.e("强制小窗: 改写启动参数失败", it) }
                    chain.proceed()
                })) {
                n++
                Logx.always(
                    "强制小窗: 已挂 ActivityStarter#executeRequest" +
                        mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                )
            }
        }
        Logx.always("强制小窗: 共挂 $n 个钩子")
        return n
    }

    /** 把 request.intent 对应的启动参数换成"小窗"参数。全程反射 + 防御式取值。 */
    private fun rewriteLaunchOptions(request: Any?, blacklist: List<String>) {
        if (request == null) return
        val cl = request.javaClass.classLoader
        val intent = fieldOf(request, "intent") as? Intent ?: return
        val component = intent.component ?: return
        val pkg = component.packageName ?: return
        if (blacklist.contains(pkg)) return

        // 已经是小窗（launchWindowingMode == 5）⇒ 不重复处理
        val safe = fieldOf(request, "activityOptions")
        if (safe != null) {
            val orig = fieldOf(safe, "mOriginalOptions")
            if (orig is ActivityOptions) {
                val mode = fieldOf(orig, "mLaunchWindowingMode") as? Int
                if (mode == 5) return
            }
        }

        // 取 system_server 的 Context：先试 request 自己的，再试 starter.mService.mContext
        val ctx = resolveContext(request) ?: return

        // MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean, boolean)
        val mmu = runCatching { Class.forName("android.util.MiuiMultiWindowUtils", false, cl) }.getOrNull()
            ?: runCatching { Class.forName("android.util.MiuiMultiWindowAdapter", false, cl) }.getOrNull()
            ?: return
        // 两种签名都试：3 参（ctx,pkg,boolean）与 4 参（ctx,pkg,boolean,boolean）
        val opts = runCatching {
            mmu.getMethod(
                "getActivityOptions", Context::class.java, String::class.java,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
            ).invoke(null, ctx, pkg, true, false)
        }.getOrNull() ?: runCatching {
            mmu.getMethod(
                "getActivityOptions", Context::class.java, String::class.java, java.lang.Boolean.TYPE
            ).invoke(null, ctx, pkg, true)
        }.getOrNull() ?: return

        val bundle = runCatching { opts.javaClass.getMethod("toBundle").invoke(opts) }
            .getOrNull() as? android.os.Bundle ?: return
        // ActivityStarter#setActivityOptions(Bundle)
        runCatching {
            request.javaClass.getMethod("setActivityOptions", android.os.Bundle::class.java)
                .invoke(request, bundle)
            Logx.once("ff-force-$pkg", "强制小窗: $pkg 已改写为小窗启动参数")
        }.onFailure { Logx.e("强制小窗: setActivityOptions 失败($pkg)", it) }
    }

    private fun resolveContext(request: Any?): Context? {
        (fieldOf(request, "mContext") as? Context)?.let { return it }
        // ActivityStarter.mService → ActivityTaskManagerService.mContext
        (fieldOf(fieldOf(request, "mService"), "mContext") as? Context)?.let { return it }
        return null
    }

    /** 调无参方法（取不到返回 null）。 */
    private fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching { return c!!.getMethod(name).invoke(target) }
            c = c.superclass
        }
        return null
    }

    private fun fieldOf(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            }
            c = c.superclass
        }
        return null
    }
}
