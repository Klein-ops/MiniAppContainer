package com.miniapp.container.core

import android.content.Context
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.permission.PermissionRegistry
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
/** zip 包内小程序清单摘要（安装前二次确认用）。 */
data class PreviewInfo(val uid: String, val uname: String, val version: String)

/** 应用包安装结果。 */
data class InstallResult(
    val success: Boolean,
    val info: MiniAppInfo? = null,
    val message: String = ""
)

/** 应用包管理：安装、解压、解析清单、注册。 */
class AppInstaller(
    private val context: Context,
    private val sandbox: SandboxManager,
    private val registry: AppRegistry,
    private val permissionManager: PermissionManager
) {

    /**
     * 只读 zip 中的 manifest.json（根目录优先，否则递归查找第一个），
     * 返回小程序清单摘要。只用于安装前的二次确认，不落盘、不安装。
     */
    suspend fun previewZip(zipFile: File): PreviewInfo? = withContext(Dispatchers.IO) {
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var rootManifest: JSONObject? = null
                var fallback: JSONObject? = null
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("manifest.json")) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        if (json != null) {
                            // 与安装逻辑一致：zip 根目录的 manifest 优先
                            if (entry.name == "manifest.json") { rootManifest = json; break }
                            if (fallback == null) fallback = json
                        }
                    }
                    entry = zis.nextEntry
                }
                val m = rootManifest ?: fallback ?: return@withContext null
                val uid = m.optString("uid").trim()
                val uname = m.optString("uname").trim()
                if (uid.isEmpty() || uname.isEmpty()) return@withContext null
                PreviewInfo(uid = uid, uname = uname, version = m.optString("version", "0.0.0"))
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun installFromZip(zipFile: File, sourceUrl: String = ""): InstallResult = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "install_${System.currentTimeMillis()}")
        try {
            tmp.mkdirs()
            try {
                IoUtil.unzip(zipFile, tmp)
            } catch (t: Throwable) {
                return@withContext InstallResult(false, message = "解压失败: ${t.message}")
            }

            // 清单查找：zip 根目录优先，否则递归查找第一个 manifest.json。
            // 支持带目录层级的应用包（不强制扁平化），以 manifest 所在目录为应用根。
            val manifestFile = File(tmp, "manifest.json").takeIf { it.isFile }
                ?: tmp.walkTopDown().firstOrNull { it.isFile && it.name == "manifest.json" }
            if (manifestFile == null) {
                return@withContext InstallResult(false, message = "清单 manifest.json 不存在")
            }
            val appRoot = manifestFile.parentFile ?: tmp
            val manifest = AppManifest.parse(manifestFile)
                ?: return@withContext InstallResult(false, message = "清单解析失败或字段缺失")

            val uid = PathGuard.sanitizeName(manifest.uid)
                ?: return@withContext InstallResult(false, message = "uid 非法: ${manifest.uid}")
            val uname = PathGuard.sanitizeName(manifest.uname)
                ?: return@withContext InstallResult(false, message = "uname 非法: ${manifest.uname}")
            val appKey = PathGuard.appKey(uid, uname)

            // 创建或复用沙箱；更新时保留 data/ 与 tmp/，只替换 app/
            sandbox.create(appKey)
            val resDir = sandbox.resDir(appKey)
            resDir.deleteRecursively()
            resDir.mkdirs()

            // 将应用根内容拷入 app/（保留目录层级）
            appRoot.copyRecursively(resDir, overwrite = true)

            // 必要权限过滤：危险权限不允许作为必要权限（清单声明会被忽略）
            val safeRequired = PermissionRegistry.sanitizeRequired(
                manifest.requiredPermissions, manifest.permissions
            )

            val existing = registry.get(appKey)
            val info = MiniAppInfo(
                uid = uid,
                uname = uname,
                version = manifest.version,
                entry = manifest.entry,
                wasm = manifest.wasm,
                permissions = manifest.permissions,
                requiredPermissions = safeRequired,
                icon = manifest.icon,
                displayName = existing?.displayName ?: "",
                installedAt = System.currentTimeMillis(),
                sourceUrl = sourceUrl
            )
            registry.put(info)   // 内存缓存 + 写沙箱 meta.json（随应用走）

            InstallResult(true, info = info)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * 从备份恢复单个应用（备份里**不含权限等元数据**，权限从重新解压的
     * `app/manifest.json` 读取，保证恢复后权限声明与资源一致）。
     *
     * @param extractedDir 已解压的 `miniapps/<appKey>` 目录（含 app/，可选 data/）
     * @param displayName  用户重命名（来自备份清单；备份里保留该项，便于恢复显示名）
     */
    suspend fun restoreFromBackup(
        extractedDir: File,
        uid: String,
        uname: String,
        displayName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appKey = PathGuard.appKey(uid, uname)

        // 1) 元数据：备份携带的 meta.json 是唯一权威（2.0 起要求；
        //    老备份无此文件则恢复失败，不再从 manifest 重建）
        val metaBackup = File(extractedDir, "meta.json")
        if (!metaBackup.isFile) return@withContext false
        val info = runCatching {
            MiniAppInfo.fromJson(JSONObject(metaBackup.readText(Charsets.UTF_8)))
        }.getOrNull() ?: return@withContext false
        if (info.appKey != appKey) return@withContext false   // 元数据与目录不一致，拒绝

        // 2) 重建沙箱：只替换 app/，data/ 若有则合并
        val appSrc = File(extractedDir, "app")
        if (!appSrc.isDirectory) return@withContext false
        sandbox.create(appKey)
        val resDir = sandbox.resDir(appKey)
        resDir.deleteRecursively()
        resDir.mkdirs()
        appSrc.copyRecursively(resDir, overwrite = true)

        val dataSrc = File(extractedDir, "data")
        if (dataSrc.isDirectory) {
            val dataDst = sandbox.dataDir(appKey)
            dataDst.deleteRecursively()
            dataDst.mkdirs()
            dataSrc.copyRecursively(dataDst, overwrite = true)
        }

        // 3) 注册（upsert）：displayName 以备份清单为准，其余信任 meta.json
        registry.put(info.copy(displayName = displayName.ifBlank { info.displayName }))
        true
    }

    suspend fun installFromAssets(assetName: String): InstallResult = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "asset_${System.currentTimeMillis()}.zip")
        try {
            context.assets.open(assetName).use { IoUtil.copy(it, cache) }
            installFromZip(cache)
        } catch (t: Throwable) {
            InstallResult(false, message = "读取内置包失败: ${t.message}")
        } finally {
            cache.delete()
        }
    }

    suspend fun uninstall(appKey: String): Boolean = withContext(Dispatchers.IO) {
        registry.remove(appKey)   // meta.json 随沙箱目录删除
        permissionManager.clearApp(appKey)   // 卸载时清除权限记录
        val deleted = sandbox.delete(appKey)
        // 移除该小程序的独立通知渠道（渠道名 = appKey = uid_uname）
        try {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.deleteNotificationChannel(appKey)
        } catch (_: Throwable) { /* 渠道不存在或系统不支持，忽略 */ }
        deleted
    }
}
