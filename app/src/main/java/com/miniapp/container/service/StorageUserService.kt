package com.miniapp.container.service

import android.os.Bundle
import com.miniapp.container.IStorageUserService
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 存储 UserService：以 shell（ADB）身份运行在**独立常驻进程**，
 * 直接用 File API 操作内部储存——不再为每条命令 fork sh 进程。
 *
 * 启动方式：`Shizuku.bindUserService`（官方公开 API，替代内部 AIDL
 * `IShizukuService.newProcess`）。`daemon=false`：宿主进程死亡时服务自动停止。
 *
 * 注意：user service 进程不是完整 Android 应用进程，本服务只使用纯文件 API，
 * 不依赖 Context。可访问 /sdcard/Android（传统方式在 Android 11+ 被拦）。
 */
class StorageUserService : IStorageUserService.Stub() {

    constructor() : super()

    /** Shizuku v13 会优先使用带 Context 的构造器；本服务不需要 Context。 */
    constructor(context: android.content.Context) : super()

    override fun destroy() {
        System.exit(0)
    }

    override fun read(path: String): ByteArray {
        val f = File(path)
        if (!f.exists()) throw FileNotFoundException("文件不存在: $path")
        if (f.isDirectory) throw IOException("目标是目录: $path")
        return f.readBytes()
    }

    override fun write(path: String, data: ByteArray) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }

    override fun list(dir: String): Array<Bundle> {
        val f = File(dir)
        if (!f.exists()) throw FileNotFoundException("目录不存在: $dir")
        if (!f.isDirectory) throw IOException("目标不是目录: $dir")
        val files = f.listFiles()?.sortedBy { it.name } ?: emptyList()
        return files.map {
            Bundle().apply {
                putString("name", it.name)
                putBoolean("isDir", it.isDirectory)
                putLong("size", it.length())
            }
        }.toTypedArray()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun stat(path: String): Bundle {
        val f = File(path)
        return Bundle().apply {
            putBoolean("exists", f.exists())
            putBoolean("isDir", f.isDirectory)
            putLong("size", if (f.isFile) f.length() else 0L)
            putString("name", f.name)
            putBoolean("canRead", f.canRead())
            putBoolean("canWrite", f.canWrite())
            putLong("lastModified", f.lastModified())
        }
    }

    override fun mkdir(dir: String): Boolean {
        val f = File(dir)
        return f.exists() || f.mkdirs()
    }

    override fun remove(path: String): Boolean {
        val f = File(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))
}
