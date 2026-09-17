package com.miniapp.container.permission

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.util.MaxHeightScrollView

/**
 * 权限审批对话框。
 *
 * **不使用 MaterialAlertDialogBuilder.setView**：Material 对自定义视图的窗口是
 * `wrap_content` 且内容不参与滚动，内容一长窗口会被系统直接裁掉、底部按钮点不到，
 * 且事后无法补救。这里改为自行创建 [Dialog] 并完全掌控窗口尺寸：
 *
 * - 内容（标题/说明/警告/4 个按钮）全部放在限高的 [MaxHeightScrollView] 内
 * - 窗口高度 `WRAP_CONTENT`：内容短时自适应收缩
 * - 限高取屏幕高度的 [MAX_HEIGHT_RATIO]，留足系统栏余量；超出即内部滚动
 * - `onStart` 里再按实际测量结果兜底一次，确保任何机型都不会被裁
 */
class PermissionDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val appKey = requireArguments().getString(ARG_APP_KEY) ?: ""
        val scope = requireArguments().getString(ARG_SCOPE) ?: ""
        val token = requireArguments().getString(ARG_TOKEN) ?: ""

        val view = layoutInflater.inflate(R.layout.dialog_permission, null)
        val dm = resources.displayMetrics
        val capPx = (dm.heightPixels * MAX_HEIGHT_RATIO).toInt()
        view.findViewById<MaxHeightScrollView>(R.id.scroll_perm).maxHeight = capPx

        view.findViewById<TextView>(R.id.tv_perm_title).text =
            "权限审批：${PermissionScope.label(scope)}"
        view.findViewById<TextView>(R.id.tv_perm_desc).text =
            "${PermissionScope.description(scope)}\n\n请选择授权方式："

        // 危险权限：显示醒目警告
        val tvWarning = view.findViewById<TextView>(R.id.tv_perm_warning)
        val warning = PermissionScope.warning(scope)
        if (PermissionRegistry.isDangerous(scope) && warning.isNotEmpty()) {
            tvWarning.text = warning
            tvWarning.visibility = View.VISIBLE
        } else {
            tvWarning.visibility = View.GONE
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

        val dialog = Dialog(requireContext(), R.style.Theme_MiniApp_FloatingDialog)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)   // 强制选择
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window = dialog.window ?: return
        val dm = resources.displayMetrics
        val widthPx = (dm.widthPixels * WIDTH_RATIO).toInt()
        val capPx = (dm.heightPixels * MAX_HEIGHT_RATIO).toInt()

        // 宽度固定，高度先跟随内容
        window.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 兜底：实际高度仍超出上限时强制限高，由内部 ScrollView 接管滚动
        window.decorView.post {
            if (window.decorView.height > capPx) window.setLayout(widthPx, capPx)
        }
    }

    companion object {
        /** 对话框最大高度占屏幕高度的比例（留出状态栏/导航栏/边距余量）。 */
        private const val MAX_HEIGHT_RATIO = 0.75f

        /** 对话框宽度占屏幕宽度的比例。 */
        private const val WIDTH_RATIO = 0.92f

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
