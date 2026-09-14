package com.miniapp.container.core

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复服务：
 * - 导出：把所有小程序数据（registry/categories/permissions + 各沙箱 app/data/meta）打包为 zip
 * - 导入：从 zip 解压覆盖 miniapps 目录
 * - WebDAV：上传/下载备份文件（HTTP PUT/GET + Basic Auth）
 */
class BackupService(private val context: Context) {

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    var webdavUrl: String
        get() = prefs.getString("url", "").orEmpty()
        set(v) = prefs.edit().putString("url", v).apply()
    var webdavUser: String
        get() = prefs.getString("user", "").orEmpty()
        set(v) = prefs.edit().putString("user", v).apply()
    var webdavPass: String
        get() = prefs.getString("pass", "").orEmpty()
        set(v) = prefs.edit().putString("pass", v).apply()

    /** 导出所有小程序数据为 zip（排除 tmp/ 临时目录）。 */
    fun exportToZip(dest: File) {
        val base = File(context.filesDir, "miniapps")
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            base.walkTopDown()
                .filter { it.isFile && !it.absolutePath.contains("/tmp/") }
                .forEach { f ->
                    val rel = "miniapps/" + f.relativeTo(base).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    /** 从 zip 恢复（解压覆盖 miniapps 目录，防路径穿越）。 */
    fun importFromZip(zipFile: File) {
        val targetRoot = context.filesDir
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("miniapps/") &&
                    !name.contains("..") && !name.startsWith("/")
                ) {
                    val target = File(targetRoot, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** WebDAV 上传备份文件。 */
    fun webdavUpload(file: File): Boolean {
        val conn = openWebdav("PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/zip")
        conn.outputStream.use { file.inputStream().use { i -> i.copyTo(it) } }
        val code = conn.responseCode
        conn.disconnect()
        return code in 200..299
    }

    /** WebDAV 下载备份文件。 */
    fun webdavDownload(dest: File): Boolean {
        val conn = openWebdav("GET")
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            return true
        }
        conn.disconnect()
        return false
    }

    private fun openWebdav(method: String): HttpURLConnection =
        (URL(webdavUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (webdavUser.isNotEmpty()) {
                setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.encodeToString(
                        "$webdavUser:$webdavPass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    )
                )
            }
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
        }
}
