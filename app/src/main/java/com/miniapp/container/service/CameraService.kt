package com.miniapp.container.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.core.PathGuard
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optBoolOr
import com.miniapp.container.util.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 相机能力：拍照（`camera.takePhoto`）+ 闪光灯/手电筒（`sys.flashlight`）。
 *
 * 拍照走**系统相机 Intent**（宿主不直接持有相机），照片经 FileProvider 输出到
 * 沙箱数据目录（`data/` 或 `tmp/`），完全由小程序管理。
 *
 * 闪光灯直接使用 `CameraManager.setTorchMode`，需要宿主持有 `CAMERA` 权限
 * （运行时权限会在首次调用时自动请求，Android 6+）。
 */
class CameraService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager,
    private val sandboxRoot: File
) {

    // ==================== 拍照 ====================

    /**
     * 调用系统相机拍照。
     *
     * `path`（相对沙箱，须在 `data/` 或 `tmp/`，可省略）：照片保存位置；
     * **省略时自动保存到 `tmp/photo_<时间戳>.jpg`**（临时区，符合
     * "小程序只拿到拍好的图片" 的场景）。
     *
     * 成功：`{ ok: true, path: "data/photo_xxx.jpg" }`
     * 取消：`{ ok: false, error: "cancelled" }`
     */
    suspend fun takePhoto(p: JSONObject): String {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.CAMERA
        )
        if (!granted) throw SecurityException("permission denied: camera")
        if (activity.isFinishing) return fail("activity closed")

        val dest = p.optStringOr("path")
        val target: File = if (dest.isNotBlank()) {
            resolveWritable(dest)
                ?: return fail("invalid path", "目标路径仅允许 data/ 或 tmp/")
        } else {
            File(activity.cacheDir, "camera/photo_${System.currentTimeMillis()}.jpg")
        }

        return suspendCancellableCoroutine { cont ->
            val uri = uriFor(target)
            activity.launchTakePhoto(uri) { ok ->
                if (!cont.isActive) return@launchTakePhoto
                if (!ok) {
                    cont.resume(fail("cancelled"))
                    return@launchTakePhoto
                }
                // 自动生成路径时：把照片复制进沙箱 tmp/（临时区）
                val finalFile = if (dest.isNotBlank()) target else {
                    val f = File(sandboxRoot, "tmp/photo_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        f.parentFile?.mkdirs()
                        target.copyTo(f, overwrite = true)
                    }.getOrNull()
                }
                if (finalFile == null) {
                    cont.resume(fail("save failed", "照片保存失败"))
                } else {
                    val rel = finalFile.relativeTo(sandboxRoot).path
                    cont.resume(JSONObject().put("ok", true).put("path", rel).toString())
                }
            }
        }
    }

    // ==================== 闪光灯 ====================

    /** 开关手电筒：`sys.flashlight({ on: true|false })`。 */
    suspend fun setTorch(p: JSONObject): String {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.FLASHLIGHT
        )
        if (!granted) throw SecurityException("permission denied: flashlight")

        val on = p.optBoolOr("on")

        // 宿主 CAMERA 权限：危险权限，Android 6+ 需运行时授予
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val grantedCamera = requestCameraRuntime()
            if (!grantedCamera) return fail("permission denied: camera", "需要相机权限才能控制闪光灯")
        }

        return withContext(Dispatchers.IO) {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findTorchCamera(cm)
            if (cameraId == null) {
                fail("no camera", "未找到可用的后置相机")
            } else {
                try {
                    cm.setTorchMode(cameraId, on)
                    JSONObject().put("ok", true).put("on", on).toString()
                } catch (t: Throwable) {
                    fail("torch error", t.message ?: "无法切换闪光灯")
                }
            }
        }
    }

    // ==================== 内部 ====================

    /** 目标必须在沙箱的 data/ 或 tmp/ 内（与文件系统白名单一致）。 */
    private fun resolveWritable(dest: String): File? = try {
        val f = PathGuard.resolveUnderRoot(sandboxRoot, dest)
        val rel = f.relativeTo(sandboxRoot).path
        if (rel.startsWith("data/") || rel.startsWith("tmp/")) f else null
    } catch (_: Throwable) {
        null
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

    private suspend fun requestCameraRuntime(): Boolean = suspendCancellableCoroutine { cont ->
        activity.requestRuntimePerms(arrayOf(Manifest.permission.CAMERA)) { result ->
            if (cont.isActive) cont.resume(result[Manifest.permission.CAMERA] == true)
        }
    }

    /** 查找支持手电筒的后置相机 id。 */
    private fun findTorchCamera(cm: CameraManager): String? {
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: return null
        for (id in ids) {
            if (!runCatching { cm.getCameraCharacteristics(id) }.isSuccess) continue
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) return id
            // 找不到后置时退而求其次：任何有闪光灯的
        }
        for (id in ids) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) return id
        }
        return null
    }

    private fun fail(error: String, detail: String = ""): String = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("detail", detail)
        .toString()
}
