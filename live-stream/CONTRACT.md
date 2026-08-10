# live-stream module contract

Status: second-level implementation in progress
Version: 1.0.0
Gradle path: :live-stream

## 1. Single responsibility

`live-stream` owns RTMP live-stream configuration, DJI stream start/stop coordination, and immutable live-stream state exposed to telemetry. It does not receive, transcode, play, or store video; the video path remains DJI SDK to the computer's RTMP service.

Second-level modules:

| Module | Sole responsibility |
| --- | --- |
| `stream-config-validator` | Validate an RTMP stream configuration and produce a safe validated value. |
| `dji-stream-adapter` | Adapt the validated stream operations to the DJI SDK through the shared operation coordinator. |
| `stream-state-store` | Own live-stream state and metrics and publish immutable snapshots. |
| `stream-command-handler` | Interpret `live-stream.start` and `live-stream.stop`, invoke the stream capability, and map results to relay-safe outcomes. |

## 2. Ownership rules

Only `stream-state-store` owns live-stream facts. Only `dji-stream-adapter` may call DJI live-stream methods. All DJI calls use `device-connection`'s `dji-operation-coordinator`. The command handler owns no state and the validator owns no state.

## 3. Required behavior

- `live-stream.start` must validate the RTMP URL before any DJI call.
- A stream is reported as active only after DJI confirms start success.
- Stop, start failure, timeout, cancellation, and device disconnect produce stable inactive state and a safe notice.
- Duplicate or late DJI callbacks cannot change a newer stream state or complete a relay command twice.
- No public result contains a password, token, filesystem path, raw exception, or DJI object.

Each second-level module must have its own `CONTRACT.md`, JVM tests for pure rules and adapter edges, and device tests for actual DJI behavior before the parent facade is composed.
