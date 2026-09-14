package com.miniapp.container.permission

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R

/** 权限审批对话框（圆角，4 选项自定义按钮）。 */
class PermissionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
        val scope = requireArguments().getString(ARG_SCOPE) ?: ""
        val token = requireArguments().getString(ARG_TOKEN) ?: ""

        val view = layoutInflater.inflate(R.layout.dialog_permission, null)
        view.findViewById<TextView>(R.id.tv_perm_title).text = "权限审批：${PermissionScope.label(scope)}"
        view.findViewById<TextView>(R.id.tv_perm_desc).text =
            "${PermissionScope.description(scope)}\n\n请选择授权方式："

        val app = (requireActivity().application as MiniAppApp).permissionManager

        fun submit(action: PermAction) {
            when (action) {
                PermAction.ALLOW -> app.recordGrant(appKey, scope)
                PermAction.ALLOW_ONCE -> app.recordTempGrant(appKey, scope)
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

        // 触摸外部不取消（强制选择）；圆角由 MaterialAlertDialogBuilder 提供
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(view).create()
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    companion object {
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
