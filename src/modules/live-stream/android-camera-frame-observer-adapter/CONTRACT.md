# android-camera-frame-observer-adapter module contract

Status: approved for the production RTMP observation path.
Gradle path: `:live-stream:android-camera-frame-observer-adapter`

## Single responsibility

This Android adapter implements `CameraFrameObservationPort` with DJI MSDK
5.17 `MediaDataCenter.getInstance().cameraStreamManager` and the production
camera index `ComponentIndexType.LEFT_OR_MAIN`. It maps each
`ReceiveStreamListener.onReceiveStream(data, offset, length, StreamInfo)` to
metadata only and discards the byte array immediately.

It neither feeds RTMP nor renders/decodes video, creates a Surface, calls
`ILiveStreamManager`, changes camera settings, or imports the archived
WHIP/WebRTC or experimental camera-source modules.

## Lifecycle rules

- It registers with `addReceiveStreamListener(LEFT_OR_MAIN, exactListener)`.
- It removes the exact SDK listener through MSDK's
  `removeReceiveStreamListener(listener)` overload.
- It preserves the identity mapping between platform-neutral and SDK listeners.
- A callback after removal is ignored.
- Synchronous MSDK exceptions are propagated to the core observer, which
  converts them to its stable non-sensitive failure state.
- It reports metadata only for a frame slice with `offset >= 0`, `length > 0`,
  and `offset + length <= data.size`; malformed callbacks are ignored.

The adapter must compile against DJI MSDK 5.17 and tests must verify exact
listener removal, codec mapping, malformed-range rejection and delayed callback
isolation.
