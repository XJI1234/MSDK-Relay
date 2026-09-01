# snapshot-assembler 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`telemetry`
Gradle 路径：`:telemetry:snapshot-assembler`

## 1. 唯一职责

本模块把同一次采样得到的设备、飞行、直播和航线不可变状态组装成一次对外遥测值，并将内部设备能力转换为电脑端稳定的能力值。它不读取 DJI、不维护第二份业务状态、不编码 JSON、不建立网络连接，也不负责持续发布。

## 2. 对外接口

```text
SnapshotAssembler.assemble(TelemetryInputs) -> TelemetrySnapshot
```

`TelemetryInputs` 包含同一次采样的 `DeviceSnapshot`、`FlightTelemetrySnapshot`、`StreamSnapshot` 和 `MissionSnapshot`。输出必须覆盖总契约允许的设备连接、飞行、电量、位置、直播、航线和能力字段。不得包含 DJI 类型、异常、Android 路径、密钥、`DeviceCapabilities` 或内部对象引用。

## 3. 规则

- 一次调用只使用传入的同一组不可变快照，结果不可变。
- 不可用事实使用明确的枚举或空值，不用 `0`、空字符串伪造未知数据。
- 飞行状态、电机状态、飞行模式、电量、低电量返航状态与预估时间、高度和位置来自 `FlightTelemetrySnapshot`；未知值保持 `null`。返航预估只允许状态已知的正秒数；纬度和经度必须同时存在且在合法范围内，否则组装前拒绝输入。
- 当 `DeviceSnapshot.flightController` 为 `DISCONNECTED` 时，飞行源中的最近值已经不能代表当前设备事实；输出必须将全部飞行动态字段（飞行状态、电机、模式、电量、低电量返航状态与预估时间、高度和位置）置为 `null`。重新连接并取得新快照后才可再次输出这些字段。
- 直播状态和指标来自 `StreamSnapshot`；`LiveStreamStatus` 的 `resolution`、`fps`、`vbps`、`packetLoss`、`packetCacheLen` 与 `rtt` 必须使用独立的同名遥测字段透传，未知值保持 `null`，不得编造单位或由其他指标推导；航线执行状态、上传进度和文件名来自 `MissionSnapshot`。上传进度仅在上传中暴露 0..100，已上传时为 100，其他状态为空。
- 当前任务存在时，必须原样带出其正 `missionRevision` 与非负 `missionDeviceGeneration`；没有当前任务时两者都为 `null`。这两个字段与安全文件名和执行终态共同构成桌面端对账条件，不能用快照总 revision、零值或旧缓存替代。
- 内部能力必须通过 `DeviceCapabilityReader` 从同一快照推导，再唯一交给 `CapabilityCalculator` 转换为 `TelemetryCapabilities`；不能在本模块重复维护判断规则或暴露内部能力类型。
- 组装是纯函数：相同输入必须得到相同输出，不产生副作用。

## 4. 测试要求

覆盖初始状态、完整连接状态、配对状态、飞行/电量/位置、直播状态与指标、航线状态/进度/文件名、内部能力到公开能力的转换、全部可选值为空、非法位置拒绝、结果不可变和纯函数重复调用。
