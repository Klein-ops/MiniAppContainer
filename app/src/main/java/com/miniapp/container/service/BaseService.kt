package com.miniapp.container.service

import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.permission.PermissionManager
import com.miniapp.container.ui.MiniAppActivity
import org.json.JSONObject

/**
 * service 层基类：统一「蜗壳权限审批」与「失败 JSON 格式」。
 *
 * 此前 8 个 service 各自重复实现 ensurePermission + fail JSON，本类把这两件事
 * 收敛为内核。子类构造仍为 `(activity, appInfo, permissionManager)`，
 * 调用方 [com.miniapp.container.bridge.MiniAppBridge] 无需改动。
 *
 * 新增 service：继承本类，用 [requirePermission] 做审批、[failJson] 报错。
 */
abstract class BaseService(
    protected val activity: MiniAppActivity,
    protected val appInfo: MiniAppInfo,
    private val permissionManager: PermissionManager
) {

    /**
     * 蜗壳权限审批：未授权抛 `SecurityException("permission denied: <scope>")`。
     * scope 直接取 [com.miniapp.container.permission.PermissionScope] 常量，
     * 因此错误文案与历史版本逐字一致。
     */
    protected suspend fun requirePermission(scope: String) {
        val granted = permissionManager.ensurePermission(
            activity, appInfo.appKey, appInfo.permissions, scope
        )
        if (!granted) throw SecurityException("permission denied: $scope")
    }

    /** 统一失败 JSON：`{ok:false, error, detail}`。 */
    protected fun failJson(error: String, detail: String): String = JSONObject()
        .put("ok", false)
        .put("error", error)
        .put("detail", detail)
        .toString()
}
