package com.miniapp.container.service

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optBoolOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 闪光灯服务（手电筒）：`sys.flashlight({ on })`。
 *
 * 需声明并审批 `flashlight` 权限（普通级）；Android 6+ 首次调用会自动向系统申请
 * `CAMERA` 运行时权限（`setTorchMode` 要求持有相机权限）。
 */
class FlashlightService(
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager
) : BaseService(activity, appInfo, permissionManager) {

    /**
     * 开关手电筒：成功 `{ ok:true, on:bool }`（`on` 回显开关状态）；
     * 失败 `{ ok:false, error, detail }`。
     */
    suspend fun setTorch(p: JSONObject): String {
        requirePermission(PermissionScope.FLASHLIGHT)

        // 系统相机权限：setTorchMode 需要 CAMERA（Android 6+ 运行时申请）
        val osGranted = Build.VERSION.SDK_INT < 23 || (
            ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
        if (!osGranted) {
            val result = suspendCancellableCoroutine<Boolean> { cont ->
                activity.requestRuntimePerms(arrayOf(android.Manifest.permission.CAMERA)) { r ->
                    if (cont.isActive) cont.resume(r.values.all { it })
                }
            }
            if (!result) {
                return failJson("camera permission denied", "系统相机权限被拒绝，无法使用闪光灯")
            }
        }

        val on = p.optBoolOr("on")
        return withContext(Dispatchers.IO) {
            findTorchCamera()?.let { id ->
                runCatching {
                    val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.setTorchMode(id, on)
                    JSONObject().put("ok", true).put("on", on).toString()
                }.getOrElse {
                    failJson("torch error", it.message ?: it.javaClass.simpleName)
                }
            } ?: failJson("no camera", "设备无可用带闪光灯的后置相机")
        }
    }

    /** 找第一个有闪光灯的后置相机。 */
    private fun findTorchCamera(): String? {
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            cm.cameraIdList.firstOrNull { id ->
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val available = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                facing == CameraCharacteristics.LENS_FACING_BACK && available
            }
        } catch (_: Throwable) {
            null
        }
    }

}
