package com.miniapp.container.wasm

import android.util.Log

/**
 * WASM 执行层原生入口（JNI -> wasm3）。
 *
 * 模块字节在宿主侧加载（沙箱路径校验后由 [WasmRuntimeManager] 读入），
 * 不依赖原生二进制执行，不受私有目录执行限制影响。
 */
object WasmNative {

    private const val TAG = "WasmNative"

    @Volatile
    private var loaded = false

    /** 加载 libminiapp_wasm.so。成功返回 true。 */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("miniapp_wasm")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "WASM 原生运行时加载失败，WASM 能力不可用：${t.message}")
            false
        }
        return loaded
    }

    /**
     * 加载一个 WASM 模块。返回运行时实例句柄（>0）。
     * 失败抛出 RuntimeException，错误信息为 wasm3 返回串。
     */
    @JvmStatic
    external fun nativeLoadWasm(bytes: ByteArray, stackSize: Int): Long

    /**
     * 调用模块导出函数。返回 JSON 结果值字符串（成功）。
     * 失败抛出 RuntimeException。
     */
    @JvmStatic
    external fun nativeCall(handle: Long, func: String, args: Array<String>): String

    /** 卸载实例，释放运行时与模块字节缓冲。 */
    @JvmStatic
    external fun nativeUnload(handle: Long)
}
