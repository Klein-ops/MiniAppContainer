package com.miniapp.container

import android.app.Application
import com.miniapp.container.core.AppInstaller
import com.miniapp.container.core.AppRegistry
import com.miniapp.container.core.SandboxManager
import com.miniapp.container.permission.PermissionManager
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

    override fun onCreate() {
        super.onCreate()
        val base = File(filesDir, "miniapps")
        sandbox = SandboxManager(base)
        registry = AppRegistry(File(base, "registry.json"))
        permissionManager = PermissionManager(this)
        installer = AppInstaller(this, sandbox, registry, permissionManager)
    }

    companion object {
        fun get(app: Application): MiniAppApp = app as MiniAppApp
    }
}
