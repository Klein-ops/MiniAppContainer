package com.miniapp.container.service

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
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
 * Shizuku 状态检测与权限申请。
 *
 * 进程执行已全部迁移到 UserService（[StorageUserService] 的 exec / 文件操作，
 * 官方公开 API，常驻 shell 身份进程），不再依赖内部 AIDL `IShizukuService.newProcess`。
 * 本类只保留授权流程所需的状态判断，供 [AdbService] 与存储 Shizuku 模式共用。
 */
object ShizukuShell {

    const val PKG = "moe.shizuku.privileged.api"
    private const val WAIT_MS = 2_000L      // 等待 Shizuku 推送 binder 的最长时间

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
}
