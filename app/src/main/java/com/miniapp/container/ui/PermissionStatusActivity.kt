package com.miniapp.container.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
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
 * 例如：未给蜗壳存储权限 → 读写内部存储接口不可用；Shizuku 未激活 → adb 不可用；
 * 未配置 WebDAV → 网络存储不可用。点击不可用项跳到对应系统/应用设置页。
 */
class PermissionStatusActivity : AppCompatActivity() {

    private data class Item(
        val label: String,
        val available: Boolean,
        val reason: String = "",
        val action: (() -> Unit)? = null
    )

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_status)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.container)
        buildItems().forEachIndexed { index, item ->
            if (index > 0) {
                val div = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.density * 0.5f).toInt()
                    ).apply { setMargins(0, 0, 0, 0) }
                    setBackgroundColor(getColor(R.color.divider))
                }
                container.addView(div)
            }
            val row = LayoutInflater.from(this).inflate(R.layout.item_permission_status, container, false)
            row.findViewById<TextView>(R.id.tv_name).text = item.label
            val tvStatus = row.findViewById<TextView>(R.id.tv_status)
            val tvAction = row.findViewById<TextView>(R.id.tv_action)
            if (item.available) {
                tvStatus.text = "可用"
                tvStatus.setTextColor(getColor(R.color.ok))
                tvAction.visibility = android.view.View.GONE
            } else {
                tvStatus.text = "不可用 · ${item.reason}"
                tvStatus.setTextColor(getColor(R.color.danger))
                tvAction.visibility = android.view.View.VISIBLE
            }
            row.setOnClickListener {
                if (item.available) {
                    toast("${item.label}：可用，无需配置")
                } else {
                    item.action?.invoke() ?: toast("无法打开配置")
                }
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
            reason = "通知权限已关闭",
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
            reason = "未授予存储权限",
            action = ::openStorageSettings
        ))
        // 网络存储（WebDAV）
        items.add(Item(
            label = "网络存储（WebDAV）",
            available = WebdavConfig(this).configured,
            reason = "未配置 WebDAV",
            action = { startActivity(Intent(this, WebdavConfigActivity::class.java)) }
        ))
        // ADB / Shell（Shizuku）
        val st = shizukuState()
        items.add(Item(
            label = "ADB / Shell（Shizuku）",
            available = st == 2,
            reason = when (st) { 0 -> "未安装 Shizuku"; else -> "Shizuku 未激活" },
            action = ::openShizuku
        ))
        // 闪光灯（相机权限）
        items.add(Item(
            label = "闪光灯（手电筒）",
            available = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            reason = "未授予相机权限",
            action = ::openAppDetailsSettings
        ))
        // 创建桌面快捷方式
        val shortcutSupported = try {
            (getSystemService(android.content.Context.SHORTCUT_SERVICE)
                as android.content.pm.ShortcutManager).isRequestPinShortcutSupported
        } catch (_: Throwable) { false }
        items.add(Item(
            label = "创建桌面快捷方式",
            available = shortcutSupported,
            reason = "当前桌面不支持",
            action = ::openAppDetailsSettings
        ))
        // 始终可用的接口
        items.add(Item("网络访问", available = true))
        items.add(Item("打开外部链接", available = true))
        items.add(Item("读写剪贴板", available = true))
        items.add(Item("震动", available = true))
        return items
    }

    /** Shizuku 状态：0=未安装 1=未激活 2=已激活 */
    private fun shizukuState(): Int = try {
        val installed = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (_: Throwable) { false }
        when {
            !installed -> 0
            Shizuku.pingBinder() -> 2
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
