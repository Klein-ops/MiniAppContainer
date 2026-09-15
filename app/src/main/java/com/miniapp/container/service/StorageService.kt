package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.netdisk.WebdavClient
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 网络存储服务（小程序 API，需 `storage` 权限）。
 *
 * 小程序在 WebDAV 上有独立沙箱：`/<ROOT>/data/<uid>_<uname>/<小程序相对路径>`。
 * 小程序**只能指定相对路径**，根路径由宿主强制拼装，且禁止 `..` 逃逸，因此
 * 各小程序之间无法互相访问。
 *
 * 失败约定：
 * - 用户未授权 → 抛 `SecurityException("permission denied: storage")`
 * - 权限已获但未配置 WebDAV → 抛 `IOException("storage not configured")`
 */
class StorageService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    private val config = WebdavConfig(activity)

    /** 小程序根路径段：/<ROOT>/data/<uid>_<uname>。 */
    private fun appRoot(): List<String> =
        listOf(WebdavConfig.ROOT_FOLDER, "data", appInfo.appKey)

    /** 把小程序给的相对路径解析为完整段（禁止 .. / 绝对路径逃逸）。 */
    private fun resolve(rel: String): List<String> {
        val parts = rel.trim().trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) {
            throw SecurityException("路径非法（禁止 . 或 ..）: $rel")
        }
        return appRoot() + parts
    }

    private suspend fun ensurePermission() {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.STORAGE
        )
        if (!granted) throw SecurityException("permission denied: storage")
    }

    private fun ensureConfigured() {
        if (!config.configured) {
            throw java.io.IOException("storage not configured")
        }
    }

    /** 上传数据：把 base64 写入指定相对路径。返回 "true"。 */
    suspend fun upload(rel: String, base64: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path 不能为空")
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val client = WebdavClient(config)
        client.mkdirs(segs.dropLast(1))
        val tmp = File(activity.cacheDir, "storage_${System.currentTimeMillis()}.tmp")
        try {
            tmp.writeBytes(IoUtil.fromBase64(base64))
            client.put(segs, tmp).toString()
        } finally {
            tmp.delete()
        }
    }

    /** 下载数据：读取指定相对路径，返回 base64。 */
    suspend fun download(rel: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path 不能为空")
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val tmp = File(activity.cacheDir, "storage_${System.currentTimeMillis()}.tmp")
        try {
            val ok = WebdavClient(config).get(segs, tmp)
            if (!ok) throw java.io.FileNotFoundException("文件不存在: $rel")
            JSONObject.quote(IoUtil.toBase64(tmp.readBytes()))
        } finally {
            tmp.delete()
        }
    }

    /** 列表扫描：返回 `[{ name, isDir }]`。rel 为空表示小程序根目录。 */
    suspend fun list(rel: String): String = withContext(Dispatchers.IO) {
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val client = WebdavClient(config)
        client.mkdirs(segs)   // 目录不存在时创建，保证 list 可用
        val arr = JSONArray()
        client.listEntries(segs).sortedBy { it.name }.forEach {
            arr.put(JSONObject().put("name", it.name).put("isDir", it.isDir))
        }
        arr.toString()
    }

    /** 删除文件/目录。返回 "true"/"false"。 */
    suspend fun delete(rel: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path 不能为空")
        ensurePermission()
        ensureConfigured()
        WebdavClient(config).delete(resolve(rel)).toString()
    }
}
