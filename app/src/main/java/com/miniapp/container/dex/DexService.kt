package com.miniapp.container.dex

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import java.nio.ByteBuffer

/**
 * 隔离进程 Dex 执行服务（android:isolatedProcess="true"）。
 *
 * 运行于独立 UID + SELinux isolated_app 域，**不继承宿主任何权限**：
 * 无网络、无路径访问、无系统服务、不能加载 native 库、不能访问宿主自定义类。
 * 仅能通过 Binder 传入的 FD 读写数据，天然沙箱。
 *
 * 约定的 dex 入口：静态方法
 * `public static Bundle run(Bundle params, ParcelFileDescriptor inputFd, ParcelFileDescriptor outputFd)`。
 * dex 内只允许使用 Android 框架类与 Java 标准库。
 */
class DexService : Service() {

    private val binder = object : IDexService.Stub() {
        override fun run(
            params: Bundle?,
            dexFd: ParcelFileDescriptor?,
            inputFd: ParcelFileDescriptor?,
            outputFd: ParcelFileDescriptor?
        ): Bundle {
            val result = Bundle()
            try {
                if (params == null || dexFd == null) {
                    result.putString("error", "缺少 params 或 dexFd")
                    return result
                }
                val className = params.getString("__className")
                    ?: return result.apply { putString("error", "缺少 className") }
                val methodName = params.getString("__methodName") ?: "run"

                // 读取 dex 字节（仅通过传入 FD，无法主动打开路径）
                val dexBytes = java.io.FileInputStream(dexFd.fileDescriptor).use { it.readBytes() }
                // parent 用 BootClassLoader：只能访问 Android 框架类 + Java 标准库
                val loader = InMemoryDexClassLoader(
                    ByteBuffer.wrap(dexBytes),
                    java.lang.ClassLoader.getSystemClassLoader()
                )
                val clazz = loader.loadClass(className)
                val method = clazz.getMethod(
                    methodName,
                    Bundle::class.java,
                    ParcelFileDescriptor::class.java,
                    ParcelFileDescriptor::class.java
                )
                method.isAccessible = true
                val out = method.invoke(null, params, inputFd, outputFd) as? Bundle
                if (out != null) result.putAll(out)
                result.putBoolean("ok", true)
            } catch (t: Throwable) {
                result.putString("error", t.toString())
            }
            return result
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
