package com.miniapp.container.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.PictureDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.caverock.androidsvg.SVG
import com.miniapp.container.R
import com.miniapp.container.core.MiniAppInfo
import java.io.File

class AppListAdapter : RecyclerView.Adapter<AppListAdapter.VH>() {

    private val items = mutableListOf<MiniAppInfo>()
    var onItemClick: ((MiniAppInfo) -> Unit)? = null
    var onSettingsClick: ((MiniAppInfo) -> Unit)? = null

    fun submit(list: List<MiniAppInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun appKeyAt(pos: Int): String? = items.getOrNull(pos)?.appKey

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val tvVersion: TextView = itemView.findViewById(R.id.tv_version)
        val tvAppkey: TextView = itemView.findViewById(R.id.tv_appkey)
        val imgIcon: ImageView = itemView.findViewById(R.id.img_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val info = items[position]
        holder.tvName.text = info.displayName.ifBlank { info.uname }
        holder.tvVersion.text = info.version
        holder.tvAppkey.text = info.appKey
        loadIcon(holder, info)
        holder.itemView.setOnClickListener { onItemClick?.invoke(info) }
        holder.itemView.findViewById<View>(R.id.btn_settings).setOnClickListener {
            onSettingsClick?.invoke(info)
        }
    }

    /** 加载应用图标（支持 SVG/PNG，无图标用默认）。 */
    private fun loadIcon(holder: VH, info: MiniAppInfo) {
        if (info.icon.isBlank()) return
        val ctx = holder.itemView.context
        val sandbox = File(ctx.filesDir, "miniapps/${info.uid}_${info.uname}")
        val iconFile = File(sandbox, "app/${info.icon}")
        if (!iconFile.exists()) return
        try {
            if (info.icon.endsWith(".svg", ignoreCase = true)) {
                val svg = SVG.getFromInputStream(iconFile.inputStream())
                holder.imgIcon.setImageDrawable(PictureDrawable(svg.renderToPicture()))
            } else {
                BitmapFactory.decodeFile(iconFile.absolutePath)?.let {
                    holder.imgIcon.setImageBitmap(it)
                }
            }
        } catch (_: Exception) { /* 加载失败保持默认图标 */ }
    }

    override fun getItemCount(): Int = items.size
}
