package com.miniapp.container.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.miniapp.container.IStorageUserService
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 存储 UserService 的连接管理（进程级单例）。
 *
 * 首次调用时绑定并等待就绪（约数百毫秒），此后每次操作是 binder IPC（毫秒级）。
 * `daemon=false`：宿主进程死亡时服务自动停止，不留僵尸进程。
 *
 * 绑定回调在主线程派发，[acquire] 需在 IO 线程调用以避免死锁。
 */
object StorageUserServiceConnector {

    private const val BIND_TIMEOUT_MS = 5_000L
    private const val VERSION = 1

    private var service: IStorageUserService? = null

    @Synchronized
    fun acquire(context: Context): IStorageUserService {
        val alive = service?.let {
            runCatching { it.asBinder().isBinderAlive }.getOrDefault(false)
        } ?: false
        if (alive) return service!!

        val latch = CountDownLatch(1)
        val bound = arrayOfNulls<IStorageUserService>(1)
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, StorageUserService::class.java)
        )
            .processNameSuffix("storage")
            .daemon(false)
            .version(VERSION)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) {
                    bound[0] = IStorageUserService.Stub.asInterface(binder)
                    service = bound[0]
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            throw IOException("无法绑定存储服务: ${t.message}")
        }

        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            throw IOException("存储服务连接超时（Shizuku 未就绪？）")
        }
        return bound[0] ?: throw IOException("存储服务连接失败")
    }
}
