package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * system_server 侧「小窗几何权威」探针（v0.4.56）。
 *
 * 目的：查明**谁在横屏后把模块写入的矩形覆盖回竖屏**。
 *
 * 已知（静态逆向 `miui-services.jar` + 运行时取证）：
 *   · 运行时动画序列（02:39:45）显示 MIUI 用自己的 scaleBounds 把高从 1374 收敛回 1675（竖屏）
 *   · `TransitionImpl#restoreFreeformWindowBounds(ArrayList, SurfaceTransaction)` 会重建小窗几何
 *   · `TransitionImpl#clipFreeformBounds(TransitionInfo, WindowContainer, SurfaceTransaction)`
 *   · `MiuiFreeFormManagerService#getMiuiFreeformBounds`
 *   · `MiuiFreeFormStackDisplayStrategy` 是小窗尺寸算子的宿主（前轮已定位）
 *
 * 本探针**只打日志、不改变行为**：把候选类的全部方法名 + 调用栈打出来，
 * 用来确认"到底是哪个方法在重算"，再据实写精准修复
 * （避免像前几轮那样按猜测的名字挂、结果「共挂 0 个」）。
 *
 * ⚠️ system 作用域钩子**只在开机注入** —— 改完必须重启 zygote 才看得到日志。
 */
object SysGeoProbe {

    /** 几何权威候选类（system_server 侧）。 */
    private val CLASSES = listOf(
        // ★★★ v0.4.59（真机探针 + smali 反汇编修正后的**真实几何来源**）：
        //   `LaunchParamsController.getFreeformLastPosition(Task)` / `getFreeformLastScale(Task)`
        //   —— MIUI 按 **Task 实例**保存"上次的小窗位置/缩放"；新任务没有记录 ⇒ 回退整屏。
        //   反汇编证据（MiuiFreeFormManagerService.smali:10460）：
        //     ActivityTaskSupervisor.getLaunchParamsController().getFreeformLastPosition(task)
        //   ⇒ 这才是该 hook 的地方。而 MiuiFreeFormStackDisplayStrategy 只有 5 个方法（数量策略），
        //     isLandcapeFreeform 装了但从不触发 —— 都不是几何来源。
        "com.android.server.wm.LaunchParamsController",
        "com.android.server.wm.LaunchParamsControllerImpl",
        "com.android.server.wm.MiuiFreeFormManagerService",
        "com.android.server.wm.MiuiFreeFormActivityStack"
    )

    /** 与小窗几何/方向相关的方法名关键词 —— 只有这些挂 log-only 钩子。 */
    private val KEYWORDS = listOf(
        "Bounds", "bounds", "Freeform", "FreeForm", "freeform",
        "Rect", "rect", "Scale", "scale", "Layout", "layout",
        "Rotation", "rotation", "Orientation", "orientation"
    )

    fun install(m: MainHook, cl: ClassLoader): Int {
        var hooked = 0
        CLASSES.forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull()
            if (c == null) {
                Logx.always("几何探针: 类不存在 $cn")
                return@forEach
            }
            val names = c.declaredMethods.map { it.name }.distinct().sorted()
            // 压成一行：Xposed 日志有 400 行上限，逐行会被截掉（本项目踩过）
            Logx.always(
                "几何探针[${cn.substringAfterLast('.')}]: 方法数=${c.declaredMethods.size} " +
                    "全部=${names.joinToString(",")}"
            )
            c.declaredMethods
                .filter { mm -> KEYWORDS.any { k -> mm.name.contains(k) } }
                .forEach { mm ->
                    val sig = cn.substringAfterLast('.') + "#" + mm.name +
                        mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                    val argc = mm.parameterTypes.size
                    val isVoid = mm.returnType == Void.TYPE
                    if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                            val res = chain.proceed()
                            runCatching {
                                val a = (0 until argc).joinToString(",") { i ->
                                    val v = chain.getArg(i)
                                    v?.toString()?.take(60) ?: "null"
                                }
                                val st = Throwable().stackTrace.drop(2).take(7)
                                    .joinToString(" <- ") {
                                        "${it.className.substringAfterLast('.')}.${it.methodName}"
                                    }
                                Logx.always("几何[$sig]: args=($a) -> ${if (isVoid) "void" else res} caller: $st")
                            }
                            res
                        })) {
                        hooked++
                    }
                }
        }
        Logx.always("几何探针: 共挂 $hooked 个钩子")
        return hooked
    }
}
