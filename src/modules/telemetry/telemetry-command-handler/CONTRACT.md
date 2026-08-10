# telemetry-command-handler 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`telemetry`
Gradle 路径：`:telemetry:telemetry-command-handler`

## 1. 唯一职责

本模块只负责响应一次 `telemetry.read` 请求，并立即读取当前设备快照、组装一次遥测值、返回结果。它不持续发布、不创建定时器、不解析 WebSocket、不编码协议 JSON、不修改设备状态。

## 2. 对外接口

```text
TelemetryCommandHandler.create(snapshotSource) -> TelemetryCommandHandler
handler.read() -> ReadSucceeded(snapshot) | ReadUnavailable
```

`SnapshotSource` 只提供一个不可变 `DeviceSnapshot`；不允许把 DJI 对象或可变状态集合暴露给本模块。

## 3. 规则

- 一次 `read()` 只调用一次状态源，并把这次读取交给 `SnapshotAssembler`。
- 成功表示已经取得并组装一次快照，不表示设备未来状态不变。
- 状态源抛异常或返回不可用事实时，返回 `ReadUnavailable`，不泄漏异常文本、堆栈或内部对象。
- 重复调用互不影响，每次都读取新的当前快照。
- 业务命令分发器只依赖本模块公开结果，不依赖状态仓库内部实现。

## 4. 测试要求

覆盖首次读取、重复读取、读取到完整状态、读取到停止状态、状态源异常、异常信息隔离和单次读取调用约束。
