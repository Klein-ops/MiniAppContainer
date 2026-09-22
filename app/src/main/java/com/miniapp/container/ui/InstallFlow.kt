package com.miniapp.container.ui

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.core.AppInstaller
import com.miniapp.container.core.AppRegistry
import com.miniapp.container.core.PathGuard
import com.miniapp.container.core.PreviewInfo
import com.miniapp.container.util.InputDialog
import com.miniapp.container.util.SafIo
import com.miniapp.container.util.Version
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 安装流程内核：选包（zip / 内置示例 / URL）→ 二次确认（预览清单 + 版本对比）→ 安装。
 *
 * 从 MainActivity 抽出，使其只专注列表与分类 UI。安装来源若要扩展
 * （二维码、分享 Intent、多选批量等），改这里即可，不动 Activity。
 *
 * @param currentCategory 当前选中分类（全新安装的应用归入此分类；"全部"视图为 null）
 * @param launchPickZip 触发系统文件选择器（由 Activity 注册的 launcher 提供）
 * @param onInstalled 安装完成回调：全新安装传 (目标分类, appKey)，更新/覆盖传 (null, appKey)
 * @param onRefresh 需要刷新列表时回调（如清单解析失败）
 */
class InstallFlow(
    private val activity: AppCompatActivity,
    private val registry: AppRegistry,
    private val installer: AppInstaller,
    private val currentCategory: () -> String?,
    private val launchPickZip: (Array<String>) -> Unit,
    private val onInstalled: (category: String?, appKey: String?) -> Unit,
    private val onRefresh: () -> Unit
) {

    /** 安装入口：应用包 / 内置示例 / URL。 */
    fun showOptions() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("安装")
            .setItems(arrayOf("安装应用包（zip）", "安装内置示例", "从 URL 安装")) { _, which ->
                when (which) {
                    0 -> launchPickZip(
                        arrayOf("application/zip", "application/octet-stream", "*/*")
                    )
                    1 -> activity.lifecycleScope.launch { installSample() }
                    2 -> showUrlDialog()
                }
            }.showRounded()
    }

    /** 安装 SAF 选中的 zip。 */
    suspend fun installFromUri(uri: Uri) {
        val cache = File(activity.cacheDir, "pick_${System.currentTimeMillis()}.zip")
        try {
            withContext(Dispatchers.IO) { SafIo.readToFile(activity, uri, cache) }
        } catch (t: Throwable) {
            activity.toast("无法读取文件")
            return
        }
        requestConfirm(cache)
    }

    /** 从 URL 下载 zip 后安装（先弹来源不可信提示）。 */
    suspend fun installFromUrl(url: String) {
        // 来源不可信风险提示
        val confirmed = suspendCancellableCoroutine<Boolean> { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle("从 URL 安装")
                .setMessage("安装来源：$url\n\n来自网络的安装包可能不可信，请仅安装你信任的来源。是否继续？")
                .setPositiveButton("继续") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("取消") { _, _ -> if (cont.isActive) cont.resume(false) }
                .showRounded()
        }
        if (!confirmed) return
        val zipFile = File(activity.cacheDir, "remote_${System.currentTimeMillis()}.zip")
        try {
            activity.toast("下载中…")
            withContext(Dispatchers.IO) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    zipFile.outputStream().use { input.copyTo(it) }
                }
                conn.disconnect()
            }
            requestConfirm(zipFile, sourceUrl = url)
        } catch (e: Exception) {
            activity.toast("下载失败: ${e.message}")
            zipFile.delete()
        }
    }

    /** 安装内置示例包。 */
    suspend fun installSample() {
        val cache = File(activity.cacheDir, "sample_${System.currentTimeMillis()}.zip")
        try {
            withContext(Dispatchers.IO) {
                activity.assets.open("sample/sample_app.zip").use { input ->
                    cache.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (t: Throwable) {
            activity.toast("示例包缺失")
            return
        }
        val preview = withContext(Dispatchers.IO) { installer.previewZip(cache) }
        if (preview == null) {
            activity.toast("示例包清单解析失败")
            cache.delete()
            return
        }
        val isNew = registry.get(PathGuard.appKey(preview.uid, preview.uname)) == null
        val r = install(cache)
        activity.toast(if (r.success) "示例已安装" else "示例安装失败: ${r.message}")
        cache.delete()
        onInstalled(if (r.success && isNew) currentCategory() else null, r.info?.appKey)
    }

    // ---------- 内部：二次确认 ----------

    /** 安装前二次确认：读 zip 内 manifest.json，展示名称/appKey/版本，按版本差异给按钮。 */
    private fun requestConfirm(zipFile: File, sourceUrl: String = "") {
        activity.lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) { installer.previewZip(zipFile) }
            if (preview == null) {
                activity.toast("无法读取应用清单 manifest.json")
                zipFile.delete()
                onRefresh()
                return@launch
            }
            showConfirmDialog(preview, zipFile)
        }
    }

    private fun showConfirmDialog(preview: PreviewInfo, zipFile: File) {
        val existing = registry.get(PathGuard.appKey(preview.uid, preview.uname))
        val isNew = existing == null
        val (verb, full) = buildAction(existing?.version, preview.version)
        val msg = buildString {
            append("名称：").append(preview.uname).append('\n')
            append("appKey：").append(preview.uid).append('_').append(preview.uname).append('\n')
            append("版本：").append(preview.version)
            if (existing != null) append("\n已安装版本：").append(existing.version)
            append("\n\n更新保留数据，仅替换应用文件。")
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("确认${verb}「${preview.uname}」？")
            .setMessage(msg)
            .setPositiveButton(full) { _, _ ->
                activity.lifecycleScope.launch {
                    val r = install(zipFile, sourceUrl)
                    activity.toast(
                        if (r.success) "已安装: ${r.info?.uname}" else "安装失败: ${r.message}"
                    )
                    zipFile.delete()
                    onInstalled(if (r.success && isNew) currentCategory() else null, r.info?.appKey)
                }
            }
            .setNegativeButton("取消") { _, _ -> zipFile.delete() }
            .showRounded()
    }

    private suspend fun install(zipFile: File, sourceUrl: String = "") = installer.installFromZip(zipFile, sourceUrl)

    /** 按已装版本与新包版本判定动作语义。 */
    private fun buildAction(existingVer: String?, newVer: String): Pair<String, String> {
        if (existingVer == null) return "安装" to "安装"
        return when {
            Version.compare(existingVer, newVer) < 0 -> "更新" to "更新到 $newVer"
            Version.compare(existingVer, newVer) > 0 -> "降级" to "降级到 $newVer"
            else -> "替换" to "替换"
        }
    }

    private fun showUrlDialog() {
        val (view, input) = InputDialog.create(
            activity, "https://example.com/app.zip",
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("从 URL 安装")
            .setMessage("输入 zip 文件直链")
            .setView(view)
            .setPositiveButton("下载安装") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) activity.lifecycleScope.launch { installFromUrl(url) }
            }.setNegativeButton("取消", null).showRounded()
    }
}
