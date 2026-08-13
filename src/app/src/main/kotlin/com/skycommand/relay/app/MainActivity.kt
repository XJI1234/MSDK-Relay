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
        renderMessage(R.string.message_stopped)
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
            text = getString(R.string.endpoint_label)
            textSize = 14f
            setPadding(0, padding, 0, 8)
        }, matchWrap())
        endpointInput = EditText(this).apply {
            hint = getString(R.string.endpoint_hint)
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
            text = getString(R.string.start_relay)
            setOnClickListener { saveAndStart() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.stop_relay)
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
            is EndpointSaveResult.Rejected -> return renderMessage(R.string.message_invalid_endpoint)
            is EndpointSaveResult.Unavailable -> return renderMessage(R.string.message_settings_unavailable)
        }
        val connection = settings.connectionSettings()
        if (connection !is RelayConnectionSettingsResult.Available) {
            renderMessage(R.string.message_settings_unavailable)
            return
        }
        val endpoint = connection.settings.endpoint?.value
        if (endpoint == null) {
            renderMessage(R.string.message_settings_unavailable)
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
                renderMessage(R.string.message_graph_initialization_failed)
                return
            }
            graphEndpoint = endpoint
            statusRegistration = graph?.onStatusChanged(::renderStatus)
        }
        runCatching { graph?.start() }.onFailure { renderMessage(R.string.message_start_failed) }
    }

    private fun stopRelay() {
        runCatching { graph?.stop() }.onFailure { renderMessage(R.string.message_stop_failed) }
    }

    private fun renderStatus(status: MobileRelayStatus) {
        runOnUiThread {
            statusView.text = listOf(
                getString(R.string.status_runtime, status.runtime),
                getString(R.string.status_gateway, status.gateway),
                getString(R.string.status_sdk, status.sdk),
                getString(R.string.status_aircraft, status.aircraft),
                getString(R.string.status_stream, status.stream),
                getString(R.string.status_mission, status.mission),
            ).joinToString("\n")
            val running = status.runtime != RuntimeState.STOPPED && status.runtime != RuntimeState.FAILED
            startButton.isEnabled = !running
            stopButton.isEnabled = running
        }
    }

    private fun renderMessage(message: String) {
        statusView.text = message
    }

    private fun renderMessage(messageResId: Int) {
        renderMessage(getString(messageResId))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
