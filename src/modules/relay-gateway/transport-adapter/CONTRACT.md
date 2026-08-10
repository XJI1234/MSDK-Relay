# relay-gateway.transport-adapter module contract

Status: Approved and implemented
Version: 1.0.0
Parent module: `relay-gateway`
Gradle path: `:relay-gateway:transport-adapter`

This is the only contract, usage guide, public interface description, behavior specification, and acceptance basis for this module.

## 1. Purpose

`transport-adapter` translates exactly one WebSocket library into the `connection-session` transport interfaces. It establishes a WebSocket, writes raw bytes, requests a close, and turns network callbacks into `opened`, `bytes`, `closed`, and `failure` callbacks carrying the supplied `SessionGeneration`.

It is the only `relay-gateway` secondary module permitted to import OkHttp or a concrete WebSocket type. The rest of the gateway talks only to `TransportConnector`, `TransportConnection`, `TransportWriter`, and `TransportListener` from `connection-session`.

## 2. One responsibility

The module is responsible for:

- creating an outbound `ws://` or `wss://` WebSocket connection;
- associating every connection and callback with the caller supplied `SessionGeneration`;
- exposing a `TransportConnection` and byte-only `TransportWriter`;
- forwarding binary WebSocket payloads in receive order;
- converting library failures, rejected writes, rejected closes, and malformed endpoints into stable transport results;
- ensuring duplicate terminal network callbacks cause at most one terminal listener callback;
- using an OkHttp protocol ping interval of 15 seconds to detect a stalled desktop session;
- containing library exceptions and raw network details inside the adapter.

The module is not responsible for:

- JSON, Base64, relay frames, `hello`, `paired`, or protocol validation;
- session state, handshake timeout, reconnect delay, command dispatch, telemetry, mission transfer, or outbound queueing;
- Android lifecycle, permissions, DJI SDK, task files, or UI;
- deciding whether an endpoint setting is persisted or user-editable;
- interpreting a WebSocket text message as a relay message.

## 3. Public interface

```text
OkHttpTransportConnector() -> TransportConnector
```

The returned connector implements the existing `connection-session` seam:

```text
open(endpoint, generation, listener)
  -> OpenAccepted(connection)
   | OpenRejected(safeReason)

connection.generation -> supplied generation
connection.writer.write(bytes)
  -> WriteAccepted | WriteRejected

connection.enableCallbacks()

connection.close(reason)
  -> CloseRequested | AlreadyClosed
```

The application composition root constructs this adapter and gives it to `ConnectionSession`. No business module is allowed to construct, retain, or call an OkHttp `WebSocket`.

## 4. Connection rules

1. `open` never throws a WebSocket, URI, or OkHttp exception to its caller.
2. `open` accepts only syntactically valid `ws://` and `wss://` endpoint URLs. All other input returns `OpenRejected` with a fixed safe reason.
3. Every successful `open` returns a distinct `TransportConnection` with the exact supplied generation.
4. The adapter must not invoke any `TransportListener` callback before `open` has returned `OpenAccepted`. It buffers callbacks that a WebSocket engine emits synchronously.
5. `connection-session` calls `connection.enableCallbacks()` exactly once after it owns the accepted connection. The adapter then delivers buffered callbacks in order; a no-op implementation is valid for a simple test transport.
6. An OkHttp `onOpen` invokes `listener.onOpened(connection)` once after callbacks are enabled.
7. A binary WebSocket message invokes `listener.onBytes(generation, copiedBytes)` once, in library callback order after callbacks are enabled.
8. A text message is not a relay transport payload and is discarded without parsing or closing the connection.
9. `onClosing` requests a normal close but does not itself create a terminal listener event.
10. The first of `onClosed` or `onFailure` invokes the matching listener terminal callback. Later terminal callbacks are discarded.
11. A callback for a connection is always delivered with its own generation. The adapter does not compare generations, decide staleness, or close a newer connection; `connection-session` owns that policy.
12. Incoming byte arrays are copied before delivery. The adapter retains no received payload after callback return.

## 5. Write and close rules

- `write` sends a binary WebSocket message only after this connection has opened and before it becomes terminal.
- The writer copies caller supplied bytes before giving them to the WebSocket library.
- A rejected library send, a library exception, a write before open, or a write after terminal state returns `WriteRejected` and does not throw.
- The adapter does not reorder or queue writes. `outbound-publisher` owns write ordering.
- `close` is idempotent. Its first call requests a normal WebSocket close with a fixed non-sensitive close reason and returns `CloseRequested`; later calls return `AlreadyClosed`.
- A library close rejection or exception still leaves the connection closed from the adapter's perspective and does not throw.
- Explicit `close` does not synchronously invoke the listener. A later network close callback remains the only terminal notification path.

## 6. Error and privacy rules

Only these safe reasons may leave the adapter through `OpenRejected`:

```text
Transport endpoint is invalid
Transport connection could not be opened
```

No public result, callback reason, or thrown exception may expose the complete endpoint, query string, HTTP response, raw payload, close reason, token, device identifier, exception message, or stack trace.

Library callbacks and injected engine implementations may throw. The adapter catches them, sends at most one `onFailure(generation, "Transport failed")` after opening, and never lets the exception cross the library callback boundary.

## 7. Internal test seam

The production connector may use an internal WebSocket engine seam so unit tests can use an in-memory engine. This seam belongs to this module only. It must preserve the public rules above and must not appear in the contracts of `connection-session`, `protocol-core`, `command-dispatcher`, `mission-transfer`, or `outbound-publisher`.

The production engine is OkHttp 4.12.0 with a 15-second ping interval. Changing the concrete WebSocket library or its internal scheduling is allowed only if all public behavior in this contract remains unchanged.

## 8. Acceptance tests

Tests must cover, at minimum:

- valid and invalid endpoint opening without exceptions;
- exact generation propagation and independent connections;
- open callback, binary payload copying, text payload discard, and callback order;
- duplicate close/failure terminal callbacks;
- write before open, normal binary write, rejected/throwing write, and write after terminal;
- idempotent close and rejected/throwing close;
- callback exceptions and listener exceptions cannot escape;
- an engine that calls back synchronously from `open` is buffered until `enableCallbacks`;
- a real local OkHttp WebSocket test covers open, binary receive and send, text discard, and one close terminal callback;
- stale callbacks are forwarded only with their original generation and never affect another connection;
- concurrency around writes, close, and terminal callbacks;
- architecture scan proving this is the sole gateway module with an OkHttp import and it has no protocol, DJI, Android, command, telemetry, or mission dependency.

## 9. Change rule

Any change to the public `TransportConnector` behavior, payload type, callback ordering, close semantics, ping interval, or allowed endpoint scheme requires this contract and its tests to be updated before implementation.
