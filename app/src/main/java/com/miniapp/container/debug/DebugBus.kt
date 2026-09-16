package com.miniapp.container.debug

/**
 * 调试模式总线：记录所有小程序对 JS Bridge 接口的调用（方法、参数、返回值、耗时）。
 * 通过 [enabled] 开关控制；环形缓冲最多保留 [MAX] 条，避免内存无限增长。
 */
object DebugBus {

    private const val MAX = 500

    @Volatile
    var enabled: Boolean = false
        private set

    private val logs = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * 开关调试模式。
     *
     * **不清空已有日志**：关闭后只是停止记录新日志，旧日志仍可查看。
     * 日志清空只有两种时机：
     * 1. 蜗壳进程结束（被从后台划掉）——日志仅存内存，进程结束自然清空；
     * 2. 在调用日志页手动点击「清空」。
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        notifyChanged()
    }

    fun log(line: String) {
        if (!enabled) return
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX) logs.removeFirst()
        }
        notifyChanged()
    }

    fun snapshot(): List<String> = synchronized(logs) { logs.toList() }

    /** 清空全部日志（手动清空时调用），并通知 UI 刷新。 */
    fun clear() {
        synchronized(logs) { logs.clear() }
        notifyChanged()
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
