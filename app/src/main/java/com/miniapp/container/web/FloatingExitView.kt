package com.miniapp.container.web

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * 可拖动、贴边、闲置半透明的悬浮退出按钮。
 * - 点击（位移小于阈值）→ 回调 [onExit]
 * - 拖动 → 跟随手指
 * - 松手 → 动画贴到最近屏幕边，延迟变半透明
 * - 触摸时恢复不透明
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

    private val idleAlpha = 0.35f
    private val activeAlpha = 1f
    private val idleHideRunnable = Runnable { animate().alpha(idleAlpha).setDuration(300).start() }

    init {
        alpha = idleAlpha
    }

    fun scheduleIdleHide() {
        removeCallbacks(idleHideRunnable)
        postDelayed(idleHideRunnable, 2500)
    }

    private fun stickToEdge() {
        val parent = parent as? android.view.View ?: return
        val edge = if (x + width / 2 < parent.width / 2) 0f else (parent.width - width).toFloat()
        val targetY = y.coerceIn(0f, (parent.height - height).toFloat())
        animate().x(edge).y(targetY).setDuration(180).withEndAction { scheduleIdleHide() }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = x
                downY = y
                dragged = false
                animate().cancel()
                removeCallbacks(idleHideRunnable)
                animate().alpha(activeAlpha).setDuration(150).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (dragged || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragged = true
                    val parent = parent as? android.view.View
                    val pw = parent?.width ?: 0
                    val ph = parent?.height ?: 0
                    x = (downX + dx).coerceIn(0f, (pw - width).toFloat())
                    y = (downY + dy).coerceIn(0f, (ph - height).toFloat())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    onExit?.invoke()
                    return true
                }
                stickToEdge()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragged) stickToEdge() else scheduleIdleHide()
            }
        }
        return true
    }
}
