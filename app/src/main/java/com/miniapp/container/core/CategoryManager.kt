package com.miniapp.container.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/**
 * 分类管理：持久化分类列表 + 每个分类的应用顺序。
 *
 * 存储 miniapps/categories.json：
 * { "order": ["默认","工具"], "categories": { "默认":["appKey1"], "工具":["appKey2"] } }
 *
 * 应用未归类时自动放入"默认"分类。
 */
class CategoryManager(private val file: File) {

    companion object {
        const val DEFAULT = "默认"
    }

    private val order = mutableListOf<String>()              // 分类顺序
    private val categories = LinkedHashMap<String, MutableList<String>>()

    init {
        file.parentFile?.mkdirs()
        load()
    }

    private fun load() {
        if (!file.isFile) {
            ensureDefault()
            return
        }
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val orderArr = root.optJSONArray("order")
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) order.add(orderArr.getString(i))
            }
            val cats = root.optJSONObject("categories")
            if (cats != null) {
                val keys = cats.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = cats.optJSONArray(k) ?: continue
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    categories[k] = list
                }
            }
        } catch (t: Throwable) { /* ignore */ }
        ensureDefault()
    }

    private fun ensureDefault() {
        if (order.isEmpty()) order.add(DEFAULT)
        if (categories[DEFAULT] == null) categories[DEFAULT] = mutableListOf()
        if (DEFAULT !in order) order.add(0, DEFAULT)
    }

    @Synchronized
    fun save() {
        try {
            val orderArr = JSONArray()
            order.forEach { orderArr.put(it) }
            val cats = JSONObject()
            for ((k, list) in categories) {
                val arr = JSONArray()
                list.forEach { arr.put(it) }
                cats.put(k, arr)
            }
            file.writeText(JSONObject().put("order", orderArr).put("categories", cats).toString(), Charsets.UTF_8)
        } catch (t: Throwable) { /* best-effort */ }
    }

    fun listCategories(): List<String> = order.toList()

    fun addCategory(name: String): Boolean {
        if (name.isBlank() || name in order) return false
        order.add(name)
        categories[name] = mutableListOf()
        save()
        return true
    }

    fun removeCategory(name: String) {
        if (name == DEFAULT) return
        val apps = categories.remove(name) ?: return
        order.remove(name)
        categories.getOrPut(DEFAULT) { mutableListOf() }.addAll(0, apps)
        save()
    }

    fun categoryOf(appKey: String): String {
        for ((cat, list) in categories) if (appKey in list) return cat
        return DEFAULT
    }

    fun appsIn(category: String): List<String> =
        categories[category]?.toList() ?: emptyList()

    fun moveTo(appKey: String, category: String) {
        if (category !in categories && category != DEFAULT) return
        // 从原分类移除
        for ((_, list) in categories) list.remove(appKey)
        categories.getOrPut(if (category in categories) category else DEFAULT) { mutableListOf() }.add(appKey)
        save()
    }

    /** 拖动排序：在 category 内将 fromPos 移到 toPos。 */
    fun reorder(category: String, fromPos: Int, toPos: Int) {
        val list = categories[category] ?: return
        if (fromPos !in list.indices || toPos !in list.indices) return
        val item = list.removeAt(fromPos)
        list.add(toPos, item)
        save()
    }

    /**
     * 同步应用列表：移除已卸载的 appKey，把未分类的加入默认分类。
     * 应在刷新列表前调用，确保所有已安装应用都可见。
     */
    fun syncApps(allAppKeys: List<String>) {
        val all = allAppKeys.toSet()
        // 移除已卸载的
        for ((_, list) in categories) list.retainAll { it in all }
        // 添加未分类的到默认
        val known = categories.values.flatten().toSet()
        for (key in allAppKeys) {
            if (key !in known) categories.getOrPut(DEFAULT) { mutableListOf() }.add(key)
        }
        save()
    }

    /** 获取某分类下的应用 appKey 列表（含"全部"模式）。 */
    fun appsInFiltered(filter: String?): List<String> =
        if (filter == null) categories.values.flatten()
        else appsIn(filter)

    /** 拖动排序后直接覆盖某分类的 appKey 顺序。 */
    fun setOrder(category: String, appKeys: List<String>) {
        val target = if (category in categories) category else DEFAULT
        categories[target] = appKeys.toMutableList()
        save()
    }
}
