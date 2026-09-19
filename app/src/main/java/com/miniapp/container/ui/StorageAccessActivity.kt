package com.miniapp.container.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.miniapp.container.R
import com.miniapp.container.core.StorageAccessConfig
import com.miniapp.container.core.StorageMode
import com.miniapp.container.service.ShizukuShell
import com.miniapp.container.service.ShizukuState
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.util.toast
import kotlinx.coroutines.launch

/**
 * 内部储存访问方式：两种授权方式选择页。
 *
 * - 传统（推荐）：申请系统存储权限，行为与历史一致
 * - Shizuku：借 ADB 权限读写，Android 11+ 亦可访问 `sdcard/Android`
 *
 * 小程序的 `fs.*External` 接口行为不随本设置变化，仅宿主底层实现切换。
 */
class StorageAccessActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val config by lazy { StorageAccessConfig(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_access)
        setupBackToolbar()
        container = findViewById(R.id.container)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // 从设置页返回后刷新状态
    }

    private fun render() {
        container.removeAllViews()
        val current = config.mode

        container.addView(sectionTitle("选择授权方式"))
        container.addView(
            option(
                mode = StorageMode.SYSTEM,
                current = current,
                title = "传统方式（推荐）",
                desc = "申请系统「所有文件访问」权限（Android 11+）/ 存储权限（Android 10 及以下）。" +
                    "兼容性最好；Android 11+ 无法访问 sdcard/Android。",
                status = systemStatusText(),
                available = systemAvailable()
            )
        )
        container.addView(
            option(
                mode = StorageMode.SHIZUKU,
                current = current,
                title = "Shizuku（ADB）",
                desc = "借 Shizuku 的 ADB 权限读写，无需系统存储授权；" +
                    "在 Android 11 及以上亦可读写 sdcard/Android。需安装并激活 Shizuku。",
                status = shizukuStatusText(),
                available = shizukuAvailable()
            )
        )
        container.addView(hint("两种方式下小程序接口行为一致；「传统方式」为默认，Shizuku 不可用时自动回退。"))
    }

    // ---------- 选项行 ----------

    private fun option(
        mode: StorageMode,
        current: StorageMode,
        title: String,
        desc: String,
        status: String,
        available: Boolean
    ): LinearLayout {
        val selected = mode == current
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@StorageAccessActivity, R.color.bg_card))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            setOnClickListener { choose(mode, available) }
        }

        val head = TextView(this).apply {
            text = (if (selected) "● " else "○ ") + title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@StorageAccessActivity, R.color.text_primary))
        }
        val st = TextView(this).apply {
            text = status
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(
                    this@StorageAccessActivity,
                    if (available) R.color.text_secondary else R.color.text_primary
                )
            )
            gravity = Gravity.END
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(head, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(st)
        }
        row.addView(headRow)
        row.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StorageAccessActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        return row
    }

    private fun choose(mode: StorageMode, available: Boolean) {
        config.mode = mode
        if (!available) {
            // 引导授权（不阻断选择：授权后自动生效）
            when (mode) {
                StorageMode.SYSTEM -> openSystemStorageSettings()
                StorageMode.SHIZUKU -> requestShizuku()
            }
        } else {
            toast(if (mode == StorageMode.SYSTEM) "已切换到传统方式" else "已切换到 Shizuku")
        }
        render()
    }

    // ---------- 状态 ----------

    private fun systemAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun systemStatusText(): String =
        if (systemAvailable()) "已授权 · 点按去设置" else "未授权 · 点按去授权"

    private fun shizukuAvailable(): Boolean = ShizukuShell.ready(this)

    private fun shizukuStatusText(): String = when {
        ShizukuShell.state(this) == ShizukuState.NOT_INSTALLED -> "未安装 · 点按去安装"
        !ShizukuShell.hasPermission() -> "未授权 · 点按去授权"
        shizukuAvailable() -> "可用 · 点按切换"
        else -> "未激活 · 点按去激活"
    }

    private fun openSystemStorageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
        else
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { toast("无法打开存储设置") }
    }

    private fun requestShizuku() {
        val st = ShizukuShell.state(this)
        if (st == ShizukuState.NOT_INSTALLED) {
            openShizuku()
            return
        }
        if (ShizukuShell.hasPermission()) {
            toast("Shizuku 未激活，请打开 Shizuku 激活服务")
            openShizuku()
            return
        }
        // 主动请求 Shizuku 授权（Shizuku 应用弹窗）
        lifecycleScope.launch {
            val granted = runCatching { ShizukuShell.requestPermission() }.getOrDefault(false)
            toast(if (granted) "Shizuku 已授权" else "Shizuku 授权未完成")
            render()
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(ShizukuShell.PKG)
        if (launch != null) {
            startActivity(launch)
        } else {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuShell.PKG}"))
                )
            }.onFailure { toast("未安装 Shizuku，请在应用市场搜索安装") }
        }
    }

    // ---------- 小部件 ----------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@StorageAccessActivity, R.color.text_secondary))
        setPadding(0, 0, 0, dp(10))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@StorageAccessActivity, R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
