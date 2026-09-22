package com.miniapp.container.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.R
import com.miniapp.container.core.PathFilterMode
import com.miniapp.container.core.StorageAccessConfig
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.util.toast

/**
 * 内部储存访问黑白名单配置：限制小程序 `fs.*External` 可访问的路径。
 *
 * 应用私有目录（`/data/data` 等）为不可配置硬底线，任何模式下都禁止。
 */
class PathFilterActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val config by lazy { StorageAccessConfig(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_path_filter)
        setupBackToolbar()
        container = findViewById(R.id.container)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val mode = config.pathFilterMode

        container.addView(sectionTitle("过滤模式"))
        container.addView(modeOption(PathFilterMode.NONE, mode, "不过滤", "仅禁止应用私有目录，其余路径自由访问。"))
        container.addView(modeOption(PathFilterMode.BLACKLIST, mode, "黑名单", "列表中的路径禁止访问，其余自由访问。"))
        container.addView(modeOption(PathFilterMode.WHITELIST, mode, "白名单", "仅列表中的路径允许访问，其余一律禁止（最严格）。"))
        container.addView(hint("应用私有目录（/data/data 等）任何模式下都禁止访问，不可配置。"))

        val list = config.pathList
        container.addView(sectionTitle("路径列表（${list.size}）"))
        if (mode == PathFilterMode.NONE) {
            container.addView(hint("当前为「不过滤」，列表暂不生效。可预先配置以便切换模式时即用。"))
        } else if (list.isEmpty()) {
            container.addView(hint(
                if (mode == PathFilterMode.BLACKLIST)
                    "黑名单为空：仅硬底线生效。"
                else "白名单为空：除硬底线外所有路径都被禁止。请添加路径。"
            ))
        }
        list.forEach { path -> container.addView(pathRow(path)) }
        container.addView(addCard())
    }

    private fun modeOption(mode: PathFilterMode, current: PathFilterMode, title: String, desc: String): LinearLayout {
        val selected = mode == current
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PathFilterActivity, R.color.bg_card))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            setOnClickListener {
                config.pathFilterMode = mode
                render()
            }
        }
        row.addView(TextView(this).apply {
            text = (if (selected) "● " else "○ ") + title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_primary))
        })
        row.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        return row
    }

    private fun pathRow(path: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@PathFilterActivity, R.color.bg_card))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@PathFilterActivity).apply {
                text = path
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PathFilterActivity).apply {
                text = "删除"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.danger))
                setOnClickListener {
                    config.pathList = config.pathList - path
                    render()
                }
            })
        }
    }

    private fun addCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@PathFilterActivity, R.color.bg_card))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            setOnClickListener { showAddDialog() }
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@PathFilterActivity).apply {
                text = "+ 添加路径"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.brand_primary))
            })
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply { hint = "如 /sdcard/Android/data" }
        MaterialAlertDialogBuilder(this)
            .setTitle("添加路径")
            .setMessage("输入路径前缀，子路径一并生效。常用：\n/sdcard/Android/data\n/sdcard/DCIM\n/data/local/tmp")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isEmpty()) { toast("路径不能为空"); return@setPositiveButton }
                if (config.pathList.contains(p)) { toast("已存在"); return@setPositiveButton }
                config.pathList = config.pathList + p
                render()
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(10))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
