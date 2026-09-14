package com.miniapp.container.ui

import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.toast
import com.miniapp.container.core.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 备份与恢复：本地 zip 导入导出 + WebDAV 备份恢复。 */
class BackupActivity : AppCompatActivity() {

    private val hostApp by lazy { MiniAppApp.get(application) }
    private val backup by lazy { BackupService(this) }
    private lateinit var etUrl: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText

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

        etUrl = findViewById<EditText>(R.id.et_webdav_url).also { it.setText(backup.webdavUrl) }
        etUser = findViewById<EditText>(R.id.et_webdav_user).also { it.setText(backup.webdavUser) }
        etPass = findViewById<EditText>(R.id.et_webdav_pass).also { it.setText(backup.webdavPass) }

        findViewById<MaterialButton>(R.id.btn_save_webdav).setOnClickListener {
            saveWebdavSettings()
            toast("WebDAV 设置已保存")
        }
        findViewById<MaterialButton>(R.id.btn_export_local).setOnClickListener {
            exportLauncher.launch("miniapp_backup_${stamp()}.zip")
        }
        findViewById<MaterialButton>(R.id.btn_import_local).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_webdav_upload).setOnClickListener {
            lifecycleScope.launch { backupToWebdav() }
        }
        findViewById<MaterialButton>(R.id.btn_webdav_download).setOnClickListener {
            lifecycleScope.launch { restoreFromWebdav() }
        }
    }

    private fun saveWebdavSettings() {
        backup.webdavUrl = etUrl.text.toString().trim()
        backup.webdavUser = etUser.text.toString().trim()
        backup.webdavPass = etPass.text.toString()
    }

    private fun exportToLocal(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "backup_${stamp()}.zip")
                withContext(Dispatchers.IO) {
                    backup.exportToZip(tmp)
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

    private suspend fun backupToWebdav() {
        saveWebdavSettings()
        if (backup.webdavUrl.isBlank()) { toast("请先填写 WebDAV 地址"); return }
        toast("上传中…")
        try {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "backup_${stamp()}.zip")
                backup.exportToZip(tmp)
                val r = backup.webdavUpload(tmp)
                tmp.delete()
                r
            }
            toast(if (ok) "已备份到 WebDAV" else "上传失败（检查地址/账号）")
        } catch (t: Throwable) {
            toast("上传失败: ${t.message}")
        }
    }

    private suspend fun restoreFromWebdav() {
        saveWebdavSettings()
        if (backup.webdavUrl.isBlank()) { toast("请先填写 WebDAV 地址"); return }
        toast("下载中…")
        try {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "restore_${stamp()}.zip")
                val downloaded = backup.webdavDownload(tmp)
                if (downloaded) backup.importFromZip(tmp)
                tmp.delete()
                downloaded
            }
            if (ok) {
                reloadData()
                toast("恢复成功")
            } else {
                toast("下载失败（检查地址/账号）")
            }
        } catch (t: Throwable) {
            toast("恢复失败: ${t.message}")
        }
    }

    /** 导入后重新加载注册表/分类/权限，使新数据生效。 */
    private fun reloadData() {
        hostApp.registry.load()
        hostApp.categoryManager.load()
        hostApp.permissionManager.reload()
    }

    private fun stamp() = System.currentTimeMillis().toString()

}
