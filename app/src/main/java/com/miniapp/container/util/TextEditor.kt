package com.miniapp.container.util

import java.util.regex.Pattern

/**
 * 简易文本编辑器（grep/sed 风格），供 fs.grep / fs.sed / fs.*External 调用。
 *
 * 适合**配置文件**的局部修改：调用方无需整文件读改写，只描述"做什么"。
 * 实现：内存中读-改-写。对大文件请慎用。
 *
 * 支持 sed 命令（每行一条）：
 * - `s/old/new/[gi]`：行内替换；`g` 全局，`i` 忽略大小写。分隔符可为 `/ | # ~`
 * - `Nd`：删除第 N 行（1-based；`$` 末行）
 * - `/pattern/d`：删除匹配 pattern 的行（pattern 为正则）
 * - `/pattern/a\\ text`：匹配行后追加 text（`a` append）
 * - `/pattern/i\\ text`：匹配行前插入 text（`i` insert）
 * - `#...` 或空行：注释/跳过
 */
object TextEditor {

    /** grep：返回匹配（或反转后不匹配）的行内容。 */
    fun grep(
        text: String,
        pattern: String,
        regex: Boolean,
        ignoreCase: Boolean,
        invert: Boolean
    ): List<String> {
        val out = ArrayList<String>()
        val compiled = if (regex) {
            try {
                Pattern.compile(pattern, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
            } catch (t: Throwable) {
                return out   // 非法正则 → 空结果
            }
        } else null
        for (line in text.split("\n")) {
            val hit = if (compiled != null) compiled.matcher(line).find()
                      else line.contains(pattern, ignoreCase)
            if (hit != invert) out.add(line)
        }
        return out
    }

    /** sed：返回编辑后的全文。 */
    fun sed(text: String, script: String): String {
        val lines = text.split("\n").toMutableList()
        for (raw in script.split("\n")) {
            val cmd = raw.trim()
            if (cmd.isEmpty() || cmd.startsWith("#")) continue
            try {
                applyCommand(lines, cmd)
            } catch (_: Throwable) { /* 单条命令失败跳过，保证脚本不中断 */ }
        }
        return lines.joinToString("\n")
    }

    private fun applyCommand(lines: MutableList<String>, cmd: String) {
        when {
            cmd.startsWith("s") -> applySubstitute(lines, cmd)
            cmd.endsWith("/d") && cmd.length > 2 && cmd.startsWith("/") ->
                applyDeleteByPattern(lines, cmd.removeSuffix("/d").removePrefix("/"))
            cmd.endsWith("d") && cmd.dropLast(1).matches(Regex("\\$|\\d+")) -> {
                // 行号删除：Nd 或 $d
                val idx = if (cmd == "d") return  // 单个 d 非法
                    else if (cmd.dropLast(1) == "\$") lines.lastIndex
                    else cmd.dropLast(1).toInt() - 1
                if (idx in lines.indices) lines.removeAt(idx)
            }
            cmd.contains("/a\\") -> applyAppend(lines, cmd)
            cmd.contains("/i\\") -> applyInsert(lines, cmd)
        }
    }

    /** s/old/new/[gi] 或 s|old|new|[gi] 等。 */
    private fun applySubstitute(lines: MutableList<String>, cmd: String) {
        if (cmd.length < 2) return
        val sep = cmd[1]
        val parts = cmd.split(sep.toString().toRegex())
        // ["s", "old", "new", "gi"]
        if (parts.size < 4) return
        val old = parts[1]
        val new = parts[2]
        val flags = parts.getOrNull(3) ?: ""
        val global = "g" in flags
        val ignoreCase = "i" in flags
        val pat = try {
            Pattern.compile(old, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
        } catch (t: Throwable) {
            // 非正则：字面匹配
            for (i in lines.indices) {
                lines[i] = if (ignoreCase)
                    lines[i].replace(old, new, ignoreCase = true)
                else lines[i].replace(old, new)
            }
            return
        }
        for (i in lines.indices) {
            val m = pat.matcher(lines[i])
            lines[i] = if (global) m.replaceAll(new) else m.replaceFirst(new)
        }
    }

    private fun applyDeleteByPattern(lines: MutableList<String>, pattern: String) {
        val pat = try { Pattern.compile(pattern) } catch (t: Throwable) { return }
        val it = lines.iterator()
        while (it.hasNext()) if (pat.matcher(it.next()).find()) it.remove()
    }

    /** /pattern/a\\ text */
    private fun applyAppend(lines: MutableList<String>, cmd: String) {
        val (pat, text) = parseInsertCmd(cmd, 'a') ?: return
        val matcher = pat.matcher("")
        for (i in lines.indices.reversed()) {
            matcher.reset(lines[i])
            if (matcher.find()) {
                lines.add(i + 1, text)
            }
        }
    }

    /** /pattern/i\\ text */
    private fun applyInsert(lines: MutableList<String>, cmd: String) {
        val (pat, text) = parseInsertCmd(cmd, 'i') ?: return
        val matcher = pat.matcher("")
        for (i in lines.indices.reversed()) {
            matcher.reset(lines[i])
            if (matcher.find()) {
                lines.add(i, text)
            }
        }
    }

    private fun parseInsertCmd(cmd: String, op: Char): Pair<Pattern, String>? {
        // 形如 /pattern/a\\ text（a 前后各一个分隔符），允许 \\\ 转义
        val marker = "/$op"
        val idx = cmd.indexOf(marker)
        if (idx <= 0) return null
        val pattern = cmd.substring(1, idx)
        val rest = cmd.substring(idx + 2)
        val text = if (rest.startsWith("\\\\")) rest.substring(2) else
            if (rest.startsWith("\\")) rest.substring(1) else rest
        val pat = try { Pattern.compile(pattern) } catch (t: Throwable) { return null }
        return pat to text
    }
}
