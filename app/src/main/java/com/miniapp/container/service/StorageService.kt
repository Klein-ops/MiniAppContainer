package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.file.FileService
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
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager,
    private val fileService: FileService
) : BaseService(activity, appInfo, permissionManager) {

    private val config = WebdavConfig(activity)

    /** 小程序根路径段：/<ROOT>/data/<uid>_<uname>。 */
    private fun appRoot(): List<String> =
        listOf(WebdavConfig.ROOT_FOLDER, "data", appInfo.appKey)

    /** 把小程序给的相对路径解析为完整段（禁止 .. / 绝对路径逃逸）。 */
    private fun resolve(rel: String): List<String> {
        val parts = rel.trim().trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) {
            throw SecurityException("illegal path (segment '.' or '..' is forbidden): $rel")
        }
        return appRoot() + parts
    }

    private suspend fun ensurePermission() = requirePermission(PermissionScope.STORAGE)

    private fun ensureConfigured() {
        if (!config.configured) {
            throw java.io.IOException("storage not configured")
        }
    }

    /** 上传数据：把 base64 写入指定相对路径。返回 "true"。 */
    suspend fun upload(rel: String, base64: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path is required")
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
        if (rel.isBlank()) throw IllegalArgumentException("path is required")
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val tmp = File(activity.cacheDir, "storage_${System.currentTimeMillis()}.tmp")
        try {
            val ok = WebdavClient(config).get(segs, tmp)
            if (!ok) throw java.io.FileNotFoundException("file not found: $rel")
            JSONObject.quote(IoUtil.toBase64(tmp.readBytes()))
        } finally {
            tmp.delete()
        }
    }

    /**
     * 流式上传：把沙箱内文件 [sandboxSrcPath] 直传到 WebDAV 相对路径 [rel]，**不经 base64**（KPI：流式代替）。
     * - 源在沙箱内（PathGuard 解析，app/data/tmp 可读）
     * - [WebdavClient.put] 本就是 OkHttp 文件流式 PUT（asRequestBody），不整读进内存
     * - 返回 "true"
     */
    suspend fun uploadFile(rel: String, sandboxSrcPath: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path is required")
        if (sandboxSrcPath.isBlank()) throw IllegalArgumentException("sandboxSrcPath required")
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val src = fileService.resolveReadable(sandboxSrcPath)
        if (!src.isFile) throw java.io.FileNotFoundException("sandbox file not found: $sandboxSrcPath")
        val client = WebdavClient(config)
        client.mkdirs(segs.dropLast(1))
        client.put(segs, src).toString()
    }

    /**
     * 流式下载：把 WebDAV 相对路径 [rel] 直落到沙箱文件 [sandboxDestPath]，**不经 base64**（KPI：流式代替）。
     * - 目标走 [FileService.resolveWritable]（仅 data/tmp 白名单）
     * - [WebdavClient.get] 本就是 OkHttp 流式 GET 直落文件（byteStream.copyTo），不整读进内存
     * - 返回 "true"；目标文件不存在抛 FileNotFoundException
     */
    suspend fun downloadTo(rel: String, sandboxDestPath: String): String = withContext(Dispatchers.IO) {
        if (rel.isBlank()) throw IllegalArgumentException("path is required")
        if (sandboxDestPath.isBlank()) throw IllegalArgumentException("sandboxDestPath required")
        ensurePermission()
        ensureConfigured()
        val segs = resolve(rel)
        val dest = fileService.resolveWritable(sandboxDestPath)
        dest.parentFile?.mkdirs()
        val ok = WebdavClient(config).get(segs, dest)
        if (!ok) throw java.io.FileNotFoundException("file not found: $rel")
        "true"
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
        if (rel.isBlank()) throw IllegalArgumentException("path is required")
        ensurePermission()
        ensureConfigured()
        WebdavClient(config).delete(resolve(rel)).toString()
    }
}
