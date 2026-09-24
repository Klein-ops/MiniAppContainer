package com.miniapp.container.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miniapp.container.core.PathGuard
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 渲染层客户端：
 * - 拦截所有请求，仅放行当前沙箱内的 file:// 资源（跨沙箱一律 403）。
 * - http/https/content 等非沙箱请求一律阻断，强制走 JS Bridge（出沙箱需审批）。
 * - 页面加载起始/完成时注入 JS Bridge 垫片。
 */
class MiniAppWebViewClient(
    private val sandboxRoot: File,
    private val bridgeJs: String,
    /**
     * 会话级外部文件授权表（token -> 真实文件），由宿主 Activity 持有并注册，本类只读。
     * 放 Activity 而非本类：WebView 方法必须在主线程调用，而 fs.openExternalFile 在 IO
     * 线程注册令牌——由 Activity 持有表，注册便完全不触碰 WebView。
     */
    private val externalTokens: Map<String, File>
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        val uri = req.url ?: return null
        return when (uri.scheme?.lowercase()) {
            "file" -> serveFile(uri)
            // 会话级外部文件授权 URL（fs.openExternalFile 返回）：仅放行本实例注册的令牌
            "https" -> if (uri.host?.lowercase() == EXTERNAL_HOST) serveExternalToken(req.method, uri) else blocked()
            "data", "blob", "about" -> null
            else -> blocked()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // 仅允许 file://（沙箱内）跳转；其余外部跳转一律拦截
        return request?.url?.scheme?.lowercase() != "file"
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        injectBridge(view)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        injectBridge(view)
    }

    private fun injectBridge(view: WebView?) {
        view?.evaluateJavascript(bridgeJs, null)
    }

    private fun serveFile(uri: Uri): WebResourceResponse {
        val path = uri.path ?: return notFound()
        val target = try {
            PathGuard.resolveUnderRoot(sandboxRoot, path)
        } catch (e: SecurityException) {
            return forbidden()
        }
        if (!target.exists() || target.isDirectory) return notFound()
        val mime = mimeOf(target.name)
        val encoding = if (isText(mime)) "utf-8" else null
        return try {
            WebResourceResponse(mime, encoding, FileInputStream(target))
        } catch (e: Exception) {
            notFound()
        }
    }

    /** 会话级外部文件流式读取：凭令牌查表，响应带 CORS 头供 fetch 跨源读取。 */
    private fun serveExternalToken(method: String, uri: Uri): WebResourceResponse {
        // 仅 GET/HEAD：OPTIONS 预检等请求不得返回文件内容（预检头未配置，直接 404 让预检失败）
        if (method != "GET" && method != "HEAD") return notFound()
        val path = uri.path ?: return notFound()
        if (!path.startsWith(EXTERNAL_PATH_PREFIX)) return notFound()
        val token = path.removePrefix(EXTERNAL_PATH_PREFIX)
        if (token.isEmpty() || token.contains('/')) return notFound()
        val target = externalTokens[token] ?: return notFound()
        if (!target.exists() || target.isDirectory) return notFound()
        val mime = mimeOf(target.name)
        val encoding = if (isText(mime)) "utf-8" else null
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",   // 页面 file:// 源跨源 fetch 必需
            "Cache-Control" to "no-store"           // 敏感文件禁止缓存
        )
        return try {
            WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(target))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun forbidden(): WebResourceResponse =
        response(403, "Forbidden", "forbidden")

    private fun notFound(): WebResourceResponse =
        response(404, "Not Found", "not found")

    private fun blocked(): WebResourceResponse =
        response(403, "Blocked", "blocked: out-of-sandbox access must go through MiniApp bridge")

    private fun response(code: Int, reason: String, body: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", code, reason, emptyMap(),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        )

    companion object {
        /** 会话级外部文件授权 URL 的虚拟域（不发起真实网络请求，由 shouldInterceptRequest 接管）。 */
        // RFC 2606 .invalid 保留域：永不解析，杜绝任何真实网络兜底
        const val EXTERNAL_HOST = "miniapp.invalid"
        const val EXTERNAL_PATH_PREFIX = "/ext/"

        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html",
            "js" to "application/javascript", "mjs" to "application/javascript",
            "css" to "text/css", "json" to "application/json",
            "txt" to "text/plain", "xml" to "application/xml",
            "svg" to "image/svg+xml",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon",
            "wasm" to "application/wasm",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf",
            "mp4" to "video/mp4", "mp3" to "audio/mpeg"
        )

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MIME[ext] ?: "application/octet-stream"
        }

        fun isText(mime: String): Boolean =
            mime.startsWith("text/") || mime.contains("javascript") ||
                    mime.contains("json") || mime.contains("svg+xml") || mime.contains("xml")
    }
}
