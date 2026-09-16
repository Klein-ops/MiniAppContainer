package com.miniapp.container.service

import android.content.pm.PackageManager
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * ADB / Shell 服务：小程序通过 Shizuku 执行 SH 指令（需 `adb` 权限）。
 *
 * 两层权限：
 * 1. 蜗壳 `adb` scope（PermissionManager 审批，弹窗含危险警告）
 * 2. Shizuku 自身权限（用户在 Shizuku 应用弹窗批准）
 *
 * 返回结果（JSON）：
 * - 蜗壳权限被拒 → 抛 `SecurityException("permission denied: adb")`（Promise reject）
 * - Shizuku 未激活 → `{ ok: false, error: "shizuku not active" }`
 * - Shizuku 权限被拒 → `{ ok: false, error: "shizuku permission denied" }`
 * - 成功 → `{ ok: true, exitCode: number, stdout: string, stderr: string }`
 */
class AdbService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    suspend fun exec(p: JSONObject): String {
        val command = p.optStringOr("command")
        if (command.isBlank()) throw IllegalArgumentException("command 不能为空")

        // 1) 蜗壳 adb 权限（含危险警告弹窗）
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.ADB
        )
        if (!granted) throw SecurityException("permission denied: adb")

        // 2) Shizuku 是否激活
        if (!isShizukuActive()) {
            return fail("shizuku not active", "请先启动 Shizuku 并激活服务。")
        }

        // 3) Shizuku 自身权限
        val shizukuGranted = try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) true
            else ensureShizukuPermission()
        } catch (t: Throwable) {
            return fail("shizuku error", t.message ?: "无法查询 Shizuku 权限状态。")
        }
        if (!shizukuGranted) return fail("shizuku permission denied", "用户未授予 Shizuku 权限。")

        // 4) 执行
        return runCatching {
            withContext(Dispatchers.IO) {
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                try {
                    val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                    val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                    val code = proc.waitFor()
                    JSONObject()
                        .put("ok", true)
                        .put("exitCode", code)
                        .put("stdout", stdout)
                        .put("stderr", stderr)
                        .toString()
                } finally {
                    proc.destroy()
                }
            }
        }.getOrElse { fail("adb error", it.message ?: it.javaClass.simpleName) }
    }

    private fun isShizukuActive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 请求 Shizuku 权限并等待结果（Shizuku 应用弹窗）。 */
    private suspend fun ensureShizukuPermission(): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeOnRequestPermissionResultListener(this)
                if (cont.isActive) {
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addOnRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(0)
        } catch (t: Throwable) {
            Shizuku.removeOnRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { Shizuku.removeOnRequestPermissionResultListener(listener) }
    }

    private fun fail(error: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("detail", detail)
        .toString()
}
