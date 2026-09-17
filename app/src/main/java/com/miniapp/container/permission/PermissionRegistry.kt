package com.miniapp.container.permission

/**
 * 权限等级。
 *
 * | 等级 | 语义 |
 * |---|---|
 * | [SAFE] | 安全：无需用户审批，调用即可用 |
 * | [NORMAL] | 普通：需用户审批后可用 |
 * | [DANGEROUS] | 危险：需审批；**不可声明为必要权限**（声明会被忽略）；审批界面显示警告 |
 */
enum class PermLevel {
    SAFE,
    NORMAL,
    DANGEROUS
}

/**
 * 一条权限定义。
 *
 * @param scope       权限标识，与 manifest `permissions` 中的字符串一致
 * @param label       显示名（审批弹窗标题、权限管理页）
 * @param description 说明（审批弹窗正文）
 * @param level       等级，决定是否需要审批、能否作为必要权限
 * @param warning     危险权限的额外警告文案（仅 [PermLevel.DANGEROUS] 使用）
 */
data class PermissionDef(
    val scope: String,
    val label: String,
    val description: String,
    val level: PermLevel,
    val warning: String = ""
)

/**
 * 权限注册表 —— 权限系统的框架核心。
 *
 * **新增一个权限只需一步**：在 [defaults] 里加一条 [PermissionDef]。
 * 其余全部自动生效：
 * - 审批流程按 [PermLevel] 决定是否弹窗（[PermissionManager.ensurePermission]）
 * - 危险权限自动被禁止声明为必要权限（[AppInstaller] 解析时剔除）
 * - 审批弹窗显示警告、权限管理页显示等级标签
 * - `PermissionScope.label/description` 自动取到新值
 */
object PermissionRegistry {

    private val defs = LinkedHashMap<String, PermissionDef>()

    /** 内置权限定义。**新增权限在此追加一行即可。** */
    private val defaults: List<PermissionDef> = listOf(
        PermissionDef(
            scope = "net",
            label = "网络访问",
            description = "该小程序请求访问网络（发起 HTTP 请求）。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "sys.openUrl",
            label = "打开外部链接",
            description = "该小程序请求打开外部链接（跳转到系统浏览器）。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "fs.external",
            label = "读写内部储存",
            description = "该小程序请求读写内部储存（沙箱外的文件）。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "clipboard",
            label = "读写剪贴板",
            description = "该小程序请求读写剪贴板。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "notification",
            label = "发送通知",
            description = "该小程序请求发送状态栏通知。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "storage",
            label = "网络存储",
            description = "该小程序请求读写你在「网络存储」中配置的 WebDAV（仅其自己的目录）。",
            level = PermLevel.NORMAL
        ),
        PermissionDef(
            scope = "adb",
            label = "ADB / Shell（Shizuku）",
            description = "该小程序请求通过 Shizuku 以 adb shell 权限执行命令。",
            level = PermLevel.DANGEROUS,
            warning = "⚠ 极度危险：该权限等同于把 adb shell 交给小程序，可能读取系统文件与隐私数据。仅在你完全信任该小程序时授予。"
        )
    )

    init {
        defaults.forEach { register(it) }
    }

    /** 注册（或覆盖）一条权限定义。 */
    fun register(def: PermissionDef) {
        defs[def.scope] = def
    }

    fun get(scope: String): PermissionDef? = defs[scope]

    /** 全部已注册权限（按注册顺序）。 */
    fun all(): List<PermissionDef> = defs.values.toList()

    fun label(scope: String): String = defs[scope]?.label ?: scope

    fun description(scope: String): String =
        defs[scope]?.description ?: "该小程序请求权限：$scope"

    fun warning(scope: String): String = defs[scope]?.warning.orEmpty()

    /** 未注册的 scope 视为普通权限（保守策略：仍需审批）。 */
    fun level(scope: String): PermLevel = defs[scope]?.level ?: PermLevel.NORMAL

    fun isSafe(scope: String): Boolean = level(scope) == PermLevel.SAFE

    fun isDangerous(scope: String): Boolean = level(scope) == PermLevel.DANGEROUS

    /**
     * 是否允许作为「必要权限」声明。
     * 危险权限不允许（清单里声明了也会被忽略，避免用户被"不授权就用不了"胁迫）。
     */
    fun canBeRequired(scope: String): Boolean = level(scope) != PermLevel.DANGEROUS

    /** 过滤必要权限列表：剔除危险权限与未声明项。 */
    fun sanitizeRequired(required: List<String>, declared: List<String>): List<String> =
        required.filter { canBeRequired(it) && it in declared }

    /** 等级显示名。 */
    fun levelLabel(scope: String): String = when (level(scope)) {
        PermLevel.SAFE -> "安全"
        PermLevel.NORMAL -> "普通"
        PermLevel.DANGEROUS -> "危险"
    }
}
