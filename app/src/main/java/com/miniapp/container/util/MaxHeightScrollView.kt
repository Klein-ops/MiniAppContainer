package com.miniapp.container.util

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 支持最大高度限制的 [ScrollView]。
 *
 * 用途：对话框内容可能很长（如危险权限的警告文案），若直接 wrap_content 会把
 * 对话框撑出屏幕、底部按钮点不到。给滚动区设一个上限，超出即内部滚动，
 * 从而保证滚动区之外的按钮**始终可见可点**。
 *
 * [ScrollView] 原生不支持 `android:maxHeight`，故在此覆写 [onMeasure]：
 * 以 AT_MOST 形式把上限传给父类测量。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 最大高度（像素）。≤0 表示不限制。 */
    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeight > 0) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
