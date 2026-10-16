package com.abel.os4freeformx

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 各进程的 hook 实现。
 *
 * system_server：`MiuiMultiWindowUtils.getFreeformRect`（小窗打开时唯一的 bounds 收敛点）→ 套用记住的尺寸。
 * systemui：`MiuiDecoration*`（wm shell 小窗装饰）→ 沉浸底栏（不绘制上下栏 + 不再占用 insets）+ 记录尺寸。
 */
object Hooks {

    private const val MODE_FREEFORM = 5





    fun installSystemServer(m: MainHook, cl: ClassLoader) {
        Logx.always("installSystemServer: remember=${Cfg.rememberBounds}")
        installLaunchBounds(m, cl)
        runCatching { installSystemFreeformProbe(m, cl) }.onFailure { Logx.e("系统侧探针失败", it) }

        // ★★★ 2026-10-15 移植自 HyperCeiler 的三项小窗增强（AGPL-3.0，见 FreeformExtra.kt）。
        //   全部落在 system_server：
        //     · 不限制小窗数量（MiuiFreeFormStackDisplayStrategy#getMaxMiuiFreeFormStackCount）
        //     · 贴边小窗强制前台（MiuiFreeFormGestureController#needForegroundPin）
        //     · 强制允许应用以小窗打开（ActivityStarter#executeRequest）
        //   安装期只装 hook，不碰 Context / prefs（与上面同一条纪律）。
        runCatching { FreeformExtra.installMultiFreeform(m, cl) }
            .onFailure { Logx.e("多小窗安装失败", it) }
        runCatching { FreeformExtra.installEdgeFront(m, cl) }
            .onFailure { Logx.e("贴边前台安装失败", it) }
        runCatching { FreeformExtra.installForceFreeform(m, cl) }
            .onFailure { Logx.e("强制小窗安装失败", it) }

        // ★★★ v0.4.56：小窗几何权威探针（system_server 侧）。
        //   目的：查明横屏后被 MIUI 覆盖回竖屏的**真正方法**。只打日志、不改行为。
        //   需要重启 zygote 才生效（system 作用域只在开机注入）。
        // ★ v0.4.61：几何探针（198 个钩子）**默认关闭** —— 它已经把该拿的信息给到了
        //   （ROM 真实方法名、freeform 栈字段、数量上限签名、几何存储位置），
        //   继续常开只会用大量日志淹没真正的信号。需要时把下面的开关打开即可。
        if (Constants.DBG_GEO_PROBE) {
            runCatching { SysGeoProbe.install(m, cl) }
                .onFailure { Logx.e("几何探针安装失败", it) }
        }

        // ★★★ v0.4.59：**方向修复的正解** —— 用 HyperOS 4 **自带**的「强制横屏名单」机制。
        //   smali 反汇编（MiuiFreeFormActivityStack.smali:229-231）证明 ROM 自己会读
        //   `MiuiMultiWindowAdapter.getForceLandscapeApplication()` 并据此置横屏。
        //   把模块记忆为横屏的应用并入该名单即可 —— 不改 MIUI 几何，故不会被它重算覆盖。
        runCatching { FreeformExtra.installForceLandscape(m, cl) }
            .onFailure { Logx.e("强制横屏安装失败", it) }

        // ★★★ v0.4.79：**小窗内锁定方向**（system_server 侧）——
        //   解决「小红书切横屏后，点开内容二级菜单又变回竖屏」。
        //   应用内跳转会请求竖屏，MIUI 会据此把 mIsLandcapeFreeform 改回 false
        //   （MiuiFreeFormManagerService.setRequestedOrientation）。这里拦下来纠正。
        runCatching { FreeformExtra.installLockFreeformOrientation(m, cl) }
            .onFailure { Logx.e("锁定方向安装失败", it) }

        // ★★★ v0.4.65：几何**持久化**（system_server 侧）—— 把模块摆放好的小窗几何
        //   通过 ROM 自己的链路（Task.infiniteResizeFinish）写进 MIUI 存储。
        //   这是"横屏被弹回/旋转后窗口丢失"两个现象的共同根因所在。
        runCatching { FreeformExtra.installGeoPersist(m, cl) }
            .onFailure { Logx.e("几何持久化安装失败", it) }

        // 【已放弃】system_server 侧清零分屏应用 insets 的尝试：
        //   探针证明 WindowState 上没有任何返回 Insets 的方法，且 system_server 里
        //   InsetsState.calculateInsets 只有整屏调用 → 应用的 insets 是在**应用进程**里算的，
        //   本模块 scope（system + systemui）够不到。相关钩子已全部撤除，避免干扰正常布局。

        // 注意：**绝不能在 system_server 启动阶段碰 Context / ContentResolver**。
        // 之前的“预热线程”在 onSystemServerStarting 里就调 contentResolver.call()，
        // 提前触发 SystemServiceRegistry 创建 MediaRouter → DisplayManagerGlobal 仍为 null
        // → MediaProjectionManagerService 构造抛 NPE → system_server 崩溃 → LSPosed 进安全模式
        // （2026-09-19 真机：dropbox system_server_crash ×2 + SYSTEM_RESTART）。
        // 现在只装 hook；配置与 bounds 都在第一次真正用到时（小窗打开，远晚于开机）惰性读取。
    }

    /** SystemUI：小窗装饰 → 2.4 沉浸底栏；三点菜单 → 比例调整；手势结束 → 2.2/2.3 记忆 */
    fun installSystemUi(m: MainHook, cl: ClassLoader) {
        Cfg.reload()
        Logx.always("installSystemUi: immersive=${Cfg.immersive} remember=${Cfg.rememberBounds}")
        installLaunchBounds(m, cl)
        runCatching { installWctProbe(m, cl) }.onFailure { Logx.e("WCT 探针失败", it) }
        runCatching { installRotationGeometryHook(m, cl) }.onFailure { Logx.e("旋转几何钩子失败", it) }
        installImmersive(m, cl)
        installRatioMenu(m, cl)
        installBoundsRecorder(m, cl)
        installSplitRatio(m, cl)
        // ★★ 手势功能已整体移除（用户 2026-10-02）。
        //    原因：角滑必须"参与/吞掉 MIUI 手势流的事件"才能屏蔽系统手势，
        //    而这条链路在真机上反复导致
        //      `ANR in com.android.systemui [Gesture Monitor] MultiTaskSwitch ... action=MOVE`
        //      → `Process com.android.systemui has died`（多次实测，最后一次仍复发）。
        //    结论：只要 hook 住那条流，就有崩 SystemUI 的风险 —— 收益远小于代价，故整体关停。
        //    （保留 Gestures.kt 源码备查，但不再安装任何挂钩/通道。）
        Logx.always("手势功能已移除：不安装任何输入挂钩（角滑下线）")
        // 小白条（手势导航条）：跟随手势 / 淡入淡出 / 触摸显隐 / 空闲自动隐藏
        installGestureHandle(m, cl)
        // 通知「下拉开小窗」相关功能已整体移除（用户 2026-10-02 决定放弃）：
        //   原因：试过 HyperCeiler 的 canSlide 置位 + 名单补足 + 代理包还原 + 强制刷新 + 入口探针，
        //   真机探针证实「长按焦点通知」**未进入** ModalController/小白条链路（探针零调用）
        //   ⇒ 该系统交互在本 ROM 上未启用，继续做等于自建整套系统 UI（风险与前次手势同级）。
        //   仍然可用的能力：通知**下拉即可开小窗**（系统自带，无需本模块介入）。
        Logx.always("本模块不介入通知小窗（系统自带的下拉开小窗不受影响）")

        // ★★★ v0.4.60：把「强制横屏」也装到 **SystemUI 侧**（用户要求：能只 killall 就别重启）。
        //   说明：真正决定 MIUI 方向的是 system_server 里的 MiuiFreeFormActivityStack，
        //   所以 SystemUI 侧这一份**不保证影响 MIUI 的判断**；但 android.util.MiuiMultiWindowAdapter
        //   来自 framework.jar，两端都可能被调用 ⇒ 装上它至少能覆盖 SystemUI 侧的调用点，
        //   且类不存在时只打一行日志、无任何副作用。
        runCatching { FreeformExtra.installForceLandscape(m, cl) }
            .onFailure { Logx.e("强制横屏(SystemUI)安装失败", it) }

        // 诊断探针：抓"双分屏 → 三分屏"真实手势调用的入口（方法名 + 参数）
        runCatching { SplitTrace.install(m, cl) }.onFailure { Logx.e("SplitTrace 安装失败", it) }
        // 诊断探针：枚举 MIUI 小窗"尺寸/动画"链路的类与方法（旋转后比例从哪来 —— 只为定位，不改行为）
        runCatching { installSizeChainProbe(cl) }.onFailure { Logx.e("尺寸链探针安装失败", it) }
        // ★ 主修复：在 MIUI 算 (bounds, scale) 的地方换成记忆值
        runCatching { installSizeLevelHook(m, cl) }.onFailure { Logx.e("尺寸档位挂钩安装失败", it) }
        runCatching { installSizePathProbe(m, cl) }.onFailure { Logx.e("路径探针安装失败", it) }
    }

    /** 小白条（手势导航条）：跟随手势 / 淡入淡出 / 触摸显隐 / 空闲自动隐藏。 */
    private fun installGestureHandle(m: MainHook, cl: ClassLoader) {
        runCatching {
            GestureHandle.install(m, cl)
            Logx.always("installGestureHandle: 已挂载（总开关=${Cfg.gestureHandle}）")
        }.onFailure { Logx.e("installGestureHandle 失败", it) }
    }

    /** 手势总入口（幂等：安装期挂全局输入，动作见 [Gestures]）。 */
    private fun installGestures(m: MainHook, cl: ClassLoader) {
        runCatching {
            if (!Cfg.gestures) {
                Logx.always("installGestures: 手势总开关关闭，跳过")
                return@runCatching
            }
            Gestures.install(m, cl)
        }.onFailure { Logx.e("installGestures 失败", it) }
    }

    // ---------------- 分屏比例（HyperOS 4：DividerSnapAlgorithm 的吸附目标） ----------------

    /**
     * HyperOS 4 双应用分屏的把手吸附位来自 `DividerSnapAlgorithm#calculateTargets`，
     * 每个目标是 `SnapTarget(position, snapPosition)`，`snapPosition` 就是"比例 id"
     * （`SplitLayout#setDivideRatio(id)` 按它定位；拖动时按 position 就近吸附）。
     *
     * 这里先挂只读取证：把实际的目标列表打出来（positions / ids / 屏幕尺寸 / snapMode），
     * 才能确定 3.5:6.5（35%）该插在哪里、用什么 id。
     */
    private fun installSplitRatio(m: MainHook, cl: ClassLoader) {
        // 目标：双应用分屏可调 10%~90%、中线 5:5 磁吸、其余任意比例。
        // HyperOS 4 的比例由 DividerSnapAlgorithm 的"吸附目标"决定，两套实现：
        //   com.android.wm.shell.common.split.DividerSnapAlgorithm      （通用）
        //   com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm （折叠屏，本机走这套）
        // 不往 mTargets 插目标（构造函数按索引取 first/middle/last，插入会打乱特殊目标），
        // 而是挂在就近吸附 snap(position, z)：一处覆盖"求当前档位"和"松手落位"两条路径。
        listOf(Constants.CLS_DIVIDER_SNAP, Constants.CLS_SOSC_DIVIDER_SNAP).forEach { cls0 ->
            runCatching {
                m.hookMethod(cl, cls0, "snap", arrayOf(Integer.TYPE, java.lang.Boolean.TYPE),
                    XposedInterface.Hooker { chain ->
                        val pos = chain.getArg(0) as? Int
                        val ours = pos?.let { splitRatioTarget(chain.thisObject, it) }
                        val result = ours ?: chain.proceed()
                        // 比例记忆改挂在 snap 上（原先挂的 setDividerPosition 拖动里 0 命中，记忆从没写进去）
                        // ★ 分屏不再记忆（用户要求：只记每个应用的小窗尺寸 ✓）
                        if (ours != null) {
                            Logx.once("split-hit-${field(ours, "snapPosition")}",
                                "分屏吸附: $pos -> 自定义档 position=${field(ours, "position")}")
                        }
                        result
                    })
            }.onFailure { Logx.e("挂分屏比例吸附失败($cls0)", it) }
        }

        // 折叠屏这条链最后会由 SoScUtilsImpl.findSnapTarget(...) 按"分屏状态机"把结果
        // 重新映射回 id 1/2/0（真机：拖到 831=35% 被改成 5:5）。所以在这里把我们自己的档位放行。
        runCatching {
            m.hookMethod(cl, "com.android.wm.shell.sosc.SoScUtilsImpl", "findSnapTarget", null,
                XposedInterface.Hooker { chain ->
                    val upstream = chain.getArg(2)
                    val id = upstream?.let { field(it, "snapPosition") } as? Int
                    if (id != null && id in 101..106) upstream else chain.proceed()
                })
        }.onFailure { Logx.e("挂 SoScUtilsImpl.findSnapTarget 失败", it) }


        runCatching {
            m.hookMethod(cl, "com.android.wm.shell.sosc.SoScStageCoordinator", "onLayoutSizeChanged",
                null,
                XposedInterface.Hooker { chain ->
                    lastCoordinator = chain.thisObject
                    splitActiveAt = android.os.SystemClock.elapsedRealtime()
                    val r = chain.proceed()
                    // ★ 分屏不再恢复记忆（同上 ✓）
                    r
                })
        }.onFailure { Logx.e("挂分屏比例记忆(恢复)失败", it) }

        // 多分屏（3 个及以上应用，SoSc 的 activeStages >= 3）时，隐藏每个应用顶部的三点
        // 与底部手势/导航条 —— 与自由小窗同一套"只不绘制、保留触摸"的做法。
        // 两分屏保持官方原样（用户明确要求恢复官方遮罩）。
        runCatching {
            val decorCls = cls("com.android.wm.shell.sosc.SoScSplitDecorManager")
            val wanted = setOf("inflate", "onResizing", "onResized", "drawNextVeilFrameForSwapAnimation")
            decorCls.declaredMethods.filter { it.name in wanted && !it.isSynthetic }.forEach { meth ->
                runCatching {
                    m.hookMethod(cl, "com.android.wm.shell.sosc.SoScSplitDecorManager", meth.name,
                        meth.parameterTypes,
                        XposedInterface.Hooker { chain ->
                            val r = chain.proceed()
                            runCatching { hideSoScDecor(chain.thisObject, chain) }
                            // 恢复比例：onLayoutSizeChanged 真机不触发，改在分屏装饰 inflate 时异步做一次
                            if (meth.name == "inflate") {
                                // inflate 那一刻两个 stage 往往还没就位（真机：取不到应用组合 → 跳过），
                                // 所以隔几次重试；restoreSplitRatio 自身有 2 秒限频，不会打架。
                                listOf(600L, 1_500L, 3_000L).forEach { d ->
                                    Handler(Looper.getMainLooper()).postDelayed(
                                        { /* 分屏不再恢复记忆 ✓ */ }, d
                                    )
                                }
                            }
                            r
                        })
                }.onFailure { Logx.e("挂多分屏 ${meth.name} 失败", it) }
            }
        }.onFailure { Logx.e("遍历 SoScSplitDecorManager 失败", it) }

        // 拖动时的"白屏替身"：screenshotIfNeeded/setScreenshotIfNeeded 会抓一张 mHostLeash 快照
        // reparent 上来顶替真实内容（真机抓到的是白帧）→ 跑完即 remove。
        listOf("screenshotIfNeeded", "setScreenshotIfNeeded").forEach { name ->
            runCatching {
                m.hookMethod(cl, "com.android.wm.shell.sosc.SoScSplitDecorManager", name, null,
                    XposedInterface.Hooker { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val tx2 = (chain.getArg(0) as? android.view.SurfaceControl.Transaction)
                                ?: (chain.getArg(1) as? android.view.SurfaceControl.Transaction)
                            val shot = field(chain.thisObject, "mScreenshot") as? android.view.SurfaceControl
                            if (tx2 != null && shot != null) {
                                tx2.javaClass.getMethod("remove", android.view.SurfaceControl::class.java)
                                    .invoke(tx2, shot)
                            }
                        }
                        r
                    })
            }.onFailure { Logx.e("挂 $name 失败", it) }
        }

        // 【已放弃的实验，勿重试】让拖动时两侧内容逐帧重排：
        //   1) 强制 SoScStageCoordinator.updateWindowBounds 返回 true —— 无效，它本来就返回 true
        //      （慢拖 1~2 秒只被调用 6 次，逐帧反馈走 surface 级 updateSurfaceBounds）；
        //   2) 在 DividerView.onTouch / setDividerPosition 里补一次全量重排 —— 同步会重入
        //      shell 的 resize 流程，真机表现为**分隔条卡住无法拖动**（异步+限频也救不回来）。
        // 结论：拖动期间两侧应用被 OS 有意冻结（官方遮罩就是为此而生），保持官方行为。
    }

    private fun splitRatioTarget(o: Any, pos: Int): Any? = runCatching {
        splitActiveAt = android.os.SystemClock.elapsedRealtime()
        val leftRight = field(o, "mIsLeftRightSplit") as? Boolean ?: true
        val display = (if (leftRight) field(o, "mDisplayWidth") else field(o, "mDisplayHeight")) as? Int
            ?: return null
        val divider = (field(o, "mDividerSize") as? Int) ?: 0
        val insets = field(o, "mInsets") as? Rect ?: return null
        val start = if (leftRight) insets.left else insets.top
        val span = display - start - (if (leftRight) insets.right else insets.bottom)
        if (span <= 0) return null
        val magnet = (span * 0.02f).toInt()          // 精确档磁吸半径：2% 跨度
        val midMagnet = (span * 0.05f).toInt()       // 中间 5:5 磁吸：5% 跨度
        val edgeMagnet = (span * 0.05f).toInt()      // 两侧 1:9 / 9:1 磁吸：放宽到 5% 跨度
        val target = { p: Int, id: Int -> buildSnapTarget(o, p, id) }

        // 注意：firstObj/lastObj/midObj 是**官方 SnapTarget 对象**（要原样返回，带官方 snapPosition），
        // first/last/mid 只是它们的位置数值。上一版误把数值 return 出去 → snap() 返回 Integer，
        // 调用方要 SnapTarget → ClassCastException 闪退（真机：拖到 10% 必崩）。
        val firstObj = field(o, "mFirstSplitTarget")
        val lastObj = field(o, "mLastSplitTarget")
        val midObj = field(o, "mMiddleTarget")
        val first = firstObj?.let { field(it, "position") as? Int }
        val last = lastObj?.let { field(it, "position") as? Int }
        val mid = midObj?.let { field(it, "position") as? Int }

        // ① 两侧台阶：
        //    1%~15% 一律吸到 10%（官方 firstSplitTarget），85%~99% 吸到 90%（lastSplitTarget）；
        //    这是用户要的"边缘 1~15% 都自动归到 10% 台阶"。
        val loEdge = start + (span * 0.01f).toInt()
        val hiEdge = start + (span * 0.99f).toInt()
        // 用户要求：25% 以内一律磁吸到 10% 台阶（镜像侧：75% 以外 → 90%）。左右分屏同理。
        val loStep = start + (span * 0.25f).toInt()
        val hiStep = start + (span * 0.75f).toInt()
        // 返回**官方自己的目标对象**（带官方 snapPosition），不要自造 id：
        // 否则会绕过分屏状态机，官方"点击边缘切换比例"就失效（真机反馈）。
        if (firstObj != null && first != null && pos > loEdge && pos <= loStep) return firstObj
        if (lastObj != null && last != null && pos >= hiStep && pos < hiEdge) return lastObj

        // ② 拉满 / 甩动：完全交还官方 —— 官方手势负责全屏、退出分屏等（1% 以内 / 99% 以外）
        if (pos <= loEdge || pos >= hiEdge) return null

        // ③ 中线 5:5 磁吸        // ② 中线 5:5 磁吸
        // 返回官方 5:5 目标对象（带官方 id，状态机才认得出这是 5:5）
        if (midObj != null && mid != null && kotlin.math.abs(pos - mid) <= midMagnet) return midObj

        // ③ 精确 3.5:6.5 / 6.5:3.5（小半径磁吸）
        for ((fraction, id) in listOf(0.35f to 101, 0.65f to 102)) {
            val p = start + (span * fraction).toInt() - divider / 2
            if (kotlin.math.abs(pos - p) <= magnet) return target(p, id)
        }

        // ④ 其余位置：松手即落点 → 任意比例
        target(pos, 103)
    }.onFailure { Logx.e("构造分屏吸附目标失败", it) }.getOrNull()


    // ---------------- 多分屏（≥3 应用）栏隐藏 ----------------

    @Volatile private var lastCoordinator: Any? = null


    /** 当前是否存在分屏（>=2 个 stage）。 */
    private fun splitActive(): Boolean = runCatching {
        val coord = lastCoordinator ?: return false
        val op = field(coord, "mStageOrderOperator") ?: return false
        val active = op.javaClass.getMethod("getActiveStages").invoke(op) as? List<*>
        (active?.size ?: 0) >= 2
    }.getOrDefault(false)

    /** 当前是否处于"3 个及以上应用"的多分屏（两分屏保持官方外观）。 */
    private fun multiSplitActive(): Boolean = runCatching {
        val coord = lastCoordinator ?: return false
        val op = field(coord, "mStageOrderOperator") ?: return false
        val active = op.javaClass.getMethod("getActiveStages").invoke(op) as? List<*>
        (active?.size ?: 0) >= 3
    }.getOrDefault(false)

    /**
     * 隐藏**多分屏（3 个及以上应用）**里每个应用顶部的三点栏与底部手势/导航条。
     * 2 分屏保持官方原样（用户明确要求，见 line 126）；且 2 分屏若抑制装饰 surface 的 show
     * 会让 dissolve 重组卡死 shell 主线程，故此处用 [multiSplitActive] 门控。
     *
     * 两层一起处理：
     *  视图层：mTextView（"上/下分屏"文字）、mVeilIconView（圆角遮罩+应用图标）、mViewHost 根布局 → GONE
     *  surface 层：mIconLeash / mBackgroundLeash（拖动白底） / mScreenshot / mSoScScreenshot（快照替身）→ hide
     * 视图与触摸逻辑保留，分隔条/把手完全不动。
     */
    private fun hideSoScDecor(decor: Any, chain: XposedInterface.Chain) {
        runCatching {
            // ⚠️ 只处理**真·多分屏（3+）**：2 分屏按用户要求保持官方原样（line 126）。
            // 之前没门控，导致 2 分屏的装饰被藏、且 show hook 抑制其装饰 surface，
            // 退出 2 分屏(dissolve)时重组 transition 卡死 shell 主线程 → 第二次四指被系统
            // MultiTaskSwitch 监视器接管后死锁 → SystemUI 重启（2026-09-21 真机复现）。
            if (!multiSplitActive()) return@runCatching
            (field(decor, "mVeilIconView") as? android.view.View)?.let {
                it.visibility = android.view.View.GONE
            }
            (field(decor, "mTextView") as? android.view.View)?.let {
                it.visibility = android.view.View.GONE
            }
            val host = field(decor, "mViewHost")
            (host?.javaClass?.getMethod("getView")?.invoke(host) as? android.view.View)?.visibility =
                android.view.View.GONE

            // 安全取参：部分被 hook 的方法参数不足 3 个，直接 getArg(2)/(3) 会抛
            // ArrayIndexOutOfBoundsException: length=2; index=2（已被 runCatching 兜住，
            // 但会导致装饰 surface 没藏掉）。这里用 runCatching 兜底，越界就当没有 Transaction。
            val tx = runCatching { chain.getArg(2) }.getOrNull() as? android.view.SurfaceControl.Transaction
                ?: runCatching { chain.getArg(3) }.getOrNull() as? android.view.SurfaceControl.Transaction
            if (tx != null) {
                val hide = tx.javaClass.getMethod("hide", android.view.SurfaceControl::class.java)
                // 注意：**不要** hide mHostLeash（拖动时快照/覆盖层的宿主，藏了会两侧黑屏——已踩）。
                // 镜像栏本身由 SoScShapeView.onDraw 不绘制来处理。
                listOf("mIconLeash", "mBackgroundLeash", "mScreenshot", "mSoScScreenshot").forEach { n ->
                    (field(decor, n) as? android.view.SurfaceControl)?.let { hide.invoke(tx, it) }
                }
            }
        }.onFailure { Logx.once("ms-decor", "隐藏分屏装饰失败: $it") }
    }

    // ---------------- 分屏比例记忆 ----------------

    /** 上次尝试恢复该组合比例的时间（限频用）。 */
    private val splitRatioRestoredAt = ConcurrentHashMap<String, Long>()

    /** 反射写 Rect 字段（多分屏 root 边界铺满用）。 */
    private fun setRectField(o: Any, name: String, v: Rect) {
        runCatching {
            var c: Class<*>? = o.javaClass
            while (c != null) {
                c.declaredFields.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true
                    val cur = it.get(o)
                    if (cur is Rect) cur.set(v) else it.set(o, Rect(v))
                    return
                }
                c = c.superclass
            }
        }
    }

    /** 反射写 int 字段（多分屏预留高度归零用）。 */
    private fun setIntField(o: Any, name: String, v: Int) {
        runCatching {
            var c: Class<*>? = o.javaClass
            while (c != null) {
                c.declaredFields.firstOrNull { it.name == name }?.let {
                    it.isAccessible = true
                    it.setInt(o, v)
                    return
                }
                c = c.superclass
            }
        }
    }

    /** 分屏最近一次活跃时间（用于把 setAppBounds 改写限定在分屏场景）。 */
    @Volatile private var splitActiveAt = 0L

    /** 已置不可见的多分屏 UI 视图（日志去重）。 */
    private val msHidden = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 取分屏里两个 Stage 的包名（按名字排序，作为这组分屏的标识）。 */
    /** 从常驻单例取 coordinator 并恢复该组合的分屏比例（分屏建立后调用）。 */
    private fun restoreSplitRatioFromSingleton() {
        runCatching {
            val impl = cls("com.android.wm.shell.sosc.SoScUtilsImpl").getMethod("getInstance").invoke(null)
            val coord = field(impl, "mStageCoordinator") ?: return
            lastCoordinator = coord
            restoreSplitRatio(coord)
        }.onFailure { Logx.once("rsr-single", "恢复入口失败: $it") }
    }

    private fun splitPair(coord: Any): String? = runCatching {
        val pkgs = listOf("mMainStage", "mSideStage").mapNotNull { name ->
            val stage = field(coord, name) ?: return@mapNotNull null
            val info = call(stage, "getRunningTaskInfo")
                ?: field(stage, "mRootTaskInfo")
                ?: call(stage, "getTopChildTaskInfo")
            info?.let { taskPkg(it) }
        }.filter { it.isNotEmpty() }
        if (pkgs.size < 2) null else pkgs.sorted().joinToString("+")
    }.getOrNull()

    /** 记录当前分屏比例（拖动定档时调用）。 */
    /**
     * 从 snap 的最终落位记录该"应用组合"的分屏比例。
     * `setDividerPosition` 在拖动里不会被调用（真机 0 命中），所以记录必须挂在 snap 上。
     */
    private fun recordRatioFromSnap(algorithm: Any, target: Any?) {
        if (target == null) return
        val span = layoutSpan(algorithm) ?: return
        val p = field(target, "position") as? Int ?: return
        val ratio = ((p - span.first).toFloat() / span.second).coerceIn(0f, 1f)
        if (ratio < 0.02f || ratio > 0.98f) return
        // 50% 是系统默认值（用户没动过把手时也会算出来）→ 不记录，否则会把 10%/90% 覆盖掉。
        if (kotlin.math.abs(ratio - 0.5f) < 0.01f) return
        val ctx = AppCtx.get() ?: return
        val impl = runCatching {
            cls("com.android.wm.shell.sosc.SoScUtilsImpl").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: return
        val coord = field(impl, "mStageCoordinator") ?: return
        val pair = splitPair(coord)
        if (pair == null) {
            Logx.once("split-pair-null", "分屏比例记忆: 取不到应用组合（跳过记录）")
            return
        }
        val screen = Bounds.screenKey(ctx)
        val key = "split:$pair"
        val permille = (ratio * 1000).toInt().coerceIn(0, 1000)
        val old = Bounds.get(ctx, key, screen)
        if (old == null || old.left != permille) {
            Bounds.put(ctx, key, screen, Rect(permille, 0, permille + 1, 1))
            Logx.e("分屏比例记忆: $pair -> ${permille / 10f}%（$screen）")
        }
    }


    /** 重建这组分屏时套回记忆里的比例（异步投递，避免重入把分隔条卡住）。 */
    private fun restoreSplitRatio(coord: Any) {
        val ctx = AppCtx.get() ?: return
        val pair = splitPair(coord) ?: run {
            Logx.once("rsr-pair", "分屏比例恢复: 取不到应用组合（跳过）")
            return
        }
        val screen = Bounds.screenKey(ctx)
        val key = "split:$pair"
        // 不能"每进程只恢复一次"（第二次进同一组分屏就不会恢复了，真机：又变回 5:5）。
        // 改成限频：同一 key 2 秒内只尝试一次，且当前比例与记忆差异 >2% 才动。
        val mark = "$key|$screen"
        val now = android.os.SystemClock.elapsedRealtime()
        val last = splitRatioRestoredAt[mark] ?: 0L
        if (now - last < 2_000) return
        splitRatioRestoredAt[mark] = now
        val memo = Bounds.get(ctx, key, screen) ?: run {
            Logx.once("rsr-memo", "分屏比例恢复: 记忆里没有 $key|$screen")
            return
        }
        val want: Float = memo.left / 1000f
        val layout = field(coord, "mSplitLayout") ?: run {
            Logx.once("rsr-layout", "分屏比例恢复: coord 上没有 mSplitLayout（字段名可能不同）")
            return
        }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val now = dividerRatio(layout)
                if (now != null && kotlin.math.abs(now - want) < 0.02f) {
                    Logx.once("rsr-eq", "分屏比例恢复: 已经接近记忆值（$now ≈ $want），不动作")
                    return@runCatching
                }
                val span = layoutSpan(layout) ?: return@runCatching
                val pos = span.first + (span.second * want).toInt()
                layout.javaClass.getMethod("setDividerPosition", Integer.TYPE, java.lang.Boolean.TYPE)
                    .invoke(layout, pos, true)
                Logx.e("分屏比例记忆: 已恢复 $pair -> ${(want * 100).toInt()}%")
            }.onFailure { Logx.e("恢复分屏比例失败", it) }
        }
    }

    /** 当前把手比例（0~1）。 */
    private fun dividerRatio(layout: Any): Float? {
        val span = layoutSpan(layout) ?: return null
        val pos = field(layout, "mDividerPosition") as? Int ?: return null
        if (span.second <= 0) return null
        return ((pos - span.first).toFloat() / span.second).coerceIn(0f, 1f)
    }

    /** 把手可动区间 (起点, 跨度)，与吸附目标同一套算法。 */
    private fun layoutSpan(layout: Any): Pair<Int, Int>? = runCatching {
        val leftRight = field(layout, "mIsLeftRightSplit") as? Boolean ?: true
        val display = (if (leftRight) field(layout, "mDisplayWidth") else field(layout, "mDisplayHeight")) as? Int
            ?: return null
        val insets = field(layout, "mInsets") as? Rect ?: return null
        val start = if (leftRight) insets.left else insets.top
        val span = display - start - (if (leftRight) insets.right else insets.bottom)
        if (span <= 0) null else start to span
    }.getOrNull()

    /** 按**现成目标**的类构造（通用版与 SoSc 版的 SnapTarget 是两个不同的内嵌类）。 */
    private fun buildSnapTarget(o: Any, position: Int, id: Int): Any? = runCatching {
        val sample = field(o, "mMiddleTarget") ?: field(o, "mFirstSplitTarget") ?: return null
        for (c in sample.javaClass.declaredConstructors) {
            val pts = c.parameterTypes
            val args: Array<Any?> = when {
                pts.size == 4 && pts[0] == o.javaClass -> arrayOf(o, position, id, 1.0f)
                pts.size == 3 && pts[0] == o.javaClass -> arrayOf(o, position, id)
                pts.size == 3 && pts[0] == java.lang.Integer.TYPE -> arrayOf(position, id, 1.0f)
                pts.size == 2 && pts[0] == java.lang.Integer.TYPE -> arrayOf(position, id)
                else -> continue
            }
            runCatching {
                c.isAccessible = true
                return c.newInstance(*args)
            }
        }
        null
    }.getOrNull()

    // ---------------- 2.2 打开小窗时套用记住的 bounds ----------------


    /**
     * 诊断（2026-10-06）：枚举 MIUI 小窗「尺寸 / 动画」链路的类与方法。
     *
     * 背景：旋转走 relayout，模块挂的 15 个**建窗**重载（`getFreeformRect`/`getCustomFreeformRect`）
     * 根本不被调用；而旋转时是 `MiuiFreeformModeAnimation` 在做那对
     * `setBounds` + `setMiuiFreeformInfoChange`（NOTES §40.3 记录的正是它）—— 它每次都按 MIUI
     * 自己的尺寸重算一遍，所以任何"事后推 bounds/scale"都会被它覆盖。
     * 目标：找到"算这一对值"的确切方法，在那里**把答案换成我们的记忆矩形**（§40.4 首选）。
     * 本函数只枚举、不挂钩，不改任何行为。
     */
    private fun installSizeChainProbe(cl: ClassLoader) {
        listOf(
            "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation",
            "com.android.wm.shell.multitasking.common.MultiTaskingSizeLevel",
            "com.android.wm.shell.multitasking.common.MultiTaskingSizeLevel\$LevelInfo",
            "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo",
            "com.android.wm.shell.multitasking.common.MultiTaskingCommonUtils",
            "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils",
            "com.android.wm.shell.multitasking.common.MultiTaskingPackageUtils"
        ).forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull()
            if (c == null) {
                Logx.always("尺寸链: 类不存在 $cn")
                return@forEach
            }
            Logx.always("尺寸链: $cn 方法数=${c.declaredMethods.size}")
            c.declaredMethods
                .filter { mm -> Regex("Bounds|Scale|Rotation|Anim|Size|Level").containsMatchIn(mm.name) }
                .take(60).forEach { mm ->
                Logx.always(
                    "   ${mm.name}${mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }} " +
                        "-> ${mm.returnType.simpleName}"
                )
            }
        }
    }


    /**
     * ★★★ 2026-10-06 主修复：**在 MIUI 自己算「目标 bounds + scale」的地方把答案换成记忆值**。
     *
     * 真机取证（尺寸链探针）：旋转后比例被切回原始，是因为 MIUI 在
     * `MultiTaskingSizeLevel#getDestBoundsAndScale(Rect)` 里重新算了一对
     * `(bounds, scale)`（返回 `LevelInfo`），而模块此前挂的 15 个重载都是**建窗**用的
     * （`getFreeformRect`/`getCustomFreeformRect`），relayout 根本不走它们 ⇒ 事后推 WCT 或重开都白搭。
     *
     * 做法（NOTES §40.4「首选」：在它算的时候给正确答案）：
     *   · **bounds 用记忆矩形**（先 `clampKeepRatio` 等比缩进当前可视区 ⇒ 比例不变、不越界）；
     *   · **scale 沿用 MIUI 自己算出来的那个**（不碰它的渲染缩放）；
     *   ⇒ 两者**成对**交给 MIUI，外框（bounds×scale）与内框（渲染）同源 —— 不会有白边；
     *     且不关窗、不重开 —— 不会有操作部位错位。
     */
    private fun installSizeLevelHook(m: MainHook, cl: ClassLoader) {
        val cls = runCatching {
            Class.forName("com.android.wm.shell.multitasking.common.MultiTaskingSizeLevel", false, cl)
        }.getOrNull() ?: run {
            Logx.e("尺寸档位: 找不到 MultiTaskingSizeLevel")
            return
        }
        val infoCls = runCatching {
            Class.forName("com.android.wm.shell.multitasking.common.MultiTaskingSizeLevel\$LevelInfo", false, cl)
        }.getOrNull()
        if (infoCls == null) {
            Logx.e("尺寸档位: 找不到 LevelInfo")
            return
        }
        // LevelInfo(Rect, float) 构造器
        val ctor = infoCls.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 2 &&
                c.parameterTypes[0] == Rect::class.java &&
                c.parameterTypes[1] == java.lang.Float.TYPE
        } ?: infoCls.declaredConstructors.firstOrNull()
        if (ctor == null) {
            Logx.e("尺寸档位: LevelInfo 无可用构造器")
            return
        }
        runCatching { ctor.isAccessible = true }
        var n = 0
        cls.declaredMethods.filter { it.name == "getDestBoundsAndScale" }.forEach { method ->
            if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                    val res = chain.proceed()
                    try {
                        val scale = res?.let { call(it, "getScale") as? Float } ?: 0f
                        val arg0 = chain.getArg(0) as? Rect
                        val cur = res?.let { call(it, "getBounds") as? Rect }
                        Logx.always("尺寸档位: getDestBoundsAndScale($arg0) -> bounds=$cur scale=$scale")
                        val ctx = AppCtx.get()
                        val pkg = lastFreeformPkg               // 最近一次见到的小窗应用（见 installLaunchBounds）
                        if (ctx != null && pkg != null && scale > 0f) {
                            val screen = Bounds.screenKey(ctx)
                            val memo = Bounds.get(ctx, pkg, screen) ?: Bounds.getAny(ctx, pkg)
                            if (memo != null) {
                                val dm = ctx.resources.displayMetrics
                                val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
                                val t = Bounds.clampKeepRatio(memo, area)
                                if (cur == null || cur.width() != t.width() || cur.height() != t.height() ||
                                    cur.left != t.left || cur.top != t.top
                                ) {
                                    val replaced = ctor.newInstance(t, scale)
                                    Logx.always("尺寸档位★: 用记忆替换 -> bounds=$t scale=$scale（$pkg）")
                                    return@Hooker replaced
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Logx.e("尺寸档位替换失败", t)
                    }
                    res
                })) {
                n++
                Logx.always("尺寸档位: hook 成功 getDestBoundsAndScale")
            }
        }
        Logx.always("尺寸档位: 共挂 $n 个")
    }


    /**
     * 诊断（2026-10-06 续）：对"尺寸档位/旋转"链路的其余候选做 **log-only** 探针，
     * 目的 = 抓出旋转时**真正执行**的那一步（`getDestBoundsAndScale` 实测未被调用）。
     * 只记日志，不改任何返回值。
     */
    private fun installSizePathProbe(m: MainHook, cl: ClassLoader) {
        val targets = listOf(
            "com.android.wm.shell.multitasking.common.MultiTaskingSizeLevel" to
                listOf("getSuitableLevelByBounds", "calBoundsForAutoResize", "scaleBounds", "adjustPosition"),
            "com.android.wm.shell.multitasking.common.MultiTaskingCommonUtils" to
                listOf("scaleBounds"),
            "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo" to
                listOf("beginRotation", "abortRotation"),
            "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils" to
                listOf("scaleDownIfNeeded", "getFreeformBounds", "getDefaultFreeformBounds")
        )
        var n = 0
        targets.forEach { (cn, names) ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: run {
                Logx.always("路径探针: 类不存在 $cn")
                return@forEach
            }
            c.declaredMethods.filter { it.name in names }.forEach { mm ->
                val sig = mm.name + mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                val argc = mm.parameterTypes.size
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val res = chain.proceed()
                        runCatching {
                            val a = (0 until argc).joinToString(",") { chain.getArg(it).toString() }
                            Logx.always("路径探针[$sig]: args=($a) -> $res")
                        }
                        res
                    })) {
                    n++
                    Logx.always("路径探针: hook 成功 ${cn.substringAfterLast('.')}#$sig")
                }
            }
        }
        Logx.always("路径探针: 共挂 $n 个")
    }


    /**
     * 诊断（2026-10-06）：**system_server 侧**的小窗状态类方法清单。
     *
     * 真机取证链：SystemUI 侧挂了 10 个候选（MultiTaskingSizeLevel / MultiTaskingCommonUtils /
     * MiuiFreeformModeTaskInfo / MiuiFreeformModeUtils）**旋转时一个都没触发** ⇒ 尺寸不在这里定。
     * 而在 `miui-services.jar` 里找到了 `com.android.server.wm.MiuiFreeFormActivityStack` ——
     * **WM 核心侧的小窗任务状态**，任务的真实 bounds 极可能由它决定。
     * ⚠️ system 作用域的钩子只在开机注入，本探针的日志要**框架级重启**后才看得到。
     */
    private fun installSystemFreeformProbe(m: MainHook, cl: ClassLoader) {
        val cls = runCatching {
            Class.forName("com.android.server.wm.MiuiFreeFormActivityStack", false, cl)
        }.getOrNull() ?: run {
            Logx.e("系统侧探针: 找不到 MiuiFreeFormActivityStack")
            return
        }
        // ★ 全部方法名压成**一行**：Xposed 日志有 400 行上限，逐行会像上次那样被截掉。
        val names = cls.declaredMethods.map { it.name }.distinct().sorted()
        Logx.always("系统侧探针: 方法数=${cls.declaredMethods.size} 全部方法名=${names.joinToString(",")}")
        // ★ 对候选方法挂 **log-only** 钩子：安装发生在开机，但触发在运行期 ⇒ logcat 一定抓得到
        // ★ 用**实测拿到的方法名**（上一轮按猜测的名字挂，结果「共挂 0 个」）
        val targets = listOf(
            "isSkipAutoLayout", "setSkipAutoLayout", "resolveTaskOrientation",
            "getFreeFormScale", "setFreeformScale", "setCornerPosition",
            "getEnterMiniFreeformRect", "setEnterMiniFreeformRect",
            "getPreExitFreeformScale", "setMiuiFreeformPreExitScale"
        )
        var n = 0
        // ★★ 2026-10-06 用户要求："确认为什么旋转会形变，去 hook 这个地方"。
        //   离线从 miui-services.jar 挖到的元凶候选：
        //     autoLayoutFreeFormStackIfNeed（需要就自动重排小窗）、removeFreeformParamsForAutoLayout、
        //     restoreFreeformWindowBounds、getMiuiFreeformBounds、clipFreeformBounds
        //   它们不在 MiuiFreeFormActivityStack（45 个方法里没有）⇒ 在其它 MiuiFreeForm* 类里，
        //   所以这里跨多个候选类按**名字**挂，并打出**调用栈**（谁在旋转时触发的，一目了然）。
        val autoNames = listOf(
            "autoLayoutFreeFormStackIfNeed", "removeFreeformParamsForAutoLayout",
            "restoreFreeformWindowBounds", "getMiuiFreeformBounds", "clipFreeformBounds",
            "getMiuiFreeformScale", "getFreeFormScale"
        )
        listOf(
            "com.android.server.wm.MiuiFreeFormGestureController",
            "com.android.server.wm.MiuiFreeFormActivityStackStub",
            "com.android.server.wm.MiuiFreeFormCameraStrategy",
            "com.android.server.wm.MiuiFreeFormManagerNotifier",
            "com.android.server.wm.MiuiFreeFormKeyCombinationHelper"
        ).forEach { cn2 ->
            val c2 = runCatching { Class.forName(cn2, false, cl) }.getOrNull() ?: return@forEach
            c2.declaredMethods.filter { it.name in autoNames }.forEach { mm ->
                val sig = cn2.substringAfterLast('.') + "#" + mm.name +
                    mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                val argc2 = mm.parameterTypes.size
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val res = chain.proceed()
                        runCatching {
                            val a = (0 until argc2).joinToString(",") { chain.getArg(it).toString() }
                            val st = Throwable().stackTrace.drop(2).take(6)
                                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                            Logx.always("形变探针[$sig]: args=($a) -> $res  caller: $st")
                        }
                        res
                    })) {
                    n++
                    Logx.always("形变探针: hook 成功 $sig")
                }
            }
        }
        cls.declaredMethods.filter { it.name in targets }.forEach { mm ->
            val sig = mm.name + mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
            val argc = mm.parameterTypes.size
            if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                    val res = chain.proceed()
                    runCatching {
                        val a = (0 until argc).joinToString(",") { chain.getArg(it).toString() }
                        Logx.always("系统探针[$sig]: args=($a) -> $res")
                    }
                    // ★★ 修复杆（2026-10-06）：旋转时 MIUI 会重排小窗、把比例切回它的默认。
                    //   `MiuiFreeFormActivityStack` 有 `isSkipAutoLayout/setSkipAutoLayout` ——
                    //   **有记忆就让 MIUI 跳过它自己的自动布局**，我们的尺寸/比例才不会被重排掉。
                    //   只在 `resolveTaskOrientation`（旋转专用入口）上做，避免影响其它流程。
                    if (mm.name == "resolveTaskOrientation") {
                        runCatching {
                            val pkg = call(chain.thisObject, "getStackPackageName") as? String
                            val ctx = AppCtx.get()
                            // ★ v0.4.38：这条守卫此前**静默失败**——两份分支各有一条日志，实测 5 份日志里
                            //   `系统探针★` 命中 0 次 ⇒ skipAutoLayout 杆从未生效过（查不到原因）。
                            //   现在把"为什么没生效"打出来（log-only，不改行为）。
                            if (pkg == null || ctx == null) {
                                Logx.always("系统探针: resolveTaskOrientation 守卫不成立 pkg=$pkg ctx=${ctx != null}（skipAutoLayout 杆未生效）")
                            }
                            if (pkg != null && ctx != null) {
                                val memo = Bounds.get(ctx, pkg, Bounds.screenKey(ctx))
                                if (memo != null) {
                                    chain.thisObject.javaClass
                                        .getMethod("setSkipAutoLayout", java.lang.Boolean.TYPE)
                                        .invoke(chain.thisObject, true)
                                    Logx.always("系统探针★: 有记忆 ⇒ setSkipAutoLayout(true) pkg=$pkg 记忆=$memo")
                                } else {
                                    Logx.always("系统探针: 无记忆，不动 skipAutoLayout pkg=$pkg")
                                }
                            }
                        }.onFailure { Logx.e("skipAutoLayout 杆失败", it) }
                    }
                    res
                })) {
                n++
                Logx.always("系统探针: hook 成功 #$sig")
            }
        }
        Logx.always("系统探针: 共挂 $n 个")
    }

    /**
     * ★★★ v0.4.43 正解（用户 2026-10-06 点破："能不能把记忆里的比例值直接就给它？"）——**能**。
     *
     * 反编译取证（`tools/dexscan.py` 扫 `Miui-WindowManager-Shell.jar`）：
     *   `com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils`
     *     `android.util.Pair calculateBoundsAndScaleAfterScreenRotation(
     *          Context, MiuiFreeformModeTaskInfo, DisplayLayout, int, int)`
     *   —— 方法名就是"算旋转后的 bounds 与 scale"。这就是**旋转时 MIUI 现算几何的那一步**。
     *
     * 之前所有方案（关闭重开 / WCT / skipAutoLayout 杆）都是"等它算完再纠正"，代价就是：
     * 闪一下原始比例、连续旋转时几条路互相打架。这里改成**在它算的这一步把记忆矩形直接给它**：
     * 不关窗、不重开、无过渡帧；连续旋转每次都会重新问一次 ⇒ 天然稳定。
     *
     * 返回 `java.util.Pair`（public 字段 `first`/`second`），反射写回 `first`(Rect)。
     * 任何异常都吞掉并退回 MIUI 原值 —— 宁可不改，也绝不把窗口搞坏。
     */
    private fun installRotationGeometryHook(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName(Constants.CLS_FREEFORM_MODE_UTILS, false, cl) }.getOrNull()
        if (cls == null) {
            Logx.e("旋转几何钩子: 找不到 ${Constants.CLS_FREEFORM_MODE_UTILS}")
            return
        }
        var n = 0
        cls.declaredMethods.filter { it.name == "calculateBoundsAndScaleAfterScreenRotation" }
            .forEach { method ->
                if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                        val res = chain.proceed()
                        runCatching {
                            Cfg.reloadThrottled()
                            if (!Cfg.rememberBounds) return@runCatching
                            val info = chain.getArg(1)
                            val pkg = info?.let { field(it, "mPackageName") } as? String
                            val first = res?.javaClass?.getField("first")?.get(res)
                            if (info == null || pkg == null || first !is Rect) return@runCatching
                            val ctx = (chain.getArg(0) as? Context) ?: AppCtx.get() ?: return@runCatching
                            val dispId = (field(info, "mDisplayId") as? Int) ?: -1
                            val screen = Bounds.screenKeyFor(ctx, dispId)
                            val memo = Bounds.get(ctx, pkg, screen) ?: Bounds.getAny(ctx, pkg)
                                ?: return@runCatching
                            val dm = ctx.resources.displayMetrics
                            val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
                            val target = Bounds.clampKeepRatio(memo, area)
                            val before = Rect(first)
                            if (before.left == target.left && before.top == target.top &&
                                before.width() == target.width() && before.height() == target.height()
                            ) {
                                Logx.always("旋转几何: MIUI 算的 $before 已符合记忆（$pkg）")
                            } else {
                                first.set(target)
                                Logx.always(
                                    "旋转几何: 直接给 MIUI 记忆矩形 $pkg " +
                                        "${before.width()}x${before.height()} -> " +
                                        "${target.width()}x${target.height()} @${target.left},${target.top}"
                                )
                            }
                        }.onFailure { Logx.e("旋转几何钩子执行失败（已退回 MIUI 原值）", it) }
                        res
                    })) {
                    n++
                    Logx.always(
                        "旋转几何钩子: 挂上 ${method.name}" +
                            method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                    )
                }
            }
        if (n == 0) Logx.e("旋转几何钩子: calculateBoundsAndScaleAfterScreenRotation 一个重载都没挂上")
    }

    /**
     * WCT 探针（log-only）：旋转后 MIUI 把新几何推给 shell 时，**必然经过一次
     * `WindowContainerTransaction#setBounds/setAppBounds`**。这里只记录"旋转后 8s 内"的每一次调用，
     * 目的是确认：旋转后的几何是否走 SystemUI 进程内的 WCT —— 若走，就能**改写 MIUI 自己的这次事务**
     * （而不是像 v0.4.10 那样自造一份 bounds），从而免重启、免关窗地把记忆矩形注入进去。
     * 只记录、不改行为。
     */
    private fun installWctProbe(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName("android.window.WindowContainerTransaction", false, cl) }
            .getOrNull()
        if (cls == null) {
            Logx.e("WCT 探针: 找不到 android.window.WindowContainerTransaction")
            return
        }
        var n = 0
        listOf("setBounds", "setAppBounds").forEach { name ->
            cls.declaredMethods.filter { it.name == name }.forEach { mm ->
                if (m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                        val res = chain.proceed()
                        runCatching {
                            val since = android.os.SystemClock.elapsedRealtime() - lastConfigChangeAt
                            if (lastConfigChangeAt > 0L && since in 0..8_000) {
                                val rect = (0 until chain.args.size)
                                    .map { chain.getArg(it) }
                                    .firstOrNull { it is Rect }
                                Logx.always("WCT探针[$name] +${since}ms: rect=$rect 参数=${chain.args.toList()}")
                            }
                        }
                        res
                    })) {
                    n++
                }
            }
        }
        Logx.always("WCT 探针: 共挂 $n 个 setBounds/setAppBounds")
    }

    private fun installLaunchBounds(m: MainHook, cl: ClassLoader) {
        val cls = runCatching { Class.forName(Constants.CLS_MULTIWINDOW_UTILS, false, cl) }.getOrNull() ?: run {
            Logx.e("找不到 ${Constants.CLS_MULTIWINDOW_UTILS}")
            return
        }
        var n = 0
        cls.declaredMethods
            .filter { it.name == "getFreeformRect" || it.name == "getCustomFreeformRect" }
            .forEach { method ->
                val params = method.parameterTypes
                val pkgIdx = params.indexOfFirst { it == String::class.java }
                val rectIdx = params.indexOfFirst { it == Rect::class.java }
                val ctxIdx = params.indexOfFirst { Context::class.java.isAssignableFrom(it) }
                val miniIdx = params.indexOfFirst { it == java.lang.Boolean.TYPE }
                val sig = method.name + params.joinToString(",", "(", ")") { it.simpleName }
                if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                        val res = chain.proceed() as? Rect
                        try {
                            Cfg.reloadThrottled()
                            val pkg = if (pkgIdx >= 0) chain.getArg(pkgIdx) as? String else null
                            // 前几次调用都打出来（不能只 once）：用户反馈"位置记不住"时，
                            // 必须能看出**他用的那条打开路径到底有没有走进这个钩子**。
                            if (pkg != null) {
                                lastFreeformPkg = pkg
                                val hit = ffrHits.merge("$sig|$pkg", 1, Int::plus) ?: 1
                                if (hit <= 6) {
                                    Logx.always("小窗 bounds 计算[$hit]: $sig pkg=$pkg -> $res")
                                    // ★★ 2026-10-06：旋转形变的答案就在这里 ——
                                    //   真机取证：旋转时 MIUI **会**调本钩子（我们返回了正方），
                                    //   但窗口最终 bounds 仍是它自己算的 1170x1870 ⇒
                                    //   **要用的是"谁在调用它、并把它算出来的矩形落到窗口上"**。
                                    //   所以把调用栈打出来，直接定位那个地方去 hook。
                                    val st = Throwable().stackTrace.drop(3).take(10)
                                        .joinToString(" <- ") {
                                            "${it.className.substringAfterLast('.')}.${it.methodName}"
                                        }
                                    Logx.always("小窗 bounds 调用栈[$hit]: $st")
                                    // ★★★ v0.4.45：判定"这是**用户主动开窗**吗"。
                                    //   真机取证（22:48:27.921）侧边栏开小窗的栈：
                                    //     getCustomFreeformRect <- MiuiMultiWindowUtils.getActivityOptions
                                    //       <- MultiTaskingLinkageTransition.startSidebarLaunchFreeform
                                    //       <- MultiTaskingLinkageController.launchLinkageAnimationFromIcon
                                    //   这类**用户主动开窗**，记忆已在下面被正确套用（实测开窗 bounds
                                    //   完全等于记忆），模块**绝不能再触发"对账关窗重开"** ——
                                    //   那正是用户看到的「黑屏 → 被关掉 → 再打开」。
                                    //   只有模块自己重开（栈里有 Hooks.relaunch*）不算用户主动。
                                    if (st.contains("Sidebar", ignoreCase = true) ||
                                        st.contains("launchLinkageAnimationFromIcon") ||
                                        st.contains("LinkageController", ignoreCase = true) ||
                                        st.contains("HyperGesture", ignoreCase = true)
                                    ) {
                                        userOpenUntil[pkg] =
                                            android.os.SystemClock.elapsedRealtime() + 8_000
                                        Logx.always("用户主动开窗(侧边栏): pkg=$pkg ⇒ 8s 内禁止对账关窗")
                                    }
                                }
                            }
                            // isMiniFreeformMode 只对 13 参数版可判断；更短的定制重载一律按普通小窗处理
                            val mini = miniIdx >= 0 && (chain.getArg(miniIdx) as? Boolean == true)
                            // ★ 注意：不能再写 `Cfg.rememberBounds &&` 作为整段门禁。
                            // 比例调整（三点菜单那排比例按钮）是用户的**显式操作**，它把算好的目标尺寸
                            // 写进「记忆」再关掉重开小窗；而落地靠的正是这里。若整段被 rememberBounds 挡住，
                            // 用户关掉「分应用记忆窗口尺寸」后，比例按钮等于没按 —— 真机复现：
                            // 选 1:1 后窗口仍是系统默认（实测 1170×1870 而非正方形）。
                            // 所以：先无条件应用「本次刚算出的目标尺寸(pendingTarget)」，记忆开关只影响"记忆"那一支。
                            if (res != null && !mini && !pkg.isNullOrEmpty()) {
                                val ctx = (if (ctxIdx >= 0) chain.getArg(ctxIdx) as? Context else null) ?: AppCtx.get()
                                if (ctx != null) {
                                    val screen = Bounds.screenKey(ctx)
                                    val pending = pendingTarget[Bounds.key(pkg, screen)]?.bounds
                                    if (pending != null) {
                                        Logx.always("套用比例目标 $pkg@$screen -> $pending（不受记忆开关限制）")
                                    }
                                    val memo = pending ?: if (Cfg.rememberBounds) Bounds.get(ctx, pkg, screen) else null
                                    if (memo == null) {
                                        Logx.once("ffr-none-$pkg", "当前屏无 $pkg 记忆，交回系统默认（不再借其它屏记忆）")
                                    } else if (pending != null) {
                                        // 比例菜单指定的目标：**整块**套用（位置+尺寸一起，见下）
                                        // ★ v0.4.46：同样要缩回屏内 —— pendingTarget 由"按屏幕可视区
                                        //   clamp 后的目标"或 reopenAtMemory 登记，仍可能比屏幕宽/高（内屏
                                        //   记忆在外屏上用），越界就半边在屏外。
                                        val pfit = fitIntoDisplay(ctx, memo)
                                        // ★★★ v0.4.80 兜底：**绝不允许把退化矩形套给窗口**。
                                        //   真机事故：记忆点被写坏后，这里把窗口设成了 1×1 像素 ⇒
                                        //   白屏 + 拖不动（音频仍在播），用户只能强杀小窗。
                                        //   `pendingTarget` 是"整块"套用（位置+尺寸一起），一旦里面是
                                        //   退化值就会直接毁掉窗口；这里发现退化就**放弃套用**，
                                        //   交回 MIUI 自己的默认几何（安全侧）。
                                        if (pfit.width() < 100 || pfit.height() < 100) {
                                            Logx.e(
                                                "套用比例目标: 目标退化 $pfit（${pfit.width()}x${pfit.height()}）" +
                                                    " ⇒ 放弃套用，交回 MIUI 默认几何（避免窗口被缩成 1×1）"
                                            )
                                            // 不套用、不报错：让 res 保持 MIUI 自己算出的值
                                        } else {
                                            if (rectIdx >= 0) (chain.getArg(rectIdx) as? Rect)?.set(pfit)
                                            res.set(pfit)
                                            Logx.always(
                                                "套用比例目标 $pkg@$screen -> $pfit（整块）" +
                                                    (if (pfit != memo) "（已从 $memo 缩回屏内）" else "")
                                            )
                                        }
                                    } else {
                                        // ★★★ v0.4.48 用户要求：「去除对尺寸的记忆，只记忆横竖屏和
                                        //   小窗的左上角那个点的位置」。
                                        //   ⇒ 恢复方式 = **MIUI 刚算出的尺寸 + 记忆里的左上角点**：
                                        //     · 尺寸：完全用 MIUI 这次算出来的（`res`），模块不碰 ⇒ 比例/尺寸
                                        //       永远跟随系统，不会再出现"记忆里的旧尺寸把比例顶回去"
                                        //     · 位置：把 MIUI 的矩形**平移**到记忆点（只改 left/top，
                                        //       宽高原样保留）
                                        //   这样同时根除了前几轮的两个老问题：
                                        //     ①「旋转后比例被切回原始」—— 因为尺寸不再由记忆提供
                                        //     ②「半边在屏幕外」—— 因为尺寸来自 MIUI，它自己是合屏的；
                                        //        位置再做一次"夹回屏内"即可（只夹位置，不缩尺寸）
                                        val systemDefault = Rect(res)
                                        // ★★★ v0.4.53 用户反馈：「小窗被切换为横屏之后，会被系统立马拉回竖屏」。
                                        //
                                        //   根因：v0.4.48「去掉尺寸记忆」之后，恢复端**只用记忆点**，
                                        //   尺寸完全交给 MIUI ⇒ 横屏形状丢失，重开又回竖屏。
                                        //   而"比例"其实一直存在比例槽里（`putPoint(..., memoScale)`），
                                        //   但**恢复端从未读它**（全库 `Bounds.getScale` 零调用）。
                                        //   真机取证（02:10:15）：横屏算对了 `Rect(0,162-1672,1208)`，
                                        //   重开后却是 `473,445-1643,2314`（1170x1869 竖屏）。
                                        //
                                        //   修法：**只把"方向"从比例槽里读回来**，不恢复尺寸数值 ——
                                        //   这样既满足"不记尺寸"，又让横屏保持横屏：
                                        //     · 比例 >= 1 ⇒ 用户要的是横屏形状 ⇒ 把 MIUI 的宽高对调
                                        //     · 比例 <  1 ⇒ 竖屏 ⇒ 保持 MIUI 原样
                                        //   尺寸大小仍由 MIUI 决定（我们只交换宽高，不指定具体数值）。
                                        val memoDir = Bounds.getOrientation(ctx, pkg, screen)
                                        val sysL = systemDefault.width() > systemDefault.height()
                                        var w = systemDefault.width()
                                        var h = systemDefault.height()
                                        if (memoDir != null && memoDir != sysL) {
                                            // MIUI 给的形状与记忆方向不符 ⇒ 对调宽高（保住它的"长边"量级）
                                            val tmp = w; w = h; h = tmp
                                        }
                                        val target = Rect(memo.left, memo.top, memo.left + w, memo.top + h)
                                        // 只夹**位置**：保证窗口完整落在屏内（尺寸不动，故不会变形）
                                        val placed = placeInside(ctx, target)
                                        // ★ v0.4.80 同样的兜底：尺寸取自 MIUI(systemDefault) 本应安全，
                                        //   但若 MIUI 这次给的就是退化值（拖动/重开中），也别把它传下去。
                                        if (placed.width() < 100 || placed.height() < 100) {
                                            Logx.e(
                                                "恢复(记忆点+方向): 几何退化 $placed" +
                                                    "（MIUI尺寸=${systemDefault.width()}x${systemDefault.height()}）" +
                                                    " ⇒ 放弃套用，交回 MIUI 默认几何"
                                            )
                                        } else {
                                            if (rectIdx >= 0) (chain.getArg(rectIdx) as? Rect)?.set(placed)
                                            res.set(placed)
                                            Logx.always(
                                                "恢复(记忆点+方向) $pkg@$screen -> $placed" +
                                                "（记忆点=${memo.left},${memo.top} 记忆方向=" +
                                                "${if (memoDir == null) "无" else if (memoDir) "横" else "竖"} " +
                                                "MIUI尺寸=${systemDefault.width()}x${systemDefault.height()}" +
                                                    (if (placed != target) " 已夹回屏内" else "") + "）"
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Logx.e("套用记住的 bounds 失败", t)
                        }
                        res
                    })) {
                    n++
                    Logx.always("hook 成功 ${Constants.CLS_MULTIWINDOW_UTILS}#$sig")
                }
            }
        Logx.always("小窗 bounds 计算共挂 ${n} 个重载")

        // ★ 诊断（用户反馈"位置记不住"）：把可能产出小窗 bounds 的入口全列出来。
        //   只挂 getFreeformRect/getCustomFreeformRect 是**盲区** —— 别的打开路径（例如
        //   defaultLaunchBounds 系列）根本不经过这两个方法，记忆就不会被套用。
        //   先靠这行日志确认还有哪些入口，再决定补挂。
        listOf(
            Constants.CLS_MULTIWINDOW_UTILS,
            "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils",
            "com.android.wm.shell.multitasking.common.utils.MultiTaskingCommonUtils"
        ).forEach { cn ->
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull()
            if (c == null) {
                Logx.always("候选入口类不存在: $cn")
            } else {
                c.declaredMethods.filter { it.returnType == Rect::class.java }.forEach { mm ->
                    Logx.always("候选入口 $cn#${mm.name}${mm.parameterTypes.joinToString(",", "(", ")") { it.simpleName }}")
                }
            }
        }
    }

    // ---------------- 2.4 沉浸式底栏 ----------------

    private fun installImmersive(m: MainHook, cl: ClassLoader) {
        // ★★★ v0.4.46 用户反馈：「部分 APP（微信/酷安）没有顶栏，也就是没有"三点"状态菜单的
        //   背景栏，导致三点按钮跟应用的顶栏产生冲突」→ 选择**恢复原生三点栏（放弃该项沉浸）**。
        //
        //   原实现把整个 `MiuiDecorationDotView#onDraw` 抹掉（不绘制）+ `updateInsets` 不预留空间，
        //   于是三点按钮直接浮在应用自己的顶栏文字上，视觉上"打架"。
        //   现在：**顶部三点栏完全交回 MIUI 原生绘制与 insets**，不再拦截、不再进 hiddenViews。
        //   底部手势条仍保留沉浸（那是用户认可的、且不与应用内容冲突）。
        //
        //   注：这里不再挂 CLS_DECOR_DOT_VIEW#onDraw。markBar 侧也同步不再把顶部栏收进 hiddenViews。
        // ★★★ v0.4.51 用户要求：「手势小白条还是经常会间歇性出现。你可以直接隐藏掉手势小白条吗？
        //   但是不允许隐藏上面 3 个小点」。
        //
        //   用户指的"小白条" = **小窗自己的底部栏**（`MiuiDecorationBottomView`）——
        //   就是小窗底边那条，原来按"空闲 1.5s 隐藏 / 触摸又显现"处理，
        //   所以只要碰到窗口就冒出来 ⇒ 表现为"间歇性出现"。
        //
        //   现在改为**无条件不绘制**（彻底隐藏）：
        //     · 视图与触摸逻辑全部保留（只是不画）⇒ 不影响小窗边缘手势
        //     · 不再依赖 `hiddenViews` / `decorRecentlyTouched()` ⇒ 不存在"又冒出来"
        //     · **顶部三点栏完全不动**（下面 CLS_DECOR_DOT_VIEW 没有任何钩子）
        //
        //   ⚠️ 这里**不碰** `NavigationHandle`（系统手势小白条）—— 那是 GestureHandle.kt
        //      的职责，且用户这次明确要的是小窗底部栏，两者是不同的视图。
        m.hookMethod(cl, Constants.CLS_DECOR_BOTTOM_VIEW, "onDraw",
            arrayOf<Class<*>>(android.graphics.Canvas::class.java),
            XposedInterface.Hooker { chain ->
                Cfg.reloadThrottled()
                if (Cfg.immersive) {
                    Logx.once("hide-bottom-bar", "小窗底部栏: 已彻底隐藏（无条件不绘制）")
                    null
                } else chain.proceed()
            })

        // "偶尔又冒出来、摸一下再消失" = surface 级重显：框架把 SCVH 的 surface 重新 show，
        // 但不重绘，于是露出上一帧缓冲（视图层的 INVISIBLE 管不到）。
        // 只拦多分屏栏那一个 surface 名 —— **绝不拦 MultipleSplitController**（那是分隔条/把手宿主，
        // 拦了就没法拖动，之前踩过）。
        runCatching {
            m.hookMethod(cl, "android.view.SurfaceControl\$Transaction", "show",
                arrayOf(android.view.SurfaceControl::class.java),
                XposedInterface.Hooker { chain ->
                    val name = chain.getArg(0)?.toString() ?: ""
                    // ⚠️ **只拦真·多分屏（3+）的 UI 栏 MultipleSplitUIController**。
                    // 不再拦 SoScSplitDecorManager —— 那是 **2 分屏** 的装饰管理器。
                    // 用户明确要求"两分屏保持官方原样"，且 2 分屏退出(dissolve)时若抑制其装饰
                    // surface 的 show，会让重组 transition 卡在 shell 主线程（内核态 CPU 打满、
                    // 音频刺啦），进而第二次四指被系统 MultiTaskSwitch 监视器接管后死锁 →
                    // 5 秒不响应 → SystemUI 被杀重启（2026-09-21 真机复现）。
                    if (name.contains("MultipleSplitUIController")) {
                        Logx.once("ms-show", "多分屏栏: 拦截 show($name)")
                        // show() 返回的是 Transaction 本身（调用方会链式 .show(..).show(..)），
                        // 这里必须返回 it，返回 null 会 NPE 崩掉 SystemUI（刚踩过）。
                        chain.thisObject
                    } else chain.proceed()
                })
        }.onFailure { Logx.e("挂多分屏 show 拦截失败", it) }

        // 多分屏不满屏（上下/左右见到黑条）：stage 的 bounds 被 displayStableInsets 缩进了
        // （真机量到 Rect(94,66-802,1606)：上下各 66、左右各 94）。该方法只用于算分屏可用区域，
        // 沉浸下返回空 Rect → 分屏铺满整屏。
        runCatching {
            m.hookMethod(cl, "com.android.wm.shell.multiplesplit.MultipleSplitLayout",
                "getDisplayStableInsets", null,
                XposedInterface.Hooker { chain ->
                    Cfg.reloadThrottled()
                    if (Cfg.immersive) {
                        Logx.once("ms-fill", "多分屏满屏: displayStableInsets -> 空")
                        Rect()
                    } else chain.proceed()
                })
        }.onFailure { Logx.e("挂多分屏满屏失败", it) }

        // 多分屏的"占位"来自 MultipleSplitLayout 的两个预留高度：
        //   mDotInsetsHeight（顶栏）/ mBottomInsetsHeight（底栏），updateBounds 里按它们缩进。
        // 沉浸开关下置 0 → 整个横条的占位一起收回（用户要求："不只是隐藏导航栏"）。
        runCatching {
            m.hookMethod(cl, "com.android.wm.shell.multiplesplit.MultipleSplitLayout",
                "updateDividerConfig", null,
                XposedInterface.Hooker { chain ->
                    val r = chain.proceed()
                    runCatching {
                        Cfg.reloadThrottled()
                        if (Cfg.immersive) {
                            val o = chain.thisObject
                            val dot = field(o, "mDotInsetsHeight") as? Int
                            val bottom = field(o, "mBottomInsetsHeight") as? Int
                            if (dot != 0) {
                                setIntField(o, "mDotInsetsHeight", 0)
                                setIntField(o, "mBottomInsetsHeight", 0)
                                Logx.e("多分屏占位: 顶栏 $dot / 底栏 $bottom -> 0")
                            }
                            // 多分屏不满屏的根因：布局的 mRootBounds 直接取任务配置边界，
                            // 真机是 (94,66)-(2266,1606)（四周都有留白）。改成整屏，
                            // 后面所有 updateBounds 都按整屏重新铺。
                            // 66/94 的留白 = mDividerInsets（= max(某 dimen, 屏幕圆角半径)），
                            // 多分屏按它把每块往里缩。归零即可铺满（分隔条绘制仍在，只是触摸区变窄）。
                            val ins = field(o, "mDividerInsets") as? Int
                            if (ins != null && ins != 0) {
                                setIntField(o, "mDividerInsets", 0)
                                setIntField(o, "mDividerWindowWidth", 0)
                                Logx.e("多分屏满屏: mDividerInsets $ins -> 0")
                            }
                            val dm = AppCtx.get()?.resources?.displayMetrics
                            val old = field(o, "mRootBounds") as? Rect
                            if (dm != null && old != null) {
                                val full = Rect(0, 0, dm.widthPixels, dm.heightPixels)
                                if (old != full) {
                                    setRectField(o, "mRootBounds", full)
                                    Logx.e("多分屏满屏: mRootBounds $old -> $full")
                                }
                            }
                        }
                    }
                    r
                })
        }.onFailure { Logx.e("挂多分屏占位归零失败", it) }

        // 多分屏自己那套 UI（MultipleSplitUIController 的 SCVH：MultipleSplitUIContainer /
        // MultipleSplitAbstractView 派生视图）——真机取证：Miui Caption 那套已确认隐藏(true)
        // 却仍看得到栏，说明栏由这里画。直接在视图挂到窗口时置 INVISIBLE（不占绘制）。
        listOf(
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitUIContainer",
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitAbstractView",
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitPanelView",
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitDotView",
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitGestureBottomView"
        ).forEach { cls0 ->
            // 三个时机都压：挂载、每次布局、每次绘制前的 dispatchDraw。
            // 只挂 onAttachedToWindow 不够 —— 切换窗口时框架会把它设回 VISIBLE，
            // 表现为"消失→切换窗口又出现→过一会儿/摸一下再消失"（用户实测）。
            listOf("onAttachedToWindow", "onLayout", "dispatchDraw").forEach { m0 ->
                runCatching {
                    m.hookMethod(cl, cls0, m0, null,
                        XposedInterface.Hooker { chain ->
                            runCatching {
                                Cfg.reloadThrottled()
                                val v = chain.thisObject as? View
                                if (Cfg.immersive && v != null && v.visibility != View.INVISIBLE) {
                                    if (msHidden.add(v)) {
                                        Logx.e("多分屏栏: ${cls0.substringAfterLast('.')} 置 INVISIBLE($m0)")
                                    }
                                    v.visibility = View.INVISIBLE
                                }
                            }
                            chain.proceed()
                        })
                }.onFailure { Logx.e("挂多分屏 UI 隐藏失败($cls0#$m0)", it) }
            }
        }

        // 多分屏（3~6 个应用）：每个应用顶部的"三点"与底部的手势/导航条，
        // 与自由小窗同样处理 —— 只不绘制，保留视图与触摸逻辑（顶部状态栏不动）。
        listOf(
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitDotView",
            "com.android.wm.shell.multiplesplit.ui.MultipleSplitGestureBottomView"
        ).forEach { cls0 ->
            runCatching {
                m.hookMethod(cl, cls0, "onDraw",
                    arrayOf<Class<*>>(android.graphics.Canvas::class.java),
                    XposedInterface.Hooker { chain ->
                        Cfg.reloadThrottled()
                        if (Cfg.immersive) {
                            Logx.once("ms-hide-" + cls0.substringAfterLast('.'), "已隐藏多分屏栏: $cls0")
                            null
                        } else chain.proceed()
                    })
            }.onFailure { Logx.e("挂多分屏栏隐藏失败($cls0)", it) }
        }

        // 每次 relayout 决定「这个窗口的栏要不要隐藏」（只针对普通自由小窗，分屏/桌面不受影响）
        // ★ v0.4.46：**顶部三点栏不再纳入沉浸** —— 用户反馈它与应用顶栏冲突，
        //   选择恢复 MIUI 原生顶栏（有背景、有 insets 占位）。所以这里不再调 markBar。
        m.hookMethod(cl, Constants.CLS_DECOR_BOTTOM, "updateRootView", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                markBar(chain.thisObject) { Cfg.immersive }
                r
            })

        // ① 隐藏 MIUI 自带的角标/缩放描边视觉（保留其触摸逻辑；我们用自己的手柄）
        m.hookMethod(cl, Constants.CLS_CORNER_TIP, "updateFreeformSurfaceStroke", null,
            XposedInterface.Hooker { chain ->
                Cfg.reloadThrottled()
                if (Cfg.immersive) {
                    Logx.once("hide-corner-stroke", "已隐藏 MIUI 自带缩放描边")
                    null
                } else chain.proceed()
            })

        // 上/下 insets：**顶部恢复原生占位**（v0.4.46：顶部三点栏回归 MIUI 原生，
        //   它的背景与内容需要自己的 insets，否则仍会和应用顶栏叠在一起），
        //   底部仍按沉浸不预留空间（内容铺到底）。
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "updateInsets", null,
            XposedInterface.Hooker { chain ->
                Cfg.reloadThrottled()
                // 顶部三点栏：交回 MIUI 原生（不再跳过 insets）
                chain.proceed()
            })
        m.hookMethod(cl, Constants.CLS_DECOR_BOTTOM, "updateInsets", null,
            XposedInterface.Hooker { chain ->
                Cfg.reloadThrottled()
                if (Cfg.immersive) {
                    Logx.once("skipBottomInsets", "沉浸: 不再为底部手势条预留 insets（task=${taskId(chain.thisObject)}）")
                    null
                } else chain.proceed()
            })
    }

    // ---------------- ③ 小窗比例：三点菜单里加一行比例按钮 ----------------
    //
    // 角柄（底角胶囊）**交回 MIUI 原生**：它本来就是「锁当前比例改尺寸」（`calBoundsAfterMoving`
    // 用 height = width / (宽高比)），而且收尾回调 `onMiuiFreeformResizeEnd` 会喂给已有的记忆链路
    // —— 真机验证：原生拖角后出现「立即写入 … why=resizeEndDelayed」与「记住 …」。
    //
    // 真机取证（2026-09-19）：角柄触摸走 `MiuiFreeformModeResizeHandler#handleResize(f, f2, taskInfo, downPoint, 0/2/1)`。
    //
    // 比例改到三点菜单：`MiuiDecorationDot#createHandleMenu` 建的 `MiuiCaptionContainerView`
    // 是纯代码 LinearLayout，`initButtonAndBg` 是它建完按钮行后的收尾 → 在那里追加我们的一行。
    // 菜单高度只按「一排按钮」算，所以在 `addWindow` 那一层给菜单表面加一行高度。

    private const val TAG_RATIO_ROW = "os4ffx_ratio_row"

    /** 菜单里的方向状态（pkg|屏幕 → 是否横屏）。点「横竖屏」切换，比例按钮按它取形态。 */
    /** 记忆 key 的日志去重（观察折叠屏内外屏是否分开记录）。 */
    private val boundsLog = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 正在构建三点菜单的 decoration（菜单构建是同步的，都在同一个线程）。 */
    private val menuOwner = ThreadLocal<Any?>()

    /**
     * SystemUI 的 classloader。wm shell 的类（`MultiTaskingAnimTarget` 等）只在它里面，
     * 用 `Class.forName(name)` 会 ClassNotFoundException（真机踩过），必须显式带上。
     */
    @Volatile private var uiLoader: ClassLoader? = null

    private fun cls(name: String): Class<*> =
        Class.forName(name, false, uiLoader ?: Hooks.javaClass.classLoader)

    private fun installRatioMenu(m: MainHook, cl: ClassLoader) {
        uiLoader = cl
        // ★ v0.4.72：把注入的 ClassLoader 也交给 FreeformExtra —— 它要拿 DSH/shell 的类
        //   （MultiTaskingControllerImpl 等），用默认 ClassLoader 会 ClassNotFoundException。
        FreeformExtra.uiLoader = cl
        // 菜单高度只算了一排按钮：在 addWindow 这一层给菜单表面加一行高度，位置往上挪半行保持居中。
        // 注意**不能**改 `getWCButtonHeight()`：`MiuiCaptionContainerView#init` 拿同一个值当
        // 按钮自己的高度，一改按钮就被拉大、把我们那行顶到菜单外（真机踩过两次）。
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "addWindow", null,
            XposedInterface.Hooker { chain ->
                // 开关关掉：完全不碰 MIUI 的三点菜单（不加那排比例按钮）
                if (!Cfg.ratioMenu) return@Hooker chain.proceed()
                val view = chain.getArg(0) as? View
                if (view != null && view.findViewWithTag<View>(TAG_RATIO_ROW) != null) {
                    val args = chain.args.toTypedArray()
                    val extra = extraRowPx()
                    val originalHeight = args[4] as Int
                    args[4] = originalHeight + extra
                    args[2] = (args[2] as Int) - extra / 2
                    val controller = chain.proceed(args)
                    // MIUI 的菜单圆角 = 高度/2，加高后圆角也会变大（真机反馈：太圆、干涉）
                    runCatching {
                        val surface = field(controller!!, "mWindowSurface") ?: return@runCatching
                        val tx = android.view.SurfaceControl.Transaction()
                        tx.javaClass.getMethod(
                            "setCornerRadius", android.view.SurfaceControl::class.java, java.lang.Float.TYPE
                        ).invoke(tx, surface, (originalHeight / 2f) + 1f)
                        tx.javaClass.getMethod("apply").invoke(tx)
                    }
                    controller
                } else chain.proceed()
            })
        // 标记当前正在给哪个窗口建菜单
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "createHandleMenu",
            arrayOf(Integer.TYPE, String::class.java),
            XposedInterface.Hooker { chain ->
                menuOwner.set(chain.thisObject)
                try {
                    chain.proceed()
                } finally {
                    menuOwner.remove()
                }
            })
        // MIUI 角柄缩放的上限 = 真实 bounds × resizeOriFreeformScale × scalingMaxValue。
        // ori 是按"原始 freeformScale"算的（<1），真机表现「小窗可调范围很小」；
        // 至少取当前 freeformScale 才能拿回正常的可调范围。
        runCatching {
            m.hookMethod(cl, Constants.CLS_FF_TASK_INFO, "getResizeOriFreeformScale", null,
                XposedInterface.Hooker { chain ->
                    // 开关关掉：不放大可调尺寸范围（避免窗口被拖到超出应用支持的比例 ⇒ 底部留白）
                    if (!Cfg.ratioMenu) return@Hooker chain.proceed()
                    val v = chain.proceed() as? Float
                    val free = call(chain.thisObject, "getFreeformScale") as? Float
                    if (v != null && free != null) maxOf(v, free) else v
                })
        }.onFailure { Logx.e("挂 getResizeOriFreeformScale 失败", it) }

        // 迷你/贴边态点回来时，恢复成**记忆**的位置与尺寸（用户确认这条好用）
        runCatching {
            val tiCls = Class.forName(Constants.CLS_FF_TASK_INFO, false, cl)
            m.hookMethod(cl, Constants.CLS_MINI_HANDLER, "getRestoredBounds",
                arrayOf(tiCls, Rect::class.java),
                XposedInterface.Hooker { chain ->
                    val r = chain.proceed()
                    try {
                        val ti = chain.getArg(0)
                        val rect = chain.getArg(1) as? Rect
                        val ctx = AppCtx.get()
                        if (rect != null && ctx != null) {
                            val info = call(ti, "getTaskInfo")
                            val pkg = info?.let { taskPkg(it) }
                            val memo = pkg?.let { Bounds.get(ctx, it, Bounds.screenKey(ctx)) }
                            if (memo != null) {
                                // ★ 原样套用（不再按屏幕 clampKeepRatio）：记忆本来就是 MIUI 未缩放坐标里的 rect，
                                //   夹取只会把用户放好的位置改成"右下贴边"。几何适配交回 MIUI。
                                rect.set(memo)
                                // 只替换恢复矩形，**不改 scale**：之前覆盖 scale 是为了修尺寸记忆，
                                // 但后来证明那是别的原因，而覆盖 scale 会让 mini→正常的转换异常
                                // （真机：贴边迷你态点击不再回到悬浮窗，而是点到内容）。
                                runCatching {
                                    val sc = runCatching { call(ti, "getFreeformScale") }.getOrNull()
                                    Logx.always(
                                        "迷你恢复: 套用记忆 $memo（scale 保持系统值）诊断: " +
                                            "real=${runCatching { call(ti, "getBounds") }.getOrNull()} " +
                                            "visual=${runCatching { call(ti, "getScaledBounds") }.getOrNull()} scale=$sc"
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Logx.e("迷你恢复套用记忆失败", t)
                    }
                    r
                })
        }.onFailure { Logx.e("挂 getRestoredBounds 失败", it) }

        // 按钮行建好后追加比例行
        m.hookMethod(cl, Constants.CLS_CAPTION_CONTAINER, "initButtonAndBg",
            arrayOf(
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
                Class.forName(Constants.CLS_EXTEND_MODE_INFO, false, cl),
                android.view.View.OnClickListener::class.java
            ),
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                // 注：scale 恢复已从"菜单初始化"搬到 relayout（首次小窗布局）——菜单不是每次开窗都建，
                //     挂在这里等于"只有你点过三点菜单才会恢复尺寸"✗（真机：尺寸记忆日志 0 条）。
                try {
                    injectRatioRow(chain.thisObject as? android.view.ViewGroup, menuOwner.get())
                } catch (t: Throwable) {
                    Logx.e("注入比例行失败", t)
                }
                r
            })
    }

    /** 我们那行的像素高度（决定菜单表面要多高）。 */
    private fun extraRowPx(): Int =
        (36 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    private fun injectRatioRow(container: android.view.ViewGroup?, owner: Any?) {
        if (container == null || owner == null) return
        if (container.findViewWithTag<View>(TAG_RATIO_ROW) != null) return
        // ★ 只在小窗里注入（用户 2026-10-01 反馈：全屏界面点三点时也出现了这些比例功能）。
        //   MIUI 在非小窗场景同样会建 caption 容器 ⇒ 必须按窗口模式门禁。
        val mode = field(owner, "mRunningTaskInfo")?.let { taskWindowingMode(it) } ?: -1
        if (mode != MODE_FREEFORM) {
            Logx.always("比例行跳过：非小窗（mode=$mode）")
            return
        }
        val ctx = container.context
        val row = android.widget.LinearLayout(ctx)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        row.tag = TAG_RATIO_ROW
        val pad = (4 * ctx.resources.displayMetrics.density).toInt()
        row.setPadding(pad, 0, pad, pad)
        row.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, extraRowPx()
        )
        // ① 原始：清掉本应用的尺寸记忆，交回 MIUI 自己的默认比例/尺寸（用户要求：能切回"原始"）
        row.addView(ratioButton(ctx, "原始") { resetToOriginal(owner) })
        RATIO_BUTTONS.forEach { (label, ratio) ->
            row.addView(ratioButton(ctx, label) { pickRatio(owner, ratio, label, row) })
        }
        // ② 横竖屏：手机轮廓图标（显示"点一下会变成哪个方向"），点了把窗口转 90°。
        //    BASELINE 版就有它；上一版"暂时取消横竖屏切换"后用户明确要回来 ⇒ 恢复。
        row.addView(directionButton(ctx, owner))
        container.addView(row)
        container.requestLayout()
        Logx.always("比例行已注入三点菜单（原始 + ${RATIO_BUTTONS.joinToString("/") { it.first }} + 方向，共 ${RATIO_BUTTONS.size + 2} 个）")
    }


    /** 与 MIUI 比例按钮文字同色（`caption_extend_text_color`），取不到就退回灰。 */
    /**
     * 三点菜单里那排控件的文字/图标色。**比例文字与方向(旋转)按钮必须同色**。
     *
     * ★ 2026-10-06 真机像素取证：浅色模式下方向按钮图标 = `#111114`(亮度17)，
     *   而比例标签 = `#90A0B0`(亮度157) ⇒ 视觉上"比例字比旁边浅"，用户反馈的正是这个。
     *   根因有两层：
     *     1) `applyCaptionButtonLook` 原来直接 `setTextColor(getColorStateList(caption_extend_text_color))`，
     *        **绕过了本函数** ⇒ 比例文字拿到 MIUI 原始浅色值；
     *     2) 上一版用"亮度>170 才换深色"的启发式，而 `#90A0B0` 亮度 157 **没到阈值** ⇒ 换不掉。
     *   现在：统一走本函数，并**直接按主题明暗取值**（浅色主题的胶囊底是浅色 ⇒ 深字；深色主题 ⇒ 浅字）。
     *   这样两个控件由构造保证同色，不再依赖对 MIUI 取色值的猜测。
     */
    private fun captionTextColor(ctx: Context): Int = runCatching {
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()
    }.getOrDefault(0xFF111114.toInt())

    private fun phoneShape(phone: android.view.View, ctx: Context, landscape: Boolean) {
        val density = ctx.resources.displayMetrics.density
        val w = ((if (landscape) 17 else 10) * density).toInt()
        val h = ((if (landscape) 10 else 17) * density).toInt()
        phone.layoutParams = android.widget.FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
        phone.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            // 用与比例文字一致的颜色（纯白太亮，真机反馈）
            setStroke((1.5f * density).toInt(), captionTextColor(ctx))
            cornerRadius = 2.5f * density
        }
    }

    /**
     * 比例按钮的外观（MIUI 自己的底 + 文字色）。方向按钮也走这里 ⇒ 两个按钮**外观一致**。
     * 用 MIUI 资源而不是自绘：深浅色模式自动跟随（自绘深色胶囊曾被反馈"不统一"）。
     */
    private fun applyCaptionButtonLook(v: android.view.View, ctx: Context) {
        val res = ctx.resources
        val bgId = res.getIdentifier("caption_extend_selector", "drawable", "com.android.systemui")
        if (bgId != 0) {
            v.setBackgroundResource(bgId)
        } else {
            v.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(40, 128, 128, 128))
                cornerRadius = 13f * ctx.resources.displayMetrics.density
            }
        }
        // ★ 必须走 captionTextColor（与方向按钮同源）——直接取 MIUI 的 caption_extend_text_color
        //   会让比例文字比旁边浅一档（真机像素：比例 #90A0B0 vs 方向 #111114，用户反馈的"不一致"）。
        if (v is android.widget.TextView) {
            v.setTextColor(captionTextColor(ctx))
        }
    }

    private fun ratioButton(ctx: Context, label: String, onClick: () -> Unit): android.widget.TextView {
        val density = ctx.resources.displayMetrics.density
        val h = (26 * density).toInt()
        val tv = android.widget.TextView(ctx)
        tv.text = label
        tv.textSize = 12f
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(android.graphics.Color.WHITE)
        // ★★ 2026-10-06 用户反馈（外屏三点菜单）：比例文字被折成两行 ——
        //   「原始」变「原/始」、「21:9」变「21:/9」、「17.5:9」变「17./5:9」。
        //   根因：外屏菜单更窄，而这排按钮是 weight=1 平分宽度，固定 12sp 放不下就自动换行。
        //   修法：**单行 + 自动缩放**（放不下就把字号往下缩，永不折行）。
        tv.maxLines = 1
        tv.isSingleLine = true
        runCatching {
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        // 用 weight=1 的弹性宽度：四个按钮平分菜单宽度，无论内屏/外屏（菜单背景窄）都不会超出背景。
        // 之前写死 42dp 固定宽，外屏菜单背景比内屏窄，四个按钮加起来就超出背景了（#3）。
        // 边距从 2dp 收到 1dp：给窄屏的文字多留一点宽度，配合自动缩放更不容易触底。
        tv.layoutParams = android.widget.LinearLayout.LayoutParams(0, h, 1f).apply {
            marginStart = (1 * density).toInt()
            marginEnd = (1 * density).toInt()
        }
        // 复用 MIUI 自己的按钮底与文字色：深浅色模式自动一致（自己画深色胶囊被反馈过“不统一”）
        applyCaptionButtonLook(tv, ctx)
        if (tv.background == null) {
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(40, 128, 128, 128))
                cornerRadius = h / 2f
            }
        }
        tv.setOnClickListener { onClick() }
        return tv
    }

    /** 关掉三点菜单（一次进菜单就完成"方向 + 比例"）。 */
    private fun closeMenu(owner: Any) = runCatching {
        val controller = field(owner, "mMiuiDecorationController") ?: return@runCatching
        controller.javaClass.getMethod("closeHandleMenu").invoke(controller)
    }.onFailure { Logx.e("关菜单失败", it) }

    /** 点某个比例：按当前方向状态定下尺寸，然后关掉菜单（一次进菜单就完成）。 */
    private fun pickRatio(owner: Any, picked: Float, label: String, row: android.view.View) {
        applyRatio(owner, picked, false, "比例$label")
        closeMenu(owner)
    }

    /** 当前小窗的**可视**宽高比（宽/高）；取不到按 1 处理。 */
    private fun currentAspect(owner: Any): Float = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
        val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
        val vis = call(freeformTask(repo, taskId(owner)), "getScaledBounds") as? Rect
        if (vis != null && vis.height() > 0 && vis.width() > 0) vis.width().toFloat() / vis.height() else 1f
    }.getOrDefault(1f)

    /**
     * 方向按钮（点一下转 90°）。
     * 外观与比例按钮**完全一致**：同一个 MIUI 按钮底（`caption_extend_selector`）、
     * 同一套文字色（`caption_extend_text_color`），图标描边也取同一颜色 ⇒ 深浅色模式自动一致。
     */
    private fun directionButton(ctx: Context, owner: Any): android.view.View {
        val density = ctx.resources.displayMetrics.density
        val h = (26 * density).toInt()
        val holder = android.widget.FrameLayout(ctx)
        holder.layoutParams = android.widget.LinearLayout.LayoutParams(0, h, 1f).apply {
            marginStart = (2 * density).toInt()
            marginEnd = (2 * density).toInt()
        }
        applyCaptionButtonLook(holder, ctx)        // ← 与比例按钮共用同一个外观函数
        val phone = android.view.View(ctx)
        // 图标显示"点一下会变成哪个方向"：当前是竖（aspect<1）就画横的轮廓。
        phoneShape(phone, ctx, currentAspect(owner) < 1f)
        holder.addView(phone)
        holder.setOnClickListener {
            val aspect = currentAspect(owner).takeIf { it > 0f } ?: 1f
            // ★★★ v0.4.52 用户反馈：「横屏按钮会失效，点击后小窗先是横屏，然后再变回竖屏」。
            //
            //   根因（真机数值精确复现）：这个按钮**把目标比例预先取了一次倒数**，
            //   而 applyRatio 内部又会按"当前窗口形状"再调一次 `ratioFor()` 取倒数
            //   ⇒ 两次取倒数互相抵消，横屏目标被算回竖屏。
            //     真机日志：`比例调整(比例16:9): 本次目标 Rect(243,162-1294,2031)`
            //       => 尺寸 1051x1869 —— 仍然是竖的（而 16:9 本该是横的）。
            //      复现：picked=16:9(1.778), landscape=false => ratio=0.563 => w=h*0.563=1051 ✗
            //   而且重开那一刻窗口还是竖的，所以 applyRatio 里推出 landscape=false 必然发生。
            //
            //   修法：**不要在这里预取倒数**。直接把"目标宽高比（宽/高）"当作 picked 传进去，
            //   并让 applyRatio 用 **landscape=true** 走"picked 即目标比例"这一支 ——
            //   即"我要的就是这个宽高比"，不让它再按当前形状反推方向。
            //   ⇒ 竖屏窗(1170x1869) 点一下 → target=1/0.626=1.597（宽>高，横屏）✓
            val target = 1f / aspect                      // 目标宽高比（宽/高）
            Logx.always(
                "横竖屏: 当前 aspect=%.3f -> 目标 aspect=%.3f（%s，task=%d）".format(
                    aspect, target, if (target > 1f) "转为横屏" else "转为竖屏", taskId(owner)
                )
            )
            // ★★★ v0.4.68 彻底重写：**不再用"关闭→重开换矩形"假装横屏**。
            //
            //   用户质疑（一针见血）：
            //     「你这个横屏又没真正实现，为什么旋转会消失？系统小窗旋转也不消失」
            //   —— 完全正确。「关闭→重开」的假横屏有三个致命问题：
            //     ① 应用内部不旋转（只是窗口框换长宽比）⇒ 没真正横屏；
            //     ② 每次重开都触发配置变更 ⇒ 对账逻辑又当成旋转 ⇒ 连环关窗（真机 03:57 抓到 3 连）
            //        ⇒ 用户看到「小窗消失再重开」；
            //     ③ 与 MIUI 自己的几何并行 ⇒ 互相覆盖 ⇒ 横屏被弹回。
            //
            //   改为调 **MIUI 自己的**旋转接口（SystemUI 侧，无需重启手机）：
            //     MiuiFreeformModeControlImpl.setRequestedOrientation(taskId, orientation)
            //       → setIsLandscapeFreeform(landscape)                      ← 真方向
            //       → startFreeformOrientationChangeShellTransition(taskInfo) ← 原生旋转转场
            //   这才是 MIUI 横竖屏按钮真正走的路，应用也会跟着真正旋转。
            val wantLandscape = target > 1f
            if (FreeformExtra.rotateFreeformNatively(owner, wantLandscape)) {
                // 记下方向（供下次打开时沿用），但**不做任何关闭/重开**
                runCatching {
                    val ctx = AppCtx.get()
                    val info = field(owner, "mRunningTaskInfo")
                    val pkg = info?.let { taskPkg(it) }
                    if (ctx != null && pkg != null) {
                        Bounds.putOrientation(ctx, pkg, Bounds.screenKeyFor(ctx, (field(info, "displayId") as? Int) ?: -1), wantLandscape)
                        Logx.always("记住方向 $pkg = ${if (wantLandscape) "横屏" else "竖屏"}（原生旋转）")
                    }
                }
                closeMenu(owner)
                return@setOnClickListener
            }
            // 兜底：万一该 ROM 版本没有原生接口，才退回旧的比例路径（并明确打日志）
            Logx.e("横竖屏: 原生旋转接口不可用 ⇒ 退回旧的比例路径（可能出现闪动）")
            applyRatio(owner, target, true, "横竖屏")
            closeMenu(owner)
        }
        return holder
    }

    /**
     * 「原始」：忘掉该应用在当前屏的记忆，关窗 → 官方接口重开 ⇒ 回到 MIUI 自己的默认比例与尺寸。
     * （用户 2026-10-01 要求：比例调过头以后要有路切回原始。）
     */
    private fun resetToOriginal(owner: Any) {
        runCatching {
            val ctx = AppCtx.get() ?: return@runCatching
            val info = field(owner, "mRunningTaskInfo") ?: return@runCatching
            val pkg = taskPkg(info) ?: return@runCatching
            val screen = Bounds.screenKeyFor(ctx, (field(info, "displayId") as? Int) ?: -1)
            val key = Bounds.key(pkg, screen)
            Bounds.forget(ctx, pkg, screen)
            pendingTarget.remove(key)
            val at = taskBounds(info) ?: Rect()
            val id = taskId(owner)
            Logx.always("回原始: 已遗忘 $key，关窗→官方接口重开（位置 $at 保留，比例交回系统）")
            closeMenu(owner)
            Handler(Looper.getMainLooper()).postDelayed({
                val closed = closeFreeformViaMiui(owner)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { relaunchViaMiuiApi(owner, id, at) }
                }, if (closed) 350 else 0)
            }, 150)
        }.onFailure { Logx.e("回原始失败", it) }
    }

    /** 比例落位用：取该任务所在屏幕的 key（display 维度，方向无关）。 */
    private fun screenKeyForRatio(ctx: Context, ti: Any?): String {
        val dispId = runCatching {
            ti?.let { call(it, "getTaskInfo") }?.let { field(it, "displayId") } as? Int
        }.getOrNull() ?: -1
        return Bounds.screenKeyFor(ctx, dispId)
    }

    /**
     * 点比例按钮：只做两件事 ——
     *  1) 把该应用的小窗尺寸**按比例写进记忆**（复用已有记忆链路）；
     *  2) 让 **MIUI 自己重开一次小窗**（退全屏 → 再用它自己的入口开回小窗）。
     *
     * 为什么不在活动窗口上直接改 bounds：MIUI 自己维护 bounds 与 freeformScale **两份**状态，
     * 它的 `scaleDownIfNeeded` 还会偷偷改 scale —— 我们自己改一份、它用另一份盖回来，
     * 真机表现就是「比例弹回上一次、背景和窗口对不上」。走重开则尺寸/scale/装饰/背景/记忆
     * 全部由 MIUI 自己的打开流程生成，天然一致（记忆里已有的 restor 路径会套用新尺寸）。
     */
    private fun applyRatio(decoration: Any, picked: Float, flip: Boolean, why: String) {
        try {
            val ctx = AppCtx.get() ?: return
            val info = field(decoration, "mRunningTaskInfo") ?: return
            val pkg = taskPkg(info) ?: return
            val id = taskId(decoration)
            val repo = field(decoration, "mMultiTaskingTaskRepository")
            val ti = repo?.let { freeformTask(it, id) }
            val inFreeform = taskWindowingMode(info) == MODE_FREEFORM && ti != null
            val dm = ctx.resources.displayMetrics
            val top0 = statusBarHeight(ctx)
            val maxH = dm.heightPixels - top0
            val visual = if (inFreeform) call(ti, "getScaledBounds") as? Rect else null
            val real = if (inFreeform) call(ti, "getBounds") as? Rect else null
            val key = Bounds.key(pkg, Bounds.screenKey(ctx))
            val landscape = if (flip) {
                !isLandscape(visual?.width() ?: dm.widthPixels, visual?.height() ?: maxH)
            } else {
                // 方向交给系统：按**窗口当前形状**决定比例朝哪个方向（不再用记忆的方向）
                isLandscape(visual?.width() ?: dm.widthPixels, visual?.height() ?: maxH)
            }
            val ratio = ratioFor(picked, landscape)
            // 目标：**高度与现在一致**（上下对齐），宽度 = 高 × 比例；**左右居中**
            var h = real?.height() ?: (maxH * 2 / 3)
            var w = (h * ratio).toInt()
            if (w > dm.widthPixels) {
                w = dm.widthPixels
                h = (w / ratio).toInt()
            }
            if (h > maxH) {
                h = maxH
                w = (h * ratio).toInt()
            }
            // 诊断：把三个真实数字打出来 —— 用户在"选比例 / 切横竖屏 / 切内外屏"三条路径上都复现了
            // "窗口比例 ≠ 应用实际比例"（底部一条白色死区）。要判断该改 bounds 还是该改 scale，
            // 必须先拿到 real(getBounds) / visual(getScaledBounds) / freeformScale 三个值。
            runCatching {
                val scale = runCatching { call(ti, "getFreeformScale") }.getOrNull()
                    ?: ti?.let { t -> runCatching { field(t, "mFreeformScale") }.getOrNull() }
                Logx.always(
                    "比例诊断($why): bounds=$real scaledBounds=$visual freeformScale=$scale " +
                        "目标比例=${"%.3f".format(ratioFor(picked, landscape))}"
                )
            }
            // ★★★ v0.4.48 用户要求：「比例保存，如果需要位置的话，也以左上角这个点的位置作为基准」。
            //   原来这里是"左右居中 + 上下沿用当前 top"（left 硬算居中），与记忆点无关。
            //   现在改成：**优先用记忆里的左上角点**当基准；没有记忆时才退回"居中 + 当前 top"。
            val memoPoint = Bounds.get(ctx, pkg, screenKeyForRatio(ctx, ti))
            val left = if (memoPoint != null) {
                memoPoint.left.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
            } else ((dm.widthPixels - w) / 2).coerceAtLeast(0)
            val topSeed = memoPoint?.top ?: (real?.top ?: top0)
            val top = topSeed.coerceIn(top0, (maxH - h).coerceAtLeast(top0))
            val target = Rect(left, top, left + w, top + h)
            Logx.always(
                "比例落位: 基准=${if (memoPoint != null) "记忆点(${memoPoint.left},${memoPoint.top})" else "居中+当前top"} " +
                    "-> $target（尺寸 ${w}x$h）"
            )
            val dispId2 = (ti?.let { call(it, "getTaskInfo") }?.let { field(it, "displayId") } as? Int) ?: -1
            val screenKey2 = Bounds.screenKeyFor(ctx, dispId2)
            // ★★★ 2026-10-06 真机对照实验（关键）：
            //   同样一条 `0,397,1672,2069` 记忆，写成 **@0.66** ⇒ MIUI 采用（窗口保持 1672×1672，
            //   旋转后不变 ✓）；写成 **@1.0** ⇒ **MIUI 完全不采用**（直接回默认 1170×1870 ✗）。
            //   而这里原来硬编码 `1.0f`（旧注释以为"scale=1.0 才让内容填满窗口"—— 按 NOTES §40.3 的
            //   SurfaceFlinger 实证，这个字段**根本不驱动渲染**，写 1.0 只会让 MIUI 不认这条记忆）。
            //   ⇒ 现在写 **MIUI 当前真实缩放（可视 ÷ 真实）**，与它自己用的值一致。
            val curScale = runCatching {
                val vis2 = call(ti, "getScaledBounds") as? Rect
                val re2 = call(ti, "getBounds") as? Rect
                if (vis2 != null && re2 != null && re2.width() > 0) {
                    vis2.width().toFloat() / re2.width()
                } else 0f
            }.getOrDefault(0f)
            val memoScale = if (curScale > 0f) curScale else 0.66f
            // ★★★ v0.4.48：尺寸不再进记忆 ⇒ 只落**左上角点**（+ 比例）。
            //   `target` 的尺寸只在本次重开时用作 pendingTarget（见下），不写入长期记忆；
            //   下次开窗时位置取这个点、尺寸由当时的比例/MIUI 决定。
            Bounds.putPoint(ctx, pkg, screenKey2, target.left, target.top, memoScale)
            // ★★★ v0.4.53：**同时记下方向** —— 这是"切成横屏后被系统拉回竖屏"的修复。
            //   去掉尺寸记忆后，方向没有别的载体（`memoScale` 是缩放比，不含方向），
            //   所以必须显式记一位，恢复端才知道要把宽高对调。
            Bounds.putOrientation(ctx, pkg, screenKey2, w > h)
            Logx.always(
                "比例调整($why): ${real ?: "无小窗"} -> 记忆点 ${target.left},${target.top} scale=$memoScale" +
                    " 方向=${if (w > h) "横" else "竖"}（比例 ${"%.3f".format(ratio)}，本次目标 $target），随后关闭并重开小窗"
            )
            // 记下「这个尺寸是我们刚指定的」，短时间内不要让记录链路用旧 bounds 覆盖它
            val memoKey = Bounds.key(pkg, Bounds.screenKey(ctx))
            pendingTarget[memoKey] = PendingTarget(target, android.os.SystemClock.elapsedRealtime())
            // 关闭→重开期间屏蔽记录；重开稳定后再按真实结果写一次（下面 3.5s 后解除并记录）
            suppressRecord[memoKey] = android.os.SystemClock.elapsedRealtime() + 10_000
            // ★★★ v0.4.53：**把这次操作也登记成"用户意图"**，让对账在随后一段时间内让位。
            //   真机取证（02:24:09）：用户点了横屏按钮、窗口已横过来，8s 后 `userOpenUntil` 过期，
            //   对账立刻以"左上角点不一致"为由关窗重开，并用竖屏尺寸把它顶回竖屏。
            //   比例/横竖屏是**用户的显式操作**，理应和对账冲突时优先。
            userOpenUntil[pkg] = android.os.SystemClock.elapsedRealtime() + 20_000
            Logx.always("比例调整: 已登记用户意图，20s 内对账不干预（pkg=$pkg）")
            // ★ 2026-10-05 用户反馈"点比例后等很久才出新小窗"：这里的 1800ms 等待是元凶
            //   （注释自己都写着"不用再原地等 1.8s"）。记忆读侧缓存已是 300ms，
            //   改为 **立即** 走"关闭→重开"，只保留 close 之后必要的 350ms 稳定窗。
            Handler(Looper.getMainLooper()).postDelayed({
                if (!inFreeform) {
                    openAsFreeform(decoration, id)
                } else {
                    // 用户指定的流程：**先写好记忆（上面已写）→ 关掉当前小窗 → 重新打开**。
                    // 关掉之后再开，系统才会走"创建小窗窗口"的流程（`getCustomFreeformRect`），
                    // 从而套用新尺寸；直接对已存在的小窗调启动接口只会把它带到前台（真机验证）。
                    markSelfRelaunch(id)
                    val closed = closeFreeformViaMiui(decoration)
                    // 只用**官方接口**重开（用户认可的方案）：转场方案（switchFullscreenToFreeform）
                    // 会按设计把桌面翻上来，导致"前台应用掉到后台"，已去掉该回退。
                    // 核验放宽：只要求任务回到 freeform（可见性在动画中不稳定，不作为判据），
                    // 未回到就再重试一次官方接口。
                    fun relaunchOnce(attempt: Int) {
                        if (!relaunchViaMiuiApi(decoration, id, target)) {
                            Logx.e("比例调整: 官方接口抛错（第 $attempt 次）")
                            return
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val info = field(decoration, "mRunningTaskInfo")
                                val ff = info != null && taskWindowingMode(info) == MODE_FREEFORM
                                if (ff) {
                                    Logx.e("比例调整: 官方接口已重开小窗（第 $attempt 次核验通过）")
                                    // ★ v0.4.61：重开成功 ⇒ 把真实几何写进 MIUI 自己的存储，
                                    //   否则它 getFreeformLastPosition 拿不到值，方向/旋转都会回退。
                                    runCatching { FreeformExtra.saveFreeformGeometry(decoration, "比例/横竖屏重开") }
                                } else if (attempt < 2) {
                                    // ★★★ v0.4.65 真机取证（03:54:26）：`getActivityOptions` 重开后
                                    //   任务**仍停在 mode=1（全屏）**，根本没变成小窗 —— 只用这一个
                                    //   接口在 HyperOS 4 上不可靠。
                                    //   ⇒ 立刻改用 ROM **自己的**「全屏→小窗」入口
                                    //     `MulWinSwitchAnimStarter#switchFullscreenToFreeform`
                                    //     （Miui-WindowManager-Shell.jar，SystemUI 侧，无需重启）。
                                    Logx.e("比例调整: 官方接口未重开小窗，改用 ROM 全屏→小窗入口（第 $attempt 次）")
                                    openAsFreeform(decoration, id)
                                    relaunchOnce(attempt + 1)
                                } else {
                                    Logx.e("比例调整: 两次都未重开小窗，最后再用 ROM 全屏→小窗入口一次")
                                    openAsFreeform(decoration, id)
                                }
                            }
                        }, 1_500)
                    }
                    Handler(Looper.getMainLooper()).postDelayed(
                        { relaunchOnce(1) }, if (closed) 350 else 0
                    )
                    // 重开稳定后：解除屏蔽并把**真实结果**记一次（保证记忆=用户看到的尺寸位置）
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            suppressRecord.remove(memoKey)
                            record(decoration, "afterRatioReopen")
                        }
                    }, if (closed) 1_500 else 1_200)
                }
            }, 120)
        } catch (t: Throwable) {
            Logx.e("比例调整失败", t)
        }
    }

    /** 记忆里刚被“比例按钮”指定的目标尺寸；在 TTL 内不许记录链路用旧 bounds 覆盖。 */
    private class PendingTarget(val bounds: Rect, val at: Long)

    private val pendingTarget = ConcurrentHashMap<String, PendingTarget>()

    /**
     * 关闭→重开期间的记录屏蔽（key → 截止时刻）。
     * 真机问题：关小窗的那一瞬间 MIUI 会把任务摆成全屏，记录链路会把这份"全屏"写进记忆，
     * 于是「尺寸/位置记忆」被污染（日志：`关闭时兜底记录 bounds=Rect(0,0-1672,2364)` 之后
     * 又出现 `记住 … = 0,692,1672,2364`）。
     */
    private val suppressRecord = ConcurrentHashMap<String, Long>()

    /** 诊断：每个（重载签名|包名）被调了几次 —— 只打前 6 次，用来确认"哪条打开路径走进来了"。 */
    private val ffrHits = ConcurrentHashMap<String, Int>()

    /**
     * 最近一次见到的**小窗应用包名**。
     * `MultiTaskingSizeLevel#getDestBoundsAndScale(Rect)` 的参数只有 Rect、不带包名，
     * 所以在这里记住"当前小窗是谁"，供尺寸档位替换时取记忆用。
     */
    @Volatile private var lastFreeformPkg: String? = null
    /** 诊断：每个 task 做过几次"记忆 vs 实际左上角"核对（前 3 次）。 */
    /**
     * "正在关闭→重开对齐"的任务：taskId → 冷却到的时刻（elapsedRealtime）。
     *
     * ★★★ v0.4.42 真机根因：**旋转后有 3 条路都想纠正几何**——`maybeReapplyOnConfigChange` 的 3s 延迟套用、
     * relayout 里的"对账"、以及重开自身的重试；每条都会"关窗→重开"，于是用户看到窗口连关带开好几次。
     * 实测（v0.4.41，12:34 旋转窗口）：`关闭→重开到` ×3、`已按 MIUI 自己的方式关闭` ×3、`未重开小窗，重试` ×3。
     * ⇒ 这里做**串行化**：同一任务在冷却期内只允许做一次关窗重开，其余路径让位（只记一行 skip）。
     */
    private val aligningUntil = ConcurrentHashMap<Int, Long>()

    /**
     * ★★★ v0.4.45 **用户主动开窗**的豁免窗口：包名 → 截止时刻（elapsedRealtime）。
     *
     * 用户从**侧边栏 / 桌面图标**打开小窗时，MIUI 走自己的建窗流程（`startSidebarLaunchFreeform`），
     * 已经把记忆的位置+尺寸+比例正确套用（真机取证 22:48:28.680
     * `窗口布局: task=4167 mode=5 bounds=Rect(1092,140-1910,1513)` == 记忆值）。
     * 此时模块的「开窗后对账」**必须让位**：它一旦判"不一致"就会 `closeFreeformViaMiui` +
     * 重开，用户看到的就是「黑屏 → 被关掉 → 再打开」。
     * 用户口径（2026-10-15）：「直接打开小窗，通过记忆的位置和尺寸、还有比例去打开」——
     * 即一次到位，不许模块事后折腾。
     */
    private val userOpenUntil = ConcurrentHashMap<String, Long>()

    /**
     * ★★★ v0.4.65 真机取证（03:51:27）：
     *
     *   03:51:27.090  配置变更(延迟套用): com.tencent.mm 记忆点=0,440 -> Rect(0,440-1360,2364)（等 MIUI 安定 3s 后重开）
     *   03:51:27.162  userClosed: task=4418 非小窗(mode=1) 且不可见 ⇒ 视为已被关掉
     *   03:51:27.162  对齐: task=4418 在核验期间被用户关掉 ⇒ 不再重开（尊重用户意图）
     *
     * 问题：`closeFreeformViaMiui()` 是**模块自己**把小窗关掉的，关掉后任务必然短暂处于
     * `mode=1`（全屏）且不可见 —— 这是**模块自己制造的过渡态**，却被 `userClosed()` 当成
     * "用户把窗关了" ⇒ 重开链当场放弃 ⇒ 用户看到"横屏一下又弹回去"。
     *
     * 修法：模块自己发起重开时登记一个**短窗口期**，期内不把过渡态判为用户关闭；
     * 窗口期过后（或用户确实动了手）仍按原逻辑尊重用户意图。
     */
    private val selfRelaunchUntil = ConcurrentHashMap<Int, Long>()

    private fun markSelfRelaunch(id: Int) {
        // 窗口期覆盖"关闭→重开→核验→稳定"的完整过程（核验 1.5s×最多 3 次 + 余量）。
        selfRelaunchUntil[id] = android.os.SystemClock.elapsedRealtime() + 12_000
    }

    private fun inSelfRelaunch(id: Int): Boolean =
        (selfRelaunchUntil[id] ?: 0L) > android.os.SystemClock.elapsedRealtime()

    /**
     * ★★★ v0.4.50：**开窗安定期** —— 任务刚进 freeform 的时刻（taskId → elapsedRealtime）。
     *
     * 用户反馈：「很多小程序第一次打开的时候都会黑屏，然后关闭」。
     * 根因是对账在窗口**还没安定**（bounds 仍是 MIUI 默认值）时就判定"点不一致"并关窗重开。
     * 这里记录"首次见到该任务处于 freeform"的时刻，[SETTLE_MS] 之内只观察不纠正，
     * 让 MIUI 有机会把我们给的矩形真正落上去。
     */
    private val openedAt = ConcurrentHashMap<Int, Long>()

    /** 开窗安定期：MIUI 从建窗到应用我们的矩形，真机实测需要 1~2s，取 2500ms 留余量。 */
    private const val SETTLE_MS = 2500L

    /** 最近一次"检测到旋转/换屏"的时刻（WCT 探针只在它之后 8s 内记录，避免刷屏）。 */
    @Volatile private var lastConfigChangeAt = 0L

    private val memChecked = ConcurrentHashMap<Int, Int>()

    /** 全屏里点比例：直接用 MIUI 自己的「全屏→小窗」入口开小窗，打开时会套用刚写下的记忆。 */
    private fun openAsFreeform(decoration: Any, id: Int) {
        runCatching {
            val org = field(decoration, "mTaskOrganizer") ?: return
            val info = field(decoration, "mRunningTaskInfo") ?: return
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
            val starter = ctl.javaClass.getMethod("getMulWinSwitchStarter").invoke(ctl) ?: return
            starter.javaClass.getMethod(
                "switchFullscreenToFreeform",
                cls(Constants.CLS_SHELL_TASK_ORG),
                android.app.ActivityManager.RunningTaskInfo::class.java
            ).invoke(starter, org, info)
            Logx.always("比例调整: 全屏 → 按比例开小窗（task=$id）")
        }.onFailure { Logx.e("全屏开小窗失败", it) }
    }

    /**
     * 小窗里点比例：退成全屏 → 用 MIUI 自己的入口开回小窗。
     * 真机上第一次调 `switchFullscreenToFreeform` 会抛 `InvocationTargetException`（窗口刚退出来还没稳），
     * 所以带重试，并把根因打出来。
     */
    /**
     * MIUI 的动画入口要求跑在它自己的线程上（否则 `IllegalStateException: must be called on Handler`）。
     * 具体是哪个 handler 没有公开文档，这里按候选依次试，并把成功/失败都记进日志。
     */
    private fun miuiPosters(decoration: Any): List<Pair<String, (Runnable) -> Unit>> {
        val out = mutableListOf<Pair<String, (Runnable) -> Unit>>()
        // 真机断言栈：HandlerExecutor.assertCurrentThread <- Transitions.startTransition
        // → 必须在 Transitions 自己的 ShellExecutor 上调用（MiuiDecorationDot 就有 mTransitions）
        runCatching {
            val tr = field(decoration, "mTransitions")
            (tr?.javaClass?.getMethod("getMainExecutor")?.invoke(tr))?.let { ex ->
                out += "transitions.mainExecutor" to { r ->
                    ex.javaClass.getMethod("execute", Runnable::class.java).invoke(ex, r)
                }
            }
        }
        out += "主线程" to { r -> Handler(Looper.getMainLooper()).post(r) }
        runCatching {
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
            val tc = ctl.javaClass.getMethod("getMultiTaskingThreadController").invoke(ctl)
            (tc.javaClass.getMethod("getSecondaryAnimHandler").invoke(tc) as? android.os.Handler)?.let { h ->
                out += "secondaryAnimHandler" to { r -> h.post(r) }
            }
            (tc.javaClass.getMethod("getAnimExecutor").invoke(tc))?.let { ex ->
                out += "animExecutor" to { r -> ex.javaClass.getMethod("execute", Runnable::class.java).invoke(ex, r) }
            }
            (ctl.javaClass.getMethod("getBackgroundExecutor").invoke(ctl))?.let { ex ->
                out += "backgroundExecutor" to { r -> ex.javaClass.getMethod("execute", Runnable::class.java).invoke(ex, r) }
            }
        }
        return out
    }

    /**
     * 用**小米自己的接口**以小窗模式重新拉起：`MiuiMultiWindowUtils.getActivityOptions(...)`。
     *
     * 依据（HyperOS 4 源码）：侧栏拖出小窗走的就是
     *   `MulWinSwitchTransition.startIconDragFreeform` →
     *   `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, x, y)` + `sendPendingIntent`
     *   + `startTransition(ANIMATION_ICON_DRAG_TO_FREEFORM)`。
     * 它用的是「图标拖拽开小窗」的转场，**不会**像 `switchFullscreenToFreeform`(11100) 那样
     * `reorder(homeTask, true)` 把桌面提到前台 —— 这正是"露桌面"的唯一来源。
     * 打开时系统会调 `getCustomFreeformRect` → 套用我们写下的记忆（含新比例）。
     */
    /**
     * 按 MIUI 自己的方式**关闭当前小窗**：`MulWinSwitchTransition#startCloseFullOrFreeform`。
     * 它做 `setAlwaysOnTop(false)+reorder(false)+setBounds(空)`、退出 freeform 态、
     * 并且 `setStillFreeFormAfterExiting(taskId,true)` —— 之后还能再开回小窗。
     * 必须投到 `Transitions.getMainExecutor()`（内部 `startTransition` 有线程断言）。
     */
    /**
     * 取"MIUI 自己做转场用的那个 executor"。关窗与重开都必须投到它上面，否则
     * `Transitions.startTransition` 的 `HandlerExecutor.assertCurrentThread()` 直接抛
     * `IllegalStateException: must be called on Handler`。
     *
     * ★★★ v0.4.38 反编译取证（`tools/miui-jars/Miui-WindowManager-Shell.jar`，用 tools/dexscan.py 查）：
     *   · `MiuiDecorationController` 的字段表里**没有** `mTransitions`（只有 mMainExecutor/mBgExecutor/
     *     mTaskOrganizer/mRunningTaskInfo/…）⇒ 原来 `field(decoration,"mTransitions")` **恒为 null**，
     *     关窗（closeFreeformViaMiui）与重开两处都拿不到 executor，且因为返回值没人看，一直是静默失败。
     *   · `mTransitions:Lcom/android/wm/shell/transition/Transitions;` 声明在
     *     **`MulWinSwitchTransitionAnim`**（`MulWinSwitchTransition` 的父类）上；
     *     而 `Transitions#getMainExecutor()` 确实存在（同一 jar 内可见）。
     *   ⇒ 唯一可靠取法：`MultiTaskingCtl.getMulWinSwitchTransition()` → `field(·,"mTransitions")`
     *     → `getMainExecutor()`（`field()` 本身会沿父类找，见其实现）。
     */
    private fun transExecutor(decoration: Any): Any? = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
        val trans = ctl.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl)
        val transitions = trans?.let { field(it, "mTransitions") } ?: field(decoration, "mTransitions")
        transitions?.javaClass?.getMethod("getMainExecutor")?.invoke(transitions)
    }.getOrNull()

    private fun closeFreeformViaMiui(decoration: Any): Boolean {
        val runner = Runnable {
            runCatching {
                val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                val trans = ctl.javaClass.getMethod("getMulWinSwitchTransition").invoke(ctl)
                    ?: return@runCatching
                val info = field(decoration, "mRunningTaskInfo") ?: return@runCatching
                trans.javaClass.getMethod(
                    "startCloseFullOrFreeform", android.app.ActivityManager.RunningTaskInfo::class.java
                ).invoke(trans, info)
                Logx.e("比例调整: 已按 MIUI 自己的方式关闭当前小窗")
            }.onFailure { Logx.e("关闭小窗失败", it) }
        }
        val exec = transExecutor(decoration) ?: return false
        runCatching { exec.javaClass.getMethod("execute", Runnable::class.java).invoke(exec, runner) }
            .onFailure { Logx.e("投递关闭失败", it) }
        return true
    }

    /**
     * 用**纯 ActivityOptions** 以小窗模式重开（等价于 `am start --windowingMode 5`）。
     *
     * ★★★ 2026-10-06 关键：**这是唯一会听我们尺寸的重开方式**。
     *   · `am start --windowingMode 5`（本函数这条路）：MIUI 走**自己的建窗流程**，
     *     会问我们的钩子要矩形（`getFreeformDefaultLaunchBounds`/`getCustomFreeformRect`）
     *     ⇒ 记忆的位置与**比例**都被采用（真机取证：`核对通过(位置+尺寸) rect=Rect(0,397-1672,2069)`）。
     *   · `MiuiMultiWindowUtils.getActivityOptions(ctx,pkg,true,x,y)`：那两个 int 只是**位置提示**，
     *     尺寸由 MIUI 自己定（NOTES:414 取证「小窗就落在起手点附近」）⇒ 永远不听我们的尺寸 ✗。
     *   所以重开一律优先走本函数，MIUI 那条只作兜底。
     */

    /**
     * ★★★ 2026-10-06 用户点破 + 真机取证：**侧边栏 / 手动从桌面打开小窗会按记忆比例**，
     *   而模块用的 `MiuiMultiWindowUtils.getActivityOptions(ctx,pkg,true,x,y)` **只带位置、尺寸由 MIUI 定**。
     *   取证（用户侧边栏开窗那一刻的日志）：
     *     套用比例目标 aweme -> Rect(0,354-1672,2026)（整块）
     *     核对通过(位置+尺寸): task=33 pkg=aweme rect=Rect(0,354-1672,2026)   ← 实际 == 记忆 ✓
     *   ⇒ 重开就用**最朴素的 startActivity（不带任何 ActivityOptions）**，与用户点桌面图标完全同路：
     *     MIUI 走自己的建窗流程 ⇒ 会问我们的钩子 ⇒ 记忆的位置与比例都被采用。
     */


    /**
     * ★★★ 2026-10-06 用户点破 + 真机取证：**侧边栏开小窗会按记忆比例**（`核对通过(位置+尺寸)`），
     *   而模块用过的 `getActivityOptions`（只带位置）、纯 `startActivity`（MIUI 不认我们的矩形）
     *   都不行。侧边栏走的是 **MIUI 自己的小窗入口 `switchFullscreenToFreeform`** ——
     *   它重走建窗流程 ⇒ 会问我们的钩子 ⇒ 记忆的位置与比例都被采用。
     *   上次直调它抛 `IllegalStateException: must be called on Handler {…}`（线程不对）；
     *   这里**自动从装饰对象里找出 MIUI 要求的那个 Handler** 再投递。
     */

    private fun relaunchPlainNoOptions(decoration: Any, id: Int): Boolean = runCatching {
        val ctx = AppCtx.get() ?: return false
        val info = field(decoration, "mRunningTaskInfo") as? android.app.ActivityManager.RunningTaskInfo
            ?: return false
        // ★ v0.4.55：同 [relaunchViaMiuiApi] —— 侧边栏小窗的 topActivity 可能为空，
        //   这里也必须用健壮的 [taskPkg]，否则兜底重开同样静默失效。
        val pkg = taskPkg(info)
        if (pkg.isNullOrEmpty()) {
            Logx.e("重开: 取不到包名（纯 startActivity 兜底也放弃）task=$id")
            return false
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: info.topActivity?.let {
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(it)
            } ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        Logx.always("重开: 纯 startActivity（与点桌面图标同路，建窗钩子会套用记忆）pkg=$pkg")
        true
    }.onFailure { Logx.e("纯 startActivity 重开失败", it) }.getOrDefault(false)

    private fun relaunchPlainFreeform(decoration: Any, id: Int, target: Rect): Boolean = runCatching {
        val ctx = AppCtx.get() ?: return false
        val info = field(decoration, "mRunningTaskInfo") as? android.app.ActivityManager.RunningTaskInfo
            ?: return false
        val intent = (field(info, "baseIntent") as? android.content.Intent)?.let { android.content.Intent(it) }
            ?: info.topActivity?.let {
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(it)
            } ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = android.app.ActivityOptions.makeBasic()
        // `setLaunchWindowingMode` 是 @hide API（公开 android.jar 里没有），必须反射
        runCatching {
            opts.javaClass.getMethod("setLaunchWindowingMode", Integer.TYPE).invoke(opts, 5)   // 5 = FREEFORM
        }.onFailure { Logx.e("setLaunchWindowingMode 反射失败", it) }
        ctx.startActivity(intent, opts.toBundle())
        Logx.always(
            "重开: plain freeform 启动（与 am start --windowingMode 5 同路，建窗钩子会套用记忆 @${target.left},${target.top}）"
        )
        true
    }.onFailure { Logx.e("plain freeform 启动失败", it) }.getOrDefault(false)

    private fun relaunchViaMiuiApi(decoration: Any, id: Int, target: Rect): Boolean = runCatching {
        val ctx = AppCtx.get() ?: return false
        val info = field(decoration, "mRunningTaskInfo") as? android.app.ActivityManager.RunningTaskInfo
            ?: return false
        // ★★★ v0.4.55 真机根因（横竖屏点了没反应 / 窗口卡在全屏）：
        //   原来这里写的是 `info.topActivity?.packageName ?: return false` —— **静默返回 false**。
        //   而本模块早就知道这个坑（NOTES 里写着）：
        //     「侧栏（侧边栏）拖出的小窗里 topActivity/baseActivity 可能为空」
        //   ⇒ 侧边栏开的小窗点「横竖屏」时，重开**直接放弃且不打印任何日志**，
        //     窗口就停在全屏态（真机 02:26:37.884 给出横屏矩形 → .900 变成 mode=1 全屏 → 之后全无日志）。
        //   改用本库既有的健壮取值 [taskPkg]（ComponentName 字段/getter → ActivityInfo →
        //   baseIntent.component → TaskDescription.label，四层兜底），并**把失败原因打出来**。
        val pkg = taskPkg(info)
        if (pkg.isNullOrEmpty()) {
            Logx.e("重开: 取不到包名（topActivity/baseActivity/baseIntent 全为空），放弃重开 task=$id")
            return false
        }
        val mmu = cls(Constants.CLS_MULTIWINDOW_UTILS)
        val optsCls = cls("android.app.ActivityOptions")
        val opts = mmu.getMethod(
            "getActivityOptions", Context::class.java, String::class.java,
            java.lang.Boolean.TYPE, Integer.TYPE, Integer.TYPE
        ).invoke(null, ctx, pkg, true, target.left, target.top) ?: return false
        val intent = (field(info, "baseIntent") as? android.content.Intent)?.let { android.content.Intent(it) }
            ?: info.topActivity?.let {
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(it)
            } ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent, optsCls.getMethod("toBundle").invoke(opts) as? android.os.Bundle)
        Logx.e("比例调整: 已用 MIUI getActivityOptions 以小窗模式重新拉起（pkg=$pkg @${target.left},${target.top}）")
        true
    }.onFailure { Logx.e("MIUI 小窗启动失败", it) }.getOrDefault(false)

    private fun reopenFreeform(decoration: Any, id: Int, attempt: Int) {
        val posters = miuiPosters(decoration)
        val poster = posters[(attempt - 1).coerceIn(0, posters.size - 1)]
        // 第 1 次：**不退全屏**，直接在活动小窗上调 MIUI 的入口 —— 它会重走一遍
        // 「按小窗打开」的流程（`getFreeformDefaultLaunchBounds` → 我们的记忆恢复），
        // 应用不会被推到后台（先退全屏会把应用切到后台，真机表现「重开后回到桌面」）。
        // 失败再退回「退全屏 → 再开」的老路。
        val needExitFirst = attempt >= posters.size
        val runner = Runnable {
            var ok = false
            runCatching {
                val org = field(decoration, "mTaskOrganizer") ?: return@runCatching
                val task = org.javaClass.getMethod("getRunningTaskInfo", Integer.TYPE).invoke(org, id)
                    ?: return@runCatching
                val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                val starter = ctl.javaClass.getMethod("getMulWinSwitchStarter").invoke(ctl)
                    ?: return@runCatching
                starter.javaClass.getMethod(
                    "switchFullscreenToFreeform",
                    cls(Constants.CLS_SHELL_TASK_ORG),
                    android.app.ActivityManager.RunningTaskInfo::class.java
                ).invoke(starter, org, task)
                ok = true
                Logx.always("比例调整: 已按新尺寸重开小窗（task=$id，route=${if (needExitFirst) "退出后重开" else "直接重开"}）")
            }.onFailure {
                val c = it.cause ?: it
                Logx.e(
                    "重开小窗失败 #$attempt: ${c.javaClass.simpleName}: ${c.message} @ " +
                        (c.stackTrace.take(5).joinToString(" <- ") { f -> "${f.className.substringAfterLast('.')}.${f.methodName}" })
                )
            }
            if (!ok) Handler(Looper.getMainLooper()).postDelayed(
                { reopenFreeform(decoration, id, attempt + 1) }, 900
            )
        }
        if (needExitFirst) {
            runCatching {
                val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                val fc = ctl.javaClass.getMethod("getMiuiFreeformModeController").invoke(ctl) ?: return
                fc.javaClass.getMethod(
                    "exitFreeformTask", Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE
                ).invoke(fc, id, false, false)
                Logx.always("比例调整: 已退出小窗，准备按新尺寸重开（task=$id）")
            }.onFailure { Logx.e("退出小窗失败", it) }
            Handler(Looper.getMainLooper()).postDelayed({
                Logx.always("重开小窗: 用 ${poster.first} 投递（第 $attempt 次）")
                runCatching { poster.second(runner) }
            }, 900)
            return
        }
        Logx.always("重开小窗: 用 ${poster.first} 投递（第 $attempt 次）")
        runCatching { poster.second(runner) }.onFailure { Logx.e("投递失败(${poster.first})", it) }
    }

    // ★ 这里原本是 `restoreScaleIfNeeded`：开窗后用 WindowContainerTransaction 把记忆里的
    //   freeformScale 提交给 MIUI。2026-10-01 真机实证（读 SurfaceFlinger 层变换）**它改不动画面**：
    //     字段 freeformScale（MIUI 自报）= 0.2507     ← 我们的提交确实写进来了
    //     实际渲染 toDisplayTransform scale = 0.66    ← MIUI 自己的值，根本没跟
    //   而 MIUI 的装饰层（三点 / 角柄 / 底栏）是按「bounds × 字段」摆位的 ⇒ 一提交就把
    //   "看得见的窗口" 与 "摸得到的装饰" 拆成两套坐标 —— 正是 NOTES 里记的「手柄消失、整窗不可用」。
    //   结论：尺寸不能靠事后提交 scale。这条通道已删除；正确的入口是 MIUI 自己算初始 scale 的那一步
    //   （见 NOTES 的「尺寸记忆」待办），不是在一个已经建好的窗口上事后改字段。

    private fun freeformTask(repo: Any, id: Int): Any? = runCatching {
        repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, id)
    }.getOrNull()

    private fun call(o: Any?, name: String): Any? =
        runCatching { o?.javaClass?.getMethod(name)?.invoke(o) }.getOrNull()

    // ---------------- 2.2 / 2.3 记录用户调整后的 bounds ----------------

    /** 被标记为「不绘制」的栏视图。用实例集合而不是全局开关，避免影响分屏/桌面的同名栏。 */
    /** 最近一次摸到小窗装饰的时间（毫秒）；"操作时显示、空闲隐藏"以此为准。 */
    @Volatile private var lastDecorTouchAt = 0L

    /**
     * 用户要求：三点栏 / 底部栏**没被操作时隐藏，操作窗口时显示**（尤其三点菜单显示 1~2 秒后再隐藏），
     * 与小白条的渐隐机制同思路。实现要点：
     *   · 触摸时立刻标记时间并让两个栏视图重绘（显现）；
     *   · 1.5s 后再重绘一次（此时已不满足"最近触摸"，于是自动隐藏）。
     * 只在开关开启（hiddenViews 收录了该视图）时才起作用，关掉开关行为不变。
     */
    private fun markDecorTouched() {
        lastDecorTouchAt = android.os.SystemClock.elapsedRealtime()
        runCatching { hiddenViews.toList().forEach { (it as? android.view.View)?.invalidate() } }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { hiddenViews.toList().forEach { (it as? android.view.View)?.invalidate() } }
        }, 1500)
    }

    private fun decorRecentlyTouched(): Boolean =
        android.os.SystemClock.elapsedRealtime() - lastDecorTouchAt < 1500

    private val hiddenViews: MutableSet<Any> =
        java.util.Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private fun markBar(decoration: Any, wantHide: () -> Boolean) {
        try {
            Cfg.reloadThrottled()
            val mode = windowingMode(decoration)

            // 1) 布局诊断（保留一行，排查 mode/bounds 用）。
            //    原来这里还有一条 gestureLayout 落盘：`persist(..., "gestureLayout")` 永远被
            //    isGestureRecord 拦掉（起点既不是 gestureTaskInfo 也不是 resizeEnd）＝ 死代码，已删。
            runCatching {
                val info = field(decoration, "mRunningTaskInfo")
                val b = info?.let { taskBounds(it) }
                if (info != null && b != null && b.width() > 0 && b.height() > 0) {
                    Logx.once(
                        "ff-layout-" + taskId(decoration) + "-" + mode,
                        "窗口布局: task=${taskId(decoration)} mode=$mode pkg=${taskPkg(info)} bounds=$b"
                    )
                }
            }

            // 2) 沉浸：隐藏上下栏。**不再限定自由小窗** —— 分屏里每个应用的
            //    "Miui Caption / Bottom Caption" 用的是同一个装饰类，之前被
            //    `mode == MODE_FREEFORM` 排除掉了（真机：2~6 分屏的顶栏底栏都在）。
            val host = field(decoration, "mMiuiDecorationRootViewHost") ?: return
            val view = host.javaClass.getMethod("getDecorationRootView").invoke(host) as? View ?: return
            decorByView[view] = decoration
            // 转场中窗口模式会短暂变成别的值，这时保持原状、不要急着恢复绘制，否则会闪一下
            val changed = when {
                !wantHide() -> hiddenViews.remove(view)
                // 自由小窗、2~6 个应用的分屏都用这套装饰 → 一律隐藏（全屏应用没有该栏，不受影响）
                else -> hiddenViews.add(view) || hiddenViews.contains(view)
            }
            if (changed) {
                Logx.always("沉浸: task=${taskId(decoration)} 栏隐藏=${hiddenViews.contains(view)} ($view)")
                view.post { view.invalidate() }
            }
        } catch (t: Throwable) {
            Logx.e("markBar 失败", t)
        }
    }

    /**
     * 手势结束时间戳。抬手回调里 MIUI 的 taskInfo 还是**旧 bounds**（新 bounds 由
     * WindowContainerTransaction 异步应用，实测 `up=旧值 变化=false`），
     * 所以要等随后的装饰布局再落盘：手势信号 + 布局信号一起用。
     */
    private val gestureEndedAt = WeakHashMap<Any, Long>()

    /** 最近一次「真实手指按在这个窗口装饰上」的时间（handleDownEvent）。记录端用它挡住系统过渡态。 */
    private val gestureTouchedAt = WeakHashMap<Any, Long>()

    /** 装饰视图 → 装饰对象。触摸 hook 拿到的是 View，需要反查任务与 TaskOrganizer。 */
    private val decorByView = WeakHashMap<View, Any>()

    /** 手势按下时的 bounds：抬起时只有真的变了才算用户调整（点一下不算）。 */
    private val gestureDownBounds = WeakHashMap<Any, Rect>()
    /** 上次见到的“配置键”（displayId:orientation）。用来检测旋转/内外屏切换。按 taskId 索引，避免换屏时装饰实例重建导致漏触发。 */
    private val lastConfigKey = ConcurrentHashMap<Int, String>()
    /** 已经为哪个配置键排程过套用，避免同一个配置变更重复触发。 */
    private val appliedForConfig = ConcurrentHashMap<Int, String>()

    private fun currentBounds(decoration: Any): Rect? =
        field(decoration, "mRunningTaskInfo")?.let { taskBounds(it) }

    // ---------------- 配置变更（旋转 / 内外屏切换）保比例 ----------------

    /**
     * 旋转 / 内屏↔外屏切换时，MIUI 对**现存**小窗做的是 relayout（不是重开），
     * 不会走 getFreeformRect 恢复记忆 —— 于是小窗被塞回系统默认比例（#1 / #2）。
     *
     * 这里在 relayout 里检测 orientation / displayId 变化，命中就把记忆里的尺寸
     * （等比缩放）直接套回窗口。
     *
     * 防误触发 / 防循环：
     * - 首次见到某装饰（lastConfigKey 为 null）不打扰——开窗时的恢复由 installLaunchBounds 负责；
     * - 同一配置键只排程一次（appliedForConfig）；
     * - 套用延迟 350ms，等 MIUI 把旋转/换屏后的布局安定下来，否则会被它的最终布局覆盖。
     */
    private fun maybeReapplyOnConfigChange(ctrl: Any, info: Any?) {
        val ti = info ?: return
        if (taskWindowingMode(ti) != MODE_FREEFORM) return
        val id = taskId(ctrl)
        if (id < 0) return
        val dispId = (field(ti, "displayId") as? Int) ?: -1
        val ctx = AppCtx.get() ?: return
        val orientation = ctx.resources.configuration.orientation
        val configKey = "$dispId:$orientation"
        val prev = lastConfigKey[id]
        if (prev == null) { lastConfigKey[id] = configKey; return }
        if (prev == configKey) { lastConfigKey[id] = configKey; return }
        lastConfigKey[id] = configKey
        lastConfigChangeAt = android.os.SystemClock.elapsedRealtime()
        // ★★★ v0.4.67 真机取证（03:57 连环重开）：**打破自激循环**。
        //   日志显示模块自己「关闭→重开」会再次触发本回调 ⇒ 又排一次「延迟套用」⇒ 又关闭重开：
        //     03:57:28 关闭→重开 → 03:57:37 配置变更(0:1->0:2) → 03:57:40 又关闭→重开
        //     → 03:57:44 配置变更(0:2->0:1) → 03:57:47 第三次关闭→重开 → 03:57:54 三次失败退纯 startActivity
        //   表现就是用户说的「旋转后过一会儿小窗消失再重开」。
        //   ⇒ 模块自发起重开期间（[inSelfRelaunch]）收到的配置变更**不再排程套用**，
        //     从根上断掉"重开→配置变更→再重开"的死循环。
        if (inSelfRelaunch(id)) {
            Logx.always("配置变更: task=$id 处于模块自发起重开中 ⇒ 不再排程套用（避免自激循环）")
            return
        }
        // ★★ 2026-10-06：配置变更（旋转/换屏）时**重置对账计数**。
        //   对账路径只允许每个窗口纠正一次（nth == 1），第一次旋转纠正过之后
        //   再旋转就不再纠了（真机：转回竖屏又变回 1170x1870 ✗）。
        //   旋转后 MIUI 会重排窗口 ⇒ 必须让对账重新有机会把记忆套回去。
        memChecked.remove(id)
        Logx.always("配置变更: 已重置对账计数 task=$id（旋转后允许重新套记忆）")
        // ★★★ 2026-10-06 最终修法：旋转后**等 MIUI 彻底安定（3s）**，再由模块主动走一次
        //   "对账重开"（reopenAtMemory）。理由（真机取证）：
        //     · 旋转**期间**重开 ⇒ 触发 getActivityOptions 内部重算 ⇒ 形状被改回默认 ✗
        //     · 旋转**安定后**重开 ⇒ 同一条路会采用我们的矩形（对账多次实测 `核对通过(位置+尺寸)` ✓）
        //   所以：不在旋转瞬间动手，等 3s 再套。
        val ctxCfg = AppCtx.get()
        val pkgCfg = runCatching { taskPkg(ti) }.getOrNull()
        if (ctxCfg != null && pkgCfg != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val memo = Bounds.get(ctxCfg, pkgCfg, Bounds.screenKeyFor(ctxCfg, dispId))
                        ?: Bounds.getAny(ctxCfg, pkgCfg)
                    if (memo != null) {
                        // ★★★ v0.4.48：尺寸不再来自记忆 ⇒ 目标 = **记忆点 + 当前 MIUI 尺寸**。
                        //   旋转后 MIUI 会给一个新的合屏尺寸，模块只负责把窗口挪回记忆点。
                        val cur = runCatching { field(ctrl, "mRunningTaskInfo")?.let { taskBounds(it) } }.getOrNull()
                        val base = cur ?: runCatching {
                            val dm = ctxCfg.resources.displayMetrics
                            Rect(0, statusBarHeight(ctxCfg), dm.widthPixels, dm.heightPixels)
                        }.getOrDefault(Rect(0, 0, 1, 1))
                        val t = placeInside(
                            ctxCfg,
                            Rect(memo.left, memo.top, memo.left + base.width(), memo.top + base.height())
                        )
                        // ⛔ 2026-10-06 回退：曾试过 WCT `removeTask` + 全新打开（想复刻用户手动"关掉再打开"），
                        //   但那会**把用户正在用的窗口直接移除**（真机：移除后小窗消失）—— 违背"不能搞出不可用的窗"
                        //   的红线，故回退为安全的 `reopenAtMemory`（只关小窗状态，不销毁 task）。
                        if (cur != null && cur.left == t.left && cur.top == t.top) {
                            Logx.always("配置变更(延迟套用): 左上角已在记忆点 ${cur.left},${cur.top} ⇒ 跳过（不关窗、不闪）")
                        } else {
                            // ★★★ v0.4.76：**不再"关闭→重开"**（用户反馈：旋转会丢小窗）。
                            //
                            //   真机取证（04:23:58）：旋转后这里调 reopenAtMemory ⇒ 关窗重开
                            //     → 04:23:59.052 `mode=5 → mode=1 bounds=Rect(0,0-2364,1672)`
                            //     ⇒ 窗口被摆成全屏，用户看到的就是"小窗消失/丢失"。
                            //
                            //   而且现在**横屏已由 MIUI 原生接口正确处理**（v0.4.75 起），
                            //   旋转时 MIUI 自己就会把窗口重排好，模块根本不需要关窗。
                            //   ⇒ 这里只做**非破坏性平移**：把窗口挪回记忆点，尺寸交给 MIUI。
                            //     挪不动就什么都不做（让 MIUI 自行 relayout），绝不销毁窗口。
                            //   本 ROM 的小窗控制接口里**没有**"只移动不重建"的公开方法
                            //   （MiuiFreeformModeControlImpl 只有 fromFullToFreeform / exitFreeformTask 等）。
                            //   而记忆点会在**小窗下次创建时**通过 `getCustomFreeformRect` 自动套用
                            //   ⇒ 这里**什么都不做**才是正确且安全的：让 MIUI 自己 relayout，
                            //     既不会丢窗，也不会闪。
                            Logx.always(
                                "配置变更(延迟套用): $pkgCfg 记忆点=${memo.left},${memo.top} " +
                                    "当前=${cur?.let { "${it.left},${it.top}" } ?: "?"} " +
                                    "⇒ 交给 MIUI 自行 relayout（不关窗、不重开、不闪）"
                            )
                        }
                    }
                }
            }, 3_000)
        }
        if (appliedForConfig[id] == configKey) return
        appliedForConfig[id] = configKey
        Logx.always("配置变更: $prev -> $configKey，排程套用记忆尺寸（task=$id）")
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { reapplyBoundsFromMemory(ctrl, dispId) }
        }, 350)
    }

    /**
     * 旋转 / 内外屏切换后把记忆尺寸套回去。
     *
     * ★★ 2026-10-06 用户口径：「小窗调成 1:1 → 旋转 → 回到原始比例；但关闭再重开又是 1:1」。
     *   真机日志实证：旋转走的是本函数（`配置变更: 0:1 -> 0:2` → 这里），
     *   而**原来这版**用的是「关闭 → 官方接口重开」：旋转刚发生时新装饰实例的 `mTransitions` 还没就绪
     *   ⇒ `closeFreeformViaMiui` 返回 false ⇒ 延迟 0ms ⇒ 37ms 后"重开"实际只是把旧窗口带到前台
     *   ⇒ MIUI 旋转后的默认布局原样保留，比例被切回原始。
     *   （日志：配置套用 09:18:27.257 → 重新拉起 09:18:27.294 → 实际 1170x1870 = 系统默认。）
     *
     * ⛔ 2026-10-06 **不要改回 WCT（`setBounds` + `setMiuiFreeformInfoChange`）**：
     *   那版（v0.4.10）真机被用户直接否掉 ——「有白边，而且操作部位是错位的，完全不可用」。
     *   根因见 NOTES §40.3 / §40.8①：WCT 只改 bounds/字段，**装饰（三点/角柄/触摸区）不跟着重排**，
     *   外框（bounds×字段）与内框（MIUI 自己的渲染缩放）成了两套坐标。用户明确不接受。
     *
     * ✅ 现在这条路（已验证一致）：
     *   ① 记忆矩形先 `clampKeepRatio` **等比缩**进当前屏可视区（旋转后宽高对调，比例不变，且不会被边界弹）；
     *   ② **关闭 → MIUI 官方接口重开**：bounds/scale/装饰/触摸区由 MIUI 一次建好，不存在白边与错位；
     *   ③ 关窗没成功也给足安定时间（`closed=false` 时 700ms），再**复核**；不一致就再套一次（最多一次）。
     */
    private fun reapplyBoundsFromMemory(ctrl: Any, dispId: Int, isRetry: Boolean = false) {
        runCatching {
            val info = field(ctrl, "mRunningTaskInfo") ?: return@runCatching
            if (taskWindowingMode(info) != MODE_FREEFORM) return@runCatching
            if (field(info, "isVisible") as? Boolean != true) return@runCatching
            // 用户刚在拖动/缩放（旋转/换屏时一般不在拖）：让位给用户手势
            val sinceGesture = android.os.SystemClock.elapsedRealtime() - (gestureEndedAt[ctrl] ?: 0)
            if (sinceGesture < 2500) {
                Logx.once("cfg-gesture-" + taskId(ctrl), "配置套用跳过：刚手势结束（task=${taskId(ctrl)}）")
                return@runCatching
            }
            val ctx = AppCtx.get() ?: return@runCatching
            val pkg = taskPkg(info) ?: return@runCatching
            val screen = Bounds.screenKeyFor(ctx, dispId)
            // 当前屏有记忆用当前屏；没有则拿其它屏记忆等比缩过来（首次换屏/折叠也保形状）
            val memo = Bounds.get(ctx, pkg, screen) ?: Bounds.getAny(ctx, pkg) ?: return@runCatching
            val dm = ctx.resources.displayMetrics
            val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
            // ★★★ 2026-10-06 收敛结论（用户提问点破 + 三轮真机否掉两条自创路）：
            //   用户手动关闭再打开能按记忆比例恢复，是因为走了 MIUI 自己的建窗流程、会问我们的钩子；
            //   而模块自创的两条路都不行：
            //     · `relaunchViaMiuiApi` 只带左上角（尺寸由 MIUI 定）⇒ 比例被切回原始；
            //     · WCT（setBounds/scale）被用户直接否掉：白边 + 装饰错位，完全不可用；
            //     · `reopenFreeform`（switchFullscreenToFreeform）要求 MIUI Transitions 自己的 Handler，
            //       从钩子线程投递必抛 `IllegalStateException: must be called on Handler` 并反复重试。
            //   ⇒ 旋转/换屏就用**与三点菜单比例按钮、位置对齐完全相同**的那条路：`reopenAtMemory`
            //     （登记 pendingTarget ⇒ 建窗钩子优先用它 + 屏蔽记录 + 关闭 + MIUI 官方接口重开）。
            //     这条路真机已验证装饰/触摸区一致（无白边、无错位）。
            // ★★★ 2026-10-06 真机调用栈实锤（用户要求"确认为什么旋转会形变，去 hook 这个地方"）：
            //   旋转后那次"算出 1170x1870"的 getCustomFreeformRect 调用，栈是
            //     MiuiMultiWindowUtils.getActivityOptions <- Hooks.relaunchViaMiuiApi <- reapplyBoundsFromMemory
            //   ⇒ **形变是模块自己的"重开"造成的**（getActivityOptions 内部重算了一遍矩形，
            //     我们返回的正方被它自己的算法覆盖）。
            //   ⇒ 现在旋转**什么都不做**：只记日志，让 MIUI 自己 relayout。
            //     若 MIUI 的 relayout 保留现存 bounds，比例就能天然保住（这正是用户说的"旋转不该关窗重开"）。
            val target = Bounds.clampKeepRatio(memo, area)
            Logx.always(
                "配置变更(不重开): pkg=$pkg screen=$screen 记忆=$memo 期望=$target " +
                    "（只记日志，让 MIUI 自己 relayout —— 重开会触发 getActivityOptions 把比例改回默认）"
            )
        }.onFailure { Logx.e("配置套用失败", it) }
    }

    /**
     * 把"没走建窗钩子"的小窗补到记忆位置（旋转后重排、开窗没套用记忆时都由它收口）。
     *
     * ⚠️ 2026-10-01 真机教训：**不能只发 setBounds**。只推 bounds 不动装饰 ⇒ 触摸区/三点栏/角柄
     * 仍按旧位置摆 ⇒ 用户实测「小窗处于不可操作状态」；WCT 那条路也已被用户否掉（白边 + 操作错位）。
     * ⇒ 走本仓库**已经验证一致**的那条路：**关闭小窗 → 用 MIUI 官方接口重开**，
     *   bounds/scale/装饰/触摸区由 MIUI 一次建好。
     *
     * ★★★ v0.4.40 真机验收修正（第 3 次尝试；前两次的失败都有日志、不是猜的）：
     *   · 尝试①（v0.4.37）用 `findTransitionsHandler` 反射**猜** Handler ⇒ 每次
     *     `IllegalStateException: must be called on Handler`；且函数 `return true` 把异常吞掉，
     *     reopenAtMemory 的两条回退**从未执行过**。
     *   · 尝试②（v0.4.38/39）改投 `Transitions.getMainExecutor()`（字段位置由 dex 反编译取证：
     *     `mTransitions` 声明在 `MulWinSwitchTransitionAnim` 上，不在 `MiuiDecorationController` 上）
     *     ⇒ 转场不再抛异常、日志 `switchFullscreenToFreeform 成功`，**但窗口没回来**：
     *     实测该序列之后 `dumpsys` 里已无 freeform 任务 —— 该入口的设计前提是"应用在全屏"，
     *     而我们**已经先把小窗关掉了**。
     *   · 现在改成与三点菜单比例按钮**完全同一条已验证序列**（applyRatio 那条，真机长期在用）：
     *     关闭 → `relaunchViaMiuiApi`（MIUI `getActivityOptions`，建窗时会问我们的
     *     `getCustomFreeformRect` ⇒ 采用刚登记的 pendingTarget 矩形）→ **按结果核验**
     *     （任务是否真的回到 freeform，而不是"调用没抛异常"）→ 最多重试 3 次
     *     → 仍失败退"纯 startActivity"，至少不让用户的小窗凭空消失。
     */
    private fun reopenAtMemory(ctrl: Any, target: Rect) = runCatching {
        val id = taskId(ctrl)
        val now = android.os.SystemClock.elapsedRealtime()
        if ((aligningUntil[id] ?: 0L) > now) {
            Logx.once("align-busy-$id", "对齐跳过：task=$id 正在关窗重开对齐中，不叠第二次（剩余 ${(aligningUntil[id] ?: 0L) - now}ms）")
            return@runCatching
        }
        aligningUntil[id] = now + 6_000
        val ctx = AppCtx.get()
        val info = field(ctrl, "mRunningTaskInfo")
        val pkg = info?.let { taskPkg(it) }
        val key = if (ctx != null && pkg != null) Bounds.key(pkg, Bounds.screenKey(ctx)) else null
        if (key != null) {
            // ★ 把"这次要用的 rect"登记成 pendingTarget —— 重开时建窗钩子优先用它
            //   （与三点菜单切比例同一机制），否则重开又拿到旧记忆值，白关一次窗。
            //
            // ★★★ v0.4.53 修复「横屏又变回竖屏」的**第二处**根因：
            //   `target` 是调用方按"当前 MIUI 尺寸"拼出来的（对账/配置变更那两条路都是），
            //   而横屏状态下 MIUI 给的仍可能是竖屏尺寸 ⇒ 这里把**竖屏尺寸**登记成 pendingTarget，
            //   重开时建窗钩子优先用它 ⇒ 刚转好的横屏被自己的重开顶回竖屏。
            //   真机取证（02:24:09）：`左上角点不一致 ... ⇒ 按记忆点重开（尺寸保持 1170x1870）`
            //   紧接着 `套用比例目标 -> Rect(0,162-1170,2032)`（1170x1870 竖屏）。
            //
            //   修法：登记前**按记忆里的方向校正宽高**，让"重开"不改变用户已选的方向。
            val memoDir = if (ctx != null && pkg != null) {
                Bounds.getOrientation(ctx, pkg, Bounds.screenKey(ctx))
            } else null
            var t = Rect(target)
            if (memoDir != null && memoDir != (t.width() > t.height())) {
                t = Rect(t.left, t.top, t.left + t.height(), t.top + t.width())
                t = placeInside(ctx, t)
                Logx.always("对齐: 重开目标已按记忆方向(横=${memoDir})校正 -> $t")
            }
            pendingTarget[key] = PendingTarget(t, android.os.SystemClock.elapsedRealtime())
            // 关闭→重开期间屏蔽记录（否则 MIUI 摆成全屏那一下会被写进记忆）
            suppressRecord[key] = android.os.SystemClock.elapsedRealtime() + 12_000
            // 后面真正重开时也用校正后的矩形
            @Suppress("NAME_SHADOWING") val target = t
            Logx.always("位置/尺寸对齐: 关闭→重开到 $target（task=$id）")
            // ★ v0.4.65 修正：这条「配置变更(延迟套用)」路径原来**没有**登记自发起重开，
            //   所以关窗后 MIUI 摆成全屏那一刻被 userClosed 当成"用户关掉了"（真机 03:54:28/38 两次）。
            markSelfRelaunch(id)
            val closed = closeFreeformViaMiui(ctrl)
            relaunchChain(ctrl, id, target, closed)
            if (key != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { suppressRecord.remove(key) }
                }, 6_000)
            }
            return@runCatching
        }
        Logx.always("位置/尺寸对齐: 关闭→重开到 $target（task=$id）")
        markSelfRelaunch(id)
        val closed = closeFreeformViaMiui(ctrl)
        relaunchChain(ctrl, id, target, closed)
        if (key != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { suppressRecord.remove(key) }
            }, 6_000)
        }
    }.onFailure { Logx.e("位置/尺寸对齐失败", it) }

    /** 关闭后按 [target] 重开的公共链路（带用户关窗尊重 + 核验重试）。 */
    private fun relaunchChain(ctrl: Any, id: Int, target: Rect, closed: Boolean) {
        fun relaunchOnce(attempt: Int) {
            // ★★★ v0.4.45 用户口径：「我在开窗过程中关掉小窗，它不许再强行打开」。
            //   重试链原来只问"是不是 freeform"，**不问"这个窗还在不在"** —— 用户在
            //   close 之后（或重试间隙）手动关掉窗口，重试仍会把应用重新拉起来 ⇒ 强行弹窗。
            //   这里加前置判定：任务一旦消失 / 不再是小窗 / 已被用户关掉，立即停止重开。
            if (userClosed(ctrl, id)) {
                Logx.always("对齐: task=$id 已被用户关掉（非 freeform 或任务已消失）⇒ 停止重开，尊重用户意图")
                return
            }
            if (!relaunchViaMiuiApi(ctrl, id, target)) {
                Logx.e("对齐: 官方接口抛错（第 $attempt 次）⇒ 退纯 startActivity")
                if (!relaunchPlainNoOptions(ctrl, id)) Logx.e("对齐: 纯 startActivity 也失败（窗口可能已消失）")
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    // 用户在这 2s 核验窗内关掉了小窗 ⇒ 不再重试，绝不强行拉起来
                    if (userClosed(ctrl, id)) {
                        Logx.always("对齐: task=$id 在核验期间被用户关掉 ⇒ 不再重开（尊重用户意图）")
                        return@runCatching
                    }
                    val now = field(ctrl, "mRunningTaskInfo")
                    val ff = now != null && taskWindowingMode(now) == MODE_FREEFORM
                    when {
                        ff -> {
                            Logx.always(
                                "对齐: 官方接口已重开小窗（第 $attempt 次核验通过）实际=${taskBounds(now)}"
                            )
                            runCatching { FreeformExtra.saveFreeformGeometry(ctrl, "对账重开") }
                        }
                        attempt < 3 -> {
                            Logx.e("对齐: 第 $attempt 次未重开小窗，重试")
                            relaunchOnce(attempt + 1)
                        }
                        else -> {
                            Logx.e("对齐: 三次都没重开，退纯 startActivity（避免窗口消失）")
                            relaunchPlainNoOptions(ctrl, id)
                        }
                    }
                }
            }, 2_000)
        }
        Handler(Looper.getMainLooper()).postDelayed({ relaunchOnce(1) }, if (closed) 350 else 700)
    }

    /**
     * 判定"用户已经把这个小窗关掉了"——用于**尊重用户意图**，不再强行重开。
     *
     * ★★★ v0.4.45：用户口径「我在打开小窗的过程中把它关掉，它不许再强行打开」。
     * 判据（任一成立即可）：
     *   · 任务已经查不到（被移除 / 退出了）；
     *   · 任务已不在 freeform **且** 明确不可见。
     * 注意：只做**否定判定**（明确"不是小窗"才停），拿不到信息时**不阻断**重开 ——
     * 否则会把"取不到字段"误判成"用户关掉了"，导致该重开的窗口不重开。
     * 取 organizer 一律走 **decoration 的 `mTaskOrganizer`**（本库既有且实测可用的路径，
     * 见 openAsFreeform / reopenFreeform）；`MultiTaskingControllerImpl` 上**没有**
     * `mTaskOrganizer` 字段，别去它那儿找。
     */
    private fun userClosed(ctrl: Any, id: Int): Boolean = runCatching {
        val org = field(ctrl, "mTaskOrganizer")
        val info = if (org != null) {
            runCatching { org.javaClass.getMethod("getRunningTaskInfo", Integer.TYPE).invoke(org, id) }
                .getOrNull()
        } else null
        if (info == null) {
            // 任务对象彻底消失 ⇒ 用户关掉了（或系统移除了）
            Logx.once("uc-gone-$id", "userClosed: task=$id 任务已消失 ⇒ 视为用户已关掉")
            return@runCatching true
        }
        val mode = taskWindowingMode(info)
        val visible = field(info, "isVisible") as? Boolean
        if (mode != MODE_FREEFORM && visible == false) {
            // ★ v0.4.65：这个"非小窗且不可见"的态，**可能是模块自己关窗准备重开**造成的，
            //   而不是用户关的。模块自发起重开的 8s 窗口期内予以豁免，避免把自己的过渡态
            //   误判成"用户关闭"从而放弃重开（真机取证 03:51:27 就是这个误判）。
            if (inSelfRelaunch(id)) {
                Logx.once(
                    "uc-self-$id",
                    "userClosed: task=$id 处于模块自发起重开的过渡态(mode=$mode 不可见) ⇒ 不算用户关闭"
                )
                return@runCatching false
            }
            Logx.once("uc-notff-$id", "userClosed: task=$id 非小窗(mode=$mode) 且不可见 ⇒ 视为已被关掉")
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    /**
     * ★★★ 位置与越界的**最终语义**（v0.4.50，修复 v0.4.49 的回归）：
     *
     * 两个用户反馈互相拉扯，必须同时满足：
     *   · 反馈B（v0.4.48）：「重开后往上移了」—— 点是用户放的，**不许搬家**
     *   · 反馈1（v0.4.49）：「首次打开黑屏然后关闭」—— 窗口**必须完整落在屏内**
     *     （v0.4.49 我为了不改点，干脆不夹右下 ⇒ 窗口顶到 `482,713-1652,2583`、
     *       `807,524-1977,2394` 这种越界状态，屏外部分渲染为黑 + 随后被系统收拾掉）
     *
     * ⇒ 正解：**点优先，改尺寸**。
     *   · 点的 left/top **永远原样保留**（只在该点本身完全出屏时才拉回）
     *   · 若 `点 + 尺寸` 伸出屏幕 ⇒ **等比缩小尺寸**（保住宽高比），让它从该点起完整放得下
     *   · 缩到放不下（点太靠边）时，才退化为"把点往回收一点"，但**优先缩尺寸**
     *
     * 这样既"在关闭的位置打开"（左上角不动），又不会出现屏外黑块。
     */
    private fun placeInside(ctx: Context?, r: Rect): Rect {
        if (r.width() <= 0 || r.height() <= 0) return Rect(r)
        val c = ctx ?: AppCtx.get() ?: return Rect(r)
        return runCatching {
            val dm = c.resources.displayMetrics
            val top0 = statusBarHeight(c)
            val maxW = (dm.widthPixels - 0).coerceAtLeast(1)
            val maxH = (dm.heightPixels - top0).coerceAtLeast(1)

            var x = r.left
            var y = r.top
            var w = r.width()
            var h = r.height()

            // 1) 点先夹进"屏幕允许的锚点范围"：保证从该点起至少有一块可见区域
            x = x.coerceIn(0, (maxW - 1).coerceAtLeast(0))
            y = y.coerceIn(top0, (dm.heightPixels - 1).coerceAtLeast(top0))

            // 2) 尺寸按"从该点起可用空间"等比缩小（只缩不放，绝不改变宽高比）
            val availW = (maxW - x).coerceAtLeast(1)
            val availH = (dm.heightPixels - y).coerceAtLeast(1)
            if (w > availW || h > availH) {
                val k = minOf(availW.toFloat() / w, availH.toFloat() / h)
                w = (w * k).toInt().coerceAtLeast(1)
                h = (h * k).toInt().coerceAtLeast(1)
            }
            Rect(x, y, x + w, y + h)
        }.getOrDefault(Rect(r))
    }

    /**
     * ★★★ v0.4.46：把要套用的矩形**等比缩回当前屏幕内**（只缩放、不裁边 ⇒ 比例不变）。
     *
     * ⚠️ v0.4.48 起：**只用于比例菜单的 pendingTarget**（那条路仍会整块指定矩形）；
     * 「记忆点恢复」那条路改用 [placeInside]（只夹位置、不缩尺寸）。
     *
     * 背景：记忆里可能存着越界矩形 —— 真机实测（2026-10-15）：
     *   `com.coolapk.market|1672x2364 = 1084,261,2254,2131`（right 2254 > 屏幕宽 1672，越界 582）
     *   `com.xingin.xhs|1672x2364   = 857,268,2027,2137`（越界 355）
     *   `com.ss.android.ugc.aweme   = 1549,186,2719,2055`（越界 1047）
     * 恢复时若原样套用，就是用户看到的「半边小窗在屏幕外」。
     * 原实现假设「超界会由 MIUI 自己的边界机制弹回」—— **已被真机证伪**
     * （`getCustomFreeformRect` 返回越界矩形后，`dumpsys window` 实测窗口仍越界）。
     *
     * 做法：复用 [Bounds.clampKeepRatio]（等比缩放 + 位置夹取），可视区取
     * 「状态栏以下 ~ 屏幕底部」。已经放得下的矩形**原样返回**，不做任何改动
     * （避免把用户故意贴边/大尺寸的窗口无故缩小）。
     */
    private fun fitIntoDisplay(ctx: Context?, r: Rect): Rect {
        if (r.width() <= 0 || r.height() <= 0) return Rect(r)
        val c = ctx ?: AppCtx.get() ?: return Rect(r)
        return runCatching {
            val dm = c.resources.displayMetrics
            val area = Rect(0, statusBarHeight(c), dm.widthPixels, dm.heightPixels)
            // 完全落在屏内 ⇒ 不动（保持用户原样）
            if (r.left >= area.left && r.top >= area.top &&
                r.right <= area.right && r.bottom <= area.bottom
            ) return Rect(r)
            Bounds.clampKeepRatio(r, area)
        }.getOrDefault(Rect(r))
    }

    /** 真实 rect → 可视 rect（MIUI 的 scaleBounds 就是"以左上角为锚"乘 scale）。 */
    private fun scaled(r: Rect, s: Float): Rect =
        if (s <= 0f || kotlin.math.abs(s - 1f) < 0.0001f) Rect(r)
        else Rect(r.left, r.top, r.left + (r.width() * s).toInt(), r.top + (r.height() * s).toInt())

    /** 可视 rect → 真实 rect（反算）。"按记忆中的尺寸新建"就靠它：新建时把 rect 给 MIUI， */
    /** MIUI 按**它自己的** scale 渲染 ⇒ 可视 = rect × scale = 记忆尺寸（我们从不推 scale）。 */
    private fun unscaled(v: Rect, s: Float): Rect =
        if (s <= 0f || kotlin.math.abs(s - 1f) < 0.0001f) Rect(v)
        else Rect(
            v.left, v.top,
            v.left + maxOf((v.width() / s).toInt(), 1),
            v.top + maxOf((v.height() / s).toInt(), 1)
        )

    /** MIUI 当前的 freeformScale（只读；模块从不写它）。 */
    private fun currentScale(ctrl: Any): Float = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
        val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
        val ti = repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, taskId(ctrl))
        (call(ti, "getFreeformScale") as? Float) ?: 0f
    }.getOrDefault(0f)

    /** MIUI 当前报告的**可视矩形**（getScaledBounds）——用户实际看到/操作的那块。 */
    private fun currentVisible(ctrl: Any): Rect? = runCatching {
        val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
        val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
        val ti = repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, taskId(ctrl))
        (call(ti, "getScaledBounds") as? Rect)?.takeIf { it.width() > 0 && it.height() > 0 }
    }.getOrNull()

    private fun installBoundsRecorder(m: MainHook, cl: ClassLoader) {
        // 证据探针：MIUI 的手势分发层（若 handleDown/UpEvent 不派发，至少这里能看到）
        listOf("dispatchDownToDecoration", "dispatchMoveToDecoration", "dispatchUpToDecoration").forEach { name ->
            m.hookMethod(cl, Constants.CLS_DECOR_VIEW_MODEL, name,
                arrayOf<Class<*>>(Integer.TYPE),
                XposedInterface.Hooker { chain ->
                    Logx.once("vm-$name", "手势分发: $name task=${chain.getArg(0)}")
                    markDecorTouched()
                    chain.proceed()
                })
        }
        // 拖动（移动位置）手势：按下记基线，抬起时若 bounds 变了就落盘
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "handleDownEvent", null,
            XposedInterface.Hooker { chain ->
                markDecorTouched()
                gestureTouchedAt[chain.thisObject] = android.os.SystemClock.elapsedRealtime()
                val r = chain.proceed()
                currentBounds(chain.thisObject)?.let {
                    gestureDownBounds[chain.thisObject] = Rect(it)
                    Logx.once("gdown-" + taskId(chain.thisObject), "手势按下: task=${taskId(chain.thisObject)} bounds=$it")
                }
                r
            })
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "handleUpEvent", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                val ctrl = chain.thisObject
                val before = gestureDownBounds.remove(ctrl)
                val now = currentBounds(ctrl)
                Logx.once(
                    "gup-" + taskId(ctrl),
                    "手势抬起: task=${taskId(ctrl)} down=$before up=$now 变化=${before != now}（新 bounds 稍后由布局带回）"
                )
                gestureEndedAt[ctrl] = android.os.SystemClock.elapsedRealtime()
                // 移动手势结束也可能把比例弹回（MIUI 内部两份状态），稍后核对一次
                r
            })
        // 拖动缩放结束：立即记录
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "onMiuiFreeformResizeEnd", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                val ctrl = chain.thisObject
                gestureEndedAt[ctrl] = android.os.SystemClock.elapsedRealtime()
                record(ctrl, "resizeEnd")
                // ★ v0.4.61：用户手动调完尺寸/位置 —— 这正是 MIUI 期望"保存几何"的时机
                //   （它自己在 changeMask & 0x2000 时调 Task.infiniteResizeFinish）。
                //   模块接管了部分几何流程，所以这里主动补一次，保证 MIUI 存储里始终有正确值。
                runCatching { FreeformExtra.saveFreeformGeometry(ctrl, "resizeEnd") }
                // 缩放的新 bounds 是回调之后异步应用的（真机实测：回调时刻读到的仍是旧尺寸 1170x1870），
                // 所以 700ms 后再补采一次，拿到的才是用户拖出来的尺寸。
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { record(ctrl, "resizeEndDelayed") }
                }, 700)
                r
            })
        // 每次 relayout：拖动结束 → 落盘位置与尺寸（一对 (bounds, scale)）；
        // 旋转/换屏 → 走 maybeReapplyOnConfigChange 关闭重开。
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "relayout", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                try {
                    val ctrl = chain.thisObject
                    val info = field(ctrl, "mRunningTaskInfo")
                    // 旋转 / 内外屏切换：现存小窗 relayout 而非重开，不会走 getFreeformRect 恢复记忆
                    runCatching { maybeReapplyOnConfigChange(ctrl, info) }
                    val freeform = info != null && taskWindowingMode(info) == MODE_FREEFORM
                    val visible = info?.let { field(it, "isVisible") as? Boolean } == true
                    // 窗口关掉/退出小窗后清掉对账计数：否则同一个 task 复用（关→开还是同一个 taskId）
                    // 时 3 次用满就永久不再对账，自动纠偏会**静默失效**（本轮调试就是这样被坑的）。
                    if (!freeform || !visible) memChecked.remove(taskId(ctrl))
                    // ★ v0.4.50：记录"首次见到该任务进 freeform"的时刻（= 开窗时刻），
                    //   供对账判断是否还在安定期；任务退出 freeform 时清掉，下次再开重新计时。
                    val tidHere = taskId(ctrl)
                    if (freeform && visible) {
                        openedAt.putIfAbsent(tidHere, android.os.SystemClock.elapsedRealtime())
                    } else {
                        openedAt.remove(tidHere)
                    }

                    // ★ 开窗后对账（用户要求：**左上角必须一致 + 尺寸按记忆新建**）：
                    //   记忆里存的是「可视矩形 ÷ 记录时 scale」。这里统一到**可视空间**比较：
                    //   不一致（位置或尺寸任一不对）就按 `可视 ÷ 当前 scale` 反算 rect，关闭→官方接口重开一次。
                    //   MIUI 自己的 scale 一直由 MIUI 决定（模块从不写），所以这条不会拆坏装饰。
                    if (freeform && visible) {
                        val id = taskId(ctrl)
                        val nth = memChecked.merge(id, 1, Int::plus) ?: 1
                        // ★★★ v0.4.50 用户反馈（关键回归）：「很多小程序第一次打开的时候都会黑屏，
                        //   然后关闭」。
                        //   真机取证（01:21:24，coolapk）：
                        //     24.354 窗口布局 mode=5 bounds=Rect(392,441-1280,2019)   ← 刚打开（MIUI 默认位）
                        //     24.360 左上角点不一致: 记忆点=502,261 实际=392,441 ⇒ 按记忆点重开
                        //     24.393 比例调整: 已按 MIUI 自己的方式关闭当前小窗     ← 刚开的窗被关掉
                        //   ⇒ 用户看到的就是「黑屏 → 窗口消失 → 再出现」。
                        //
                        //   根因：**对账在窗口"刚创建、还没安定"时就动手**。
                        //   窗口从创建到 MIUI 把我们给的矩形真正落上去，有 1~2s 的过渡期
                        //   （applyWct → relayout → 动画），期间 bounds 还是 MIUI 的默认值。
                        //   在这个窗口里比"点是否一致"必然不一致 ⇒ 必然关窗重开 ⇒ 必然黑屏。
                        //
                        //   修法：给**新任务**一个"安定期"（见 [openedAt]），期内只观察、不纠正；
                        //   过了安定期若仍不一致，才做一次纠正（那时确实是真不一致）。
                        val sinceOpen = android.os.SystemClock.elapsedRealtime() - (openedAt[id] ?: 0L)
                        val settling = sinceOpen < SETTLE_MS
                        if (settling) {
                            Logx.once(
                                "settling-$id",
                                "对账跳过: task=$id 开窗安定期中（${sinceOpen}ms < ${SETTLE_MS}ms），不关窗"
                            )
                        }
                        if (nth <= 3 && !settling) runCatching {
                            val ctx = AppCtx.get()
                            val pkg = taskPkg(info)
                            val dispId = (field(info, "displayId") as? Int) ?: -1
                            val screen = Bounds.screenKeyFor(ctx, dispId)
                            val memo = if (ctx != null && pkg != null) Bounds.get(ctx, pkg, screen) else null
                            val realNow = taskBounds(info)
                            if (pkg == null) {
                                Logx.always("核对: task=$id 取不到包名")
                            } else if (memo == null) {
                                Logx.always("核对: task=$id pkg=$pkg 该屏无记忆，实际=$realNow")
                            } else if (realNow != null) {
                                // ★★★ v0.4.48（用户要求：去除尺寸记忆，只记左上角点）：
                                //   对账**只比左上角点**，不再比尺寸 ——
                                //   尺寸已不属于记忆的内容，比它等于拿"别人的东西"判自己不合格。
                                //   历史教训正是如此：v0.4.41~v0.4.46 反复因为"尺寸不同"就关窗重开，
                                //   造成「黑屏→被关掉→再打开」。现在尺寸不同属于正常（MIUI 自己定），
                                //   只有**位置**对不上才需要纠正。
                                val samePos = memo.left == realNow.left && memo.top == realNow.top
                                if (samePos) {
                                    Logx.always("核对通过(左上角点): task=$id pkg=$pkg 点=${realNow.left},${realNow.top}（尺寸 ${realNow.width()}x${realNow.height()} 不参与对账）")
                                } else if (nth == 1) {
                                    val busy = (aligningUntil[id] ?: 0L) >
                                        android.os.SystemClock.elapsedRealtime()
                                    // ★★★ v0.4.45：**用户主动开窗**（侧边栏/图标）刚开出来的小窗，
                                    //   一律不对账、不关窗 —— 记忆已在建窗时正确套用（真机取证），
                                    //   这里再动一次就是用户看到的「黑屏→被关掉→再打开」。
                                    val userOpen = (userOpenUntil[pkg] ?: 0L) >
                                        android.os.SystemClock.elapsedRealtime()
                                    // ★★★ v0.4.77 真机取证（04:27:35）：
                                    //     04:27:35.895  配置变更: 已重置对账计数 task=4448（旋转后…）
                                    //     04:27:35.898  左上角点不一致: 记忆点=43,404 实际=1394,217 ⇒ 按记忆点重开
                                    //     04:27:35.898  位置/尺寸对齐: 关闭→重开到 Rect(43,404-1213,2274)
                                    //     04:27:36.474  task=4448 mode=1 bounds=Rect(0,0-2364,1672)  ← 变全屏
                                    //   ⇒ **旋转后 MIUI 重排窗口，左上角点自然与记忆点不同**，
                                    //     对账把这当成"位置不对"⇒ 关窗重开 ⇒ 中途被摆成全屏
                                    //     ⇒ 用户看到「旋转时小窗概率性重启/丢失」。
                                    //   旋转是**系统的正常重排**，不是用户把窗挪走了 ⇒ 必须豁免对账。
                                    val justRotated = (android.os.SystemClock.elapsedRealtime() - lastConfigChangeAt) < 8_000
                                    if (justRotated) {
                                        Logx.once(
                                            "rec-rotate-$id",
                                            "对账跳过：task=$id 刚经历配置变更/旋转（${android.os.SystemClock.elapsedRealtime() - lastConfigChangeAt}ms 前），" +
                                                "左上角差异是 MIUI 正常重排 ⇒ 不关窗重开（实际=$realNow）"
                                        )
                                    } else if (busy) {
                                        Logx.once("rec-align-busy-$id", "对账跳过：task=$id 正在关窗重开对齐中（不叠第二次）")
                                    } else if (userOpen) {
                                        Logx.once(
                                            "rec-align-useropen-$id",
                                            "对账跳过：task=$id pkg=$pkg 是用户主动开窗（侧边栏/图标），" +
                                                "记忆已正确套用 ⇒ 不关窗重开（实际=$realNow）"
                                        )
                                    } else {
                                        // 只按**记忆点 + 当前 MIUI 尺寸**重开（不带任何尺寸主张）
                                        val target = Rect(
                                            memo.left, memo.top,
                                            memo.left + realNow.width(), memo.top + realNow.height()
                                        )
                                        Logx.e(
                                            "左上角点不一致: task=$id pkg=$pkg 记忆点=${memo.left},${memo.top} " +
                                                "实际=${realNow.left},${realNow.top} ⇒ 按记忆点重开（尺寸保持 ${realNow.width()}x${realNow.height()}）"
                                        )
                                        reopenAtMemory(ctrl, target)
                                    }
                                }
                            }
                        }
                    }

                    // 拖动（移动位置）落盘：手势结束后 MIUI 通过 onTaskInfoChanged 带来新 bounds。
                    // 与缩放共用 record()，保证写进去的永远是一对 (bounds, scale)。
                    val endedAt = gestureEndedAt[ctrl]
                    if (freeform && visible && endedAt != null &&
                        android.os.SystemClock.elapsedRealtime() - endedAt in 0..3000 && !isMiniOrPinned(ctrl)
                    ) {
                        gestureEndedAt.remove(ctrl)
                        record(ctrl, "gestureMove")
                    }
                } catch (t: Throwable) {
                    Logx.e("relayout 记录失败", t)
                }
                r
            })
    }

    /**
     * 记录一次「用户调整结果」——**先做稳定性判定，两次读数一致才落盘**。
     *
     * ⚠️ 2026-10-01 真机 bug（用户实测）："每开关一次小窗，窗口就往屏幕右下漂一点"。
     * 根因：关闭/重开的那一刻 MIUI 会把任务摆成**过渡态**（越界/偏移的 rect，日志实测
     * `真实=Rect(671,660-1841,2530)`），旧代码把这份过渡值**原样**写进记忆 ⇒ 下次开窗就从
     * 更右下的位置开始，一轮一轮累积成"不断往右下移动"。
     * 现在：读一次快照 → 350ms 后再读一次，两次 (bounds, scale) 完全一致才写；
     * 窗口已关 / 已不是小窗 / 读数还在变 ⇒ 放弃这一次（宁可不记，也绝不记错）。
     */
    private fun record(controller: Any, why: String, attempt: Int = 1) {
        try {
            Cfg.reloadThrottled()
            if (!Cfg.rememberBounds) return
            // ★ 必须"最近 4 秒内有手指按在这个窗口上"才允许记录。
            //   真机证据：关窗/重开时 MIUI 自己会走一遍 handleUpEvent→relayout，那时窗口是
            //   **系统重建后的默认几何**（实测把记忆从用户拖的 94,891,1227,2562 改写成 270,388,1247,1828），
            //   于是"位置又记不住了"。系统过渡没有真实 DOWN，用 DOWN 时间戳就能挡住。
            val touched = gestureTouchedAt[controller] ?: 0L
            if (android.os.SystemClock.elapsedRealtime() - touched > 4000L) {
                Logx.once("rec-notouch-$why", "记录跳过：最近 4s 内窗口没有真实触摸（$why，判为系统过渡/重建）")
                return
            }
            val a = snapshot(controller, why) ?: return
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val b = snapshot(controller, if (attempt == 1) "$why/复核" else "$why/复核$attempt") ?: return@runCatching
                    // ★★★ v0.4.80：用**真实几何**判稳（`bounds` 是折叠过的 1×1，比较它恒为真）。
                    //   同时拒绝畸形尺寸：拖动/重开过程中 MIUI 可能短暂报出极小或越界的值，
                    //   这种"稳定"是假稳定，落盘会把记忆点写坏（真机：窗口被缩成 1×1 像素）。
                    val sane = b.real.width() >= MIN_SANE_W && b.real.height() >= MIN_SANE_H
                    if (!sane) {
                        if (attempt < 4) {
                            Logx.once(
                                "rec-insane-$why",
                                "记录跳过：几何畸形 ${b.real}（${b.real.width()}x${b.real.height()}" +
                                    " < ${MIN_SANE_W}x${MIN_SANE_H}），判为过渡态，第 ${attempt + 1} 次重试"
                            )
                            record(controller, why, attempt + 1)
                        } else {
                            Logx.once(
                                "rec-insane-final-$why",
                                "记录放弃：四次读数都是畸形几何 ${b.real}（不落盘，保持旧记忆）"
                            )
                        }
                        return@runCatching
                    }
                    if (b.real == a.real && kotlin.math.abs(b.scale - a.scale) <= 0.0001f) {
                        persist(b.pkg, b.bounds, why, b.scale, b.screen)
                    } else if (attempt < 4) {
                        // 还在动（真机实测：350ms 后 scale 仍从 0.66 变到 0.132）⇒ 隔久一点再看，
                        // 四次都不稳才放弃 —— 只写"两次读数一致"的最终态，绝不写过渡态。
                        Logx.once(
                            "rec-retry-$why",
                            "记录复核$attempt 不稳: ${a.real}@${a.scale} -> ${b.real}@${b.scale}，第 ${attempt + 1} 次重试"
                        )
                        record(controller, why, attempt + 1)
                    } else {
                        Logx.once(
                            "rec-unstable-final-$why",
                            "记录放弃（$why 四次复核都不稳）: ${a.real}@${a.scale} -> ${b.real}@${b.scale}"
                        )
                    }
                }
            }, 350L * attempt)
        } catch (t: Throwable) {
            Logx.e("record 失败", t)
        }
    }

    /**
     * 判定"几何是否可信"的最小尺寸。小于它视为过渡态（拖动/重开中 MIUI 会短暂报出极小值）。
     * 取 100px：正常小窗最小也有几百像素，100 已足够宽松，不会误伤真实的极小窗。
     */
    private val MIN_SANE_W get() = 100
    private val MIN_SANE_H get() = 100

    /** 一次读数快照（小窗 + 可见 + 非迷你/贴边 + 包名 + bounds + scale + 屏幕键）。 */
    private class Snap(
        val pkg: String,
        /** 进记忆的形态：只有左上角点（1×1，按用户"只记点"的要求）。 */
        val bounds: Rect,
        val scale: Float,
        val screen: String,
        /**
         * ★★★ v0.4.80 真实几何（**不折叠**）—— 仅用于 record 的一致性校验。
         *
         * 为什么必须有它：`bounds` 被折叠成 1×1 之后，`record` 里那句
         *   `if (b.bounds == a.bounds && |Δscale| <= 1e-4) persist(...)`
         * 中两个 Rect **永远相等**（都是 (x,y,x+1,y+1)，相等只看 left/top）。
         * 于是这道"两次读数一致才落盘"的闸门形同虚设 ——
         * 拖动中途读到畸形/极小 bounds 时照样落盘，把记忆点写坏。
         * 真机取证：`记录核对(gestureMove): 真实=Rect(591,888-2461,2057)` 之后
         * `落盘(bounds=Rect(591,888-592,889))`；用户随后遇到窗口被缩成 1×1 像素
         * ⇒ 白屏、拖不动（音频仍在播）。校验用真实值才能挡住过渡态。
         */
        val real: Rect
    )

    private fun snapshot(controller: Any, why: String): Snap? {
        val info = field(controller, "mRunningTaskInfo") ?: return null
        val mode = taskWindowingMode(info)
        if (mode != MODE_FREEFORM) {
            Logx.once("rec-mode-$mode", "记录跳过：windowingMode=$mode（非自由小窗）")
            return null
        }
        if (field(info, "isVisible") as? Boolean != true) {
            Logx.once("rec-hidden", "记录跳过：任务不可见，bounds 可能是回退态（$why）")
            return null
        }
        val pkg = taskPkg(info)
        if (pkg == null) {
            Logx.once("rec-nopkg", "记录跳过：取不到包名（$why）")
            return null
        }
        if (isMiniOrPinned(controller)) {
            Logx.once("rec-mini-$pkg", "记录跳过：迷你/贴边态（$why）")
            return null
        }
        val bounds = taskBounds(info)
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            Logx.once("rec-nobounds", "记录跳过：bounds=$bounds（$why）")
            return null
        }
        // 用户调的大小体现在 freeformScale 上 → 必须一起记（真机：bounds 尺寸恒定、只有 scale 变）
        var scale = 0f
        var vis: Rect? = null
        runCatching {
            // 控制器上没有这个字段（在装饰基类上），必须走单例，否则永远打不出来
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
            val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
            val ti = repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, taskId(controller))
            if (ti != null) {
                scale = (call(ti, "getFreeformScale") as? Float) ?: 0f
                vis = call(ti, "getScaledBounds") as? Rect
                Logx.e("记录核对($why): 真实=$bounds 可视=$vis scale=$scale")
            }
        }
        // ★★★ v0.4.48 用户要求：「去除对尺寸的记忆，只记忆横竖屏和小窗的左上角那个点的位置」。
        //   ⇒ 这里**只取左上角点**，尺寸信息到此为止（不再进记忆）。
        //   历史教训（顺带解决）：①「可视÷scale」会让尺寸乱跳/变小（scale 不受控，0.09~1.55）；
        //   ②「可视原样」依赖建窗时 scale==1.0，实测重开时 scale 常读到 0 ⇒ 失效。
        //   现在尺寸干脆不记 —— 恢复时由 MIUI 自己算，**位置**才是模块要保证的东西。
        val store = Rect(bounds.left, bounds.top, bounds.left + 1, bounds.top + 1)
        // 折叠屏内外屏分开记录：用**任务所在的 display** 算 key（外屏是另一个 displayId）
        val dispId = (field(info, "displayId") as? Int) ?: -1
        val screen = Bounds.screenKeyFor(AppCtx.get(), dispId)
        if (boundsLog.add("$pkg|$screen")) Logx.e("记忆 key: pkg=$pkg displayId=$dispId -> $screen")
        return Snap(pkg, store, scale, screen, Rect(bounds))
    }

    /**
     * 落盘一次「用户调整结果」。2026-10-01 重构后这里只剩两道**真正需要**的闸：
     *   · 关闭→重开小窗期间（suppressRecord）：那时 MIUI 会把任务摆成全屏，写进去就是污染
     *   · 刚用比例按钮指定过尺寸（pendingTarget）：别让旧 bounds 立刻把它盖回去
     * 被删掉的闸（都是"用流程掩盖模型错误"）：
     *   · 非手势来源一律拒写 ✗ —— 结果只有"手势"能落盘，其它路径全被拦（死代码）
     *   · 等于记忆 clamp 结果就跳过 ✗ —— 恢复端已改成原样套用，这个判据按屏幕坐标算，1px 抖动就翻脸
     *   · heal（就地修正越界记忆）✗ —— 记忆本就是 MIUI 未缩放坐标，没有"越界"可言
     *   · 记录时按屏幕 clamp ✗ —— 这条是把用户位置改成"右下贴边"的元凶
     *   · 满屏判定 ✗ —— 贴边/吸附/最大化是 MIUI 自己的行为，模块不替它判断（用户 2026-10-01 要求删）
     */
    private fun persist(pkg: String?, bounds: Rect, why: String, scale: Float = 0f, screenIn: String? = null) {
        if (pkg == null) {
            Logx.once("rec-nopkg-persist", "记录跳过：包名为空（$why）")
            return
        }
        val ctx = AppCtx.get() ?: return
        val screen = screenIn ?: Bounds.screenKey(ctx)
        // 关闭→重开期间一律不记录（那时 MIUI 会把任务摆成全屏，写进去就把记忆污染了）
        val key = Bounds.key(pkg, screen)
        suppressRecord[key]?.let { until ->
            if (android.os.SystemClock.elapsedRealtime() < until) {
                Logx.once("sup-$key", "记录跳过：正在关闭→重开小窗（$why）")
                return
            }
            suppressRecord.remove(key)
        }
        // 刚用比例按钮指定过尺寸：别让旧 bounds 把它覆盖掉
        pendingTarget[key]?.let { p ->
            when {
                bounds == p.bounds -> pendingTarget.remove(key)          // 已经套用上了
                android.os.SystemClock.elapsedRealtime() - p.at < 60_000 -> {
                    Logx.once("rec-hold-$pkg", "记录跳过：刚指定过比例（$why），等重开小窗套用 ${p.bounds}")
                    return
                }
                else -> pendingTarget.remove(key)
            }
        }
        // ★ 用户口径（2026-10-01）：**忠实记录原位置**；窗口万一超界，交给 MIUI 自己的边界机制弹回来。
        //   所以这里**不做**任何"越界就不记"的取舍（早前那版会因此丢掉用户位置）。
        //   只留一条诊断日志，方便出问题时一眼看出可视矩形是否越界。
        runCatching {
            if (scale > 0f) {
                val vis = Rect(
                    bounds.left, bounds.top,
                    bounds.left + (bounds.width() * scale).toInt(),
                    bounds.top + (bounds.height() * scale).toInt()
                )
                val dm = ctx.resources.displayMetrics
                val sw = maxOf(dm.widthPixels, dm.heightPixels)
                val sh = minOf(dm.widthPixels, dm.heightPixels)
                if (vis.left < 0 || vis.top < 0 || vis.right > sw || vis.bottom > sh) {
                    Logx.always("记录提示: 可视矩形超出屏幕($why) 真实=$bounds scale=$scale 可视=$vis 屏=${sw}x$sh（照记，交给 MIUI 弹回）")
                }
            }
        }
        // 忠实记录用户看到的真实值：(bounds, scale) 成对交给 Bounds.put（它保证不写裸值）
        Logx.always("落盘($why): pkg=$pkg screen=$screen bounds=$bounds scale=$scale")
        Bounds.put(ctx, pkg, screen, bounds, scale)
    }

    /**
     * 迷你态 / 贴边态不记：那两种状态下 bounds 是胶囊或半屏尺寸，记下来会让下次打开变成迷你尺寸。
     * 直接用 MIUI 自己的状态（MiuiFreeformModeTaskInfo），比猜尺寸可靠。
     */
    private fun isMiniOrPinned(decoration: Any): Boolean = try {
        val repo = field(decoration, "mMultiTaskingTaskRepository")
        val info = repo?.javaClass?.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE)
            ?.invoke(repo, taskId(decoration))
        if (info == null) false else listOf("isEnteringMini", "isInPinMode", "isForegroundPin").any { name ->
            runCatching { info.javaClass.getMethod(name).invoke(info) as? Boolean == true }.getOrDefault(false)
        }
    } catch (t: Throwable) {
        false
    }

    /** 状态栏高度：设置页诊断与比例按钮的目标计算用。 */
    private fun statusBarHeight(ctx: Context): Int = runCatching {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    // ---------------- 反射小工具 ----------------

    fun field(o: Any, name: String): Any? = try {
        var c: Class<*>? = o.javaClass
        var f: Field? = null
        while (c != null && f == null) {
            f = c.declaredFields.firstOrNull { it.name == name }
            c = c.superclass
        }
        if (f == null) null else {
            f.isAccessible = true
            f.get(o)
        }
    } catch (t: Throwable) {
        null
    }

    private fun taskId(o: Any): Int = try {
        field(o, "mRunningTaskInfo")?.let { it.javaClass.getMethod("getTaskId").invoke(it) as? Int } ?: -1
    } catch (t: Throwable) {
        -1
    }

    private fun windowingMode(o: Any): Int {
        val info = field(o, "mRunningTaskInfo") ?: return -1
        return taskWindowingMode(info)
    }

    private fun taskWindowingMode(info: Any): Int = try {
        (info.javaClass.getMethod("getWindowingMode").invoke(info) as? Int) ?: -1
    } catch (t: Throwable) {
        (field(info, "windowingMode") as? Int) ?: -1
    }

    /**
     * 取任务所属包名。侧栏（侧边栏）拖出的小窗里 topActivity/baseActivity 可能为空，
     * 因此把所有可能来源都试一遍（2026-09-19 用户实测：侧栏小窗走到这里 pkg=null）。
     */
    private fun taskPkg(info: Any): String? {
        // 1) ComponentName 字段 / getter
        listOf("topActivity" to "getTopActivity", "baseActivity" to "getBaseActivity", "realActivity" to "getRealActivity")
            .forEach { (f, g) -> component(info, f, g)?.packageName?.let { if (it.isNotEmpty()) return it } }
        // 2) ActivityInfo 字段
        listOf("topActivityInfo", "baseActivityInfo").forEach { f ->
            (field(info, f) as? android.content.pm.ActivityInfo)?.packageName?.let { if (it.isNotEmpty()) return it }
        }
        // 3) baseIntent 的 component / package
        (field(info, "baseIntent") as? android.content.Intent)?.let { it.component?.packageName?.let { p -> if (p.isNotEmpty()) return p } }
        // 4) 任务描述里的包名（最后兜底）
        runCatching {
            val td = info.javaClass.getMethod("getTaskDescription").invoke(info)
            val label = td?.javaClass?.getMethod("getLabel")?.invoke(td) as? String
            if (!label.isNullOrEmpty() && label.contains('/')) return label.substringBefore('/')
        }
        return null
    }

    private fun component(info: Any, fieldName: String, getter: String): android.content.ComponentName? {
        (field(info, fieldName) as? android.content.ComponentName)?.let { return it }
        return runCatching { info.javaClass.getMethod(getter).invoke(info) as? android.content.ComponentName }.getOrNull()
    }

    private fun taskBounds(info: Any): Rect? = try {
        val cfg = runCatching { info.javaClass.getMethod("getConfiguration").invoke(info) }
            .getOrNull() ?: field(info, "configuration")
        val wc = cfg?.let { runCatching { it.javaClass.getMethod("getWindowConfiguration").invoke(it) }.getOrNull() ?: field(it, "windowConfiguration") }
        wc?.let { runCatching { it.javaClass.getMethod("getBounds").invoke(it) as? Rect }.getOrNull() }
    } catch (t: Throwable) {
        null
    }
}
