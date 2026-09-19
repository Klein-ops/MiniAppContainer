package com.miniapp.container.core

import com.miniapp.container.MiniAppApp

import android.content.Context
import com.miniapp.container.netdisk.WebdavClient
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.util.IoUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份失败原因（便于 UI 区分提示）。 */
enum class BackupError { NOT_CONFIGURED, NETWORK, SERVER }

/**
 * 备份与恢复服务。
 *
 * **备份内容刻意精简**：只含小程序资源（app/）与应用数据（data/，可选），
 * **不含权限声明、权限授权记录、分类等元数据**。恢复时权限从重新解压的
 * `app/manifest.json` 读取，保证与资源一致。
 *
 * 导出结构（zip）：
 * ```
 * backup.json                     精简清单：{ format, includeData, apps:[{uid,uname,version,entry,wasm,icon,displayName}] }
 * miniapps/<appKey>/app/...       应用资源
 * miniapps/<appKey>/data/...      应用数据（includeData=true 时）
 * ```
 *
 * WebDAV 路径：`/<ROOT>/backup/<文件名>`；小程序数据：`/<ROOT>/data/<uid>_<uname>/`。
 */
class BackupService(
    private val context: Context,
    private val installer: AppInstaller
) {

    private val config = WebdavConfig(context)
    private val root = listOf(WebdavConfig.ROOT_FOLDER)

    // ==================== 本地 zip ====================

    /**
     * 导出为 zip。
     * @param appKeys 要备份的 appKey 列表；空表示全部应用
     * @param includeData 是否包含 data/（应用数据）
     * @param compressionLevel zip 压缩等级 0-9（0=不压缩，9=最大，默认 6）
     */
    fun exportToZip(
        dest: File,
        appKeys: List<String>,
        includeData: Boolean,
        compressionLevel: Int = 6
    ) {
        val base = File(context.filesDir, "miniapps")
        val registry = MiniAppApp.require(context).registry
        val all = appKeys.isEmpty()
        val selected = if (all) registry.list().map { it.appKey } else appKeys

        ZipOutputStream(BufferedOutputStream(dest.outputStream())).use { zos ->
            zos.setLevel(compressionLevel.coerceIn(0, 9))

            // 1) 精简清单：不含 permissions/requiredPermissions/installedAt
            val appsArr = JSONArray()
            selected.forEach { key ->
                registry.get(key)?.let { info ->
                    appsArr.put(
                        JSONObject()
                            .put("uid", info.uid)
                            .put("uname", info.uname)
                            .put("version", info.version)
                            .put("entry", info.entry)
                            .put("wasm", JSONArray(info.wasm))
                            .put("icon", info.icon)
                            .put("displayName", info.displayName)
                            .put("appKey", info.appKey)
                    )
                }
            }
            val manifest = JSONObject()
                .put("format", 2)
                .put("includeData", includeData)
                .put("apps", appsArr)
            // 1.5) 分类列表（宿主级组织结构）：随备份迁移，恢复后应用回到原分类
            val catFile = File(base, "categories.json")
            if (catFile.isFile) {
                runCatching { manifest.put("categories", JSONObject(catFile.readText(Charsets.UTF_8))) }
            }
            writeEntry(zos, "backup.json", manifest.toString().toByteArray(Charsets.UTF_8))

            // 2) 各应用沙箱：只备份 app/ 与（可选）data/，不备份 meta.json
            selected.forEach appLoop@{ key ->
                val dir = File(base, key)
                if (!dir.isDirectory) return@appLoop
                dir.walkTopDown().filter { it.isFile }.forEach fileLoop@{ f ->
                    val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    if (rel.startsWith("$key/app/")) {
                        writeEntry(zos, "miniapps/$rel", f.readBytes())
                    } else if (rel == "$key/meta.json") {
                        // 应用元数据（声明/入口/图标/displayName）随应用走，始终备份
                        writeEntry(zos, "miniapps/$rel", f.readBytes())
                    } else if (includeData && rel.startsWith("$key/data/")) {
                        writeEntry(zos, "miniapps/$rel", f.readBytes())
                    }
                    // tmp/、其它一律不备份
                }
            }
        }
    }

    /** 从 zip 导入：解压沙箱并按清单逐个恢复（不删除未包含的应用）。 */
    suspend fun importFromZip(zipFile: File) {
        val tmp = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
        tmp.mkdirs()
        try {
            IoUtil.unzip(zipFile, tmp)   // 自带 Zip-Slip 防护
            val manifestFile = File(tmp, "backup.json")
            if (!manifestFile.isFile) throw IOException("备份文件缺少 backup.json（可能不是蜗壳备份）")
            val root = JSONObject(manifestFile.readText(Charsets.UTF_8))

            // 0) 分类列表：先恢复，再恢复应用（syncApps 会清理备份里没有的应用 key）
            val cats = root.optJSONObject("categories")
            if (cats != null) {
                val f = File(context.filesDir, "miniapps/categories.json")
                f.writeText(cats.toString(), Charsets.UTF_8)
                MiniAppApp.require(context).categoryManager.load()   // 刷新内存
            }

            val apps = root.optJSONArray("apps") ?: JSONArray()
            var restored = 0
            for (i in 0 until apps.length()) {
                val o = apps.getJSONObject(i)
                val uid = o.optString("uid")
                val uname = o.optString("uname")
                val appKey = PathGuard.appKey(uid, uname)
                val extracted = File(tmp, "miniapps/$appKey")
                val ok = installer.restoreFromBackup(
                    extractedDir = extracted,
                    uid = uid,
                    uname = uname,
                    displayName = o.optString("displayName")
                )
                if (ok) restored++
            }
            if (restored == 0 && apps.length() > 0) {
                throw IOException("备份内容无法恢复（应用数据缺失或格式不符）")
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    // ==================== WebDAV ====================

    fun isWebdavConfigured(): Boolean = config.configured

    /** 上传备份到 /ROOT/backup/<name>。 */
    fun uploadBackup(name: String, localZip: File): Boolean {
        if (!config.configured) throw WebdavError(BackupError.NOT_CONFIGURED)
        val client = WebdavClient(config)
        return try {
            client.mkdirs(root + "backup")
            client.put(root + "backup" + name, localZip)
        } catch (t: Throwable) {
            throw WebdavError(BackupError.NETWORK, t)
        }
    }

    /** 从 /ROOT/backup/<name> 下载到本地。 */
    fun downloadBackup(name: String, dest: File): Boolean {
        if (!config.configured) throw WebdavError(BackupError.NOT_CONFIGURED)
        val client = WebdavClient(config)
        return try {
            client.get(root + "backup" + name, dest)
        } catch (t: Throwable) {
            throw WebdavError(BackupError.NETWORK, t)
        }
    }

    /** 列出 /ROOT/backup/ 下的备份文件名。 */
    fun listBackups(): List<String> {
        if (!config.configured) throw WebdavError(BackupError.NOT_CONFIGURED)
        val client = WebdavClient(config)
        return try {
            client.mkdirs(root + "backup")
            client.list(root + "backup").filter { it.endsWith(".zip") }
        } catch (t: Throwable) {
            throw WebdavError(BackupError.NETWORK, t)
        }
    }

    /** 删除 /ROOT/backup/ 下的一个备份文件。 */
    fun deleteBackup(name: String): Boolean {
        if (!config.configured) throw WebdavError(BackupError.NOT_CONFIGURED)
        val client = WebdavClient(config)
        return try {
            client.delete(root + "backup" + name)
        } catch (t: Throwable) {
            throw WebdavError(BackupError.NETWORK, t)
        }
    }
}

/** WebDAV 操作异常，携带可区分的原因。 */
class WebdavError(val reason: BackupError, cause: Throwable? = null) :
    Exception(cause?.message ?: reason.name, cause)
