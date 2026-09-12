package com.miniapp.container.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/** 已安装小程序注册表，持久化为 JSON。 */
class AppRegistry(val file: File) {

    private val apps = LinkedHashMap<String, MiniAppInfo>()

    init { load() }

    fun load() {
        apps.clear()
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val info = MiniAppInfo.fromJson(arr.getJSONObject(i))
                apps[info.appKey] = info
            }
        } catch (t: Throwable) {
            apps.clear()
        }
    }

    fun save() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        apps.values.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().put("apps", arr).toString(), Charsets.UTF_8)
    }

    fun list(): List<MiniAppInfo> = apps.values.toList()

    fun get(appKey: String): MiniAppInfo? = apps[appKey]

    fun put(info: MiniAppInfo) { apps[info.appKey] = info }

    fun remove(appKey: String) { apps.remove(appKey) }
}
