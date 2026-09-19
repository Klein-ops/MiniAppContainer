package com.miniapp.container.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 剪贴板服务（单一职责）：读写系统剪贴板，需 `clipboard` 权限。 */
class ClipboardService(
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager
) : BaseService(activity, appInfo, permissionManager) {

    suspend fun read(): String = withContext(Dispatchers.Main) {
        ensurePermission()
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        JSONObject.quote(cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
    }

    suspend fun write(text: String): String = withContext(Dispatchers.Main) {
        ensurePermission()
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("miniapp", text))
        "true"
    }

    private suspend fun ensurePermission() = requirePermission(PermissionScope.CLIPBOARD)
}
