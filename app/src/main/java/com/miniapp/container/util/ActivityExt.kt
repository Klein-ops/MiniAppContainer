package com.miniapp.container.util

import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.miniapp.container.R

/**
 * 二级页统一工具栏：设 ActionBar + 返回箭头（白色，日间深蓝底可见）。
 *
 * 用法：setContentView 之后调用 `setupBackToolbar()`，需要覆盖标题时传 title。
 * 布局里的 toolbar 用 MaterialToolbar 或 Toolbar 均可（按父类型查找）。
 */
fun AppCompatActivity.setupBackToolbar(title: String? = null) {
    val toolbar = findViewById<Toolbar>(R.id.toolbar)
    setSupportActionBar(toolbar)
    if (title != null) supportActionBar?.title = title
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    toolbar.setNavigationOnClickListener { finish() }
    toolbar.navigationIcon?.setTint(Color.WHITE)
}
