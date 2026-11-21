package com.abel.os4freeformx

import android.content.Context
import android.net.Uri

/**
 * UI 进程（模块设置页 / 其它进程）→ system_server 的 [TouchBlocker] 转发入口。
 *
 * 走模块自己的 ContentProvider（`content://com.abel.os4freeformx.store`）的
 * `touchblock` 方法，参数是遮蔽时长（毫秒）。
 *
 * ⚠️ 仅供折叠流程调试用。分屏转场**不要**走这里（会让用户点了没反应）。
 */
object TouchBlockerBridge {

    private val URI = Uri.parse("content://com.abel.os4freeformx.store")

    fun request(durationMs: Long) {
        val ctx: Context = AppCtx.get() ?: return
        runCatching {
            ctx.contentResolver.call(URI, "touchblock", durationMs.toString(), null)
        }.onFailure { Logx.e("触摸屏蔽: 转发失败", it) }
    }
}
