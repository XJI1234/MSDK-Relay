package com.skycommand.relay.settings.endpoint

import java.net.URI

/** An endpoint which has passed the relay connection safety checks. */
data class ValidatedRelayEndpoint(
    val value: String,
)

sealed interface EndpointValidationResult {
    data class Valid(val endpoint: ValidatedRelayEndpoint) : EndpointValidationResult

    data class Invalid(val reason: EndpointRejection) : EndpointValidationResult
}

enum class EndpointRejection {
    EMPTY,
    TOO_LONG,
    MALFORMED,
    INVALID_SCHEME,
    MISSING_HOST,
    INVALID_PORT,
    USER_INFO_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    CONTROL_CHARACTER,
}

object EndpointSettings {
    private const val MAX_ENDPOINT_CODE_POINTS = 2048

    fun validate(value: String): EndpointValidationResult {
        if (value.isBlank()) return EndpointValidationResult.Invalid(EndpointRejection.EMPTY)
        if (value.codePointCount(0, value.length) > MAX_ENDPOINT_CODE_POINTS) {
            return EndpointValidationResult.Invalid(EndpointRejection.TOO_LONG)
        }
        if (value.any(Char::isISOControl)) {
            return EndpointValidationResult.Invalid(EndpointRejection.CONTROL_CHARACTER)
        }

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return EndpointValidationResult.Invalid(EndpointRejection.MALFORMED)
        }
        if (!uri.scheme.equals("ws", ignoreCase = true) && !uri.scheme.equals("wss", ignoreCase = true)) {
            return EndpointValidationResult.Invalid(EndpointRejection.INVALID_SCHEME)
        }
        if (uri.userInfo != null) {
            return EndpointValidationResult.Invalid(EndpointRejection.USER_INFO_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank() && uri.rawAuthority.isNullOrBlank()) {
            return EndpointValidationResult.Invalid(EndpointRejection.MISSING_HOST)
        }
        if (uri.host.isNullOrBlank()) {
            return EndpointValidationResult.Invalid(EndpointRejection.MALFORMED)
        }
        if (uri.port !in -1..65535 || uri.port == 0) {
            return EndpointValidationResult.Invalid(EndpointRejection.INVALID_PORT)
        }
        if (uri.fragment != null) {
            return EndpointValidationResult.Invalid(EndpointRejection.FRAGMENT_NOT_ALLOWED)
        }

        return EndpointValidationResult.Valid(ValidatedRelayEndpoint(value))
    }
}
