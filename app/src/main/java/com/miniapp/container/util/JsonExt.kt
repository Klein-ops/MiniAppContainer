package com.miniapp.container.util

import org.json.JSONObject

fun JSONObject.optStringOr(key: String, default: String = ""): String =
    if (has(key) && !isNull(key)) getString(key) else default

fun JSONObject.optStringList(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val a = optJSONArray(key) ?: return listOf(getString(key))
    val out = ArrayList<String>(a.length())
    for (i in 0 until a.length()) out.add(a.getString(i))
    return out
}

fun JSONObject.optLongOr(key: String, default: Long = 0L): Long =
    if (has(key) && !isNull(key)) getLong(key) else default

fun JSONObject.optBoolOr(key: String, default: Boolean = false): Boolean =
    if (has(key) && !isNull(key)) getBoolean(key) else default
