package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    permissionManager: PermissionManager
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
}
