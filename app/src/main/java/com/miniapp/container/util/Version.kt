package com.miniapp.container.util

/** 语义化版本号比较（安装/更新的"更新/降级/替换"判定，也可供将来检查更新复用）。 */
object Version {

    /** 比较 a 与 b：a>b 返回 1，a<b 返回 -1，相等返回 0。非数字段忽略，缺省段当 0。 */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
