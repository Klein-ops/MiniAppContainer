package com.miniapp.container.core

import android.content.Context
import android.content.SharedPreferences

/** 内部储存访问方式。 */
enum class StorageMode {
    /** 传统：MANAGE_EXTERNAL_STORAGE（Android 11+）/ 运行时权限（10 及以下）。 */
    SYSTEM,

    /** Shizuku：借 ADB 权限读写，Android 11+ 亦可访问 `sdcard/Android`。 */
    SHIZUKU;

    companion object {
        fun from(raw: String?): StorageMode =
            values().firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * 内部储存路径过滤模式（叠加在「应用私有目录硬禁」之上的用户可配置项）。
 *
 * - [NONE]：不额外过滤（仅硬底线生效）
 * - [BLACKLIST]：黑名单，命中的路径禁止访问
 * - [WHITELIST]：白名单，仅命中的路径允许访问
 */
enum class PathFilterMode {
    NONE,
    BLACKLIST,
    WHITELIST;

    companion object {
        fun from(raw: String?): PathFilterMode =
            values().firstOrNull { it.name == raw } ?: NONE
    }
}

/**
 * 内部储存访问方式的全局偏好（所有小程序统一生效）。
 *
 * 默认 [StorageMode.SYSTEM]：保持"直接申请系统存储权限"的老路。
 * 小程序的 `fs.*External` 接口行为不变，仅底层实现随本设置切换。
 */
class StorageAccessConfig(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences?
        get() = try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }

    var mode: StorageMode
        get() = StorageMode.from(prefs?.getString(KEY_MODE, null))
        set(value) {
            prefs?.edit()?.putString(KEY_MODE, value.name)?.apply()
        }

    /** 路径过滤模式（默认 NONE：仅硬底线私有目录禁）。 */
    var pathFilterMode: PathFilterMode
        get() = PathFilterMode.from(prefs?.getString(KEY_FILTER_MODE, null))
        set(value) {
            prefs?.edit()?.putString(KEY_FILTER_MODE, value.name)?.apply()
        }

    /** 黑/白名单路径前缀列表（canonical 比较在调用方做）。 */
    var pathList: List<String>
        get() = prefs?.getStringSet(KEY_PATH_LIST, emptySet())
            ?.toList()?.sorted() ?: emptyList()
        set(value) {
            prefs?.edit()?.putStringSet(KEY_PATH_LIST, value.toSet())?.apply()
        }

    /** 首次切 Shizuku 模式的风险告知是否已读过（避免重复弹窗）。 */
    var shizukuWarned: Boolean
        get() = prefs?.getBoolean(KEY_SHIZUKU_WARNED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SHIZUKU_WARNED, value)?.apply()
        }

    companion object {
        private const val PREFS = "storage_access"
        private const val KEY_MODE = "mode"
        private const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_PATH_LIST = "path_list"
        private const val KEY_SHIZUKU_WARNED = "shizuku_warned"
    }
}
