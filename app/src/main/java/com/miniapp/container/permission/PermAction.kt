package com.miniapp.container.permission

/** 权限审批结果。 */
enum class PermAction {
    ALLOW,           // 允许（持久化授权）
    ALLOW_ONCE,      // 仅允许一次（本次会话有效，下次再问）
    DENY,            // 拒绝（本次，下次会再问）
    DENY_FOREVER     // 不再询问（持久化拒绝，之后不再弹窗）
}
