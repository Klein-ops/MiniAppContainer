package com.miniapp.container.netdisk

import android.content.Context

/**
 * WebDAV 连接配置（持久化到 SharedPreferences `webdav_config`）。
 *
 * [ROOT_FOLDER] 为蜗壳在 WebDAV 上的根文件夹（英文名，避免中文路径在部分服务器上的兼容问题）。
 */
class WebdavConfig(context: Context) {

    private val p = context.applicationContext
        .getSharedPreferences("webdav_config", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString(KEY_URL, "").orEmpty()
        set(v) = p.edit().putString(KEY_URL, v.trim()).apply()

    var user: String
        get() = p.getString(KEY_USER, "").orEmpty()
        set(v) = p.edit().putString(KEY_USER, v.trim()).apply()

    var pass: String
        get() = p.getString(KEY_PASS, "").orEmpty()
        set(v) = p.edit().putString(KEY_PASS, v).apply()

    /** 是否已配置（URL 非空即视为已配置）。 */
    val configured: Boolean get() = url.isNotBlank()

    /** zip 压缩等级 0-9（0=不压缩，9=最大，默认 6）。 */
    var compressionLevel: Int
        get() = p.getInt(KEY_LEVEL, 6).coerceIn(0, 9)
        set(v) = p.edit().putInt(KEY_LEVEL, v.coerceIn(0, 9)).apply()

    companion object {
        /** 蜗壳在 WebDAV 上的根文件夹名。 */
        const val ROOT_FOLDER = "MiniAppContainer"
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_LEVEL = "compression_level"
    }
}
