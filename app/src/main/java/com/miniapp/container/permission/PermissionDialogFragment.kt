package com.miniapp.container.permission

import android.app.Dialog
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R

/** 权限审批对话框。用户选择后回调 PermissionManager.resolve(token, granted)。 */
class PermissionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
        val scope = requireArguments().getString(ARG_SCOPE) ?: ""
        val token = requireArguments().getString(ARG_TOKEN) ?: ""

        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_permission, null)
        view.findViewById<TextView>(R.id.tv_perm_title).text =
            "权限审批：${PermissionScope.label(scope)}"
        view.findViewById<TextView>(R.id.tv_perm_desc).text =
            "应用 [$appKey]\n${PermissionScope.description(scope)}\n\n是否允许？"

        val app = (requireActivity().application as MiniAppApp).permissionManager

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.perm_allow) { _, _ ->
                app.recordGrant(appKey, scope)
                app.resolve(token, true)
            }
            .setNegativeButton(R.string.perm_deny) { _, _ ->
                app.resolve(token, false)
            }
            .setOnCancelListener {
                app.resolve(token, false)
            }
            .create()
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
