package com.miniapp.container.web

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miniapp.container.R
import com.miniapp.container.util.showRounded

class MiniAppWebChromeClient(
    private val onProgress: (Int) -> Unit = {},
    private val onTitle: (String) -> Unit = {},
    private val onPageFinished: (() -> Unit)? = null
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        onProgress(newProgress)
        if (newProgress >= 100) onPageFinished?.invoke()
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (!title.isNullOrBlank()) onTitle(title)
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        MaterialAlertDialogBuilder(view?.context ?: return false)
            .setMessage(message ?: "")
            .setOnCancelListener { result.cancel() }
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .showRounded()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        MaterialAlertDialogBuilder(view?.context ?: return false)
            .setMessage(message ?: "")
            .setOnCancelListener { result.cancel() }
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .showRounded()
        return true
    }

    /**
     * prompt() 输入框：默认 WebView 对话框不受主题控制（文字与背景对比低），
     * 改用 Material 对话框 + EditText，颜色走项目主题。
     */
    override fun onJsPrompt(
        view: WebView?, url: String?, message: String?,
        defaultValue: String?, result: JsPromptResult
    ): Boolean {
        val ctx = view?.context ?: return false
        val density = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            setText(defaultValue ?: "")
            setSingleLine()
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            val h = (20 * density).toInt()
            val v = (12 * density).toInt()
            setPadding(h, v, h, v)
        }
        MaterialAlertDialogBuilder(ctx)
            .setMessage(message ?: "")
            .setView(input)
            .setOnCancelListener { result.cancel() }
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .showRounded()
        return true
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val m = consoleMessage
        Log.d("MiniAppJS", "[${m?.sourceId()}:${m?.lineNumber()}] ${m?.message()}")
        return true
    }
}
