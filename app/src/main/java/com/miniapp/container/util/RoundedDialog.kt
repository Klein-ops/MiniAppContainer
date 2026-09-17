package com.miniapp.container.util

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.miniapp.container.R

/**
 * 统一对话框圆角。
 *
 * 背景：不同 ROM / 系统版本对 Material 对话框背景形状的处理不一致，部分设备上
 * 24dp 圆角会退化成直角。这里显式校正窗口背景：
 * 1. 若窗口背景本身就是 [MaterialShapeDrawable]（Material 自行绘制），直接改它的
 *    圆角——保留 Material 的内边距与布局，不产生位移；
 * 2. 否则（自定义视图对话框）用圆角矩形作为窗口背景。
 */
object RoundedDialog {

    private const val RADIUS_DP = 24f

    /** 校正 [dialog] 的窗口圆角。 */
    fun apply(dialog: Dialog) {
        val window = dialog.window ?: return
        val radiusPx = RADIUS_DP * dialog.context.resources.displayMetrics.density
        val shaped = findShapeDrawable(window.decorView.background)
        if (shaped != null) {
            shaped.shapeAppearanceModel = shaped.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(radiusPx)
                .build()
        } else {
            window.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(ContextCompat.getColor(dialog.context, R.color.bg_card))
                    cornerRadius = radiusPx
                }
            )
        }
    }

    private fun findShapeDrawable(d: Drawable?): MaterialShapeDrawable? = when (d) {
        is MaterialShapeDrawable -> d
        is InsetDrawable -> findShapeDrawable(d.drawable)
        else -> null
    }
}

/** 显示对话框并统一圆角（替代 `.show()`）。 */
fun MaterialAlertDialogBuilder.showRounded(): AlertDialog {
    val dialog = show()
    RoundedDialog.apply(dialog)
    return dialog
}
