package com.abel.os4freeformx

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import kotlin.math.abs

/**
 * 「跟随应用」占比桥：把手菜单点击另一方向的分屏按钮时，先把「点击应用切换前的占比」
 * 写到这里，[SplitRatioKeep] 在下一次方向切换时优先采用。
 *
 * 只在本模块内部使用；未消费时由写入方 [CaptionSplitBoth] 在调用返回后清空。
 */
internal object SplitRatioBridge {
    @Volatile var nextSwitchRatio: Float? = null

    fun consume(): Float? {
        val value = nextSwitchRatio
        nextSwitchRatio = null
        return value
    }
}

/**
 * 功能 3：**切换分屏方向（左右 ↔ 上下）时保持每个应用的占比**。
 *
 * ## 来源与合规
 * 移植自开源项目 **HyperRefine**（AGPL-3.0）的 `SplitRatioMemoryHook`，按本模块 hook 风格
 * 独立重写。目标类与方法签名已用本机 `_recon/out_jadx` 核对：
 *   - `com.android.wm.shell.sosc.common.split.SplitLayout#initDividerPosition(Rect, boolean)`
 *   - `com.android.wm.shell.sosc.SoScStageCoordinator#switchSplitDirection(String)`
 *   - `…SoScStageCoordinator#updateDividerFixedState(boolean)`
 *   - `com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm#is1_9SplitSnapMode()`
 *
 * ## 官方本来会按比例换算，但紧接着又把比例改掉
 * `SplitLayout#initDividerPosition(旧 bounds, 旧方向)` 自己会算
 * 「旧方向占比 × 新方向尺寸」，但有两条路径随后覆盖这个结果：
 * 1. `switchSplitDirection` 切到左右分屏且分割线不在 1:1 时，会按焦点把线强推到 1:9 档位
 *    （`setDividerPositionInRatio1_9`）—— 切换期间让 `is1_9SplitSnapMode()` 返回一次 false 拦掉；
 * 2. 切换过程中 `onPreSoScStateChanged` 在「分割线锁定」（`mIsDividerFixed`，平板上桌面常置 true）
 *    时会把布局边界钳回 1:1 —— 切换期间临时挂起该标志，结束后按桌面最新一次推送恢复。
 *
 * 占比快照取自 `initDividerPosition` 的入参（旧方向 + 旧方向尺寸），所以：
 * 把手菜单切方向、分割线菜单切方向、旋屏（旋转 / 配置变化）三条路径都等价命中。
 *
 * ## 与 libxposed 相关的改写
 * 参考实现用的是老 XposedBridge 的 `hookBefore` / `hookAfter`（可 `setResultValue`）。
 * libxposed 的 [XposedInterface.Chain] 没有 setResult —— 只有一个 hooker，
 * 所以这里把「抓快照 → proceed → 还原」写进同一个 hooker 的前后两段。
 */
object SplitRatioKeep {

    /** 占比有效区间（关闭分屏等极端位置不还原）。 */
    private const val MIN_RATIO = 0.02f
    private const val MAX_RATIO = 0.98f

    /** 收尾复查允许的像素偏差。 */
    private const val DRIFT_TOLERANCE_PX = 2

    /** 换算后贴回 1:1 / 1:9 档位的容差（px）。 */
    private const val MIDDLE_TOLERANCE_PX = 8
    private const val EDGE_TOLERANCE_PX = 4

    /** 兜底恢复「分割线锁定」的延时。 */
    private const val SAFETY_RESTORE_MS = 2500L

    private var loader: ClassLoader? = null

    /** `initDividerPosition` 调用前的占比快照（官方同步调用，同一线程读写）。 */
    private var pending: RatioSnapshot? = null

    /** 最近一次方向切换写入的目标，用于切换收尾复查。 */
    private var applied: AppliedRatio? = null

    /** 是否处于官方 `switchSplitDirection` 的同步执行期。 */
    private var switchActive = false

    /** 切换期间临时挂起 `mIsDividerFixed` 前记录的原值。 */
    private var dividerFixedOriginal: Boolean? = null

    /** 切换期间桌面重新推送的 isFixed（以最新一次为准）。 */
    private var dividerFixedLatest: Boolean? = null

    /** 已武装的「拦掉本次切换内的 1:9 强推」标记。 */
    private var suppress1_9 = false

    private val handler = Handler(Looper.getMainLooper())
    private var safetyRestore: Runnable? = null

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        var ok = 0
        runCatching { ok += hookInitDividerPosition(m, cl) }
            .onFailure { Logx.e("换向保比例: 挂 initDividerPosition 失败", it) }
        runCatching { ok += hookSwitchSplitDirection(m, cl) }
            .onFailure { Logx.e("换向保比例: 挂 switchSplitDirection 失败", it) }
        runCatching { ok += hook1_9SnapMode(m, cl) }
            .onFailure { Logx.e("换向保比例: 挂 is1_9SplitSnapMode 失败", it) }
        runCatching { ok += hookDividerFixedState(m, cl) }
            .onFailure { Logx.e("换向保比例: 挂 updateDividerFixedState 失败", it) }
        if (ok == 0) {
            Logx.e("换向保比例: 一个都没挂上（本机 SoSc 分屏类名可能变了）")
            return
        }
        Logx.always("换向保比例: 已挂 $ok/4（开关=${Cfg.splitRatioKeep}）")
    }

    // ---------------- SplitLayout#initDividerPosition ----------------

    private fun hookInitDividerPosition(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.SPLIT_LAYOUT) ?: run {
            Logx.e("换向保比例: 找不到 ${SoScSupport.SPLIT_LAYOUT}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "initDividerPosition", 2) ?: run {
            Logx.e("换向保比例: 找不到 SplitLayout#initDividerPosition/2")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val layout = chain.thisObject
                // before：记录「切换前」的占比（官方入参 = 旧 bounds + 旧方向）
                capture(layout, chain.getArg(0) as? Rect, chain.getArg(1) as? Boolean)
                val r = chain.proceed()
                // after：方向真正切换后按同一占比把分割线写回
                restore(layout)
                r
            })) 1 else 0
    }

    private fun capture(layout: Any?, rect: Rect?, oldLeftRight: Boolean?) {
        pending = null
        // 进分屏 / 旋屏 / 切方向都会走到这里：顺带节流刷新配置，
        // 让设置页改完开关后操作一次分屏就生效（不用重启 SystemUI）。
        Cfg.reloadThrottled()
        if (layout == null || rect == null || oldLeftRight == null) return
        if (!Cfg.splitRatioKeep) return
        val cl = loader ?: return
        if (!SoScSupport.isSoScActive(cl)) return
        if (SoScSupport.inMinimizedMode(cl)) return
        if (switchActive) {
            // 把手菜单点击「另一方向」的分屏按钮时写入的「跟随应用」占比
            SplitRatioBridge.consume()?.let { ratio ->
                if (ratio > MIN_RATIO && ratio < MAX_RATIO) {
                    pending = RatioSnapshot(ratio, oldLeftRight, "menu app share")
                    return
                }
            }
        }
        val position = SoScSupport.intField(layout, "mDividerPosition") ?: return
        val dimension = if (oldLeftRight) rect.width() else rect.height()
        if (position <= 0 || dimension <= 0) return
        val ratio = position.toFloat() / dimension
        if (ratio <= MIN_RATIO || ratio >= MAX_RATIO) return
        pending = RatioSnapshot(ratio, oldLeftRight, "live $position/$dimension")
    }

    private fun restore(layout: Any?) {
        if (layout == null) return
        val snapshot = pending ?: return
        pending = null
        if (!Cfg.splitRatioKeep) return
        val newLeftRight = SoScSupport.boolField(layout, "mIsLeftRightSplit") ?: return
        if (newLeftRight == snapshot.oldLeftRight) return
        val dimension = axisDimension(layout) ?: return
        if (dimension <= 0) return
        val position = applyRatio(layout, snapshot.ratio, dimension)
        applied = AppliedRatio(snapshot.ratio, newLeftRight, position)
        if (switchActive) suppress1_9 = true
        Logx.always(
            "换向保比例: 保持 ${percent(snapshot.ratio)}（${snapshot.from} -> pos=$position/$dimension, 左右=$newLeftRight）"
        )
    }

    /** 把占比写回分割线：位置 + 分屏状态 + 固定吸附目标。 */
    private fun applyRatio(layout: Any, ratio: Float, dimension: Int): Int {
        val position = targetPosition(layout, ratio, dimension)
        SoScSupport.call(layout, "setDividerPosition", position, false)
        SoScSupport.call(layout, "updateStateWithCurrentPosition")
        syncFixedSnapTarget(layout, position)
        return position
    }

    /** 占比 → 新方向的分割线位置（含档位贴回）。 */
    private fun targetPosition(layout: Any, ratio: Float, dimension: Int): Int =
        alignToNearbyTarget(
            layout,
            (dimension * ratio).toInt().coerceIn(1, dimension - 1)
        )

    /**
     * 两个方向的尺寸不同，换算天然有半个分割线的取整误差：非常接近 1:1 档位或 1:9 档位时
     * 贴回档位，避免「切过去再切回来」逐次漂移，也让 1:9 的移出屏幕形态精确复现。
     */
    private fun alignToNearbyTarget(layout: Any, position: Int): Int {
        val algorithm = SoScSupport.call(layout, "getDividerSnapAlgorithm") ?: return position
        val candidates = listOf(
            "mMiddleTarget" to MIDDLE_TOLERANCE_PX,
            "mFirstSplitTarget" to EDGE_TOLERANCE_PX,
            "mLastSplitTarget" to EDGE_TOLERANCE_PX
        )
        candidates.forEach { (field, tolerance) ->
            val target = SoScSupport.field(algorithm, field) ?: return@forEach
            val targetPosition = SoScSupport.intField(target, "position") ?: return@forEach
            if (abs(position - targetPosition) <= tolerance) return targetPosition
        }
        return position
    }

    /**
     * 与官方 `initDividerPosition` 一致：同步「固定吸附目标」。
     * 不同步的话，单应用分屏下松手吸附会继续用换算前的旧目标。
     */
    private fun syncFixedSnapTarget(layout: Any, position: Int) {
        runCatching {
            val algorithm = SoScSupport.call(layout, "getDividerSnapAlgorithm") ?: return
            val target = SoScSupport.call(algorithm, "calculateNonDismissingSnapTarget", position) ?: run {
                Logx.always("换向保比例: 固定吸附目标取不到（pos=$position），保持原值")
                return
            }
            SoScSupport.setField(layout, "mFixedSnapTarget", target)
            // 单侧分屏（另一侧是桌面）下这个字段直接决定松手吸附，出问题会崩 SystemUI，
            // 所以落一条可追溯的日志（仅换向时触发，不是热路径）。
            Logx.always(
                "换向保比例: 同步固定吸附目标 pos=$position " +
                    "target=${SoScSupport.intField(target, "position")}" +
                    "/snapPosition=${SoScSupport.intField(target, "snapPosition")}"
            )
        }.onFailure { Logx.e("换向保比例: 同步固定吸附目标失败", it) }
    }

    // ---------------- SoScStageCoordinator#switchSplitDirection ----------------

    private fun hookSwitchSplitDirection(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.STAGE_COORD) ?: run {
            Logx.e("换向保比例: 找不到 ${SoScSupport.STAGE_COORD}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "switchSplitDirection", 1) ?: run {
            Logx.e("换向保比例: 找不到 SoScStageCoordinator#switchSplitDirection/1")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                if (!Cfg.splitRatioKeep) {
                    chain.proceed()
                } else {
                    onSwitchBegin(chain.thisObject)
                    try {
                        chain.proceed()
                    } finally {
                        onSwitchEnd(chain.thisObject)
                    }
                }
            })) 1 else 0
    }

    private fun onSwitchBegin(coordinator: Any?) {
        switchActive = true
        pending = null
        applied = null
        suppress1_9 = false
        dividerFixedLatest = null
        dividerFixedOriginal = SoScSupport.boolField(coordinator, "mIsDividerFixed")
        // 平板上桌面常把「分割线锁定」置为 true：切换期间 onPreSoScStateChanged 会据此把
        // 布局边界钳回 1:1，先把标志临时挂起；结束后按桌面最新一次推送恢复。
        if (coordinator != null && dividerFixedOriginal == true) {
            SoScSupport.setField(coordinator, "mIsDividerFixed", false)
            scheduleSafetyRestore(coordinator)
            Logx.v("换向保比例: 切换期间挂起分割线锁定")
        }
    }

    private fun onSwitchEnd(coordinator: Any?) {
        switchActive = false
        suppress1_9 = false
        cancelSafetyRestore()
        if (coordinator != null) {
            (dividerFixedLatest ?: dividerFixedOriginal)?.let {
                SoScSupport.setField(coordinator, "mIsDividerFixed", it)
            }
        }
        dividerFixedOriginal = null
        dividerFixedLatest = null
        verifyApplied()
    }

    /** 切换收尾复查：理论上线已在目标位置（1:9 强推与 1:1 钳制都被拦掉），有偏差再补一次。 */
    private fun verifyApplied() {
        val expected = applied ?: return
        applied = null
        val cl = loader ?: return
        val layout = SoScSupport.splitLayout(cl) ?: return
        val current = SoScSupport.intField(layout, "mDividerPosition") ?: return
        val leftRight = SoScSupport.boolField(layout, "mIsLeftRightSplit")
        val dimension = axisDimension(layout) ?: return
        val target = targetPosition(layout, expected.ratio, dimension)
        if (leftRight != expected.leftRight || abs(current - target) > DRIFT_TOLERANCE_PX) {
            Logx.always("换向保比例: 收尾复查有偏差（pos=$current, 期望=$target），补一次")
            applyRatio(layout, expected.ratio, dimension)
        } else {
            Logx.v("换向保比例: 收尾复查通过（pos=$current, ${percent(expected.ratio)}）")
        }
    }

    // ---------------- DividerSnapAlgorithm#is1_9SplitSnapMode ----------------

    /**
     * `switchSplitDirection` 在方向切换后紧跟一段「切到左右分屏且不在 1:1 → 按焦点强推 1:9
     * 档位」的逻辑（同一个同步调用链里的第一次 `is1_9SplitSnapMode()` 查询）。
     * 若刚刚按占比还原过分割线，这里返回 false 拦掉这一次，保持占比不变。
     */
    private fun hook1_9SnapMode(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.DIVIDER_SNAP) ?: run {
            Logx.e("换向保比例: 找不到 ${SoScSupport.DIVIDER_SNAP}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "is1_9SplitSnapMode", 0) ?: run {
            Logx.e("换向保比例: 找不到 is1_9SplitSnapMode/0")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                if (suppress1_9) {
                    suppress1_9 = false
                    Logx.v("换向保比例: 拦掉本次 1:9 强推")
                    false
                } else {
                    chain.proceed()
                }
            })) 1 else 0
    }

    // ---------------- SoScStageCoordinator#updateDividerFixedState ----------------

    /** 切换期间桌面若重新推送「分割线锁定」，只记录不生效，收尾时按最新值恢复。 */
    private fun hookDividerFixedState(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, SoScSupport.STAGE_COORD) ?: return 0
        val method = SoScSupport.methodOf(cls, "updateDividerFixedState", 1) ?: run {
            Logx.e("换向保比例: 找不到 updateDividerFixedState/1")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val fixed = chain.getArg(0) as? Boolean
                if (switchActive && Cfg.splitRatioKeep && fixed != null) {
                    dividerFixedLatest = fixed
                    if (fixed) {
                        Logx.v("换向保比例: 切换中收到 isFixed=true，暂缓")
                        null
                    } else {
                        chain.proceed()
                    }
                } else {
                    chain.proceed()
                }
            })) 1 else 0
    }

    /**
     * 兜底：万一 `switchSplitDirection` 以异常结束导致收尾没跑到，
     * 延时把「分割线锁定」按最新值恢复，避免标志一直挂起。
     */
    private fun scheduleSafetyRestore(coordinator: Any) {
        cancelSafetyRestore()
        val runnable = Runnable {
            safetyRestore = null
            switchActive = false
            (dividerFixedLatest ?: dividerFixedOriginal)?.let { value ->
                if (SoScSupport.boolField(coordinator, "mIsDividerFixed") != value) {
                    SoScSupport.setField(coordinator, "mIsDividerFixed", value)
                    Logx.v("换向保比例: 兜底恢复 isFixed=$value")
                }
            }
        }
        safetyRestore = runnable
        handler.postDelayed(runnable, SAFETY_RESTORE_MS)
    }

    private fun cancelSafetyRestore() {
        safetyRestore?.let { handler.removeCallbacks(it) }
        safetyRestore = null
    }

    // ---------------- 工具 ----------------

    /** 分割线所在轴的尺寸（新方向 + 新 bounds）。 */
    private fun axisDimension(layout: Any): Int? {
        val leftRight = SoScSupport.boolField(layout, "mIsLeftRightSplit") ?: return null
        val root = SoScSupport.call(layout, "getRootBounds") as? Rect ?: return null
        return if (leftRight) root.width() else root.height()
    }

    private fun percent(ratio: Float): String = "${(ratio * 1000).toInt() / 10f}%"

    private data class RatioSnapshot(val ratio: Float, val oldLeftRight: Boolean, val from: String)

    private data class AppliedRatio(val ratio: Float, val leftRight: Boolean, val position: Int)
}
