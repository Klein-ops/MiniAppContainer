package com.miniapp.container.core

import android.content.Context
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    suspend fun installFromZip(zipFile: File): InstallResult = withContext(Dispatchers.IO) {
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

            // 写 meta.json
            val meta = MetaInfo(
                uid = uid,
                uname = uname,
                version = manifest.version,
                entry = manifest.entry,
                wasm = manifest.wasm,
                permissions = manifest.permissions,
                requiredPermissions = manifest.requiredPermissions,
                installedAt = System.currentTimeMillis(),
                appKey = appKey,
                sandboxPath = sandbox.appDir(appKey).absolutePath
            )
            sandbox.metaFile(appKey).writeText(meta.toJson().toString(), Charsets.UTF_8)

            val existing = registry.get(appKey)
            val info = MiniAppInfo(
                uid = uid,
                uname = uname,
                version = manifest.version,
                entry = manifest.entry,
                wasm = manifest.wasm,
                permissions = manifest.permissions,
                requiredPermissions = manifest.requiredPermissions,
                icon = manifest.icon,
                displayName = existing?.displayName ?: "",
                installedAt = System.currentTimeMillis()
            )
            registry.put(info)
            registry.save()

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
        val manifestFile = File(extractedDir, "app/manifest.json")
        if (!manifestFile.isFile) return@withContext false
        val manifest = AppManifest.parse(manifestFile) ?: return@withContext false

        // 1) 重建沙箱：只替换 app/，data/ 若有则合并
        val appDir = sandbox.appDir(appKey)
        appDir.deleteRecursively()
        appDir.mkdirs()
        File(extractedDir, "app").copyRecursively(appDir, overwrite = true)
        val dataSrc = File(extractedDir, "data")
        if (dataSrc.isDirectory) {
            val dataDst = sandbox.dataDir(appKey)
            dataDst.deleteRecursively()
            dataDst.mkdirs()
            dataSrc.copyRecursively(dataDst, overwrite = true)
        }

        // 2) 写 meta.json（权限取自重新解析的 manifest）
        val meta = MetaInfo(
            uid = uid,
            uname = uname,
            version = manifest.version,
            entry = manifest.entry,
            wasm = manifest.wasm,
            permissions = manifest.permissions,
            requiredPermissions = manifest.requiredPermissions,
            installedAt = System.currentTimeMillis(),
            appKey = appKey,
            sandboxPath = appDir.absolutePath
        )
        sandbox.metaFile(appKey).writeText(meta.toJson().toString(), Charsets.UTF_8)

        // 3) 注册（upsert，displayName 来自备份）
        val existing = registry.get(appKey)
        registry.put(
            MiniAppInfo(
                uid = uid,
                uname = uname,
                version = manifest.version,
                entry = manifest.entry,
                wasm = manifest.wasm,
                permissions = manifest.permissions,
                requiredPermissions = manifest.requiredPermissions,
                icon = manifest.icon,
                displayName = displayName.ifBlank { existing?.displayName ?: "" },
                installedAt = System.currentTimeMillis()
            )
        )
        registry.save()
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
        registry.remove(appKey)
        registry.save()
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
