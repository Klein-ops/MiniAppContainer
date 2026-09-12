package com.miniapp.container.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.core.CategoryManager
import com.miniapp.container.core.MiniAppInfo
import kotlinx.coroutines.launch

/** 小程序独立设置页：权限管理 / 移动分类 / 清空数据 / 卸载。 */
class AppSettingsActivity : AppCompatActivity() {

    private val hostApp: MiniAppApp get() = application as MiniAppApp
    private lateinit var info: MiniAppInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)
        val appKey = intent.getStringExtra(EXTRA_APP_KEY) ?: run { finish(); return }
        info = hostApp.registry.find(appKey) ?: run { finish(); return }

        findViewById<MaterialToolbar>(R.id.toolbar).also { setSupportActionBar(it); title = info.uname }
        findViewById<MaterialButton>(R.id.btn_perm_manage).setOnClickListener {
            startActivity(Intent(this, PermissionManageActivity::class.java)
                .putExtra(PermissionManageActivity.EXTRA_APP_KEY, appKey))
        }
        findViewById<MaterialButton>(R.id.btn_move_category).setOnClickListener { showMoveToCategory() }
        findViewById<MaterialButton>(R.id.btn_clear_data).setOnClickListener { confirmClearData() }
        findViewById<MaterialButton>(R.id.btn_uninstall).setOnClickListener { confirmUninstall() }

        findViewById<android.widget.TextView>(R.id.tv_app_info).text =
            "${info.uname}\n版本 ${info.version}\n${info.appKey}"
    }

    private fun showMoveToCategory() {
        val cm = hostApp.categoryManager
        val cats = cm.listCategories().toMutableList()
        cats.add("＋ 新建分类…")
        AlertDialog.Builder(this)
            .setTitle("移动到分类")
            .setItems(cats.toTypedArray()) { _, which ->
                if (which == cats.size - 1) showAddCategoryAndMove()
                else {
                    cm.moveTo(info.appKey, cats[which])
                    toast("已移到 ${cats[which]}")
                }
            }.show()
    }

    private fun showAddCategoryAndMove() {
        val input = android.widget.EditText(this).apply { hint = "分类名称" }
        AlertDialog.Builder(this)
            .setTitle("新建分类并移入")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (hostApp.categoryManager.addCategory(name)) {
                    hostApp.categoryManager.moveTo(info.appKey, name)
                    toast("已移到 $name")
                } else toast("分类名无效或已存在")
            }.setNegativeButton("取消", null).show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("清空数据")
            .setMessage("将清空 ${info.uname} 的沙箱数据（data/ 和 tmp/），应用资源不受影响。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val sandbox = java.io.File(hostApp.filesDir, "miniapps/${info.uid}_${info.uname}")
                    com.miniapp.container.file.FileService(sandbox).clearData()
                    toast("已清空数据")
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle("卸载")
            .setMessage("卸载 ${info.uname}？沙箱与权限记录将被清除。")
            .setPositiveButton("卸载") { _, _ ->
                lifecycleScope.launch {
                    hostApp.installer.uninstall(info.appKey)
                    toast("已卸载")
                    finish()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object { const val EXTRA_APP_KEY = "appKey" }
}
