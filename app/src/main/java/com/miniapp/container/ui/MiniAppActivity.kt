package com.miniapp.container.ui

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.bridge.MiniAppBridge
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.file.FileService
import com.miniapp.container.sys.SystemInfoService
import com.miniapp.container.web.MiniAppWebChromeClient
import com.miniapp.container.web.MiniAppWebViewClient
import com.miniapp.container.wasm.WasmRuntimeManager
import java.io.File

/** WebView 容器：加载沙箱入口页面，注入 JS Bridge。 */
class MiniAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_KEY = "appKey"
    }

    private lateinit var appInfo: MiniAppInfo
    private lateinit var bridge: MiniAppBridge
    private lateinit var wasmManager: WasmRuntimeManager
    private lateinit var progress: ProgressBar
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appKey = intent.getStringExtra(EXTRA_APP_KEY)
        if (appKey == null) { finish(); return }
        val info = (application as MiniAppApp).registry.get(appKey)
        if (info == null) { finish(); return }
        appInfo = info

        setContentView(R.layout.activity_mini_app)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.title = "${appInfo.uid}/${appInfo.uname}"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)

        val hostApp = application as MiniAppApp
        val sandboxRoot = hostApp.sandbox.appDir(appInfo.appKey)
        val bridgeJs = assets.open("bridge.js").bufferedReader().use { it.readText() }

        configureWebView(webView)

        wasmManager = WasmRuntimeManager(sandboxRoot)
        val hostAppVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        } catch (t: Throwable) {
            "0.1.0"
        }
        bridge = MiniAppBridge(
            activity = this,
            appInfo = appInfo,
            sandboxRoot = sandboxRoot,
            fileService = FileService(sandboxRoot),
            wasmManager = wasmManager,
            permissionManager = hostApp.permissionManager,
            systemInfo = SystemInfoService(this),
            hostAppVersion = hostAppVersion
        )
        webView.addJavascriptInterface(bridge, "MiniAppNative")
        bridge.attach(webView)

        webView.webViewClient = MiniAppWebViewClient(sandboxRoot, bridgeJs)
        webView.webChromeClient = MiniAppWebChromeClient(
            onProgress = { p ->
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            },
            onTitle = { t -> supportActionBar?.title = t }
        )

        val entryFile = File(File(sandboxRoot, "app"), appInfo.entry)
        if (!entryFile.exists()) {
            AlertDialog.Builder(this)
                .setTitle("错误")
                .setMessage("入口文件不存在: ${appInfo.entry}")
                .setOnDismissListener { finish() }
                .show()
            return
        }
        webView.loadUrl(Uri.fromFile(entryFile).toString())
    }

    private fun configureWebView(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // 允许 file:// 主框架加载；跨沙箱隔离由 WebViewClient 拦截放行/拒绝。
        s.allowFileAccess = true
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.userAgentString = "${s.userAgentString} MiniAppContainer/0.1"
        w.isVerticalScrollBarEnabled = true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.mini_app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> { webView.reload(); true }
        R.id.action_permissions -> { showPermissions(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun showPermissions() {
        val pm = (application as MiniAppApp).permissionManager
        val declared = appInfo.permissions
        val granted = pm.grantedScopes(appInfo.appKey)
        val msg = buildString {
            append("声明的权限：\n")
            append(if (declared.isEmpty()) "（无）" else declared.joinToString(", "))
            append("\n\n已授权：\n")
            append(if (granted.isEmpty()) "（无）" else granted.joinToString(", "))
        }
        AlertDialog.Builder(this)
            .setTitle("权限")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    override fun onDestroy() {
        try { wasmManager.unloadAll() } catch (t: Throwable) { /* ignore */ }
        try {
            webView.stopLoading()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (t: Throwable) { /* ignore */ }
        super.onDestroy()
    }
}
