package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 网络服务（单一职责）：HTTP 请求，需 `net` 权限。 */
class NetService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    /** 兼容旧接口：GET 请求。 */
    suspend fun get(url: String): String =
        request(JSONObject().put("url", url).put("method", "GET"))

    /** 通用 HTTP 请求（GET/POST/PUT/DELETE/PATCH 等）。 */
    suspend fun request(p: JSONObject): String = withContext(Dispatchers.IO) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.NET
        )
        if (!granted) throw SecurityException("permission denied: net")
        val url = p.optStringOr("url")
        val method = p.optStringOr("method", "GET").uppercase()
        val body = p.optStringOr("body")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = method
            instanceFollowRedirects = true
        }
        p.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) conn.setRequestProperty(k, h.optString(k))
        }
        if (body.isNotBlank() && method != "GET" && method != "HEAD") {
            conn.doOutput = true
            conn.outputStream?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            JSONObject().put("status", code).put("body", respBody).toString()
        } finally {
            conn.disconnect()
        }
    }
}
