package com.miniapp.container.bridge

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.core.PathGuard
import com.miniapp.container.file.FileService
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.service.ClipboardService
import com.miniapp.container.service.ExternalFileService
import com.miniapp.container.service.NetService
import com.miniapp.container.service.NotificationService
import com.miniapp.container.sys.SystemInfoService
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optBoolOr
import com.miniapp.container.util.optStringList
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

private data class BridgeOut(val ok: Boolean, val payload: String)

/**
 * 前端与 Native 的唯一通信通道（仅负责分发，具体能力委托各服务类）。
 *
 * 前端通过 `MiniAppNative.call(reqId, method, paramsJson)` 调用，
 * 结果异步通过 `window.__MiniAppBridge.__resolve(reqId, ok, payload)` 回传。
 *
 * - 沙箱内文件：委托 [FileService]
 * - 出沙箱文件（fs.external / SAF / content://）：委托 [ExternalFileService]
 * - 网络：委托 [NetService]
 * - 剪贴板：委托 [ClipboardService]
 * - 通知：委托 [NotificationService]
 * - Dex：委托 [com.miniapp.container.dex.DexRunner]
 * - 应用/系统信息：委托 [SystemInfoService] / 本类
 */
class MiniAppBridge(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val sandboxRoot: File,
    private val fileService: FileService,
    private val permissionManager: PermissionManager,
    private val systemInfo: SystemInfoService,
    private val containerUi: com.miniapp.container.sys.ContainerUiService,
    private val hostAppVersion: String
) {
    private val main = Handler(Looper.getMainLooper())
    private var webViewRef: WeakReference<WebView> = WeakReference(null)

    private val externalFile = ExternalFileService(activity, appInfo, permissionManager, fileService)
    private val net = NetService(activity, appInfo, permissionManager)
    private val clipboard = ClipboardService(activity, appInfo, permissionManager)
    private val notification = NotificationService(activity, appInfo, permissionManager)
    private val storage = com.miniapp.container.service.StorageService(activity, appInfo, permissionManager)
    private val adb = com.miniapp.container.service.AdbService(activity, appInfo, permissionManager)
    private val vibrate = com.miniapp.container.service.VibrateService(activity, appInfo, permissionManager)
    private val camera = com.miniapp.container.service.CameraService(activity, appInfo, permissionManager, sandboxRoot)

        companion object {
        /**
         * 接口版本：**只在接口文档行为变化时递增**，不随 App 版本号变动。
         * （App 可能仅调整 UI / 修 Bug 就升级，但那不影响接口契约。）
         * 首次引入定为 1.0.0；将来接口行为变化（新增/修改/删除接口）时递增。
         */
        const val API_VERSION = "1.0.0"
    }
companion object { private const val TAG = "MiniAppBridge" }

    fun attach(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    @JavascriptInterface
    fun call(reqId: String, method: String, paramsJson: String) {
        activity.lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val out = try {
                val params = if (paramsJson.isBlank()) JSONObject() else JSONObject(paramsJson)
                BridgeOut(true, handle(method, params))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "bridge call '$method' failed", e)
                BridgeOut(false, e.message ?: e.javaClass.simpleName)
            }
            logIfDebug(method, paramsJson, out, t0)
            respond(reqId, out)
        }
    }

    private fun logIfDebug(method: String, paramsJson: String, out: BridgeOut, t0: Long) {
        if (!com.miniapp.container.debug.DebugBus.enabled) return
        val ms = System.currentTimeMillis() - t0
        val result = if (out.ok) "← 返回: ${out.payload}" else "✗ 错误: ${out.payload}"
        com.miniapp.container.debug.DebugBus.log(
            "→ $method\n参数: $paramsJson\n$result\n耗时: ${ms}ms"
        )
    }

    private suspend fun handle(method: String, p: JSONObject): String = when (method) {
        // 应用 / 系统
        "app.info" -> appInfoJson()
        "system.info" -> systemInfo.info(hostAppVersion, API_VERSION, p.optStringList("fields"))
        "ui.toast" -> { toast(p.optStringOr("message")); "true" }

        // 沙箱内文件
        "fs.read" -> fileService.read(p.optStringOr("path"))
        "fs.readBytes" -> fileService.readBytes(p.optStringOr("path"))
        "fs.write" -> fileService.write(p.optStringOr("path"), p.optStringOr("content"))
        "fs.writeBytes" -> fileService.writeBytes(p.optStringOr("path"), p.optStringOr("base64"))
        "fs.list" -> fileService.list(p.optStringOr("path"))
        "fs.grep" -> fileService.grep(
            p.optStringOr("path"), p.optStringOr("pattern"),
            p.optBoolOr("regex"), p.optBoolOr("ignoreCase"), p.optBoolOr("invert")
        )
        "fs.sed" -> fileService.sed(p.optStringOr("path"), p.optStringOr("script"))
        "fs.exists" -> fileService.exists(p.optStringOr("path"))
        "fs.stat" -> fileService.stat(p.optStringOr("path"))
        "fs.mkdir" -> fileService.mkdir(p.optStringOr("path"))
        "fs.remove" -> fileService.remove(p.optStringOr("path"))

        // 出沙箱文件（内部储存 / SAF / content://）
        "fs.readExternal" -> externalFile.readUri(p.optStringOr("uri"))
        "fs.importFile" -> externalFile.importViaSaf(p.optStringOr("destPath"))
        "fs.exportFile" -> externalFile.exportViaSaf(p.optStringOr("path"))
        "fs.readExternalFile" -> externalFile.readFile(p.optStringOr("path"))
        "fs.writeExternalFile" -> externalFile.writeFile(p.optStringOr("path"), p.optStringOr("base64"))
        "fs.listExternal" -> externalFile.list(p.optStringOr("dir"))
        "fs.grepExternal" -> externalFile.grepFile(
            p.optStringOr("path"), p.optStringOr("pattern"),
            p.optBoolOr("regex"), p.optBoolOr("ignoreCase"), p.optBoolOr("invert")
        )
        "fs.sedExternal" -> externalFile.sedFile(p.optStringOr("path"), p.optStringOr("script"))
        "fs.existsExternal" -> externalFile.exists(p.optStringOr("path"))
        "fs.statExternal" -> externalFile.stat(p.optStringOr("path"))
        "fs.mkdirExternal" -> externalFile.mkdir(p.optStringOr("dir"))
        "fs.removeExternal" -> externalFile.remove(p.optStringOr("path"))
        "fs.renameExternal" -> externalFile.rename(p.optStringOr("from"), p.optStringOr("to"))

        // 网络
        "net.httpGet" -> net.get(p.optStringOr("url"))
        "net.httpRequest" -> net.request(p)

        // 剪贴板
        "cb.read" -> clipboard.read()
        "cb.write" -> clipboard.write(p.optStringOr("text"))

        // 通知
        "notify.show" -> notification.show(p.optStringOr("title"), p.optStringOr("body"))
        "notify.cancel" -> notification.cancel()

        // 网络存储
        "storage.upload" -> storage.upload(p.optStringOr("path"), p.optStringOr("base64"))
        "storage.download" -> storage.download(p.optStringOr("path"))
        "storage.list" -> storage.list(p.optStringOr("path"))
        "storage.delete" -> storage.delete(p.optStringOr("path"))

        // ADB / Shell（Shizuku）
        "adb.exec" -> adb.exec(p)

        // Dex
        "dex.run" -> dexRun(p)

        // 其他
        "sys.openUrl" -> openUrl(p.optStringOr("url"))
        "sys.setOrientation" -> containerUi.setOrientation(p)
        "sys.setStatusBar" -> containerUi.setStatusBar(p)
        "sys.setStatusBarColor" -> containerUi.setStatusBarColor(p)
        "sys.vibrate" -> vibrate.vibrate(p)
        "sys.flashlight" -> camera.setTorch(p)
        "camera.takePhoto" -> camera.takePhoto(p)
        "perm.request" -> {
            val scope = p.optStringOr("scope")
            val granted = permissionManager.ensurePermission(
                activity, appInfo.appKey, appInfo.permissions, scope
            )
            if (granted) "true" else "false"
        }
        else -> throw IllegalArgumentException("unknown method: $method")
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

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private suspend fun openUrl(url: String): String {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.OPEN_URL
        )
        if (!granted) throw SecurityException("permission denied: sys.openUrl")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return "true"
    }

    /**
     * 在隔离进程执行 Dex 字节码（需 dex 权限）。
     * dex / input / output 路径相对沙箱根；隔离进程仅能通过 FD 读写。
     */
    private suspend fun dexRun(p: JSONObject): String {
        // 无需权限：dex 在 isolatedProcess 隔离进程中执行（独立 UID + isolated_app 域），
        // 无网络/无路径访问/无系统服务/不能加载 native，天然受限，故不再要求审批。
        val dexPath = p.optStringOr("dex")
        val className = p.optStringOr("className")
        if (dexPath.isEmpty()) throw IllegalArgumentException("dex 路径不能为空")
        if (className.isEmpty()) throw IllegalArgumentException("className 不能为空")
        val dexFile = PathGuard.resolveUnderRoot(sandboxRoot, dexPath)
        if (!dexFile.isFile) throw java.io.FileNotFoundException("dex 文件不存在: $dexPath")
        val inputPath = p.optStringOr("input")
        val outputPath = p.optStringOr("output")
        val input = if (inputPath.isNotEmpty()) PathGuard.resolveUnderRoot(sandboxRoot, inputPath) else null
        val output = if (outputPath.isNotEmpty()) PathGuard.resolveUnderRoot(sandboxRoot, outputPath) else null
        val params = Bundle()
        p.optJSONObject("params")?.let { o -> for (k in o.keys()) params.putString(k, o.optString(k)) }
        // 用内部保留键传递入口信息，不污染调用方传入的 params
        params.putString("__className", className)
        params.putString("__methodName", p.optStringOr("methodName", "run"))
        val result = com.miniapp.container.dex.DexRunner(activity).run(dexFile, input, output, params)
        val out = JSONObject()
        result.keySet().forEach { k ->
            // 布尔值保持布尔类型（如 ok），其余转字符串，避免 ok 变成 "true" 字符串
            when (val v = result.get(k)) {
                is Boolean -> out.put(k, v)
                else -> out.put(k, v?.toString())
            }
        }
        return out.toString()
    }

    private fun respond(reqId: String, out: BridgeOut) {
        // payload 会被原样拼进 JS：若为空会生成 __resolve(id,true,) 语法错误，
        // 导致 Promise 永不结算。空 payload 统一降级为 null。
        val payloadJs = if (out.payload.isBlank()) "null" else out.payload
        val js = if (out.ok) {
            "window.__MiniAppBridge.__resolve(${jsString(reqId)},true,$payloadJs)"
        } else {
            "window.__MiniAppBridge.__resolve(${jsString(reqId)},false,${jsString(out.payload)})"
        }
        main.post {
            try {
                webViewRef.get()?.evaluateJavascript(js, null)
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
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
