package com.skycommand.relay.gateway.session

import com.skycommand.relay.settings.endpoint.EndpointSettings
import com.skycommand.relay.settings.endpoint.EndpointValidationResult

/** One caller-owned connection intent. Reuse the object while its intent is unchanged. */
class ConnectionSelection private constructor(internal val endpoint: String) {
    override fun toString(): String = "ConnectionSelection(opaque)"

    companion object {
        fun create(endpoint: String): ConnectionSelectionResult = when (val result = EndpointSettings.validate(endpoint)) {
            is EndpointValidationResult.Valid -> ConnectionSelectionResult.Valid(ConnectionSelection(result.endpoint.value))
            is EndpointValidationResult.Invalid -> ConnectionSelectionResult.Invalid
        }
    }
}

sealed interface ConnectionSelectionResult {
    data class Valid(val selection: ConnectionSelection) : ConnectionSelectionResult
    data object Invalid : ConnectionSelectionResult
}

sealed interface SelectionResult {
    data object Changed : SelectionResult
    data object Unchanged : SelectionResult
}
