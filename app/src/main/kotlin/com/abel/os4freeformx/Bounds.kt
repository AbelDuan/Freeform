package com.abel.os4freeformx

import android.content.Context
import android.graphics.Rect
import android.os.Bundle

/**
 * 分应用 + 屏幕形态（折叠态/横竖屏）小窗 bounds 记忆。
 *
 * key = `包名|屏幕宽x高`。屏幕宽高天然区分内屏/外屏（本机内屏 1672x2364、外屏 1168x1712）
 * 与横竖屏，不需要读取隐藏 API，system_server / systemui / 模块 App 三个进程算法一致。
 *
 * 存储：进程内存缓存 + 经 [StoreProvider] 落到模块 App 私有 prefs（跨进程共享）。
 */
object Bounds {
    private val cache = HashMap<String, Rect>()
    @Volatile private var loaded = false

    /**
     * 屏幕形态标识：**短边x长边**（排序后，与旋转无关）。
     *
     * 之前直接用 `ctx.resources.displayMetrics`，结果 system_server 与 SystemUI 两个进程
     * 拿到的显示不一致（实测 system_server 报 1168x1712「外屏」，而机器处于展开态），
     * 导致记忆写在 A 键、恢复去 B 键找，永远不生效（2026-09-19 用户实测）。
     * 现在统一走 DisplayManager 的默认显示 + 当前旋转的真实尺寸。
     */
    fun screenKey(ctx: Context?): String = screenKeyFor(ctx, -1)

    /**
     * 屏幕键。折叠屏内外屏是**两个 display**，所以必须按 display 取 ——
     * 原来固定读 display 0，导致外屏小窗的位置/尺寸也写进内屏的 key（用户反馈的内外屏不分开）。
     * displayId < 0 时：优先用 context 自己的 display，再退回 display 0。
     */
    fun screenKeyFor(ctx: Context?, displayId: Int): String {
        val id = if (displayId >= 0) displayId else runCatching { ctx?.display?.displayId }.getOrNull() ?: 0
        val (w, h) = displaySize(ctx, id)
        if (w <= 0 || h <= 0) return "0x0"
        // ★ 用户要求（2026-10-01）：**横竖屏完全一致** —— 形态/比例/尺寸/方向都用同一个值，
        // 记忆只按【内屏/外屏】区分（display），不再区分方向。
        //   （曾按 @P/@L 分槽，导致旋转时要"挑槽/同步对侧槽"，多次踩坑：取错槽、
        //    对侧为空时套用了另一方向的尺寸 ⇒ 第一次旋转变大 ✗。去掉这个维度后这些分支全部消失。）
        return "${minOf(w, h)}x${maxOf(w, h)}"
    }

    private fun displaySize(ctx: Context?, displayId: Int = 0): Pair<Int, Int> {
        if (ctx != null) {
            runCatching {
                val dmgr = ctx.getSystemService(Context.DISPLAY_SERVICE)
                val d = dmgr?.javaClass?.getMethod("getDisplay", Integer.TYPE)?.invoke(dmgr, displayId)
                if (d != null) {
                    val p = android.graphics.Point()
                    d.javaClass.getMethod("getRealSize", android.graphics.Point::class.java).invoke(d, p)
                    if (p.x > 0 && p.y > 0) return p.x to p.y
                }
            }
        }
        val dm = ctx?.resources?.displayMetrics
        return (dm?.widthPixels ?: 0) to (dm?.heightPixels ?: 0)
    }

    fun key(pkg: String, screen: String): String = "$pkg|$screen"

    /** 强制重新从 Provider 拉一遍（App 进程刷新列表用）。 */
    @Synchronized
    fun loadNow(ctx: Context) {
        loaded = false
        load(ctx)
    }

    /**
     * 内外屏状态（设置页展示 + 就是记忆 key 的依据）。
     * DeviceStateManager 在普通 App 进程里被隐藏 API 拦掉，所以按屏幕短边判断：
     * 本机内屏 1672x2364、外屏 1168x1712（实测）。
     */
    fun screenStateLabel(ctx: Context?): String {
        val dm = ctx?.resources?.displayMetrics ?: return "?"
        val short = minOf(dm.widthPixels, dm.heightPixels)
        return if (short >= 1500) "展开（内屏 ${dm.widthPixels}x${dm.heightPixels}）"
        else "折叠（外屏 ${dm.widthPixels}x${dm.heightPixels}）"
    }

    /** 折叠态名称，仅用于设置页展示（需要系统 API，App 进程通常读不到）。 */
    fun foldLabel(ctx: Context?): String {
        if (ctx == null) return "?"
        runCatching {
            val dsm = ctx.getSystemService("device_state") ?: return@runCatching
            val id = dsm.javaClass.getMethod("getCurrentState").invoke(dsm) as? Int ?: return@runCatching
            return when (id) {
                0 -> "CLOSED(外屏)"
                1 -> "TENT"
                2 -> "HALF"
                3 -> "OPENED(内屏)"
                4 -> "OPENED_REV"
                else -> "STATE$id"
            }
        }
        return "?"
    }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        runCatching {
            val b = ctx.contentResolver.call(
                android.net.Uri.parse("content://${Constants.AUTHORITY}"), "getAll", null, null
            )
            b?.keySet()?.forEach { k ->
                val raw = b.getString(k)
                parseRect(raw)?.let { cache[k] = it }
                raw?.substringAfter('@', "")?.toFloatOrNull()?.takeIf { it > 0f }?.let { scaleCache[k] = it }
            }
            // 旧格式键（@P/@L「方向槽」，已废弃）一次性清理：能迁的迁到新键，然后**一律删除**。
            // 必须按 prefs 的**原始键**遍历：空值旧键解析不出 Rect，走 cache 遍历永远清不掉 ✗
            // （真机存储里至今残留 `pkg|1672x2364@P=` / `@L=` 一串空值键，会让"读哪条"变得不确定）
            runCatching {
                val legacy = b?.keySet()?.filter { it.endsWith("@P") || it.endsWith("@L") } ?: emptyList()
                var migrated = 0
                legacy.forEach { old ->
                    val base = old.substringBeforeLast('@')
                    val rr = parseRect(b?.getString(old))
                    val sc = b?.getString(old)?.substringAfter('@', "")?.toFloatOrNull() ?: 0f
                    if (rr != null && !cache.containsKey(base)) {
                        cache[base] = rr
                        if (sc > 0f) scaleCache[base] = sc
                        write(ctx, base, rr, sc)
                        migrated++
                    }
                    cache.remove(old); scaleCache.remove(old)
                    write(ctx, old, null)
                }
                if (legacy.isNotEmpty()) Logx.always("旧键清理: 迁移 $migrated 条、删除 ${legacy.size} 条 @P/@L")
            }

            loaded = true
            Logx.always("bounds 载入 ${cache.size} 条")
        }.onFailure { Logx.e("bounds 载入失败", it) }
    }

    @Volatile private var lastLoad = 0L

    /**
     * 读记忆值。缓存 TTL 内直接用快照：systemui 写入新值后，system_server（负责打开时套用）
     * 必须能拿到新值，否则永远用开机时的那份快照。
     *
     * TTL 从 30s 降到 1.5s：菜单里刚按比例存下的尺寸要马上生效（重开小窗在 ~1.8s 后），
     * 30s 会让 system_server 拿旧值把方向/比例盖回去（真机现象：选横屏又被恢复成竖屏）。
     */
    @Synchronized
    fun get(ctx: Context, pkg: String, screen: String): Rect? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!loaded || now - lastLoad > 300) {
            loaded = false
            load(ctx)
            lastLoad = now
        }
        cache[key(pkg, screen)]?.let { return Rect(it) }
        // 兼容旧键格式（未排序的 宽x高，例如 2364x1672）
        val alt = screen.split('x').takeIf { it.size == 2 }?.let { "${it[1]}x${it[0]}" }
        return alt?.let { cache[key(pkg, it)] }?.let { Rect(it) }
    }

    /** 记忆里一起存的 freeformScale（真机：用户调的大小体现在 scale 上，只有 bounds 记不住尺寸）。 */
    private val scaleCache = HashMap<String, Float>()

    fun getScale(ctx: Context, pkg: String, screen: String): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!loaded || now - lastLoad > 300) {
            loaded = false
            load(ctx)
            lastLoad = now
        }
        return scaleCache[key(pkg, screen)] ?: 0f
    }

    /**
     * 等比缩放到可视区（**只缩放、不单独裁边** ⇒ 形状/比例不变）。
     *
     * ★ 2026-10-06 从上游 `d156b72` 取回：旋转/内外屏切换时可视区宽高会对调，
     *   记忆里的矩形必须等比缩进新可视区，比例才不会被破坏。当时删掉它改用
     *   「关闭→重开」后，旋转场景反而被 MIUI 的最终布局覆盖（用户复现：
     *   1:1 旋转后回到原始比例，但关闭再重开又是 1:1）。
     */
    fun clampKeepRatio(r: Rect, area: Rect): Rect {
        val w = r.width()
        val h = r.height()
        if (w <= 0 || h <= 0) return Rect(r)
        val areaW = maxOf(area.width(), 1)
        val areaH = maxOf(area.height(), 1)
        val scale = minOf(1f, minOf(areaW.toFloat() / w, areaH.toFloat() / h))
        val nw = maxOf((w * scale).toInt(), 1)
        val nh = maxOf((h * scale).toInt(), 1)
        val out = Rect(r.left, r.top, r.left + nw, r.top + nh)
        // 缩完再夹位置，保证完全落在可视区
        if (out.left < area.left) out.offsetTo(area.left, out.top)
        if (out.top < area.top) out.offsetTo(out.left, area.top)
        if (out.right > area.right) out.offsetTo(area.right - out.width(), out.top)
        if (out.bottom > area.bottom) out.offsetTo(out.left, area.bottom - out.height())
        return out
    }

    /**
     * 取该应用**任意一个屏幕**下的记忆（key 形如 `pkg|WxH`）。
     * 目标屏幕还没有记忆时兜底：把别的屏幕的尺寸等比缩到当前屏幕，
     * 让「内外屏切换 / 旋转」第一次遇到新几何也能保住形状，而不是退化成系统默认。
     * （同样从上游 `d156b72` 取回。）
     */
    fun getAny(ctx: Context, pkg: String): Rect? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!loaded || now - lastLoad > 300) {
            loaded = false
            load(ctx)
            lastLoad = now
        }
        synchronized(this) {
            for ((k, v) in cache) {
                if (k.startsWith("$pkg|")) return Rect(v)
            }
        }
        return null
    }

    /**
     * 合并写入（用户要求：尺寸与位置都要各自记住）：
     *   · 只变了位置（尺寸相同）⇒ 保留新位置、沿用旧尺寸 ✓
     *   · 只变了尺寸（左上角相同）⇒ 沿用旧位置、采用新尺寸 ✓
     *   · 两者都变 ⇒ 整体采用 ✓
     * 目的：修掉"整个矩形互相覆盖"导致"记住尺寸就丢位置、记住位置就丢尺寸"✗
     */
    fun mergePut(ctx: Context, pkg: String, screen: String, r: Rect, scale: Float = 0f) {
        val old = get(ctx, pkg, screen)
        val merged = if (old != null && old.width() == r.width() && old.height() == r.height()) {
            Rect(r.left, r.top, r.right, r.bottom)                       // 尺寸同 ⇒ 只更新位置 ✓
        } else if (old != null && old.left == r.left && old.top == r.top) {
            Rect(r.left, r.top, r.right, r.bottom)                       // 位置同 ⇒ 只更新尺寸 ✓
        } else r
        put(ctx, pkg, screen, merged, scale)
    }

    /** 落盘一条 `左,上,右,下[@scale]`；r=null 表示删除该键。所有写入走这里，不再各处手写 Bundle。 */
    private fun write(ctx: Context, k: String, r: Rect?, scale: Float = 0f) {
        val v = if (r == null) "" else
            "${r.left},${r.top},${r.right},${r.bottom}" + if (scale > 0f) "@$scale" else ""
        runCatching {
            val b = Bundle().apply { putString("k", k); putString("v", v) }
            ctx.contentResolver.call(android.net.Uri.parse("content://${Constants.AUTHORITY}"), "put", null, b)
        }.onFailure { Logx.e("bounds 写入失败($k)", it) }
    }

    /**
     * 写入一对 **(bounds, scale)** —— 2026-10-01 重构的核心不变量：两者同生同死。
     *
     * 两个真机根因（都在这几行里）：
     *  1) 传 `scale<=0`（老调用：拖动记录、heal、配置变更）会把值写成**裸 bounds**，
     *     跨进程 `load()` 后 `scaleCache` 没有这条 ⇒ `getScale()=0` ⇒ 恢复端静默跳过 ⇒ **尺寸永远记不住** ✗
     *     ⇒ 现在：`scale<=0` 一律**沿用已有 scale**，绝不写裸值。
     *  2) 拖角柄改尺寸时，任务的**真实 bounds 尺寸恒定、变的只有 freeformScale**
     *     （真机：真实恒为 502,494-1672,2364，scale 0.38/0.41/0.4065 在变）✗
     *     而原来 `changed` 只比 bounds ⇒ "只改尺寸"这一路**完全不落盘、连日志都没有** ✗
     *     ⇒ 现在：scale 变了也算变化。
     */
    fun put(ctx: Context, pkg: String, screen: String, r: Rect, scale: Float = 0f) {
        lastLoad = android.os.SystemClock.elapsedRealtime()
        val k = key(pkg, screen)
        var eff = 0f
        var changed = false
        synchronized(this) {
            eff = if (scale > 0f) scale else scaleCache[k] ?: 0f
            val old = cache[k]
            if (old == null || old != r || eff != (scaleCache[k] ?: 0f)) {
                cache[k] = Rect(r)
                if (eff > 0f) scaleCache[k] = eff
                changed = true
            }
        }
        if (!changed) return
        Logx.always("记住 $k = ${r.left},${r.top},${r.right},${r.bottom}${if (eff > 0f) "@$eff" else ""}")
        write(ctx, k, r, eff)
    }

    /** 遗忘某应用在某屏幕(含方向)下的记忆：写空串，读取侧视为"无记忆"⇒交回小米默认。 */
    fun forget(ctx: Context, pkg: String, screen: String) {
        val k = key(pkg, screen)
        synchronized(this) { cache.remove(k); scaleCache.remove(k) }
        Logx.always("遗忘 $k")
        write(ctx, k, null)
    }

    fun all(): Map<String, Rect> = synchronized(this) { HashMap(cache) }

    fun parseRect(s: String?): Rect? {
        if (s.isNullOrEmpty()) return null
        val p = s.substringBefore('@').split(',')
        if (p.size != 4) return null
        return runCatching {
            Rect(p[0].trim().toInt(), p[1].trim().toInt(), p[2].trim().toInt(), p[3].trim().toInt())
        }.getOrNull()?.takeIf { it.width() > 0 && it.height() > 0 }
    }

}
