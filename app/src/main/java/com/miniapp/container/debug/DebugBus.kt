package com.miniapp.container.debug

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试模式总线：记录小程序运行时的一切调试信息。
 *
 * 日志类型：
 * - **CALL** — JS Bridge 接口调用（方法、参数、返回值/错误、耗时）
 * - **EVT** — 内部事件（权限审批、Shizuku 绑定、沙箱安装/卸载、WebView 拦截等）
 * - **ERR** — 异常与错误（接口异常、服务绑定失败、Dex 隔离进程崩溃等）
 * - **JS**  — 前端 console 输出（console.log/warn/error 等）
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
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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

    // ---------- 分类日志方法 ----------

    /** 记录 JS Bridge 接口调用。 */
    fun logCall(method: String, params: String, ok: Boolean, payload: String, ms: Long) {
        if (!enabled) return
        val tag = if (ok) "OK" else "ERR"
        val arrow = if (ok) "←" else "✗"
        log("[${now()}] CALL $method\n  params: $params\n  $arrow $tag: $payload\n  ${ms}ms")
    }

    /** 记录内部事件（权限审批、服务绑定、拦截等）。 */
    fun logEvent(category: String, message: String) {
        if (!enabled) return
        log("[${now()}] EVT $category: $message")
    }

    /** 记录异常与错误。 */
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val detail = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        log("[${now()}] ERR $tag: $message$detail")
    }

    /** 记录前端 JS console 输出（带小程序标签与级别，供按小程序隔离）。 */
    fun logJsConsole(appKey: String?, sourceId: String?, line: Int, message: String?, level: String) {
        if (!enabled) return
        val tag = appKey ?: "?"
        log("[${now()}] JS [$tag] $level ${sourceId ?: "?"}:$line ${message ?: ""}")
    }

    /** 记录小程序主动打的日志（MiniApp.debug.log），强制带 appKey 标签，小程序无法伪造/去掉。 */
    fun logApp(appKey: String, message: String) {
        if (!enabled) return
        log("[${now()}] APP [$appKey] $message")
    }

    // ---------- 底层 ----------

    fun log(line: String) {
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

    /**
     * 按小程序 appKey 过滤日志：只返回该小程序自己的日志
     * （APP 主动日志 + 该小程序的 console 日志），
     * 永远不包含其他小程序的日志与系统层 CALL/EVT/ERR 日志。
     */
    fun snapshotForApp(appKey: String): List<String> = synchronized(lock) {
        val f = logFile ?: return emptyList()
        runCatching { f.readLines() }
            .getOrDefault(emptyList())
            .filter { it.contains("[$appKey]") }
    }

    /** 手动清空（并通知 UI 刷新）。 */
    fun clear() {
        synchronized(lock) {
            logFile?.let { runCatching { it.writeText("") } }
        }
        notifyChanged()
    }

    /** 只清空指定小程序自己的日志行（其他小程序与系统日志保留）。 */
    fun clearForApp(appKey: String) {
        synchronized(lock) {
            val f = logFile ?: return
            val kept = runCatching { f.readLines() }
                .getOrDefault(emptyList())
                .filterNot { it.contains("[$appKey]") }
            runCatching { f.writeText(kept.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" }) }
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

    private fun now(): String = ts.format(Date())
}