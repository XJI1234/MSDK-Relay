# telemetry 一级模块契约

状态：已实施并已验证
版本：1.0.0
所属程序：MSDK Relay Android
Gradle 路径：`:telemetry`

## 1. 唯一职责

`telemetry` 将其他模块已经公开的不可变状态转换为电脑端可读取的一次快照，并在状态变化时安全发布。它不拥有设备、航线或直播的业务事实，不直接调用 DJI，也不直接管理 WebSocket。

## 2. 二级模块

| 二级模块 | 唯一职责 |
| --- | --- |
| `snapshot-assembler` | 从同一次采样的设备、飞行、直播和航线快照产生安全遥测值，并组合公开能力值 |
| `capability-calculator` | 将内部设备能力转换为电脑端稳定能力字段 |
| `telemetry-command-handler` | 提供一次性 `telemetry.read` 结果 |
| `telemetry-publisher` | 去重、失败重试和发送顺序 |
| `android-flight-telemetry-adapter` | 观察 DJI 飞行、电量与位置键，并提供原子只读飞行遥测快照 |

## 3. 对外接口

```text
Telemetry.create(source, sink) -> Telemetry
telemetry.start() -> Started | AlreadyStarted
telemetry.stop() -> Stopped | AlreadyStopped
telemetry.read() -> ReadSucceeded(snapshot) | ReadUnavailable
```

## 4. 规则

- `source` 是组合根提供的只读 `TelemetryStateSource`，负责在一个采样边界返回设备、飞行、直播和航线快照，并在任一来源变化时通知。telemetry 不持有或修改这些事实。
- `start()` 订阅统一状态源，并对每次有效状态事件重新采样后尝试发布；重复启动不得重复订阅。
- `stop()` 注销订阅并重置发布去重基线；停止后不得因已经排队的旧事件发布。
- 启动不补发历史状态；连接建立后需要立即完整快照时，组合根显式调用当前发布接口。
- 即时读取和持续发布都使用同一个 `SnapshotAssembler`，不存在两套字段规则。
- `TelemetrySnapshot.capabilities` 只能是 `TelemetryCapabilities`，不得暴露 `DeviceCapabilities` 或其他设备连接层内部类型。
- sink 失败不影响状态仓库或后续状态变化。

## 5. 测试要求

覆盖启动/停止幂等、四类来源任一变化发布、同一组合快照去重、停止后不发布、重启后重新发布、即时读取、采样失败、sink 失败和监听器并发注销。
