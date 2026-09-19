package com.miniapp.container.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Shizuku 服务状态。 */
enum class ShizukuState { NOT_INSTALLED, NOT_ACTIVE, ACTIVE }

/** 命令执行结果（stdout 为原始字节，避免二进制经编码膨胀）。 */
data class ShellResult(
    val ok: Boolean,
    val exitCode: Int = -1,
    val stdout: ByteArray = ByteArray(0),
    val stderr: String = "",
    val error: String? = null,
    val detail: String = ""
) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)

    /** 失败时抛出带 stderr 的 IOException，成功则返回 stdout。 */
    fun requireStdout(): ByteArray {
        if (!ok) throw java.io.IOException(detail.ifBlank { error ?: "shell 执行失败" })
        if (exitCode != 0) {
            val msg = stderr.trim().ifBlank { "exitCode=$exitCode" }
            throw java.io.IOException(msg)
        }
        return stdout
    }
}

/**
 * Shizuku 内核：状态检测、权限申请、shell 执行。
 *
 * 由 [AdbService]（小程序 adb.exec）与存储的 Shizuku 模式（[ShizukuStorageBackend]）共用，
 * 避免两处各写一套 Shizuku 状态判断。
 *
 * 注意：Shizuku 官方无公开 shell 执行 API，此处直接调用其内部 AIDL
 * `IShizukuService.newProcess`，**依赖内部接口**，大版本升级可能失效。
 */
object ShizukuShell {

    const val PKG = "moe.shizuku.privileged.api"
    private const val WAIT_MS = 2_000L      // 等待 Shizuku 推送 binder 的最长时间
    private const val READ_JOIN_MS = 2_000L // 读线程收尾等待

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 单次检测：未安装 / 已安装未激活 / 已激活。 */
    fun state(context: Context): ShizukuState = try {
        if (!isInstalled(context)) ShizukuState.NOT_INSTALLED
        else if (Shizuku.pingBinder()) ShizukuState.ACTIVE
        else ShizukuState.NOT_ACTIVE
    } catch (_: Throwable) {
        ShizukuState.NOT_ACTIVE
    }

    /**
     * 检测状态，未激活时轮询等待（最多 [WAIT_MS]）。
     * Shizuku 服务端推送 binder 是异步的，刚启动直接判定会误报"未激活"。
     */
    suspend fun awaitState(context: Context): ShizukuState {
        var st = state(context)
        if (st == ShizukuState.NOT_INSTALLED || st == ShizukuState.ACTIVE) return st
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(120)
            st = state(context)
            if (st == ShizukuState.ACTIVE) return st
        }
        return st
    }

    /** Shizuku 自身权限是否已授予。 */
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 请求 Shizuku 权限（Shizuku 应用弹窗）并等待结果。 */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(0)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /** 是否"已激活且已授权"，可直接执行命令；不弹任何授权。 */
    fun ready(context: Context): Boolean =
        state(context) == ShizukuState.ACTIVE && hasPermission()

    /**
     * 执行 shell 命令（阻塞，请在 IO 线程调用）。
     *
     * @param stdin 需要喂入进程的原始字节（如写文件内容），null 表示不写。
     * @param timeoutMs null = 永不超时。
     */
    fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long? = 60_000L): ShellResult {
        val binder = Shizuku.getBinder()
            ?: return ShellResult(ok = false, error = "shizuku not active", detail = "Shizuku 服务未就绪。")
        val service = try {
            IShizukuService.Stub.asInterface(binder)
        } catch (t: Throwable) {
            return ShellResult(ok = false, error = "shizuku error", detail = t.message ?: "无法访问 Shizuku 服务。")
        }
        val proc = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (t: Throwable) {
            return ShellResult(ok = false, error = "shizuku error", detail = t.message ?: "无法创建进程。")
        } ?: return ShellResult(ok = false, error = "shizuku error", detail = "Shizuku 返回空进程。")

        // 并发读 stdout(字节)/stderr(文本)，避免管道写满导致死锁
        val outRef = AtomicReference(ByteArray(0))
        val errRef = AtomicReference("")
        val latch = CountDownLatch(2)
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).use { outRef.set(it.readBytes()) }
            } catch (_: Throwable) {
                // 进程被强杀时可能抛异常，忽略（已有部分内容或空）
            } finally {
                latch.countDown()
            }
        }.apply { isDaemon = true }.start()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                    .bufferedReader().use { errRef.set(it.readText()) }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }.apply { isDaemon = true }.start()

        // 需要时把 stdin 字节喂给进程（写文件走这条路径，不经任何编码）
        if (stdin != null) {
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(proc.outputStream).use { os ->
                        os.write(stdin)
                        os.flush()
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true }.start()
        } else {
            runCatching { proc.outputStream?.close() }
        }

        val finished = waitFor(proc, timeoutMs)
        if (!finished) {
            runCatching { proc.destroy() }
            latch.await(READ_JOIN_MS, TimeUnit.MILLISECONDS)
            return ShellResult(
                ok = false,
                stdout = outRef.get(),
                stderr = errRef.get(),
                error = "timeout",
                detail = "命令执行超过 ${timeoutMs ?: "∞"}ms，已强制终止。"
            )
        }

        latch.await(READ_JOIN_MS, TimeUnit.MILLISECONDS)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        runCatching { proc.destroy() }
        return ShellResult(ok = true, exitCode = code, stdout = outRef.get(), stderr = errRef.get())
    }

    /** 轮询等待进程结束；timeoutMs 为 null 时无限等待。超时返回 false。 */
    private fun waitFor(proc: IRemoteProcess, timeoutMs: Long?): Boolean {
        if (timeoutMs == null) {
            while (runCatching { proc.alive() }.getOrDefault(false)) Thread.sleep(40)
            return true
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!runCatching { proc.alive() }.getOrDefault(false)) return true
            Thread.sleep(40)
        }
        return !runCatching { proc.alive() }.getOrDefault(false)
    }

    /** shell 单引号转义（防路径注入）。 */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
