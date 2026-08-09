# Protocol Core Repair Design

**Status:** Approved approach, ready for implementation planning
**Date:** 2026-08-09
**Target:** `relay-gateway.protocol-core`

## 1. Objective

Repair `protocol-core` so it is a small, deterministic Kotlin/JVM module with one responsibility:

> Define immutable relay frames and safely encode, decode, and validate one frame at a time.

The repaired module must be understandable without Android, DJI, WebSocket, file-system, session-lifecycle, or mission-transfer knowledge. Every accepted input must satisfy explicit protocol limits, and every rejected input must return a stable protocol error without leaking parser exceptions or allocating unbounded memory.

## 2. Scope

This repair includes:

- aligning the module contract, public types, implementation, and tests;
- bounding encoded frame size and JSON nesting;
- rejecting integers that cannot be represented exactly as a signed 64-bit value;
- enforcing canonical padded Base64 for mission chunks;
- making all identifier, command-name, file-name, result-detail, mission-size, and chunk-size limits explicit;
- testing every frame and every documented boundary;
- removing runtime session and mission-transfer state from `protocol-core`;
- adapting `connection-session` only where it consumes a renamed protocol error.

This repair does not include:

- WebSocket connection or reconnection behavior;
- command routing;
- mission chunk sequencing, hashing, cancellation, staging, or disk access;
- DJI or Android integration;
- changing existing business commands or telemetry contents.

## 3. Responsibility Boundary

Runtime facts have exactly one owner:

| Runtime fact | Owner | `protocol-core` role |
| --- | --- | --- |
| Current connection state, session ID, and generation | `connection-session` | Validate frame structure and protocol version only |
| Current mission transfer, accumulated bytes, and digest | `mission-transfer` | Validate each mission frame independently only |
| Command registration and completion | `command-dispatcher` | Represent command and result frames only |
| Outbound order and current-generation checks | `outbound-publisher` | Encode an already-authorized frame only |

`RelaySessionStateMachine` and `MissionTransferState` therefore leave `protocol-core`. Their behavior is not silently discarded: connection lifecycle is already covered by `connection-session`, while mission sequencing will be specified and implemented with the future `mission-transfer` module.

## 4. Public Interface

The public surface remains deliberately small:

```text
RelayFrame and immutable frame variants
JsonValue, JsonObject, and JsonArray immutable protocol values
validate(frame) -> Accepted(frame) | Rejected(error)
RelayFrameCodec.encode(frame) -> Accepted(bytes) | Rejected(error)
RelayFrameCodec.decode(bytes) -> Decoded(frame) | Rejected(error) | Ignored(type)
ProtocolLimits
ProtocolError and ProtocolErrorCode
```

No public signature may expose Jackson, Android, DJI, WebSocket, file, executor, or mutable collection types. `MissionChunkFrame` must continue to copy bytes on input and output. `JsonObject` and `JsonArray` must continue to copy and expose read-only collections.

## 5. Exact Limits

All limits are protocol facts and must appear in both the contract and `ProtocolLimits`:

| Item | Limit |
| --- | --- |
| UTF-8 bytes in one encoded frame | `98304` bytes (96 KiB) |
| JSON nesting depth | `32` containers |
| JSON number token | `128` characters |
| Generic JSON field name | `1..128` Unicode code points, no control characters |
| Message type | `1..64` Unicode code points, no control characters |
| Device, session, command, and transfer IDs | `1..128` Unicode code points, not blank, no control characters |
| Command name | `1..64` Unicode code points, not blank, no control characters |
| Mission file name | `1..128` Unicode code points |
| Result detail | `0..1024` Unicode code points, no control characters |
| Mission file size | `1..104857600` bytes |
| Decoded mission chunk | `1..49152` bytes |
| Encoded mission chunk data | at most `65536` Base64 characters |
| Protocol error message | `1..256` Unicode code points, no control characters |

The 96 KiB frame limit safely contains the current 48 KiB chunk after Base64 expansion, the bounded transfer ID, and JSON framing. It also bounds all generic telemetry and command objects without imposing business-specific schemas in this module.

## 6. Decode Rules

Decoding follows this order:

1. Reject an empty frame or a frame larger than 96 KiB before UTF-8 conversion or JSON parsing.
2. Decode UTF-8 with malformed and unmappable input reporting enabled.
3. Parse one JSON value with duplicate-field detection, trailing-token rejection, a nesting depth of 32, and a number-token length of 128.
4. Require a JSON object and a valid bounded textual `type`.
5. Return `Ignored(type)` for a valid but unknown type without interpreting its other fields.
6. For a known type, read required fields without type coercion and construct an immutable frame.
7. Run frame validation and return `Decoded` only when every field is valid.

Known frames may contain additional fields so v1 can receive compatible optional extensions. Duplicate fields remain invalid.

An integral JSON number is accepted as a `Long` only if it fits exactly in the signed 64-bit range. Values outside that range are rejected; truncation or wraparound is forbidden.

Decode failures use these stable mappings:

| Condition | Error |
| --- | --- |
| Empty bytes, malformed JSON, trailing tokens, excessive depth, or excessive number-token length | `INVALID_JSON` |
| More than 96 KiB before parsing | `FRAME_TOO_LARGE` |
| Invalid UTF-8 | `INVALID_UTF8` |
| Missing field, wrong field type, or an integer outside signed 64-bit range | `INVALID_FIELD` |
| Blank, controlled, or overlong `type` | `INVALID_MESSAGE_TYPE` |
| Unsupported explicit protocol version | `PROTOCOL_VERSION_UNSUPPORTED` |

## 7. Base64 Rules

Mission chunk data uses RFC 4648's standard alphabet with canonical padding:

- only `A-Z`, `a-z`, `0-9`, `+`, `/`, and final `=` padding are allowed;
- encoded length must be divisible by four;
- omitted padding, whitespace, URL-safe alphabet, misplaced padding, and invalid characters are rejected;
- an encoded value longer than 65536 characters is rejected before decoding;
- decoded data must contain 1 through 49152 bytes;
- re-encoding decoded bytes must reproduce the exact original text.

This remains compatible with the desktop application's standard Base64 encoder.

Base64 error mapping is fixed: malformed or non-canonical text returns `INVALID_BASE64`; decoded zero bytes returns `EMPTY_CHUNK`; encoded or decoded data above the chunk limit returns `CHUNK_TOO_LARGE`.

## 8. File-Name Rules

A mission file name is valid only when it:

- is a basename rather than a path;
- is not blank and contains no control character;
- contains neither `/`, `\\`, nor `..`;
- ends in `.kmz` using ASCII case-insensitive comparison;
- stays within 128 Unicode code points.

The module validates only the protocol-safe name. It does not choose a storage path or determine whether the KMZ is a valid DJI mission.

## 9. Error Semantics

The protocol error vocabulary must match the documented behavior. It includes structural and field errors only. Runtime lifecycle and mission-transfer errors belong to their owning modules.

Required protocol errors are:

```text
FRAME_TOO_LARGE
INVALID_UTF8
INVALID_JSON
INVALID_FIELD
INVALID_BASE64
PROTOCOL_VERSION_UNSUPPORTED
INVALID_DEVICE_ID
INVALID_SESSION_ID
INVALID_MESSAGE_ID
INVALID_MESSAGE_TYPE
INVALID_COMMAND_NAME
INVALID_FILE_NAME
INVALID_SHA256
MISSION_SIZE_OUT_OF_RANGE
EMPTY_CHUNK
CHUNK_TOO_LARGE
INVALID_RESULT_DETAIL
```

Errors contain only a code and a short, bounded, non-sensitive message. They never contain raw JSON, Base64 content, file paths, identifiers, credentials, parser messages, or stack traces.

Only this module constructs `ProtocolError`. Other modules may inspect returned errors but cannot forge protocol validation results for their own runtime failures.

Gateway-wide errors such as `NOT_CONNECTED`, `HANDSHAKE_TIMEOUT`, `TRANSFER_ALREADY_ACTIVE`, and `TRANSFER_SUPERSEDED` remain defined by their owning gateway modules; they are not `protocol-core` validation errors.

## 10. Encode Rules

Encoding must first validate the frame and its nested generic JSON values. It then emits canonical field names and UTF-8 JSON. The result is rejected if:

- a frame field violates a documented limit;
- a nested JSON value exceeds depth 32;
- a generic JSON object contains a blank, controlled, or overlong field name;
- a `JsonNumber` is not a valid JSON number;
- encoded output exceeds 96 KiB;
- any implementation dependency throws while encoding.

No partially encoded bytes are returned after failure.

Encoding maps an oversized result to `FRAME_TOO_LARGE`; an invalid nested JSON value to `INVALID_JSON`; and an invalid frame field to that field's specific validation error.

## 11. Testing Strategy

Tests remain pure JVM tests and are organized by responsibility:

- frame validation tests cover every minimum, maximum, just-below, and just-above boundary;
- codec round-trip tests cover all nine frame types;
- malformed-input tests cover empty input, invalid UTF-8, invalid JSON, duplicate fields, trailing tokens, wrong field types, oversized frames, excessive nesting, and oversized numbers;
- Base64 tests cover minimum and maximum chunks, empty data, excess decoded size, missing padding, bad padding, whitespace, URL-safe characters, and invalid characters;
- file-name tests cover both separators, traversal, controls, blank names, extension casing, wrong extensions, and Unicode code-point length;
- compatibility tests cover `paired` with explicit v1 and with the version omitted;
- seeded randomized byte tests assert that thousands of arbitrary inputs always return a `DecodeResult` and never leak an exception;
- dependency scans assert that main code has no Android, DJI, WebSocket, network, or file-system imports;
- `connection-session` tests must continue passing after the protocol error rename.

Deleted state-machine tests are replaced by ownership-appropriate coverage: existing `connection-session` tests remain authoritative for sessions, and mission sequencing tests will be required by the future `mission-transfer` contract.

## 12. Acceptance Criteria

The repair is complete only when:

1. `protocol-core` contract, public API, implementation, and tests describe the same responsibility and limits;
2. the oversized-integer probe is rejected instead of becoming `size=1`;
3. overlarge frames and overdeep JSON are rejected before unbounded processing;
4. non-canonical Base64 is rejected;
5. no runtime session or transfer state remains in `protocol-core`;
6. all focused and repository-wide tests pass with zero failures and zero skipped tests;
7. `git diff --check` passes;
8. forbidden dependency scans return no matches.
