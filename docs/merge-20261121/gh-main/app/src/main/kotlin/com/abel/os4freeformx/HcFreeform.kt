package com.abel.os4freeformx

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.graphics.Rect
import io.github.libxposed.api.XposedInterface

/**
 * 从 HyperCeiler 移植的三个「系统框架侧」小窗增强。
 *
 * 目标（用户 2026-10-18 要求）：
 *   ① 强制多小窗 —— 可以打开很多个小窗（系统默认只给 2 个）
 *   ② 贴边小窗强制前台 —— 贴边小窗里的应用跳转新页面时，新页面仍留在小窗
 *   ③ 强制允许所有应用打开小窗 —— 解除 MIUI 的小窗黑名单
 *
 * ─────────────────────────────────────────────────────────────
 * ⚠️ 三个功能**全部在 system_server 侧生效**（作用域 `system`）。
 *    所以改完代码必须软重启；只 `killall com.android.systemui` 不会生效。
 * ─────────────────────────────────────────────────────────────
 *
 * 三者共同的两个坑（HyperCeiler 踩过、我们照抄结论）：
 *   · 跳转双方是**同一个包**时必须放行 —— 否则"全屏应用内部跳页"会被误变成小窗。
 *   · 黑名单永远保留 `com.miui.home` / `com.miui.securitycenter` 等，
 *     否则桌面和安全中心也会被拉进小窗，导致无法回到桌面。
 */
object HcFreeform {

    /** 永远不参与强制小窗/贴边前台的包（HyperCeiler 同款） */
    private val FW_BLACKLIST = listOf(
        "com.miui.securitycenter",
        "com.miui.home"
    )

    /** 贴边小窗记忆表：包名 -> (缩放比, 位置) */
    private val fwApps = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Rect?>>()

    /** 贴边窗口的"完美尺寸"比例常量，与 MIUI 内部一致 */
    private const val STICKY_SCALE = 0.45f

    // ═══════════════════════════════════════════════════════════
    // ① 强制多小窗（上限拉到 256）
    // ═══════════════════════════════════════════════════════════

    /**
     * HyperCeiler 的做法（FreeFormCount.kt）：
     *   · **Android 36+**：只 hook `getMaxMiuiFreeFormStackCount`
     *     （新版把 `ForFlashBack` 那个变体去掉了，硬挂会报 NoSuchMethod）
     *   · **Android 36 之前**：两个变体都挂
     *
     * 我们此前**没做 36 分支**，所以在 Android 17 上会去挂一个不存在的方法。
     */
    fun installMaxCount(m: MainHook, cl: ClassLoader): Int {
        val cls = runCatching {
            Class.forName("com.android.server.wm.MiuiFreeFormStackDisplayStrategy", false, cl)
        }.getOrNull() ?: run {
            Logx.always("多小窗: 找不到 MiuiFreeFormStackDisplayStrategy")
            return 0
        }

        // Android 17 = SDK 37，属于 36+ 分支
        val sdk = android.os.Build.VERSION.SDK_INT
        val names = if (sdk >= 36) {
            listOf("getMaxMiuiFreeFormStackCount")
        } else {
            listOf("getMaxMiuiFreeFormStackCount", "getMaxMiuiFreeFormStackCountForFlashBack")
        }

        var n = 0
        cls.declaredMethods.filter { it.name in names }.forEach { mm ->
            mm.isAccessible = true
            if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                    val orig = runCatching { chain.proceed() as? Int }.getOrNull()
                    val v = Constants.FREEFORM_MAX_COUNT
                    if ((orig ?: -1) != v) {
                        Logx.once("maxcount-${mm.name}", "多小窗: ${mm.name} 原生=${orig} -> $v")
                    }
                    v
                })) n++
        }
        Logx.always("多小窗: 共挂 $n 个上限钩子（SDK=$sdk，方法=${names.joinToString()}）")
        return n
    }

    // ═══════════════════════════════════════════════════════════
    // ② 强制允许所有应用打开小窗（解除黑名单）
    // ═══════════════════════════════════════════════════════════

    /**
     * HyperCeiler 的做法（DisableFreeformBlackList.java）——
     * **从根上解除限制**，而不是拦截启动意图：
     *
     *   1. `ActivityTaskManager#supportsSplitScreen`        -> true
     *   2. `Task#isResizeable`                              -> true
     *   3. `ActivityTaskManagerService#retrieveSettings`    -> 置 mDevEnableNonResizableMultiWindow=true
     *   4. `WindowManagerService$SettingsObserver#updateDevEnableNonResizableMultiWindow`
     *                                                       -> 返回 null（**阻止系统改回去**，关键）
     *   5. `MiuiMultiWindowAdapter` 4 个黑名单**静态字段**  -> 清空
     *   6. `MiuiMultiWindowAdapter` 8 个黑名单**方法**      -> 返回空表
     *   7. `MiuiMultiWindowUtils#isForceResizeable`         -> true
     *   8. `MiuiMultiWindowUtils#supportFreeform`           -> true
     *
     * 我们此前的做法是 hook `ActivityStarter#executeRequest` 改写启动参数 ——
     * 那只在"用户主动开小窗"时有效，**应用自己跳转/从最近任务启动仍会被拦**。
     */
    fun installRemoveRestriction(m: MainHook, cl: ClassLoader): Int {
        var n = 0
        fun hookConst(cn: String, mn: String, value: Any?, tag: String) {
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: return
            c.declaredMethods.filter { it.name == mn }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        Logx.once("$tag-$cn-$mn", "强制小窗: $cn#$mn -> $value")
                        value
                    })) n++
            }
        }

        // 1、2
        hookConst("android.app.ActivityTaskManager", "supportsSplitScreen", true, "restrict")
        hookConst("com.android.server.wm.Task", "isResizeable", true, "restrict")

        // 3：retrieveSettings 之后把开关置 true
        runCatching {
            val c = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl)
            c.declaredMethods.filter { it.name == "retrieveSettings" }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val f = chain.thisObject.javaClass.getDeclaredField("mDevEnableNonResizableMultiWindow")
                            f.isAccessible = true
                            f.set(chain.thisObject, true)
                            Logx.once("nonResizable", "强制小窗: 已置 mDevEnableNonResizableMultiWindow=true")
                        }.onFailure { Logx.e("强制小窗: 置 nonResizable 开关失败", it) }
                        r
                    })) n++
            }
        }.onFailure { Logx.e("强制小窗: 挂 retrieveSettings 失败", it) }

        // 4：**关键** —— 阻止 SettingsObserver 把上面的开关改回去
        runCatching {
            val c = Class.forName("com.android.server.wm.WindowManagerService\$SettingsObserver", false, cl)
            c.declaredMethods.filter { it.name == "updateDevEnableNonResizableMultiWindow" }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        Logx.once("no-reset-nonResizable", "强制小窗: 阻止系统重置 nonResizable 开关")
                        null
                    })) n++
            }
        }.onFailure { Logx.e("强制小窗: 挂 SettingsObserver 失败", it) }

        // 5、6：MIUI 黑名单
        runCatching {
            val c = Class.forName("android.util.MiuiMultiWindowAdapter", false, cl)
            val fields = listOf(
                "FREEFORM_BLACK_LIST",
                "ABNORMAL_FREEFORM_BLACK_LIST",
                "START_FROM_FREEFORM_BLACK_LIST_ACTIVITY",
                "FOREGROUND_PIN_APP_BLACK_LIST"
            )
            fields.forEach { fn ->
                runCatching {
                    val f = c.getDeclaredField(fn)
                    f.isAccessible = true
                    f.set(null, ArrayList<String>())
                    Logx.once("clear-$fn", "强制小窗: 已清空黑名单字段 $fn")
                }
            }
            val methods = listOf(
                "getFreeformBlackList", "getFreeformBlackListFromCloud",
                "getAbnormalFreeformBlackList", "getAbnormalFreeformBlackListFromCloud",
                "getStartFromFreeformBlackList", "getStartFromFreeformBlackListFromCloud",
                "getForegroundPinAppBlackList", "getForegroundPinAppBlackListFromCloud"
            )
            methods.forEach { mn ->
                c.declaredMethods.filter { it.name == mn }.forEach { mm ->
                    mm.isAccessible = true
                    if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                            Logx.once("blk-$mn", "强制小窗: 黑名单方法 $mn -> 空表")
                            ArrayList<String>()
                        })) n++
                }
            }
        }.onFailure { Logx.e("强制小窗: 处理 MiuiMultiWindowAdapter 失败", it) }

        // 7、8：可调整大小 / 支持小窗
        hookConst("android.util.MiuiMultiWindowUtils", "isForceResizeable", true, "restrict")
        hookConst("android.util.MiuiMultiWindowUtils", "supportFreeform", true, "restrict")

        Logx.always("强制小窗: 共挂 $n 个解除限制钩子")
        return n
    }

    // ═══════════════════════════════════════════════════════════
    // ③ 贴边小窗强制前台（Sticky Floating Windows）
    // ═══════════════════════════════════════════════════════════

    /**
     * HyperCeiler 的做法（StickyFloatingWindows.java）：
     *
     *   核心是**记住哪些应用是"贴边小窗"**（`fwApps` 表），
     *   然后在这些应用跳转新页面时，主动把 `ActivityOptions` 改回小窗模式。
     *
     *   挂钩点：
     *     A. `ActivityStarterInjector#modifyLaunchActivityOptionIfNeed`（8 参）
     *        —— 启动新 Activity 时改写 options
     *     B. `ActivityTaskSupervisor#startActivityFromRecents`
     *        —— 从最近任务启动也要改写
     *     C. `ActivityTaskManagerService#launchSmallFreeFormWindow`
     *        —— 记录新进入贴边小窗的应用
     *     D. `ActivityTaskManagerService#resizeTask`
     *        —— 更新该应用的缩放/位置记忆
     *     E. `MiuiFreeFormGestureController$FreeFormReceiver#onReceive`
     *     F. `MiuiFreeFormGestureController#notifyFullScreenWidnowModeStart`
     *        —— 应用真的全屏了就从表里移除
     *
     * ⚠️ 我们此前只 hook 了 `MiuiFreeFormGestureController#needForegroundPin`，
     *    那只是 MIUI 内部"要不要"的一个判断，**并不能让跳转留在小窗**。
     */
    fun installStickyFloating(m: MainHook, cl: ClassLoader): Int {
        var n = 0

        // A：启动 Activity 时改写 options（核心）
        runCatching {
            val c = Class.forName("com.android.server.wm.ActivityStarterInjector", false, cl)
            // ★ v0.6.1：先把**真实签名**打出来 —— HyperCeiler 假设是 8 参，
            //   本机 ROM 若不同就会"命中 0 个重载"而静默失效（我们已踩过一次）。
            // ★ v0.6.2：本机 ROM 的 `ActivityStarterInjector` **只有 `startActivityUncheckedBefore`**，
            //   没有 HyperCeiler 假设的 `modifyLaunchActivityOptionIfNeed`
            //   ⇒ 改为**在整个类层次 + 相邻类里搜方法名**，按名字找而不是按类找。
            // ★★★ v0.6.3 **真实宿主与签名**（dexdump 全量扫描 miui-services dex 得到）：
            //
            //   宿主类: com.android.server.wm.MiuiFreeformServiceImpl
            //   方法  : modifyLaunchActivityOptionIfNeed
            //   签名  : (RootWindowContainer, ActivityOptions, Intent) -> ActivityOptions   ← **3 参**
            //
            //   ⚠️ HyperCeiler 的源码假设宿主是 `ActivityStarterInjector` 且 **8 参**
            //      （它读 args[0]/[5]/[7]）。**本机 ROM 完全不是这个签名**
            //      ⇒ 按 8 参过滤必然"命中 0 个重载"而静默失效。
            //   这条路我们已用 dexdump 证实，不再依赖 HyperCeiler 的版本假设。
            val want = "modifyLaunchActivityOptionIfNeed"
            val holderCn = "com.android.server.wm.MiuiFreeformServiceImpl"
            val hc = runCatching { Class.forName(holderCn, false, cl) }.getOrNull()
            if (hc == null) {
                Logx.always("贴边前台: 找不到 $holderCn")
            } else {
                val all = hc.declaredMethods.filter { it.name == want }
                Logx.always("贴边前台: $holderCn 有 ${all.size} 个 $want" +
                    all.joinToString(" | ", " [", "]") { mm ->
                        mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName } + "->" + mm.returnType.simpleName
                    })
                all.forEach { mm ->
                    mm.isAccessible = true
                    if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                            val r = chain.proceed()
                            runCatching { patchLaunchOptions3(chain, r) }
                                .onFailure { Logx.e("贴边前台: 改写启动参数失败", it) }
                                .getOrDefault(r)
                        })) n++
                }
            }
            val unusedAll: List<java.lang.reflect.Method> = emptyList()
            unusedAll.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val r = chain.proceed()
                        runCatching { patchLaunchOptions(chain, r) }
                            .onFailure { Logx.e("贴边前台: 改写启动参数失败", it) }
                            .getOrDefault(r)
                    })) n++
            }
            Logx.always("贴边前台: modifyLaunchActivityOptionIfNeed 命中 " +
                c.declaredMethods.count { it.name == "modifyLaunchActivityOptionIfNeed" } + " 个重载")
        }.onFailure { Logx.always("贴边前台: 无 ActivityStarterInjector（${it.javaClass.simpleName}）") }

        // C：记录进入贴边小窗的应用
        runCatching {
            val c = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl)
            c.declaredMethods.filter { it.name == "launchSmallFreeFormWindow" }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val pkg = stackPkgOfTaskArg(chain, 0)
                            if (pkg != null && pkg !in FW_BLACKLIST && !fwApps.containsKey(pkg)) {
                                fwApps[pkg] = STICKY_SCALE to null
                                Logx.always("贴边前台: 登记贴边应用 $pkg（共 ${fwApps.size} 个）")
                            }
                        }
                        r
                    })) n++
            }
        }.onFailure { Logx.e("贴边前台: 挂 launchSmallFreeFormWindow 失败", it) }

        // D：更新缩放/位置记忆
        runCatching {
            val c = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl)
            c.declaredMethods.filter { it.name == "resizeTask" }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        runCatching {
                            val pkg = taskPkg(chain.thisObject, chain.getArg(0))
                            val rect = chain.getArgOrNull(1) as? Rect
                            if (pkg != null && rect != null && fwApps.containsKey(pkg)) {
                                val sc = fwApps[pkg]?.first ?: STICKY_SCALE
                                fwApps[pkg] = sc to Rect(rect)
                            }
                        }
                        chain.proceed()
                    })) n++
            }
        }.onFailure { Logx.e("贴边前台: 挂 resizeTask 失败", it) }

        // F：应用真的全屏了 -> 移出表
        runCatching {
            val c = Class.forName("com.android.server.wm.MiuiFreeFormGestureController", false, cl)
            c.declaredMethods.filter { it.name == "notifyFullScreenWidnowModeStart" }.forEach { mm ->
                mm.isAccessible = true
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        runCatching {
                            val pkg = runCatching { chain.getArg(1).let { call(it, "getStackPackageName") } }.getOrNull() as? String
                            if (pkg != null && fwApps.remove(pkg) != null) {
                                Logx.always("贴边前台: $pkg 已全屏，移出贴边表（余 ${fwApps.size} 个）")
                            }
                        }
                        chain.proceed()
                    })) n++
            }
        }.onFailure { Logx.always("贴边前台: 无 notifyFullScreenWidnowModeStart") }

        Logx.always("贴边前台: 共挂 $n 个钩子，已登记 ${fwApps.size} 个贴边应用")
        return n
    }

    // ─── 辅助 ────────────────────────────────────────────────

    /** 从第 idx 个 Task 参数取包名 */
    private fun taskPkg(host: Any?, taskId: Any?): String? = runCatching {
        val tid = (taskId as? Int) ?: return@runCatching null
        val mrwc = field(host, "mRootWindowContainer") ?: return@runCatching null
        val task = call(mrwc, "anyTaskForId", tid) ?: return@runCatching null
        val it2 = field(task, "intent") as? Intent
        it2?.component?.packageName
    }.getOrNull()

    /** launchSmallFreeFormWindow 的第 0 参是 ActivityRecord，其 taskId 再反查包名 */
    private fun stackPkgOfTaskArg(chain: XposedInterface.Chain, idx: Int): String? = runCatching {
        val arg = chain.getArg(idx)
        val tid = field(arg, "taskId") ?: return@runCatching null
        val svc = field(chain.thisObject, "mMiuiFreeFormManagerService") ?: return@runCatching null
        val stack = call(svc, "getMiuiFreeFormActivityStack", tid) ?: return@runCatching null
        call(stack, "getStackPackageName") as? String
    }.getOrNull()

    /**
     * 把一个正在启动的 Activity 改回"贴边小窗"模式。
     * 与 HyperCeiler 一致：**同包跳转直接放行**，避免全屏应用内部跳页被误变。
     */
    /**
     * ⚠️ LibXposed API 102 的 `Chain` **没有 setResult()** ——
     *    改写返回值只能由 Hooker **return 新值**。
     *    所以本函数返回"是否已改写"，由调用方决定 return 什么。
     */
    /**
     * v0.6.3 新增：**本机 ROM 的真实签名**（3 参）
     *   `modifyLaunchActivityOptionIfNeed(RootWindowContainer, ActivityOptions, Intent) -> ActivityOptions`
     *
     * 语义：调用方已经算出 options，我们在它基础上按"这个包是不是贴边小窗"决定要不要改。
     */
    private fun patchLaunchOptions3(chain: XposedInterface.Chain, result: Any?): Any? {
        val args = runCatching { chain.args }.getOrNull() ?: return result
        if (args.size != 3) return result
        val opts = args[1] as? ActivityOptions ?: return result
        val intent = args[2] as? Intent ?: return result
        val pkg = intent.component?.packageName ?: return result
        if (pkg in FW_BLACKLIST) return result
        if (!fwApps.containsKey(pkg)) return result
        Logx.once("sticky3-$pkg", "贴边前台: 命中贴边应用 $pkg 的启动（3 参路径）")
        val ctx = AppCtx.get() ?: return result
        return makeFreeformOptions(ctx, opts, pkg) ?: result
    }

    private fun patchLaunchOptions(chain: XposedInterface.Chain, result: Any?): Any? {
        val args = runCatching { chain.args }.getOrNull() ?: return result
        if (args.size != 8) return result
        val intent = args[5] as? Intent ?: return result
        val pkg = intent.component?.packageName ?: return result
        if (pkg in FW_BLACKLIST) return result

        // 同包跳转放行（关键防误伤：全屏应用内部跳页不能变成小窗）
        runCatching {
            val recIntent = field(args[7], "intent") as? Intent
            if (recIntent?.component?.packageName == pkg) return result
        }

        if (!fwApps.containsKey(pkg)) return result
        val opts = result as? ActivityOptions ?: return result
        val ctx = contextOf(args[0]) ?: return result
        val newOpts = makeFreeformOptions(ctx, opts, pkg) ?: return result
        Logx.once("sticky-$pkg", "贴边前台: 已将 $pkg 的新页面改回贴边小窗")
        return newOpts
    }

    private fun contextOf(arg: Any?): Context? = runCatching {
        (field(arg, "mContext") as? Context)
            ?: (field(field(arg, "mService"), "mContext") as? Context)
    }.getOrNull()

    /**
     * 构造"贴边小窗"的 ActivityOptions。
     * MIUI 在 `ActivityOptions` 上打 `mFreeformScale` / `mFreeformRect` 这类扩展字段，
     * 逐项反射尝试，命中哪个用哪个；都不命中就返回原样（不破坏启动）。
     */
    private fun makeFreeformOptions(ctx: Context, src: ActivityOptions, pkg: String): ActivityOptions? = runCatching {
        // ActivityOptions.clone() 是 public Object clone() —— 必须显式强转
        val cloned = runCatching { src.javaClass.getMethod("clone").invoke(src) as? ActivityOptions }.getOrNull()
        cloned?.let { o ->
            val memo = fwApps[pkg]
            var ok = false
            runCatching {
                setField(o, "mFreeformScale", memo?.first ?: STICKY_SCALE); ok = true
            }
            runCatching {
                setField(o, "mIsFreeForm", true); ok = true
            }
            runCatching {
                setField(o, "mForceFreeform", true); ok = true
            }
            if (ok) o else null
        }
    }.getOrNull()

    private fun setField(o: Any, name: String, v: Any) {
        val f = o.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(o, v)
    }

    private fun field(o: Any?, name: String): Any? = runCatching {
        var c: Class<*>? = o?.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                return@runCatching f.get(o)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun call(o: Any?, name: String, vararg a: Any?): Any? = runCatching {
        if (o == null) return@runCatching null
        var c: Class<*>? = o.javaClass
        while (c != null) {
            val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == a.size }
            if (m != null) {
                m.isAccessible = true
                return@runCatching m.invoke(o, *a)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun XposedInterface.Chain.getArgOrNull(i: Int): Any? =
        runCatching { getArg(i) }.getOrNull()
}
