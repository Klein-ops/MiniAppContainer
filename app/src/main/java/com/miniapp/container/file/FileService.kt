package com.miniapp.container.file

import com.miniapp.container.core.PathGuard
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.miniapp.container.util.TextEditor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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

    /** 解析为可读文件（app/data/tmp 均可读）。供桥层复用（如直落网络下载目标）。 */
    fun resolveReadable(path: String): File = resolve(path)

    /** 解析为可写文件（仅 data/tmp）。供桥层复用（如流式写、下载落盘）。 */
    fun resolveWritable(path: String): File {
        assertWritable(path)
        return resolve(path)
    }

    /** 写操作白名单：只允许 data/ 和 tmp/ 前缀。 */
    private fun assertWritable(path: String) {
        val norm = path.trim().removePrefix("./").removePrefix("/")
        val prefix = norm.substringBefore('/')
        if (prefix != "data" && prefix != "tmp") {
            throw SecurityException("write operations only allowed in data/ and tmp/: $path")
        }
    }

    /** grep：返回匹配行（不修改文件）。 */
    suspend fun grep(path: String, pattern: String, regex: Boolean, ignoreCase: Boolean, invert: Boolean): String =
        withContext(Dispatchers.IO) {
            val f = resolve(path)
            if (!f.exists()) throw java.io.FileNotFoundException("file not found: $path")
            val arr = JSONArray()
            TextEditor.grep(f.readText(Charsets.UTF_8), pattern, regex, ignoreCase, invert)
                .forEach { arr.put(it) }
            arr.toString()
        }

    /** sed：对文本文件应用编辑脚本（s/old/new/[gi]、Nd、/pat/d、/pat/a\t、/pat/i\t），写回。 */
    suspend fun sed(path: String, script: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (!f.exists()) throw java.io.FileNotFoundException("file not found: $path")
        if (f.isDirectory) throw java.io.IOException("target is a directory: $path")
        val text = f.readText(Charsets.UTF_8)
        val result = TextEditor.sed(text, script)
        f.writeText(result, Charsets.UTF_8)
        "true"
    }

    suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) throw java.io.FileNotFoundException("file not found: $path")
        if (f.isDirectory) throw java.io.IOException("target is a directory: $path")
        JSONObject.quote(f.readText(Charsets.UTF_8))
    }

    suspend fun readBytes(path: String): String = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) throw java.io.FileNotFoundException("file not found: $path")
        if (f.isDirectory) throw java.io.IOException("target is a directory: $path")
        JSONObject.quote(IoUtil.toBase64(IoUtil.readBytes(f)))
    }

    suspend fun write(path: String, content: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("target is a directory: $path")
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        "true"
    }

    suspend fun writeBytes(path: String, base64: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("target is a directory: $path")
        IoUtil.writeBytes(f, IoUtil.fromBase64(base64))
        "true"
    }

    /**
     * 偏移读取指定字节区间（[offset, offset+length)）。
     * - offset<0 → 从 0 读；length<0 → 读到文件尾
     * - offset 超出文件长度 → 空串
     * 返回 base64。
     */
    suspend fun readChunk(path: String, offset: Long, length: Long): String = withContext(Dispatchers.IO) {
        val f = resolveReadable(path)
        if (!f.exists()) throw java.io.FileNotFoundException("file not found: $path")
        if (f.isDirectory) throw java.io.IOException("target is a directory: $path")
        val start = if (offset <= 0) 0L else offset
        if (start >= f.length()) return@withContext JSONObject.quote("")
        val end = if (length < 0L) f.length() else minOf(start + length, f.length())
        val toRead = (end - start).toInt()
        FileInputStream(f).use { input ->
            input.skip(start)
            val buf = ByteArray(toRead)
            var read = 0
            while (read < toRead) {
                val n = input.read(buf, read, toRead - read)
                if (n < 0) break
                read += n
            }
            val bytes = if (read == toRead) buf else buf.copyOf(read)
            JSONObject.quote(IoUtil.toBase64(bytes))
        }
    }

    /**
     * 偏移写：[offset] 处写入 [base64] 解码后的数据。
     * - offset 超出当前长度 → 自动以 0 字节补齐到该位置
     * - 用于随机写、断点续传、流式分块写
     */
    suspend fun writeChunk(path: String, offset: Long, base64: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("target is a directory: $path")
        f.parentFile?.mkdirs()
        val data = IoUtil.fromBase64(base64)
        val pos = if (offset < 0) f.length() else offset
        FileOutputStream(f, true).use { output ->
            output.channel.use {
                it.position(pos)  // position 超过当前 size 时，channel 写入会自动以 0 补齐
                it.write(java.nio.ByteBuffer.wrap(data))
            }
        }
        "true"
    }

    /** 追加写：在文件末尾追加 [base64] 数据（等价于 offset=文件尾）。 */
    suspend fun append(path: String, base64: String): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("target is a directory: $path")
        f.parentFile?.mkdirs()
        val data = IoUtil.fromBase64(base64)
        FileOutputStream(f, true).use { it.write(data) }
        "true"
    }

    /** 交截到指定大小（size 之后的字节丢弃）。 */
    suspend fun truncate(path: String, size: Long): String = withContext(Dispatchers.IO) {
        assertWritable(path)
        val f = resolve(path)
        if (f.exists() && f.isDirectory) throw java.io.IOException("target is a directory: $path")
        if (!f.exists()) {
            // 截断不存在的文件：创建空文件并置长
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { }
        }
        FileOutputStream(f, true).use { it.channel.truncate(size) }
        "true"
    }

    suspend fun list(dir: String): String = withContext(Dispatchers.IO) {
        val f = resolve(dir)
        if (!f.exists()) throw java.io.FileNotFoundException("directory not found: $dir")
        if (!f.isDirectory) throw java.io.IOException("target is not a directory: $dir")
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
