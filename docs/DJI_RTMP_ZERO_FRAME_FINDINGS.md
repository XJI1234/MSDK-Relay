# DJI RTMP Zero-Frame Findings

## Purpose

This document is the evidence ledger for the DJI native RTMP zero-frame incident. It distinguishes facts established for a specific failed attempt from hypotheses. Do not reopen an excluded item without new contradictory runtime evidence.

## Confirmed Failed Attempt: 2026-09-28 11:29 China Standard Time

- App process: `28091`; diagnostic run: `e5cb44fe-2591-4173-8e57-66080b6e294e`.
- The newly installed APK was active: it emitted `CAMERA_ZERO_FRAME_MEDIA_STATE_BEFORE_RECOVERY`, an event absent from earlier APKs.
- `live-stream.start` completed successfully in 104 ms. MRTC subsequently reported `readyStatus=1`, `isOnline=true`, and `isStreaming=true`.
- Starting at 11:29:52, MRTC repeatedly reported `fps=0`, `bps=0`, and `packetCacheLen=0`.
- MRTC's configured adaptive output was `1280x720`, `30 fps`, `524288 bps`, with `enableAdaptiveResolution=1`.
- Before any zero-frame recovery, the application recorded `cameraMode=VIDEO_NORMAL;playingBack=false`.
- The one permitted zero-frame media recovery ran and reported success. MRTC remained at `fps=0,bps=0` after that recovery.

## Excluded for This Attempt

| Item | Why it is excluded |
| --- | --- |
| Wrong or stale APK | The process emitted the new pre-recovery camera-media diagnostic event. |
| Desktop RTMP server, desktop decoder, or renderer | DJI MRTC itself reported zero encoded frames and zero bitrate before the desktop could consume a video packet. The RTMP session was online and streaming. |
| A rejected or uninvoked MRTC start | `live-stream.start` completed successfully, and MRTC reached `readyStatus=1` and `isStreaming=true`. |
| Camera left in `PHOTO_NORMAL` | The pre-recovery `KeyCameraMode` read returned `VIDEO_NORMAL`. |
| Camera media playback left active | The pre-recovery `KeyIsPlayingBack` read returned `false`. |
| A stranded media-manager playback session | A single `IMediaManager.disable()` recovery completed successfully but did not restore frames. |
| The current 1080p request versus MRTC's adaptive 720p output | The same MRTC output attribute, `1280x720/30/524288/adaptive=1`, occurred in a historical successful session that continuously reported 23-24 fps and sent frames. It prevents a true 1080p claim but does not explain zero frames. |
| The `JNI_ERR` line from `libmrtc_core_jni.so` | It was also present in historical successful sessions; DJI's MRTC static initializer catches that load error and continues. |

## What the Failure Means

The failed path is upstream of RTMP publishing and desktop display:

```text
aircraft camera video source
  -> DJI StreamEncoder / MRTC video input   (no frames observed)
  -> RTMP publisher
  -> desktop RTMP server and renderer
```

The public MSDK calls completed, the RTMP session connected, and the camera was in normal video mode outside media playback. The unresolved fault is DJI's camera-video-source to MRTC-encoder-input path or another DJI internal session condition not exposed by the currently observed public keys.

## Aircraft Power-Cycle Control Comparison

The aircraft was restarted after the failed attempt without restarting the Android process, reinstalling the APK, changing the desktop server, or changing the RTMP configuration.

- The same Android process (`28091`) and diagnostic run continued throughout the comparison.
- At 11:32:34, the aircraft shutdown changed `CameraKey.KeyConnection` and `AirLinkKey.KeyConnection` from `true` through `false` to unknown. The application stopped the generation-1 camera observer; it had received zero frames.
- At 11:32:53, AirLink reconnected. At 11:33:01, the main camera connection became `true`.
- At 11:33:29, the identical native RTMP start path completed successfully for attempt 2. At 11:33:30, the generation-2 observer received `H264 1920x1080`. MRTC's first status was `13 fps`; subsequent statuses remained at 23-24 fps.

Therefore, a complete aircraft power-cycle is sufficient to clear the failed state. It proves the failed state is not held solely in the Android process, desktop RTMP server, or their configuration. It does not identify which aircraft-side subsystem was reset: camera capture, camera-to-AirLink forwarding, or an undocumented DJI media session are still indistinguishable from public MSDK evidence.

## Public MSDK Reset Gap

MSDK 5.17's `ILiveStreamManager` exposes configuration, `startStream`, `stopStream`, state getters, and listener registration only. It has no documented `reset`, `restart`, `release`, or encoder-reinitialization API.

`ICameraStreamManager.enableStream(cameraIndex, enable)` is the only public operation close to a video-source reset. DJI documents it only as enabling or disabling the stream (supported since MSDK 5.15), and its sample exposes it as a manual open/close control. DJI does not document it as a zero-frame recovery, does not provide a completion callback, and the retrieved documentation does not establish Mini 4 Pro support for this use. It is a test candidate, not an approved production recovery. The frame observer and normal RTMP startup must continue to avoid calling it.

Official references: [ILiveStreamManager](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ILiveStreamManager.html) and [ICameraStreamManager.enableStream](https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html#icamerastreammanager_enablestream_inline).

## MSDK Flight-Controller Recovery Gate

This section records the static audit of the locally cached DJI MSDK V5.17.0
implementation. DJI does not publish the Java/Kotlin source for this component;
the findings below come from the SDK's shipped/compile-visible bytecode and are
not an invented application contract.

`CameraStreamManager.updateAllCameraStream()` creates an
`AircraftStreamSource` and attaches it to a `StreamObserver`. The source installs
a `DJIVideoManager` video observer and schedules one five-second no-data check.
`AircraftStreamSource$1.handleMessage()` calls the source state callback once if
no video bytes were received during that check.

That callback is wired to `CameraStreamManager.connectStreamSource()`. Before
performing its source reset/reconnect operation, that method reads
`FlightControllerKey.KeyConnection` with a default of `false` and returns
immediately when the value is false. It does not queue the reconnect for a later
flight-controller transition.

`CameraStreamManager.init()` listens to product type, available video source,
live video source, RC mode, playback state, camera connection, and vision-assist
keys. It does not listen to `FlightControllerKey.KeyConnection`. Consequently,
if the five-second no-data callback occurs while the flight controller is
disconnected, a later flight-controller reconnection does not itself rerun the
failed source recovery. An unrelated camera/AirLink/source event or a complete
aircraft restart may cause `updateAllCameraStream()` to run again.

The resulting state transition is:

```text
cold MSDK init while FlightControllerKey.KeyConnection=false
  -> initial AircraftStreamSource exists but receives no bytes
  -> one-shot five-second no-data callback
  -> connectStreamSource() returns at the flight-controller gate
  -> flight controller later becomes true, but no SDK listener retries
  -> LiveStreamManager/MRTC can remain online with fps=0 and bps=0
```

This explains why the observed recovery succeeds when the flight controller is
already connected, and why the exact combination of power-save disconnect plus
Android process restart is different from either condition alone.

## First-Frame Boundary

The flight-controller gate explains why a first no-frame state becomes stranded;
it does not, by itself, prove why the aircraft produces no bytes during the
initial source start. The current evidence establishes the following boundary:

- `updateAllCameraStream()` can create and start the source before any raw H.264
  callback is observed. `mainAvailable=true` and `mainEnabled=true` therefore do
  not prove that `DJIVideoManager` has delivered data.
- The five-second timeout is the first MSDK code path that explicitly observes
  the absence of bytes and attempts recovery.
- `ICameraStreamManager.addReceiveStreamListener()` only registers the listener
  with `StreamEncoder`. Its bytecode does not call `updateAllCameraStream()`,
  `enableStream()`, a camera-mode key, or a media-manager operation.
- The application's read-only `CameraFrameObserver` therefore cannot explain the
  initial no-frame condition by changing DJI state. It can observe the same
  missing input, but it is not the source reset trigger.
- The aircraft power-save transition can leave the AirLink/live-video channel
  advertised as available while the underlying aircraft-to-video input is not
  producing bytes. Whether that first missing input is camera capture, AirLink
  forwarding, or DJI native source binding remains opaque because those layers
  are closed.

The current static conclusion is therefore deliberately split:

1. **Confirmed MSDK lifecycle defect:** the no-frame recovery path is gated on
   flight-controller connectivity but has no flight-controller recovery listener
   or retry.
2. **Not yet source-proven:** the aircraft-side/native reason that the newly
   initialized source receives zero bytes after power-save mode.
3. **Not supported by the audit:** the read-only frame observer, photo mode,
   1080p request, desktop RTMP path, or a second application H.264 transport
   causing the first no-frame state.

The same `CameraStreamManager` flight-controller gate and missing listener were
present in the locally cached DJI MSDK 5.18.0 bytecode, so a version bump alone
is not evidence of a fix.

## Required Evidence for Future Attempts

For every future zero-frame claim, preserve the corresponding diagnostic run and check these events before changing code:

1. `LIVE_STREAM_START_OK` and the first MSDK status.
2. `CAMERA_FIRST_FRAME` or its absence.
3. `CAMERA_ZERO_FRAME_MEDIA_STATE_BEFORE_RECOVERY`.
4. `CAMERA_ZERO_FRAME_RECOVERY_STARTED` and its terminal event.
5. MRTC `isOnline`, `isStreaming`, `fps`, `bps`, and configured video attributes.

Only a new observation that differs from this sequence may justify reopening one of the excluded items above.

## Official Sample Cross-Check

The local checkout of DJI's official `Mobile-SDK-Android-V5` repository is at
commit `a48aa4e7811d824c27abfa973f5655579bfb8a77` (`dev-sdk-main`, dated
2026-06-03). Its SampleCode-V5 project uses MSDK `5.18.0` and the standard
live-stream path:

```text
LiveStreamVM.startStream()
  -> ILiveStreamManager.startStream()
LiveStreamVM.addListener()
  -> addLiveStreamStatusListener()
  -> cameraStreamManager.addAvailableCameraUpdatedListener()
```

The sample has no photo-transfer module, no application zero-frame recovery,
and no second RTMP/H.264 transport. Its live page uses the documented
`CameraStreamManager` surface path and the documented `ILiveStreamManager`
settings/start/stop path. Therefore, reproducing the same no-frame state in
that sample is strong comparative evidence against this application's photo
logic, observer, RTMP state machine, and desktop renderer.

DJI's public issue tracker contains matching independent evidence:

- Issue [#641](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/641)
  reports a Mini 4 Pro on MSDK 5.16 where the documented
  `CameraStreamManager` setup succeeds but `onReceiveStreamData()` never
  fires. DJI support directs the reporter to the official sample and standard
  `addReceiveStreamListener` tutorial; no SDK fix is recorded in the issue.
- Issue [#817](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/817),
  opened 2026-09-28, reports Mini 4 Pro on MSDK 5.18 where the official
  sample's media playback reports `PLAYING` while the surface is black or
  freezes. This is a different playback path, so it is corroboration of
  state-dependent DJI video-source failures, not proof of the RTMP path by
  itself.

The attribution is consequently split:

1. **Confirmed vendor SDK defect:** MSDK's no-data recovery path checks
   `FlightControllerKey.KeyConnection` and returns when it is false, but does
   not retry when that key later becomes true. The same structure is present
   in the locally inspected 5.17.0 and 5.18.0 bytecode.
2. **Strongly supported vendor-side failure:** the official sample can reach
   the same connected/streaming/zero-frame state under the same aircraft and
   lifecycle sequence. This rules out an application-specific cause with high
   confidence.
3. **Not formally proven by public evidence:** why the aircraft/native video
   source first produces no bytes after power-save disconnect plus cold MSDK
   initialization. DJI's Java source and native video-source implementation
   are closed, and DJI has not publicly acknowledged this exact trigger.

Use the precise wording **"DJI MSDK video-source lifecycle defect, with an
unresolved aircraft/native first-frame trigger"**, rather than claiming that
the entire aircraft firmware or every zero-frame case has been proven faulty.
