# Photo Profile Contract

`photo-profile` is a pure Kotlin module. It contains no Android or DJI SDK types and has no dependency on either platform.

`PhotoProfileResolver.resolve(capabilities)` produces requests only for settings whose capability is explicitly `SUPPORTED`. `UNKNOWN` and `UNSUPPORTED` produce no write.

The resolver considers only these capability facts: ratio, resolution, photo size, quality, and file format. It emits supported requests in this stable order: ratio, resolution, size, quality, file format.

The quality request is named `QUALITY_SUPER_FINE` at this platform-neutral boundary. The Android DJI adapter maps that request explicitly to DJI's `CameraPhotoQuality.SFINE` value.

The module is registered directly in Gradle but is intentionally not exposed through the `:camera-photo` facade. The facade would have no consumer for this profile and would create an unused dependency; the Android DJI photo adapter is the sole direct consumer and depends on this module directly.
