package com.miniapp.container.sys

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 宿主主题模式：跟随系统 / 强制白天 / 强制黑夜。
 *
 * 偏好持久化于 [PREFS]（与 DebugBus 等共用），App 启动时 [apply] 一次，
 * 用户在设置页 [set] 后立即生效（setDefaultNightMode 会重建 Activity）。
 */
object ThemeManager {
    const val FOLLOW = 0
    const val LIGHT = 1
    const val DARK = 2

    private const val PREFS = "host_prefs"
    private const val KEY = "theme_mode"

    fun labels(): Array<String> = arrayOf("跟随系统", "白天", "黑夜")

    fun current(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, FOLLOW)

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (current(context)) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun set(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
        apply(context)
    }
}
