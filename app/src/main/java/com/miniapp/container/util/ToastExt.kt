package com.miniapp.container.util

import android.content.Context
import android.widget.Toast

/** 全局短提示扩展函数（各 Activity 统一使用，避免重复定义）。 */
fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
