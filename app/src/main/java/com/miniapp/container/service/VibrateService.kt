package com.miniapp.container.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import org.json.JSONObject

/**
 * 震动服务：`sys.vibrate(duration)`。
 *
 * 需声明并审批 `vibrate` 权限（普通级）。
 * 宿主已持有系统 `android.permission.VIBRATE`（安装即授予），小程序侧仅需蜗壳审批。
 */
class VibrateService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    /** 震动 [duration] 毫秒（1~5000，超范围夹紧）。返回 boolean。 */
    suspend fun vibrate(p: JSONObject): String {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.VIBRATE
        )
        if (!granted) throw SecurityException("permission denied: vibrate")

        val duration = p.optLong("duration").coerceIn(1L, 5000L)
        return try {
            vibrator().vibrate(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
            JSONObject().put("ok", true).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", "vibrate failed").put("detail", t.message ?: "").toString()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
