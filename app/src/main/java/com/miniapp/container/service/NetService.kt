package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.file.FileService
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网络服务（单一职责）：HTTP 请求，需 `net` 权限。
 *
 * 使用 OkHttp 而非 HttpURLConnection：后者的方法白名单只含
 * GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，发送 PATCH 等自定义方法会失败。
 */
class NetService(
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager,
    private val fileService: FileService
) : BaseService(activity, appInfo, permissionManager) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 兼容旧接口：GET 请求。 */
    suspend fun get(url: String): String =
        request(JSONObject().put("url", url).put("method", "GET"))

    /** 通用 HTTP 请求（GET/POST/PUT/DELETE/PATCH 等）。 */
    suspend fun request(p: JSONObject): String = withContext(Dispatchers.IO) {
        requirePermission(PermissionScope.NET)

        val url = p.optStringOr("url")
        if (url.isBlank()) throw IllegalArgumentException("url required")
        val method = p.optStringOr("method", "GET").uppercase()
        val bodyStr = p.optStringOr("body")

        // 不主动设置 Content-Type（与文档一致：宿主不自动添加，需要时由调用方显式指定）
        val body = if (bodyStr.isNotEmpty() && method != "GET" && method != "HEAD") {
            bodyStr.toRequestBody(null)
        } else null

        val builder = Request.Builder().url(url).method(method, body)
        p.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) builder.header(k, h.optString(k))
        }

        client.newCall(builder.build()).execute().use { resp ->
            JSONObject()
                .put("status", resp.code)
                .put("body", resp.body?.string() ?: "")
                .toString()
        }
    }

    /**
     * 下载并**直落沙箱文件**（data/tmp）：文件不经 JS 内存，Native 侧流式写入目标文件。
     * 返回 JSON：{ status, size(bytes), path }。
     */
    suspend fun download(p: JSONObject): String = withContext(Dispatchers.IO) {
        requirePermission(PermissionScope.NET)
        val url = p.optStringOr("url")
        if (url.isBlank()) throw IllegalArgumentException("url required")
        val destPath = p.optStringOr("destPath")
        if (destPath.isBlank()) throw IllegalArgumentException("destPath required")

        val builder = Request.Builder().url(url).get()
        p.optJSONObject("headers")?.let { h -> for (k in h.keys()) builder.header(k, h.optString(k)) }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@withContext JSONObject()
                    .put("status", resp.code)
                    .put("size", 0)
                    .put("path", destPath)
                    .toString()
            }
            val body = resp.body ?: return@withContext JSONObject()
                .put("status", resp.code).put("size", 0).put("path", destPath).toString()
            val written = fileService.openWritableStream(destPath).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
            JSONObject()
                .put("status", resp.code)
                .put("size", written)
                .put("path", destPath)
                .toString()
        }
    }

    /**
     * 上传**沙箱内文件**到 URL：文件不经 JS 内存，Native 侧从源文件流式发包体。
     * 返回 JSON：{ status, body }。
     */
    suspend fun upload(p: JSONObject): String = withContext(Dispatchers.IO) {
        requirePermission(PermissionScope.NET)
        val url = p.optStringOr("url")
        if (url.isBlank()) throw IllegalArgumentException("url required")
        val srcPath = p.optStringOr("srcPath")
        if (srcPath.isBlank()) throw IllegalArgumentException("srcPath required")

        val src = fileService.resolveReadable(srcPath)
        if (!src.isFile) throw java.io.FileNotFoundException("file not found: $srcPath")

        val method = p.optStringOr("method", "POST").uppercase()
        val reqBody = src.asRequestBody("application/octet-stream".toMediaTypeOrNull())

        val builder = Request.Builder().url(url).method(method, reqBody)
        p.optJSONObject("headers")?.let { h -> for (k in h.keys()) builder.header(k, h.optString(k)) }

        client.newCall(builder.build()).execute().use { resp ->
            JSONObject()
                .put("status", resp.code)
                .put("body", resp.body?.string() ?: "")
                .toString()
        }
    }
}
