package com.miniapp.container.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.R
import com.miniapp.container.core.MiniAppInfo

class AppListAdapter : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<MiniAppInfo>()
    var onItemClick: ((MiniAppInfo) -> Unit)? = null
    var onPermManageClick: ((MiniAppInfo) -> Unit)? = null
    var onUninstallClick: ((MiniAppInfo) -> Unit)? = null

    fun submit(list: List<MiniAppInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val tvVersion: TextView = itemView.findViewById(R.id.tv_version)
        val tvAppkey: TextView = itemView.findViewById(R.id.tv_appkey)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val info = items[position]
        holder.tvName.text = info.uname
        holder.tvVersion.text = info.version
        holder.tvAppkey.text = info.appKey
        holder.itemView.setOnClickListener { onItemClick?.invoke(info) }
        holder.itemView.findViewById<View>(R.id.btn_settings).setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menuInflater.inflate(R.menu.app_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_perm_manage -> { onPermManageClick?.invoke(info); true }
                    R.id.action_uninstall -> { onUninstallClick?.invoke(info); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = items.size
}
