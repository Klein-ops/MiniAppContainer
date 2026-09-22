package com.miniapp.container.core

import com.miniapp.container.util.optStringList
import com.miniapp.container.util.optLongOr
import com.miniapp.container.util.optStringOr
import org.json.JSONArray
import org.json.JSONObject

/** 注册表中的一条已安装小程序记录。 */
data class MiniAppInfo(
    val uid: String,
    val uname: String,
    val version: String,
    val entry: String,
    val wasm: List<String>,
    val permissions: List<String>,
    val requiredPermissions: List<String>,
    val icon: String = "",
    val displayName: String = "",
    val installedAt: Long,
    /** 所属分类（宿主写；随 meta.json 备份，恢复时"存在则移入、不存在则创建"）。 */
    val category: String = "",
    /** 安装来源 URL（仅从 URL 安装时记录，便于追溯）。 */
    val sourceUrl: String = ""
) {
    val appKey: String get() = "${uid}_${uname}"

    fun toJson(): JSONObject {
        val wasmArr = JSONArray()
        wasm.forEach { wasmArr.put(it) }
        val permArr = JSONArray()
        permissions.forEach { permArr.put(it) }
        val reqArr = JSONArray()
        requiredPermissions.forEach { reqArr.put(it) }
        return JSONObject()
            .put("uid", uid)
            .put("uname", uname)
            .put("version", version)
            .put("entry", entry)
            .put("wasm", wasmArr)
            .put("permissions", permArr)
            .put("requiredPermissions", reqArr)
            .put("installedAt", installedAt)
            .put("icon", icon)
            .put("displayName", displayName)
            .put("category", category)
            .put("sourceUrl", sourceUrl)
            .put("appKey", appKey)
    }

    companion object {
        fun fromJson(o: JSONObject): MiniAppInfo = MiniAppInfo(
            uid = o.optStringOr("uid"),
            uname = o.optStringOr("uname"),
            version = o.optStringOr("version", "0.0.0"),
            entry = o.optStringOr("entry", "index.html"),
            wasm = o.optStringList("wasm"),
            permissions = o.optStringList("permissions"),
            requiredPermissions = o.optStringList("requiredPermissions"),
            icon = o.optStringOr("icon", ""),
            displayName = o.optStringOr("displayName", ""),
            installedAt = o.optLongOr("installedAt", System.currentTimeMillis()),
            category = o.optStringOr("category", ""),
            sourceUrl = o.optStringOr("sourceUrl", "")
        )
    }
}
