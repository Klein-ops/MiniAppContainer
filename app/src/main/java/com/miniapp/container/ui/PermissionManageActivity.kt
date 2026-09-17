package com.miniapp.container.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
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
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.title = app.uname
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = PermissionListAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        adapter.onToggle = { scope, granted ->
            val pm = hostApp.permissionManager
            if (granted) pm.recordGrant(app.appKey, scope) else pm.revoke(app.appKey, scope)
            adapter.setGranted(scope, granted)
        }
        refresh()
    }

    private fun refresh() {
        val pm = hostApp.permissionManager
        val list = app.permissions.map {
            PermItem(it, required = it in app.requiredPermissions, granted = pm.isGranted(app.appKey, it))
        }
        adapter.submit(list)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
