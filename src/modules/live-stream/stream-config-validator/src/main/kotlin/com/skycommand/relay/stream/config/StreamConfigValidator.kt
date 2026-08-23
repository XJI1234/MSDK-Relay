package com.skycommand.relay.stream.config

import java.net.URI

data class ValidatedStreamConfig(
    val rtmpUrl: String,
)

sealed interface StreamValidationResult {
    data class Valid(val config: ValidatedStreamConfig) : StreamValidationResult

    data class Invalid(val reason: StreamConfigRejection) : StreamValidationResult
}

enum class StreamConfigRejection {
    EMPTY,
    TOO_LONG,
    MALFORMED,
    INVALID_SCHEME,
    MISSING_HOST,
    INVALID_PORT,
    MISSING_PATH,
    USER_INFO_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    CONTROL_CHARACTER,
    LOOPBACK,
}

object StreamConfigValidator {
    private const val MAX_URL_CODE_POINTS = 2048

    fun validate(rtmpUrl: String): StreamValidationResult {
        if (rtmpUrl.isBlank()) return StreamValidationResult.Invalid(StreamConfigRejection.EMPTY)
        if (rtmpUrl.codePointCount(0, rtmpUrl.length) > MAX_URL_CODE_POINTS) {
            return StreamValidationResult.Invalid(StreamConfigRejection.TOO_LONG)
        }
        if (rtmpUrl.any(Char::isISOControl)) {
            return StreamValidationResult.Invalid(StreamConfigRejection.CONTROL_CHARACTER)
        }

        val uri = try {
            URI(rtmpUrl)
        } catch (_: Exception) {
            return StreamValidationResult.Invalid(StreamConfigRejection.MALFORMED)
        }
        if (!uri.scheme.equals("rtmp", ignoreCase = true)) {
            return StreamValidationResult.Invalid(StreamConfigRejection.INVALID_SCHEME)
        }
        if (uri.userInfo != null) {
            return StreamValidationResult.Invalid(StreamConfigRejection.USER_INFO_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank() && uri.rawAuthority.isNullOrBlank()) {
            return StreamValidationResult.Invalid(StreamConfigRejection.MISSING_HOST)
        }
        if (uri.host.isNullOrBlank()) {
            return StreamValidationResult.Invalid(StreamConfigRejection.MALFORMED)
        }
        if (uri.port !in -1..65535 || uri.port == 0) {
            return StreamValidationResult.Invalid(StreamConfigRejection.INVALID_PORT)
        }
        if (uri.rawPath.isNullOrBlank() || !uri.rawPath.startsWith("/")) {
            return StreamValidationResult.Invalid(StreamConfigRejection.MISSING_PATH)
        }
        if (uri.fragment != null) {
            return StreamValidationResult.Invalid(StreamConfigRejection.FRAGMENT_NOT_ALLOWED)
        }
        if (uri.path.orEmpty().any(Char::isISOControl)) {
            return StreamValidationResult.Invalid(StreamConfigRejection.CONTROL_CHARACTER)
        }
        if (loopbackHost(uri.host)) {
            return StreamValidationResult.Invalid(StreamConfigRejection.LOOPBACK)
        }
        return StreamValidationResult.Valid(ValidatedStreamConfig(rtmpUrl))
    }

    private fun loopbackHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        val parts = normalized.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { it.toIntOrNull() in 0..255 }
    }
}
