package com.miniapp.container.permission

/**
 * 小程序可申请的出沙箱能力范围（常量 + 便捷访问）。
 *
 * **权限的实际定义（等级、标签、说明、警告）集中在 [PermissionRegistry]。**
 * 本对象只提供常量与转发方法，新权限请到 [PermissionRegistry.defaults] 添加。
 */
object PermissionScope {
    const val NET = "net"
    const val FS_EXTERNAL = "fs.external"
    const val OPEN_URL = "sys.openUrl"
    const val CLIPBOARD = "clipboard"
    const val NOTIFICATION = "notification"
    const val STORAGE = "storage"
    const val ADB = "adb"
    const val VIBRATE = "vibrate"
    const val FLASHLIGHT = "flashlight"
    const val CAMERA = "camera"

    fun label(scope: String): String = PermissionRegistry.label(scope)

    fun description(scope: String): String = PermissionRegistry.description(scope)

    fun warning(scope: String): String = PermissionRegistry.warning(scope)

    fun level(scope: String): PermLevel = PermissionRegistry.level(scope)

    fun isDangerous(scope: String): Boolean = PermissionRegistry.isDangerous(scope)

    fun canBeRequired(scope: String): Boolean = PermissionRegistry.canBeRequired(scope)
}
