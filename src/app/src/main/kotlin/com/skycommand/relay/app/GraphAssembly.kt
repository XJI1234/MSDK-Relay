package com.skycommand.relay.app

/** Construction-time ownership only; successful assembly transfers it to the graph. */
internal class GraphAssembly {
    private val cleanup = mutableListOf<() -> Unit>()
    fun add(action: () -> Unit) { cleanup += action }
    fun commit() { cleanup.clear() }
    fun rollback(original: Throwable) {
        val actions = cleanup.asReversed().toList()
        cleanup.clear()
        actions.forEach { action ->
            try { action() }
            catch (failure: Throwable) { if (failure !== original) original.addSuppressed(failure) }
        }
    }
}
