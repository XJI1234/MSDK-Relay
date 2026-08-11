package com.skycommand.relay.app

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.settings.RelayConnectionSettingsResult
import com.skycommand.relay.settings.RelaySettings
import com.skycommand.relay.settings.android.AndroidRelaySettingsBackend
import com.skycommand.relay.settings.store.EndpointSaveResult
import com.skycommand.relay.settings.store.SettingsLoadResult

class MainActivity : ComponentActivity() {
    private lateinit var settings: RelaySettings
    private lateinit var endpointInput: EditText
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var graph: MobileRelayGraph? = null
    private var graphEndpoint: String? = null
    private var statusRegistration: CloseableRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = RelaySettings.create(AndroidRelaySettingsBackend.create(this))
        setContentView(buildContent())
        val loaded = settings.loadEndpoint()
        if (loaded is SettingsLoadResult.Available) {
            endpointInput.setText(loaded.snapshot.endpoint?.value.orEmpty())
        }
        renderMessage("已停止")
    }

    override fun onDestroy() {
        statusRegistration?.unregister()
        graph?.close()
        graph = null
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(0xFF17211F.toInt())
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "电脑端 WebSocket 地址"
            textSize = 14f
            setPadding(0, padding, 0, 8)
        }, matchWrap())
        endpointInput = EditText(this).apply {
            hint = "ws://192.168.1.10:8080/relay"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            minHeight = (52 * density).toInt()
            setSingleLine(true)
        }
        content.addView(endpointInput, matchWrap())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        startButton = Button(this).apply {
            text = "保存并启动"
            setOnClickListener { saveAndStart() }
        }
        stopButton = Button(this).apply {
            text = "停止"
            isEnabled = false
            setOnClickListener { stopRelay() }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(
            stopButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12 },
        )
        content.addView(actions, matchWrap())
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF24312E.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFE9EFED.toInt())
        }
        content.addView(statusView, matchWrap())
        return ScrollView(this).apply { addView(content) }
    }

    private fun saveAndStart() {
        when (settings.saveEndpoint(endpointInput.text.toString().trim())) {
            is EndpointSaveResult.Saved -> Unit
            is EndpointSaveResult.Rejected -> return renderMessage("WebSocket 地址无效")
            is EndpointSaveResult.Unavailable -> return renderMessage("设置存储不可用")
        }
        val connection = settings.connectionSettings()
        if (connection !is RelayConnectionSettingsResult.Available) {
            renderMessage("连接设置不可用")
            return
        }
        val endpoint = connection.settings.endpoint?.value
        if (endpoint == null) {
            renderMessage("连接设置不可用")
            return
        }
        if (graph == null || graphEndpoint != endpoint) {
            statusRegistration?.unregister()
            statusRegistration = null
            graph?.close()
            graph = null
            graphEndpoint = null
            graph = runCatching {
                MobileRelayGraph.create(this, endpoint, connection.settings.deviceId.value)
            }.getOrElse {
                renderMessage("手机端模块初始化失败")
                return
            }
            graphEndpoint = endpoint
            statusRegistration = graph?.onStatusChanged(::renderStatus)
        }
        runCatching { graph?.start() }.onFailure { renderMessage("启动请求失败") }
    }

    private fun stopRelay() {
        runCatching { graph?.stop() }.onFailure { renderMessage("停止请求失败") }
    }

    private fun renderStatus(status: MobileRelayStatus) {
        runOnUiThread {
            statusView.text = listOf(
                "运行时：" + status.runtime,
                "电脑连接：" + status.gateway,
                "DJI SDK：" + status.sdk,
                "飞行器：" + status.aircraft,
                "图传：" + status.stream,
                "航线：" + status.mission,
            ).joinToString("\n")
            val running = status.runtime != RuntimeState.STOPPED && status.runtime != RuntimeState.FAILED
            startButton.isEnabled = !running
            stopButton.isEnabled = running
        }
    }

    private fun renderMessage(message: String) {
        statusView.text = message
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
