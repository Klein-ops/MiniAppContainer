package com.miniapp.container.netdisk

import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WebDAV 目录项。 */
data class WebdavEntry(val name: String, val isDir: Boolean)

/**
 * WebDAV 客户端（OkHttp + Basic Auth）。
 *
 * **必须用 OkHttp**：`HttpURLConnection` 只支持 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
 * 发送 `PROPFIND` / `MKCOL` 会被拒绝或被改写成 GET，导致所有 WebDAV 服务器都无法连接。
 * OkHttp 的 `Request.Builder.method()` 允许任意方法。
 *
 * 支持 MKCOL / PUT / GET / DELETE / PROPFIND。
 * 路径按 `/` 分段，各段 percent-encode（含中文），拼接在 [WebdavConfig.url] 之后。
 */
class WebdavClient(private val config: WebdavConfig) {

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
               |<d:propfind xmlns:d="DAV:">
               |  <d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop>
               |</d:propfind>""".trimMargin()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // 服务器返回 401 且我们尚未附带凭据时，补一次 Basic Auth 重试
            .authenticator { _, response ->
                if (response.request.header("Authorization") != null) return@authenticator null
                if (config.user.isEmpty()) return@authenticator null
                response.request.newBuilder()
                    .header("Authorization", Credentials.basic(config.user, config.pass, Charsets.UTF_8))
                    .build()
            }
            .build()
    }

    /** 服务器根 URL（去末尾 `/`）。 */
    private fun base(): String = config.url.trim()

    /** 拼完整 URL：base + 各段（percent-encode，保留已有 `/` 结构）。 */
    private fun urlOf(segments: List<String>): String {
        val sb = StringBuilder(base().trimEnd('/'))
        segments.filter { it.isNotEmpty() }.forEach { sb.append('/').append(Uri.encode(it)) }
        return sb.toString()
    }

    /** 构造请求（自动附加 Basic Auth）。 */
    private fun newBuilder(method: String, segments: List<String>, body: RequestBody?): Request.Builder {
        val b = Request.Builder().url(urlOf(segments)).method(method, body)
        if (config.user.isNotEmpty()) {
            b.header("Authorization", Credentials.basic(config.user, config.pass, Charsets.UTF_8))
        }
        return b
    }

    /** 逐级创建目录；已存在（405）视为成功。 */
    fun mkdirs(segments: List<String>) {
        for (i in 1..segments.size) {
            val sub = segments.subList(0, i)
            client.newCall(newBuilder("MKCOL", sub, null).build()).execute().use { resp ->
                val code = resp.code
                if (code !in 200..299 && code != 405) {
                    throw IOException("MKCOL 失败 ($code): ${sub.joinToString("/")}")
                }
            }
        }
    }

    /** 上传文件。 */
    fun put(segments: List<String>, file: File): Boolean =
        client.newCall(newBuilder("PUT", segments, file.asRequestBody(OCTET)).build())
            .execute().use { it.isSuccessful }

    /** 下载到本地文件。 */
    fun get(segments: List<String>, dest: File): Boolean =
        client.newCall(newBuilder("GET", segments, null).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val stream = resp.body?.byteStream() ?: return@use false
            stream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            true
        }

    /** 删除文件/目录（不存在视为成功）。 */
    fun delete(segments: List<String>): Boolean =
        client.newCall(newBuilder("DELETE", segments, null).build())
            .execute().use { it.isSuccessful || it.code == 404 }

    /** 是否存在（PROPFIND Depth:0）。 */
    fun exists(segments: List<String>): Boolean =
        client.newCall(
            newBuilder("PROPFIND", segments, null).header("Depth", "0").build()
        ).execute().use { it.isSuccessful }

    /** 列出目录项（PROPFIND Depth:1）。 */
    fun listEntries(segments: List<String>): List<WebdavEntry> =
        client.newCall(
            newBuilder("PROPFIND", segments, PROPFIND_BODY.toRequestBody(XML))
                .header("Depth", "1")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            parseEntries(body, segments)
        }

    /** 仅列目录名。 */
    fun list(segments: List<String>): List<String> = listEntries(segments).map { it.name }

    /** 从 PROPFIND 响应解析直接子项（名称 + 是否目录）。 */
    private fun parseEntries(xml: String, parentSegments: List<String>): List<WebdavEntry> {
        // prefix 用「未编码」的原始段拼接；href 解码后再比对，避免编码层级错位
        val prefix = parentSegments.filter { it.isNotEmpty() }.joinToString("") { "/" + it }
        val result = LinkedHashSet<WebdavEntry>()
        val blockRe = Regex(
            "<[^>]*:?response[^>]*>(.*?)</[^>]*:?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hrefRe = Regex("<[^>]*:?href[^>]*>(.*?)</[^>]*:?href>", RegexOption.IGNORE_CASE)
        blockRe.findAll(xml).forEach block@{ m ->
            val body = m.groupValues[1]
            var href = hrefRe.find(body)?.groupValues?.get(1)?.trim() ?: return@block
            // 绝对 URL → 取 path
            if (href.startsWith("http://") || href.startsWith("https://")) {
                href = runCatching { java.net.URL(href).path }.getOrDefault(href)
            }
            // 百分号解码（先保护 '+'，避免被解成空格）
            val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8").trimEnd('/')
            val idx = decoded.indexOf(prefix)
            if (idx < 0) return@block
            val child = decoded.substring(idx + prefix.length).trim('/')
            if (child.isEmpty() || child.contains('/')) return@block
            val isDir = body.contains("collection", ignoreCase = true)
            result.add(WebdavEntry(child, isDir))
        }
        return result.toList()
    }

    /**
     * 连通性测试：返回 `null` 表示成功，否则为可读的错误原因。
     * 依次验证：能否读取 → 目录不存在则尝试创建 → 能否列出内容。
     */
    fun testConnection(rootFolder: String): String? {
        val segs = listOf(rootFolder)
        return try {
            val readCode = propfindCode(segs, "0")
            if (readCode == 401 || readCode == 403) {
                return "认证失败或被拒绝（HTTP $readCode）。请检查用户名/密码；" +
                    "部分服务器（如坚果云）要求用户名填写完整邮箱，或需要在服务端开启应用密码。"
            }
            if (readCode !in 200..299) {
                // 目录可能不存在 → 尝试创建（同时验证写权限）
                val mk = mkcolCode(segs)
                if (mk !in 200..299 && mk != 405) {
                    return "目录 /$rootFolder 不存在且创建失败（HTTP $mk）。" +
                        "请确认地址指向你账号下可写的目录（部分服务需填到具体子路径）。"
                }
            }
            val listCode = propfindCode(segs, "1")
            if (listCode !in 200..299) return "无法列出目录内容（HTTP $listCode）"
            null
        } catch (t: Throwable) {
            "网络错误：${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun propfindCode(segments: List<String>, depth: String): Int =
        client.newCall(
            newBuilder("PROPFIND", segments, PROPFIND_BODY.toRequestBody(XML))
                .header("Depth", depth).build()
        ).execute().use { it.code }

    private fun mkcolCode(segments: List<String>): Int =
        client.newCall(newBuilder("MKCOL", segments, null).build()).execute().use { it.code }
}
