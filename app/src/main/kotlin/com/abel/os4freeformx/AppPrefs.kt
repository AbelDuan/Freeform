package com.abel.os4freeformx

import android.content.Context
import android.provider.Settings

/**
 * 模块 App 侧的配置读写。
 *
 * 2026-11-24 起改存 **Settings.System**（见 [Store]）：被 hook 的进程
 * （SystemUI / system_server）直接读系统设置，不必再通过 ContentProvider
 * 去访问模块 App，因此模块 App 被杀掉后也永远不会被「读配置」叫醒。
 *
 * 旧版的 SharedPreferences（CE + DE 两套）作为一次性迁移源保留：
 * [migrateIfNeeded] 在 App 首次启动、或 hook 侧首次读配置时（见 Cfg.reload）导入。
 */
object AppPrefs {

    fun putString(ctx: Context, key: String, value: String) {
        runCatching { Store.putString(ctx.contentResolver, key, value) }
    }

    fun putBoolean(ctx: Context, key: String, value: Boolean) {
        runCatching { Store.putBool(ctx.contentResolver, key, value) }
    }

    fun putInt(ctx: Context, key: String, value: Int) {
        runCatching { Settings.System.putInt(ctx.contentResolver, Store.name(key), value) }
    }

    fun putFloat(ctx: Context, key: String, value: Float) {
        runCatching { Store.putFloat(ctx.contentResolver, key, value) }
    }

    fun getString(ctx: Context, key: String): String? = runCatching {
        Store.getString(ctx.contentResolver, key, null)
    }.getOrNull()

    fun getBoolean(ctx: Context, key: String, def: Boolean): Boolean =
        runCatching { Store.getBool(ctx.contentResolver, key, def) }.getOrDefault(def)

    fun getInt(ctx: Context, key: String, def: Int): Int =
        runCatching { Settings.System.getInt(ctx.contentResolver, Store.name(key), def) }.getOrDefault(def)

    fun getFloat(ctx: Context, key: String, def: Float): Float =
        runCatching { Store.getFloat(ctx.contentResolver, key, def) }.getOrDefault(def)

    /**
     * 把旧版 SharedPreferences（CE + DE）里的配置一次性导入 Settings.System。
     * 幂等：用 [Store.KEY_MIGRATED] 标记，导入过就直接返回。
     *
     * 返回是否真的做了迁移（hook 侧据此决定要不要再走 Provider 兜底迁移）。
     */
    fun migrateIfNeeded(ctx: Context): Boolean {
        if (Store.has(ctx.contentResolver, Store.KEY_MIGRATED)) return false
        val merged = LinkedHashMap<String, Any?>()
        runCatching {
            ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
        }.getOrNull()?.all?.let { merged.putAll(it) }
        runCatching {
            ctx.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
        }.getOrNull()?.all?.let { merged.putAll(it) }
        merged.forEach { (k, v) ->
            when (v) {
                is Boolean -> Store.putBool(ctx.contentResolver, k, v)
                is String -> Store.putString(ctx.contentResolver, k, v)
                is Int -> Settings.System.putInt(ctx.contentResolver, Store.name(k), v)
                is Float -> Store.putFloat(ctx.contentResolver, k, v)
            }
        }
        Store.putBool(ctx.contentResolver, Store.KEY_MIGRATED, true)
        return true
    }

    /** App 是否已经拿到「修改系统设置」权限（设置页引导用）。 */
    fun canWrite(ctx: Context): Boolean = runCatching { Settings.System.canWrite(ctx) }.getOrDefault(false)
}
