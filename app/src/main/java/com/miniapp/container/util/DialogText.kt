package com.miniapp.container.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.miniapp.container.R

/**
 * 可滚动的对话框消息视图。
 *
 * 背景：MaterialAlertDialogBuilder 的 `setMessage` 区域在 Material 3 对话框里
 * **不参与滚动**——消息一旦超过窗口高度会被系统直接裁掉（`RoundedDialog` 的
 * 限高兜底也只是"裁剪"而非"滚动"），底部按钮可能点不到，用户也看不到完整内容。
 * 超长 `uname` / `appKey` / 备份文件名正是这种情况。
 *
 * 这里构造一个限高 [MaxHeightScrollView] 包裹 TextView：内容短时自动收缩保持
 * 紧凑外观；内容超长时内部滚动，按钮始终可见可点。样式对齐项目内权限弹窗。
 */
object DialogText {

    /**
     * 构造限高可滚动消息视图，替代 `setMessage(text)`。
     *
     * @param maxHeightRatio 滚动区最大高度占屏幕高度比例（默认 0.6，留足标题/按钮空间）
     */
    fun scrollable(context: Context, text: String, maxHeightRatio: Float = 0.6f): View {
        val dm = context.resources.displayMetrics
        val cap = (dm.heightPixels * maxHeightRatio).toInt()
        val sv = MaxHeightScrollView(context).apply {
            maxHeight = cap
            isVerticalScrollBarEnabled = true
        }
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
            setLineSpacing(0f, 1.25f)
            // 长无空格串（如超长 appKey/uname）按字符断行，避免横向溢出。
            // 默认 breakStrategy=simple 不允许单词中断行，超长 token 会画出屏幕。
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            // 对齐 M3 对话框消息区 24dp 侧边距，文字不贴边
            val pad = (24 * dm.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        sv.addView(
            tv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return sv
    }
}