# device-capability-reader 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`device-connection`
Gradle 路径：`:device-connection:device-capability-reader`

## 1. 唯一职责

本模块只根据一个不可变 `DeviceSnapshot` 推导手机端当前允许请求的能力。它不读取 DJI、不修改状态、不排队操作、不监听网络，也不把“允许请求”误认为“操作已经成功”。

## 2. 对外接口

```text
DeviceCapabilityReader.read(snapshot) -> DeviceCapabilities
```

`DeviceCapabilities` 固定包含：

```text
canStartPairing
canStopPairing
canReadTelemetry
canStreamVideo
canRunWayline
```

## 3. 推导规则

- `canStartPairing`：SDK `READY`、遥控器连接、飞行器断开，且配对为 `UNKNOWN` 或 `IDLE`。
- `canStopPairing`：配对为 `PAIRING`、`PAIRED` 或 `STOPPING`。
- `canReadTelemetry`：SDK `READY`、飞行器连接、飞控连接。
- `canStreamVideo`：SDK `READY`、飞行器连接。
- `canRunWayline`：SDK `READY`、遥控器连接、飞行器和飞控连接、配对为 `PAIRED`。
- 所有字段独立按同一个输入快照推导；输入快照不会被修改。异常或未知状态只能收紧能力，不能放宽能力。

## 4. 测试要求

必须覆盖初始状态、每个前置条件单独缺失、每个配对状态、完整就绪状态、输入不可变性和能力之间不互相污染。
