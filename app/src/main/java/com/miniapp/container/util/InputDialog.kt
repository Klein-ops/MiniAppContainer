package com.miniapp.container.util

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.miniapp.container.R

/**
 * 统一的文本输入弹窗视图。
 *
 * 裸 `EditText` 直接 `setView` 会贴到对话框边缘（下划线与文字顶到卡片边），
 * 因此需要用户输入的地方一律用本工具构造视图：
 *
 * ```kotlin
 * val (view, input) = InputDialog.create(this, "分类名称")
 * MaterialAlertDialogBuilder(this).setTitle("新建分类").setView(view)...showRounded()
 * ```
 */
object InputDialog {

    /**
     * @param hint      输入框提示文本
     * @param initial   初始内容（非空时定位到末尾）
     * @param inputType 输入类型，见 [InputType]，默认普通文本
     */
    fun create(
        context: Context,
        hint: String,
        initial: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): Pair<View, EditText> {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        val til = root.findViewById<TextInputLayout>(R.id.til_input)
        val et = root.findViewById<TextInputEditText>(R.id.et_input)

        til.hint = hint
        et.inputType = inputType
        if (initial.isNotEmpty()) {
            et.setText(initial)
            et.setSelection(et.text?.length ?: 0)
        }
        return root to et
    }
}
