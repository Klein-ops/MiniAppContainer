package com.miniapp.container.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
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
 * 接口详情：按命名空间分组列出全部 JS 接口，显示权限等级、宿主侧可用性、
 * 与配置入口。安全级接口（无需审批/无需系统权限）显示"无需配置"。
 *
 * 视角是"如果任意小程序调用此接口，能否真正工作"，而非"某小程序被授予了什么"。
 * 后续接口级的自定义配置（如内部储存黑白名单）入口都聚合到这里。
 */
class PermissionStatusActivity : AppCompatActivity() {

    private data class Group(
        val title: String,
        val methods: String,
        val level: String,          // "安全" / "普通" / "危险"
        val available: Boolean?,    // true/false/null(无法检测)
        val action: (() -> Unit)? = null
    )

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_status)
        setupBackToolbar()
        container = findViewById(R.id.container)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        buildGroups().forEach { g -> container.addView(card(g)) }
    }

    private fun card(g: Group): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PermissionStatusActivity, R.color.bg_card))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = g.action != null
            setOnClickListener { g.action?.invoke() ?: toast("该接口无需配置") }
        }
        val statusText = when (g.available) {
            true -> "可用"
            false -> "不可用"
            null -> "无法检测"
        }
        val statusColor = when (g.available) {
            true -> R.color.ok
            false -> R.color.danger
            null -> R.color.brand_accent
        }
        val head = TextView(this).apply {
            text = g.title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@PermissionStatusActivity, R.color.text_primary))
        }
        val st = TextView(this).apply {
            text = statusText
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PermissionStatusActivity, statusColor))
            gravity = Gravity.END
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(head, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(st)
        })
        row.addView(TextView(this).apply {
            text = g.methods
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PermissionStatusActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        val levelColor = when (g.level) {
            "危险" -> R.color.danger
            "普通" -> R.color.brand_primary
            else -> R.color.text_secondary
        }
        row.addView(TextView(this).apply {
            text = "等级：${g.level}" + (if (g.level == "安全") "（无需配置）" else "")
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PermissionStatusActivity, levelColor))
            setPadding(0, dp(4), 0, 0)
        })
        return row
    }

    private fun buildGroups(): List<Group> {
        val cfg = StorageAccessConfig(this)
        val storageOk = when (cfg.mode) {
            StorageMode.SHIZUKU -> ShizukuShell.ready(this)
            StorageMode.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Environment.isExternalStorageManager()
                else ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val notifyOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val webdavOk = WebdavConfig(this).configured
        val shizukuOk = shizukuState() == 2

        return listOf(
            Group("应用与系统信息", "app.info / system.info", "安全", true),
            Group("界面与容器控制",
                "ui.toast / sys.setOrientation / setStatusBar / setStatusBarColor / setTextSelection",
                "安全", true),
            Group("沙箱内文件",
                "fs.read / readBytes / write / writeBytes / list / exists / stat / mkdir / remove / grep / sed / importFile / exportFile",
                "安全", true),
            Group("外部文件（内部储存）",
                "fs.readExternalFile / writeExternalFile / listExternal / existsExternal / statExternal / " +
                    "mkdirExternal / removeExternal / renameExternal / grepExternal / sedExternal / readExternal",
                "普通", storageOk) { startActivity(Intent(this, StorageAccessActivity::class.java)) },
            Group("WASM", "wasm.createMemory / instantiate", "安全", true),
            Group("网络", "net.get / post / put / delete / request", "普通", true),
            Group("剪贴板", "clipboard.read / write", "普通", true),
            Group("通知", "notification.show / cancel", "普通", notifyOk, ::openNotificationSettings),
            Group("网络存储（WebDAV）", "storage.upload / download / list / delete", "普通", webdavOk) {
                startActivity(Intent(this, WebdavConfigActivity::class.java)) },
            Group("震动", "sys.vibrate", "普通", true),
            Group("闪光灯", "sys.flashlight", "普通", cameraOk, ::openAppDetailsSettings),
            Group("打开外部链接", "sys.openUrl", "普通", true),
            Group("ADB / Shell", "adb.exec", "危险", shizukuOk, ::openShizuku),
            Group("Dex 执行", "dex.run（隔离进程）", "安全", true),
            Group("权限预请求", "permission.request", "安全", true),
            Group("桌面快捷方式", "（宿主能力，非 JS 接口）", "普通", null, ::openAppDetailsSettings)
        )
    }

    private fun shizukuState(): Int = try {
        val installed = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (_: Throwable) { false }
        when {
            !installed -> 0
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> 2
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
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))
            }.onFailure { toast("未安装 Shizuku，请在应用市场搜索安装") }
        }
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName")))
        }.onFailure { toast("无法打开应用设置") }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
