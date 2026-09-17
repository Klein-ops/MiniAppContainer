package com.miniapp.container.dex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 主进程侧的 Dex 执行客户端：绑定 [DexService]（隔离进程），
 * 打开 dex / 输入 / 输出 FD 并跨 Binder 传入，返回结果 Bundle。
 *
 * 隔离进程无法主动打开任何路径，只能读写主进程传入的 FD，因此不破坏沙箱。
 */
class DexRunner(private val context: Context) {

    private var connRef: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    suspend fun run(
        dexFile: File,
        input: File?,
        output: File?,
        params: Bundle
    ): Bundle = withContext(Dispatchers.IO) {
        val svc = bind() ?: throw IllegalStateException("无法连接 Dex 执行服务（设备可能不支持隔离进程）")
        var dexFd: ParcelFileDescriptor? = null
        var inputFd: ParcelFileDescriptor? = null
        var outputFd: ParcelFileDescriptor? = null
        try {
            dexFd = ParcelFileDescriptor.open(dexFile, ParcelFileDescriptor.MODE_READ_ONLY)
            inputFd = input?.let {
                ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            outputFd = output?.let {
                it.parentFile?.mkdirs()
                ParcelFileDescriptor.open(
                    it,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            }
            svc.run(params, dexFd, inputFd, outputFd)
        } finally {
            runCatching { dexFd?.close() }
            runCatching { inputFd?.close() }
            runCatching { outputFd?.close() }
            runCatching { context.unbindService(connRef) }
        }
    }

    /**
     * 绑定隔离进程服务。
     *
     * 加超时：若隔离进程启动失败（如历史版本中 Application 初始化抛异常导致进程崩溃），
     * `onServiceConnected` 永远不会回调，不加超时会让调用方永久挂起。
     */
    private suspend fun bind(): IDexService? = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        bindInternal()
    }

    private suspend fun bindInternal(): IDexService? = suspendCancellableCoroutine { cont ->
        val intent = Intent().setComponent(ComponentName(context, DexService::class.java))
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (cont.isActive) cont.resume(IDexService.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connRef = conn
        try {
            val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!ok && cont.isActive) cont.resume(null)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        /** 绑定隔离进程服务的超时（毫秒）。 */
        const val BIND_TIMEOUT_MS = 10_000L
    }
}
