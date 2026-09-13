package com.miniapp.container.permission

/** 小程序可申请的出沙箱能力范围。 */
object PermissionScope {
    const val NET = "net"
    const val FS_EXTERNAL = "fs.external"
    const val OPEN_URL = "sys.openUrl"
    const val CLIPBOARD = "clipboard"

    fun label(scope: String): String = when (scope) {
        NET -> "网络访问"
        FS_EXTERNAL -> "读写内部储存"
        OPEN_URL -> "打开外部链接"
        CLIPBOARD -> "读写剪贴板"
        else -> scope
    }

    fun description(scope: String): String = when (scope) {
        NET -> "该小程序请求访问网络（发起 HTTP 请求）。"
        FS_EXTERNAL -> "该小程序请求读写内部储存（沙箱外的文件）。"
        OPEN_URL -> "该小程序请求打开外部链接（跳转到系统浏览器）。"
        CLIPBOARD -> "该小程序请求读写剪贴板。"
        else -> "该小程序请求权限：$scope"
    }
}
