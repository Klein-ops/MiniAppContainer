package com.miniapp.container.permission

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 持久化的授权记录：appKey -> 已授权 scope 集合。 */
class PermissionStore(private val file: File) {

    private val grants = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        file.parentFile?.mkdirs()
        load()
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val obj = root.optJSONObject("grants") ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.optJSONArray(k) ?: continue
                val set = Collections.synchronizedSet(HashSet<String>())
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                grants[k] = set
            }
        } catch (t: Throwable) {
            // ignore corrupted
        }
    }

    @Synchronized
    private fun save() {
        try {
            val obj = JSONObject()
            for ((k, set) in grants) {
                val arr = JSONArray()
                synchronized(set) { set.forEach { arr.put(it) } }
                obj.put(k, arr)
            }
            file.writeText(JSONObject().put("grants", obj).toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // best-effort
        }
    }

    fun isGranted(appKey: String, scope: String): Boolean =
        grants[appKey]?.contains(scope) == true

    fun grant(appKey: String, scope: String) {
        val set = grants.getOrPut(appKey) { Collections.synchronizedSet(HashSet()) }
        set.add(scope)
        save()
    }

    fun revoke(appKey: String, scope: String) {
        grants[appKey]?.remove(scope)
        save()
    }

    fun grantedScopes(appKey: String): List<String> =
        grants[appKey]?.toList() ?: emptyList()

    /** 卸载应用时清除其所有授权记录。 */
    fun clearApp(appKey: String) {
        grants.remove(appKey)
        save()
    }
}
