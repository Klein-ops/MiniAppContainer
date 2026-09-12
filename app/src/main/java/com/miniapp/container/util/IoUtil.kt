package com.miniapp.container.util

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object IoUtil {

    /** 解压 zip 到 destDir，带 Zip-Slip 防护。 */
    fun unzip(zip: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(FileInputStream(zip).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = safeChild(destDir, entry.name)
                if (out == null) {
                    zis.closeEntry(); entry = zis.nextEntry; continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun safeChild(base: File, name: String): File? {
        val f = File(base, name)
        val b = base.canonicalPath
        val t = f.canonicalPath
        if (t == b || t.startsWith(b + File.separator)) return f
        return null
    }

    fun copy(src: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { src.copyTo(it) }
    }

    fun readBytes(file: File): ByteArray = FileInputStream(file).use { it.readBytes() }

    fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    /** 清理宿主缓存目录（WebView 缓存等）。 */
    fun clearCache(context: android.content.Context) {
        val cache = context.cacheDir
        cache.listFiles()?.forEach { it.deleteRecursively() }
        // WebView 数据库目录（app_webview）由 WebStorage.deleteAllData 处理
    }
}
