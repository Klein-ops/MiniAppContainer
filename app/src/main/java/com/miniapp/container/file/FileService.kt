package com.miniapp.container.file

import com.miniapp.container.core.PathGuard
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 沙箱内文件服务。
 *
 * - 所有路径在沙箱内解析（[PathGuard.resolveUnderRoot]）。
 * - **白名单**：写操作（write/writeBytes/mkdir/remove）只允许 `data/` 和 `tmp/`，
 *   禁止写 `app/`（只读资源区）。
 * - 读操作允许 `app/`、`data/`、`tmp/`。
 */
class FileService(private val sandboxRoot: File) {

    private fun resolve(path: String): File =
        PathGuard.resolveUnderRoot(sandboxRoot, path)

    /** 写操作白名单：只允许 data/ 和 tmp/ 前缀。 */
    private fun assertWritable(path: String) {
        val norm = path.trim().removePrefix("./").removePrefix("/")
        val prefix = norm.substringBefore('/')
        if (prefix != "data" && prefix != "tmp") {
            throw SecurityException("写操作仅允许 data/ 和 tmp/ 目录: $path")
        }
    }

    suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $path")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $path")
        JSONObject.quote(f.readText(Charsets.UTF_8))
    }

    suspend fun readBytes(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $path")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $path")
        JSONObject.quote(IoUtil.toBase64(IoUtil.readBytes(f)))
    }

    suspend fun write(path: String, content: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("目标是目录: $path")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        "true"
    }

    suspend fun writeBytes(path: String, base64: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("目标是目录: $path")
        IoUtil.writeBytes(f, IoUtil.fromBase64(base64))
        "true"
    }

    suspend fun list(dir: String): String = withContext(Dispatchers.IO) {
        val f = resolve(dir)
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

    suspend fun exists(path: String): String = withContext(Dispatchers.IO) {
        if (resolve(path).exists()) "true" else "false"
    }

    suspend fun stat(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        JSONObject()
            .put("exists", f.exists())
            .put("isDir", f.isDirectory)
            .put("size", f.length())
            .put("name", f.name)
            .put("canRead", f.canRead())
            .put("canWrite", f.canWrite())
            .put("lastModified", f.lastModified())
            .toString()
    }

    suspend fun mkdir(path: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        resolve(path).mkdirs()
        "true"
    }

    suspend fun remove(path: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) "true" else "false"
    }

    /** 清空沙箱数据（data/ 和 tmp/），保留 app/。 */
    suspend fun clearData(): String = withContext(Dispatchers.IO) {
        File(sandboxRoot, "data").listFiles()?.forEach { it.deleteRecursively() }
        File(sandboxRoot, "tmp").listFiles()?.forEach { it.deleteRecursively() }
        "true"
    }
}
