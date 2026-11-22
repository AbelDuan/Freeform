package com.abel.os4freeformx

import android.graphics.Rect
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 分屏（小米 WMShell SoSc / MultipleSplit，运行于 `com.android.systemui` 进程）共用的反射工具。
 *
 * ## 来源与合规
 * 四项分屏功能移植自开源项目 **HyperRefine**（AGPL-3.0），本文件与 [SplitDirection] /
 * [MultiSplitRatio] / [SplitRatioKeep] / [CaptionSplitBoth] 均为**按本模块 hook 风格独立重写**，
 * 未拷贝其框架代码。所有类名 / 方法签名 / 字段名都用本机反编译产物
 * `_recon/out_jadx`（HyperOS 4 / Android 17 的 `Miui-WindowManager-Shell.jar`）逐条核对过，
 * 不靠参考仓库的写法推断。
 *
 * ## 方向判定（本文件的核心）
 * 系统判断「当前是不是左右分屏」有两处，平板横屏时都会忽略「动态方向标志」：
 *   - `SplitUtilsImpl#isLeftRightSplit()`：`isPad() && isLandscape()` 直接返回 `!isTestMode`；
 *     非平板走 `supportedRatioCustomization() && isLandscape() → true`，也绕过了标志 ⇒
 *     **折叠屏横屏切成上下不生效**（这是真机会遇到的现象，HyperRefine 原实现只接管平板，
 *     在本机折叠屏上等于没修）。
 *   - `MulWinSwitchSplitUtils#isLeftRightSplit()`：未进分屏时恒返回「上下」，把手菜单图标因此与实际方向不符。
 *
 * 两个类里真正的「用户意图」存在 `SplitUtilsImpl#mDynamicTopBottomSplit`：
 *   `true` = 用当前方向的默认（竖屏上下 / 横屏左右），`false` = 被菜单切到了另一方向。
 * 所以 `左右 = (isLandscape() == mDynamicTopBottomSplit)` —— 这条判定平板与折叠屏通用，
 * 本模块直接以它为准（见 [effectiveLeftRightSplit]）。
 */
internal object SoScSupport {

    // ---------------- 目标类 ----------------

    const val SPLIT_UTILS_IMPL = Constants.CLS_SPLIT_UTILS_IMPL
    const val SPLIT_UTILS_STUB = Constants.CLS_SPLIT_UTILS_STUB
    const val SPLIT_SCREEN_UTILS = Constants.CLS_SPLIT_SCREEN_UTILS
    const val SOSC_UTILS_IMPL = Constants.CLS_SOSC_UTILS_IMPL
    const val MULWIN_SPLIT_UTILS = Constants.CLS_MULWIN_SPLIT_UTILS
    const val SPLIT_LAYOUT = Constants.CLS_SOSC_SPLIT_LAYOUT
    const val STAGE_COORD = Constants.CLS_SOSC_STAGE_COORD
    const val DIVIDER_SNAP = Constants.CLS_SOSC_DIVIDER_SNAP

    /** `SNAP_TO_NONE`：算法对「不在任何档位上」的位置返回的 snapPosition。 */
    const val SNAP_TO_NONE = 10

    // ---------------- 实例获取 ----------------

    fun findClass(cl: ClassLoader, name: String): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /** `SoScUtilsImpl.getInstance()`（折叠屏 / 平板走 SoSc 分屏时的总服务）。 */
    fun soScUtils(cl: ClassLoader): Any? = runCatching {
        findClass(cl, SOSC_UTILS_IMPL)?.getMethod("getInstance")?.invoke(null)
    }.getOrNull()

    /** `SplitUtilsImpl.getInstance()`（分屏能力总闸 + 动态方向标志）。 */
    fun splitUtils(cl: ClassLoader): Any? = runCatching {
        findClass(cl, SPLIT_UTILS_STUB)?.getMethod("getInstance")?.invoke(null)
    }.getOrNull()

    /** `SoScUtilsImpl.mStageCoordinator`。 */
    fun stageCoordinator(cl: ClassLoader): Any? = field(soScUtils(cl), "mStageCoordinator")

    /** 进入分屏后真正用于几何 / 吸附的布局对象（`SoScStageCoordinator#getSplitLayout`）。 */
    fun splitLayout(cl: ClassLoader): Any? = call(stageCoordinator(cl), "getSplitLayout")

    /** 当前是否已处于分屏状态。 */
    fun isSoScActive(cl: ClassLoader): Boolean =
        (call(soScUtils(cl), "isSoScActive") as? Boolean) == true

    /** 迷你小窗等最小化形态（此时占比没有意义，保持系统行为）。 */
    fun inMinimizedMode(cl: ClassLoader): Boolean =
        (call(soScUtils(cl), "inSoScMinimizedMode") as? Boolean) == true

    // ---------------- 方向 ----------------

    /**
     * 期望的「是否左右分屏」：`左右 = (横屏 == 动态方向标志)`。
     *
     * 只在 `SplitUtilsImpl.mReady` 已就绪、且调用方开关打开时返回非 null；否则交回系统原实现
     * （返回 null = 不接管），避免在无关设备 / 未初始化的早期阶段改变行为。
     */
    fun effectiveLeftRightSplit(cl: ClassLoader): Boolean? = runCatching {
        val stub = splitUtils(cl) ?: return@runCatching null
        if (boolField(stub, "mReady") != true) return@runCatching null
        val soSc = soScUtils(cl) ?: return@runCatching null
        val landscape = call(soSc, "isLandscape") as? Boolean ?: return@runCatching null
        val dynamic = boolField(stub, "mDynamicTopBottomSplit") ?: return@runCatching null
        landscape == dynamic
    }.getOrNull()

    /** `SplitLayout#mIsLeftRightSplit`：布局对象当前缓存的方向（真正用于几何的那个值）。 */
    fun layoutLeftRightSplit(cl: ClassLoader): Boolean? = boolField(splitLayout(cl), "mIsLeftRightSplit")

    /**
     * 让「实际进入分屏时使用的方向」（`SplitLayout#mIsLeftRightSplit`）与期望一致。
     *
     * 进入分屏的几何与吸附读的是布局对象里缓存的值，而不是实时的 `isLeftRightSplit()`，
     * 所以必须调官方 `updateDynamicLeftRightSplit()`（翻转动态方向标志并重算布局方向 / 吸附算法）
     * 才能做到「点哪个按钮就进哪个方向」。
     */
    fun ensureDirection(cl: ClassLoader, wantLeftRight: Boolean): Boolean {
        val layout = splitLayout(cl)
        if (layout != null && boolField(layout, "mIsLeftRightSplit") != null) {
            repeat(3) {
                if (boolField(layout, "mIsLeftRightSplit") == wantLeftRight) return true
                runCatching { call(layout, "updateDynamicLeftRightSplit") }
                    .onFailure { Logx.e("分屏方向: 对齐失败", it) }
            }
            val ok = boolField(layout, "mIsLeftRightSplit") == wantLeftRight
            if (!ok) Logx.e("分屏方向: 无法对齐到左右=$wantLeftRight（布局对象拒绝翻转）")
            return ok
        }
        // 退化路径：拿不到布局对象时只翻转动态方向标志。
        val current = effectiveLeftRightSplit(cl) ?: return false
        if (current == wantLeftRight) return true
        val stub = splitUtils(cl) ?: return false
        return runCatching {
            call(stub, "updateDynamicLeftRightSplit")
            true
        }.onFailure { Logx.e("分屏方向: 翻转动态标志失败", it) }.getOrDefault(false)
    }

    /** 分割线在「槽位 0（左 / 上）」所占的比例；未分屏或尺寸无效时 null。 */
    fun splitRatio(cl: ClassLoader): Float? = runCatching {
        val layout = splitLayout(cl) ?: return@runCatching null
        val leftRight = boolField(layout, "mIsLeftRightSplit") ?: return@runCatching null
        val position = intField(layout, "mDividerPosition") ?: return@runCatching null
        val root = call(layout, "getRootBounds") as? Rect ?: return@runCatching null
        val dimension = if (leftRight) root.width() else root.height()
        if (position <= 0 || dimension <= 0) return@runCatching null
        position.toFloat() / dimension
    }.getOrNull()

    // ---------------- 资源 ----------------

    /**
     * 取 WMShell 自己的资源 id（`com.android.wm.shell.R`）。
     *
     * WMShell 是共享库资源（运行时包段 0x70），按 `getIdentifier(name, type, pkg)` 找不到，
     * 只能读它自己的 R 类字段。
     */
    fun wmRes(cl: ClassLoader, type: String, name: String): Int = runCatching {
        Class.forName("com.android.wm.shell.R\$$type", false, cl)
            .getField(name).getInt(null)
    }.getOrDefault(0)

    /** dp → px（系统密度，不需要 Context）。 */
    fun dpToPx(dp: Float): Int = runCatching {
        (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }.getOrDefault(dp.toInt())

    // ---------------- 反射底层 ----------------

    /**
     * 字段 / 方法缓存：`isLeftRightSplit()`、分割线拖动这些都是每秒几十次的热路径，
     * 每次遍历 `declaredFields` 太浪费（真机上表现为拖动发涩）。
     * 字段用「类 + 名字」两级 Map（避免热路径拼字符串），方法用字符串键（调用频率低得多）。
     */
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, MutableMap<String, Field>>()
    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Method>()

    /** 沿继承链找字段（`mXxx` 常在父类上）。 */
    private fun fieldOf(o: Any?, name: String): Field? {
        val cls = o?.javaClass ?: return null
        fieldCache[cls]?.get(name)?.let { return it }
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                fieldCache.getOrPut(cls) { java.util.concurrent.ConcurrentHashMap() }[name] = it
                return it
            }
            c = c.superclass
        }
        return null
    }

    fun field(o: Any?, name: String): Any? = runCatching {
        val f = fieldOf(o, name) ?: return null
        f.isAccessible = true
        f.get(o)
    }.getOrNull()

    fun intField(o: Any?, name: String): Int? = field(o, name) as? Int

    fun boolField(o: Any?, name: String): Boolean? = field(o, name) as? Boolean

    fun setField(o: Any?, name: String, value: Any?): Boolean = runCatching {
        val f = fieldOf(o, name) ?: return false
        f.isAccessible = true
        f.set(o, value)
        true
    }.getOrDefault(false)

    /**
     * 按「方法名 + 参数个数」调用（本机 WMShell 里同名重载的参数个数都不同，够用）。
     * 先查 public 方法（含继承），再查各级 declaredMethods（含 private）。
     */
    fun call(o: Any?, name: String, vararg args: Any?): Any? {
        if (o == null) return null
        val key = o.javaClass.name + "#" + name + "/" + args.size
        return runCatching {
            val cached = methodCache[key]
            if (cached != null) return cached.invoke(o, *args)
            val pub = o.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            if (pub != null) {
                pub.isAccessible = true
                methodCache[key] = pub
                return pub.invoke(o, *args)
            }
            var c: Class<*>? = o.javaClass
            while (c != null && c != Any::class.java) {
                val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
                if (m != null) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m.invoke(o, *args)
                }
                c = c.superclass
            }
            null
        }.onFailure { Logx.v("分屏: 调用 $name 失败: $it") }.getOrNull()
    }

    fun callStatic(cl: ClassLoader, className: String, name: String, vararg args: Any?): Any? = runCatching {
        val cls = findClass(cl, className) ?: return null
        val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size } ?: return null
        m.isAccessible = true
        m.invoke(null, *args)
    }.getOrNull()

    /** 构造实例（内部类把外部实例作为第一个构造参数传入）。 */
    fun newInstance(cls: Class<*>, vararg args: Any?): Any? = runCatching {
        val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == args.size } ?: return null
        ctor.isAccessible = true
        ctor.newInstance(*args)
    }.getOrNull()

    /** 取指定名字 + 参数个数的方法（用于 hook，含 private）。 */
    fun methodOf(cls: Class<*>?, name: String, params: Int): Method? =
        cls?.declaredMethods?.firstOrNull { it.name == name && it.parameterCount == params }
            ?.also { it.isAccessible = true }
}
