package com.miniapp.container.service

import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.util.TextEditor
import com.miniapp.container.file.FileService
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 沙箱外文件服务（单一职责）。
 *
 * - 内部储存静默读写/列目录/查信息/建删改（需 `fs.external` + 系统存储权限）
 * - SAF 导入导出（无需权限）
 * - content:// URI 读取（需 `fs.external`）
 *
 * 安全：禁止访问应用私有目录（`/data/data/...`）。
 */
class ExternalFileService(
    private val activity: MiniAppActivity,
    private val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager,
    private val fileService: FileService
) {

    // ---------- content:// URI ----------

    /** grep：返回匹配行（不修改文件）。 */
    suspend fun grepFile(absPath: String, pattern: String, regex: Boolean, ignoreCase: Boolean, invert: Boolean): String =
        withContext(Dispatchers.IO) {
            val f = File(assertExternalPath(absPath))
            if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
            val arr = org.json.JSONArray()
            TextEditor.grep(f.readText(Charsets.UTF_8), pattern, regex, ignoreCase, invert)
                .forEach { arr.put(it) }
            arr.toString()
        }

    /** sed：对内部储存文本文件应用编辑脚本，写回。 */
    suspend fun sedFile(absPath: String, script: String): String = withContext(Dispatchers.IO) {
        val f = File(assertExternalPath(absPath))
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $absPath")
        val text = f.readText(Charsets.UTF_8)
        val result = TextEditor.sed(text, script)
        f.writeText(result, Charsets.UTF_8)
        "true"
    }

    suspend fun readUri(uri: String): String = withContext(Dispatchers.IO) {
        ensurePermission()
        val parsed = Uri.parse(uri)
        val bytes = activity.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: throw java.io.IOException("无法读取: $uri")
        JSONObject.quote(IoUtil.toBase64(bytes))
    }

    // ---------- SAF 导入导出（无需权限） ----------

    /** 通过 SAF 选择文件导入到沙箱。 */
    suspend fun importViaSaf(destPath: String): String {
        if (destPath.isBlank()) throw IllegalArgumentException("destPath required")
        return suspendCancellableCoroutine { cont ->
            activity.launchImport { uri ->
                // 用户取消：返回 false（与返回类型 boolean 一致，不抛异常）
                if (uri == null) { if (cont.isActive) cont.resume("false"); return@launchImport }
                activity.lifecycleScope.launch {
                    try {
                        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: ByteArray(0)
                        fileService.writeBytes(destPath, IoUtil.toBase64(bytes))
                        if (cont.isActive) cont.resume("true")
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                }
            }
        }
    }

    /** 通过 SAF 选择位置导出沙箱文件。 */
    suspend fun exportViaSaf(path: String): String {
        if (path.isBlank()) throw IllegalArgumentException("path required")
        val bytes = IoUtil.fromBase64(fileService.readBytes(path))
        val defaultName = File(path).name
        return suspendCancellableCoroutine { cont ->
            activity.launchExport(defaultName) { uri ->
                // 用户取消：返回 false（与返回类型 boolean 一致，不抛异常）
                if (uri == null) { if (cont.isActive) cont.resume("false"); return@launchExport }
                activity.lifecycleScope.launch {
                    try {
                        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw java.io.IOException("无法写入")
                        if (cont.isActive) cont.resume("true")
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                }
            }
        }
    }

    // ---------- 内部储存静默操作 ----------

    suspend fun readFile(absPath: String): String = withContext(Dispatchers.IO) {
        val f = File(absPath)
        assertGranted(f)
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $absPath")
        JSONObject.quote(IoUtil.toBase64(IoUtil.readBytes(f)))
    }

    suspend fun writeFile(absPath: String, base64: String): String = withContext(Dispatchers.IO) {
        val f = File(absPath)
        assertGranted(f)
        f.parentFile?.mkdirs()
        IoUtil.writeBytes(f, IoUtil.fromBase64(base64))
        "true"
    }

    suspend fun list(dir: String): String = withContext(Dispatchers.IO) {
        val f = File(dir)
        assertGranted(f)
        if (!f.exists()) throw java.io.FileNotFoundException("目录不存在: $dir")
        if (!f.isDirectory) throw java.io.IOException("目标不是目录: $dir")
        val arr = JSONArray()
        f.listFiles()?.sortedBy { it.name }?.forEach {
            arr.put(JSONObject().put("name", it.name).put("isDir", it.isDirectory).put("size", it.length()))
        }
        arr.toString()
    }

    suspend fun exists(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        assertGranted(f)
        if (f.exists()) "true" else "false"
    }

    suspend fun stat(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        assertGranted(f)
        JSONObject()
            .put("exists", f.exists())
            .put("isDir", f.isDirectory)
            .put("size", if (f.isFile) f.length() else 0)
            .put("name", f.name)
            .put("canRead", f.canRead())
            .put("canWrite", f.canWrite())
            .put("lastModified", f.lastModified())
            .toString()
    }

    suspend fun mkdir(dir: String): String = withContext(Dispatchers.IO) {
        val f = File(dir)
        assertGranted(f)
        (f.exists() || f.mkdirs()).toString()
    }

    suspend fun remove(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        assertGranted(f)
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) "true" else "false"
    }

    suspend fun rename(from: String, to: String): String = withContext(Dispatchers.IO) {
        val src = File(from)
        val dst = File(to)
        assertGranted(src)
        assertGranted(dst)
        src.renameTo(dst).toString()
    }

    // ---------- 内部 ----------

    private suspend fun ensurePermission() {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, PermissionScope.FS_EXTERNAL
        )
        if (!granted) throw SecurityException("permission denied: fs.external")
    }

    /** 前置：权限 + 系统存储权限 + 路径防私有目录。 */
    private suspend fun assertGranted(f: File) {
        ensurePermission()
        ensureSystemStoragePermission()
        assertExternalPath(f)
    }

    /** 禁止访问应用私有目录（防篡改权限记录）。 */
    private fun assertExternalPath(f: File) {
        val p = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
        if (p.startsWith(activity.filesDir.canonicalPath) || p.startsWith("/data/data/")) {
            throw SecurityException("禁止访问应用私有目录: $p")
        }
    }

    /**
     * 确保系统存储权限（版本适配 + 主动申请）。
     * - Android 11+：检查 isExternalStorageManager，未开启跳设置页
     * - Android 10 及以下：主动申请 READ/WRITE_EXTERNAL_STORAGE 运行时权限
     */
    private suspend fun ensureSystemStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) return
            withContext(Dispatchers.Main) {
                try {
                    activity.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${activity.packageName}"))
                    )
                } catch (e: Exception) {
                    try {
                        activity.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    } catch (e2: Exception) { /* 无该设置项 */ }
                }
            }
            throw SecurityException("已跳转系统设置，请开启「所有文件访问权限」后重试")
        } else {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (needed.isEmpty()) return
            val result = suspendCancellableCoroutine<Map<String, Boolean>> { cont ->
                activity.requestRuntimePerms(needed.toTypedArray()) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
            if (!result.values.all { it }) throw SecurityException("存储权限被拒绝")
        }
    }
}
