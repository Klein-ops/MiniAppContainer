package com.miniapp.container.permission

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R

/** 权限审批对话框（圆角，4 选项）。 */
class PermissionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
        val scope = requireArguments().getString(ARG_SCOPE) ?: ""
        val token = requireArguments().getString(ARG_TOKEN) ?: ""

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_permission, null)
        view.setBackgroundResource(R.drawable.bg_dialog_rounded)
        view.findViewById<TextView>(R.id.tv_perm_title).text =
            "权限审批：${PermissionScope.label(scope)}"
        view.findViewById<TextView>(R.id.tv_perm_desc).text =
            "应用 [$appKey]\n${PermissionScope.description(scope)}\n\n请选择授权方式："

        val app = (requireActivity().application as MiniAppApp).permissionManager

        fun dismiss(action: PermAction) {
            when (action) {
                PermAction.ALLOW -> app.recordGrant(appKey, scope)
                PermAction.ALLOW_ONCE -> app.recordTempGrant(appKey, scope)
                PermAction.DENY -> {}
                PermAction.DENY_FOREVER -> app.recordDenyForever(appKey, scope)
            }
            app.resolve(token, action)
            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton("允许") { _, _ -> dismiss(PermAction.ALLOW) }
            .setNegativeButton("拒绝") { _, _ -> dismiss(PermAction.DENY) }
            .setNeutralButton("仅本次") { _, _ -> dismiss(PermAction.ALLOW_ONCE) }
            .create().apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
    }

    override fun onStart() {
        super.onStart()
        // 增加"不再询问"按钮到对话框（neutral 之外的第 4 个选项用按钮布局）
        (dialog as? AlertDialog)?.apply {
            getButton(AlertDialog.BUTTON_NEUTRAL)?.let { btn ->
                btn.setOnClickListener {
                    val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
                    val scope = requireArguments().getString(ARG_SCOPE) ?: ""
                    val token = requireArguments().getString(ARG_TOKEN) ?: ""
                    val app = (requireActivity().application as MiniAppApp).permissionManager
                    app.recordDenyForever(appKey, scope)
                    app.resolve(token, PermAction.DENY_FOREVER)
                    dismiss()
                }
                btn.text = "不再询问"
            }
        }
    }

    companion object {
        private const val ARG_APP_KEY = "appKey"
        private const val ARG_SCOPE = "scope"
        private const val ARG_TOKEN = "token"

        fun newInstance(appKey: String, scope: String, token: String): PermissionDialogFragment {
            return PermissionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_APP_KEY, appKey)
                    putString(ARG_SCOPE, scope)
                    putString(ARG_TOKEN, token)
                }
            }
        }
    }
}
