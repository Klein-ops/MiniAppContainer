package com.miniapp.container.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.InputDialog
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.toast
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

    private val hostApp: MiniAppApp get() = MiniAppApp.require(application)

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
        findViewById<View>(R.id.install_card).setOnClickListener { showInstallOptions() }
        setupBottomNav()
        setupSettingsPage()
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



    // ===== 分类栏 =====
    private fun refreshCategoryBar() {
        categoryBar.removeAllViews()
        val cats = hostApp.categoryManager.listCategories()

        // "全部" chip
        addChip("全部", currentFilter == null, {
            currentFilter = null
            refreshCategoryBar()
            refresh()
        }, {})
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
        MaterialAlertDialogBuilder(this)
            .setTitle("删除分类")
            .setMessage("删除分类「$cat」？\n其中的小程序将移回「默认」。")
            .setPositiveButton("删除") { _, _ ->
                hostApp.categoryManager.removeCategory(cat)
                if (currentFilter == cat) currentFilter = com.miniapp.container.core.CategoryManager.DEFAULT
                refreshCategoryBar()
                refresh()
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    private fun showAddCategoryDialog() {
        val (view, input) = InputDialog.create(this, "分类名称")
        MaterialAlertDialogBuilder(this)
            .setTitle("新建分类")
            .setView(view)
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
            .showRounded()
    }

    /** 显示"移动到分类"对话框。 */
    private fun showMoveToCategory(info: MiniAppInfo) {
        val cats = hostApp.categoryManager.listCategories().toMutableList()
        cats.add("＋ 新建分类…")
        MaterialAlertDialogBuilder(this)
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
            .showRounded()
    }

    private fun showAddCategoryAndMove(info: MiniAppInfo) {
        val (view, input) = InputDialog.create(this, "分类名称")
        MaterialAlertDialogBuilder(this)
            .setTitle("新建分类并移入")
            .setView(view)
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
            .showRounded()
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
        // 冗余过滤：危险权限不可作为必要权限（安装时已剔除，此处防旧数据）
        val ungranted = app.requiredPermissions
            .filter { PermissionScope.canBeRequired(it) }
            .filter { !pm.isGranted(app.appKey, it) }
        if (ungranted.isEmpty()) {
            launchMiniApp(app.appKey)
            return
        }
        val msg = "该小程序需要以下权限才能运行：\n" +
                ungranted.joinToString("\n") { "• " + PermissionScope.label(it) }
        MaterialAlertDialogBuilder(this)
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
            .showRounded()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_search) {
            showSearchDialog()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    /** 搜索对话框：按名称/标识实时过滤，点击直接启动。 */
    private fun showSearchDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_search, null)
        val et = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_search)
        val lv = view.findViewById<ListView>(R.id.list_search)
        val empty = view.findViewById<android.widget.TextView>(R.id.tv_search_empty)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("搜索小程序")
            .setView(view)
            .setNegativeButton("关闭", null)
            .showRounded()

        var apps = emptyList<com.miniapp.container.core.MiniAppInfo>()
        fun refresh(query: String) {
            val q = query.trim()
            apps = hostApp.registry.list().filter {
                q.isEmpty() ||
                    it.displayName.contains(q, ignoreCase = true) ||
                    it.uname.contains(q, ignoreCase = true)
            }
            lv.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                apps.map { app ->
                    val name = app.displayName.ifBlank { app.uname }
                    "$name  ·  ${app.appKey}"
                }
            )
            empty.visibility = if (apps.isEmpty() && q.isNotEmpty()) View.VISIBLE else View.GONE
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        lv.setOnItemClickListener { _, _, pos, _ ->
            val app = apps.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            startMiniApp(app)
        }

        refresh("")
        et.requestFocus()
        et.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun launchMiniApp(appKey: String) {
        startActivity(miniAppIntent(appKey))
    }

    /**
     * 构造小程序启动 Intent：
     * - data = miniapp://<appKey>，使每个小程序成为独立 task（多开互不干扰）
     * - FLAG_ACTIVITY_NEW_DOCUMENT 配合 activity 的 documentLaunchMode=intoExisting
     */
    private fun miniAppIntent(appKey: String): Intent =
        Intent(this, MiniAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("miniapp://$appKey"))
            .putExtra(MiniAppActivity.EXTRA_APP_KEY, appKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    private fun showUrlInstallDialog() {
        val (view, input) = InputDialog.create(
            this, "https://example.com/app.zip", inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("从 URL 安装")
            .setMessage("输入 zip 文件直链")
            .setView(view)
            .setPositiveButton("下载安装") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) lifecycleScope.launch { installFromUrl(url) }
            }.setNegativeButton("取消", null).showRounded()
    }

    private suspend fun installFromUrl(url: String) {
        val zipFile = java.io.File(cacheDir, "remote_${System.currentTimeMillis()}.zip")
        try {
            Toast.makeText(this, "下载中…", Toast.LENGTH_SHORT).show()
            withContext(Dispatchers.IO) {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    zipFile.outputStream().use { input.copyTo(it) }
                }
                conn.disconnect()
            }
            val result = hostApp.installer.installFromZip(zipFile)
            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            zipFile.delete()
        }
        refresh()
    }

    private fun showInstallOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle("安装")
            .setItems(arrayOf("安装应用包（zip）", "安装内置示例", "从 URL 安装")) { _, which ->
                when (which) {
                    0 -> pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    1 -> lifecycleScope.launch { installSample() }
                    2 -> showUrlInstallDialog()
                }
            }.showRounded()
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
        MaterialAlertDialogBuilder(this)
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
            .showRounded()
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


    // ===== 底部导航：应用 / 设置 同容器切换（不新开页面，选中态正常高亮）=====
    private fun setupBottomNav() {
        val pageApps = findViewById<View>(R.id.page_apps)
        val pageSettings = findViewById<View>(R.id.page_settings)
        // 显式初始化可见性（不依赖 <include> 上的 android:visibility）
        pageApps.visibility = View.VISIBLE
        pageSettings.visibility = View.GONE
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val bottomNav =
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            val settings = item.itemId == R.id.nav_settings
            val show = if (settings) pageSettings else pageApps
            val hide = if (settings) pageApps else pageSettings
            toolbar.title = if (settings) "设置" else getString(R.string.title_app_list)
            // 淡入切换
            hide.visibility = View.GONE
            show.alpha = 0f
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).setDuration(180).start()
            true
        }
    }

    // ===== 设置页各项 =====
    private fun setupSettingsPage() {
        val switchDebug = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_debug)
        switchDebug.isChecked = com.miniapp.container.debug.DebugBus.enabled
        switchDebug.setOnCheckedChangeListener { _, checked ->
            com.miniapp.container.debug.DebugBus.setEnabled(checked)
            toast(if (checked) "调试模式已开启" else "调试模式已关闭")
        }
        findViewById<android.view.View>(R.id.card_debug_log)
            .setOnClickListener { startActivity(Intent(this, DebugActivity::class.java)) }
        findViewById<android.view.View>(R.id.card_backup)
            .setOnClickListener { startActivity(Intent(this, BackupActivity::class.java)) }
        findViewById<android.view.View>(R.id.card_netdisk)
            .setOnClickListener { startActivity(Intent(this, WebdavConfigActivity::class.java)) }
        findViewById<android.view.View>(R.id.card_clean)
            .setOnClickListener { cleanWebViewCache() }
        // 关于蜗壳：点击卡片直接打开 GitHub 仓库
        findViewById<android.view.View>(R.id.card_about).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Klein-ops/MiniAppContainer"))
            )
        }
    }

    /** 清理 WebView 缓存与存储。 */
    private fun cleanWebViewCache() {
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
            IoUtil.clearCache(this)
        } catch (t: Throwable) { /* ignore */ }
        toast("已清理 WebView 缓存与存储")
    }

}
