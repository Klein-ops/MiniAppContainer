package com.miniapp.container

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.miniapp.container.core.AppInstaller
import com.miniapp.container.core.AppRegistry
import com.miniapp.container.core.BackupService
import com.miniapp.container.core.CategoryManager
import com.miniapp.container.core.SandboxManager
import com.miniapp.container.debug.DebugBus
import com.miniapp.container.permission.PermissionManager
import java.io.File

/**
 * 应用级单例：持有沙箱、注册表、安装器、权限管理器、分类管理器、备份服务。
 *
 * 初始化通过 [ensureInitialized] 完成，**幂等**，且在两种环境下会**主动跳过**：
 *
 * 1. **隔离进程**（`android:isolatedProcess="true"`，即 `dex.run` 的执行进程）：
 *    该进程没有应用数据目录、无法访问系统服务。若在此创建 [BackupService]，
 *    其内部的 `getSharedPreferences` 会抛
 *    `SharedPreferences in credential encrypted storage are not available until after user is unlocked`
 *    导致进程启动即崩溃（表现为 `dex.run` 后应用异常）。
 * 2. **Direct Boot 阶段**（用户尚未解锁，如 exported 的 ContentProvider 被系统或
 *    其他应用在锁屏时查询）：此时 credential encrypted storage（含 `filesDir` 与
 *    SharedPreferences）不可访问。此情形下注册 `ACTION_USER_UNLOCKED` 接收器，
 *    解锁后再初始化。
 *
 * 因此**各 Activity / Provider 入口都应先调用 [ensureInitialized]**（解锁后必然成功）。
 */
class MiniAppApp : Application() {

    lateinit var sandbox: SandboxManager
        private set
    lateinit var registry: AppRegistry
        private set
    lateinit var installer: AppInstaller
        private set
    lateinit var permissionManager: PermissionManager
        private set
    lateinit var categoryManager: CategoryManager
        private set
    lateinit var backupService: BackupService
        private set

    @Volatile
    private var initialized = false

    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ensureInitialized()
    }

    /** 幂等初始化。隔离进程与未解锁阶段会跳过或延迟。 */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return

        // 1) 隔离进程：无数据目录、无系统服务，不初始化任何应用状态
        if (Process.isIsolated()) return

        // 2) 未解锁：延迟到解锁后（此时 credential storage 不可访问）
        if (!isUserUnlocked()) {
            registerUnlockReceiver()
            return
        }

        val base = File(filesDir, "miniapps")
        sandbox = SandboxManager(base)
        registry = AppRegistry(base)   // 数据源 = 各沙箱 meta.json（随应用走）
        permissionManager = PermissionManager(this)
        categoryManager = CategoryManager(File(base, "categories.json"), registry)
        installer = AppInstaller(this, sandbox, registry, permissionManager)
        backupService = BackupService(this, installer)

        // 调试日志落盘：应用启动即清空（日志只保留本次会话）
        DebugBus.attach(File(filesDir, "debug/log.txt"))

        initialized = true
        unregisterUnlockReceiver()
    }

    /** 是否已完成初始化（隔离进程与未解锁阶段为 false）。 */
    fun isReady(): Boolean = initialized

    private fun isUserUnlocked(): Boolean = try {
        (getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    } catch (_: Throwable) {
        // 无法查询（含隔离进程等情形）：按未解锁处理，避免触碰 credential storage
        false
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                ensureInitialized()
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            unlockReceiver = receiver
        } catch (_: Throwable) {
            // 注册失败不影响后续：Activity 入口调用 ensureInitialized 时会重试
        }
    }

    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
    }

    companion object {
        fun get(app: Application): MiniAppApp = app as MiniAppApp

        /**
         * 取得 [MiniAppApp] 并确保已初始化（用于 Activity / Provider 入口）。
         * 接受任意 Context（Application 亦兼容）。
         */
        fun require(context: Context): MiniAppApp =
            (context.applicationContext as MiniAppApp).also { it.ensureInitialized() }
    }
}
