package com.miniapp.container.service

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optLongOr
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IRemoteProcess
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Shizuku 服务状态。 */
enum class ShizukuState { NOT_INSTALLED, NOT_ACTIVE, ACTIVE }

/**
 * ADB / Shell 服务：小程序通过 Shizuku 执行 SH 指令（需 `adb` 权限）。
 *
 * 两层权限：
 * 1. 蜗壳 `adb` scope（PermissionManager 审批，弹窗含危险警告）
 * 2. Shizuku 自身权限（用户在 Shizuku 应用弹窗批准）
 *
 * 注意：Shizuku 官方无公开的 shell 执行 API（`Shizuku.newProcess` 自 API 13 起
 * 为 private 并计划于 API 14 移除，`bindUserService` 又要求小程序提供 Service
 * 组件）。此处直接调用其内部 AIDL `IShizukuService.newProcess`。**依赖内部接口**，
 * Shizuku 大版本升级可能失效。
 *
 * 返回 JSON：
 * - 蜗壳权限被拒 → 抛 SecurityException("permission denied: adb")（reject）
 * - 未安装 / 未激活 / Shizuku 权限被拒 / 超时 / 执行异常 → `{ok:false, error, detail}`
 * - 成功 → `{ok:true, exitCode, stdout, stderr, timedOut:false}`
 *
 * `timeout`（毫秒）由调用方指定，未指定默认 30000；超时后强杀进程。
 */
class AdbService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 600_000L   // 最长 10 分钟
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val WAIT_MS = 2_000L   // 等待 Shizuku 推送 binder 的最长时间
    }

    suspend fun exec(p: JSONObject): String {
        val command = p.optStringOr("command")
        if (command.isBlank()) throw IllegalArgumentException("command 不能为空")
        // timeout ≤ 0（或省略时传 0）= 永不超时；否则夹紧 1s~10min
        val rawTimeout = p.optLongOr("timeout", DEFAULT_TIMEOUT_MS)
            .let { if (it == 0L) DEFAULT_TIMEOUT_MS else it }
        val timeoutMs: Long? = if (rawTimeout <= 0L) null
        else rawTimeout.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        // 1) 蜗壳 adb 权限（含危险警告弹窗）
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.ADB
        )
        if (!granted) throw SecurityException("permission denied: adb")

        // 2) Shizuku 状态
        // 注意：binder 由 Shizuku 服务端主动推送（经 ShizukuProvider），是异步的。
        // 应用刚启动时可能尚未到达，故未激活时短暂等待再判定，避免误报。
        when (awaitShizukuState()) {
            ShizukuState.NOT_INSTALLED ->
                return fail("shizuku not installed", "未检测到 Shizuku，请先安装并激活。")
            ShizukuState.NOT_ACTIVE ->
                return fail(
                    "shizuku not active",
                    "Shizuku 已安装但服务未就绪。请在 Shizuku 中确认已启动，" +
                        "并确认蜗壳出现在 Shizuku 的应用列表中；首次启用后稍等片刻再试。"
                )
            ShizukuState.ACTIVE -> Unit
        }

        // 3) Shizuku 自身权限
        val shizukuGranted = try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) true
            else ensureShizukuPermission()
        } catch (t: Throwable) {
            return fail("shizuku error", t.message ?: "无法查询 Shizuku 权限状态。")
        }
        if (!shizukuGranted) {
            return fail("shizuku permission denied", "用户未在 Shizuku 中授予权限。")
        }

        // 4) 执行（并发读 stdout/stderr，防管道缓冲死锁；带超时）
        return runCatching {
            withContext(Dispatchers.IO) { runCommand(command, timeoutMs) }
        }.getOrElse { fail("adb error", it.message ?: it.javaClass.simpleName) }
    }

    /** 执行命令：并发读取两个流，超时强杀。timeoutMs 为 null 表示永不超时。 */
    private fun runCommand(command: String, timeoutMs: Long?): String {
        val binder = Shizuku.getBinder()
            ?: return fail("shizuku not active", "Shizuku 服务未就绪。")
        val service = IShizukuService.Stub.asInterface(binder)
        val proc = service.newProcess(arrayOf("sh", "-c", command), null, null)
            ?: return fail("adb error", "Shizuku 返回空进程。")

        // 并发读，避免 stderr 写满管道导致死锁
        val outRef = AtomicReference("")
        val errRef = AtomicReference("")
        val latch = CountDownLatch(2)
        val reader = { fd: ParcelFileDescriptor?, sink: AtomicReference<String> ->
            Thread {
                try {
                    sink.set(
                        ParcelFileDescriptor.AutoCloseInputStream(fd)
                            .bufferedReader().use { it.readText() }
                    )
                } catch (_: Throwable) {
                    // 进程被强杀时读流可能抛异常，忽略（已有部分内容或空）
                } finally {
                    latch.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        reader(proc.inputStream, outRef)
        reader(proc.errorStream, errRef)

        val finished = waitFor(proc, timeoutMs)
        if (!finished) {
            runCatching { proc.destroy() }   // 超时强杀
            latch.await(1, TimeUnit.SECONDS) // 给读线程一点收尾时间
            return JSONObject()
                .put("ok", false)
                .put("timedOut", true)
                .put("error", "timeout")
                .put("detail", "命令执行超过 ${timeoutMs ?: "∞"}ms，已强制终止。")
                .put("stdout", outRef.get())
                .put("stderr", errRef.get())
                .toString()
        }

        latch.await(2, TimeUnit.SECONDS)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        runCatching { proc.destroy() }
        return JSONObject()
            .put("ok", true)
            .put("exitCode", code)
            .put("stdout", outRef.get())
            .put("stderr", errRef.get())
            .put("timedOut", false)
            .toString()
    }

    /** 轮询等待进程结束；timeoutMs 为 null 时无限等待。超时返回 false。 */
    private fun waitFor(proc: IRemoteProcess, timeoutMs: Long?): Boolean {
        if (timeoutMs == null) {
            // 永不超时：一直等到进程自行结束
            while (runCatching { proc.alive() }.getOrDefault(false)) Thread.sleep(40)
            return true
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { proc.alive() }.getOrDefault(false)
            if (!alive) return true
            Thread.sleep(40)
        }
        return !runCatching { proc.alive() }.getOrDefault(false)
    }

    /**
     * 检测 Shizuku 状态；若暂未激活则轮询等待（最多 [WAIT_MS]）。
     * Shizuku 服务端推送 binder 是异步的，刚启动时直接判定会误报"未激活"。
     */
    private suspend fun awaitShizukuState(): ShizukuState {
        var st = shizukuState()
        if (st == ShizukuState.NOT_INSTALLED || st == ShizukuState.ACTIVE) return st
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(120)
            st = shizukuState()
            if (st == ShizukuState.ACTIVE) return st
        }
        return st
    }

    /** 检测 Shizuku 状态：未安装 / 已安装未激活 / 已激活。 */
    private fun shizukuState(): ShizukuState = try {
        if (!isShizukuInstalled()) ShizukuState.NOT_INSTALLED
        else if (Shizuku.pingBinder()) ShizukuState.ACTIVE
        else ShizukuState.NOT_ACTIVE
    } catch (_: Throwable) {
        ShizukuState.NOT_ACTIVE
    }

    private fun isShizukuInstalled(): Boolean = try {
        activity.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 请求 Shizuku 权限并等待结果（Shizuku 应用弹窗）。 */
    private suspend fun ensureShizukuPermission(): Boolean = suspendCancellableCoroutine { cont ->
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

    private fun fail(error: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("detail", detail)
        .toString()
}
