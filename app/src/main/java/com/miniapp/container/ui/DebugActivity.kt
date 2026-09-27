package com.miniapp.container.ui

import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.miniapp.container.R
import com.miniapp.container.util.setupBackToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.debug.DebugBus
import com.miniapp.container.util.SafIo
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.stamp
import com.miniapp.container.util.toast

/** 显示调试模式记录的接口调用日志，实时刷新。支持按小程序过滤（只能看到带 [appKey] 标签的日志）。 */
class DebugActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvHint: TextView

    /** 当前过滤的小程序 appKey；null 表示不过滤（显示全部日志）。 */
    private var filterAppKey: String? = null

    private val listener: () -> Unit = {
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        setupBackToolbar()

        tvLog = findViewById(R.id.tv_log)
        scroll = findViewById(R.id.scroll)
        tvHint = findViewById(R.id.tv_hint)

        // 按小程序过滤：从日志条目头提取 appKey 标签（含 system），单选切换
        findViewById<MaterialButton>(R.id.btn_filter_log).setOnClickListener {
            val tags = DebugBus.snapshot()
                .mapNotNull { DebugBus.parseEntry(it)?.appKey }
                .distinct()
            if (tags.isEmpty()) { toast("暂无带标签的日志"); return@setOnClickListener }
            val options = listOf("全部日志") + tags
            val currentIdx = if (filterAppKey == null) 0 else tags.indexOf(filterAppKey) + 1
            MaterialAlertDialogBuilder(this)
                .setTitle("按小程序过滤")
                .setSingleChoiceItems(options.toTypedArray(), currentIdx.coerceAtLeast(0)) { d, which ->
                    filterAppKey = if (which == 0) null else options[which]
                    d.dismiss()
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showRounded()
        }

        // 复制全部日志到系统剪贴板
        findViewById<MaterialButton>(R.id.btn_copy_log).setOnClickListener {
            val text = (if (filterAppKey == null) DebugBus.snapshot() else DebugBus.snapshotForHost(filterAppKey!!))
                .joinToString("\n")
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
                SafIo.writeBytes(this, uri, text.toByteArray(Charsets.UTF_8))
            }.onSuccess { toast("已导出日志") }.onFailure { toast("导出失败: ${it.message}") }
        }
        findViewById<MaterialButton>(R.id.btn_export_log).setOnClickListener {
            exportLauncher.launch("debug_log_${stamp()}.txt")
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
        val all = if (filterAppKey == null) DebugBus.snapshot() else DebugBus.snapshotForHost(filterAppKey!!)
        // 渲染限流：日志可能很多（接口调用/大参数），一次性全量 joinToString 会卡死 UI，
        // 只渲染最近 MAX_RENDER_LINES 行，其余行通过复制/导出查看全量。
        val shown = if (all.size > MAX_RENDER_LINES) {
            "（仅显示最近 $MAX_RENDER_LINES 行，共 ${all.size} 行；可点「复制日志」获取全量）\n\n" +
                all.takeLast(MAX_RENDER_LINES).joinToString("\n\n")
        } else {
            if (all.isEmpty()) "(暂无记录)" else all.joinToString("\n\n")
        }
        tvLog.text = shown
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        /** 单次渲染最多显示的日志行数（防止超大日志卡死 UI）。 */
        private const val MAX_RENDER_LINES = 300
    }
}
