package com.miniapp.container.core

import com.miniapp.container.util.optStringList
import com.miniapp.container.util.optStringOr
import org.json.JSONObject
import java.io.File

/** 应用包清单（manifest.json）模型与解析。 */
data class AppManifest(
    val uid: String,
    val uname: String,
    val version: String,
    val entry: String,
    val wasm: List<String>,
    val permissions: List<String>,
    val requiredPermissions: List<String>
) {
    companion object {
        fun parse(file: File): AppManifest? = try {
            parse(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (t: Throwable) {
            null
        }

        fun parse(o: JSONObject): AppManifest? {
            val uid = o.optStringOr("uid").trim()
            val uname = o.optStringOr("uname").trim()
            if (uid.isEmpty() || uname.isEmpty()) return null
            return AppManifest(
                uid = uid,
                uname = uname,
                version = o.optStringOr("version", "0.0.0"),
                entry = o.optStringOr("entry", "index.html"),
                wasm = o.optStringList("wasm"),
                permissions = o.optStringList("permissions"),
                requiredPermissions = o.optStringList("requiredPermissions")
            )
        }
    }
}
