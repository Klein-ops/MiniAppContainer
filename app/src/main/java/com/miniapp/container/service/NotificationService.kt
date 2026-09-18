package com.miniapp.container.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.miniapp.container.R
import com.miniapp.container.util.toast
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * 通知服务（单一职责）：发送/取消状态栏通知，需 `notification` 权限。
 * 通知标题以 `[小程序名]` 标注来源；每个小程序独立通知渠道。
 */
class NotificationService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    private fun label(): String = appInfo.displayName.ifBlank { appInfo.uname }

    suspend fun show(title: String, body: String): String = withContext(Dispatchers.Main) {
        ensurePermission()

        // 系统通知总开关检测（国产 ROM 常见：即使 Android 13 运行时授权被通过，
        // 系统设置里的总开关也可能是关的，此时发通知无效）。
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            activity.toast("通知权限已关闭，跳转系统设置打开…")
            activity.openNotificationSettings()
            return@withContext JSONObject()
                .put("ok", false)
                .put("error", "notifications disabled")
                .toString()
        }

        val nm = activity.getSystemService(NotificationManager::class.java)
        val channelId = appInfo.appKey   // 渠道名 = uid_uname，便于卸载时清理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, label(), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(activity, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("[${label()}] $title")
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(appInfo.appKey.hashCode(), notif)
        "true"
    }

    suspend fun cancel(): String = withContext(Dispatchers.Main) {
        val nm = activity.getSystemService(NotificationManager::class.java)
        nm.cancel(appInfo.appKey.hashCode())
        "true"
    }

    /** 蜗壳审批 + Android 13+ 运行时通知权限。 */
    private suspend fun ensurePermission() {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.NOTIFICATION
        )
        if (!granted) throw SecurityException("permission denied: notification")
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val result = suspendCancellableCoroutine<Map<String, Boolean>> { cont ->
                activity.requestRuntimePerms(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
            if (!result.values.all { it }) throw SecurityException("通知权限被拒绝")
        }
    }
}
