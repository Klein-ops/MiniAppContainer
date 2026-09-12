package com.miniapp.container.core

import org.json.JSONObject

/** 沙箱内 meta.json 的模型。 */
data class MetaInfo(
    val uid: String,
    val uname: String,
    val version: String,
    val entry: String,
    val wasm: List<String>,
    val permissions: List<String>,
    val requiredPermissions: List<String>,
    val installedAt: Long,
    val appKey: String,
    val sandboxPath: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uid", uid)
        .put("uname", uname)
        .put("version", version)
        .put("entry", entry)
        .put("wasm", org.json.JSONArray(wasm))
        .put("permissions", org.json.JSONArray(permissions))
        .put("requiredPermissions", org.json.JSONArray(requiredPermissions))
        .put("installedAt", installedAt)
        .put("appKey", appKey)
        .put("sandboxPath", sandboxPath)
}
