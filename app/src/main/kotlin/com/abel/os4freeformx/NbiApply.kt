package com.abel.os4freeformx

import android.content.Context

/**
 * 小白条悬浮的落地（需求：白名单应用隐藏导航栏背景条）。
 *
 * 有效路径 = 写 HyperOS 自带的沉浸导航名单 + 让 system_server 重读：
 *   ① 写 /data/system/cloudFeature_navigation_bar_immersive_rules_list.json
 *   ② `cmd miui_navigation_bar_immersive update`   ← 关键，缺了它文件写了也不生效
 *   ③ 目标应用重启后生效（NBI 服务自己的要求）
 * 全程不需要重启框架。曾用的 SystemUI hook 方案已删除（实测对底栏底色零效果）。
 */
object NbiApply {

    /** 上一次真正写进系统名单的包名（逗号分隔）——只回滚我们自己写的那部分。 */
    const val K_APPLIED = "nbi_applied"

    /** 当次要写的"包名 → 规则体"（隐藏优先于取色，由 Cfg.desiredRules 保证）。 */
    private fun desiredNow(ctx: Context): Map<String, String> = Cfg.desiredRules()

    /** 当次要写的目标集合（供调用方记录"已写入"）。 */
    fun targetsOf(ctx: Context): Set<String> = runCatching { desiredNow(ctx).keys }.getOrDefault(emptySet())

    /** 同步执行（调用方自己保证不在主线程）。 */
    fun applyNow(ctx: Context): Nbi.Result {
        val desired = desiredNow(ctx)
        val applied = Nbi.parseSet(AppPrefs.getString(ctx, K_APPLIED))
        val r = Nbi.apply(desired, applied)
        if (r.ok) {
            AppPrefs.putString(ctx, K_APPLIED, Nbi.join(desired.keys))
            // NBI 要求"重启目标应用才生效" —— 模块有 root，直接代劳（这就是"点应用再生效"）
            Nbi.restartApps((desired.keys + applied).toList())
        }
        return r
    }

    /** 后台执行（`su` 会阻塞，绝不放在主线程）。 */
    fun applyAsync(ctx: Context, onDone: ((Nbi.Result) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val r = runCatching { applyNow(app) }.getOrElse { Nbi.Result(false, it.toString()) }
            onDone?.invoke(r)
        }.apply { isDaemon = true; name = "os4ffx-nbi" }.start()
    }

}
