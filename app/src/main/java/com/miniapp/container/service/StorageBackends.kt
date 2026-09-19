package com.miniapp.container.service

import com.miniapp.container.util.IoUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** 目录项。 */
data class StorageEntry(val name: String, val isDir: Boolean, val size: Long)

/** 文件状态。 */
data class StorageStat(
    val exists: Boolean,
    val isDir: Boolean,
    val size: Long,
    val name: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val lastModified: Long
)

/**
 * 内部储存访问后端：把「怎么读写」与业务解耦，使 [ExternalFileService] 的
 * `fs.*External` 系列在两种授权方式下行为完全一致。
 *
 * - [DirectStorageBackend]：传统方式，直接 File IO（需系统存储权限）
 * - [ShizukuStorageBackend]：借 Shizuku/ADB 权限执行 shell，字节流直传（不经 base64）
 */
interface StorageBackend {

    /** 读取全部字节。文件不存在抛 FileNotFoundException。 */
    fun read(f: File): ByteArray

    /** 写入字节（自动建父目录）。 */
    fun write(f: File, bytes: ByteArray)

    /** 列出目录（含隐藏项，按名称排序）。目录不存在/不是目录时抛异常。 */
    fun list(dir: File): List<StorageEntry>

    fun exists(f: File): Boolean

    fun stat(f: File): StorageStat

    /** 创建目录（已存在视为成功）。 */
    fun mkdir(dir: File): Boolean

    /** 删除（目录递归）。 */
    fun remove(f: File): Boolean

    fun rename(from: File, to: File): Boolean
}

/** 传统后端：直接文件 IO。 */
class DirectStorageBackend : StorageBackend {

    override fun read(f: File): ByteArray {
        if (!f.exists()) throw FileNotFoundException("文件不存在: ${f.absolutePath}")
        if (f.isDirectory) throw IOException("目标是目录: ${f.absolutePath}")
        return IoUtil.readBytes(f)
    }

    override fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        IoUtil.writeBytes(f, bytes)
    }

    override fun list(dir: File): List<StorageEntry> {
        if (!dir.exists()) throw FileNotFoundException("目录不存在: ${dir.absolutePath}")
        if (!dir.isDirectory) throw IOException("目标不是目录: ${dir.absolutePath}")
        return dir.listFiles()?.sortedBy { it.name }?.map {
            StorageEntry(it.name, it.isDirectory, it.length())
        } ?: emptyList()
    }

    override fun exists(f: File): Boolean = f.exists()

    override fun stat(f: File): StorageStat = StorageStat(
        exists = f.exists(),
        isDir = f.isDirectory,
        size = if (f.isFile) f.length() else 0,
        name = f.name,
        canRead = f.canRead(),
        canWrite = f.canWrite(),
        lastModified = f.lastModified()
    )

    override fun mkdir(dir: File): Boolean = dir.exists() || dir.mkdirs()

    override fun remove(f: File): Boolean =
        if (f.isDirectory) f.deleteRecursively() else f.delete()

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)
}

/**
 * Shizuku 后端：以 shell（adb shell 身份）执行文件操作。
 *
 * 因 shell 走的是进程管道（ParcelFileDescriptor 字节流），**读写不经 base64**，
 * 不产生体积膨胀。优势：Android 11+ 亦能访问 `sdcard/Android`（传统方式被 scoped
 * storage 拦住）。
 */
class ShizukuStorageBackend : StorageBackend {

    private fun sh(command: String, stdin: ByteArray? = null): ShellResult =
        ShizukuShell.exec(command, stdin)

    private fun q(s: String): String = ShizukuShell.quote(s)

    override fun read(f: File): ByteArray {
        if (!exists(f)) throw FileNotFoundException("文件不存在: ${f.absolutePath}")
        val r = sh("cat -- ${q(f.absolutePath)}")
        if (!r.ok) throw IOException(r.detail)
        if (r.exitCode != 0) throw IOException(r.stderr.trim().ifBlank { "读取失败: ${f.absolutePath}" })
        return r.stdout
    }

    override fun write(f: File, bytes: ByteArray) {
        val parent = f.parentFile
        if (parent != null) sh("mkdir -p ${q(parent.absolutePath)}")
        val r = sh("cat > ${q(f.absolutePath)}", stdin = bytes)
        if (!r.ok) throw IOException(r.detail)
        if (r.exitCode != 0) throw IOException(r.stderr.trim().ifBlank { "写入失败: ${f.absolutePath}" })
    }

    override fun list(dir: File): List<StorageEntry> {
        if (!exists(dir)) throw FileNotFoundException("目录不存在: ${dir.absolutePath}")
        if (!stat(dir).isDir) throw IOException("目标不是目录: ${dir.absolutePath}")
        // 逐项输出 "类型<TAB>大小<TAB>名称"，含隐藏项
        val script = buildString {
            append("cd ")
            append(q(dir.absolutePath))
            append(" 2>/dev/null || exit 1; ")
            append("ls -A | while IFS= read -r n; do ")
            append("[ -e \"${'$'}n\" ] || continue; ")
            append("if [ -d \"${'$'}n\" ]; then t=d; else t=f; fi; ")
            append("s=${'$'}(stat -c %s \"${'$'}n\" 2>/dev/null || echo 0); ")
            append("printf '%s\\t%s\\t%s\\n' \"${'$'}t\" \"${'$'}s\" \"${'$'}n\"; done")
        }
        val r = sh(script)
        if (!r.ok) throw IOException(r.detail)
        if (r.exitCode != 0) throw IOException("目录不可访问: ${dir.absolutePath}")
        val out = ArrayList<StorageEntry>()
        for (line in r.stdoutText.split("\n")) {
            if (line.isEmpty()) continue
            val parts = line.split("\t")
            if (parts.size < 3) continue
            val type = parts[0]
            val size = parts[1].toLongOrNull() ?: 0L
            val name = parts.subList(2, parts.size).joinToString("\t")
            out.add(StorageEntry(name, type == "d", size))
        }
        return out.sortedBy { it.name }
    }

    override fun exists(f: File): Boolean {
        val r = sh("[ -e ${q(f.absolutePath)} ] && echo 1 || echo 0")
        return r.ok && r.stdoutText.trim() == "1"
    }

    override fun stat(f: File): StorageStat {
        val p = q(f.absolutePath)
        val script = "if [ -e $p ]; then " +
            "sz=\$(stat -c %s $p 2>/dev/null || echo 0); " +
            "if [ -d $p ]; then d=1; else d=0; fi; " +
            "[ -r $p ] && r=1 || r=0; " +
            "[ -w $p ] && w=1 || w=0; " +
            "mt=\$(stat -c %Y $p 2>/dev/null || echo 0); " +
            "echo \"1|\$d|\$sz|\$r\$w|\$mt\"; else echo \"0|0|0|00|0\"; fi"
        val r = sh(script)
        if (!r.ok) throw IOException(r.detail)
        val parts = r.stdoutText.trim().split("|")
        if (parts.size < 5 || parts[0] != "1") {
            return StorageStat(false, false, 0, f.name, false, false, 0)
        }
        val rw = parts[3]
        return StorageStat(
            exists = true,
            isDir = parts[1] == "1",
            size = parts[2].toLongOrNull() ?: 0L,
            name = f.name,
            canRead = rw.startsWith("1"),
            canWrite = rw.length > 1 && rw[1] == '1',
            lastModified = (parts[4].toLongOrNull() ?: 0L) * 1000L
        )
    }

    override fun mkdir(dir: File): Boolean {
        val p = q(dir.absolutePath)
        val r = sh("mkdir -p $p; [ -d $p ] && echo 1 || echo 0")
        return r.ok && r.stdoutText.trim() == "1"
    }

    override fun remove(f: File): Boolean {
        val p = q(f.absolutePath)
        val r = sh("rm -rf $p; [ -e $p ] && echo 0 || echo 1")
        return r.ok && r.stdoutText.trim() == "1"
    }

    override fun rename(from: File, to: File): Boolean {
        val r = sh("mv ${q(from.absolutePath)} ${q(to.absolutePath)} && echo 1 || echo 0")
        return r.ok && r.stdoutText.trim() == "1"
    }
}
