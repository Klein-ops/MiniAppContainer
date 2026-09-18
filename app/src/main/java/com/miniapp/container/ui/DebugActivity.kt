package com.miniapp.container.ui

import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.miniapp.container.R
import com.google.android.material.button.MaterialButton
import com.miniapp.container.debug.DebugBus
import com.miniapp.container.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 显示调试模式记录的接口调用日志，实时刷新。 */
class DebugActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvHint: TextView

    private val listener: () -> Unit = {
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tvLog = findViewById(R.id.tv_log)
        scroll = findViewById(R.id.scroll)
        tvHint = findViewById(R.id.tv_hint)

        // 复制全部日志到系统剪贴板
        findViewById<MaterialButton>(R.id.btn_copy_log).setOnClickListener {
            val text = DebugBus.snapshot().joinToString("\n")
            if (text.isBlank()) { toast("暂无日志"); return@setOnClickListener }
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MiniAppContainer 调试日志", text))
            toast("已复制 ${text.lines().size} 行日志")
        }

        // 导出日志为 txt（SAF 保存）
        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val text = DebugBus.snapshot().joinToString("\n")
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { toast("已导出日志") }.onFailure { toast("导出失败: ${it.message}") }
        }
        findViewById<MaterialButton>(R.id.btn_export_log).setOnClickListener {
            exportLauncher.launch("debug_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
        }

        // 手动清空
        findViewById<MaterialButton>(R.id.btn_clear_log).setOnClickListener {
            DebugBus.clear()
            toast("已清空日志")
        }

        DebugBus.addListener(listener)
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugBus.removeListener(listener)
    }

    private fun render() {
        tvHint.text = if (DebugBus.enabled) {
            "调试模式：已开启 · 日志落盘（应用重启清空）"
        } else {
            "调试模式未开启（已有日志仍保留）· 日志落盘保存，应用重启即清空"
        }
        val logs = DebugBus.snapshot()
        tvLog.text = if (logs.isEmpty()) "(暂无记录)" else logs.joinToString("\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
