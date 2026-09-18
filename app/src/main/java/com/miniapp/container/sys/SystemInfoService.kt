package com.miniapp.container.sys

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.webkit.WebView
import android.view.WindowManager
import org.json.JSONObject

/**
 * 系统信息查询，供 JS Bridge 的 `system.info` 返回。
 *
 * 支持按需字段：`fields` 非空时只返回列出的字段，避免每次取回全量信息。
 */
class SystemInfoService(private val context: Context) {

    fun info(appVersion: String, apiVersion: String, fields: List<String>): String {
        val dm = DisplayMetrics()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(dm)
        } catch (t: Throwable) {
            dm.density = 0f
        }
        val full = JSONObject()
            .put("platform", "android")
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("osVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("hostAppVersion", appVersion)
            .put("apiVersion", apiVersion)
            .put("webviewVersion", webviewVersion())
            .put("density", dm.density)
            .put("densityDpi", dm.densityDpi)
            .put("widthPixels", dm.widthPixels)
            .put("heightPixels", dm.heightPixels)

        if (fields.isEmpty()) return full.toString()
        val out = JSONObject()
        fields.forEach { f -> if (full.has(f)) out.put(f, full.get(f)) }
        return out.toString()
    }

    /** 当前 WebView 实现版本（如 113.0.5672.136）。 */
    private fun webviewVersion(): String = try {
        WebView.getCurrentWebViewPackage()?.versionName ?: ""
    } catch (_: Throwable) {
        ""
    }
}
