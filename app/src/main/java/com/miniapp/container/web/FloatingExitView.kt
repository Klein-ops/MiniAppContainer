package com.miniapp.container.web

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * 可拖动、贴边、闲置半透明的悬浮退出按钮。
 * - 点击（位移小于阈值）→ 回调 [onExit]
 * - 拖动 → 跟随手指；松手 → 贴边
 * - 打开/贴边后闲置 3s 向屏幕边缘平滑滑出，保留约 40% 可见；触摸时滑回完全可见
 */
class FloatingExitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onExit: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var collapsed = false
    private var stickX = 0f   // 贴边后的完全可见位置

    private val idleAlpha = 0.35f
    private val activeAlpha = 1f
    private val idleDelay = 3000L      // 闲置多久后收起
    private val collapseDuration = 320L // 滑出动画时长（平滑，不瞬移）
    private val expandDuration = 200L

    private val idleHideRunnable = Runnable { animate().alpha(idleAlpha).setDuration(300).start() }
    private val collapseRunnable = Runnable { collapse() }

    init { alpha = idleAlpha }

    /** 安排闲置后自动贴边收起（打开小程序、拖动贴边后调用）。 */
    fun scheduleIdleHide() {
        removeCallbacks(idleHideRunnable)
        removeCallbacks(collapseRunnable)
        postDelayed(collapseRunnable, idleDelay)
        postDelayed(idleHideRunnable, idleDelay)
    }

    /** 计算并记录贴边后的完全可见位置（供 [expand] 使用）。 */
    private fun computeStickX(parent: View): Float =
        if (x + width / 2 < parent.width / 2) 0f else (parent.width - width).toFloat()

    /** 向边缘滑出，保留约 40% 可见（保证全面屏手势下仍可点击）。 */
    private fun collapse() {
        if (collapsed) return
        val parent = parent as? View ?: return
        if (width == 0 || parent.width == 0) return   // 尚未完成布局，等下次调度
        collapsed = true
        stickX = computeStickX(parent)                 // 记录，保证 expand 能回到正确位置
        val visible = width * 0.40f
        val isLeft = x + width / 2 < parent.width / 2
        val hiddenX = if (isLeft) -width + visible else (parent.width - visible).toFloat()
        animate().x(hiddenX).setDuration(collapseDuration).start()
    }

    /** 滑回完全可见的贴边位置。 */
    private fun expand() {
        if (!collapsed) return
        collapsed = false
        animate().x(stickX).setDuration(expandDuration).start()
    }

    private fun stickToEdge() {
        val parent = parent as? View ?: return
        stickX = computeStickX(parent)
        val targetY = y.coerceIn(0f, (parent.height - height).toFloat())
        animate().x(stickX).y(targetY).setDuration(200)
            .withEndAction {
                dragged = false   // 重置，允许后续 collapse
                scheduleIdleHide()
            }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                downX = x; downY = y
                dragged = false
                animate().cancel()
                removeCallbacks(idleHideRunnable)
                removeCallbacks(collapseRunnable)
                expand()
                animate().alpha(activeAlpha).setDuration(150).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (dragged || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragged = true
                    val parent = parent as? View
                    val pw = parent?.width ?: 0
                    val ph = parent?.height ?: 0
                    x = (downX + dx).coerceIn(0f, (pw - width).toFloat())
                    y = (downY + dy).coerceIn(0f, (ph - height).toFloat())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) { onExit?.invoke(); return true }
                stickToEdge()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragged) stickToEdge() else { dragged = false; scheduleIdleHide() }
            }
        }
        return true
    }
}
