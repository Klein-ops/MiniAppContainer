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
import com.miniapp.container.R
import com.miniapp.container.core.StorageAccessConfig
import com.miniapp.container.core.StorageMode
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.service.ShizukuShell
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.util.toast
import rikka.shizuku.Shizuku

/**
 * 接口详情：列出**需审批**的接口（普通/危险，含安全但可配置的）在宿主侧的可用性。
 *
 * 视角不是"某个小程序被授予了什么"，而是"如果任意小程序调用此接口，能否真正工作"。
 * 绿点=可用；红点=不可用；黄点=无法检测。点击行直接跳对应系统/应用设置页（onResume 自动刷新）。
 * 无需配置的安全级接口（沙箱内文件、WASM、Dex 等）不在此列。
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
        setupBackToolbar()
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

    /**
     * 只列需审批的接口（普通/危险）与安全但可配置的接口；
     * 无需配置的安全级接口不在此列。
     */
    private fun buildItems(): List<Item> {
        // 读写内部存储：可用性按当前授权方式（传统 / Shizuku）判定
        val storageAccess = StorageAccessConfig(this)
        val storageOk = when (storageAccess.mode) {
            StorageMode.SHIZUKU -> ShizukuShell.ready(this)
            StorageMode.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        // 网络存储（WebDAV）
        val webdavOk = WebdavConfig(this).configured
        val st = shizukuState()
        val notifyOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val cameraOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        return listOf(
            Item(
                label = "发送通知",
                available = notifyOk,
                action = ::openNotificationSettings
            ),
            Item(
                label = "读写内部存储",
                available = storageOk,
                action = { startActivity(Intent(this, StorageAccessActivity::class.java)) }
            ),
            Item(
                label = "网络存储（WebDAV）",
                available = webdavOk,
                action = { startActivity(Intent(this, WebdavConfigActivity::class.java)) }
            ),
            Item(
                label = "ADB / Shell（Shizuku）",
                available = st == 2,
                action = ::openShizuku
            ),
            Item(
                label = "闪光灯（手电筒）",
                available = cameraOk,
                action = ::openAppDetailsSettings
            ),
            // 创建桌面快捷方式：无可靠的事前检测 API（isRequestPinShortcutSupported
            // 返回 true 也不保证真能 pin；国产 ROM 更有独立的快捷方式权限开关），
            // 因此显示为"无法检测"（黄点），点击跳应用详情设置自行查看。
            Item(
                label = "创建桌面快捷方式",
                available = null,
                action = ::openAppDetailsSettings
            ),
            Item("网络访问", available = true),
            Item("打开外部链接", available = true),
            Item("读写剪贴板", available = true),
            Item("震动", available = true)
        )
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