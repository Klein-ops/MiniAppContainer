package com.miniapp.container.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.miniapp.container.R
import com.miniapp.container.debug.DebugBus
import com.miniapp.container.util.IoUtil
import com.miniapp.container.util.toast

/** 设置页：承载原先右上角菜单的功能（调试模式 / 调用日志 / 备份恢复 / 清理缓存）。 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val btnDebugToggle = findViewById<MaterialButton>(R.id.btn_debug_toggle)
        renderDebugLabel(btnDebugToggle)
        btnDebugToggle.setOnClickListener {
            DebugBus.setEnabled(!DebugBus.enabled)
            renderDebugLabel(btnDebugToggle)
            toast(if (DebugBus.enabled) "调试模式已开启" else "调试模式已关闭")
        }

        findViewById<MaterialButton>(R.id.btn_debug_log).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_clean_storage).setOnClickListener {
            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
                IoUtil.clearCache(this)
            } catch (t: Throwable) { /* ignore */ }
            toast("已清理 WebView 缓存与存储")
        }
    }

    private fun renderDebugLabel(btn: MaterialButton) {
        btn.text = if (DebugBus.enabled) "调试模式（已开启）" else "调试模式（已关闭）"
    }
}
