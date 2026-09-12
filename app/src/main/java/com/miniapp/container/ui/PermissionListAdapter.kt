package com.miniapp.container.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.R
import com.miniapp.container.permission.PermissionScope

data class PermItem(val scope: String, val required: Boolean, val granted: Boolean)

class PermissionListAdapter : RecyclerView.Adapter<PermissionListAdapter.VH>() {

    private val items = mutableListOf<PermItem>()
    var onToggle: ((scope: String, granted: Boolean) -> Unit)? = null

    fun submit(list: List<PermItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setGranted(scope: String, granted: Boolean) {
        val i = items.indexOfFirst { it.scope == scope }
        if (i >= 0) {
            items[i] = items[i].copy(granted = granted)
            notifyItemChanged(i)
        }
    }

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.tv_perm_name)
        val tag: TextView = itemView.findViewById(R.id.tv_perm_tag)
        val sw: SwitchCompat = itemView.findViewById(R.id.switch_perm)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_permission, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.name.text = PermissionScope.label(it.scope)
        holder.tag.text = if (it.required) "必要 · 拒绝后无法运行" else "可选"
        holder.tag.setTextColor(
            if (it.required) 0xFFE03131.toInt() else 0xFF6B7280.toInt()
        )
        // 先解绑，避免回填触发
        holder.sw.setOnCheckedChangeListener(null)
        holder.sw.isChecked = it.granted
        holder.sw.setOnCheckedChangeListener { _, isChecked ->
            onToggle?.invoke(it.scope, isChecked)
        }
    }

    override fun getItemCount(): Int = items.size
}
