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

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) clear()
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

    fun clear() = synchronized(logs) { logs.clear() }

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
