package com.miniapp.container.core

import android.content.Context
import com.miniapp.container.netdisk.WebdavClient
import com.miniapp.container.netdisk.WebdavConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份失败原因（便于 UI 区分提示）。 */
enum class BackupError { NOT_CONFIGURED, NETWORK, SERVER }

/**
 * 备份与恢复服务。
 *
 * 导出结构（zip）：
 * ```
 * backup.json                     清单：{ format, includeData, allApps, apps:[MiniAppInfo...] }
 * miniapps/<appKey>/app/...       应用资源
 * miniapps/<appKey>/data/...      应用数据（includeData=true 时）
 * miniapps/<appKey>/meta.json     安装信息
 * miniapps/categories.json        分类（仅全量备份时）
 * ```
 *
 * WebDAV 路径：`/ROOT/backup/<文件名>`；小程序数据：`/ROOT/data/<uid>_<uname>/`。
 */
class BackupService(private val context: Context) {

    private val config = WebdavConfig(context)
    private val root = listOf(WebdavConfig.ROOT_FOLDER)

    // ==================== 本地 zip ====================

    /**
     * 导出为 zip。
     * @param appKeys 要备份的 appKey 列表；空表示全部应用
     * @param includeData 是否包含 data/（应用数据）
     */
    fun exportToZip(dest: File, appKeys: List<String>, includeData: Boolean) {
        val base = File(context.filesDir, "miniapps")
        val registry = AppRegistry(File(base, "registry.json")).also { it.load() }
        val all = appKeys.isEmpty()
        val selected = if (all) registry.list().map { it.appKey } else appKeys

        ZipOutputStream(BufferedOutputStream(dest.outputStream())).use { zos ->
            val appsArr = JSONArray()
            selected.forEach { registry.get(it)?.let { info -> appsArr.put(info.toJson()) } }
            val manifest = JSONObject()
                .put("format", 1)
                .put("includeData", includeData)
                .put("allApps", all)
                .put("apps", appsArr)
            writeEntry(zos, "backup.json", manifest.toString().toByteArray(Charsets.UTF_8))

            if (all) {
                listOf("categories.json").forEach { n ->
                    val f = File(base, n)
                    if (f.isFile) writeEntry(zos, "miniapps/$n", f.readBytes())
                }
            }

            selected.forEach { key ->
                val dir = File(base, key)
                if (!dir.isDirectory) return@forEach
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    if (rel.contains("/tmp/")) return@forEach
                    if (!includeData && rel.startsWith("$key/data/")) return@forEach
                    writeEntry(zos, "miniapps/$rel", f.readBytes())
                }
            }
        }
    }

    /** 从 zip 导入：解压沙箱并合并注册表（不删除未包含的应用）。 */
    fun importFromZip(zipFile: File) {
        val base = context.filesDir
        var manifest: JSONObject? = null
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && !name.contains("..") && !name.startsWith("/")) {
                    when {
                        name == "backup.json" ->
                            manifest = runCatching {
                                JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                            }.getOrNull()
                        name.startsWith("miniapps/") -> {
                            val target = File(base, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val reg = AppRegistry(File(base, "miniapps/registry.json")).also { it.load() }
        manifest?.optJSONArray("apps")?.let { arr ->
            for (i in 0 until arr.length()) reg.put(MiniAppInfo.fromJson(arr.getJSONObject(i)))
        }
        reg.save()
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
}

/** WebDAV 操作异常，携带可区分的原因。 */
class WebdavError(val reason: BackupError, cause: Throwable? = null) :
    Exception(cause?.message ?: reason.name, cause)
