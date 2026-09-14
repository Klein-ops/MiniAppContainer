package com.miniapp.container.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.miniapp.container.MiniAppApp
import com.miniapp.container.R
import com.miniapp.container.bridge.MiniAppBridge
import com.miniapp.container.core.MiniAppInfo
import com.miniapp.container.file.FileService
import com.miniapp.container.sys.SystemInfoService
import com.miniapp.container.web.FloatingExitView
import com.miniapp.container.web.MiniAppWebChromeClient
import com.miniapp.container.web.MiniAppWebViewClient
import java.io.File

/** WebView 容器：全屏渲染沙箱入口页面，注入 JS Bridge，悬浮按钮退出。 */
class MiniAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_KEY = "appKey"
        private var importCallback: ((Uri?) -> Unit)? = null
        private var exportCallback: ((Uri?) -> Unit)? = null
        // permCallback 不能放 companion（launcher 实例相关），放实例字段
    }

    private lateinit var appInfo: MiniAppInfo
    private lateinit var bridge: MiniAppBridge
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var floatingExit: FloatingExitView

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importCallback?.invoke(uri)
        importCallback = null
    }
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        exportCallback?.invoke(uri)
        exportCallback = null
    }

    /** 启动文件选择器导入（SAF，无需权限）。 */
    fun launchImport(cb: (Uri?) -> Unit) {
        importCallback = cb
        importLauncher.launch(arrayOf("*/*"))
    }

    /** 启动文件保存器导出（SAF，无需权限）。 */
    fun launchExport(defaultName: String, cb: (Uri?) -> Unit) {
        exportCallback = cb
        exportLauncher.launch(defaultName)
    }

    private var permCallback: ((Map<String, Boolean>) -> Unit)? = null
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> permCallback?.invoke(result); permCallback = null }

    /** 主动申请运行时存储权限（Android 10 及以下弹窗）。 */
    fun requestRuntimePerms(permissions: Array<String>, cb: (Map<String, Boolean>) -> Unit) {
        permCallback = cb
        permLauncher.launch(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appKey = intent.getStringExtra(EXTRA_APP_KEY)
        if (appKey == null) { finish(); return }
        val info = (application as MiniAppApp).registry.get(appKey)
        if (info == null) { finish(); return }
        appInfo = info

        setContentView(R.layout.activity_mini_app)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)
        floatingExit = findViewById(R.id.floating_exit)
        floatingExit.onExit = { finish() }
        // 打开时不自动收起，仅在用户拖动贴边后才进入闲置收起

        val hostApp = application as MiniAppApp
        val sandboxRoot = hostApp.sandbox.appDir(appInfo.appKey)
        val bridgeJs = assets.open("bridge.js").bufferedReader().use { it.readText() }

        configureWebView(webView)

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
            onTitle = { }
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

    private var lastBackPressedAt = 0L

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 有历史页则向前推一页
        if (webView.canGoBack()) { webView.goBack(); return }
        // 已到入口页：二次确认才退出
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt < 2000) { super.onBackPressed(); return }
        lastBackPressedAt = now
        Toast.makeText(this, "再按一次返回才会退出", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.removeAllViews()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (t: Throwable) { /* ignore */ }
        super.onDestroy()
    }
}
