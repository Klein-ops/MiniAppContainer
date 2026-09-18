package com.miniapp.container.web

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val m = consoleMessage
        Log.d("MiniAppJS", "[${m?.sourceId()}:${m?.lineNumber()}] ${m?.message()}")
        return true
    }
}
