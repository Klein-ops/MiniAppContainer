package com.miniapp.container.service

import android.os.Bundle
import com.miniapp.container.IStorageUserService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 存储 UserService：以 shell（ADB）身份运行在**独立常驻进程**，
 * 直接用 File API 操作内部储存——不再为每条命令 fork sh 进程。
 *
 * 启动方式：`Shizuku.bindUserService`（官方公开 API）。`daemon=false`：
 * 宿主进程死亡时服务自动停止。
 *
 * 注意：user service 进程不是完整 Android 应用进程，本服务只使用纯文件 API，
 * 不依赖 Context。可访问 /sdcard/Android（传统方式在 Android 11+ 被拦）。
 */
class StorageUserService : IStorageUserService.Stub() {

    // 不写任何显式构造器：Kotlin 隐式无参主构造器即 Shizuku 反射所需的
    // 默认构造器（getConstructor()）。带 Context 的构造器是可选的（v13 优先
    // 尝试、找不到则回退默认），本服务不需要 Context。

    override fun destroy() {
        System.exit(0)
    }

    override fun read(path: String): ByteArray {
        val f = File(path)
        if (!f.exists()) throw SecurityException("文件不存在: $path")
        if (f.isDirectory) throw SecurityException("目标是目录: $path")
        return f.readBytes()
    }

    override fun write(path: String, data: ByteArray) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
    }

    override fun list(dir: String): Array<Bundle> {
        val f = File(dir)
        if (!f.exists()) throw SecurityException("目录不存在: $dir")
        if (!f.isDirectory) throw SecurityException("目标不是目录: $dir")
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

    override fun exec(command: String, stdin: ByteArray?, timeoutMs: Long): Bundle {
        val proc = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (t: Throwable) {
            throw SecurityException("无法启动 sh: ${t.message}")
        }

        // 并发读 stdout/stderr（字节），防止管道写满导致 sh 阻塞
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val latch = CountDownLatch(2)
        thread(isDaemon = true) {
            try { proc.inputStream.use { it.copyTo(out) } } catch (_: Throwable) {} finally { latch.countDown() }
        }
        thread(isDaemon = true) {
            try { proc.errorStream.use { it.copyTo(err) } } catch (_: Throwable) {} finally { latch.countDown() }
        }
        if (stdin != null) {
            thread(isDaemon = true) {
                try { proc.outputStream.use { it.write(stdin); it.flush() } } catch (_: Throwable) {}
            }
        } else {
            runCatching { proc.outputStream.close() }
        }

        val finished = if (timeoutMs <= 0L) {
            runCatching { proc.waitFor() }.isSuccess
        } else {
            runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            latch.await(2, TimeUnit.SECONDS)
            return Bundle().apply {
                putBoolean("ok", false)
                putBoolean("timedOut", true)
                putInt("exitCode", -1)
                putByteArray("stdout", out.toByteArray())
                putByteArray("stderr", err.toByteArray())
            }
        }

        latch.await(2, TimeUnit.SECONDS)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        runCatching { proc.destroy() }
        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean("timedOut", false)
            putInt("exitCode", code)
            putByteArray("stdout", out.toByteArray())
            putByteArray("stderr", err.toByteArray())
        }
    }
}
