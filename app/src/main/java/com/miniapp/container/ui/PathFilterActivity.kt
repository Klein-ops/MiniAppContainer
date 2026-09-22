package com.miniapp.container.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.core.MatchMode
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.core.PathRule
import com.miniapp.container.core.RuleType
import com.miniapp.container.core.StorageAccessConfig
import com.miniapp.container.util.showRounded
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.util.toast

/**
 * 内部储存访问规则：每条规则独立配置（黑/白名单、启停、匹配方式、作用域）。
 *
 * - 白名单规则：未命中即禁（存在白名单时只有命中白名单的路径可访问）
 * - 黑名单规则：命中即禁（白名单过滤后二次过滤）
 * - 应用私有目录（/data/data 等）为硬底线，任何规则都不可放行
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
        container.addView(sectionTitle("访问规则"))
        container.addView(addCard())
        val rules = config.rules()
        if (rules.isEmpty()) {
            container.addView(hint(
                "尚未配置规则。\n- 白名单：未命中即禁\n- 黑名单：命中即禁\n" +
                "启用多条时：白名单先过滤，黑名单再过滤。"
            ))
        }
        rules.forEach { rule -> container.addView(ruleCard(rule)) }
        container.addView(hint("应用私有目录（/data/data 等）任何规则都禁止访问，不可放行。"))
    }

    // ==================== 添加规则 ====================

    private fun addCard(): LinearLayout = card {
        setOnClickListener { chooseTypeDialog() }
        addChild("+ 添加新规则", R.color.brand_primary, 15f)
    }

    private fun chooseTypeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("添加新规则")
            .setItems(arrayOf("黑名单（命中即禁）", "白名单（未命中即禁）")) { _, which ->
                inputPathDialog(if (which == 0) RuleType.BLACKLIST else RuleType.WHITELIST)
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    private fun inputPathDialog(type: RuleType) {
        val input = EditText(this).apply { hint = "如 /sdcard/Android/data" }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (type == RuleType.BLACKLIST) "添加黑名单路径" else "添加白名单路径")
            .setMessage("路径前缀（含子目录）或精确路径，可在「设置」中调整匹配方式。")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isEmpty()) { toast("路径不能为空"); return@setPositiveButton }
                val rule = PathRule(
                    id = "r" + System.currentTimeMillis(),
                    type = type,
                    path = p
                )
                config.saveRules(config.rules() + rule)
                render()
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    // ==================== 规则卡片 ====================

    private fun ruleCard(rule: PathRule): LinearLayout {
        val row = card {
            setOnClickListener { settingsDialog(rule) }
        }
        // 标题行：类型标签 + 路径
        val tagColor = if (rule.type == RuleType.BLACKLIST) R.color.danger else R.color.brand_primary
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PathFilterActivity).apply {
                text = if (rule.type == RuleType.BLACKLIST) "黑名单" else "白名单"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, tagColor))
            })
            addView(TextView(this@PathFilterActivity).apply {
                text = rule.path
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(8) }
            })
        })
        // 状态行
        val parts = mutableListOf<String>()
        parts += if (rule.enabled) "已启用" else "已停用"
        parts += if (rule.matchMode == MatchMode.PREFIX) "前缀" else "精确"
        parts += if (rule.appKeys.isEmpty()) "全部小程序" else "${rule.appKeys.size}个小程序"
        row.addView(TextView(this).apply {
            text = parts.joinToString(" · ") + " · 点按设置"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        return row
    }

    // ==================== 规则设置 ====================

    private fun settingsDialog(rule: PathRule) {
        var enabled = rule.enabled
        var matchMode = rule.matchMode
        var appKeys = rule.appKeys

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val sw = SwitchCompat(this).apply {
            text = "启用此规则"
            isChecked = enabled
            setOnCheckedChangeListener { _, c -> enabled = c }
        }
        view.addView(sw)

        view.addView(sectionTitle("匹配方式"))
        val prefixDot = TextView(this)
        val exactDot = TextView(this)
        fun refreshDots() {
            prefixDot.text = if (matchMode == MatchMode.PREFIX) "\u25cf " else "\u25cb "
            exactDot.text = if (matchMode == MatchMode.EXACT) "\u25cf " else "\u25cb "
        }
        view.addView(selectRow(prefixDot, "前缀匹配（含所有子目录）") {
            matchMode = MatchMode.PREFIX; refreshDots()
        })
        view.addView(selectRow(exactDot, "精确匹配（仅此路径）") {
            matchMode = MatchMode.EXACT; refreshDots()
        })
        refreshDots()

        view.addView(sectionTitle("作用域 · 全不勾选 = 所有小程序"))
        val scopeSummary = TextView(this).apply {
            text = if (appKeys.isEmpty()) "所有小程序" else "仅 ${appKeys.size} 个小程序"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_primary))
            setPadding(0, dp(4), 0, 0)
        }
        view.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                scopeDialog(rule.copy(appKeys = appKeys)) { picked ->
                    appKeys = picked
                    scopeSummary.text = if (picked.isEmpty()) "所有小程序" else "仅 ${picked.size} 个小程序"
                }
            }
            addView(scopeSummary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@PathFilterActivity).apply {
                text = "修改 >"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.brand_primary))
            })
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("规则设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                saveRule(rule.copy(enabled = enabled, matchMode = matchMode, appKeys = appKeys))
                render()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                removeRule(rule.id)
                render()
            }
            .showRounded()
    }

    private fun scopeDialog(rule: PathRule, onResult: (Set<String>) -> Unit) {
        val apps = appList()
        if (apps.isEmpty()) {
            toast("没有已安装的小程序")
            return
        }
        val names = apps.map { (it.displayName.ifBlank { it.uname }) + "（" + it.appKey + "）" }
            .toTypedArray()
        val checked = BooleanArray(apps.size) { i -> rule.appKeys.contains(apps[i].appKey) }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择小程序")
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("确定") { _, _ ->
                val picked = apps.filterIndexed { i, _ -> checked[i] }
                    .map { it.appKey }.toSet()
                onResult(picked)
            }
            .setNegativeButton("取消", null)
            .showRounded()
    }

    private fun selectRow(dot: TextView, text: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { onClick() }
            addView(dot.apply {
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.brand_primary))
            })
            addView(TextView(this@PathFilterActivity).apply {
                this.text = text
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PathFilterActivity, R.color.text_primary))
            })
        }

    private fun saveRule(updated: PathRule) {
        config.saveRules(config.rules().map { if (it.id == updated.id) updated else it })
    }

    private fun removeRule(id: String) {
        config.saveRules(config.rules().filterNot { it.id == id })
    }

    private fun appList(): List<MiniAppInfo> = try {
        MiniAppApp.require(application).registry.list()
    } catch (_: Throwable) {
        emptyList()
    }

    // ==================== 小部件 ====================

    private fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(this@PathFilterActivity, R.color.bg_card))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        isClickable = true
        block()
    }

    private fun LinearLayout.addChild(text: String, color: Int, size: Float) {
        addView(TextView(this@PathFilterActivity).apply {
            this.text = text
            textSize = size
            setTextColor(ContextCompat.getColor(this@PathFilterActivity, color))
        })
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
