package com.skycommand.relay.app

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.skycommand.relay.device.pairing.PairingRequestResult
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.settings.RelayConnectionSettingsResult
import com.skycommand.relay.settings.RelaySettings
import com.skycommand.relay.settings.android.AndroidRelaySettingsBackend
import com.skycommand.relay.runtime.permission.android.AndroidPermissionAdapter
import com.skycommand.relay.settings.store.EndpointSaveResult
import com.skycommand.relay.settings.store.SettingsLoadResult

class MainActivity : ComponentActivity() {
    private lateinit var settings: RelaySettings
    private lateinit var permissionAdapter: AndroidPermissionAdapter
    private lateinit var endpointInput: EditText
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var startPairingButton: Button
    private lateinit var stopPairingButton: Button
    private var graph: MobileRelayGraph? = null
    private var graphEndpoint: String? = null
    private var statusRegistration: CloseableRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = RelaySettings.create(AndroidRelaySettingsBackend.create(this))
        val restored = RelayRuntimeHolder.restore()
        if (restored != null) {
            permissionAdapter = restored.permissionAdapter
            permissionAdapter.rebind(this, activityResultRegistry, this)
            graph = restored.graph
            graphEndpoint = restored.graphEndpoint
        } else {
            permissionAdapter = AndroidPermissionAdapter.attach(this, activityResultRegistry, this)
        }
        setContentView(buildContent())
        val loaded = settings.loadEndpoint()
        if (loaded is SettingsLoadResult.Available) {
            endpointInput.setText(loaded.snapshot.endpoint?.value.orEmpty())
        }
        if (graph != null) {
            statusRegistration = graph?.onStatusChanged(::paintStatus)
            clearMessage()
        } else {
            showMessage(R.string.message_stopped)
        }
        paintStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        statusRegistration?.unregister()
        statusRegistration = null
        val runtime = runCatching { graph?.status()?.runtime }.getOrNull()
        val keep = graph != null && runtime != null && RelaySurfaceRetention.shouldRetain(runtime)
        if (keep) {
            RelayRuntimeHolder.retain(graph!!, permissionAdapter, graphEndpoint)
        } else {
            graph?.close()
            graph = null
            graphEndpoint = null
            permissionAdapter.close()
            RelayRuntimeHolder.clear()
        }
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(0xFF17211F.toInt())
        }, matchWrap())
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF24312E.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFE9EFED.toInt())
        }
        root.addView(statusView, matchWrap())
        messageView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8A3A32.toInt())
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        root.addView(messageView, matchWrap())
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
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
        content.addView(TextView(this).apply {
            text = getString(R.string.pairing_hint)
            textSize = 13f
            setTextColor(0xFF5B6B67.toInt())
            setPadding(0, 8, 0, 8)
        }, matchWrap())
        val pairingActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        startPairingButton = Button(this).apply {
            text = getString(R.string.start_pairing)
            isEnabled = false
            setOnClickListener { startPairing() }
        }
        stopPairingButton = Button(this).apply {
            text = getString(R.string.stop_pairing)
            isEnabled = false
            setOnClickListener { stopPairing() }
        }
        pairingActions.addView(startPairingButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pairingActions.addView(
            stopPairingButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12 },
        )
        content.addView(pairingActions, matchWrap())
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun saveAndStart() {
        when (settings.saveEndpoint(endpointInput.text.toString().trim())) {
            is EndpointSaveResult.Saved -> Unit
            is EndpointSaveResult.Rejected -> return showMessage(R.string.message_invalid_endpoint)
            is EndpointSaveResult.Unavailable -> return showMessage(R.string.message_settings_unavailable)
        }
        val connection = settings.connectionSettings()
        if (connection !is RelayConnectionSettingsResult.Available) {
            showMessage(R.string.message_settings_unavailable)
            return
        }
        val endpoint = connection.settings.endpoint?.value
        if (endpoint == null) {
            showMessage(R.string.message_settings_unavailable)
            return
        }
        if (graph == null || graphEndpoint != endpoint) {
            statusRegistration?.unregister()
            statusRegistration = null
            graph?.close()
            graph = null
            graphEndpoint = null
            RelayRuntimeHolder.clear()
            graph = runCatching {
                MobileRelayGraph.create(this, endpoint, connection.settings.deviceId.value, permissionAdapter)
            }.getOrElse { error ->
                Log.e(TAG, "MobileRelayGraph.create failed", error)
                showMessage(R.string.message_graph_initialization_failed)
                return
            }
            graphEndpoint = endpoint
            statusRegistration = graph?.onStatusChanged(::paintStatus)
        }
        runCatching { graph?.start() }
            .onSuccess { clearMessage() }
            .onFailure { error ->
                Log.e(TAG, "MobileRelayGraph.start failed", error)
                showMessage(R.string.message_start_failed)
            }
        paintStatus()
    }

    private fun stopRelay() {
        runCatching { graph?.stop() }
            .onSuccess { showMessage(R.string.message_stopped) }
            .onFailure { showMessage(R.string.message_stop_failed) }
        paintStatus()
    }

    private fun startPairing() {
        val current = graph ?: return showMessage(R.string.message_stopped)
        when (current.startPairing()) {
            is PairingRequestResult.Accepted -> clearMessage()
            is PairingRequestResult.Rejected -> showMessage(R.string.message_pairing_rejected)
        }
        paintStatus()
    }

    private fun stopPairing() {
        val current = graph ?: return showMessage(R.string.message_stopped)
        when (current.stopPairing()) {
            is PairingRequestResult.Accepted -> clearMessage()
            is PairingRequestResult.Rejected -> showMessage(R.string.message_pairing_stop_rejected)
        }
        paintStatus()
    }

    private fun paintStatus(status: MobileRelayStatus = currentStatus()) {
        val paint = {
            statusView.visibility = View.VISIBLE
            statusView.text = listOf(
                getString(R.string.status_runtime, StatusLabels.runtime(status.runtime)),
                getString(R.string.status_gateway, StatusLabels.gateway(status.gateway)),
                getString(R.string.status_msdk, StatusLabels.sdk(status.sdk)),
                getString(R.string.status_remote_controller, StatusLabels.link(status.remoteController)),
                getString(R.string.status_pairing, StatusLabels.pairing(status.pairing)),
                getString(R.string.status_flight_controller, StatusLabels.link(status.flightController)),
                getString(R.string.status_aircraft, StatusLabels.link(status.aircraft)),
                getString(R.string.status_stream, status.stream),
                getString(R.string.status_mission, status.mission),
            ).joinToString("\n")
            val running = status.runtime == RuntimeState.RUNNING
            val starting = status.runtime == RuntimeState.WAITING_PERMISSIONS ||
                status.runtime == RuntimeState.STARTING_SERVICE ||
                status.runtime == RuntimeState.STARTING_MODULES
            startButton.isEnabled = status.runtime == RuntimeState.STOPPED || status.runtime == RuntimeState.FAILED
            stopButton.isEnabled = running || starting
            startPairingButton.isEnabled = status.canStartPairing
            stopPairingButton.isEnabled = status.canStopPairing
        }
        if (Looper.myLooper() == Looper.getMainLooper()) paint() else runOnUiThread(paint)
    }

    private fun currentStatus(): MobileRelayStatus =
        graph?.let { runCatching(it::status).getOrNull() } ?: idleStatus()

    private fun idleStatus(): MobileRelayStatus = MobileRelayStatus(
        runtime = RuntimeState.STOPPED,
        gateway = SessionState.STOPPED,
        sdk = "STOPPED",
        remoteController = "UNKNOWN",
        pairing = "UNKNOWN",
        flightController = "UNKNOWN",
        aircraft = "UNKNOWN",
        stream = "IDLE",
        mission = "-",
        canStartPairing = false,
        canStopPairing = false,
    )

    private fun showMessage(messageResId: Int) {
        messageView.text = getString(messageResId)
        messageView.visibility = View.VISIBLE
        paintStatus()
    }

    private fun clearMessage() {
        messageView.text = ""
        messageView.visibility = View.GONE
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val TAG = "MSDKRelay"
    }
}
