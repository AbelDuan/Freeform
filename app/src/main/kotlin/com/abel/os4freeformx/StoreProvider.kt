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
                putBoolean(Constants.K_GESTURES, c?.getBoolean(Constants.K_GESTURES, Constants.DEF_GESTURES) ?: Constants.DEF_GESTURES)
                putBoolean(Constants.K_CORNER_FREEFORM, c?.getBoolean(Constants.K_CORNER_FREEFORM, Constants.DEF_CORNER_FREEFORM) ?: Constants.DEF_CORNER_FREEFORM)
                putBoolean(Constants.K_FOUR_FINGER_SPLIT, c?.getBoolean(Constants.K_FOUR_FINGER_SPLIT, Constants.DEF_FOUR_FINGER_SPLIT) ?: Constants.DEF_FOUR_FINGER_SPLIT)
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
