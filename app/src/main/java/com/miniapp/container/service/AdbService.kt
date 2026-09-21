package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optLongOr
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ADB / Shell 服务：小程序通过 Shizuku 执行 SH 指令（需 `adb` 权限）。
 *
 * 两层权限：
 * 1. 蜗壳 `adb` scope（PermissionManager 审批，弹窗含危险警告）
 * 2. Shizuku 自身权限（用户在 Shizuku 应用弹窗批准）
 *
 * 状态检测由 [ShizukuShell] 提供，进程执行走 [StorageUserServiceConnector]
 * 绑定的 UserService（常驻 shell 身份进程）；本类只负责「蜗壳权限 + JSON 结果格式」。
 *
 * 返回 JSON：
 * - 蜗壳权限被拒 → 抛 SecurityException("permission denied: adb")（reject）
 * - 未安装 / 未激活 / Shizuku 权限被拒 / 超时 / 执行异常 → `{ok:false, error, detail}`
 * - 成功 → `{ok:true, exitCode, stdout, stderr, timedOut:false}`
 *
 * `timeout`（毫秒）由调用方指定，未指定默认 30000，不设上限（负数=永不超时）；超时后强杀进程。
 */
class AdbService(
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager
) : BaseService(activity, appInfo, permissionManager) {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    suspend fun exec(p: JSONObject): String {
        val command = p.optStringOr("command")
        if (command.isBlank()) throw IllegalArgumentException("command 不能为空")
        // timeout：省略或 0 → 默认 30s；负数 → 永不超时；正数 → 直接采用（无上限）
        val rawTimeout = p.optLongOr("timeout", DEFAULT_TIMEOUT_MS)
            .let { if (it == 0L) DEFAULT_TIMEOUT_MS else it }
        val timeoutMs: Long? = if (rawTimeout <= 0L) null else rawTimeout

        // 1) 蜗壳 adb 权限（含危险警告弹窗）
        requirePermission(PermissionScope.ADB)

        // 2) Shizuku 状态（binder 异步推送，未激活时短暂等待，避免误报）
        when (ShizukuShell.awaitState(activity)) {
            ShizukuState.NOT_INSTALLED ->
                return failJson("shizuku not installed", "未检测到 Shizuku，请先安装并激活。")
            ShizukuState.NOT_ACTIVE ->
                return failJson(
                    "shizuku not active",
                    "Shizuku 已安装但服务未就绪。请在 Shizuku 中确认已启动，" +
                        "并确认蜗壳出现在 Shizuku 的应用列表中；首次启用后稍等片刻再试。"
                )
            ShizukuState.ACTIVE -> Unit
        }

        // 3) Shizuku 自身权限
        val shizukuGranted = try {
            ShizukuShell.hasPermission() || ShizukuShell.requestPermission()
        } catch (t: Throwable) {
            return failJson("shizuku error", t.message ?: "无法查询 Shizuku 权限状态。")
        }
        if (!shizukuGranted) {
            return failJson("shizuku permission denied", "用户未在 Shizuku 中授予权限。")
        }

        // 4) 执行（UserService 常驻进程，字节流返回，超时强杀）
        //     acquire 必须放 IO 线程：绑定回调经主线程派发，主线程阻塞会死锁
        val service = runCatching {
            withContext(Dispatchers.IO) { StorageUserServiceConnector.acquire(activity) }
        }.getOrElse { return failJson("shizuku error", it.message ?: "无法连接 UserService。") }
        val b = runCatching {
            withContext(Dispatchers.IO) { service.exec(command, null, timeoutMs ?: 0L) }
        }.getOrElse { return failJson("adb error", it.message ?: it.javaClass.simpleName) }

        val timedOut = b.getBoolean("timedOut")
        val result = ShellResult(
            ok = b.getBoolean("ok"),
            exitCode = b.getInt("exitCode"),
            stdout = b.getByteArray("stdout") ?: ByteArray(0),
            stderr = String(b.getByteArray("stderr") ?: ByteArray(0), Charsets.UTF_8),
            error = if (timedOut) "timeout" else null,
            detail = if (timedOut) "命令执行超过 ${timeoutMs ?: "∞"}ms，已强制终止。" else ""
        )

        if (!result.ok) {
            if (result.error == "timeout") {
                return JSONObject()
                    .put("ok", false)
                    .put("timedOut", true)
                    .put("error", "timeout")
                    .put("detail", result.detail)
                    .put("stdout", result.stdoutText)
                    .put("stderr", result.stderr)
                    .toString()
            }
            return failJson(result.error ?: "adb error", result.detail)
        }

        return JSONObject()
            .put("ok", true)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdoutText)
            .put("stderr", result.stderr)
            .put("timedOut", false)
            .toString()
    }
}
