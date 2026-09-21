package com.abel.os4freeformx

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块 App 侧的配置读写。
 *
 * LSPosed 的 remote preferences 从模块 App 的「设备加密（DE）」存储读取，
 * 普通 `getSharedPreferences` 只写 CE —— 只写 CE 时被 hook 的进程永远读到默认值
 * （2026-09-19 实测）。所以这里 CE + DE 双写。
 */
object AppPrefs {

    fun putString(ctx: Context, key: String, value: String) {
        ce(ctx).edit().putString(key, value).apply()
        de(ctx)?.edit()?.putString(key, value)?.apply()
    }


    private fun ce(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)

    private fun de(ctx: Context): SharedPreferences? = runCatching {
        ctx.createDeviceProtectedStorageContext()
            .getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
    }.getOrNull()

    fun putBoolean(ctx: Context, key: String, value: Boolean) {
        ce(ctx).edit().putBoolean(key, value).apply()
        de(ctx)?.edit()?.putBoolean(key, value)?.apply()
    }

    fun putInt(ctx: Context, key: String, value: Int) {
        ce(ctx).edit().putInt(key, value).apply()
        de(ctx)?.edit()?.putInt(key, value)?.apply()
    }

    fun getBoolean(ctx: Context, key: String, def: Boolean): Boolean =
        runCatching { ce(ctx).getBoolean(key, def) }.getOrDefault(def)

    fun getInt(ctx: Context, key: String, def: Int): Int =
        runCatching { ce(ctx).getInt(key, def) }.getOrDefault(def)
}
