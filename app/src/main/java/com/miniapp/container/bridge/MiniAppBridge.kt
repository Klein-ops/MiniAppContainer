package com.miniapp.container.bridge

import com.miniapp.container.R

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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
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
            val t0 = System.currentTimeMillis()
            val out = try {
                val params =
                    if (paramsJson.isBlank()) JSONObject() else JSONObject(paramsJson)
                BridgeOut(true, handle(method, params))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "bridge call '$method' failed", e)
                BridgeOut(false, e.message ?: e.javaClass.simpleName)
            }
            if (com.miniapp.container.debug.DebugBus.enabled) {
                val ms = System.currentTimeMillis() - t0
                val result = if (out.ok) "← 返回: ${out.payload}" else "✗ 错误: ${out.payload}"
                com.miniapp.container.debug.DebugBus.log(
                    "→ $method\n参数: $paramsJson\n$result\n耗时: ${ms}ms"
                )
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
        "fs.importFile" -> importFile(p.optStringOr("destPath"))
        "fs.exportFile" -> exportFile(p.optStringOr("path"))
        "fs.readExternalFile" -> readExternalFile(p.optStringOr("path"))
        "fs.writeExternalFile" -> writeExternalFile(p.optStringOr("path"), p.optStringOr("base64"))
        "fs.listExternal" -> listExternal(p.optStringOr("dir"))
        "fs.existsExternal" -> existsExternal(p.optStringOr("path"))
        "fs.statExternal" -> statExternal(p.optStringOr("path"))
        "fs.mkdirExternal" -> mkdirExternal(p.optStringOr("dir"))
        "fs.removeExternal" -> removeExternal(p.optStringOr("path"))
        "fs.renameExternal" -> renameExternal(p.optStringOr("from"), p.optStringOr("to"))
        "net.httpGet" -> netHttpGet(p.optStringOr("url"))
        "net.httpRequest" -> httpRequest(p)
        "cb.read" -> readClipboard()
        "cb.write" -> writeClipboard(p.optStringOr("text"))
        "notify.show" -> notifyShow(p.optStringOr("title"), p.optStringOr("body"))
        "notify.cancel" -> notifyCancel()
        "dex.run" -> dexRun(p)
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

    /** 兼容旧接口：GET 请求复用 httpRequest。 */
    private suspend fun netHttpGet(url: String): String =
        httpRequest(JSONObject().put("url", url).put("method", "GET"))

    /** 通用 HTTP 请求（GET/POST/PUT/DELETE/PATCH 等）。 */
    private suspend fun httpRequest(p: org.json.JSONObject): String = withContext(Dispatchers.IO) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.NET
        )
        if (!granted) throw SecurityException("permission denied: net")
        val url = p.optStringOr("url")
        val method = p.optStringOr("method", "GET").uppercase()
        val body = p.optStringOr("body")
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = method
            instanceFollowRedirects = true
        }
        // 自定义请求头
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

    /** 读取剪贴板（需 clipboard 权限）。 */
    private suspend fun readClipboard(): String = withContext(Dispatchers.Main) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.CLIPBOARD
        )
        if (!granted) throw SecurityException("permission denied: clipboard")
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        JSONObject.quote(text)
    }

    /** 写入剪贴板（需 clipboard 权限）。 */
    private suspend fun writeClipboard(text: String): String = withContext(Dispatchers.Main) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.CLIPBOARD
        )
        if (!granted) throw SecurityException("permission denied: clipboard")
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("miniapp", text))
        "true"
    }

    /** 确保通知权限（蜗壳审批 + Android 13+ 运行时权限）。 */
    private suspend fun ensureNotificationPermission() {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.NOTIFICATION
        )
        if (!granted) throw SecurityException("permission denied: notification")
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val result = suspendCancellableCoroutine<Map<String, Boolean>> { cont ->
                activity.requestRuntimePerms(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
            if (!result.values.all { it }) throw SecurityException("通知权限被拒绝")
        }
    }

    /** 发送状态栏通知（title 标注来源小程序，需 notification 权限）。 */
    private suspend fun notifyShow(title: String, body: String): String = withContext(Dispatchers.Main) {
        ensureNotificationPermission()
        val nm = activity.getSystemService(NotificationManager::class.java)
        val label = appInfo.displayName.ifBlank { appInfo.uname }
        val channelId = "miniapp_${appInfo.appKey}"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(activity, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("[$label] $title")
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(appInfo.appKey.hashCode(), notif)
        "true"
    }

    /** 取消该小程序的通知。 */
    private suspend fun notifyCancel(): String = withContext(Dispatchers.Main) {
        val nm = activity.getSystemService(NotificationManager::class.java)
        nm.cancel(appInfo.appKey.hashCode())
        "true"
    }

    /**
     * 在隔离进程执行 Dex 字节码（需 dex 权限）。
     * dex / input / output 路径相对沙箱根；隔离进程仅能通过 FD 读写，无法访问沙箱其他内容。
     */
    private suspend fun dexRun(p: JSONObject): String = withContext(Dispatchers.IO) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.DEX
        )
        if (!granted) throw SecurityException("permission denied: dex")
        val dexPath = p.optStringOr("dex")
        val className = p.optStringOr("className")
        if (dexPath.isEmpty()) throw IllegalArgumentException("dex 路径不能为空")
        if (className.isEmpty()) throw IllegalArgumentException("className 不能为空")
        val dexFile = com.miniapp.container.core.PathGuard.resolveUnderRoot(sandboxRoot, dexPath)
        if (!dexFile.isFile) throw java.io.FileNotFoundException("dex 文件不存在: $dexPath")
        val inputPath = p.optStringOr("input")
        val outputPath = p.optStringOr("output")
        val input = if (inputPath.isNotEmpty())
            com.miniapp.container.core.PathGuard.resolveUnderRoot(sandboxRoot, inputPath) else null
        val output = if (outputPath.isNotEmpty())
            com.miniapp.container.core.PathGuard.resolveUnderRoot(sandboxRoot, outputPath) else null
        val params = android.os.Bundle()
        p.optJSONObject("params")?.let { o -> for (k in o.keys()) params.putString(k, o.optString(k)) }
        params.putString("className", className)
        params.putString("methodName", p.optStringOr("methodName", "run"))
        val result = com.miniapp.container.dex.DexRunner(activity).run(dexFile, input, output, params)
        val out = JSONObject()
        result.keySet().forEach { k -> out.put(k, result.get(k)?.toString()) }
        out.toString()
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

    /** 通过 SAF 选择文件导入到沙箱（无需权限）。 */
    private suspend fun importFile(destPath: String): String {
        if (destPath.isBlank()) throw IllegalArgumentException("destPath required")
        return suspendCancellableCoroutine { cont ->
            activity.launchImport { uri ->
                if (uri == null) { if (cont.isActive) cont.resume(""); return@launchImport }
                activity.lifecycleScope.launch {
                    try {
                        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        fileService.writeBytes(destPath, com.miniapp.container.util.IoUtil.toBase64(bytes))
                        if (cont.isActive) cont.resume("true")
                    } catch (e: Throwable) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                }
            }
        }
    }

    /** 通过 SAF 选择位置导出沙箱文件（无需权限）。 */
    private suspend fun exportFile(path: String): String {
        if (path.isBlank()) throw IllegalArgumentException("path required")
        val b64 = fileService.readBytes(path)
        val bytes = com.miniapp.container.util.IoUtil.fromBase64(b64)
        val defaultName = java.io.File(path).name
        return suspendCancellableCoroutine { cont ->
            activity.launchExport(defaultName) { uri ->
                if (uri == null) { if (cont.isActive) cont.resume(""); return@launchExport }
                activity.lifecycleScope.launch {
                    try {
                        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw java.io.IOException("无法写入")
                        if (cont.isActive) cont.resume("true")
                    } catch (e: Throwable) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                }
            }
        }
    }

    /** 静默读取内部储存文件（需 fs.external 权限 + 系统所有文件访问）。 */
    private suspend fun readExternalFile(absPath: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(absPath)
        assertExternalGranted(f)
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $absPath")
        JSONObject.quote(com.miniapp.container.util.IoUtil.toBase64(com.miniapp.container.util.IoUtil.readBytes(f)))
    }

    /** 静默写入内部储存文件（需 fs.external 权限 + 系统所有文件访问）。 */
    private suspend fun writeExternalFile(absPath: String, base64: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(absPath)
        assertExternalGranted(f)
        f.parentFile?.mkdirs()
        com.miniapp.container.util.IoUtil.writeBytes(f, com.miniapp.container.util.IoUtil.fromBase64(base64))
        "true"
    }

    /** 禁止访问应用私有目录（防篡改权限记录）。 */
    private fun assertExternalPath(f: java.io.File) {
        val p = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
        if (p.startsWith(activity.filesDir.canonicalPath) || p.startsWith("/data/data/")) {
            throw SecurityException("禁止访问应用私有目录: $p")
        }
    }

    /** 内部储存操作前置：fs.external 权限 + 系统存储权限 + 路径防私有目录。 */
    private suspend fun assertExternalGranted(f: java.io.File) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.FS_EXTERNAL
        )
        if (!granted) throw SecurityException("permission denied: fs.external")
        ensureSystemStoragePermission()
        assertExternalPath(f)
    }

    /** 内部储存是否存在（需 fs.external）。 */
    private suspend fun existsExternal(path: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(path)
        assertExternalGranted(f)
        if (f.exists()) "true" else "false"
    }

    /** 内部储存文件信息（需 fs.external）。 */
    private suspend fun statExternal(path: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(path)
        assertExternalGranted(f)
        JSONObject()
            .put("exists", f.exists())
            .put("isDir", f.isDirectory)
            .put("size", if (f.isFile) f.length() else 0)
            .put("name", f.name)
            .put("canRead", f.canRead())
            .put("canWrite", f.canWrite())
            .put("lastModified", f.lastModified())
            .toString()
    }

    /** 内部储存创建目录（需 fs.external）。 */
    private suspend fun mkdirExternal(dir: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(dir)
        assertExternalGranted(f)
        (f.exists() || f.mkdirs()).toString()
    }

    /** 内部储存删除文件或目录（递归，需 fs.external）。 */
    private suspend fun removeExternal(path: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(path)
        assertExternalGranted(f)
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) "true" else "false"
    }

    /** 内部储存重命名/移动文件（需 fs.external）。 */
    private suspend fun renameExternal(from: String, to: String): String = withContext(Dispatchers.IO) {
        val src = java.io.File(from)
        val dst = java.io.File(to)
        assertExternalGranted(src)
        assertExternalGranted(dst)
        src.renameTo(dst).toString()
    }

    /** 列出内部储存某目录的文件列表（不递归，需 fs.external + 系统存储权限）。 */
    private suspend fun listExternal(dir: String): String = withContext(Dispatchers.IO) {
        val f = java.io.File(dir)
        assertExternalGranted(f)
        if (!f.exists()) throw java.io.FileNotFoundException("目录不存在: $dir")
        if (!f.isDirectory) throw java.io.IOException("目标不是目录: $dir")
        val arr = JSONArray()
        f.listFiles()?.sortedBy { it.name }?.forEach {
            arr.put(JSONObject()
                .put("name", it.name)
                .put("isDir", it.isDirectory)
                .put("size", it.length()))
        }
        arr.toString()
    }

    /**
     * 确保系统存储权限（版本适配 + 主动申请）。
     * - Android 11+ (API>=30)：检查 [Environment.isExternalStorageManager]，
     *   未开启则自动跳转系统设置页申请（MANAGE_EXTERNAL_STORAGE 系统限制只能跳设置）。
     * - Android 10 及以下：主动弹窗申请 READ/WRITE_EXTERNAL_STORAGE 运行时权限。
     */
    private suspend fun ensureSystemStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) return
            withContext(Dispatchers.Main) {
                try {
                    activity.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(android.net.Uri.parse("package:${activity.packageName}"))
                    )
                } catch (e: Exception) {
                    try {
                        activity.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    } catch (e2: Exception) { /* 无该设置项，忽略 */ }
                }
            }
            throw SecurityException("已跳转系统设置，请开启「所有文件访问权限」后重试")
        } else {
            val needed = mutableListOf<String>()
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (needed.isEmpty()) return
            val result = suspendCancellableCoroutine<Map<String, Boolean>> { cont ->
                activity.requestRuntimePerms(needed.toTypedArray()) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
            if (!result.values.all { it }) throw SecurityException("存储权限被拒绝")
        }
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
