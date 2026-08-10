# LiveStream facade contract

Status: approved for implementation
Version: 1.0.0
Parent module: live-stream
Gradle path: :live-stream

## 1. Single responsibility

`LiveStream` is the composition facade for mobile RTMP streaming. It connects relay commands to configuration validation, DJI adaptation, and the sole stream state store.

It does not receive or play video, open a network socket, call DJI directly, own a second state copy, or implement command parsing rules that belong to `stream-command-handler`.

## 2. Public interface

```text
LiveStream.create(dependencies) -> LiveStream
liveStream.commandHandler() -> relay-gateway CommandHandler
liveStream.snapshot() -> StreamSnapshot
liveStream.onChanged(listener) -> Registration
```

`live-stream.start` reports relay success only after DJI start completion succeeds. `live-stream.stop` follows the same rule. Accepted submission, timeout, cancellation, adapter failure, and stale/duplicate callbacks produce one safe relay result and the corresponding state store transition.

## 3. Dependencies and ownership

Dependencies are the DJI stream port, shared `DjiOperationCoordinator`, bounded operation timeout, and optional state diagnostic sink. The facade creates and owns one `StreamStateStore`, `DjiStreamAdapter`, and `StreamCommandHandler`; injected objects remain owned by the caller.

The public snapshot contains no RTMP URL or credential. State listener delivery follows `stream-state-store` guarantees. All DJI calls remain behind the adapter and shared coordinator.

## 4. Required tests

Integration tests cover valid start/stop terminal completion, failure and timeout, duplicate callbacks, invalid URL rejection before DJI, stop precondition rejection, state observation, and command completion exactly once.
