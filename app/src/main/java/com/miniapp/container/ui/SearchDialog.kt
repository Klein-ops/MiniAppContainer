package com.miniapp.container.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.miniapp.container.R
import com.miniapp.container.core.AppRegistry
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.util.showRounded

/** 主页搜索弹窗：按显示名/包名实时过滤，点选后回调 [onPick]。 */
object SearchDialog {

    fun show(activity: AppCompatActivity, registry: AppRegistry, onPick: (MiniAppInfo) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_search, null)
        val et = view.findViewById<TextInputEditText>(R.id.et_search)
        val rv = view.findViewById<RecyclerView>(R.id.list_search)
        val empty = view.findViewById<TextView>(R.id.tv_search_empty)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("搜索小程序")
            .setView(view)
            .setNegativeButton("关闭", null)
            .showRounded()

        // 复用主页应用列表 Adapter（图标/卡片样式一致），仅隐藏设置按钮
        val adapter = AppListAdapter().apply {
            showSettings = false
            onItemClick = { dialog.dismiss(); onPick(it) }
        }
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter

        var apps = emptyList<MiniAppInfo>()

        fun refresh(query: String) {
            val q = query.trim()
            apps = registry.list().filter {
                q.isEmpty() ||
                    it.displayName.contains(q, ignoreCase = true) ||
                    it.uname.contains(q, ignoreCase = true)
            }
            adapter.submit(apps)
            empty.visibility = if (apps.isEmpty() && q.isNotEmpty()) View.VISIBLE else View.GONE
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refresh("")
        et.requestFocus()
        et.postDelayed({
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }
}
