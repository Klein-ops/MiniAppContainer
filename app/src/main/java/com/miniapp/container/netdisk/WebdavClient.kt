package com.miniapp.container.netdisk

import android.net.Uri
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 WebDAV 客户端（基于 HttpURLConnection + Basic Auth）。
 *
 * 支持 MKCOL / PUT / GET / DELETE / PROPFIND。
 * 路径以 `/` 分段，各段按需 percent-encode（含中文），拼接在 [WebdavConfig.url] 之后。
 */
class WebdavClient(private val config: WebdavConfig) {

    /** 服务器根 URL（去掉末尾 `/`）。 */
    private fun base(): String = config.url.trimEnd('/')

    /** 拼完整 URL：base + 各段（中文等 percent-encode）。 */
    private fun buildUrl(segments: List<String>): String {
        val sb = StringBuilder(base())
        segments.filter { it.isNotEmpty() }.forEach { sb.append('/').append(Uri.encode(it)) }
        return sb.toString()
    }

    private fun open(method: String, segments: List<String>): HttpURLConnection =
        (URL(buildUrl(segments)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            if (config.user.isNotEmpty()) {
                setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.encodeToString(
                        "${config.user}:${config.pass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    )
                )
            }
        }

    /** 创建目录（逐级 MKCOL；已存在 405 视为成功）。 */
    fun mkdirs(segments: List<String>) {
        for (i in 1..segments.size) {
            val conn = open("MKCOL", segments.subList(0, i))
            try {
                val code = conn.responseCode
                if (code !in 200..299 && code != 405) {
                    throw java.io.IOException(
                        "MKCOL 失败 ($code): ${segments.subList(0, i).joinToString("/")}"
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 上传文件到指定路径。 */
    fun put(segments: List<String>, file: File): Boolean {
        val conn = open("PUT", segments)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /** 下载到本地文件。 */
    fun get(segments: List<String>, dest: File): Boolean {
        val conn = open("GET", segments)
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                true
            } else false
        } finally {
            conn.disconnect()
        }
    }

    /** 删除文件或目录。 */
    fun delete(segments: List<String>): Boolean {
        val conn = open("DELETE", segments)
        return try {
            val code = conn.responseCode
            code in 200..299 || code == 404
        } finally {
            conn.disconnect()
        }
    }

    /** 文件/目录是否存在（PROPFIND Depth:0）。 */
    fun exists(segments: List<String>): Boolean {
        val conn = open("PROPFIND", segments)
        return try {
            conn.setRequestProperty("Depth", "0")
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /** 列目录项（含是否目录），PROPFIND Depth:1。 */
    fun listEntries(segments: List<String>): List<WebdavEntry> {
        val conn = open("PROPFIND", segments)
        return try {
            conn.setRequestProperty("Depth", "1")
            conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use {
                it.write(
                    ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>")
                        .toByteArray(Charsets.UTF_8)
                )
            }
            if (conn.responseCode !in 200..299) return emptyList()
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            parseEntries(xml, segments)
        } finally {
            conn.disconnect()
        }
    }

    /** 列出目录下的直接子项名。 */
    fun list(segments: List<String>): List<String> = listEntries(segments).map { it.name }

    /** 从 PROPFIND 响应 XML 中提取直接子项（名称 + 是否目录）。 */
    private fun parseEntries(xml: String, parentSegments: List<String>): List<WebdavEntry> {
        val parentEncoded = parentSegments.joinToString("/") { Uri.encode(it) }
        val result = LinkedHashSet<WebdavEntry>()
        val blockRe = Regex(
            "<[^>]*:?response[^>]*>(.*?)</[^>]*:?response>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val hrefRe = Regex("<[^>]*:?href[^>]*>(.*?)</[^>]*:?href>", RegexOption.IGNORE_CASE)
        blockRe.findAll(xml).forEach { block ->
            val body = block.groupValues[1]
            val hrefRaw = hrefRe.find(body)?.groupValues?.get(1)?.trim() ?: return@forEach
            var href = hrefRaw
            if (href.startsWith("http://") || href.startsWith("https://")) {
                href = runCatching { URL(href).path }.getOrDefault(href)
            }
            href = java.net.URLDecoder.decode(href, "UTF-8").trimEnd('/')
            val idx = href.indexOf(parentEncoded)
            if (idx < 0) return@forEach
            val child = href.substring(idx + parentEncoded.length).trim('/')
            if (child.isEmpty() || child.contains('/')) return@forEach
            val isDir = body.contains("collection", ignoreCase = true)
            result.add(WebdavEntry(child, isDir))
        }
        return result.toList()
    }
}

/** WebDAV 目录项。 */
data class WebdavEntry(val name: String, val isDir: Boolean)
