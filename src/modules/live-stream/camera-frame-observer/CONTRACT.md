# camera-frame-observer module contract

Status: approved for the production RTMP observation path.
Gradle path: `:live-stream:camera-frame-observer`

## Single responsibility

`camera-frame-observer` owns only the local, read-only facts that prove whether
the DJI camera media layer has delivered encoded video bytes to the phone during
the current production RTMP stream generation. It never retains a video buffer,
decodes, renders, transmits, configures RTMP, calls `ILiveStreamManager`, or
issues a DJI control command.

This is a production observation module. It has no dependency on the archived
WHIP/WebRTC modules, `encoded-video`, `camera-stream-source`, or any media
transport implementation.

## Public port

```kotlin
CameraFrameObserver.create(port, clock?, staleAfterMillis?) -> CameraFrameObserver

observer.start() -> Started | AlreadyStarted | Failed
observer.stop() -> Stopped | AlreadyStopped | Failed
observer.evaluate(nowMillis) -> CameraFrameSnapshot
observer.snapshot(nowMillis) -> CameraFrameSnapshot
observer.onChanged(listener) -> CameraFrameRegistration
```

The injected `CameraFrameObservationPort` has only two operations: add one
metadata listener, and remove that exact listener. A received frame contains
only byte count and `StreamInfo`-derived metadata: codec, width, height and
reported frame rate. It deliberately has no `ByteArray` field, so neither this
module nor its callers can use it as a second video transport.

`clock` is a phone-local monotonic millisecond clock. It must never be
interpreted as a wall-clock timestamp or compared with desktop time.

## States and generation rules

Each successful `start` creates a new, strictly increasing observer generation
and resets count, metadata and last-frame age. Snapshot state is exactly one of:

| State | Meaning |
| --- | --- |
| `UNAVAILABLE` | No active observation generation: it was explicitly stopped, MSDK/device lifecycle ended, or platform listener registration failed. It is not a claim that the aircraft disconnected. |
| `UNOBSERVED` | An active generation is registered but no valid encoded camera frame has yet arrived. It is not a disconnected state. |
| `RECEIVING` | At least one valid encoded camera frame has arrived within the continuous-frame window. |
| `STALLED` | This generation previously received a valid frame, but no new valid frame arrived before the continuous-frame window elapsed. It means only that no new frame was observed; it must never be translated into a DJI link-disconnected assertion. |

The window uses the received `StreamInfo.frameRate` when valid, but is bounded
to a conservative range. It is used only for frame continuity and never changes
AirLink, Camera, flight-controller, or product connection facts.

A frame is valid only when its byte count is positive, dimensions are positive,
and metadata is structurally valid. Invalid callbacks do not increment a count,
refresh the last-frame time, or alter state. Delayed callbacks from an old
generation are ignored.

`stop` and listener-registration failure make the active generation unavailable
and remove the exact listener identity where possible. Failure to remove is
diagnostic only and cannot resurrect the generation. All public snapshots are
immutable copies.

## Notifications and acceptance

State or source-metadata transitions notify listeners outside internal locks.
Steady high-rate frames update the in-memory fact but do not notify once per
frame, preventing a video-rate telemetry flood. The application may call
`evaluate` periodically to surface a `STALLED` transition.

Tests must cover first frame, continuous frames, invalid frames, stale
generation callbacks, stoppage, platform registration/removal failures,
stalled detection, notification isolation, and immutable snapshots.
