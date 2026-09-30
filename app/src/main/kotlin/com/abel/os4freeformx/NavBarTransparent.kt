package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * 小白条悬浮 · SystemUI 侧实现：让手势导航栏底色恒为全透明，pill 直接浮在应用内容上。
 *
 * ── 为什么必须是 SystemUI 侧 ──────────────────────────────────────────
 * 模块作用域固定（staticScope=true，scope.list 只有 system + com.android.systemui），
 * 不能把第三方应用勾进来；用户也明确要求 **不 hook 第三方 app**。
 * 应用侧改写 Window.setNavigationBarColor 的路子因此作废（v0.2.9 废弃）。
 *
 * ── 颜色链（2026-09-30 反编译 MiuiSystemUI.apk 取证，Android 17 / HyperOS 4）──
 *   NavigationBar.onSystemBarAttributesChanged(II[AppearanceRegion;ZIILjava/lang/String;[LetterboxDetails;)V
 *     → NavBarHelper.transitionMode(appearance, transientShown) → mTransitionMode
 *   NavigationBarTransitions.onTransition(int mMode, int, boolean v)
 *     → BarTransitions.applyModeBackground(int mode, boolean animate) → BarBackgroundDrawable.mMode = mode
 *   BarTransitions$BarBackgroundDrawable.draw(Canvas) 按 mMode 选色：
 *       MODE_TRANSPARENT(0)          → 0x00000000  ← 全透明（目标）
 *       MODE_SEMI_TRANSPARENT(1)     → mSemiTransparent
 *       MODE_TRANSLUCENT(2)          → mSemiTransparent
 *       MODE_LIGHTS_OUT(3)           → 0（无底）
 *       MODE_OPAQUE_DARK(4)          → mOpaqueDark = 0xff000000  ← 小鹏首页「黑板」
 *       MODE_WARNING(5)              → mWarning
 *       MODE_LIGHTS_OUT_TRANSPARENT(6)→ 0
 *       MODE_OPAQUE_LIGHT(7)         → mOpaqueLight = 0xffffffff ← 小鹏 splash「白板」
 *
 * 用户看到「splash 白 → 首页黑」，就是 ImmersionBar 让应用分别要了 OPAQUE_LIGHT / OPAQUE_DARK。
 *
 * ── 挂点选择 ─────────────────────────────────────────────────────
 * 挂 `NavigationBarTransitions.onTransition(int,int,boolean)`，把第 0 个实参改写为 0（MODE_TRANSPARENT）。
 *   ① 作用域内（com.android.systemui），满足固定作用域约束；
 *   ② 是 MIUI 自己的分支（onTransition 是 NavigationBarTransitions 覆写 BarTransitions 的版本），
 *      改这里等于把「底栏底色」这一个意图掐掉，不碰 insets / 布局 / 触摸 / 图标深浅；
 *   ③ 只改 mode 参数、不改返回值，不涉及 void setter 的 proceed 语义坑；
 *   ④ 可运行时开关（gestureHandleFloat），改开关不必重启，只是下一次 mode 变更才生效。
 *
 * 注意：**不改图标颜色**。深色图标在浅背景上仍由 LightBarController 按 appearance 决定，
 * 因此开启后 pill / 图标在浅色应用上可能对比度不足——这是「真·悬浮」的固有代价，
 * 用户已选择 (a) 悬浮语义。
 *
 * ── 分应用生效（悬停在哪些应用上）─────────────────────────────────
 * 用户可指定「仅白名单 / 黑名单外」生效。判定需要当前前台包名：
 *   这里用 ActivityTaskManager.getTasks 取前台任务（沿用 Gestures.topTaskViaAtm 的可用路径），
 *   但 **做 500ms 节流缓存**，且只在 mode != ALL 时才去查，避免每次 onTransition 都打 IPC。
 */
object NavBarTransparent {

    /** MODE_TRANSPARENT：BarTransitions 里常量 0，draw() 落入 `move v0, v2`(0) 分支。 */
    private const val MODE_TRANSPARENT = 0

    private const val TRANSITIONS = "com.android.systemui.navigationbar.views.NavigationBarTransitions"
    private const val NAV_BAR_VIEW = "com.android.systemui.navigationbar.views.NavigationBarView"
    private const val BAR_TRANSITIONS = "com.android.systemui.statusbar.phone.BarTransitions"

    /** 前台包名缓存：只在白/黑名单模式下查询，500ms 内复用。 */
    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAt = 0L
    private const val PKG_TTL_MS = 500L

    /**
     * 已挂到的 NavigationBarView 实例集合（弱引用）。
     *
     * 折叠/展开时 MIUI 会**重建** NavigationBar（外屏/内屏各一套 NavigationBarView +
     * NavigationBarTransitions），新实例的 BarBackgroundDrawable.mMode 是默认值，
     * 而 onTransition 要等应用下一次请求 mode 才会被调到 —— 这段时间底栏就是一块不透明板。
     * 所以除了拦 onTransition，还要：
     *   ① 在新 NavigationBarView 构造完成后**主动补一次** forceTransparent()；
     *   ② 给所有已知实例做低频看门狗，兜住"既没重建也没回调"的漏网情况。
     */
    private val views = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Any, Boolean>()
    )

    @Volatile private var watchdogStarted = false

    fun install(m: MainHook, cl: ClassLoader) {
        val n = hookOnTransition(m, cl)
        val r = hookReorient(m, cl)
        Logx.always(
            "小白条悬浮(SystemUI): 已挂 onTransition=$n reorient=$r 个导航栏底色入口" +
                    "（开关=${Cfg.gestureHandleFloat}，模式=${Cfg.floatMode}，名单=${Cfg.floatPkgsRaw}）"
        )
        startWatchdog()
    }

    /**
     * 兜底：Fold 切换后若没有触发 onTransition，底栏会残留不透明底色。
     * 这里每秒轻量检查一次「当前是否该透明」，该透明就直接把 mBarBackground.mMode 写 0。
     *
     * 为什么低频（1000ms）：只是兜底路径，正常路径由 onTransition + reorient 覆盖；
     * 1000ms 的反射开销可忽略，也不会和应用自己的 inset/动画打架。
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val t = Thread {
            while (true) {
                runCatching { Thread.sleep(1000) }
                runCatching {
                    if (!Cfg.gestureHandleFloat) return@runCatching
                    if (Cfg.floatMode != Constants.FLOAT_MODE_ALL && !Cfg.floatAppliesTo(currentPkg())) {
                        return@runCatching
                    }
                    forceTransparentOnAll()
                }
            }
        }
        t.isDaemon = true
        t.name = "os4ffx-float-watchdog"
        t.start()
    }

    /**
     * 直接改 BarBackgroundDrawable.mMode = 0。
     *
     * 为什么不走 onTransition(mode=0)：那条路会带一个 250ms 的 alpha 交叉淡入动画，
     * 每秒调一次会让底栏处于"永远在动画中"的状态、且可能闪。这里直接写字段，
     * 下一帧 draw() 就是全透明，无动画无抖动。
     */
    private fun forceTransparentOnAll() {
        for (v in views.toTypedArray()) {
            runCatching { forceTransparent(v) }
        }
    }

    private fun forceTransparent(navBarView: Any) {
        val trans = findBarTransitions(navBarView) ?: return
        val drawable = findBarBackground(trans) ?: return
        setModeTransparent(drawable)
    }

    /** 沿继承链找 NavigationBarView.getBarTransitions() 并调用。 */
    private fun findBarTransitions(navBarView: Any): Any? {
        var k: Class<*>? = navBarView.javaClass
        while (k != null) {
            val cur = k
            val mm = cur.declaredMethods.firstOrNull {
                it.name == "getBarTransitions" && it.parameterCount == 0
            }
            if (mm != null) {
                mm.isAccessible = true
                return runCatching { mm.invoke(navBarView) }.getOrNull()
            }
            k = cur.superclass
        }
        return null
    }

    /** NavigationBarTransitions 里名为 mBarBackground 的字段（父类 BarTransitions 声明）。 */
    private fun findBarBackground(trans: Any): Any? {
        var k: Class<*>? = trans.javaClass
        while (k != null) {
            val cur = k
            val f = runCatching { cur.getDeclaredField("mBarBackground") }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return runCatching { f.get(trans) }.getOrNull()
            }
            k = cur.superclass
        }
        return null
    }

    /** BarBackgroundDrawable.mMode = 0（无动画，下一帧即透明）。 */
    private fun setModeTransparent(drawable: Any) {
        var k: Class<*>? = drawable.javaClass
        while (k != null) {
            val cur = k
            val f = runCatching { cur.getDeclaredField("mMode") }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                val old = runCatching { f.getInt(drawable) }.getOrDefault(-1)
                if (old != MODE_TRANSPARENT) {
                    runCatching { f.setInt(drawable, MODE_TRANSPARENT) }
                    Logx.v("小白条悬浮: 看门狗 mMode $old → 0")
                }
                return
            }
            k = cur.superclass
        }
    }

    /**
     * NavigationBarTransitions.onTransition(int, int, boolean) —— 唯一挂点。
     *
     * 第 0 参是 mode 枚举；改写成 TRANSPARENT 后 applyModeBackground 会把 mBarBackground.mMode 设为 0，
     * draw() 随即走透明分支。
     *
     * ── 为什么只挂这一个就够 ──
     * 全 dex 里 applyModeBackground 只有 4 处调用，导航栏相关 2 处：
     *   ① NavigationBarTransitions.onTransition  —— 正常 mode 变更（本条已拦）
     *   ② NavigationBarView.reorient              —— 读的是 NavigationBarTransitions.mMode（已被 ① 改过），
     *                                               所以 reorient 只会把 0 再刷一遍，天然保持一致
     * 其余 2 处属于 PhoneStatusBarTransitions（状态栏），与本功能无关。
     *
     * ── 为什么不上挂 BarTransitions.applyModeBackground ──
     * 该类同时是 PhoneStatusBarTransitions（状态栏）的父类，挂父类会连状态栏底色一起抹掉。
     * 因此必须落在 NavigationBarTransitions 这个导航栏专属子类上。
     *
     * ── 为什么不挂 applyLightsOut ──
     * applyLightsOut(ZZ) 只改 mLightsOut 与按钮 alpha，从不调用 applyModeBackground，对底色无影响；
     * 在它前后做反射改写反而会和按钮淡出动画抢时序，故不挂。
     */
    private fun hookOnTransition(m: MainHook, cl: ClassLoader): Int {
        val cls = runCatching { Class.forName(TRANSITIONS, false, cl) }.getOrNull() ?: run {
            Logx.e("小白条悬浮: 找不到 $TRANSITIONS")
            return 0
        }
        val mm = runCatching {
            cls.getDeclaredMethod("onTransition", Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE)
        }.getOrNull() ?: run {
            Logx.e("小白条悬浮: 找不到 onTransition(IIZ)")
            return 0
        }
        val ok = m.hookExecutable(mm, XposedInterface.Hooker { chain ->
            Cfg.refreshAsync()
            // 登记实例，让看门狗能覆盖到它（onTransition 的 thisObject 就是 NavigationBarTransitions）
            runCatching {
                val self = chain.getThisObject()
                // NavigationBarTransitions.mNavBarView / mView / mBar 之类，尽力找 NavigationBarView
                collectNavBarView(self)?.let { views.add(it) }
            }
            if (!Cfg.gestureHandleFloat) return@Hooker chain.proceed()
            // 分应用判定：ALL 模式下不必查前台包名（省一次 IPC）
            if (Cfg.floatMode != Constants.FLOAT_MODE_ALL && !Cfg.floatAppliesTo(currentPkg())) {
                return@Hooker chain.proceed()
            }
            val args = chain.args.toTypedArray()
            val old = (args[0] as? Int) ?: MODE_TRANSPARENT
            args[0] = MODE_TRANSPARENT
            if (old != MODE_TRANSPARENT) {
                Logx.v("小白条悬浮: onTransition mode $old → $MODE_TRANSPARENT (pkg=${cachedPkg ?: "?"})")
            }
            chain.proceed(args)
        })
        return if (ok) 1 else 0
    }

    /**
     * 挂 NavigationBarView.reorient()：折叠切换 / 旋转 / 内外屏切换都会走这里。
     *
     * 这是修复「折叠切换后底栏失控」的关键：
     *   Fold 状态变化 → NavigationBarView 重新 reorient（重新测量 + 重新设置底色）
     *   → 此时旧实例的 mMode 可能被重置为非 0，新实例则从未收到过 onTransition。
     * 在 reorient 前后各补一次 forceTransparent，保证切换完就是透明。
     *
     * 同时把 thisObject 登记进 views 集合，供看门狗兜底（新实例首次 reorient 时登记）。
     */
    private fun hookReorient(m: MainHook, cl: ClassLoader): Int {
        val cls = runCatching { Class.forName(NAV_BAR_VIEW, false, cl) }.getOrNull() ?: run {
            Logx.e("小白条悬浮: 找不到 $NAV_BAR_VIEW")
            return 0
        }
        // reorient() 在部分版本带参数（比如 reorient(boolean)），这里把所有同名方法都挂上
        val cands = cls.declaredMethods.filter { it.name == "reorient" }
        if (cands.isEmpty()) {
            Logx.e("小白条悬浮: 找不到 reorient")
            return 0
        }
        var cnt = 0
        for (mm in cands) {
            mm.isAccessible = true
            val ok = m.hookExecutable(mm, XposedInterface.Hooker { chain ->
                val self = chain.getThisObject()
                runCatching { views.add(self) }
                val r = chain.proceed()
                // proceed 之后再压一次：reorient 内部可能重置了 mMode
                runCatching {
                    if (Cfg.gestureHandleFloat &&
                        (Cfg.floatMode == Constants.FLOAT_MODE_ALL || Cfg.floatAppliesTo(currentPkg()))
                    ) {
                        forceTransparent(self)
                    }
                }
                r
            })
            if (ok) cnt++
        }
        return cnt
    }

    /**
     * 已知的两种持有关系，尽力从 NavigationBarTransitions 反查到 NavigationBarView：
     *   · NavigationBarTransitions 构造时接收 NavigationBarView（字段名 mView / mNavBarView）
     *   · 或 NavigationBarView 是它的宿主 View（getParent 链上找）
     * 找不到就返回 null（看门狗仍有 reorient 登记这条路，不算致命）。
     */
    private fun collectNavBarView(trans: Any): Any? {
        if (trans.javaClass.name == NAV_BAR_VIEW) return trans
        // ① 字段扫描
        var k: Class<*>? = trans.javaClass
        while (k != null) {
            val cur = k
            for (f in cur.declaredFields) {
                if (f.type?.name != NAV_BAR_VIEW) continue
                runCatching {
                    f.isAccessible = true
                    (f.get(trans) as? Any)?.let { return it }
                }
            }
            k = cur.superclass
        }
        return null
    }

    /** 带 TTL 的前台包名（仅白/黑名单模式会调用）。 */
    private fun currentPkg(): String? {
        val now = android.os.SystemClock.uptimeMillis()
        val c = cachedPkg
        if (c != null && now - cachedAt < PKG_TTL_MS) return c
        val p = queryForegroundPkg()
        if (p != null) {
            cachedPkg = p
            cachedAt = now
        }
        return p ?: c
    }

    /**
     * 取前台应用包名。沿用 Gestures 里已验证可用的 ActivityTaskManager 路径：
     * Android 17 上 ActivityManager.getTasks 已移除，systemui（system uid）用 getTasks(int,boolean,boolean)。
     * 过滤桌面/launcher：桌面本身就是「无应用内容」，悬浮与否无感，但为了让名单判定符合直觉，
     * 这里把桌面当作「不适用」返回 null，由调用方按名单语义处理（null 不在白名单里 → 不生效；
     * 黑名单模式下 null 也不在集合里 → 会生效，但桌面无 ContentView，视觉无差别）。
     */
    private fun queryForegroundPkg(): String? {
        return runCatching {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val atm = atmCls.getMethod("getInstance").invoke(null) ?: return@runCatching null
            val tries = listOf(
                arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false, false),
                arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, 0, false),
                arrayOf<Class<*>>(Integer.TYPE, java.lang.Boolean.TYPE) to arrayOf<Any>(16, false),
            )
            for ((sig, args) in tries) {
                val r = runCatching {
                    val m = atmCls.getMethod("getTasks", *sig)
                    val tasks = m.invoke(atm, *args) as? List<*> ?: return@runCatching null
                    for (e in tasks) {
                        val t = e as? android.app.ActivityManager.RunningTaskInfo ?: continue
                        val tn = t.topActivity ?: t.baseActivity ?: continue
                        val pkg = tn.packageName ?: continue
                        return@runCatching pkg
                    }
                    null
                }.getOrNull()
                if (r != null) return r
            }
            null
        }.getOrNull()
    }
}
