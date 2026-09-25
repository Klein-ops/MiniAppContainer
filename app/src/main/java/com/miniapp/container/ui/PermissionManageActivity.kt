package com.miniapp.container.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.core.MiniAppInfo

/** 权限管理页（应用列表外部入口）：查看/撤销/授予已声明权限。 */
class PermissionManageActivity : AppCompatActivity() {

    companion object { const val EXTRA_APP_KEY = "appKey" }

    private lateinit var app: MiniAppInfo
    private lateinit var adapter: PermissionListAdapter
    private val hostApp: MiniAppApp get() = MiniAppApp.require(application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appKey = intent.getStringExtra(EXTRA_APP_KEY)
        if (appKey == null) { finish(); return }
        val info = hostApp.registry.get(appKey)
        if (info == null) { finish(); return }
        app = info

        setContentView(R.layout.activity_permission_manage)
        setupBackToolbar(app.uname)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = PermissionListAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        adapter.onStateChange = { scope, state ->
            val pm = hostApp.permissionManager
            when (state) {
                PermState.GRANTED -> pm.recordGrant(app.appKey, scope)
                PermState.DENIED -> pm.revoke(app.appKey, scope)
                PermState.DENIED_FOREVER -> pm.recordDenyForever(app.appKey, scope)
            }
            // 不调 adapter.setState：拖拽中 rebind 会打断 Slider，UI 由 Slider 自身反映
        }
        refresh()
    }

    private fun refresh() {
        val pm = hostApp.permissionManager
        val list = app.permissions.map {
            val state = when {
                pm.isGranted(app.appKey, it) -> PermState.GRANTED
                pm.isDeniedForever(app.appKey, it) -> PermState.DENIED_FOREVER
                else -> PermState.DENIED
            }
            PermItem(it, required = it in app.requiredPermissions, state = state)
        }
        adapter.submit(list)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
