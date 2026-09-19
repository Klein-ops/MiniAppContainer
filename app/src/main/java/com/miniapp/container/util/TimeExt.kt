package com.miniapp.container.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件名用时间戳：yyyyMMdd_HHmmss。 */
fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
