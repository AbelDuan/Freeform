package com.abel.os4freeformx

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** 模块 App 侧的 bounds 存储（被 system_server / systemui 进程通过 ContentResolver.call 访问）。 */
class StoreProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun prefs(): android.content.SharedPreferences? =
        context?.getSharedPreferences(Constants.PREFS_BOUNDS, Context.MODE_PRIVATE)

    /** FLOAT_MODE 既可能是 putInt 写的 Int，也可能是 putCfg 写的 String，统一成 Int。 */
    private fun floatModeOf(c: android.content.SharedPreferences?): Int {
        if (c == null) return Constants.DEF_FLOAT_MODE
        return runCatching {
            c.getInt(Constants.K_FLOAT_MODE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?: c.getString(Constants.K_FLOAT_MODE, null)?.trim()?.toIntOrNull()
                ?: Constants.DEF_FLOAT_MODE
        }.getOrDefault(Constants.DEF_FLOAT_MODE)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val p = prefs() ?: return null
        return when (method) {
            "getAll" -> Bundle().apply {
                p.all.forEach { (k, v) -> if (v is String) putString(k, v) }
            }
            // 被 hook 的进程读配置（不依赖 LSPosed remote preferences 的快照）
            "getCfg" -> Bundle().apply {
                val c = context?.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
                putBoolean(Constants.K_ENABLE_LOG, c?.getBoolean(Constants.K_ENABLE_LOG, Constants.DEF_ENABLE_LOG) ?: Constants.DEF_ENABLE_LOG)
                putBoolean(Constants.K_IMMERSIVE, c?.getBoolean(Constants.K_IMMERSIVE, Constants.DEF_IMMERSIVE) ?: Constants.DEF_IMMERSIVE)
                putBoolean(Constants.K_REMEMBER_BOUNDS, c?.getBoolean(Constants.K_REMEMBER_BOUNDS, Constants.DEF_REMEMBER_BOUNDS) ?: Constants.DEF_REMEMBER_BOUNDS)
                putBoolean(Constants.K_REMEMBER_FOLD, c?.getBoolean(Constants.K_REMEMBER_FOLD, Constants.DEF_REMEMBER_FOLD) ?: Constants.DEF_REMEMBER_FOLD)
                putBoolean(Constants.K_RESIZE, c?.getBoolean(Constants.K_RESIZE, Constants.DEF_RESIZE) ?: Constants.DEF_RESIZE)
                putInt(Constants.K_DEFAULT_W, c?.getInt(Constants.K_DEFAULT_W, 0) ?: 0)
                putInt(Constants.K_DEFAULT_H, c?.getInt(Constants.K_DEFAULT_H, 0) ?: 0)
                putBoolean(Constants.K_GESTURE_HANDLE, c?.getBoolean(Constants.K_GESTURE_HANDLE, Constants.DEF_GESTURE_HANDLE) ?: Constants.DEF_GESTURE_HANDLE)
                putBoolean(Constants.K_GESTURE_HANDLE_FOLLOW, c?.getBoolean(Constants.K_GESTURE_HANDLE_FOLLOW, Constants.DEF_GESTURE_HANDLE_FOLLOW) ?: Constants.DEF_GESTURE_HANDLE_FOLLOW)
                putBoolean(Constants.K_GESTURE_HANDLE_TOUCH, c?.getBoolean(Constants.K_GESTURE_HANDLE_TOUCH, Constants.DEF_GESTURE_HANDLE_TOUCH) ?: Constants.DEF_GESTURE_HANDLE_TOUCH)
                putBoolean(Constants.K_GESTURE_HANDLE_IDLE, c?.getBoolean(Constants.K_GESTURE_HANDLE_IDLE, Constants.DEF_GESTURE_HANDLE_IDLE) ?: Constants.DEF_GESTURE_HANDLE_IDLE)
                putFloat(Constants.K_GESTURE_HANDLE_AREA, c?.getFloat(Constants.K_GESTURE_HANDLE_AREA, Constants.DEF_GESTURE_HANDLE_AREA) ?: Constants.DEF_GESTURE_HANDLE_AREA)
                putBoolean(Constants.K_GESTURE_HANDLE_FLOAT, c?.getBoolean(Constants.K_GESTURE_HANDLE_FLOAT, Constants.DEF_GESTURE_HANDLE_FLOAT) ?: Constants.DEF_GESTURE_HANDLE_FLOAT)
                // FLOAT_MODE 可能被 putCfg 以字符串写进来（adb 调试通道只传 String），两种类型都要能读
                putInt(Constants.K_FLOAT_MODE, floatModeOf(c))
                putString(Constants.K_FLOAT_PKGS, c?.getString(Constants.K_FLOAT_PKGS, Constants.DEF_FLOAT_PKGS) ?: Constants.DEF_FLOAT_PKGS)
                putBoolean(Constants.K_GESTURES, c?.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES) ?: Constants.DEF_GESTURES)
                putBoolean(Constants.K_CORNER_FREEFORM, c?.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM) ?: Constants.DEF_CORNER_FREEFORM)
            }
            "put" -> {
                val k = extras?.getString("k")
                val v = extras?.getString("v")
                if (k != null && v != null) p.edit().putString(k, v).apply()
                Bundle()
            }
            "remove" -> {
                arg?.let { p.edit().remove(it).apply() }
                Bundle()
            }
            // 配置键（PREFS_CFG）是 CE+DE 双写的，remove 只清 BOUNDS 清不掉它
            "clearCfgKey" -> {
                val k = extras?.getString("k") ?: arg
                if (k != null) {
                    context?.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)?.edit()?.remove(k)?.apply()
                    runCatching {
                        context?.createDeviceProtectedStorageContext()
                            ?.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)?.edit()?.remove(k)?.apply()
                    }
                }
                Bundle()
            }
            // 写入配置键（PREFS_CFG，CE+DE 双写）—— SystemUI 侧的测试广播用它把命令投递进来
            "putCfg" -> {
                val k = extras?.getString("k") ?: arg
                val v = extras?.getString("v")
                if (k != null && v != null) {
                    val c = context?.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
                    c?.edit()?.putString(k, v)?.apply()
                    runCatching {
                        context?.createDeviceProtectedStorageContext()
                            ?.getSharedPreferences(Constants.PREFS_CFG, Context.MODE_PRIVATE)
                            ?.edit()?.putString(k, v)?.apply()
                    }
                }
                Bundle()
            }
            "clear" -> {
                p.edit().clear().apply()
                Bundle()
            }
            else -> null
        }
    }

    override fun query(u: Uri, proj: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
