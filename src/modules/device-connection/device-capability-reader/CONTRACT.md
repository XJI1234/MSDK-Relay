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

- `canStartPairing`：SDK `READY`、遥控器连接、飞控明确断开，且配对为 `UNKNOWN`、`IDLE`、`FAILED` 或 `STOPPING`。飞控已连接或状态未知时必须为假。停止对频后必须仍可再次开始。
- `canStopPairing`：配对为 `PAIRING`、`PAIRED` 或 `STOPPING`。
- `canReadTelemetry`：SDK `READY`、飞控连接。
- `canStreamVideo`：SDK `READY`、AirLink Key 已连接且所选主相机 Key 已连接。它不依赖产品 Key、飞控连接、遥测、电量、航线或对频状态。它只授权请求 DJI 开始图传；`startStream` 成功和 `LiveStreamStatus.isStreaming` 才分别表示 DJI 已接受并正在推流。
- `canRunWayline`：SDK `READY`、遥控器连接和飞控连接。对频是否完成不进入这条能力；电脑按对频状态单独拦截启动。
- 所有字段独立按同一个输入快照推导；输入快照不会被修改。异常或未知状态只能收紧能力，不能放宽能力。`ProductKey.KeyConnection` 仍在输入快照中作为原始诊断事实保留，但本模块不得读取它或用它授权、拒绝任何能力。

## 4. 测试要求

必须覆盖初始状态、每个前置条件单独缺失、每个配对状态、完整就绪状态、输入不可变性和能力之间不互相污染。
