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
 * - **APP** — 小程序主动日志（MiniApp.debug.log）
 *
 * **日志行格式（唯一规范，写入方全部经本类收口）**：
 * `[HH:mm:ss.SSS] TYPE [appKey] 内容`
 * - 时间戳由 [now] 生成（调用方不可控）；
 * - appKey 标签由宿主拼装（小程序可控内容经 [esc] 转义，无法伪造）；
 * - TYPE 仅限 CALL/EVT/ERR/JS/APP；**所有类型都带 [appKey] 段**：CALL/JS/APP 带归属小程序标签，EVT/ERR 及无归属 CALL 带 `[system]`（系统层，仍可过滤）。
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

    /** 日志条目头：`[时间] TYPE [appKey]`。TYPE 限 CALL/EVT/ERR/JS/APP；所有类型都带 [appKey] 段（可为空，空视为无标签）。 */
    private val ENTRY_HEAD = Regex("^\\[[^]]+\\] (CALL|EVT|ERR|JS|APP) \\[([^\\]]*)]")

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

    /** 记录 JS Bridge 接口调用（带 appKey 标签，供宿主按小程序过滤；小程序侧不可见）。 */
    fun logCall(appKey: String?, method: String, params: String, ok: Boolean, payload: String, ms: Long) {
        if (!enabled) return
        val tag = if (ok) "OK" else "ERR"
        val arrow = if (ok) "←" else "✗"
        val appTag = appKey?.let { "[$it]" } ?: "[$SYSTEM_APP_KEY]"
        log("[${now()}] CALL $appTag $method\n  params: ${esc(params)}\n  $arrow $tag: ${esc(payload)}\n  ${ms}ms")
    }

    /** 记录内部事件（权限审批、服务绑定、拦截等）；系统层统一带 [system] 标签。 */
    fun logEvent(category: String, message: String) {
        if (!enabled) return
        log("[${now()}] EVT [$SYSTEM_APP_KEY] $category: ${esc(message)}")
    }

    /** 记录异常与错误；系统层统一带 [system] 标签。 */
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val detail = throwable?.let { " | ${it.javaClass.simpleName}: ${esc(it.message ?: "")}" } ?: ""
        log("[${now()}] ERR [$SYSTEM_APP_KEY] $tag: ${esc(message)}$detail")
    }

    /** 记录前端 JS console 输出（带小程序标签与级别，供按小程序隔离）。 */
    fun logJsConsole(appKey: String?, sourceId: String?, line: Int, message: String?, level: String) {
        if (!enabled) return
        val tag = appKey ?: "?"
        log("[${now()}] JS [$tag] $level ${esc(sourceId ?: "?")}:$line ${esc(message ?: "")}")
    }

    /** 记录小程序主动打的日志（MiniApp.debug.log），强制带 appKey 标签，小程序无法伪造/去掉。 */
    fun logApp(appKey: String, message: String) {
        if (!enabled) return
        log("[${now()}] APP [$appKey] ${esc(message)}")
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
     *
     * 通过 [parseEntry] 严格解析条目头，不模糊 contains。
     */
    fun snapshotForApp(appKey: String): List<String> = synchronized(lock) {
        val f = logFile ?: return emptyList()
        runCatching { f.readLines() }
            .getOrDefault(emptyList())
            .filter { line -> parseEntry(line)?.let { it.type in setOf(Type.APP, Type.JS) && it.appKey == appKey } == true }
    }

    /**
     * 宿主视角：按 appKey 过滤全部日志（含 CALL 接口调用记录），
     * 供 DebugActivity「按小程序过滤」使用；标签严格取条目头。
     * appKey == SYSTEM_APP_KEY 时只显示系统层日志（无归属小程序的 CALL）。
     */
    fun snapshotForHost(appKey: String): List<String> = synchronized(lock) {
        val f = logFile ?: return emptyList()
        runCatching { f.readLines() }
            .getOrDefault(emptyList())
            .filter { line ->
                val e = parseEntry(line) ?: return@filter false
                e.type in setOf(Type.APP, Type.JS, Type.CALL) &&
                    (if (appKey == SYSTEM_APP_KEY) e.appKey == SYSTEM_APP_KEY else e.appKey == appKey)
            }
    }

    /** 手动清空（并通知 UI 刷新）。 */
    fun clear() {
        synchronized(lock) {
            logFile?.let { runCatching { it.writeText("") } }
        }
        notifyChanged()
    }

    /** 只清空指定小程序自己的日志行（APP/JS/CALL 带该标签的条目，其他小程序与系统日志保留）。 */
    fun clearForApp(appKey: String) {
        synchronized(lock) {
            val f = logFile ?: return
            val kept = runCatching { f.readLines() }
                .getOrDefault(emptyList())
                .filterNot { line ->
                    val e = parseEntry(line) ?: return@filterNot false
                    e.type in setOf(Type.APP, Type.JS, Type.CALL) && e.appKey == appKey
                }
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

    /**
     * 系统层 appKey（无归属小程序的日志占位标签）。
     * 作为"系统"参与按小程序过滤（DebugActivity 下拉可选 system）。
     */
    const val SYSTEM_APP_KEY = "system"

    /** 日志条目类型。 */
    enum class Type { CALL, EVT, ERR, JS, APP }

    /** 解析出的日志条目：类型 + 归属 appKey（无标签为 null）。 */
    data class Entry(val type: Type?, val appKey: String?)

    /** 严格解析日志条目头 `[时间] TYPE [appKey]`；解析失败返回 null。 */
    fun parseEntry(line: String): Entry? {
        val m = ENTRY_HEAD.find(line) ?: return null
        val type = when (m.groupValues[1]) {
            "CALL" -> Type.CALL
            "EVT" -> Type.EVT
            "ERR" -> Type.ERR
            "JS" -> Type.JS
            "APP" -> Type.APP
            else -> return null
        }
        return Entry(type, m.groupValues[2].takeIf { it.isNotEmpty() })
    }

    private fun now(): String = ts.format(Date())

    /**
     * 转义日志条目里的小程序可控内容：换行 / 方括号 / 反斜杠。
     * 防止小程序通过 message/params 里的换行伪造出额外的
     * `[时间] APP [别的appKey] ...` 假标签行（保证约束1：标签无法伪造）。
     */
    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("[", "\\[")
        .replace("]", "\\]")
}
