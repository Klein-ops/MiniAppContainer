package com.miniapp.container.permission

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.MaxHeightScrollView
import com.miniapp.container.util.RoundedDialog

/** 权限审批对话框（圆角，4 选项自定义按钮）。 */
class PermissionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
        val scope = requireArguments().getString(ARG_SCOPE) ?: ""
        val token = requireArguments().getString(ARG_TOKEN) ?: ""

        val view = layoutInflater.inflate(R.layout.dialog_permission, null)

        // 内容区限高：长文案（如危险权限警告）时内部滚动，保证下方 4 个按钮始终可见可点
        val dm = resources.displayMetrics
        val reservedPx = (BTN_AREA_DP * dm.density).toInt()   // 按钮区 + 内边距
        view.findViewById<MaxHeightScrollView>(R.id.scroll_perm).maxHeight =
            ((dm.heightPixels * 0.9f).toInt() - reservedPx)
                .coerceAtLeast((MIN_CONTENT_DP * dm.density).toInt())

        view.findViewById<TextView>(R.id.tv_perm_title).text = "权限审批：${PermissionScope.label(scope)}"
        view.findViewById<TextView>(R.id.tv_perm_desc).text =
            "${PermissionScope.description(scope)}\n\n请选择授权方式："

        // 危险权限：显示醒目警告
        val tvWarning = view.findViewById<TextView>(R.id.tv_perm_warning)
        val warning = PermissionScope.warning(scope)
        if (PermissionRegistry.isDangerous(scope) && warning.isNotEmpty()) {
            tvWarning.text = warning
            tvWarning.visibility = android.view.View.VISIBLE
        } else {
            tvWarning.visibility = android.view.View.GONE
        }

        val app = MiniAppApp.require(requireActivity().application).permissionManager

        fun submit(action: PermAction) {
            when (action) {
                PermAction.ALLOW -> app.recordGrant(appKey, scope)
                PermAction.ALLOW_ONCE -> {}   // 只放行当前这次调用，不写任何持久状态
                PermAction.DENY -> {}
                PermAction.DENY_FOREVER -> app.recordDenyForever(appKey, scope)
            }
            app.resolve(token, action)
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_allow).setOnClickListener { submit(PermAction.ALLOW) }
        view.findViewById<MaterialButton>(R.id.btn_allow_once).setOnClickListener { submit(PermAction.ALLOW_ONCE) }
        view.findViewById<MaterialButton>(R.id.btn_deny).setOnClickListener { submit(PermAction.DENY) }
        view.findViewById<MaterialButton>(R.id.btn_deny_forever).setOnClickListener { submit(PermAction.DENY_FOREVER) }

        // 触摸外部不取消（强制选择）；窗口背景统一圆角（跨 ROM 一致）
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)
        RoundedDialog.apply(dialog)
        return dialog
    }

    companion object {
        /** 按钮区（4 个按钮 + 边距）预留高度，单位 dp，用于给滚动区限高。 */
        private const val BTN_AREA_DP = 270
        /** 滚动区最小高度，单位 dp。 */
        private const val MIN_CONTENT_DP = 140

        private const val ARG_APP_KEY = "appKey"
        private const val ARG_SCOPE = "scope"
        private const val ARG_TOKEN = "token"

        fun newInstance(appKey: String, scope: String, token: String): PermissionDialogFragment =
            PermissionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_APP_KEY, appKey)
                    putString(ARG_SCOPE, scope)
                    putString(ARG_TOKEN, token)
                }
            }
    }
}
