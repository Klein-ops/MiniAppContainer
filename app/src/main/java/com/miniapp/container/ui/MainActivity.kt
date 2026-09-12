package com.miniapp.container.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionScope
import com.miniapp.container.util.IoUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 应用管理界面：分类栏 + 拖动排序 + 安装/卸载/权限管理/移动分类。 */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var adapter: AppListAdapter

    private var currentFilter: String? = com.miniapp.container.core.CategoryManager.DEFAULT

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
        categoryBar = findViewById(R.id.category_bar)
        categoryScroll = findViewById(R.id.category_scroll)

        adapter = AppListAdapter()
        adapter.onItemClick = { startMiniApp(it) }
        adapter.onSettingsClick = { openAppSettings(it.appKey) }
        adapter.onItemClick = { startMiniApp(it) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // 长按拖动排序
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.move(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                saveOrder()
            }
        })
        touchHelper.attachToRecyclerView(recycler)
    }

    override fun onResume() {
        super.onResume()
        refreshCategoryBar()
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
        R.id.action_clean_storage -> {
            cleanStorage()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ===== 分类栏 =====
    private fun refreshCategoryBar() {
        categoryBar.removeAllViews()
        val cats = hostApp.categoryManager.listCategories()

        // "全部" chip
        addChip("全部", currentFilter == null) {
            currentFilter = null
            refreshCategoryBar()
            refresh()
        }
        // 各分类 chip（长按可删除非默认分类）
        for (cat in cats) {
            addChip(cat, currentFilter == cat, {
                currentFilter = cat
                refreshCategoryBar()
                refresh()
            }) {
                if (cat != com.miniapp.container.core.CategoryManager.DEFAULT) {
                    confirmDeleteCategory(cat)
                }
            }
        }
        // "+ 添加分类"
        addChip("+", false, { showAddCategoryDialog() }) {}
        categoryScroll.scrollTo(0, 0)
    }

    private fun addChip(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
        val tv = LayoutInflater.from(this).inflate(R.layout.item_category_chip, categoryBar, false) as TextView
        tv.text = text
        tv.setBackgroundResource(if (selected) R.drawable.chip_selected else R.drawable.chip_unselected)
        tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt())
        tv.setOnClickListener { onClick() }
        tv.setOnLongClickListener { onLongClick(); true }
        categoryBar.addView(tv)
    }

    private fun confirmDeleteCategory(cat: String) {
        AlertDialog.Builder(this)
            .setTitle("删除分类")
            .setMessage("删除分类「$cat」？\n其中的小程序将移回「默认」。")
            .setPositiveButton("删除") { _, _ ->
                hostApp.categoryManager.removeCategory(cat)
                if (currentFilter == cat) currentFilter = com.miniapp.container.core.CategoryManager.DEFAULT
                refreshCategoryBar()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "分类名称"
        }
        AlertDialog.Builder(this)
            .setTitle("新建分类")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (hostApp.categoryManager.addCategory(name)) {
                    refreshCategoryBar()
                    toast("已创建分类: $name")
                } else {
                    toast("分类名无效或已存在")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 显示"移动到分类"对话框。 */
    private fun showMoveToCategory(info: MiniAppInfo) {
        val cats = hostApp.categoryManager.listCategories().toMutableList()
        cats.add("＋ 新建分类…")
        AlertDialog.Builder(this)
            .setTitle("移动 ${info.uname} 到分类")
            .setItems(cats.toTypedArray()) { _, which ->
                if (which == cats.size - 1) {
                    showAddCategoryAndMove(info)
                } else {
                    hostApp.categoryManager.moveTo(info.appKey, cats[which])
                    refresh()
                    toast("已移到 ${cats[which]}")
                }
            }
            .show()
    }

    private fun showAddCategoryAndMove(info: MiniAppInfo) {
        val input = android.widget.EditText(this).apply { hint = "分类名称" }
        AlertDialog.Builder(this)
            .setTitle("新建分类并移入")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (hostApp.categoryManager.addCategory(name)) {
                    hostApp.categoryManager.moveTo(info.appKey, name)
                    refreshCategoryBar()
                    refresh()
                    toast("已移到 $name")
                } else toast("分类名无效或已存在")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 列表刷新 =====
    private fun refresh() {
        val cm = hostApp.categoryManager
        val all = hostApp.registry.list()
        cm.syncApps(all.map { it.appKey })   // 同步：新安装的加入默认，卸载的移除
        val allApps = all.associateBy { it.appKey }
        val order = if (currentFilter == null) cm.appsInFiltered(null) else cm.appsIn(currentFilter!!)
        val list = order.mapNotNull { allApps[it] }
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 拖动结束后保存顺序到当前分类。 */
    private fun saveOrder() {
        val cm = hostApp.categoryManager
        val appKeys = (0 until adapter.itemCount).mapNotNull { adapter.appKeyAt(it) }
        val target = currentFilter ?: com.miniapp.container.core.CategoryManager.DEFAULT
        cm.setOrder(target, appKeys)
    }

    // ===== 启动小程序（必要权限检查）=====
    private fun startMiniApp(app: MiniAppInfo) {
        val pm = hostApp.permissionManager
        val ungranted = app.requiredPermissions.filter { !pm.isGranted(app.appKey, it) }
        if (ungranted.isEmpty()) {
            launchMiniApp(app.appKey)
            return
        }
        val msg = "该小程序需要以下权限才能运行：\n" +
                ungranted.joinToString("\n") { "• " + PermissionScope.label(it) }
        AlertDialog.Builder(this)
            .setTitle("必要权限")
            .setMessage(msg)
            .setPositiveButton("允许") { _, _ ->
                ungranted.forEach { pm.recordGrant(app.appKey, it) }
                launchMiniApp(app.appKey)
            }
            .setNegativeButton("拒绝") { _, _ ->
                Toast.makeText(this, "未获得必要权限，无法运行", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun launchMiniApp(appKey: String) {
        startActivity(
            Intent(this, MiniAppActivity::class.java).putExtra(MiniAppActivity.EXTRA_APP_KEY, appKey)
        )
    }

    private fun openAppSettings(appKey: String) {
        startActivity(Intent(this, AppSettingsActivity::class.java).putExtra(AppSettingsActivity.EXTRA_APP_KEY, appKey))
    }

    private fun openPermissionManage(appKey: String) {
        startActivity(
            Intent(this, PermissionManageActivity::class.java)
                .putExtra(PermissionManageActivity.EXTRA_APP_KEY, appKey)
        )
    }

    private fun confirmUninstall(info: MiniAppInfo) {
        AlertDialog.Builder(this)
            .setTitle("卸载")
            .setMessage("卸载 ${info.uname}？\n沙箱数据与权限记录将被清除。")
            .setPositiveButton("卸载") { _, _ ->
                lifecycleScope.launch {
                    hostApp.installer.uninstall(info.appKey)
                    refreshCategoryBar()
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun installFromUri(uri: Uri) {
        val cache = File(cacheDir, "pick_${System.currentTimeMillis()}.zip")
        try {
            val ok = try {
                contentResolver.openInputStream(uri)?.use { IoUtil.copy(it, cache); true } ?: false
            } catch (t: Throwable) { false }
            if (!ok) { toast("无法读取文件"); return }
            val r = hostApp.installer.installFromZip(cache)
            toast(if (r.success) "已安装: ${r.info?.uname}" else "安装失败: ${r.message}")
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

    private fun cleanStorage() {
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
            IoUtil.clearCache(this)
        } catch (t: Throwable) { /* ignore */ }
        toast("已清理 WebView 缓存与存储")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
