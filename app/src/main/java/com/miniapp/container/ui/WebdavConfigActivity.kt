package com.miniapp.container.ui
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.miniapp.container.R
import com.miniapp.container.util.setupBackToolbar
import com.miniapp.container.netdisk.WebdavClient
import com.miniapp.container.netdisk.WebdavConfig
import com.miniapp.container.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 网络存储：WebDAV 服务器配置（备份与小程序「网络存储」接口共用）。 */
class WebdavConfigActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvPaths: TextView
    private lateinit var config: WebdavConfig
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进入页面不自动弹出键盘，用户点击输入框时才弹出
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_webdav_config)
        setupBackToolbar()

        config = WebdavConfig(this)
        etUrl = findViewById<EditText>(R.id.et_url).also { it.setText(config.url) }
        etUser = findViewById<EditText>(R.id.et_user).also { it.setText(config.user) }
        etPass = findViewById<EditText>(R.id.et_pass).also { it.setText(config.pass) }
        tvStatus = findViewById(R.id.tv_status)
        tvPaths = findViewById(R.id.tv_paths)
        setupCompressionLevel()
        renderPaths()
        renderStatus()

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            save()
            toast("已保存")
            renderStatus()
        }
        findViewById<MaterialButton>(R.id.btn_test).setOnClickListener {
            save()
            testConnection()
        }
    }

    /** 压缩等级滑块：0-9，保存到配置。 */
    private fun setupCompressionLevel() {
        val slider = findViewById<Slider>(R.id.seek_level)
        val tv = findViewById<TextView>(R.id.tv_level)
        slider.value = config.compressionLevel.toFloat()
        tv.text = config.compressionLevel.toString()
        slider.addOnChangeListener { _, value, fromUser ->
            tv.text = value.toInt().toString()
            if (fromUser) config.compressionLevel = value.toInt()
        }
    }

    private fun save() {
        config.url = etUrl.text.toString()
        config.user = etUser.text.toString()
        config.pass = etPass.text.toString()
    }

    private fun renderStatus() {
        tvStatus.text = if (config.configured) "状态：已配置（${config.url}）" else "状态：未配置"
    }

    private fun renderPaths() {
        tvPaths.text = buildString {
            appendLine("根目录：/${WebdavConfig.ROOT_FOLDER}")
            appendLine("备份：/${WebdavConfig.ROOT_FOLDER}/backup/")
            appendLine("小程序数据：/${WebdavConfig.ROOT_FOLDER}/data/<uid>_<uname>/")
        }.trim()
    }

    /** 测试连接：确保根目录存在（写权限）并列出内容（读权限），失败时显示具体原因。 */
    private fun testConnection() {
        if (!config.configured) {
            toast("请先填写服务器地址")
            return
        }
        tvStatus.text = "状态：测试中…"
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                WebdavClient(config).testConnection(WebdavConfig.ROOT_FOLDER)
            }
            tvStatus.text = if (err == null) {
                "状态：连接成功（读写正常）"
            } else {
                "状态：连接失败\n原因：$err"
            }
        }
    }
}
