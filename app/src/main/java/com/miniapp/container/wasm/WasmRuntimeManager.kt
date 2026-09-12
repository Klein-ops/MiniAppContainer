package com.miniapp.container.wasm

import com.miniapp.container.core.PathGuard
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * WASM 执行层管理器（每个小程序会话一个实例，沙箱路径绑定该小程序）。
 *
 * 流程（文档六）：
 * 1. 前端通过 JS Bridge 请求执行 WASM；
 * 2. 宿主校验模块路径在沙箱内；
 * 3. WASM 运行时加载模块；
 * 4. 调用导出函数；
 * 5. 返回结果给前端。
 *
 * 出于安全与实现简单，所有原生调用串行化（[callLock]）。
 */
class WasmRuntimeManager(private val sandboxRoot: File) {

    private val callLock = Mutex()
    private val idSeq = AtomicLong(0)
    private val handles = HashMap<Long, Long>()   // 本地 id -> 原生句柄

    /** 加载沙箱内 WASM 模块，返回 {"handle": <id>} JSON。 */
    suspend fun load(path: String): String = withContext(Dispatchers.IO) {
        if (!WasmNative.ensureLoaded()) {
            throw IllegalStateException("WASM 运行时不可用（libminiapp_wasm.so 未加载）")
        }
        // wasm 模块属于应用资源，位于沙箱 app/ 下；路径相对 app/ 解析
        val resDir = java.io.File(sandboxRoot, "app")
        val file = PathGuard.resolveUnderRoot(resDir, path)
        if (!file.exists() || file.isDirectory) {
            throw java.io.FileNotFoundException("WASM 模块不存在或非文件: $path")
        }
        val bytes = IoUtil.readBytes(file)
        val nativeHandle = WasmNative.nativeLoadWasm(bytes, DEFAULT_STACK)
        if (nativeHandle == 0L) throw IllegalStateException("WASM 模块加载失败")
        val id = idSeq.incrementAndGet()
        synchronized(handles) { handles[id] = nativeHandle }
        "{\"handle\":$id}"
    }

    suspend fun call(id: Long, func: String, args: Array<String>): String = withContext(Dispatchers.IO) {
        val nativeHandle = synchronized(handles) { handles[id] }
            ?: throw IllegalArgumentException("无效的 wasm 句柄: $id")
        callLock.withLock {
            WasmNative.nativeCall(nativeHandle, func, args)
        }
    }

    suspend fun unload(id: Long): String = withContext(Dispatchers.IO) {
        val nativeHandle = synchronized(handles) { handles.remove(id) }
        if (nativeHandle != null && WasmNative.ensureLoaded()) {
            try { WasmNative.nativeUnload(nativeHandle) } catch (t: Throwable) { /* ignore */ }
        }
        "true"
    }

    fun unloadAll() {
        val snapshot: List<Pair<Long, Long>> = synchronized(handles) {
            val out = handles.entries.map { it.key to it.value }
            handles.clear()
            out
        }
        if (WasmNative.ensureLoaded()) {
            snapshot.forEach { (_, nativeHandle) ->
                try { WasmNative.nativeUnload(nativeHandle) } catch (t: Throwable) { /* ignore */ }
            }
        }
    }

    companion object {
        private const val DEFAULT_STACK = 64 * 1024
    }
}
