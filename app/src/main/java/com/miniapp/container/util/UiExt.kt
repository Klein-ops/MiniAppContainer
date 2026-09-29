package com.miniapp.container.util

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.miniapp.container.R

/**
 * 动态构建页面的通用小部件（PathFilterActivity / StorageAccessActivity 共用）。
 *
 * 原先两个文件各自维护一份几乎相同的实现（卡片容器 / 小节标题 / 提示 / dp），
 * 现统一收口到这里，避免重复。
 */

/** dp 转 px。 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 圆角卡片容器：统一背景、内边距、底部间距与可点击状态。 */
fun Activity.card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundResource(R.drawable.bg_card_rounded)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }
    isClickable = true
    block()
}

/** 小节标题（默认无上边距；需要时传 [topPaddingDp]）。 */
fun Activity.sectionTitle(text: String, topPaddingDp: Int = 0): TextView {
    val activity = this
    return TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        setPadding(0, dp(topPaddingDp), 0, dp(10))
    }
}

/** 提示文字。 */
fun Activity.hint(text: String): TextView {
    val activity = this
    return TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }
}

/** 向容器追加一行指定颜色/字号的文字。 */
fun LinearLayout.addChild(text: String, color: Int, size: Float) {
    addView(TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(ContextCompat.getColor(context, color))
    })
}