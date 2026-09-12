package com.miniapp.container.bridge

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.miniapp.container.file.FileService
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.sys.SystemInfoService
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

private data class BridgeOut(val ok: Boolean, val payload: String)

/**
 * 前端与 Native 的唯一通信通道。
 *
 * 前端通过 `MiniAppNative.call(reqId, method, paramsJson)` 调用，
 * 结果异步通过 `window.__MiniAppBridge.__resolve(reqId, ok, payload)` 回传。
 *
 * - 沙箱内操作直接执行；
 * - 出沙箱操作（net / sys.openUrl / fs.readExternal）进入权限审批。
 */
class MiniAppBridge(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val sandboxRoot: java.io.File,
    private val fileService: FileService,
    private val permissionManager: PermissionManager,
    private val systemInfo: SystemInfoService,
    private val hostAppVersion: String
) {
    private val main = Handler(Looper.getMainLooper())
    private var webViewRef: WeakReference<WebView> = WeakReference(null)

    companion object { private const val TAG = "MiniAppBridge" }

    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    /** JS 唯一入口。 */
    @JavascriptInterface
    fun call(reqId: String, method: String, paramsJson: String) {
        activity.lifecycleScope.launch {
            val out = try {
                val params =
                    if (paramsJson.isBlank()) JSONObject() else JSONObject(paramsJson)
                BridgeOut(true, handle(method, params))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "bridge call '$method' failed", e)
                BridgeOut(false, e.message ?: e.javaClass.simpleName)
            }
            respond(reqId, out)
        }
    }

    private suspend fun handle(method: String, p: JSONObject): String = when (method) {
        "app.info" -> appInfoJson()
        "system.info" -> systemInfo.info(hostAppVersion)
        "ui.toast" -> {
            toast(p.optStringOr("message")); "true"
        }
        "fs.read" -> fileService.read(p.optStringOr("path"))
        "fs.readBytes" -> fileService.readBytes(p.optStringOr("path"))
        "fs.write" -> fileService.write(p.optStringOr("path"), p.optStringOr("content"))
        "fs.writeBytes" -> fileService.writeBytes(p.optStringOr("path"), p.optStringOr("base64"))
        "fs.list" -> fileService.list(p.optStringOr("path"))
        "fs.exists" -> fileService.exists(p.optStringOr("path"))
        "fs.stat" -> fileService.stat(p.optStringOr("path"))
        "fs.mkdir" -> fileService.mkdir(p.optStringOr("path"))
        "fs.remove" -> fileService.remove(p.optStringOr("path"))
        "fs.readExternal" -> readExternal(p.optStringOr("uri"))
        "net.httpGet" -> netHttpGet(p.optStringOr("url"))
        "sys.openUrl" -> openUrl(p.optStringOr("url"))
        "perm.request" -> {
            val scope = p.optStringOr("scope")
            val granted = permissionManager.ensurePermission(
                activity, appInfo.appKey, appInfo.permissions, scope
            )
            if (granted) "true" else "false"
        }
        else -> throw IllegalArgumentException("unknown method: $method")
    }

    private fun argsToStringArray(arr: JSONArray?): Array<String> {
        if (arr == null) return emptyArray()
        return Array(arr.length()) { arr.getString(it) }
    }

    private fun appInfoJson(): String {
        val permArr = JSONArray()
        appInfo.permissions.forEach { permArr.put(it) }
        return JSONObject()
            .put("uid", appInfo.uid)
            .put("uname", appInfo.uname)
            .put("version", appInfo.version)
            .put("entry", appInfo.entry)
            .put("permissions", permArr)
            .put("appKey", appInfo.appKey)
            .toString()
    }

    private fun toast(message: String) {
        main.post {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "toast failed", t)
            }
        }
    }

    private suspend fun openUrl(url: String): String {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.OPEN_URL
        )
        if (!granted) throw SecurityException("permission denied: sys.openUrl")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return "true"
    }

    private suspend fun netHttpGet(url: String): String = withContext(Dispatchers.IO) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.NET
        )
        if (!granted) throw SecurityException("permission denied: net")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            JSONObject().put("status", code).put("body", body).toString()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun readExternal(uri: String): String = withContext(Dispatchers.IO) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.FS_EXTERNAL
        )
        if (!granted) throw SecurityException("permission denied: fs.external")
        val parsed = Uri.parse(uri)
        val bytes = activity.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: throw java.io.IOException("无法读取: $uri")
        JSONObject.quote(com.miniapp.container.util.IoUtil.toBase64(bytes))
    }

    private fun respond(reqId: String, out: BridgeOut) {
        val js = if (out.ok) {
            "window.__MiniAppBridge.__resolve(${jsString(reqId)},true,${out.payload})"
        } else {
            "window.__MiniAppBridge.__resolve(${jsString(reqId)},false,${jsString(out.payload)})"
        }
        main.post {
            try {
                val w = webViewRef.get()
                if (w != null) {
                    w.evaluateJavascript(js, null)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "respond failed", t)
            }
        }
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }
}
