package com.abel.os4freeformx

import android.content.ContentResolver
import android.provider.Settings

/**
 * 配置 / 窗口尺寸的统一存储：**Settings.System**（系统设置库）。
 *
 * 为什么不用模块 App 的 ContentProvider：系统按需创建 Provider 所在的进程，
 * 每次读都会把已经被杀掉的模块 App 拉起来 —— 「墓碑统计」里的后台唤醒就是这么来的。
 *
 * 为什么不用 LSPosed 的 remote preferences：LibXposed 返回的是**进程内快照**对象
 * （2026-11-24 本机实测：多次 getRemotePreferences 拿到同一个 hashCode，
 * 文件里新增/改动的键永远读不到），App 改完设置后 hook 侧不会更新。
 *
 * Settings.System 由 system_server 持有：读取是本地缓存、写入后各进程实时一致，
 * 且完全与模块 App 进程无关（hook 侧、App 侧都直接读写系统设置）。
 *
 * 键统一加 `ffx_` 前缀，避免与系统设置里的其它项冲突。
 * boolean 以 int（0/1）存储。
 */
object Store {
    private const val PREFIX = "ffx_"

    /** 旧版 SharedPreferences 已导入 Settings.System 的标记。 */
    const val KEY_MIGRATED = "migrated"

    /** 窗口尺寸的键索引（逗号分隔的原始 key，形如 `pkg|1672x2364`）。 */
    const val KEY_BOUNDS_INDEX = "bounds_index"

    fun name(key: String): String = PREFIX + key

    /*
     * 所有读写都套 runCatching：这些调用可能发生在 hook 回调里，
     * 而 SystemUI 早期拿到的 Context 可能是包名 `android` 的系统上下文 ——
     * SettingsProvider 会以 "calling package android does not match caller's uid"
     * 抛 SecurityException。异常一旦冒到被 hook 的系统方法，SystemUI 会直接崩溃，
     * 连崩几次 LSPosed 就进安全模式（2026-11-24 真机踩过）。
     */

    fun has(cr: ContentResolver, key: String): Boolean =
        runCatching { Settings.System.getString(cr, name(key)) != null }.getOrDefault(false)

    fun getBool(cr: ContentResolver, key: String, def: Boolean): Boolean =
        runCatching { Settings.System.getInt(cr, name(key), if (def) 1 else 0) != 0 }.getOrDefault(def)

    fun putBool(cr: ContentResolver, key: String, value: Boolean): Boolean =
        runCatching { Settings.System.putInt(cr, name(key), if (value) 1 else 0) }.getOrDefault(false)

    fun getString(cr: ContentResolver, key: String, def: String?): String? =
        runCatching { Settings.System.getString(cr, name(key)) }.getOrNull() ?: def

    fun putString(cr: ContentResolver, key: String, value: String?): Boolean =
        runCatching { Settings.System.putString(cr, name(key), value) }.getOrDefault(false)

    fun getFloat(cr: ContentResolver, key: String, def: Float): Float =
        runCatching { Settings.System.getFloat(cr, name(key), def) }.getOrDefault(def)

    fun putFloat(cr: ContentResolver, key: String, value: Float): Boolean =
        runCatching { Settings.System.putFloat(cr, name(key), value) }.getOrDefault(false)

    fun remove(cr: ContentResolver, key: String): Boolean =
        runCatching { Settings.System.putString(cr, name(key), null) }.getOrDefault(false)
}
