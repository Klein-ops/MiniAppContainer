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
 *   若清单未声明该 scope 返回 false；否则弹出审批对话框，等待用户选择。
 */
class PermissionManager(context: Context) {

    private val store = PermissionStore(
        java.io.File(context.filesDir, "miniapps/permissions.json")
    )
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Boolean>>()

    fun isGranted(appKey: String, scope: String): Boolean = store.isGranted(appKey, scope)

    fun grantedScopes(appKey: String): List<String> = store.grantedScopes(appKey)

    fun revoke(appKey: String, scope: String) = store.revoke(appKey, scope)

    suspend fun ensurePermission(
        activity: FragmentActivity,
        appKey: String,
        declared: List<String>,
        scope: String
    ): Boolean {
        if (store.isGranted(appKey, scope)) return true
        // 清单未声明的能力一律拒绝
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

    /** 由审批对话框回调。 */
    fun resolve(token: String, granted: Boolean) {
        val cont = pending.remove(token) ?: return
        cont.resume(granted)
    }

    /** 审批通过后记录授权。 */
    fun recordGrant(appKey: String, scope: String) = store.grant(appKey, scope)
}
