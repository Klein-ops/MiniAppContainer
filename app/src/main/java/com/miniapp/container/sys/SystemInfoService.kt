package com.miniapp.container.sys

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

/** 系统信息查询，供 JS Bridge 的 system.info 返回。 */
class SystemInfoService(private val context: Context) {

    fun info(appVersion: String): String {
        val dm = DisplayMetrics()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(dm)
        } catch (t: Throwable) {
            dm.density = 0f
        }
        return JSONObject()
            .put("platform", "android")
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("osVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("hostAppVersion", appVersion)
            .put("density", dm.density)
            .put("densityDpi", dm.densityDpi)
            .put("widthPixels", dm.widthPixels)
            .put("heightPixels", dm.heightPixels)
            .toString()
    }
}
