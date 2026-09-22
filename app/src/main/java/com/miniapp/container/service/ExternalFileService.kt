package com.miniapp.container.service

import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.core.MatchMode
import com.miniapp.container.core.PathRule
import com.miniapp.container.core.RuleType
import com.miniapp.container.core.StorageAccessConfig
import com.miniapp.container.core.StorageMode
import com.miniapp.container.util.TextEditor
import com.miniapp.container.file.FileService
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.IoUtil
import com.miniapp.container.util.SafIo
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
    activity: MiniAppActivity,
    appInfo: MiniAppInfo,
    permissionManager: PermissionManager,
    private val fileService: FileService
) : BaseService(activity, appInfo, permissionManager) {

    // ---------- content:// URI ----------

    /** grep：返回匹配行（不修改文件）。 */
    suspend fun grepFile(absPath: String, pattern: String, regex: Boolean, ignoreCase: Boolean, invert: Boolean): String =
        withContext(Dispatchers.IO) {
            val f = File(absPath)
            assertExternalPath(f)
            if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
            val arr = org.json.JSONArray()
            TextEditor.grep(f.readText(Charsets.UTF_8), pattern, regex, ignoreCase, invert)
                .forEach { arr.put(it) }
            arr.toString()
        }

    /** sed：对内部储存文本文件应用编辑脚本，写回。 */
    suspend fun sedFile(absPath: String, script: String): String = withContext(Dispatchers.IO) {
        val f = File(absPath)
        assertExternalPath(f)
        if (!f.exists()) throw java.io.FileNotFoundException("文件不存在: $absPath")
        if (f.isDirectory) throw java.io.IOException("目标是目录: $absPath")
        val text = f.readText(Charsets.UTF_8)
        val result = TextEditor.sed(text, script)
        f.writeText(result, Charsets.UTF_8)
        "true"
    }

    suspend fun readUri(uri: String): String = withContext(Dispatchers.IO) {
        ensurePermission()
        val bytes = SafIo.readBytes(activity, Uri.parse(uri))
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
                        fileService.writeBytes(destPath, IoUtil.toBase64(SafIo.readBytes(activity, uri)))
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
                        SafIo.writeBytes(activity, uri, bytes)
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
        JSONObject.quote(IoUtil.toBase64(backend(f).read(f)))
    }

    suspend fun writeFile(absPath: String, base64: String): String = withContext(Dispatchers.IO) {
        val f = File(absPath)
        backend(f).write(f, IoUtil.fromBase64(base64))
        "true"
    }

    suspend fun list(dir: String): String = withContext(Dispatchers.IO) {
        val f = File(dir)
        val arr = JSONArray()
        backend(f).list(f).forEach {
            arr.put(JSONObject().put("name", it.name).put("isDir", it.isDir).put("size", it.size))
        }
        arr.toString()
    }

    suspend fun exists(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        backend(f).exists(f).toString()
    }

    suspend fun stat(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        val st = backend(f).stat(f)
        JSONObject()
            .put("exists", st.exists)
            .put("isDir", st.isDir)
            .put("size", st.size)
            .put("name", st.name)
            .put("canRead", st.canRead)
            .put("canWrite", st.canWrite)
            .put("lastModified", st.lastModified)
            .toString()
    }

    suspend fun mkdir(dir: String): String = withContext(Dispatchers.IO) {
        val f = File(dir)
        backend(f).mkdir(f).toString()
    }

    suspend fun remove(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        backend(f).remove(f).toString()
    }

    suspend fun rename(from: String, to: String): String = withContext(Dispatchers.IO) {
        val src = File(from)
        val dst = File(to)
        backend(src, dst).rename(src, dst).toString()
    }

    // ---------- 内部 ----------

    private suspend fun ensurePermission() = requirePermission(PermissionScope.FS_EXTERNAL)

    /**
     * 前置检查并选择后端：
     * - 蜗壳 `fs.external` 权限（必查）
     * - 路径不得落在应用私有目录
     * - Shizuku 模式且可用 → [ShizukuStorageBackend]
     * - 否则（默认 / Shizuku 不可用自动回退）→ 检查系统存储权限后走 [DirectStorageBackend]
     */
    private suspend fun backend(vararg files: File): StorageBackend {
        ensurePermission()
        files.forEach { assertExternalPath(it) }
        if (StorageAccessConfig(activity).mode == StorageMode.SHIZUKU &&
            ShizukuShell.state(activity) == ShizukuState.ACTIVE &&
            ShizukuShell.hasPermission()
        ) {
            return ShizukuStorageBackend(activity)
        }
        ensureSystemStoragePermission()
        return DirectStorageBackend()
    }

    /**
     * 路径校验：应用私有目录为不可配置硬底线（永远禁），黑白名单叠加其上。
     * - 黑名单：命中则禁
     * - 白名单：未命中则禁
     * - NONE：仅硬底线生效
     */
    private fun assertExternalPath(f: File) {
        val p = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
        // 硬底线：应用私有目录永远禁（不可配置，防篡改权限记录与沙箱数据）
        if (p.startsWith(activity.filesDir.canonicalPath) || p.startsWith("/data/data/")) {
            throw SecurityException("禁止访问应用私有目录: $p")
        }
        // 规则过滤：只取启用且作用域当前小程序 的规则
        val active = StorageAccessConfig(activity).rules().filter {
            it.enabled && (it.appKeys.isEmpty() || appInfo.appKey in it.appKeys)
        }
        val whitelists = active.filter { it.type == RuleType.WHITELIST }
        val blacklists = active.filter { it.type == RuleType.BLACKLIST }
        // 有白名单规则时：路径必须命中至少一条才放行
        if (whitelists.isNotEmpty() && !whitelists.any { ruleMatches(it, p) }) {
            throw SecurityException("路径不在白名单中: $p")
        }
        // 黑名单规则：命中即禁（在白名单之后的二次过滤）
        if (blacklists.any { ruleMatches(it, p) }) {
            throw SecurityException("路径在黑名单中: $p")
        }
    }

    private fun ruleMatches(rule: PathRule, canonicalPath: String): Boolean {
        val rp = try { File(rule.path).canonicalPath } catch (e: Exception) { rule.path }
        return if (rule.matchMode == MatchMode.EXACT) canonicalPath == rp
        else canonicalPath == rp || canonicalPath.startsWith(rp + File.separator)
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
