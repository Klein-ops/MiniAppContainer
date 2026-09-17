package com.miniapp.container.netdisk

import android.content.Context
import android.content.SharedPreferences

/**
 * WebDAV 连接配置（持久化到 SharedPreferences `webdav_config`）。
 *
 * **注意**：在隔离进程（`dex.run` 的执行进程）或 Direct Boot 未解锁阶段，
 * credential encrypted storage 不可访问，`getSharedPreferences` 会抛
 * `IllegalStateException`。因此这里**懒加载 + 静默兜底**：
 * 不可访问时读返回默认值、写忽略，不抛异常、不崩溃。
 *
 * [ROOT_FOLDER] 为蜗壳在 WebDAV 上的根文件夹（英文名，避免中文路径在部分服务器上的兼容问题）。
 */
class WebdavConfig(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        try {
            appContext.getSharedPreferences("webdav_config", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            // 隔离进程 / 未解锁：无 credential storage
            null
        }
    }

    var url: String
        get() = prefs?.getString(KEY_URL, "").orEmpty()
        set(v) = prefs?.edit()?.putString(KEY_URL, v.trim())?.apply() ?: Unit

    var user: String
        get() = prefs?.getString(KEY_USER, "").orEmpty()
        set(v) = prefs?.edit()?.putString(KEY_USER, v.trim())?.apply() ?: Unit

    var pass: String
        get() = prefs?.getString(KEY_PASS, "").orEmpty()
        set(v) = prefs?.edit()?.putString(KEY_PASS, v)?.apply() ?: Unit

    /** 是否已配置（URL 非空即视为已配置）。 */
    val configured: Boolean get() = url.isNotBlank()

    /** zip 压缩等级 0-9（0=不压缩，9=最大，默认 6）。 */
    var compressionLevel: Int
        get() = (prefs?.getInt(KEY_LEVEL, 6) ?: 6).coerceIn(0, 9)
        set(v) = prefs?.edit()?.putInt(KEY_LEVEL, v.coerceIn(0, 9))?.apply() ?: Unit

    companion object {
        /** 蜗壳在 WebDAV 上的根文件夹名。 */
        const val ROOT_FOLDER = "MiniAppContainer"
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_LEVEL = "compression_level"
    }
}
