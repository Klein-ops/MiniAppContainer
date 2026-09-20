package com.miniapp.container.service

import android.content.Context
import android.os.Bundle
import com.miniapp.container.IStorageUserService
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
/**
 * Shizuku 后端：通过 [StorageUserServiceConnector] 绑定 UserService，
 * 由常驻的 shell 身份进程直接用 File API 读写（不再 fork sh 进程）。
 *
 * 优势：Android 11+ 亦能访问 `sdcard/Android`（传统方式被 scoped storage 拦住）；
 * 首次调用含绑定开销（数百毫秒），此后为毫秒级 binder IPC。
 */
class ShizukuStorageBackend(private val context: Context) : StorageBackend {

    private val service: IStorageUserService
        get() = StorageUserServiceConnector.acquire(context)

    override fun read(f: File): ByteArray = service.read(f.absolutePath)

    override fun write(f: File, bytes: ByteArray) {
        service.write(f.absolutePath, bytes)
    }

    override fun list(dir: File): List<StorageEntry> =
        service.list(dir.absolutePath).map {
            StorageEntry(
                name = it.getString("name") ?: "",
                isDir = it.getBoolean("isDir"),
                size = it.getLong("size")
            )
        }

    override fun exists(f: File): Boolean = service.exists(f.absolutePath)

    override fun stat(f: File): StorageStat {
        val b = service.stat(f.absolutePath)
        return StorageStat(
            exists = b.getBoolean("exists"),
            isDir = b.getBoolean("isDir"),
            size = b.getLong("size"),
            name = b.getString("name") ?: f.name,
            canRead = b.getBoolean("canRead"),
            canWrite = b.getBoolean("canWrite"),
            lastModified = b.getLong("lastModified")
        )
    }

    override fun mkdir(dir: File): Boolean = service.mkdir(dir.absolutePath)

    override fun remove(f: File): Boolean = service.remove(f.absolutePath)

    override fun rename(from: File, to: File): Boolean =
        service.rename(from.absolutePath, to.absolutePath)
}
