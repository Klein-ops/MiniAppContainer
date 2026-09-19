package com.miniapp.container.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.miniapp.container.R
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.util.toast
import rikka.shizuku.Shizuku

/**
 * 权限状态：展示各小程序接口在**宿主侧**是否可用，并提供跳转配置入口。
 *
 * 视角不是"某个小程序被授予了什么"，而是"如果任意小程序调用此接口，能否真正工作"。
 * 绿点=可用；红点=不可用。点击红点项直接跳对应系统/应用设置页（onResume 自动刷新）。
 */
class PermissionStatusActivity : AppCompatActivity() {

    /** available: true=可用 false=不可用 null=无法检测（黄点）。 */
    private data class Item(
        val label: String,
        val available: Boolean?,
        val action: (() -> Unit)? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_status)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialToolbar>(R.id.toolbar).navigationIcon?.setTint(android.graphics.Color.WHITE)
    }

    /** 从设置页返回后自动重探最新状态。 */
    override fun onResume() {
        super.onResume()
        val container = findViewById<LinearLayout>(R.id.container)
        container.removeAllViews()
        buildItems().forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_permission_status, container, false)
            row.findViewById<TextView>(R.id.tv_name).text = item.label
            val color = when (item.available) {
                true -> getColor(R.color.ok)
                false -> getColor(R.color.danger)
                null -> getColor(R.color.brand_accent)   // 无法检测（黄）
            }
            row.findViewById<View>(R.id.dot_status).setBackgroundColor(color)
            val tvStatus = row.findViewById<TextView>(R.id.tv_status)
            tvStatus.text = when (item.available) {
                true -> "可用"
                false -> "不可用"
                null -> "无法检测，请自行查看"
            }
            tvStatus.setTextColor(color)
            // 一律可点：不管绿/红/黄都跳到对应授权/配置页
            row.setOnClickListener {
                item.action?.invoke() ?: toast("该接口无需配置")
            }
            container.addView(row)
        }
    }

    private fun buildItems(): List<Item> {
        val items = mutableListOf<Item>()
        // 通知
        items.add(Item(
            label = "发送通知",
            available = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            action = ::openNotificationSettings
        ))
        // 读写内部存储
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        items.add(Item(
            label = "读写内部存储",
            available = storageOk,
            action = ::openStorageSettings
        ))
        // 网络存储（WebDAV）
        items.add(Item(
            label = "网络存储（WebDAV）",
            available = WebdavConfig(this).configured,
            action = { startActivity(Intent(this, WebdavConfigActivity::class.java)) }
        ))
        // ADB / Shell（Shizuku）
        val st = shizukuState()
        items.add(Item(
            label = "ADB / Shell（Shizuku）",
            available = st == 2,
            action = ::openShizuku
        ))
        // 闪光灯（相机权限）
        items.add(Item(
            label = "闪光灯（手电筒）",
            available = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            action = ::openAppDetailsSettings
        ))
        // 创建桌面快捷方式：无可靠的事前检测 API（isRequestPinShortcutSupported
        // 返回 true 也不保证真能 pin；国产 ROM 更有独立的快捷方式权限开关），
        // 因此显示为"无法检测"（黄点），点击跳应用详情设置自行查看。
        items.add(Item(
            label = "创建桌面快捷方式",
            available = null,
            action = ::openAppDetailsSettings
        ))
        // 始终可用的接口
        items.add(Item("网络访问", available = true))
        items.add(Item("打开外部链接", available = true))
        items.add(Item("读写剪贴板", available = true))
        items.add(Item("震动", available = true))
        return items
    }

    /**
     * Shizuku 状态：0=未安装 1=未激活或未授权 2=已激活且蜗壳已获授权。
     * pingBinder 只代表 Shizuku 服务存活；用户移除授权后它仍为 true，
     * 必须再查 checkSelfPermission 才能反映"蜗壳是否真正可用"。
     */
    private fun shizukuState(): Int = try {
        val installed = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (_: Throwable) { false }
        when {
            !installed -> 0
            Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
            else -> 1
        }
    } catch (_: Throwable) { 1 }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }.onFailure { openAppDetailsSettings() }
    }

    private fun openStorageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(android.net.Uri.parse("package:$packageName"))
        else
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { toast("无法打开存储设置") }
    }

    private fun openShizuku() {
        val pkg = "moe.shizuku.privileged.api"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            startActivity(launch)
        } else {
            // 未安装：跳应用市场
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$pkg")))
            }.onFailure { toast("未安装 Shizuku，请在应用市场搜索安装") }
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName")))
        }.onFailure { toast("无法打开应用设置") }
    }
}
