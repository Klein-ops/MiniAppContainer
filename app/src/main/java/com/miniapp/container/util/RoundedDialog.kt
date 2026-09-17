package com.miniapp.container.util

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.view.WindowManager
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

    /** 校正 [dialog] 的窗口圆角，并保证其不超出屏幕。 */
    fun apply(dialog: Dialog) {
        applyCorners(dialog)
        clampToScreen(dialog)
    }

    /**
     * 兜底：对话框实际高度超过屏幕可用高度的 90% 时强制限高。
     *
     * 为什么需要：MaterialAlertDialog 用的是 `wrap_content` 窗口，一旦内部
     * 布局（如 `setView` 的自定义内容）测量结果偏大，窗口会被系统直接裁掉，
     * 且**不会**产生滚动，用户就点不到底部按钮。这里事后发现超限便限制窗口高度，
     * 迫使内部的 `ScrollView` 接管滚动。
     */
    private fun clampToScreen(dialog: Dialog) {
        val window = dialog.window ?: return
        val dm = dialog.context.resources.displayMetrics
        val cap = (dm.heightPixels * 0.9f).toInt()
        window.decorView.post {
            val h = window.decorView.height
            val w = window.decorView.width
            if (h > cap && w > 0) {
                window.setLayout(w, cap)
                window.attributes = window.attributes.apply {
                    height = cap
                }
            }
        }
    }

    private fun applyCorners(dialog: Dialog) {
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
