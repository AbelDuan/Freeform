package com.abel.os4freeformx

import io.github.libxposed.api.XposedInterface

/**
 * 功能 2：**多分屏无级调节宽度**（只在 3 个及以上分屏时生效，2 分屏保持官方行为）。
 *
 * ## 来源与合规
 * 移植自开源项目 **HyperRefine**（AGPL-3.0）的 `MultipleSplitFreeRatioHook`，
 * 按本模块 hook 风格独立重写（LibXposed 没有 `setResultValue`，改结果靠 hooker 直接返回）。
 * 目标类与方法签名已用本机 `_recon/out_jadx` 核对：
 *   - `com.android.wm.shell.multiplesplit.MultipleSplitSnapAlgorithm#calculateSnapTarget(int, float, boolean)`
 *   - `…MultipleSplitSnapAlgorithm#isValidWidth(int)`、字段 `mMiniMoveWidth` / `mMaxMoveWidth` / `mTargets`
 *   - `…MultipleSplitLayout#getSnapPosition(int)`（private，1 参）
 *
 * ## 官方为什么只能吸档
 * 1. 松手 / 拖拽吸附：`calculateSnapTarget(position, velocity, snapToDismiss)` 在慢速时调
 *    `snap(position, snapToDismiss)`，把分割线吸到固定档位（snapPosition 21…34）；
 * 2. 位置校验：`MultipleSplitLayout#getSnapPosition(int)` 对「不在任何档位上」的位置返回
 *    `SNAP_TO_NONE = 10`，`isValidDividerPosition` 见到 10 直接判非法；
 * 3. 宽度校验：`isValidWidth(int)` 只认由档位间距算出的离散宽度。
 *
 * 接管方式（开关打开且分屏数 ≥3 时）：
 * - 松手停在原地：返回 `SnapTarget(position, 10, 1.0f, false)`（官方自己的"自由目标"形态）；
 * - 保留磁吸：松手位置距系统档位小于 24dp 时仍吸附过去，手感不至于"吸不住"；
 * - 校验放行：`getSnapPosition` 的 10 映射成 0；
 * - 宽度放宽：`[mMiniMoveWidth, mMaxMoveWidth]` 之间任意宽度都算合法。
 *
 * ## 分屏数量判定（用户明确要求）
 * **多分屏 = 3 个及以上，不含 2 分屏。** 数量取自 `MultipleSplitLayout#mCurrentDividerPosition`
 * （当前所有分割线位置列表，`size + 1` 就是当前分屏的应用数）。数量未知（缓存为 0）时
 * 一律不接管 —— 宁可退回官方吸附，也不要在 2 分屏上误伤。
 */
object MultiSplitRatio {

    /** 多分屏的最小应用数（含）。2 分屏不算多分屏。 */
    private const val MIN_STAGES = 3

    /** 磁吸半径（dp）：无级调节下，距系统档位小于该距离即吸附过去。 */
    private const val MAGNET_DP = 24f

    /** `SNAP_TO_NONE`：算法对非档位位置返回的 snapPosition，也是"自由目标"用的值。 */
    private const val SNAP_TO_NONE = SoScSupport.SNAP_TO_NONE

    /** 关闭分屏的两个极端档位（不参与磁吸）。 */
    private const val DISMISS_START = 11
    private const val DISMISS_END = 12

    @Volatile private var stageCount = 0
    @Volatile private var magnetPx = 0

    private var loader: ClassLoader? = null

    fun install(m: MainHook, cl: ClassLoader) {
        loader = cl
        var ok = 0
        runCatching { ok += hookCalculateSnapTarget(m, cl) }
            .onFailure { Logx.e("多分屏无级: 挂 calculateSnapTarget 失败", it) }
        runCatching { ok += hookLayoutSnapPosition(m, cl) }
            .onFailure { Logx.e("多分屏无级: 挂 getSnapPosition 失败", it) }
        runCatching { ok += hookValidWidth(m, cl) }
            .onFailure { Logx.e("多分屏无级: 挂 isValidWidth 失败", it) }
        if (ok == 0) {
            Logx.e("多分屏无级: 一个都没挂上（本机 WMShell 多分屏类名可能变了）")
            return
        }
        Logx.always("多分屏无级（≥$MIN_STAGES 分屏）: 已挂 $ok/3（开关=${Cfg.multiSplitFree}）")
    }

    /** 开关 + 分屏数双重门禁。热路径只读 volatile，关掉立刻退回官方吸附。 */
    private fun active(): Boolean = Cfg.multiSplitFree && stageCount >= MIN_STAGES

    // ---------------- 1) 松手 / 拖拽吸附 ----------------

    /**
     * `calculateSnapTarget(position, velocity, snapToDismiss)`：
     * 距系统档位近就吸过去（磁吸），否则原地停住。
     */
    private fun hookCalculateSnapTarget(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_MULTI_SPLIT_SNAP) ?: run {
            Logx.e("多分屏无级: 找不到 ${Constants.CLS_MULTI_SPLIT_SNAP}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "calculateSnapTarget", 3) ?: run {
            Logx.e("多分屏无级: 找不到 calculateSnapTarget/3")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val algorithm = chain.thisObject
                val position = chain.getArg(0) as? Int
                val result = if (algorithm == null || position == null || !active()) {
                    chain.proceed()
                } else {
                    nearestTarget(algorithm, position) ?: freeTarget(algorithm, position) ?: chain.proceed()
                }
                result
            })) 1 else 0
    }

    /** `new SnapTarget(position, 10, 1.0f, false)`（非静态内部类，构造要多带外部实例）。 */
    private fun freeTarget(algorithm: Any, position: Int): Any? {
        val cl = loader ?: return null
        val cls = SoScSupport.findClass(cl, Constants.CLS_MULTI_SPLIT_SNAP_TARGET) ?: return null
        return SoScSupport.newInstance(cls, algorithm, position, SNAP_TO_NONE, 1.0f, false)
            ?: SoScSupport.newInstance(cls, position, SNAP_TO_NONE, 1.0f, false)
    }

    /**
     * `mTargets` 里距 [position] 最近的**真实档位**（跳过自由目标 10 与关闭分屏 11/12）。
     * 距离超过磁吸半径返回 null。
     */
    private fun nearestTarget(algorithm: Any, position: Int): Any? = runCatching {
        val targets = SoScSupport.field(algorithm, "mTargets") as? List<*> ?: return@runCatching null
        var best: Any? = null
        var bestDistance = Int.MAX_VALUE
        for (target in targets) {
            if (target == null) continue
            val snapPosition = SoScSupport.intField(target, "snapPosition") ?: continue
            if (snapPosition == SNAP_TO_NONE || snapPosition == DISMISS_START || snapPosition == DISMISS_END) continue
            val targetPosition = SoScSupport.intField(target, "position") ?: continue
            val distance = kotlin.math.abs(targetPosition - position)
            if (distance < bestDistance) {
                bestDistance = distance
                best = target
            }
        }
        if (best != null && bestDistance <= magnetRadius()) best else null
    }.getOrNull()

    private fun magnetRadius(): Int {
        val cached = magnetPx
        if (cached > 0) return cached
        return SoScSupport.dpToPx(MAGNET_DP).also { magnetPx = it }
    }

    // ---------------- 2) 位置校验 + 分屏数量采样 ----------------

    /**
     * `MultipleSplitLayout#getSnapPosition(int)`（private）：
     * - 顺带更新「当前分屏数」（拖动中这个方法被 `isValidDividerPosition` 反复调用，
     *   比任何定时器都及时）；
     * - 接管期间把 `SNAP_TO_NONE(10)` 映射成 0，让自由位置通过校验。
     */
    private fun hookLayoutSnapPosition(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_MULTI_SPLIT_LAYOUT) ?: run {
            Logx.e("多分屏无级: 找不到 ${Constants.CLS_MULTI_SPLIT_LAYOUT}")
            return 0
        }
        val method = SoScSupport.methodOf(cls, "getSnapPosition", 1) ?: run {
            Logx.e("多分屏无级: 找不到 MultipleSplitLayout#getSnapPosition/1")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                refreshStageCount(chain.thisObject)
                val result = chain.proceed()
                if (active() && (result as? Int) == SNAP_TO_NONE) 0 else result
            })) 1 else 0
    }

    /**
     * 从布局对象读当前分屏数：`mCurrentDividerPosition` 是「当前所有分割线位置」，
     * 应用数 = size + 1（2 个应用 1 条线、3 个应用 2 条线）。
     */
    private fun refreshStageCount(layout: Any?) {
        // 拖动分割线是最热的热路径：让开关改动 1~2 秒内生效（节流 + 异步，绝不阻塞拖动）
        Cfg.reloadThrottled()
        if (layout == null) return
        val list = SoScSupport.field(layout, "mCurrentDividerPosition") as? List<*> ?: return
        val n = list.size + 1
        if (n != stageCount) {
            stageCount = n
            Logx.always("多分屏无级: 当前分屏数=$n（${if (n >= MIN_STAGES) "接管" else "不足 $MIN_STAGES 个，保持官方吸附"}）")
        }
    }

    // ---------------- 3) 宽度校验 ----------------

    /**
     * `isValidWidth(width)`：官方只认档位间距算出的离散宽度；
     * 这里放宽成「最小宽度 ≤ width ≤ 最大可移动宽度」的连续区间
     * （与官方由档位间距推出的最小 / 最大宽度同一套边界，拖拽时的最小宽度限制仍由
     * `updateMultipleDividerPosition` 保证）。
     */
    private fun hookValidWidth(m: MainHook, cl: ClassLoader): Int {
        val cls = SoScSupport.findClass(cl, Constants.CLS_MULTI_SPLIT_SNAP) ?: return 0
        val method = SoScSupport.methodOf(cls, "isValidWidth", 1) ?: run {
            Logx.e("多分屏无级: 找不到 isValidWidth/1")
            return 0
        }
        return if (m.hookExecutable(method, XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                if ((result as? Boolean) == true || !active()) {
                    result
                } else {
                    val width = chain.getArg(0) as? Int
                    val mini = SoScSupport.intField(chain.thisObject, "mMiniMoveWidth")
                    val max = SoScSupport.intField(chain.thisObject, "mMaxMoveWidth")
                    if (width != null && mini != null && max != null &&
                        width >= mini && (width <= max - mini || width == max)
                    ) {
                        true
                    } else {
                        result
                    }
                }
            })) 1 else 0
    }
}
