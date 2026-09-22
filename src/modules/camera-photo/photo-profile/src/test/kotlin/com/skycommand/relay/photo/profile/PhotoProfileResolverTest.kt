package com.skycommand.relay.photo.profile

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test
import java.util.stream.Stream

class PhotoProfileResolverTest {
    @Test
    fun preservesSupportedRequestedValuesInStableOrder() {
        val profile = PhotoProfileResolver.resolve(PhotoCapabilities.allSupported())

        assertEquals(
            listOf(
                PhotoSettingRequest.RATIO_4_3,
                PhotoSettingRequest.RESOLUTION_48_MP,
                PhotoSettingRequest.SIZE_EXTRA_LARGE,
                PhotoSettingRequest.QUALITY_SUPER_FINE,
                PhotoSettingRequest.FILE_FORMAT_JPEG,
            ),
            profile.writes,
        )
    }

    @ParameterizedTest
    @MethodSource("unsupportedCapabilities")
    fun omitsEachIndividuallyUnsupportedCapability(
        capabilities: PhotoCapabilities,
        omittedRequest: PhotoSettingRequest,
    ) {
        val profile = PhotoProfileResolver.resolve(capabilities)

        assertFalse(profile.writes.any { it.key == omittedRequest.key })
    }

    @Test
    fun skipsUnknownCapabilities() {
        val profile = PhotoProfileResolver.resolve(PhotoCapabilities())

        assertEquals(emptyList(), profile.writes)
    }

    companion object {
        @JvmStatic
        fun unsupportedCapabilities(): Stream<Arguments> = Stream.of(
            Arguments.of(
                PhotoCapabilities(ratio = PhotoSettingSupport.UNSUPPORTED),
                PhotoSettingRequest.RATIO_4_3,
            ),
            Arguments.of(
                PhotoCapabilities(resolution = PhotoSettingSupport.UNSUPPORTED),
                PhotoSettingRequest.RESOLUTION_48_MP,
            ),
            Arguments.of(
                PhotoCapabilities(photoSize = PhotoSettingSupport.UNSUPPORTED),
                PhotoSettingRequest.SIZE_EXTRA_LARGE,
            ),
            Arguments.of(
                PhotoCapabilities(quality = PhotoSettingSupport.UNSUPPORTED),
                PhotoSettingRequest.QUALITY_SUPER_FINE,
            ),
            Arguments.of(
                PhotoCapabilities(fileFormat = PhotoSettingSupport.UNSUPPORTED),
                PhotoSettingRequest.FILE_FORMAT_JPEG,
            ),
        )
    }
}
