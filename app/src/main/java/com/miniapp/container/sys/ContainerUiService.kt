package com.miniapp.container.sys

import android.content.pm.ActivityInfo
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.miniapp.container.ui.MiniAppActivity
import com.miniapp.container.util.optBoolOr
import com.miniapp.container.util.optStringOr
import org.json.JSONObject

/**
 * 小程序对**自身容器界面**的控制：屏幕方向、状态栏显隐与颜色。
 *
 * 这些操作只影响当前小程序自己的容器窗口，不触碰系统其他部分，因此**无需权限审批**。
 */
class ContainerUiService(private val activity: MiniAppActivity) {

    // ==================== 屏幕方向 ====================

    /**
     * `sys.setOrientation(mode)`：
     * - `"portrait"`  强制竖屏
     * - `"landscape"` 强制横屏
     * - `"auto"`      自动（跟随传感器的默认行为）
     */
    fun setOrientation(p: JSONObject): String {
        val mode = p.optStringOr("mode", "auto")
        val orientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> return fail("invalid mode", "仅支持 portrait / landscape / auto")
        }
        activity.requestedOrientation = orientation
        return "true"
    }

    // ==================== 状态栏 ====================

    /** `sys.setStatusBar(visible)`：隐藏/显示状态栏。 */
    fun setStatusBar(p: JSONObject): String {
        val visible = p.optBoolOr("visible")
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        return "true"
    }

    /** `sys.setStatusBarColor(color)`：#RRGGBB / #AARRGGBB / `"transparent"`。 */
    fun setStatusBarColor(p: JSONObject): String {
        val raw = p.optStringOr("color", "#000000")
        val color = if (raw.equals("transparent", ignoreCase = true)) Color.TRANSPARENT
        else runCatching { Color.parseColor(raw) }.getOrNull()
            ?: return fail("invalid color", "支持 #RRGGBB / #AARRGGBB / transparent")
        activity.window.statusBarColor = color
        return "true"
    }

    private fun fail(error: String, detail: String = ""): String = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("detail", detail)
        .toString()
}
