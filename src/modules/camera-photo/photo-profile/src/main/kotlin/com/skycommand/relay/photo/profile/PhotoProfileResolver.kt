package com.skycommand.relay.photo.profile

enum class PhotoSettingSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class PhotoSettingKey {
    RATIO,
    RESOLUTION,
    SIZE,
    QUALITY,
    FILE_FORMAT,
}

enum class PhotoSettingRequest(val key: PhotoSettingKey) {
    RATIO_4_3(PhotoSettingKey.RATIO),
    RESOLUTION_48_MP(PhotoSettingKey.RESOLUTION),
    SIZE_EXTRA_LARGE(PhotoSettingKey.SIZE),
    QUALITY_SUPER_FINE(PhotoSettingKey.QUALITY),
    FILE_FORMAT_JPEG(PhotoSettingKey.FILE_FORMAT),
}

data class PhotoCapabilities(
    val ratio: PhotoSettingSupport = PhotoSettingSupport.UNKNOWN,
    val resolution: PhotoSettingSupport = PhotoSettingSupport.UNKNOWN,
    val photoSize: PhotoSettingSupport = PhotoSettingSupport.UNKNOWN,
    val quality: PhotoSettingSupport = PhotoSettingSupport.UNKNOWN,
    val fileFormat: PhotoSettingSupport = PhotoSettingSupport.UNKNOWN,
) {
    companion object {
        fun allSupported() = PhotoCapabilities(
            ratio = PhotoSettingSupport.SUPPORTED,
            resolution = PhotoSettingSupport.SUPPORTED,
            photoSize = PhotoSettingSupport.SUPPORTED,
            quality = PhotoSettingSupport.SUPPORTED,
            fileFormat = PhotoSettingSupport.SUPPORTED,
        )
    }
}

data class PhotoProfile(val writes: List<PhotoSettingRequest>)

object PhotoProfileResolver {
    fun resolve(capabilities: PhotoCapabilities): PhotoProfile = PhotoProfile(
        listOfNotNull(
            capabilities.ratio.takeIfSupported(PhotoSettingRequest.RATIO_4_3),
            capabilities.resolution.takeIfSupported(PhotoSettingRequest.RESOLUTION_48_MP),
            capabilities.photoSize.takeIfSupported(PhotoSettingRequest.SIZE_EXTRA_LARGE),
            capabilities.quality.takeIfSupported(PhotoSettingRequest.QUALITY_SUPER_FINE),
            capabilities.fileFormat.takeIfSupported(PhotoSettingRequest.FILE_FORMAT_JPEG),
        ),
    )

    private fun PhotoSettingSupport.takeIfSupported(request: PhotoSettingRequest): PhotoSettingRequest? =
        request.takeIf { this == PhotoSettingSupport.SUPPORTED }
}
