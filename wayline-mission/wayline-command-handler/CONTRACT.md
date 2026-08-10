# wayline-command-handler module contract

Status: implemented and verified
Version: 1.0.0
Parent module: wayline-mission
Gradle path: :wayline-mission:wayline-command-handler

## 1. Single responsibility

This module validates and interprets the six wayline commands, converts generate fields into a WaylinePlan, and delegates to already-built wayline capabilities. It owns no mission facts and performs no DJI or network operation.

Supported commands are wayline.generate, wayline.upload, wayline.start, wayline.pause, wayline.resume, and wayline.stop. Unknown commands are rejected.

## 2. Public interface

handler.handle(command) -> Succeeded(detail) | Accepted(detail) | Rejected(reason)

Generate requires fileName, waypoints, and speedMetersPerSecond. It validates 2..99 ordered waypoints, longitude -180..180, latitude -90..90, altitude 1..500 meters, and speed 0.1..15.0. A successful generated artifact is passed to MissionStaging and only safe filename, size, and SHA-256 appear in detail.

Upload and control commands require confirm=true. They delegate to WaylineCommandActions. Accepted means the operation was submitted; it is not a claim that DJI has completed it.

## 3. Ownership and failure behavior

The handler does not retain command fields, byte arrays, paths, exceptions, or DJI objects. Generation failure, staging failure, missing fields, wrong types, invalid bounds, false confirmation, and delegated rejection become stable enum reasons. No raw detail is exposed.

handle is synchronous and thread-safe because it owns no mutable state. Concurrent calls are delegated independently; mission-specific serialization belongs to mission-uploader, mission-executor, and the shared operation coordinator.

## 4. Test requirements

Cover each command, valid generation and staging, field/type/boundary failures, unsafe names, false confirmation, delegation, generated metadata detail, delegated rejection, unknown commands, and concurrent independent calls.
