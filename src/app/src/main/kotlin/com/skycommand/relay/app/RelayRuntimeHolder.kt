package com.skycommand.relay.app

import com.skycommand.relay.runtime.permission.android.AndroidPermissionAdapter

internal object RelayRuntimeHolder {
    data class Retained(
        val graph: MobileRelayGraph,
        val permissionAdapter: AndroidPermissionAdapter,
        val graphEndpoint: String?,
    )

    @Volatile
    private var retained: Retained? = null

    fun retain(graph: MobileRelayGraph, permissionAdapter: AndroidPermissionAdapter, graphEndpoint: String?) {
        retained = Retained(graph, permissionAdapter, graphEndpoint)
    }

    fun restore(): Retained? = retained

    fun clear() {
        retained = null
    }
}
