package com.miniapp.container.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.miniapp.container.R
import com.miniapp.container.permission.PermissionRegistry
import com.miniapp.container.permission.PermissionScope
import kotlin.math.roundToInt

/** 权限管理页的授权三态：允许 / 拒绝（可再询问）/ 不再询问。 */
enum class PermState { GRANTED, DENIED, DENIED_FOREVER }

data class PermItem(val scope: String, val required: Boolean, val state: PermState)

class PermissionListAdapter : RecyclerView.Adapter<PermissionListAdapter.VH>() {

    private val items = mutableListOf<PermItem>()
    var onStateChange: ((scope: String, state: PermState) -> Unit)? = null

    fun submit(list: List<PermItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setState(scope: String, state: PermState) {
        val i = items.indexOfFirst { it.scope == scope }
        if (i >= 0) {
            items[i] = items[i].copy(state = state)
            notifyItemChanged(i)
        }
    }

    private fun stateToValue(s: PermState): Float = when (s) {
        PermState.GRANTED -> 0f
        PermState.DENIED -> 1f
        PermState.DENIED_FOREVER -> 2f
    }

    private fun valueToState(v: Float): PermState = when (v.roundToInt()) {
        0 -> PermState.GRANTED
        2 -> PermState.DENIED_FOREVER
        else -> PermState.DENIED
    }

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.tv_perm_name)
        val tag: TextView = itemView.findViewById(R.id.tv_perm_tag)
        val slider: Slider = itemView.findViewById(R.id.slider_perm)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_permission, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.name.text = PermissionScope.label(it.scope)
        val levelText = PermissionRegistry.levelLabel(it.scope)
        holder.tag.text = when {
            PermissionRegistry.isDangerous(it.scope) -> "$levelText · 需谨慎"
            it.required -> "$levelText · 必要（拒绝后无法运行）"
            else -> "$levelText · 可选"
        }
        holder.tag.setTextColor(
            when {
                PermissionRegistry.isDangerous(it.scope) -> 0xFFE03131.toInt()
                it.required -> 0xFFE03131.toInt()
                else -> 0xFF6B7280.toInt()
            }
        )
        // 先解绑避免回填触发；拖动过程即回调，最终值写入持久状态
        holder.slider.clearOnChangeListeners()
        holder.slider.value = stateToValue(it.state)
        holder.slider.addOnChangeListener { _, value, _ ->
            onStateChange?.invoke(it.scope, valueToState(value))
        }
    }

    override fun getItemCount(): Int = items.size
}
