package com.skycommand.relay.stream.whip.config

import java.net.URI

data class ValidatedWhipStreamConfig(val whipUrl: String)

sealed interface WhipConfigValidationResult {
    data class Valid(val config: ValidatedWhipStreamConfig) : WhipConfigValidationResult

    data class Invalid(val reason: WhipConfigRejection) : WhipConfigValidationResult
}

enum class WhipConfigRejection {
    EMPTY,
    TOO_LONG,
    MALFORMED,
    INVALID_SCHEME,
    MISSING_HOST,
    INVALID_PORT,
    MISSING_PATH,
    USER_INFO_NOT_ALLOWED,
    QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    CONTROL_CHARACTER,
    LOOPBACK,
}

object WhipStreamConfigValidator {
    private const val MAX_URL_CODE_POINTS = 2048

    fun validate(whipUrl: String): WhipConfigValidationResult {
        if (whipUrl.isBlank()) return WhipConfigValidationResult.Invalid(WhipConfigRejection.EMPTY)
        if (whipUrl.codePointCount(0, whipUrl.length) > MAX_URL_CODE_POINTS) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.TOO_LONG)
        }
        if (whipUrl.any(Char::isISOControl)) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.CONTROL_CHARACTER)
        }

        val uri = try {
            URI(whipUrl)
        } catch (_: Exception) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.MALFORMED)
        }
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.INVALID_SCHEME)
        }
        if (uri.userInfo != null) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.USER_INFO_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank() && uri.rawAuthority.isNullOrBlank()) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.MISSING_HOST)
        }
        if (uri.host.isNullOrBlank()) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.MALFORMED)
        }
        if (uri.port !in -1..65535 || uri.port == 0) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.INVALID_PORT)
        }
        if (uri.rawAuthority?.endsWith(':') == true) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.MALFORMED)
        }
        if (uri.rawQuery != null) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.QUERY_NOT_ALLOWED)
        }
        if (uri.rawFragment != null) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.FRAGMENT_NOT_ALLOWED)
        }
        if (uri.rawPath.isNullOrBlank() || !uri.rawPath.startsWith('/') || !uri.rawPath.endsWith("/whip")) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.MISSING_PATH)
        }
        if (loopbackHost(uri.host)) {
            return WhipConfigValidationResult.Invalid(WhipConfigRejection.LOOPBACK)
        }
        return WhipConfigValidationResult.Valid(ValidatedWhipStreamConfig(whipUrl))
    }

    private fun loopbackHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        val parts = normalized.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { it.toIntOrNull() in 0..255 }
    }
}
