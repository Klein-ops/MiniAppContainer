package com.miniapp.container.sys

import android.content.Context
import android.webkit.WebView

/**
 * WebView 渲染方式开关：硬件加速 / 软件渲染。
 *
 * 背景：部分老设备（如骁龙 820/Adreno 530 + Android 8.0 的 MIUI）的系统 WebView
 * 在硬件加速渲染下存在 GPU 驱动 bug——FBO 销毁时 free 未分配地址，导致
 * Chrome_InProcGp 线程 SIGABRT 崩溃（崩溃栈位于 /system/vendor/lib64/egl/libGLESv2_adreno.so）。
 * 这是系统驱动问题，宿主/小程序代码均无法直接规避，唯一可行绕开方案是关闭 WebView 硬件加速
 * 改用软件渲染（宿主其他 UI 不受影响）。
 *
 * 偏好持久化于 [PREFS]（与 ThemeManager 共用 host_prefs），默认开启硬件加速（性能优先），
 * 用户可在设置页关闭以兼容老设备。
 */
object WebViewRender {
    private const val PREFS = "host_prefs"
    private const val KEY = "webview_hardware_accelerated"

    /** 当前是否启用硬件加速（默认 true）。隔离进程/未解锁阶段抛错时回退 true。 */
    fun isHardwareAccelerated(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)
    } catch (_: Throwable) {
        true
    }

    /** 用户切换开关后持久化。 */
    fun setHardwareAccelerated(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /**
     * 将开关应用到 WebView：关闭时禁用硬件加速（LAYER_TYPE_SOFTWARE 软渲染），
     * 开启时恢复默认（LAYER_TYPE_HARDWARE，跟随应用级硬件加速设置）。
     * 在 [WebView.loadUrl] 之前调用才有效。
     */
    fun apply(webView: WebView) {
        if (isHardwareAccelerated(webView.context)) {
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        } else {
            webView.setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
        }
    }
}
