package com.miniapp.container.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 内部储存访问方式。 */
enum class StorageMode {
    /** 传统：MANAGE_EXTERNAL_STORAGE（Android 11+）/ 运行时权限（10 及以下）。 */
    SYSTEM,

    /** Shizuku：借 ADB 权限读写，Android 11+ 亦可访问 `sdcard/Android`。 */
    SHIZUKU;

    companion object {
        fun from(raw: String?): StorageMode =
            values().firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** 规则类型：黑名单（命中即禁）/ 白名单（未命中即禁）。 */
enum class RuleType {
    BLACKLIST,
    WHITELIST
}

/** 路径匹配方式。 */
enum class MatchMode {
    /** 前缀：路径本身 + 其下所有子目录（如 `/sdcard` 命中 `/sdcard/DCIM`）。 */
    PREFIX,

    /** 精确：仅路径本身，不含子目录。 */
    EXACT
}

/**
 * 一条内部储存访问规则。
 *
 * @param id        唯一标识（增删改定位用）
 * @param type      黑名单 / 白名单
 * @param path      匹配路径前缀或精确路径
 * @param enabled   是否启用（停用的规则不参与匹配）
 * @param matchMode 前缀匹配 / 精确匹配
 * @param appKeys   作用域：空 = 所有小程序；非空 = 仅这些 appKey
 */
data class PathRule(
    val id: String,
    val type: RuleType,
    val path: String,
    val enabled: Boolean = true,
    val matchMode: MatchMode = MatchMode.PREFIX,
    val appKeys: Set<String> = emptySet()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("path", path)
        .put("enabled", enabled)
        .put("matchMode", matchMode.name)
        .put("appKeys", JSONArray().also { a -> appKeys.forEach { a.put(it) } })

    companion object {
        fun fromJson(o: JSONObject): PathRule? = try {
            PathRule(
                id = o.getString("id"),
                type = RuleType.valueOf(o.getString("type")),
                path = o.getString("path"),
                enabled = o.optBoolean("enabled", true),
                matchMode = runCatching { MatchMode.valueOf(o.getString("matchMode")) }
                    .getOrDefault(MatchMode.PREFIX),
                appKeys = o.optJSONArray("appKeys")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optString(i) }.toSet()
                } ?: emptySet()
            )
        } catch (_: Throwable) { null }
    }
}

/**
 * 内部储存访问方式的全局偏好（所有小程序统一生效）。
 *
 * 默认 [StorageMode.SYSTEM]：保持"直接申请系统存储权限"的老路。
 * 小程序的 `fs.*External` 接口行为不变，仅底层实现随本设置切换。
 * 访问规则（黑/白名单）见 [rules] / [saveRules]。
 */
class StorageAccessConfig(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences?
        get() = try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }

    var mode: StorageMode
        get() = StorageMode.from(prefs?.getString(KEY_MODE, null))
        set(value) {
            prefs?.edit()?.putString(KEY_MODE, value.name)?.apply()
        }

    /** 首次切 Shizuku 模式的风险告知是否已读过（避免重复弹窗）。 */
    var shizukuWarned: Boolean
        get() = prefs?.getBoolean(KEY_SHIZUKU_WARNED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SHIZUKU_WARNED, value)?.apply()
        }

    /** 全部访问规则（每次读时解析，量小无性能顾虑）。 */
    fun rules(): List<PathRule> = try {
        val arr = JSONArray(prefs?.getString(KEY_RULES, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> PathRule.fromJson(arr.optJSONObject(i)) }
    } catch (_: Throwable) {
        emptyList()
    }

    /** 保存全部规则（覆盖）。 */
    fun saveRules(list: List<PathRule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString(KEY_RULES, arr.toString())?.apply()
    }

    companion object {
        private const val PREFS = "storage_access"
        private const val KEY_MODE = "mode"
        private const val KEY_SHIZUKU_WARNED = "shizuku_warned"
        private const val KEY_RULES = "rules"
    }
}