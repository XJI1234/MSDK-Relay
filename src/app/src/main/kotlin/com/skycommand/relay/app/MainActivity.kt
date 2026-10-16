package com.skycommand.relay.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.skycommand.relay.discovery.DiscoveryPhase
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.discovery.RelayConnectionMode
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionState
import com.skycommand.relay.settings.RelaySettings
import com.skycommand.relay.settings.android.AndroidRelaySettingsBackend
import com.skycommand.relay.settings.identity.DeviceIdentityResult
import com.skycommand.relay.settings.store.EndpointSaveResult
import com.skycommand.relay.settings.store.SettingsLoadResult

class MainActivity : ComponentActivity() {
    private lateinit var settings: RelaySettings
    private lateinit var endpointInput: EditText
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var retryButton: Button
    private lateinit var permissionSettingsButton: Button
    private var entry: RelayProcessRuntime.Entry? = null
    private var graph: MobileRelayGraph? = null
    private var statusRegistration: CloseableRegistration? = null
    private val statusDispatcher = LatestStatusDispatcher<MobileRelayStatus>(
        schedule = { work -> runOnUiThread(work) }, render = ::renderStatus,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        settings = RelaySettings.create(AndroidRelaySettingsBackend.create(applicationContext))
        setContentView(buildContent())
        val loaded = settings.loadEndpoint()
        if (loaded is SettingsLoadResult.Available) endpointInput.setText(loaded.snapshot.endpoint?.value.orEmpty())
        paintStatus()
    }

    override fun onResume() {
        super.onResume()
        // System permission requests require an already STARTED visible lifecycle.
        startWhenVisible(explicit = false)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }

    override fun onDestroy() {
        statusRegistration?.unregister()
        statusRegistration = null
        statusDispatcher.close()
        runCatching { entry?.detach(this) }
        entry = null
        graph = null
        super.onDestroy()
    }

    private fun attachProcess(): Boolean {
        if (entry != null && graph?.isClosing() == false) return true
        val identity = settings.deviceIdentity()
        if (identity !is DeviceIdentityResult.Available) {
            showMessage(R.string.message_settings_unavailable)
            return false
        }
        val attached = runCatching { RelayProcessRuntime.attach(this, identity.deviceId.value) }.getOrElse { error ->
            Log.e(TAG, "Relay process initialization failed", error)
            showMessage(R.string.message_graph_initialization_failed)
            return false
        }
        statusRegistration?.unregister()
        entry = attached
        graph = attached.graph
        statusRegistration = attached.graph.onStatusChanged(::paintStatus)
        return true
    }

    private fun startWhenVisible(explicit: Boolean) {
        if (!attachProcess()) return
        val current = entry ?: return
        if (current.graph.isClosing()) { showMessage(R.string.message_stopping); return }
        val state = current.graph.status()
        val permission = current.permissions.snapshot().stateOf(PermissionKind.RUNTIME)
        runCatching {
            if (state.runtime == RuntimeState.RUNNING && state.connection?.failed == true && explicit) {
                check(current.graph.retryConnection())
            } else if (current.startPolicy.shouldStart(state.runtime, permission, explicit)) {
                current.graph.start()
            }
        }.onSuccess { clearMessage() }.onFailure {
            Log.e(TAG, "Relay start failed", it)
            showMessage(R.string.message_start_failed)
        }
        paintStatus()
    }

    private fun useManualAddress() {
        val endpoint = endpointInput.text.toString().trim()
        when (settings.saveEndpoint(endpoint)) {
            is EndpointSaveResult.Saved -> Unit
            is EndpointSaveResult.Rejected -> return showMessage(R.string.message_invalid_endpoint)
            is EndpointSaveResult.Unavailable -> return showMessage(R.string.message_settings_unavailable)
        }
        if (!attachProcess()) return
        if (graph?.manualConnection(endpoint) != true) { showMessage(R.string.message_connection_control_failed); return }
        startWhenVisible(explicit = true)
    }

    private fun useAutomaticDiscovery() {
        if (!attachProcess()) return
        if (graph?.automaticConnection() != true) { showMessage(R.string.message_connection_control_failed); return }
        startWhenVisible(explicit = true)
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding * 4)
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(0xFF17211F.toInt())
        }
        page.addView(title, matchWrap())
        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF24312E.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFE9EFED.toInt())
        }
        page.addView(statusView, matchWrap())
        messageView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8A3A32.toInt())
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        page.addView(messageView, matchWrap())
        retryButton = Button(this).apply {
            text = getString(R.string.retry_relay)
            setOnClickListener { startWhenVisible(explicit = true) }
        }
        page.addView(retryButton, matchWrap())
        permissionSettingsButton = Button(this).apply {
            text = getString(R.string.permission_settings)
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        }
        page.addView(permissionSettingsButton, matchWrap())
        val debugPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        title.setOnLongClickListener {
            debugPanel.visibility = if (debugPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            true
        }
        debugPanel.addView(TextView(this).apply {
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
        debugPanel.addView(endpointInput, matchWrap())
        debugPanel.addView(Button(this).apply {
            text = getString(R.string.manual_connection)
            setOnClickListener { useManualAddress() }
        }, matchWrap())
        debugPanel.addView(Button(this).apply {
            text = getString(R.string.automatic_connection)
            setOnClickListener { useAutomaticDiscovery() }
        }, matchWrap())
        page.addView(debugPanel, matchWrap())
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page, matchWrap())
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom + ime.bottom)
            if (endpointInput.isFocused) scroll.post { scroll.smoothScrollTo(0, endpointInput.top) }
            insets
        }
        endpointInput.setOnFocusChangeListener { view, focused -> if (focused) scroll.post { scroll.smoothScrollTo(0, view.top) } }
        return scroll
    }

    private fun paintStatus(status: MobileRelayStatus = currentStatus()) { statusDispatcher.offer(status) }

    private fun discoveryLabel(status: MobileRelayStatus): String {
        val label = when {
            status.runtime == RuntimeState.WAITING_PERMISSIONS -> R.string.discovery_permissions
            status.runtime == RuntimeState.FAILED -> R.string.discovery_runtime_failed
            status.runtime == RuntimeState.STOPPING -> R.string.message_stopping
            status.runtime != RuntimeState.RUNNING -> R.string.discovery_preparing
            status.connection?.failed == true -> R.string.discovery_source_failed
            status.gateway == SessionState.ACTIVE -> R.string.discovery_connected
            status.connection?.discovery?.phase == DiscoveryPhase.CONFLICT -> R.string.discovery_conflict
            status.gateway in setOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING, SessionState.RECONNECT_WAIT) -> R.string.discovery_connecting
            else -> R.string.discovery_searching
        }
        val text = getString(label)
        return if (status.connection?.mode == RelayConnectionMode.MANUAL) getString(R.string.discovery_manual, text) else text
    }

    private fun renderStatus(status: MobileRelayStatus) {
        statusView.text = listOf(
            getString(R.string.status_runtime, StatusLabels.runtime(status.runtime)),
            getString(R.string.status_gateway, discoveryLabel(status)),
            getString(R.string.status_msdk, StatusLabels.sdk(status.sdk)),
            getString(R.string.status_remote_controller, StatusLabels.link(status.remoteController)),
            getString(R.string.status_pairing, StatusLabels.pairing(status.pairing)),
            getString(R.string.status_flight_controller, StatusLabels.link(status.flightController)),
            getString(R.string.status_battery, StatusLabels.link(status.battery)),
            getString(R.string.status_stream, status.stream),
            getString(R.string.status_mission, status.mission),
        ).joinToString("\n")
        retryButton.visibility = if (status.runtime in setOf(RuntimeState.STOPPED, RuntimeState.FAILED) || status.connection?.failed == true) View.VISIBLE else View.GONE
        val permanentlyDenied = runCatching { entry?.permissions?.snapshot()?.stateOf(PermissionKind.RUNTIME) == PermissionState.PERMANENTLY_DENIED }.getOrDefault(false)
        permissionSettingsButton.visibility = if (status.runtime == RuntimeState.FAILED && permanentlyDenied) View.VISIBLE else View.GONE
    }

    private fun currentStatus(): MobileRelayStatus = graph?.let { runCatching(it::status).getOrNull() } ?: MobileRelayStatus(
        runtime = RuntimeState.STOPPED, gateway = SessionState.STOPPED, sdk = "STOPPED", remoteController = "UNKNOWN",
        pairing = "UNKNOWN", flightController = "UNKNOWN", aircraft = "UNKNOWN", battery = "UNKNOWN", stream = "IDLE",
        mission = "-", canStartPairing = false, canStopPairing = false,
    )
    private fun showMessage(message: Int) { messageView.text = getString(message); messageView.visibility = View.VISIBLE; paintStatus() }
    private fun clearMessage() { messageView.text = ""; messageView.visibility = View.GONE }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private companion object { const val TAG = "MSDKRelay" }
}
