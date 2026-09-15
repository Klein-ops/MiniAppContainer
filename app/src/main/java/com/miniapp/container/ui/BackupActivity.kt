package com.miniapp.container.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
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
import com.miniapp.container.core.BackupService
import com.miniapp.container.core.WebdavError
import com.miniapp.container.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份与恢复：本地 zip + WebDAV（备份内容可选、路径固定 /蜗壳/backup/）。 */
class BackupActivity : AppCompatActivity() {

    private val hostApp by lazy { MiniAppApp.get(application) }
    private val backup by lazy { BackupService(this) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { exportToLocal(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFromLocal(it) } }

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

    private var pendingKeys: List<String> = emptyList()
    private var pendingData: Boolean = true

    private fun renderWebdavHint() {
        findViewById<TextView>(R.id.tv_webdav_hint).text =
            if (backup.isWebdavConfigured()) "备份保存到 /蜗壳/backup/。"
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
                // 全选等价于"全部"（同时备份分类等全局数据）
                val keys = if (checked.size == apps.size) emptyList() else checked.map { it.appKey }
                onConfirm(keys, swData.isChecked)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 本地 ====================

    private fun exportToLocal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "backup_${stamp()}.zip")
                withContext(Dispatchers.IO) {
                    backup.exportToZip(tmp, pendingKeys, pendingData)
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
                    backup.exportToZip(tmp, keys, includeData)
                    val r = backup.uploadBackup(name, tmp)
                    tmp.delete()
                    r
                }
                toast(if (ok) "已备份到 /蜗壳/backup/$name" else "上传失败")
            } catch (e: WebdavError) {
                toast(webdavErrorText(e))
            } catch (t: Throwable) {
                toast("上传失败: ${t.message}")
            }
        }
    }

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
        val items = list.sortedDescending().toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择要恢复的备份")
            .setItems(items) { _, which ->
                lifecycleScope.launch { doRestore(items[which]) }
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun webdavErrorText(e: WebdavError): String = when (e.reason) {
        com.miniapp.container.core.BackupError.NOT_CONFIGURED -> "请先在「网络存储」中配置 WebDAV"
        com.miniapp.container.core.BackupError.NETWORK -> "网络错误：无法连接 WebDAV"
        com.miniapp.container.core.BackupError.SERVER -> "服务器错误"
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
