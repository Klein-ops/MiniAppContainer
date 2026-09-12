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
 * - 所有路径都在沙箱内解析（[PathGuard.resolveUnderRoot]）。
 * - 沙箱内读写、创建、删除默认允许，无需审批。
 * - 出沙箱路径会抛 SecurityException，由上层转为权限错误。
 *
 * 所有方法返回 JSON 值字符串（供 JS Bridge 直接回传前端）。
 */
class FileService(private val sandboxRoot: File) {

    private fun resolve(path: String): File =
        PathGuard.resolveUnderRoot(sandboxRoot, path)

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
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("目标是目录: $path")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        "true"
    }

    suspend fun writeBytes(path: String, base64: String): String = withContext(Dispatchers.IO) {
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
        resolve(path).mkdirs()
        "true"
    }

    suspend fun remove(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) "true" else "false"
    }
}
