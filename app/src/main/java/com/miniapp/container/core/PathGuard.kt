package com.miniapp.container.core

import java.io.File

/** 路径清洗与沙箱穿越防护。 */
object PathGuard {

    private val NAME_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** uid/uname 清洗：禁止路径分隔符、.. 等。返回清洗后的合法串或 null。 */
    fun sanitizeName(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 64) return null
        if (s.contains('/') || s.contains('\\') || s == "." || s == "..") return null
        if (!NAME_RE.matches(s)) return null
        return s
    }

    fun appKey(uid: String, uname: String): String = "${uid}_${uname}"

    /**
     * 将 [path]（相对路径或绝对路径）解析为 [root] 沙箱之内的 File。
     *
     * - 相对路径（如 `app/index.html`、`data/x.txt`）→ 以 [root] 为基。
     * - 绝对路径（如 `file://` 拦截到的 `/data/data/<pkg>/.../app/index.html`）
     *   → 直接作为绝对路径，仅校验是否落在 [root] 内。
     * - 解析后若逃出 [root]，抛出 SecurityException（出沙箱）。
     */
    fun resolveUnderRoot(root: File, path: String): File {
        var raw = path.trim()
        if (raw.isEmpty()) return root.canonicalFile
        if (raw.startsWith("file://")) raw = raw.substring("file://".length)
        val candidate = if (raw.startsWith("/") || raw.startsWith("\\")) {
            File(raw)
        } else {
            File(root, raw)
        }
        val rp = root.canonicalPath
        val tp = try {
            candidate.canonicalPath
        } catch (e: Exception) {
            candidate.absolutePath
        }
        if (tp != rp && !tp.startsWith(rp + File.separator)) {
            throw SecurityException("path escapes sandbox: $path")
        }
        return File(tp)
    }

    /** 判断 file 是否在 root 沙箱内（含 root 自身）。 */
    fun isInSandbox(root: File, file: File): Boolean {
        val rp = root.canonicalPath
        val tp = file.canonicalPath
        return tp == rp || tp.startsWith(rp + File.separator)
    }
}
