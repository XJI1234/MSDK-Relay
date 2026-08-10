# wpmz-generator module contract

Status: implemented and verified
Version: 1.0.0
Parent module: wayline-mission
Gradle path: :wayline-mission:wpmz-generator

## 1. Single responsibility

This module converts a complete, already-decided waypoint plan into a valid DJI-compatible WPMZ/KMZ byte sequence and safe metadata.

The computer side owns route planning, map editing, waypoint selection, camera-action policy, and user-facing naming. This module does not plan routes, alter waypoint order, upload, execute, access DJI objects, access Android files, or send network traffic. It does not write to mission-staging; the composition layer passes its result to that module.

## 2. Public interface

WpmzGenerator.create() -> WpmzGenerator
generator.generate(plan) -> Generated(artifact) | Rejected(reason)

Input:

WaylinePlan(
  fileName: safe .kmz basename, 1..255 characters
  waypoints: 1..10,000 ordered points
  speedMetersPerSecond: finite number in 0.1..15.0
)
Waypoint(
  longitude: finite number in -180.0..180.0
  latitude: finite number in -90.0..90.0
  altitudeMeters: finite number in 0.0..10,000.0
)

Output:

Generated(
  artifact.fileName
  artifact.bytes              // a complete KMZ archive, never a path
  artifact.sha256              // lowercase 64-character SHA-256
  artifact.sizeBytes           // exact byte count
)
Rejected(reason)

The archive must contain exactly the generated mission documents under:

wpmz/template.kml
wpmz/waylines.wpml

waylines.wpml uses DJI namespace http://www.dji.com/wpmz/1.0.6, preserves waypoint order, writes longitude/latitude/altitude, writes the requested speed at document and waypoint level, and XML-escapes all text values.

## 3. Lifecycle and ownership

The generator is stateless after create(). A successful call returns a self-contained artifact; the generator retains no plan or bytes. The caller owns the returned bytes and must hand them to mission-staging.

Calls are synchronous and bounded by the 10,000-waypoint limit. The module has no Android lifecycle, no connection lifecycle, no callback registration, and no cancellation handle. A caller that needs background execution or cancellation owns that policy outside this module.

## 4. Validation and failure behavior

- Blank names, path separators, control characters, names longer than 255 characters, and non-.kmz names return INVALID_PLAN.
- Empty plans, more than 10,000 points, non-finite coordinates, out-of-range coordinates, non-finite altitude, out-of-range altitude, non-finite speed, and out-of-range speed return INVALID_PLAN.
- A ZIP/XML generation failure returns GENERATION_FAILED; the result contains no exception, path, partial archive, or input bytes.
- Invalid input never invokes the encoder and never produces bytes.
- Generation is deterministic for the same input except for ZIP metadata that is not exposed as business state; entry names and XML content are stable.
- The returned byte array is defensively copied when exposed. A caller cannot mutate the artifact held by the result.
- Concurrent calls are independent and must not corrupt one another.

## 5. Integration seam

The module depends only on Kotlin/JVM and standard library ZIP/XML escaping. It exposes no Android, DJI SDK, WebSocket, filesystem, or thread-pool types. The generated artifact is consumed by mission-staging; device validation and upload are owned by mission-uploader.

## 6. Test requirements

JVM tests must cover a valid one-point plan, waypoint order, requested speeds, archive entry names, XML escaping, all numeric boundaries, invalid metadata, invalid coordinates, invalid counts, non-finite values, deterministic metadata, no output on rejection, defensive byte-array copying, and concurrent generation.

## 7. Change rules

Changing the archive entry names, namespace, numeric limits, input units, XML semantics, or output metadata requires updating this contract and the computer/mobile integration contract before implementation changes.
