package com.miniapp.container.debug

import java.io.File

/**
 * 调试模式总线：记录小程序对 JS Bridge 接口的调用（方法、参数、返回值、耗时）。
 *
 * **落盘而非常驻内存**：日志追加写入应用私有目录的 `debug/log.txt`，
 * 由 [attach] 在应用启动时清空（与"日志只保留本次会话"效果一致），
 * 避免长会话下内存无限增长。
 *
 * 清空时机：
 * 1. 应用每次启动（[attach] 时清空）；
 * 2. 在「调用日志」页手动点「清空」。
 * 关闭「调试模式」开关不会清空，只是停止记录。
 */
object DebugBus {

    private const val MAX = 500
    private const val TRIM_THRESHOLD = 2 * MAX

    @Volatile
    var enabled: Boolean = false
        private set

    private val lock = Any()
    private var logFile: File? = null
    private val listeners = mutableListOf<() -> Unit>()

    /** 绑定日志文件并清空（应用启动时调用；仅主进程）。 */
    fun attach(file: File) {
        synchronized(lock) {
            logFile = file.also {
                it.parentFile?.mkdirs()
                runCatching { it.writeText("") }   // 每次启动清空
            }
        }
    }

    /** 开关调试模式：不清空已有日志，只影响是否继续记录。 */
    fun setEnabled(on: Boolean) {
        enabled = on
        notifyChanged()
    }

    fun log(line: String) {
        if (!enabled) return
        synchronized(lock) {
            val f = logFile ?: return
            runCatching { f.appendText(line + "\n") }
            trimIfNeeded(f)
        }
        notifyChanged()
    }

    fun snapshot(): List<String> = synchronized(lock) {
        val f = logFile ?: return emptyList()
        runCatching { f.readLines() }.getOrDefault(emptyList())
    }

    /** 手动清空（并通知 UI 刷新）。 */
    fun clear() {
        synchronized(lock) {
            logFile?.let { runCatching { it.writeText("") } }
        }
        notifyChanged()
    }

    /** 超过阈值时截断为最近 [MAX] 行（避免频繁重写）。 */
    private fun trimIfNeeded(f: File) {
        val size = runCatching { f.readLines().size }.getOrDefault(0)
        if (size > TRIM_THRESHOLD) {
            val tail = runCatching { f.readLines().takeLast(MAX) }.getOrDefault(emptyList())
            runCatching { f.writeText(tail.joinToString("\n") + "\n") }
        }
    }

    fun addListener(l: () -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }
}
