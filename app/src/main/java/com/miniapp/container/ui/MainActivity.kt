package com.miniapp.container.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.launch
import java.io.File

/** 应用管理界面：列出已安装小程序、安装/卸载、启动。 */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: AppListAdapter

    private val hostApp: MiniAppApp get() = application as MiniAppApp

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch { installFromUri(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        recycler = findViewById(R.id.recycler)
        empty = findViewById(R.id.empty)

        adapter = AppListAdapter()
        adapter.onItemClick = { startMiniApp(it.appKey) }
        adapter.onItemLongClick = { confirmUninstall(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_install -> {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            true
        }
        R.id.action_install_sample -> {
            lifecycleScope.launch { installSample() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val list = hostApp.registry.list()
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private suspend fun installFromUri(uri: Uri) {
        val cache = File(cacheDir, "pick_${System.currentTimeMillis()}.zip")
        try {
            val ok = try {
                contentResolver.openInputStream(uri)?.use { IoUtil.copy(it, cache); true } ?: false
            } catch (t: Throwable) {
                false
            }
            if (!ok) {
                toast("无法读取文件")
                return
            }
            val r = hostApp.installer.installFromZip(cache)
            toast(if (r.success) "已安装: ${r.info?.uid}/${r.info?.uname}" else "安装失败: ${r.message}")
        } finally {
            cache.delete()
            refresh()
        }
    }

    private suspend fun installSample() {
        val r = hostApp.installer.installFromAssets("sample/sample_app.zip")
        toast(if (r.success) "示例已安装" else "示例安装失败: ${r.message}")
        refresh()
    }

    private fun confirmUninstall(info: MiniAppInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("卸载")
            .setMessage("卸载 ${info.uid}/${info.uname}？\n沙箱数据将被清除。")
            .setPositiveButton("卸载") { _, _ ->
                lifecycleScope.launch {
                    hostApp.installer.uninstall(info.appKey)
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startMiniApp(appKey: String) {
        startActivity(
            Intent(this, MiniAppActivity::class.java)
                .putExtra(MiniAppActivity.EXTRA_APP_KEY, appKey)
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
