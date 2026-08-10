# stream-command-handler module contract

Status: approved for implementation
Version: 1.0.0
Parent module: live-stream
Gradle path: :live-stream:stream-command-handler

## 1. Single responsibility

This module interprets `live-stream.start` and `live-stream.stop`, validates command fields through `stream-config-validator`, and delegates accepted operations to injected stream actions.

It does not call DJI, open RTMP sockets, own stream state, publish telemetry, parse WebSocket frames, or expose parser/DJI exceptions.

## 2. Public interface

```text
handler.handle(command) -> Accepted | Succeeded | Rejected(reason)
handler.handle(command, completion) -> Accepted | Succeeded | Rejected(reason)
```

Start accepts exactly one string field `rtmpUrl`; stop accepts no fields. Start configuration is validated before `StreamCommandActions.start` is called. An accepted action means submitted only. Its `StreamActionCompletion` reports terminal `SUCCEEDED`, `FAILED`, `TIMED_OUT`, or `CANCELLED` to the parent facade. Generation of no success result occurs at submission time.

## 3. Stable failures and concurrency

Unknown commands, wrong/missing fields, invalid RTMP configuration, capability precondition failures, and malformed action results become stable enum reasons. No raw URL, password/token, exception, or DJI value appears in a rejection. The handler is stateless and thread-safe; operation serialization belongs to `dji-stream-adapter` and the shared coordinator.

## 4. Tests

Tests cover both commands, exact field/type checking, all validator failure classes, action delegation, action rejection, accepted-versus-terminal timing, duplicate terminal callback behavior at the composition boundary, unknown commands, and concurrent independent reads.
