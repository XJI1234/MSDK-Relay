package com.skycommand.relay.app

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.permission.android.AndroidPermissionAdapter

/** The process owns graph and permissions; activities own only subscriptions/hosts. */
internal object RelayProcessRuntime {
    class Entry(val graph: MobileRelayGraph, val permissions: AndroidPermissionAdapter) {
        val startPolicy = VisibleRelayStartPolicy()
        fun detach(activity: ComponentActivity) {
            val waiting = graph.status().runtime == RuntimeState.WAITING_PERMISSIONS
            permissions.detach(activity)
            if (waiting && graph.status().runtime != RuntimeState.WAITING_PERMISSIONS) startPolicy.interruptedPermissionRequest()
        }
    }
    private val owner = ProcessRelayOwner<Entry>()
    private val main = Handler(Looper.getMainLooper())

    fun attach(activity: ComponentActivity, deviceId: String): Entry {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Relay UI attachment requires main thread" }
        val entry = owner.acquire {
            val permissions = AndroidPermissionAdapter.attach(activity, activity.activityResultRegistry, activity)
            try {
                lateinit var created: Entry
                val graph = MobileRelayGraph.create(activity.applicationContext, null, deviceId, permissions, onReleased = {
                    main.post {
                        try { created.permissions.close() }
                        finally { owner.release(created) }
                    }
                })
                Entry(graph, permissions).also { created = it }
            } catch (failure: Throwable) {
                runCatching { permissions.close() }
                throw failure
            }
        }
        val waiting = entry.graph.status().runtime == RuntimeState.WAITING_PERMISSIONS
        entry.permissions.rebind(activity, activity.activityResultRegistry, activity)
        if (waiting && entry.graph.status().runtime != RuntimeState.WAITING_PERMISSIONS) entry.startPolicy.interruptedPermissionRequest()
        return entry
    }
}
