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

    companion object {
        private const val PREFS = "storage_access"
        private const val KEY_MODE = "mode"
    }
}
