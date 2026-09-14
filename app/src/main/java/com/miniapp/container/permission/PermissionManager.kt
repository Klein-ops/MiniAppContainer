package com.miniapp.container.permission

import android.content.Context
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 权限审批管理器（应用级单例）。
 *
 * - 沙箱内操作默认允许，不走这里。
 * - 出沙箱能力调用 [ensurePermission]：若已授权直接返回 true；
 *   若"不再询问"标记则返回 false（不再弹窗）；
 *   若清单未声明该 scope 返回 false；否则弹出审批对话框（4 选项）。
 */
class PermissionManager(context: Context) {

    private val store = PermissionStore(
        java.io.File(context.filesDir, "miniapps/permissions.json")
    )
    // 仅允许一次：内存临时授权（进程生命周期内有效）
    private val tempGrants = ConcurrentHashMap<String, MutableSet<String>>()
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Boolean>>()

    /** 导入备份后重新加载授权记录。 */
    fun reload() = store.load()

    fun isGranted(appKey: String, scope: String): Boolean =
        store.isGranted(appKey, scope) || (tempGrants[appKey]?.contains(scope) == true)

    fun isDeniedForever(appKey: String, scope: String): Boolean = store.isDeniedForever(appKey, scope)

    fun grantedScopes(appKey: String): List<String> = store.grantedScopes(appKey)

    fun revoke(appKey: String, scope: String) = store.revoke(appKey, scope)

    /** 卸载应用时清除其所有授权记录。 */
    fun clearApp(appKey: String) = store.clearApp(appKey)

    suspend fun ensurePermission(
        activity: FragmentActivity,
        appKey: String,
        declared: List<String>,
        scope: String
    ): Boolean {
        if (store.isGranted(appKey, scope)) return true
        if (tempGrants[appKey]?.contains(scope) == true) return true
        if (store.isDeniedForever(appKey, scope)) return false
        if (scope !in declared) return false
        if (activity.isFinishing) return false

        return suspendCancellableCoroutine { cont ->
            val token = UUID.randomUUID().toString()
            pending[token] = cont
            try {
                val frag = PermissionDialogFragment.newInstance(appKey, scope, token)
                frag.show(activity.supportFragmentManager, "perm_$token")
            } catch (t: Throwable) {
                pending.remove(token)
                cont.resume(false)
            }
            cont.invokeOnCancellation { pending.remove(token) }
        }
    }

    /** 由审批对话框回调，根据 [action] 记录并 resume。 */
    fun resolve(token: String, action: PermAction) {
        val cont = pending.remove(token) ?: return
        when (action) {
            PermAction.ALLOW -> { /* grant 在对话框内已 recordGrant */ }
            PermAction.ALLOW_ONCE -> { /* 临时授权由 recordTempGrant 处理 */ }
            PermAction.DENY -> { /* 无记录 */ }
            PermAction.DENY_FOREVER -> { /* denyForever 在对话框内已 record */ }
        }
        cont.resume(action == PermAction.ALLOW || action == PermAction.ALLOW_ONCE)
    }

    fun recordGrant(appKey: String, scope: String) = store.grant(appKey, scope)
    fun recordTempGrant(appKey: String, scope: String) {
        val set = tempGrants.getOrPut(appKey) { java.util.Collections.synchronizedSet(HashSet()) }
        set.add(scope)
    }
    fun recordDenyForever(appKey: String, scope: String) = store.denyForever(appKey, scope)
}
