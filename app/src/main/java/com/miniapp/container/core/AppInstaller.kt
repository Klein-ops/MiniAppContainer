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

            val manifestFile = File(tmp, "manifest.json")
            if (!manifestFile.isFile) {
                return@withContext InstallResult(false, message = "清单 manifest.json 不存在")
            }
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

            // 将解压内容拷入 app/
            tmp.copyRecursively(resDir, overwrite = true)

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

            val info = MiniAppInfo(
                uid = uid,
                uname = uname,
                version = manifest.version,
                entry = manifest.entry,
                wasm = manifest.wasm,
                permissions = manifest.permissions,
                requiredPermissions = manifest.requiredPermissions,
                installedAt = System.currentTimeMillis()
            )
            registry.put(info)
            registry.save()

            InstallResult(true, info = info)
        } finally {
            tmp.deleteRecursively()
        }
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
        permissionManager.clearApp(appKey)   // 修复：卸载时清除权限记录
        sandbox.delete(appKey)
    }
}
