package com.miniapp.container.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.miniapp.container.R
import com.google.android.material.button.MaterialButton
import com.miniapp.container.debug.DebugBus
import com.miniapp.container.util.toast

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

        // 手动清空（另一种清空时机是蜗壳进程结束，日志仅存内存会自然消失）
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
            "调试模式：已开启 · 关闭调试模式不会清空日志"
        } else {
            "调试模式未开启（已有日志仍保留）· 日志仅存内存，蜗壳被划掉后消失"
        }
        val logs = DebugBus.snapshot()
        tvLog.text = if (logs.isEmpty()) "(暂无记录)" else logs.joinToString("\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
