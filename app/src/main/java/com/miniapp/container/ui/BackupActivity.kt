package com.miniapp.container.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.core.BackupError
import com.miniapp.container.core.WebdavError
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份与恢复：本地 zip + WebDAV（备份内容精简、可选应用与数据、WebDAV 可删备份）。 */
class BackupActivity : AppCompatActivity() {

    private val hostApp by lazy { MiniAppApp.get(application) }
    private val backup by lazy { hostApp.backupService }
    private val webdavConfig by lazy { WebdavConfig(this) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { exportToLocal(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFromLocal(it) } }

    private var pendingKeys: List<String> = emptyList()
    private var pendingData: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_export_local).setOnClickListener {
            chooseBackupOptions { keys, data ->
                pendingKeys = keys
                pendingData = data
                exportLauncher.launch("miniapp_backup_${stamp()}.zip")
            }
        }
        findViewById<MaterialButton>(R.id.btn_import_local).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_webdav_upload).setOnClickListener {
            chooseBackupOptions { keys, data -> backupToWebdav(keys, data) }
        }
        findViewById<MaterialButton>(R.id.btn_webdav_download).setOnClickListener {
            lifecycleScope.launch { restoreFromWebdav() }
        }
        findViewById<MaterialButton>(R.id.btn_webdav_config).setOnClickListener {
            startActivity(Intent(this, WebdavConfigActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderWebdavHint()
    }

    private fun renderWebdavHint() {
        findViewById<TextView>(R.id.tv_webdav_hint).text =
            if (backup.isWebdavConfigured()) "备份保存到 /MiniAppContainer/backup/（压缩等级 ${webdavConfig.compressionLevel}）。"
            else "尚未配置服务器。请先在「网络存储」中填写 WebDAV 地址。"
    }

    /** 弹出「选择备份内容」对话框：应用多选 + 是否含数据。 */
    private fun chooseBackupOptions(onConfirm: (appKeys: List<String>, includeData: Boolean) -> Unit) {
        val apps = hostApp.registry.list()
        if (apps.isEmpty()) {
            toast("还没有已安装的小程序")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_backup_options, null)
        val list = view.findViewById<ListView>(R.id.list_apps)
        val swData = view.findViewById<SwitchCompat>(R.id.switch_include_data)
        swData.isChecked = true

        val names = apps.map { it.displayName.ifBlank { it.uname } }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, names)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        for (i in names.indices) list.setItemChecked(i, true)

        MaterialAlertDialogBuilder(this)
            .setTitle("选择备份内容")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val checked = apps.filterIndexed { i, _ -> list.checkedItemPositions.get(i) }
                // 全选等价于"全部"
                val keys = if (checked.size == apps.size) emptyList() else checked.map { it.appKey }
                onConfirm(keys, swData.isChecked)
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    // ==================== 本地 ====================

    private fun exportToLocal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "backup_${stamp()}.zip")
                withContext(Dispatchers.IO) {
                    backup.exportToZip(tmp, pendingKeys, pendingData, webdavConfig.compressionLevel)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                }
                tmp.delete()
                toast("导出成功")
            } catch (t: Throwable) {
                toast("导出失败: ${t.message}")
            }
        }
    }

    private fun importFromLocal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "restore_${stamp()}.zip")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    backup.importFromZip(tmp)
                    tmp.delete()
                }
                reloadData()
                toast("恢复成功")
            } catch (t: Throwable) {
                toast("恢复失败: ${t.message}")
            }
        }
    }

    // ==================== WebDAV ====================

    private fun backupToWebdav(keys: List<String>, includeData: Boolean) {
        if (!backup.isWebdavConfigured()) {
            toast("请先在「网络存储」中配置 WebDAV")
            return
        }
        lifecycleScope.launch {
            toast("上传中…")
            try {
                val name = "backup_${stamp()}.zip"
                val ok = withContext(Dispatchers.IO) {
                    val tmp = File(cacheDir, name)
                    backup.exportToZip(tmp, keys, includeData, webdavConfig.compressionLevel)
                    val r = backup.uploadBackup(name, tmp)
                    tmp.delete()
                    r
                }
                toast(if (ok) "已备份到 /MiniAppContainer/backup/$name" else "上传失败")
            } catch (e: WebdavError) {
                toast(webdavErrorText(e))
            } catch (t: Throwable) {
                toast("上传失败: ${t.message}")
            }
        }
    }

    /** 列出 WebDAV 备份：点击行恢复，点右侧叉删除（带确认）。 */
    private suspend fun restoreFromWebdav() {
        if (!backup.isWebdavConfigured()) {
            toast("请先在「网络存储」中配置 WebDAV")
            return
        }
        val list = try {
            withContext(Dispatchers.IO) { backup.listBackups() }
        } catch (e: WebdavError) {
            toast(webdavErrorText(e)); return
        } catch (t: Throwable) {
            toast("获取备份列表失败: ${t.message}"); return
        }
        if (list.isEmpty()) {
            toast("WebDAV 上没有备份文件")
            return
        }
        val names = list.sortedDescending()
        showBackupListDialog(names)
    }

    private fun showBackupListDialog(names: List<String>) {
        val view = layoutInflater.inflate(R.layout.dialog_backup_list, null)
        val listView = view.findViewById<ListView>(R.id.list_backups)
        val adapter = BackupRowAdapter(names)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择要恢复的备份")
            .setView(view)
            .setNegativeButton("关闭", null)
            .showRounded()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            lifecycleScope.launch { doRestore(names[position]) }
        }
        adapter.onDelete = { name ->
            MaterialAlertDialogBuilder(this)
                .setTitle("删除备份")
                .setMessage("确定删除备份文件\n$name\n？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch { doDeleteBackup(name) }
                }
                .setNegativeButton("取消", null)
                .showRounded()
        }
    }

    private suspend fun doRestore(name: String) {
        toast("下载中…")
        try {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "restore_${stamp()}.zip")
                val downloaded = backup.downloadBackup(name, tmp)
                if (downloaded) backup.importFromZip(tmp)
                tmp.delete()
                downloaded
            }
            if (ok) {
                reloadData()
                toast("恢复成功")
            } else {
                toast("下载失败")
            }
        } catch (e: WebdavError) {
            toast(webdavErrorText(e))
        } catch (t: Throwable) {
            toast("恢复失败: ${t.message}")
        }
    }

    private suspend fun doDeleteBackup(name: String) {
        toast("删除中…")
        try {
            val ok = withContext(Dispatchers.IO) { backup.deleteBackup(name) }
            toast(if (ok) "已删除 $name" else "删除失败（文件可能不存在）")
        } catch (e: WebdavError) {
            toast(webdavErrorText(e))
        } catch (t: Throwable) {
            toast("删除失败: ${t.message}")
        }
    }

    private fun webdavErrorText(e: WebdavError): String = when (e.reason) {
        BackupError.NOT_CONFIGURED -> "请先在「网络存储」中配置 WebDAV"
        BackupError.NETWORK -> "网络错误：无法连接 WebDAV"
        BackupError.SERVER -> "服务器错误"
    }

    /** 导入后重新加载注册表/分类/权限。 */
    private fun reloadData() {
        hostApp.registry.load()
        hostApp.categoryManager.load()
        hostApp.permissionManager.reload()
    }

    private fun stamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

/** WebDAV 备份列表行：名称 + 删除按钮。 */
private class BackupRowAdapter(private val names: List<String>) : BaseAdapter() {

    var onDelete: ((String) -> Unit)? = null

    override fun getCount(): Int = names.size
    override fun getItem(pos: Int): Any = names[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_backup_row, parent, false)
        v.findViewById<TextView>(R.id.tv_backup_name).text = names[pos]
        v.findViewById<ImageButton>(R.id.btn_backup_delete).setOnClickListener {
            onDelete?.invoke(names[pos])
        }
        return v
    }
}
