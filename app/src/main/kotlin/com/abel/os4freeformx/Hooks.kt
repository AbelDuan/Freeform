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
        installImmersive(m, cl)
        installRatioMenu(m, cl)
        installBoundsRecorder(m, cl)
        installSplitRatio(m, cl)
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
                        runCatching { recordRatioFromSnap(chain.thisObject, result) }
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
                    runCatching { restoreSplitRatio(chain.thisObject) }
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
                                        { runCatching { restoreSplitRatioFromSingleton() } }, d
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
     * 隐藏分屏（2~6 个应用都算）里每个应用顶部的三点栏与底部手势/导航条。
     *
     * 两层一起处理：
     *  视图层：mTextView（"上/下分屏"文字）、mVeilIconView（圆角遮罩+应用图标）、mViewHost 根布局 → GONE
     *  surface 层：mIconLeash / mBackgroundLeash（拖动白底） / mScreenshot / mSoScScreenshot（快照替身）→ hide
     * 视图与触摸逻辑保留，分隔条/把手完全不动。
     */
    private fun hideSoScDecor(decor: Any, chain: XposedInterface.Chain) {
        runCatching {
            (field(decor, "mVeilIconView") as? android.view.View)?.let {
                it.visibility = android.view.View.GONE
            }
            (field(decor, "mTextView") as? android.view.View)?.let {
                it.visibility = android.view.View.GONE
            }
            val host = field(decor, "mViewHost")
            (host?.javaClass?.getMethod("getView")?.invoke(host) as? android.view.View)?.visibility =
                android.view.View.GONE

            val tx = (chain.getArg(2) as? android.view.SurfaceControl.Transaction)
                ?: (chain.getArg(3) as? android.view.SurfaceControl.Transaction)
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
                            if (pkg != null) Logx.once("ffr-$sig-$pkg", "小窗 bounds 计算: $sig pkg=$pkg -> $res")
                            // isMiniFreeformMode 只对 13 参数版可判断；更短的定制重载一律按普通小窗处理
                            val mini = miniIdx >= 0 && (chain.getArg(miniIdx) as? Boolean == true)
                            if (res != null && Cfg.rememberBounds && !mini && !pkg.isNullOrEmpty()) {
                                val ctx = (if (ctxIdx >= 0) chain.getArg(ctxIdx) as? Context else null) ?: AppCtx.get()
                                if (ctx != null) {
                                    val dm = ctx.resources.displayMetrics
                                    val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
                                    val screen = Bounds.screenKey(ctx)
                                    // 优先用「当前屏幕」的记忆；没有则该应用其它屏幕的记忆按比例缩到当前屏，
                                    // 保住形状（#1 内外屏切换 / #2 旋转：第一次遇到新几何也不退化成系统默认）
                                    var memo = Bounds.get(ctx, pkg, screen)
                                    if (memo == null) {
                                        val any = Bounds.getAny(ctx, pkg)
                                        if (any != null) {
                                            memo = Bounds.clampKeepRatio(any, area)
                                            Logx.once("ffr-fallback-$pkg",
                                                "当前屏无记忆，$pkg 用其它屏记忆 $any 等比缩到 $memo")
                                        }
                                    }
                                    if (memo != null) {
                                        val systemDefault = Rect(res)
                                        // 等比夹进可视区：旋转/换屏只整体缩放、不裁边，形状不变
                                        val fixed = Bounds.clampKeepRatio(memo, area)
                                        if (rectIdx >= 0) (chain.getArg(rectIdx) as? Rect)?.set(fixed)
                                        res.set(fixed)
                                        Logx.always("恢复 $pkg@$screen -> $fixed（记忆 $memo，系统默认 $systemDefault）")
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
    }

    // ---------------- 2.4 沉浸式底栏 ----------------

    private fun installImmersive(m: MainHook, cl: ClassLoader) {
        // 顶部三点栏 / 底部手势条：不绘制（视图仍 VISIBLE、仍接收触摸）
        m.hookMethod(cl, Constants.CLS_DECOR_DOT_VIEW, "onDraw",
            arrayOf<Class<*>>(android.graphics.Canvas::class.java),
            XposedInterface.Hooker { chain ->
                if (hiddenViews.contains(chain.thisObject)) null else chain.proceed()
            })
        m.hookMethod(cl, Constants.CLS_DECOR_BOTTOM_VIEW, "onDraw",
            arrayOf<Class<*>>(android.graphics.Canvas::class.java),
            XposedInterface.Hooker { chain ->
                if (hiddenViews.contains(chain.thisObject)) null else chain.proceed()
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
                    // 多分屏栏 UI + SoSc 镜像栏（都是"栏"，不是分隔条宿主）
                    if (name.contains("MultipleSplitUIController") || name.contains("SoScSplitDecorManager")) {
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
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "updateRootView", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                markBar(chain.thisObject) { Cfg.immersive }
                r
            })
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

        // 上/下 insets：不再为栏位预留空间（内容区铺满整个窗口）
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "updateInsets", null,
            XposedInterface.Hooker { chain ->
                Cfg.reloadThrottled()
                // 同样不再限定自由小窗：分屏每个应用也用这套装饰，否则栏不画了但**高度仍占位**
                if (Cfg.immersive) {
                    Logx.once("skipTopInsets", "沉浸: 不再为顶部三点栏预留 insets（task=${taskId(chain.thisObject)}）")
                    null
                } else chain.proceed()
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
        // 菜单高度只算了一排按钮：在 addWindow 这一层给菜单表面加一行高度，位置往上挪半行保持居中。
        // 注意**不能**改 `getWCButtonHeight()`：`MiuiCaptionContainerView#init` 拿同一个值当
        // 按钮自己的高度，一改按钮就被拉大、把我们那行顶到菜单外（真机踩过两次）。
        m.hookMethod(cl, Constants.CLS_DECOR_DOT, "addWindow", null,
            XposedInterface.Hooker { chain ->
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
                                // 等比夹进当前可视区：内外屏切换/旋转后，mini 恢复也保形状、不越界
                                val dm = ctx.resources.displayMetrics
                                val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
                                rect.set(Bounds.clampKeepRatio(memo, area))
                                // 只替换恢复矩形，**不改 scale**：之前覆盖 scale 是为了修尺寸记忆，
                                // 但后来证明那是别的原因，而覆盖 scale 会让 mini→正常的转换异常
                                // （真机：贴边迷你态点击不再回到悬浮窗，而是点到内容）。
                                Logx.always("迷你恢复: 套用记忆 $memo（scale 保持系统值）")
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
                runCatching { restoreScaleIfNeeded(chain.thisObject) }
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
        val key = menuKey(owner)
        RATIO_BUTTONS.forEach { (label, ratio) ->
            row.addView(ratioButton(ctx, label) { pickRatio(owner, ratio, label, row) })
        }
        // 用户要求：暂时取消横竖屏切换，只做比例调整，方向交给系统。
        // 因此这一行只放比例按钮，不再放手机形状的方向按钮。
        container.addView(row)
        container.requestLayout()
        Logx.always("比例行已注入三点菜单（${RATIO_BUTTONS.size} 个按钮，无方向按钮）")
    }


    /** 与 MIUI 比例按钮文字同色（`caption_extend_text_color`），取不到就退回灰。 */
    private fun captionTextColor(ctx: Context): Int = runCatching {
        val res = ctx.resources
        val id = res.getIdentifier("caption_extend_text_color", "color", "com.android.systemui")
        if (id == 0) android.graphics.Color.GRAY else res.getColorStateList(id, ctx.theme).defaultColor
    }.getOrDefault(android.graphics.Color.GRAY)

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

    private fun ratioButton(ctx: Context, label: String, onClick: () -> Unit): android.widget.TextView {
        val density = ctx.resources.displayMetrics.density
        val h = (26 * density).toInt()
        val tv = android.widget.TextView(ctx)
        tv.text = label
        tv.textSize = 12f
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextColor(android.graphics.Color.WHITE)
        // 用 weight=1 的弹性宽度：四个按钮平分菜单宽度，无论内屏/外屏（菜单背景窄）都不会超出背景。
        // 之前写死 42dp 固定宽，外屏菜单背景比内屏窄，四个按钮加起来就超出背景了（#3）。
        tv.layoutParams = android.widget.LinearLayout.LayoutParams(0, h, 1f).apply {
            marginStart = (2 * density).toInt()
            marginEnd = (2 * density).toInt()
        }
        // 复用 MIUI 自己的按钮底与文字色：深浅色模式自动一致（自己画深色胶囊被反馈过“不统一”）
        val res = ctx.resources
        val bgId = res.getIdentifier("caption_extend_selector", "drawable", "com.android.systemui")
        val textId = res.getIdentifier("caption_extend_text_color", "color", "com.android.systemui")
        if (bgId != 0) {
            tv.setBackgroundResource(bgId)
        } else {
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(40, 128, 128, 128))
                cornerRadius = h / 2f
            }
        }
        if (textId != 0) {
            tv.setTextColor(res.getColorStateList(textId, ctx.theme))
        } else {
            tv.setTextColor(android.graphics.Color.GRAY)
        }
        tv.setOnClickListener { onClick() }
        return tv
    }

    /** 菜单方向状态的 key：与 `applyRatio` 里的记忆 key 保持一致（pkg|屏幕）。 */
    private fun menuKey(owner: Any): String {
        val pkg = field(owner, "mRunningTaskInfo")?.let { taskPkg(it) } ?: return "?"
        val ctx = AppCtx.get() ?: return pkg
        return Bounds.key(pkg, Bounds.screenKey(ctx))
    }

    /** 点某个比例：按当前方向状态定下尺寸，然后关掉菜单（一次进菜单就完成）。 */
    private fun pickRatio(owner: Any, picked: Float, label: String, row: android.view.View) {
        applyRatio(owner, picked, false, "比例$label")
        // 关掉菜单：一次进菜单就完成「方向 + 比例」
        runCatching {
            val controller = field(owner, "mMiuiDecorationController") ?: return@runCatching
            controller.javaClass.getMethod("closeHandleMenu").invoke(controller)
        }.onFailure { Logx.e("关菜单失败", it) }
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
            val left = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
            val top = (real?.top ?: top0).coerceIn(top0, (maxH - h).coerceAtLeast(top0))
            val target = Rect(left, top, left + w, top + h)
            val dispId2 = (ti?.let { call(it, "getTaskInfo") }?.let { field(it, "displayId") } as? Int) ?: -1
            val screenKey2 = Bounds.screenKeyFor(ctx, dispId2)
            Bounds.put(ctx, pkg, screenKey2, target)
            Logx.always(
                "比例调整($why): ${real ?: "无小窗"} -> 记忆 $target（比例 ${"%.3f".format(ratio)}，" +
                    "上下对齐 + 左右居中），随后关闭并重开小窗"
            )
            // 记下「这个尺寸是我们刚指定的」，短时间内不要让记录链路用旧 bounds 覆盖它
            val memoKey = Bounds.key(pkg, Bounds.screenKey(ctx))
            pendingTarget[memoKey] = PendingTarget(target, android.os.SystemClock.elapsedRealtime())
            // 关闭→重开期间屏蔽记录；重开稳定后再按真实结果写一次（下面 3.5s 后解除并记录）
            suppressRecord[memoKey] = android.os.SystemClock.elapsedRealtime() + 10_000
            // 记忆读侧缓存已降到 300ms，所以不用再原地等 1.8s（用户反馈等太久）
            Handler(Looper.getMainLooper()).postDelayed({
                if (!inFreeform) {
                    openAsFreeform(decoration, id)
                } else {
                    // 用户指定的流程：**先写好记忆（上面已写）→ 关掉当前小窗 → 重新打开**。
                    // 关掉之后再开，系统才会走"创建小窗窗口"的流程（`getCustomFreeformRect`），
                    // 从而套用新尺寸；直接对已存在的小窗调启动接口只会把它带到前台（真机验证）。
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
                                } else if (attempt < 2) {
                                    Logx.e("比例调整: 官方接口未重开小窗，重试（第 $attempt 次）")
                                    relaunchOnce(attempt + 1)
                                } else {
                                    Logx.e("比例调整: 官方接口两次都未重开小窗（不回退转场，避免露桌面）")
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
            }, 1_800)
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

    /**
     * 每个 key「上一次被替换掉的旧值」。
     * 真机问题：拖动后立即写入是对的（`立即写入 … 122,345-1352,1985`），
     * 但随后某个事件（窗口关闭/relayout）读到的却是**回退后的旧状态**，
     * 经 700ms 延时落盘把旧值写了回去 → 记忆永远慢一拍（`记住 … = 442,724,1672,2364`）。
     * 所以：要写的值若正好等于上一次被替换掉的旧值，判为回退态，跳过。
     */
    private val prevWritten = ConcurrentHashMap<String, Rect>()

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
        val exec = runCatching {
            val tr = field(decoration, "mTransitions") ?: return@runCatching null
            tr.javaClass.getMethod("getMainExecutor").invoke(tr)
        }.getOrNull() ?: return false
        runCatching { exec.javaClass.getMethod("execute", Runnable::class.java).invoke(exec, runner) }
            .onFailure { Logx.e("投递关闭失败", it) }
        return true
    }

    private fun relaunchViaMiuiApi(decoration: Any, id: Int, target: Rect): Boolean = runCatching {
        val ctx = AppCtx.get() ?: return false
        val info = field(decoration, "mRunningTaskInfo") as? android.app.ActivityManager.RunningTaskInfo
            ?: return false
        val pkg = info.topActivity?.packageName ?: return false
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

    /** 已经纠正过 scale 的 task（每个窗口只纠正一次，避免和 MIUI 来回打）。 */
    private val scaleApplied = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /**
     * 打开小窗时把记忆里的 **freeformScale** 也套上。
     *
     * 真机取证：用户调大小后，任务真实 bounds 的尺寸恒定（836x1672），变化的是 freeformScale；
     * 只恢复 bounds 是"尺寸记不住"的根因。所以记忆里 bounds 与 scale 一起存、一起恢复。
     * 显示尺寸 = bounds × freeformScale，两者都还原 → 用户看到的尺寸/位置才一致。
     */
    private fun restoreScaleIfNeeded(decoration: Any) {
        runCatching {
            val ctx = AppCtx.get() ?: return
            val id = taskId(decoration)
            if (!scaleApplied.add(id)) return
            val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
            val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
            val ti = repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, id)
                ?: return
            val info = call(ti, "getTaskInfo") ?: return
            if (taskWindowingMode(info) != MODE_FREEFORM) return
            val pkg = taskPkg(info) ?: return
            val want = Bounds.getScale(ctx, pkg, Bounds.screenKey(ctx))
            if (want <= 0f) return
            val now = call(ti, "getFreeformScale") as? Float ?: return
            if (kotlin.math.abs(now - want) < 0.01f) {
                Logx.e("尺寸记忆: scale 已一致 ($now)，无需纠正 task=$id")
                return
            }
            val org = field(decoration, "mTaskOrganizer") ?: return
            val token = field(info, "token") ?: call(info, "getToken") ?: return
            val wctCls = cls("android.window.WindowContainerTransaction")
            val tokenCls = cls("android.window.WindowContainerToken")
            val wct = wctCls.getDeclaredConstructor().newInstance()
            val changeCls = cls("miui.app.MiuiFreeFormManager\$MiuiFreeFormInfoChange")
            val change = changeCls.getDeclaredConstructor().newInstance()
            changeCls.getMethod("setMiuiFreeformScale", java.lang.Float.TYPE).invoke(change, want)
            wctCls.getMethod("setMiuiFreeformInfoChange", tokenCls, changeCls).invoke(wct, token, change)
            org.javaClass.getMethod("applyTransaction", wctCls).invoke(org, wct)
            Logx.e("尺寸记忆: 已恢复 scale $now -> $want（task=$id pkg=$pkg）")
        }.onFailure { Logx.e("恢复 scale 失败", it) }
    }

    private fun freeformTask(repo: Any, id: Int): Any? = runCatching {
        repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE).invoke(repo, id)
    }.getOrNull()

    private fun call(o: Any?, name: String): Any? =
        runCatching { o?.javaClass?.getMethod(name)?.invoke(o) }.getOrNull()

    // ---------------- 2.2 / 2.3 记录用户调整后的 bounds ----------------

    /** 被标记为「不绘制」的栏视图。用实例集合而不是全局开关，避免影响分屏/桌面的同名栏。 */
    private val hiddenViews: MutableSet<Any> =
        java.util.Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private fun markBar(decoration: Any, wantHide: () -> Boolean) {
        try {
            Cfg.reloadThrottled()
            val mode = windowingMode(decoration)

            // 1) 记忆：自由小窗装饰每完成一次布局就（防抖）落盘。
            //    必须放在「视图是否就绪」判断之前——首帧时 ViewHost 往往还没建好，
            //    早期 return 会让记录整条路径永远不执行（2026-09-19 踩过）。
            if (Cfg.rememberBounds) {
                val info = field(decoration, "mRunningTaskInfo")
                val b = info?.let { taskBounds(it) }
                if (info != null && b != null && b.width() > 0 && b.height() > 0) {
                    val p = taskPkg(info)
                    Logx.once(
                        "ff-layout-" + taskId(decoration) + "-" + mode,
                        "小窗布局: task=${taskId(decoration)} mode=$mode pkg=$p bounds=$b"
                    )
                    // 布局信号：只有在「刚发生过手势」时才落盘（否则打开窗口时的默认位置会把记忆冲掉）
                    val endedAt = gestureEndedAt[decoration]
                    val now2 = android.os.SystemClock.elapsedRealtime()
                    if (endedAt != null && now2 - endedAt in 0..3000) {
                        gestureEndedAt.remove(decoration)
                        if (mode == MODE_FREEFORM && !isMiniOrPinned(decoration)) {
                            Logx.once("ff-gesture-" + taskId(decoration), "手势后布局落盘: task=${taskId(decoration)} bounds=$b")
                            persist(p, b, "gestureLayout")
                        }
                    }
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

    private val lastVisible = WeakHashMap<Any, Boolean>()
    private class LastFreeform(var pkg: String?, var bounds: Rect?, var at: Long)

    private val lastFreeform = WeakHashMap<Any, LastFreeform>()

    /**
     * 手势结束时间戳。抬手回调里 MIUI 的 taskInfo 还是**旧 bounds**（新 bounds 由
     * WindowContainerTransaction 异步应用，实测 `up=旧值 变化=false`），
     * 所以要等随后的装饰布局再落盘：手势信号 + 布局信号一起用。
     */
    private val gestureEndedAt = WeakHashMap<Any, Long>()

    /** 手势路径刚写入过的时间：关闭兜底在 10 秒内不再写，避免用旧值覆盖刚记下的新值。 */
    private val gestureWroteAt = WeakHashMap<Any, Long>()

    /** 装饰视图 → 装饰对象。触摸 hook 拿到的是 View，需要反查任务与 TaskOrganizer。 */
    private val decorByView = WeakHashMap<View, Any>()

    /** 手势按下时的 bounds：抬起时只有真的变了才算用户调整（点一下不算）。 */
    private val gestureDownBounds = WeakHashMap<Any, Rect>()

    private fun currentBounds(decoration: Any): Rect? =
        field(decoration, "mRunningTaskInfo")?.let { taskBounds(it) }

    private fun installBoundsRecorder(m: MainHook, cl: ClassLoader) {
        // 证据探针：MIUI 的手势分发层（若 handleDown/UpEvent 不派发，至少这里能看到）
        listOf("dispatchDownToDecoration", "dispatchMoveToDecoration", "dispatchUpToDecoration").forEach { name ->
            m.hookMethod(cl, Constants.CLS_DECOR_VIEW_MODEL, name,
                arrayOf<Class<*>>(Integer.TYPE),
                XposedInterface.Hooker { chain ->
                    Logx.once("vm-$name", "手势分发: $name task=${chain.getArg(0)}")
                    chain.proceed()
                })
        }
        // 拖动（移动位置）手势：按下记基线，抬起时若 bounds 变了就落盘
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "handleDownEvent", null,
            XposedInterface.Hooker { chain ->
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
                // 缩放的新 bounds 是回调之后异步应用的（真机实测：回调时刻读到的仍是旧尺寸 1170x1870），
                // 所以 700ms 后再补采一次，拿到的才是用户拖出来的尺寸。
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { record(ctrl, "resizeEndDelayed") }
                }, 700)
                r
            })
        // 每次 relayout：窗口从可见变为不可见（关闭/收进迷你）时记录，覆盖「拖动移动位置」的情况
        m.hookMethod(cl, Constants.CLS_DECOR_CONTROLLER, "relayout", null,
            XposedInterface.Hooker { chain ->
                val r = chain.proceed()
                try {
                    val ctrl = chain.thisObject
                    val info = field(ctrl, "mRunningTaskInfo")
                    val visible = info?.let { field(it, "isVisible") as? Boolean } == true
                    if (info != null && taskWindowingMode(info) == MODE_FREEFORM) {
                        val p = taskPkg(info)
                        val b = taskBounds(info)
                        lastFreeform[ctrl] = LastFreeform(p, b, android.os.SystemClock.elapsedRealtime())
                    }
                    // 手势结束后 MIUI 通过 onTaskInfoChanged 带来**新 bounds**（移动窗口不重排装饰，
                    // 所以 updateRootView 不会来）。这里用「手势信号 + 新 taskInfo」落盘。
                    val endedAt = gestureEndedAt[ctrl]
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (endedAt != null && nowMs - endedAt in 0..3000 && info != null &&
                        taskWindowingMode(info) == MODE_FREEFORM && !isMiniOrPinned(ctrl)
                    ) {
                        val nb = taskBounds(info)
                        if (nb != null) {
                            gestureEndedAt.remove(ctrl)
                            Logx.once("ff-relayout-" + taskId(ctrl), "手势后 taskInfo 落盘: task=${taskId(ctrl)} bounds=$nb")
                            gestureWroteAt[ctrl] = android.os.SystemClock.elapsedRealtime()
                            persist(taskPkg(info), nb, "gestureTaskInfo")
                        }
                    }
                    val was = lastVisible.put(ctrl, visible)
                    if (was == true && !visible) {
                        // 关闭小窗时兜底记录：不依赖手势回调是否派发，直接用此刻的 bounds。
                        // persist() 里已有三道闸（等于记忆 clamp 则跳过 / 满屏跳过 / 迷你态跳过），
                        // 所以“打开后原样关闭”不会污染记忆。
                        val wroteAt = gestureWroteAt[ctrl]
                        val justWrote = wroteAt != null &&
                            android.os.SystemClock.elapsedRealtime() - wroteAt < 10_000
                        if (justWrote) {
                            Logx.once("ff-hide-skip-" + taskId(ctrl), "关闭兜底跳过：手势已写入，避免用旧值覆盖（task=${taskId(ctrl)}）")
                            return@Hooker r
                        }
                        val nb = info?.let { taskBounds(it) } ?: lastFreeform[ctrl]?.bounds
                        val np = info?.let { taskPkg(it) } ?: lastFreeform[ctrl]?.pkg
                        if (nb != null) {
                            Logx.once("ff-hide-" + taskId(ctrl), "关闭时兜底记录: task=${taskId(ctrl)} pkg=$np bounds=$nb")
                            persist(np, nb, "hidden")
                        }
                    }
                } catch (t: Throwable) {
                    Logx.e("relayout 记录失败", t)
                }
                r
            })
    }

    private fun record(controller: Any, why: String) {
        try {
            Cfg.reloadThrottled()
            if (!Cfg.rememberBounds) return
            val info = field(controller, "mRunningTaskInfo") ?: return
            val mode = taskWindowingMode(info)
            if (mode != MODE_FREEFORM) {
                Logx.once("rec-mode-$mode", "记录跳过：windowingMode=$mode（非自由小窗）")
                return
            }
            // 窗口已经不可见（关闭/切走）时读到的 bounds 往往是回退后的旧状态 —— 不记，
            // 否则延时/收尾的 gestureTaskInfo 会把旧值写回记忆（真机：记忆"永远慢一拍"）。
            val visible = field(info, "isVisible") as? Boolean == true
            if (!visible) {
                Logx.once("rec-hidden", "记录跳过：任务不可见，bounds 可能是回退态（$why）")
                return
            }
            val pkg = taskPkg(info)
            if (pkg == null) {
                Logx.once("rec-nopkg", "记录跳过：取不到包名（$info）")
                return
            }
            // 用户调的大小体现在 freeformScale 上 → 必须一起记（真机：bounds 尺寸恒定 836x1672）
            var scale = 0f
            runCatching {
                val ctl2 = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                val repo2 = ctl2.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl2)
                val ti2 = repo2.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE)
                    .invoke(repo2, taskId(controller))
                scale = (call(ti2, "getFreeformScale") as? Float) ?: 0f
            }
            // 对照日志：真实 bounds（我们记的） vs 可视 rect（用户看到/操作的） vs scale
            runCatching {
                // 控制器上没有这个字段（在装饰基类上），必须走单例，否则永远打不出来
                val ctl = cls(Constants.CLS_MULTITASKING_CTL).getMethod("getInstance").invoke(null)
                val repo = ctl.javaClass.getMethod("getMultiTaskingTaskRepository").invoke(ctl)
                val ti = repo.javaClass.getMethod("getMiuiFreeformTaskInfo", Integer.TYPE)
                    .invoke(repo, taskId(controller))
                if (ti != null) {
                    Logx.e(
                        "记录核对($why): 真实=${taskBounds(info)} 可视=${call(ti, "getScaledBounds")} " +
                            "scale=${call(ti, "getFreeformScale")} 将存scale=$scale"
                    )
                }
            }
            // 折叠屏内外屏分开记录：用**任务所在的 display** 算 key（外屏是另一个 displayId）
            val dispId = (field(info, "displayId") as? Int) ?: -1
            val keyScreen = Bounds.screenKeyFor(AppCtx.get(), dispId)
            if (boundsLog.add("$pkg|$keyScreen")) {
                Logx.e("记忆 key: pkg=$pkg displayId=$dispId -> $keyScreen")
            }
            val bounds = taskBounds(info)
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                Logx.once("rec-nobounds", "记录跳过：bounds=$bounds")
                return
            }
            if (why == "layout") {
                // 只为日志对照；真正写入走 persist
            }
            persist(pkg, bounds, why, scale, keyScreen)
        } catch (t: Throwable) {
            Logx.e("record 失败", t)
        }
    }

    private fun persist(pkg: String?, bounds: Rect, why: String, scale: Float = 0f, screenIn: String? = null) {
        if (pkg == null) {
            Logx.once("rec-nopkg-persist", "记录跳过：包名为空（$why）")
            return
        }
        val ctx = AppCtx.get() ?: return
        val screen = screenIn ?: Bounds.screenKey(ctx)
        if (!isGestureRecord(why)) {
            Logx.once("rec-src-$why", "记录跳过：非手势来源（$why）")
            return
        }
        // 关闭→重开期间一律不记录（那时 MIUI 会把任务摆成全屏，写进去就把记忆污染了）
        val supKey = Bounds.key(pkg, screen)
        suppressRecord[supKey]?.let { until ->
            if (android.os.SystemClock.elapsedRealtime() < until) {
                Logx.once("sup-$supKey", "记录跳过：正在关闭→重开小窗（$why）")
                return
            }
            suppressRecord.remove(supKey)
        }
        // 刚用比例按钮指定过尺寸：别让旧 bounds 把它覆盖掉（真机踩过：写进去立刻被记录链路盖回来）
        pendingTarget[Bounds.key(pkg, screen)]?.let { p ->
            when {
                bounds == p.bounds -> pendingTarget.remove(Bounds.key(pkg, screen))   // 已经套用上了
                android.os.SystemClock.elapsedRealtime() - p.at < 60_000 -> {
                    Logx.once("rec-hold-$pkg", "记录跳过：刚指定过比例（$why），等重开小窗套用 ${p.bounds}")
                    return
                }
                else -> pendingTarget.remove(Bounds.key(pkg, screen))
            }
        }
        // 恢复发生在 system_server、记录发生在 SystemUI，跨进程没法用内存标记。
        // 于是直接判定：如果当前 bounds 恰好等于「已有记忆经过同样的 clamp」的结果，
        // 那它就是恢复产物，不是用户调整，跳过（否则记忆会被夹取结果覆盖，表现为永远慢一拍）。
        val memo = Bounds.get(ctx, pkg, screen)
        if (memo != null) {
            val dm = ctx.resources.displayMetrics
            val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
            val clampedMemo = Bounds.clamp(memo, area)
            if (clampedMemo != memo) {
                // 历史记忆里存的越界坐标：就地修正（否则恢复时会贴状态栏）
                Logx.once("rec-heal-$pkg", "修正越界记忆: $memo -> $clampedMemo")
                Recorder.schedule(ctx, pkg, screen, clampedMemo, "heal")
            }
            if (clampedMemo == bounds) {
                Logx.once("rec-skip-clamped-$pkg", "记录跳过：等于记忆的 clamp 结果，判为恢复产物（$bounds）")
                return
            }
        }
        // 记录时就夹到可视区（上边界=状态栏高度），这样记忆里永远不会存越界坐标：
        // 恢复端即使还是旧的 y=0 夹取，也不会把三点栏顶到状态栏底下。
        // 注：这里用 Bounds.clamp（位置/超限修正），与下方 heal 比较保持一致；
        // 等比缩放（保形状）只在「恢复端」installLaunchBounds 里做，那里才会遇到
        // 不同屏幕/旋转几何，是 #1 内外屏切换 / #2 旋转「比例被改」的真正修复点。
        val dm = ctx.resources.displayMetrics
        val area = Rect(0, statusBarHeight(ctx), dm.widthPixels, dm.heightPixels)
        val safe = Bounds.clamp(bounds, area)
        // MIUI 把小窗拖到边缘/角落会「吸附、最大化」，那不是用户想要的尺寸。
        // 真机症状：拖到左下角后被记成 0,140,1672,2364（满屏），下次打开就是左上角巨大窗口。
        if (safe.width() >= area.width() - 2 && safe.height() >= area.height() - 2) {
            Logx.once("rec-skip-max-$pkg", "记录跳过：满屏尺寸（MIUI 吸附/最大化），保留原记忆（$safe）")
            return
        }
        if (why == "layout") {
            Recorder.schedule(ctx, pkg, screen, safe, why, scale)
        } else {
            // 手势/关闭兜底：值已经是稳定结果，立即落盘。防抖会让紧随其后的关闭事件把写入挤掉
            // （2026-09-19 真机：手势拿到 516,804,1686,2674，随后的关闭事件触发满屏判定，写入丢失）。
            Logx.once("pf-imm-" + pkg, "立即写入: pkg=$pkg bounds=$safe scale=$scale why=$why")
            Bounds.put(ctx, pkg, screen, safe, scale)
        }
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

    /**
     * 只认「手势结束」这一类记录来源。
     * 真机反复踩：关闭/relayout/重开后的记录读到的是回退或全屏态，
     * 会把用户刚调好的尺寸/位置盖掉（`记录核对` 明明是真值，写进去却是满屏）。
     */
    private fun isGestureRecord(why: String) = why.startsWith("gestureTaskInfo") ||
        why.startsWith("resizeEnd")

    /** 状态栏高度：clamp 的上边界，保证小窗顶部三点栏不被状态栏压住。 */
    private fun statusBarHeight(ctx: Context): Int = runCatching {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    /** 延迟落盘：拖动过程中 bounds 会连续变化，等稳定后再写。 */
    private object Recorder {
        private val main = Handler(Looper.getMainLooper())
        private val pending = ConcurrentHashMap<String, Runnable>()

        fun schedule(ctx: Context, pkg: String, screen: String, bounds: Rect, why: String, scale: Float = 0f) {
            val key = Bounds.key(pkg, screen)
            val r = Rect(bounds)
            val task = Runnable {
                pending.remove(key)
                Bounds.put(ctx, pkg, screen, r, scale)
            }
            pending.put(key, task)?.let { main.removeCallbacks(it) }
            main.postDelayed(task, 700)
        }
    }

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
