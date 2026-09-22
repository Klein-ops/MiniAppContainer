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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch

/** 应用管理界面：分类栏 + 拖动排序 + 安装/卸载/权限管理/移动分类。 */
class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var adapter: AppListAdapter

    private var currentFilter: String? = com.miniapp.container.core.CategoryManager.DEFAULT

    private val hostApp: MiniAppApp get() = MiniAppApp.require(application)

    /** 安装流程内核：选包 → 二次确认（版本对比）→ 安装。 */
    private val installFlow: InstallFlow by lazy {
        InstallFlow(
            activity = this,
            registry = hostApp.registry,
            installer = hostApp.installer,
            currentCategory = { currentFilter },
            launchPickZip = { types -> pickZip.launch(types) },
            onInstalled = { category, appKey ->
                // 全新安装才归入当前分类；必须在 refresh() 的 syncApps 之前完成
                if (category != null && appKey != null) {
                    hostApp.categoryManager.moveTo(appKey, category)
                }
                refresh()
            },
            onRefresh = { refresh() }
        )
    }

    private val pickZip: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) lifecycleScope.launch { installFlow.installFromUri(uri) }
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
        findViewById<View>(R.id.install_card).setOnClickListener { installFlow.showOptions() }
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

    /** 当前是否在「设置」tab（设置页隐藏搜索按钮）。 */
    private var onSettingsTab = false

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_search)?.isVisible = !onSettingsTab
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_search) {
            showSearchDialog()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    /** 搜索对话框：实现见 [SearchDialog]。 */
    private fun showSearchDialog() {
        SearchDialog.show(this, hostApp.registry) { startMiniApp(it) }
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
            onSettingsTab = settings
            invalidateOptionsMenu()
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
        findViewById<android.view.View>(R.id.card_permission_status)
            .setOnClickListener { startActivity(Intent(this, com.miniapp.container.ui.PermissionStatusActivity::class.java)) }
        findViewById<android.view.View>(R.id.card_clean)
            .setOnClickListener { cleanWebViewCache() }
        // 主题：跟随系统 / 白天 / 黑夜（选中即生效，重建 Activity）
        val tvThemeDesc = findViewById<android.widget.TextView>(R.id.tv_theme_desc)
        fun updateThemeDesc() {
            tvThemeDesc.text = com.miniapp.container.sys.ThemeManager.labels()[com.miniapp.container.sys.ThemeManager.current(this)]
        }
        updateThemeDesc()
        findViewById<android.view.View>(R.id.card_theme).setOnClickListener {
            val labels = com.miniapp.container.sys.ThemeManager.labels()
            val current = com.miniapp.container.sys.ThemeManager.current(this)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("主题")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    com.miniapp.container.sys.ThemeManager.set(this, which)
                    updateThemeDesc()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .showRounded()
        }
        // 关于蜗壳：弹版本信息，含"查看 GitHub"入口
        findViewById<android.view.View>(R.id.card_about).setOnClickListener { showAbout() }
    }

    /** 清理 WebView 缓存与存储。 */
    private fun cleanWebViewCache() {
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
            IoUtil.clearCache(this)
        } catch (t: Throwable) { /* ignore */ }
        toast("已清理 WebView 缓存与存储")
    }

    private fun showAbout() {
        val appVer = try {
            packageManager.getPackageInfo(packageName, 0)?.versionName ?: "?"
        } catch (_: Throwable) { "?" }
        val apiVer = com.miniapp.container.bridge.MiniAppBridge.API_VERSION
        val webviewVer = try {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "?"
        } catch (_: Throwable) { "?" }
        val shizuku = try {
            val installed = runCatching {
                packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
            }.getOrDefault(false)
            when {
                !installed -> "未安装"
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED -> "已激活"
                else -> "未激活"
            }
        } catch (_: Throwable) { "未知" }
        MaterialAlertDialogBuilder(this)
            .setTitle("关于蜗壳")
            .setMessage("App 版本：$appVer\n接口版本：$apiVer\nWebView：$webviewVer\nShizuku：$shizuku")
            .setPositiveButton("查看 GitHub") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Klein-ops/MiniAppContainer")))
            }
            .setNegativeButton("关闭", null)
            .showRounded()
    }

}
