package com.miniapp.container

import android.app.Application
import com.miniapp.container.core.AppInstaller
import com.miniapp.container.core.AppRegistry
import com.miniapp.container.core.SandboxManager
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.core.BackupService
import com.miniapp.container.core.CategoryManager
import java.io.File

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

    override fun onCreate() {
        super.onCreate()
        val base = File(filesDir, "miniapps")
        sandbox = SandboxManager(base)
        registry = AppRegistry(File(base, "registry.json"))
        permissionManager = PermissionManager(this)
        categoryManager = CategoryManager(File(base, "categories.json"))
        installer = AppInstaller(this, sandbox, registry, permissionManager)
        backupService = BackupService(this, installer)
    }

    companion object {
        fun get(app: Application): MiniAppApp = app as MiniAppApp
    }
}
