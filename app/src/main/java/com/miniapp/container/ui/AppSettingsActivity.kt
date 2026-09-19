package com.miniapp.container.ui

import android.content.Intent
import android.os.Bundle
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Icon
import com.caverock.androidsvg.SVG
import java.io.File
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.InputDialog
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.toast
import com.miniapp.container.core.CategoryManager
import com.miniapp.container.core.MiniAppInfo
import kotlinx.coroutines.launch

/** 小程序独立设置页：权限管理 / 移动分类 / 清空数据 / 卸载。 */
class AppSettingsActivity : AppCompatActivity() {

    private val hostApp: MiniAppApp get() = MiniAppApp.require(application)
    private lateinit var info: MiniAppInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)
        val appKey = intent.getStringExtra(EXTRA_APP_KEY) ?: run { finish(); return }
        info = hostApp.registry.get(appKey) ?: run { finish(); return }

        findViewById<MaterialToolbar>(R.id.toolbar).also { setSupportActionBar(it); title = info.uname }
        findViewById<android.view.View>(R.id.btn_rename).setOnClickListener { showRename() }
        findViewById<android.view.View>(R.id.btn_perm_manage).setOnClickListener {
            startActivity(Intent(this, PermissionManageActivity::class.java)
                .putExtra(PermissionManageActivity.EXTRA_APP_KEY, appKey))
        }
        findViewById<android.view.View>(R.id.btn_move_category).setOnClickListener { showMoveToCategory() }
        findViewById<android.view.View>(R.id.btn_shortcut).setOnClickListener { createDesktopShortcut() }
        findViewById<android.view.View>(R.id.btn_clear_data).setOnClickListener { confirmClearData() }
        findViewById<android.view.View>(R.id.btn_uninstall).setOnClickListener { confirmUninstall() }

        findViewById<android.widget.TextView>(R.id.tv_app_info).text =
            "${info.uname}\n版本 ${info.version}\n${info.appKey}"
    }

    private fun createDesktopShortcut() {
        val sm = getSystemService(android.content.Context.SHORTCUT_SERVICE)
            as android.content.pm.ShortcutManager
        // 提前检查桌面是否支持快捷方式功能（国产 ROM 可能禁用）
        if (!sm.isRequestPinShortcutSupported) {
            toast("当前桌面不支持创建快捷方式")
            return
        }
        // data 唯一化 → 每个小程序的快捷方式对应独立 task（多开互不干扰）
        val launchIntent = Intent(this, com.miniapp.container.ui.MiniAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("miniapp://${info.appKey}"))
            .putExtra(com.miniapp.container.ui.MiniAppActivity.EXTRA_APP_KEY, info.appKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        val label = info.displayName.ifBlank { info.uname }
        val shortcut = ShortcutInfo.Builder(this, "miniapp_${info.appKey}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(loadAppIcon() ?: Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        sm.requestPinShortcut(shortcut, null)
        toast("已请求创建快捷方式")
    }

    /**
     * 加载小程序自有图标（SVG/PNG → AdaptiveIcon 位图），无图标返回 null。
     *
     * 之前用 `Icon.createWithBitmap`（普通位图）：MIUI 等 launcher 会把 legacy 位图
     * **填满整个图标区域**（108dp）显示，而正常应用图标是自适应图标（内容只占中间
     * 66% 安全区），于是看起来大一圈。
     *
     * 正确做法：生成 **108dp 画布**，把源图标内容缩放到中心 60% 安全区、四周留透明，
     * 再 `Icon.createWithAdaptiveBitmap`——系统按自适应图标规格渲染，与其他图标一致。
     */
    private fun loadAppIcon(): Icon? {
        if (info.icon.isBlank()) return null
        val sandbox = File(filesDir, "miniapps/${info.uid}_${info.uname}")
        val iconFile = File(sandbox, "app/${info.icon}")
        if (!iconFile.exists()) return null
        return try {
            val density = resources.displayMetrics.density
            val full = (108 * density).toInt().coerceAtLeast(108)      // 自适应画布 108dp
            val content = (full * 0.60f).toInt().coerceAtLeast(1)      // 安全区内容 ~64.8dp
            val src = if (info.icon.endsWith(".svg", ignoreCase = true)) {
                val svg = SVG.getFromInputStream(iconFile.inputStream())
                svg.setDocumentWidth(content.toFloat())
                svg.setDocumentHeight(content.toFloat())
                val picture = svg.renderToPicture()
                Bitmap.createBitmap(content, content, Bitmap.Config.ARGB_8888).also { b ->
                    picture.draw(Canvas(b))
                }
            } else {
                val raw = BitmapFactory.decodeFile(iconFile.absolutePath) ?: return null
                Bitmap.createScaledBitmap(raw, content, content, true)
            }
            // 居中放上透明画布 → 自适应图标（系统只显示安全区）
            val canvas = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
            Canvas(canvas).drawBitmap(src, (full - content) / 2f, (full - content) / 2f, null)
            Icon.createWithAdaptiveBitmap(canvas)
        } catch (_: Exception) { null }
    }

    private fun showRename() {
        val (view, input) = InputDialog.create(
            this, "显示名称", initial = info.displayName.ifBlank { info.uname }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setMessage("仅影响显示，不影响应用身份（${info.uid}_${info.uname}）")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                hostApp.registry.put(info.copy(displayName = name))
                hostApp.registry.save()
                info = hostApp.registry.get(info.appKey) ?: return@setPositiveButton
                findViewById<android.widget.TextView>(R.id.tv_app_info).text =
                    "${info.displayName.ifBlank { info.uname }}\n版本 ${info.version}\n${info.appKey}"
                toast("已重命名")
            }.setNegativeButton("取消", null).showRounded()
    }

    private fun showMoveToCategory() {
        val cm = hostApp.categoryManager
        val cats = cm.listCategories().toMutableList()
        cats.add("＋ 新建分类…")
        MaterialAlertDialogBuilder(this)
            .setTitle("移动到分类")
            .setItems(cats.toTypedArray()) { _, which ->
                if (which == cats.size - 1) showAddCategoryAndMove()
                else {
                    cm.moveTo(info.appKey, cats[which])
                    toast("已移到 ${cats[which]}")
                }
            }.showRounded()
    }

    private fun showAddCategoryAndMove() {
        val (view, input) = InputDialog.create(this, "分类名称")
        MaterialAlertDialogBuilder(this)
            .setTitle("新建分类并移入")
            .setView(view)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (hostApp.categoryManager.addCategory(name)) {
                    hostApp.categoryManager.moveTo(info.appKey, name)
                    toast("已移到 $name")
                } else toast("分类名无效或已存在")
            }.setNegativeButton("取消", null).showRounded()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空数据")
            .setMessage("将清空 ${info.uname} 的沙箱数据（data/ 和 tmp/），应用资源不受影响。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    val sandbox = java.io.File(hostApp.filesDir, "miniapps/${info.uid}_${info.uname}")
                    com.miniapp.container.file.FileService(sandbox).clearData()
                    toast("已清空数据")
                }
            }.setNegativeButton("取消", null).showRounded()
    }

    private fun confirmUninstall() {
        MaterialAlertDialogBuilder(this)
            .setTitle("卸载")
            .setMessage("卸载 ${info.uname}？沙箱与权限记录将被清除。")
            .setPositiveButton("卸载") { _, _ ->
                lifecycleScope.launch {
                    hostApp.installer.uninstall(info.appKey)
                    toast("已卸载")
                    finish()
                }
            }.setNegativeButton("取消", null).showRounded()
    }


    companion object { const val EXTRA_APP_KEY = "appKey" }
}
