package com.miniapp.container.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.miniapp.container.R
import com.miniapp.container.core.MiniAppInfo

class AppListAdapter : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<MiniAppInfo>()
    var onItemClick: ((MiniAppInfo) -> Unit)? = null
    var onItemLongClick: ((MiniAppInfo) -> Unit)? = null

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
        holder.tvName.text = "${info.uid}/${info.uname}"
        holder.tvVersion.text = info.version
        holder.tvAppkey.text = info.appKey
        holder.itemView.setOnClickListener { onItemClick?.invoke(info) }
        holder.itemView.setOnLongClickListener { onItemLongClick?.invoke(info); true }
    }

    override fun getItemCount(): Int = items.size
}
