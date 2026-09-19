package com.miniapp.container.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/**
 * 已安装小程序注册表（内存缓存，持久化 = 每应用沙箱里的 meta.json）。
 *
 * 数据源：每个沙箱目录下的 `meta.json`（宿主写入、随应用走、备份时打包）。
 * - 启动时扫描 miniapps/ 聚合为内存缓存（列表秒开）
 * - 安装/恢复通过 [put] 写 meta.json
 * - 卸载通过 [remove] 移除缓存；meta.json 随沙箱目录一起删除
 */
class AppRegistry(private val sandboxRoot: File) {

    private val apps = LinkedHashMap<String, MiniAppInfo>()

    init {
        migrateLegacy()
        load()
    }

    /** 一次性迁移：旧版宿主级 registry.json → 补全各沙箱 meta.json（含 icon/displayName），随后删除。 */
    private fun migrateLegacy() {
        val legacy = File(sandboxRoot, "registry.json")
        if (!legacy.isFile) return
        try {
            val root = JSONObject(legacy.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.optString("appKey")
                if (key.isBlank()) continue
                val metaF = File(File(sandboxRoot, key), "meta.json")
                if (metaF.isFile) metaF.writeText(o.toString(), Charsets.UTF_8)
            }
            legacy.delete()
        } catch (t: Throwable) {
            // 迁移失败不阻断；缺失字段走默认值
        }
    }

    /** 全量扫描沙箱重建缓存（备份恢复等外部改动后调用）。 */
    fun load() {
        apps.clear()
        sandboxRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val meta = File(dir, "meta.json")
            if (!meta.isFile) return@forEach
            val info = runCatching {
                MiniAppInfo.fromJson(JSONObject(meta.readText(Charsets.UTF_8)))
            }.getOrNull() ?: return@forEach
            apps[info.appKey] = info
        }
    }

    fun list(): List<MiniAppInfo> = apps.values.toList()

    fun get(appKey: String): MiniAppInfo? = apps[appKey]

    /** 注册/更新：更新内存缓存，并持久化到该应用沙箱的 meta.json（随应用走）。 */
    fun put(info: MiniAppInfo) {
        apps[info.appKey] = info
        val dir = File(sandboxRoot, info.appKey)
        dir.mkdirs()
        File(dir, "meta.json").writeText(info.toJson().toString(), Charsets.UTF_8)
    }

    /** 更新分类归属（双写 meta.json）；无变化时不写。 */
    fun updateCategory(appKey: String, category: String) {
        val cur = get(appKey) ?: return
        if (cur.category == category) return
        put(cur.copy(category = category))
    }

    /** 卸载：移除缓存；meta.json 随沙箱目录删除（调用方负责删沙箱）。 */
    fun remove(appKey: String) {
        apps.remove(appKey)
    }
}
